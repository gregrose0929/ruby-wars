package dev.rubymod.game.party;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.game.Duels;
import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/**
 * Tank Battle: Red against Blue, everyone in a tank, on the vehicle arena (hills,
 * ruins, trees). Knocked out? A fresh tank in 5 seconds. First team to 10 kills wins.
 */
public class TankBattle extends PartyGame {
	private static final int WIN = 10;
	private static final int ROUND_TICKS = 8 * 60 * 20;
	private final int[] kills = new int[2];
	/** Each player's tank, and ticks until they climb in. */
	private final Map<UUID, UUID> tanks = new HashMap<>();
	private final Map<UUID, Integer> boardIn = new HashMap<>();

	public TankBattle() {
		super("tanks", "Tank Battle", "Red vs Blue in tanks. First team to 10 knockouts wins!", "rubymod:tank", 2, false, true, true);
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(-65000, GROUND + 1, 0);
	}

	@Override
	protected int reach() {
		return 150;
	}

	/** A fresh arena every round (craters filled, walls back up), built a little at a time. */
	@Override
	protected void build(ServerLevel level) {
		Duels.ArenaBuilder.buildQueued(level, Duels.Mode.VEHICLES, center(), this::later);
	}

	@Override
	protected String roundStatus() {
		int left = Math.max(0, ROUND_TICKS - roundTicks) / 20;
		return "Red " + kills[0] + " - " + kills[1] + " Blue  •  " + left / 60 + ":" + String.format("%02d", left % 60);
	}

	@Override
	protected float barProgress() {
		return Math.max(kills[0], kills[1]) / (float) WIN;
	}

	@Override
	protected void startRound() {
		kills[0] = 0;
		kills[1] = 0;
		tanks.clear();
		boardIn.clear();
		for (ServerPlayer p : online()) {
			Part part = players.get(p.getUUID());
			spawn(p, part);
			dev.rubymod.game.Alerts.title(p, "YOU'RE " + teamName(part.team).toUpperCase(), "First team to 10 knockouts wins",
					part.team == 0 ? ChatFormatting.RED : ChatFormatting.BLUE);
		}
	}

	@Override
	protected void spawn(ServerPlayer player, Part part) {
		ServerLevel level = level();
		BlockPos c = center();
		int dir = part.team == 0 ? 1 : -1;
		double x = c.getX() + 0.5 + player.getRandom().nextInt(9) - 4;
		double z = c.getZ() - dir * 100 + 0.5;
		float yaw = part.team == 0 ? 0 : 180;
		UUID old = tanks.remove(player.getUUID());
		if (old != null && level.getEntity(old) instanceof Entity e && !e.isVehicle()) {
			e.discard();
		}
		teleport(player, x, c.getY(), z, yaw);
		ready(player);
		clearPlayer(player);
		give(player, new ItemStack(ModItems.LANCER), new ItemStack(ModItems.AT_MISSILE, 4), gun(ModItems.ARR_RIFLE), new ItemStack(ModItems.HEAVY_AMMO, 64));
		Mob tank = ModEntities.TANK.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
		if (tank != null) {
			tank.snapTo(x, c.getY(), z + dir * 3, yaw, 0);
			tank.yBodyRot = yaw;
			tank.yHeadRot = yaw;
			tank.addTag(tag());
			level.addFreshEntity(tank);
			spawned.add(tank.getUUID());
			tanks.put(player.getUUID(), tank.getUUID());
			// Climb in a moment later, once the client has the ground loaded.
			boardIn.put(player.getUUID(), 30);
		}
	}

	@Override
	protected void onDowned(ServerPlayer victim, Part part, Entity killer) {
		part.respawnIn = 100;
		int other = 1 - part.team;
		if (killer instanceof ServerPlayer k && players.containsKey(k.getUUID()) && players.get(k.getUUID()).team == other) {
			kills[other]++;
			k.sendOverlayMessage(Component.literal("Knocked out " + part.name + "!").withStyle(ChatFormatting.GREEN));
		} else {
			kills[other]++; // a crash or your own shell still counts for the other side
		}
		tell(Component.literal(part.name + " is knocked out (" + kills[0] + " - " + kills[1] + ")")
				.withStyle(part.team == 0 ? ChatFormatting.RED : ChatFormatting.BLUE));
		if (kills[other] >= WIN) {
			endRound(teamName(other).toUpperCase() + " WINS!", "Red " + kills[0] + " - " + kills[1] + " Blue", other == 0 ? ChatFormatting.RED : ChatFormatting.BLUE,
					teamIds(other));
		}
	}

	@Override
	protected void tickRound() {
		ServerLevel level = level();
		for (var it = boardIn.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			int left = e.getValue() - 1;
			if (left > 0) {
				e.setValue(left);
				continue;
			}
			it.remove();
			ServerPlayer p = PartyGames.player(server, e.getKey());
			UUID tankId = tanks.get(e.getKey());
			if (p != null && tankId != null && level.getEntity(tankId) instanceof Entity tank && tank.isAlive() && !tank.isVehicle()
					&& p.getVehicle() == null && p.distanceTo(tank) < 12) {
				p.startRiding(tank, true, true);
			}
		}
		if (roundTicks >= ROUND_TICKS) {
			String head = kills[0] == kills[1] ? "DRAW" : (kills[0] > kills[1] ? "RED" : "BLUE") + " WINS!";
			endRound(head, "Time's up: Red " + kills[0] + " - " + kills[1] + " Blue", ChatFormatting.GOLD,
					kills[0] == kills[1] ? java.util.List.<UUID>of() : teamIds(kills[0] > kills[1] ? 0 : 1));
		}
	}

	@Override
	public boolean allowsBreak(ServerPlayer player, BlockPos pos) {
		return false;
	}
}
