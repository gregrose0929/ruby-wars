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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/**
 * Air Battle: Red against Blue in the sky over two runways that face each other. You
 * take off in a different aircraft each time (fighter, attack jet, attack helicopter,
 * bomber) and carry a Skyhammer to shoot back from the ground; each runway also has two
 * Heavy AA Guns anyone on that side can climb into. Fly out of the battle area and you
 * have five seconds to turn back. Knocked out? Back on your runway in 5 seconds. First
 * team to 10 knockouts wins.
 */
public class AirBattle extends PartyGame {
	private static final int WIN = 10;
	private static final int ROUND_TICKS = 8 * 60 * 20;
	/** How far the battle area reaches from the middle, and how high. */
	public static final int AREA = 650;
	public static final int CEILING = 170;
	/** The airfield's own land (the client draws the edge of the battle around it; see AirWalls). */
	public static final int CENTER_X = -82000;
	@SuppressWarnings("unchecked")
	private static final EntityType<? extends Mob>[] AIRCRAFT = new EntityType[] {ModEntities.FIGHTER_JET, ModEntities.ATTACK_JET,
			ModEntities.ATTACK_HELI, ModEntities.BOMBER};
	private static final String[] AIRCRAFT_NAMES = {"Fighter Jet", "Attack Jet", "Attack Helicopter", "Bomber"};
	private final int[] kills = new int[2];
	private final Map<UUID, UUID> planes = new HashMap<>();
	private final Map<UUID, Integer> boardIn = new HashMap<>();
	/** Which aircraft each player gets next. */
	private final Map<UUID, Integer> nextPlane = new HashMap<>();
	private final Map<UUID, Integer> outside = new HashMap<>();

	public AirBattle() {
		super("air", "Air Battle", "Red vs Blue in jets and helicopters, with AA guns on the ground. First team to 10 knockouts wins!",
				"rubymod:fighter_jet", 2, false, true, true);
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(CENTER_X, GROUND + 1, 0);
	}

	@Override
	protected int reach() {
		return AREA + 20;
	}

	@Override
	protected void build(ServerLevel level) {
		Duels.ArenaBuilder.buildQueued(level, Duels.Mode.PLANES, center(), this::later);
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

	/** Which way a team's runway runs (+1: Red, from the south end; -1: Blue, from the north). */
	private static int side(int team) {
		return team == 0 ? 1 : -1;
	}

	private BlockPos runwayStart(int team) {
		BlockPos c = center();
		return new BlockPos(c.getX(), c.getY(), c.getZ() - Duels.Mode.PLANES.spawnZ * side(team));
	}

	@Override
	protected void startRound() {
		kills[0] = 0;
		kills[1] = 0;
		planes.clear();
		boardIn.clear();
		outside.clear();
		ace = null;
		// Rings in the sky: fly through one to rearm and patch up without landing.
		BlockPos mid = center();
		int[][] rings = {{0, 0, 95}, {-260, 0, 80}, {260, 0, 80}, {0, -330, 110}, {0, 330, 110}};
		for (int[] r : rings) {
			net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(mid.getX() + r[0] + 0.5, mid.getY() + r[2], mid.getZ() + r[1] + 0.5);
			addPickup("ring", at, new ItemStack(net.minecraft.world.item.Items.NETHER_STAR), "REARM + REPAIR", ChatFormatting.AQUA, 25 * 20, 13);
			drawRing(at);
		}
		// Two Heavy AA Guns by each runway.
		ServerLevel level = level();
		for (int team = 0; team < 2; team++) {
			BlockPos start = runwayStart(team);
			for (int sx : new int[] {13, -13}) {
				Mob gun = ModEntities.HEAVY_AA_GUN.create(level, EntitySpawnReason.EVENT);
				if (gun != null) {
					double x = start.getX() + sx + 0.5;
					double z = start.getZ() + 11 * side(team) + 0.5;
					gun.snapTo(x, dev.rubymod.game.Hub.safeY(level, x, start.getY(), z), z, team == 0 ? 0 : 180, 0);
					gun.addTag(tag());
					level.addFreshEntity(gun);
					spawned.add(gun.getUUID());
				}
			}
		}
		for (ServerPlayer p : online()) {
			Part part = players.get(p.getUUID());
			spawn(p, part);
			dev.rubymod.game.Alerts.title(p, "YOU'RE " + teamName(part.team).toUpperCase(), "First team to 10 knockouts wins",
					part.team == 0 ? ChatFormatting.RED : ChatFormatting.BLUE);
		}
	}

	/** The top scorer (with at least two knockouts): marked for everyone, and worth two when shot down. */
	private UUID ace;

	/** A hoop of glowing blocks around a ring's pick-up, in two directions so it reads as a ring from anywhere. */
	private void drawRing(net.minecraft.world.phys.Vec3 at) {
		ServerLevel level = level();
		var block = net.minecraft.world.level.block.Blocks.SEA_LANTERN.defaultBlockState();
		for (int i = 0; i < 20; i++) {
			double a = i * Math.PI * 2 / 20;
			double h = Math.cos(a) * 13;
			double v = Math.sin(a) * 13;
			for (net.minecraft.world.phys.Vec3 p : new net.minecraft.world.phys.Vec3[] {at.add(h, v, 0), at.add(0, v, h)}) {
				var d = dev.rubymod.game.Props.block(level, p, block, 1.6F, 1.6F, 1.6F, tag());
				if (d != null) {
					d.setGlowingTag(true);
					((dev.rubymod.mixin.DisplayAccessor) d).rubymod$setGlowColorOverride(0x40E0FF);
				}
			}
		}
	}

	@Override
	protected boolean onPickup(ServerPlayer player, Pickup pickup) {
		if (!(player.getVehicle() instanceof dev.rubymod.entity.AircraftEntity plane)) {
			return false;
		}
		plane.rearm();
		plane.setHealth(Math.min(plane.getMaxHealth(), plane.getHealth() + plane.getMaxHealth() * 0.5F));
		dev.rubymod.game.Alerts.title(player, "REARMED", "Weapons full, hull patched up", ChatFormatting.AQUA);
		return true;
	}

	private void updateAce() {
		UUID best = null;
		int most = 1;
		for (Map.Entry<UUID, Part> e : players.entrySet()) {
			if (!e.getValue().watching && e.getValue().kills > most) {
				most = e.getValue().kills;
				best = e.getKey();
			}
		}
		if (best != null && !best.equals(ace)) {
			ace = best;
			Part part = players.get(best);
			tell(Component.literal("★ " + part.name + " is the ACE (" + most + " knockouts). Shoot them down for double!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			ServerPlayer p = PartyGames.player(server, best);
			if (p != null) {
				dev.rubymod.game.Alerts.title(p, "YOU'RE THE ACE", "Everyone can see you now", ChatFormatting.GOLD);
			}
		}
		// The ace's aircraft glows.
		for (Map.Entry<UUID, UUID> e : planes.entrySet()) {
			if (level().getEntity(e.getValue()) instanceof Entity plane) {
				plane.setGlowingTag(e.getKey().equals(ace));
			}
		}
	}

	@Override
	protected java.util.List<Component> extraAwards() {
		Part part = ace == null ? null : players.get(ace);
		return part == null ? java.util.List.of() : java.util.List.of(award("Ace of the round", part.name, ChatFormatting.GOLD));
	}

	@Override
	protected void spawn(ServerPlayer player, Part part) {
		ServerLevel level = level();
		BlockPos start = runwayStart(part.team);
		int dir = side(part.team);
		// Near the start of your own runway (with all of it ahead of you to take off), with 5 seconds of protection.
		double x = start.getX() + 0.5 + player.getRandom().nextInt(7) - 3;
		double z = start.getZ() + 0.5 + dir * (4 + player.getRandom().nextInt(16));
		float yaw = part.team == 0 ? 0 : 180;
		UUID old = planes.remove(player.getUUID());
		if (old != null && level.getEntity(old) instanceof Entity e && !e.isVehicle()) {
			e.discard();
		}
		teleport(player, x + 4, start.getY(), z, yaw);
		ready(player);
		clearPlayer(player);
		give(player, new ItemStack(ModItems.SKYHAMMER), new ItemStack(ModItems.AA_MISSILE, 6), gun(ModItems.ARR_RIFLE),
				new ItemStack(ModItems.HEAVY_AMMO, 64));
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.RUBY_HELMET));
		player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.RUBY_CHESTPLATE));
		int kind = nextPlane.merge(player.getUUID(), 1, Integer::sum) - 1;
		Mob plane = AIRCRAFT[kind % AIRCRAFT.length].create(level, EntitySpawnReason.SPAWN_ITEM_USE);
		if (plane != null) {
			plane.snapTo(x, dev.rubymod.game.Hub.safeY(level, x, start.getY(), z), z, yaw, 0);
			plane.yBodyRot = yaw;
			plane.yHeadRot = yaw;
			if (plane instanceof dev.rubymod.entity.AircraftEntity a) {
				a.shield(dev.rubymod.game.SpawnShield.TICKS);
			}
			plane.addTag(tag());
			level.addFreshEntity(plane);
			spawned.add(plane.getUUID());
			planes.put(player.getUUID(), plane.getUUID());
			// Climb in a moment later, once the client has the runway loaded.
			boardIn.put(player.getUUID(), 30);
			player.sendOverlayMessage(Component.literal("This time: " + AIRCRAFT_NAMES[kind % AIRCRAFT.length]).withStyle(ChatFormatting.AQUA));
		}
	}

	@Override
	protected void onDowned(ServerPlayer victim, Part part, Entity killer) {
		part.respawnIn = 100;
		outside.remove(victim.getUUID());
		int other = 1 - part.team;
		kills[other]++; // shot down, crashed or flew off: it counts for the other side
		boolean wasAce = victim.getUUID().equals(ace);
		if (killer instanceof ServerPlayer k && players.containsKey(k.getUUID()) && players.get(k.getUUID()).team == other) {
			k.sendOverlayMessage(Component.literal("Shot down " + part.name + "!").withStyle(ChatFormatting.GREEN));
			if (wasAce) {
				kills[other]++;
				tell(Component.literal("★ " + k.getName().getString() + " shot down the ACE! Worth two.").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			}
		}
		if (wasAce) {
			ace = null;
		}
		tell(Component.literal(part.name + " is down (" + kills[0] + " - " + kills[1] + ")")
				.withStyle(part.team == 0 ? ChatFormatting.RED : ChatFormatting.BLUE));
		if (kills[other] >= WIN) {
			endRound(teamName(other).toUpperCase() + " WINS!", "Red " + kills[0] + " - " + kills[1] + " Blue", ChatFormatting.GOLD, teamIds(other));
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
			UUID planeId = planes.get(e.getKey());
			if (p != null && planeId != null && level.getEntity(planeId) instanceof Entity plane && plane.isAlive() && !plane.isVehicle()
					&& p.getVehicle() == null && p.distanceTo(plane) < 12) {
				p.startRiding(plane, true, true);
			}
		}
		if (roundTicks % 20 == 0) {
			updateAce();
		}
		// The edge of the battle area: five seconds to turn back.
		BlockPos c = center();
		for (ServerPlayer p : online()) {
			Part part = players.get(p.getUUID());
			if (part == null || part.watching || p.isSpectator() || part.respawnIn > 0) {
				continue;
			}
			boolean out = Math.abs(p.getX() - c.getX()) > AREA || Math.abs(p.getZ() - c.getZ()) > AREA || p.getY() > c.getY() + CEILING;
			if (!out) {
				outside.remove(p.getUUID());
				continue;
			}
			int t = outside.merge(p.getUUID(), 1, Integer::sum);
			if (t % 20 == 1) {
				p.sendOverlayMessage(Component.literal("⚠ Leaving the battle! Turn back (" + (5 - t / 20) + ")").withStyle(ChatFormatting.RED));
			}
			if (t >= 100) {
				outside.remove(p.getUUID());
				Entity ride = p.getVehicle();
				p.stopRiding();
				if (ride != null) {
					ride.discard();
				}
				p.hurtServer(level, p.damageSources().outOfBorder(), 1000.0F);
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
