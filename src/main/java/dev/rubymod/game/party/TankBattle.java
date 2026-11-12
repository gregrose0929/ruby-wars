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
		minesLeft.clear();
		// Pick-ups out in the open, where it's dangerous to go and get them.
		BlockPos c = center();
		Object[][] spots = {{"rapid", 0, 0}, {"repair", -55, 0}, {"repair", 55, 0}, {"mines", 0, -48}, {"mines", 0, 48}, {"repair", 0, -85}, {"repair", 0, 85}};
		for (Object[] spot : spots) {
			String kind = (String) spot[0];
			double x = c.getX() + (int) spot[1] + 0.5;
			double z = c.getZ() + (int) spot[2] + 0.5;
			net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(x, dev.rubymod.game.Hub.safeY(level(), x, c.getY() + 6, z) + 1.5, z);
			switch (kind) {
				case "rapid" -> addPickup(kind, at, new ItemStack(net.minecraft.world.item.Items.BLAZE_POWDER), "RAPID FIRE", ChatFormatting.GOLD, 45 * 20, 4.5);
				case "mines" -> addPickup(kind, at, new ItemStack(ModItems.TANK), "MINES", ChatFormatting.RED, 40 * 20, 4.5);
				default -> addPickup(kind, at, new ItemStack(net.minecraft.world.item.Items.ANVIL), "REPAIR", ChatFormatting.GREEN, 30 * 20, 4.5);
			}
		}
		for (ServerPlayer p : online()) {
			Part part = players.get(p.getUUID());
			spawn(p, part);
			dev.rubymod.game.Alerts.title(p, "YOU'RE " + teamName(part.team).toUpperCase(), "First team to 10 knockouts. Drive over the pick-ups!",
					part.team == 0 ? ChatFormatting.RED : ChatFormatting.BLUE);
		}
	}

	@Override
	protected void spawn(ServerPlayer player, Part part) {
		ServerLevel level = level();
		BlockPos c = center();
		int dir = part.team == 0 ? 1 : -1;
		// Along your own back line, at the spot farthest from the other team (and your own tanks), with 5 seconds of protection.
		double x = c.getX() + 0.5;
		double z = c.getZ() - dir * (96 + player.getRandom().nextInt(10)) + 0.5;
		double best = -1;
		for (int tries = 0; tries < 10; tries++) {
			double tx = c.getX() + 0.5 + player.getRandom().nextInt(161) - 80;
			double nearest = 999;
			for (ServerPlayer o : online()) {
				if (o != player && !o.isSpectator()) {
					nearest = Math.min(nearest, Math.hypot(o.getX() - tx, o.getZ() - z) * (players.get(o.getUUID()) != null && players.get(o.getUUID()).team == part.team
							? 2.5 : 1.0));
				}
			}
			if (nearest > best) {
				best = nearest;
				x = tx;
			}
		}
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
			tank.snapTo(x, dev.rubymod.game.Hub.safeY(level, x, c.getY(), z + dir * 3), z + dir * 3, yaw, 0);
			if (tank instanceof dev.rubymod.entity.VehicleEntity v) {
				v.shield(dev.rubymod.game.SpawnShield.TICKS);
			}
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

	/** Mines still to drop behind each player's tank (one every second and a half). */
	private final Map<UUID, Integer> minesLeft = new HashMap<>();

	@Override
	protected boolean onPickup(ServerPlayer player, Pickup pickup) {
		if (!(player.getVehicle() instanceof dev.rubymod.entity.VehicleEntity tank)) {
			return false;
		}
		switch (pickup.kind) {
			case "rapid" -> {
				tank.rapidFire(15 * 20);
				dev.rubymod.game.Alerts.title(player, "RAPID FIRE", "Your gun reloads 3x faster for 15 seconds", ChatFormatting.GOLD);
			}
			case "mines" -> {
				minesLeft.put(player.getUUID(), 3);
				dev.rubymod.game.Alerts.title(player, "MINES", "Dropping 3 mines behind you: keep driving!", ChatFormatting.RED);
			}
			default -> {
				if (tank.getHealth() >= tank.getMaxHealth() && tank.smokeCharges() >= dev.rubymod.entity.VehicleEntity.SMOKE_CHARGES) {
					return false;
				}
				tank.setHealth(Math.min(tank.getMaxHealth(), tank.getHealth() + tank.getMaxHealth() * 0.6F));
				tank.refillSmoke();
				dev.rubymod.game.Alerts.title(player, "REPAIRED", "Armor patched up, smoke reloaded", ChatFormatting.GREEN);
			}
		}
		Part part = players.get(player.getUUID());
		tell(Component.literal(part.name + " grabbed " + pickup.kind.toUpperCase().replace("RAPID", "RAPID FIRE") + "!")
				.withStyle(part.team == 0 ? ChatFormatting.RED : ChatFormatting.BLUE));
		return true;
	}

	/** Tanks plough through whatever isn't the stadium wall: ruins, trees, anything taller than they can climb. */
	private void crush() {
		ServerLevel level = level();
		BlockPos c = center();
		for (UUID id : tanks.values()) {
			if (!(level.getEntity(id) instanceof dev.rubymod.entity.VehicleEntity tank) || !tank.isVehicle()
					|| tank.getDeltaMovement().horizontalDistanceSqr() < 0.0025) {
				continue;
			}
			net.minecraft.world.phys.AABB box = tank.getBoundingBox().inflate(0.6, 0, 0.6).move(tank.getDeltaMovement().multiply(3, 0, 3));
			int y0 = (int) Math.floor(tank.getY() + 1.05);
			boolean broke = false;
			for (BlockPos pos : BlockPos.betweenClosed((int) Math.floor(box.minX), y0, (int) Math.floor(box.minZ), (int) Math.floor(box.maxX), y0 + 2,
					(int) Math.floor(box.maxZ))) {
				var state = level.getBlockState(pos);
				if (state.isAir() || state.is(net.minecraft.world.level.block.Blocks.BARRIER) || state.getDestroySpeed(level, pos) < 0
						|| wallAt(pos.getX() - c.getX(), pos.getY() - c.getY(), pos.getZ() - c.getZ())) {
					continue;
				}
				level.destroyBlock(pos, false);
				broke = true;
			}
			if (broke) {
				tank.setDeltaMovement(tank.getDeltaMovement().multiply(0.8, 1, 0.8));
			}
		}
	}

	private void dropMines() {
		ServerLevel level = level();
		for (var it = minesLeft.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			ServerPlayer p = PartyGames.player(server, e.getKey());
			if (p == null || !(p.getVehicle() instanceof dev.rubymod.entity.VehicleEntity tank)) {
				it.remove();
				continue;
			}
			net.minecraft.world.phys.Vec3 back = tank.position().subtract(net.minecraft.world.phys.Vec3.directionFromRotation(0, tank.getYRot()).scale(4.5));
			BlockPos at = BlockPos.containing(back.x, dev.rubymod.game.Hub.safeY(level, back.x, tank.getY() + 2, back.z), back.z);
			if (level.getBlockState(at).isAir() && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), net.minecraft.core.Direction.UP)) {
				level.setBlock(at, dev.rubymod.registry.ModBlocks.AT_MINE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
				level.playSound(null, at, net.minecraft.sounds.SoundEvents.IRON_TRAPDOOR_CLOSE, net.minecraft.sounds.SoundSource.PLAYERS, 1.5F, 0.7F);
				if (e.getValue() <= 1) {
					it.remove();
				} else {
					e.setValue(e.getValue() - 1);
				}
			}
		}
	}

	/** The stadium's perimeter wall (and a little either side of it). */
	@Override
	protected boolean wallAt(int dx, int dy, int dz) {
		int h = dev.rubymod.game.Duels.Mode.VEHICLES.half;
		return Math.max(Math.abs(dx), Math.abs(dz)) >= h - 1 && dy >= -2;
	}

	/** Out past the wall (through a gap, over it, anyhow): back inside, tank and all. */
	private void keepInside() {
		BlockPos c = center();
		int h = dev.rubymod.game.Duels.Mode.VEHICLES.half;
		for (ServerPlayer p : online()) {
			if (p.isSpectator()) {
				continue;
			}
			Entity body = p.getVehicle() != null ? p.getVehicle() : p;
			double dx = body.getX() - c.getX();
			double dz = body.getZ() - c.getZ();
			if (Math.abs(dx) <= h - 2 && Math.abs(dz) <= h - 2 && body.getY() < c.getY() + 30) {
				continue;
			}
			double x = c.getX() + Math.max(-(h - 8), Math.min(h - 8, dx));
			double z = c.getZ() + Math.max(-(h - 8), Math.min(h - 8, dz));
			double y = dev.rubymod.game.Hub.safeY(level(), x, c.getY(), z);
			body.teleportTo(x, y, z);
			body.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
			p.sendOverlayMessage(Component.literal("Stay inside the arena!").withStyle(ChatFormatting.RED));
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
		if (roundTicks % 10 == 0) {
			keepInside();
		}
		if (roundTicks % 2 == 0) {
			crush();
		}
		if (roundTicks % 30 == 0) {
			dropMines();
		}
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
