package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Two more Fun Park rides. The Swings: chairs on long chains round a tower; the top spins, climbs, and the chairs fly
 * out wide (nothing above your head but sky). The Slingshot: stand on the pad between two tall towers, the bungees
 * stretch... 3, 2, 1, and you're flung high into the sky, then float back down.
 */
final class FunRides {
	private FunRides() {
	}

	// ----- The Swings -----

	private static final Vec3 SWING = new Vec3(84.5, FLOOR, 2.5);
	private static final int CHAIRS = 16;
	private static final double CROWN_R = 4.5;
	private static final double CHAIN = 5.5;
	private static final double LOW = FLOOR + 7.2;
	private static final double HIGH = FLOOR + 13.5;
	private static final Vec3 SWING_EXIT = new Vec3(84.5, FLOOR + 1, 14.5);
	private static final String[] CHAIR_COLORS = {"red", "yellow", "blue", "green", "pink", "purple", "orange", "sky"};

	private static UUID crown;
	private static final UUID[] CHAIR = new UUID[CHAIRS];
	private static final UUID[] SEAT = new UUID[CHAIRS];
	private static final UUID[][] CHAINS = new UUID[CHAIRS][2];
	private static double spin;
	private static double speed;
	private static double height = LOW;
	/** -1 waiting; counts up through a ride (wait, spin up, fly, slow down). */
	private static int swingT = -1;
	private static final List<UUID> SWING_RIDERS = new ArrayList<>();
	private static final int WAIT = 200;
	private static final int UP = WAIT + 140;
	private static final int FLY = UP + 500;
	private static final int DOWN = FLY + 200;

	private static void swings(ServerLevel level) {
		SWING_RIDERS.clear();
		swingT = -1;
		speed = 0;
		height = LOW;
		int cx = (int) Math.floor(SWING.x);
		int cz = (int) Math.floor(SWING.z);
		// A round deck in sunburst stripes, a fence round it, the tower in the middle.
		for (int dx = -12; dx <= 12; dx++) {
			for (int dz = -12; dz <= 12; dz++) {
				double r = Math.sqrt(dx * dx + dz * dz);
				if (r > 11.6) {
					continue;
				}
				int sector = (int) Math.floor((Math.atan2(dz, dx) + Math.PI) / (Math.PI / 8));
				HubPark.set(level, cx + dx, FLOOR, cz + dz, r > 11 ? Blocks.GOLD_BLOCK.defaultBlockState()
						: Blocks.CONCRETE.pick(sector % 2 == 0 ? DyeColor.LIGHT_BLUE : DyeColor.WHITE).defaultBlockState());
				for (int y = 1; y <= 3; y++) {
					HubPark.set(level, cx + dx, FLOOR + y, cz + dz, Blocks.AIR.defaultBlockState());
				}
				if (r > 11 && !(dz > 9 && Math.abs(dx) <= 1)) {
					HubPark.set(level, cx + dx, FLOOR + 1, cz + dz, Blocks.GLASS_PANE.defaultBlockState());
				}
			}
		}
		BlockState pole = Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState();
		HubPark.box(level, cx - 1, FLOOR + 1, cz - 1, cx + 1, FLOOR + 2, cz + 1, Blocks.CONCRETE.pick(DyeColor.BLUE).defaultBlockState());
		HubPark.box(level, cx, FLOOR + 3, cz, cx, FLOOR + 18, cz, pole);
		HubPark.set(level, cx, FLOOR + 19, cz, Blocks.SEA_LANTERN.defaultBlockState());
		HubPark.label(level, SWING.x, FLOOR + 21.5, SWING.z, "THE SWINGS", ChatFormatting.AQUA, 3.0F);
		Display.ItemDisplay c = HubPark.tempModel(level, new Vec3(SWING.x, height, SWING.z), 0, "swing_crown", 11.0F, 0.0F);
		crown = c == null ? null : c.getUUID();
		if (c != null) {
			// It sits round the pole (inside it, as far as the light goes): lit as if in daylight.
			((DisplayAccessor) c).rubymod$setBrightness(new net.minecraft.util.Brightness(15, 15));
		}
		BlockState chain = Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState();
		for (int i = 0; i < CHAIRS; i++) {
			Display.ItemDisplay m = HubPark.tempModel(level, chairAt(i), 0, "swing_chair_" + CHAIR_COLORS[i % CHAIR_COLORS.length], 1.3F, 0.0F);
			CHAIR[i] = m == null ? null : m.getUUID();
			Display.BlockDisplay s = HubPark.seat(level, chairAt(i).add(0, 0.15, 0));
			SEAT[i] = s == null ? null : s.getUUID();
			for (int k = 0; k < 2; k++) {
				Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
				if (d == null) {
					continue;
				}
				((BlockDisplayAccessor) d).rubymod$setBlockState(chain);
				((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(-0.03F, (float) -CHAIN, -0.03F), new Quaternionf(),
						new Vector3f(0.06F, (float) CHAIN, 0.06F), new Quaternionf()));
				((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(2);
				((DisplayAccessor) d).rubymod$setViewRange(6.0F);
				Vec3 at = chainAt(i, k);
				d.snapTo(at.x, at.y, at.z, 0, 0);
				d.addTag(HubRides.TEMP);
				HubPark.live(d);
				level.addFreshEntity(d);
				CHAINS[i][k] = d.getUUID();
			}
		}
		HubHosts.add(level, new Vec3(86.5, FLOOR + 1, 14.5), 0, VillagerProfession.FLETCHER, VillagerType.PLAINS,
				new HubHosts.Host("swings", "Skye", "The Swings", 0x60D0FF, "minecraft:feather",
						"Sit in a swing chair... then the top spins, climbs up high, and you fly out over the whole park! Nothing above "
								+ "you but sky. About 40 seconds. Crouch to get off.",
						"The Swings: right-click Skye to fly!", HubHosts.rideButtons("Ride the Swings!"), (p, key) -> {
							for (ServerPlayer q : HubHosts.group(p, key)) {
								swing(q);
							}
						}));
	}

	private static double angle(int i) {
		return spin + i * Math.PI * 2 / CHAIRS;
	}

	/** How far out the chains swing (radians from straight down): more the faster it spins. */
	private static double tilt() {
		return Math.min(0.95, speed * speed * 220);
	}

	private static Vec3 chainAt(int i, int k) {
		double a = angle(i);
		double side = k == 0 ? -0.56 : 0.56;
		return new Vec3(SWING.x + Math.cos(a) * CROWN_R - Math.sin(a) * side, height - 0.2, SWING.z + Math.sin(a) * CROWN_R + Math.cos(a) * side);
	}

	private static Vec3 chairAt(int i) {
		double a = angle(i);
		double t = tilt();
		double r = CROWN_R + Math.sin(t) * CHAIN;
		return new Vec3(SWING.x + Math.cos(a) * r, height - 0.2 - Math.cos(t) * CHAIN, SWING.z + Math.sin(a) * r);
	}

	private static void swing(ServerPlayer p) {
		ServerLevel level = (ServerLevel) p.level();
		if (p.isPassenger()) {
			return;
		}
		if (swingT > WAIT) {
			p.sendOverlayMessage(Component.literal("The swings are flying! Wait for them to come down.").withStyle(ChatFormatting.YELLOW));
			return;
		}
		for (int i = 0; i < CHAIRS; i++) {
			Entity s = SEAT[i] == null ? null : level.getEntity(SEAT[i]);
			if (s != null && !s.isVehicle()) {
				p.startRiding(s, true, true);
				SWING_RIDERS.add(p.getUUID());
				if (swingT < 0) {
					swingT = 0;
				}
				p.sendOverlayMessage(Component.literal("Hold on! We fly in a few seconds... (crouch to get off)").withStyle(ChatFormatting.AQUA));
				return;
			}
		}
		p.sendOverlayMessage(Component.literal("All the chairs are taken! Next time.").withStyle(ChatFormatting.YELLOW));
	}

	private static void tickSwings(ServerLevel level) {
		for (Iterator<UUID> it = SWING_RIDERS.iterator(); it.hasNext();) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(it.next());
			if (p == null || !onAny(p, SEAT)) {
				it.remove();
				if (p != null && p.level() == level) {
					FunPark.letOff(p, SWING_EXIT, "Talk to Skye to fly again!");
				}
			}
		}
		if (swingT >= 0) {
			swingT++;
			double top = 0.055;
			if (swingT < WAIT) {
				speed = 0;
				if (swingT % 40 == 0) {
					tellSwing(level, "Flying in " + (WAIT - swingT) / 20 + "...");
				}
			} else if (swingT < UP) {
				double f = (swingT - WAIT) / (double) (UP - WAIT);
				speed = top * f;
				height = LOW + (HIGH - LOW) * f;
				if (swingT == WAIT) {
					tellSwing(level, "Here we go! Wheee!");
					level.playSound(null, BlockPos.containing(SWING), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 2.0F, 1.5F);
				}
			} else if (swingT < FLY) {
				speed = top;
				height = HIGH + Math.sin((swingT - UP) * 0.02) * 1.2;
			} else if (swingT < DOWN) {
				double f = 1 - (swingT - FLY) / (double) (DOWN - FLY);
				speed = top * f;
				height = LOW + (HIGH - LOW) * f;
			} else {
				speed = 0;
				height = LOW;
				swingT = -1;
				for (UUID id : new ArrayList<>(SWING_RIDERS)) {
					ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
					if (p != null) {
						FunPark.letOff(p, SWING_EXIT, "What a flight! Talk to Skye to go again.");
					}
				}
				SWING_RIDERS.clear();
			}
			if (SWING_RIDERS.isEmpty() && swingT >= 0 && swingT < WAIT) {
				swingT = -1;
			}
			if (speed > 0.03 && swingT % 10 == 0) {
				for (UUID id : SWING_RIDERS) {
					ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
					if (p != null) {
						// A touch of speed widens the view: it feels faster.
						p.addEffect(new MobEffectInstance(MobEffects.SPEED, 30, 1, false, false));
					}
				}
			}
		}
		spin += speed;
		float tiltDeg = (float) Math.toDegrees(tilt());
		Entity c = crown == null ? null : level.getEntity(crown);
		if (c != null) {
			c.teleportTo(SWING.x, height, SWING.z);
			c.setYRot(net.minecraft.util.Mth.wrapDegrees((float) Math.toDegrees(spin)));
		}
		for (int i = 0; i < CHAIRS; i++) {
			double a = angle(i);
			Vec3 at = chairAt(i);
			Entity m = CHAIR[i] == null ? null : level.getEntity(CHAIR[i]);
			if (m != null) {
				m.teleportTo(at.x, at.y, at.z);
				m.setYRot(net.minecraft.util.Mth.wrapDegrees((float) Math.toDegrees(a) - 90));
			}
			Entity s = SEAT[i] == null ? null : level.getEntity(SEAT[i]);
			if (s != null) {
				s.teleportTo(at.x, at.y + 0.15, at.z);
				s.setYRot(net.minecraft.util.Mth.wrapDegrees((float) Math.toDegrees(a) - 90));
			}
			for (int k = 0; k < 2; k++) {
				Entity d = CHAINS[i][k] == null ? null : level.getEntity(CHAINS[i][k]);
				if (d != null) {
					Vec3 ca = chainAt(i, k);
					d.teleportTo(ca.x, ca.y, ca.z);
					d.setYRot(net.minecraft.util.Mth.wrapDegrees((float) Math.toDegrees(a) + 90));
					d.setXRot(tiltDeg);
				}
			}
		}
	}

	private static void tellSwing(ServerLevel level, String text) {
		for (UUID id : SWING_RIDERS) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
			if (p != null) {
				p.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.AQUA));
			}
		}
	}

	// ----- The Slingshot -----

	private static final Vec3 SLING = new Vec3(90.5, FLOOR + 1, 20.5);
	private static final double POST = 5.0;
	private static final double POST_TOP = FLOOR + 16;
	private static UUID slingPlayer;
	private static int slingT;
	private static final List<UUID> CORDS = new ArrayList<>();
	/** Players who've been flung, until they land. */
	private static final Map<UUID, Integer> FLYING = new HashMap<>();

	private static void slingshot(ServerLevel level) {
		slingPlayer = null;
		CORDS.clear();
		int cx = (int) Math.floor(SLING.x);
		int cz = (int) Math.floor(SLING.z);
		HubPark.box(level, cx - 1, FLOOR, cz - 1, cx + 1, FLOOR, cz + 1, Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState());
		HubPark.set(level, cx, FLOOR, cz, Blocks.GOLD_BLOCK.defaultBlockState());
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				if (Math.abs(dx) == 3 || Math.abs(dz) == 3) {
					HubPark.set(level, cx + dx, FLOOR, cz + dz, Blocks.CONCRETE.pick((dx + dz) % 2 == 0 ? DyeColor.YELLOW : DyeColor.BLACK).defaultBlockState());
				}
			}
		}
		// Two tall towers, with lights climbing them.
		for (int side : new int[] {-1, 1}) {
			int x = cx + side * (int) POST;
			HubPark.box(level, x, FLOOR + 1, cz, x, (int) POST_TOP, cz, Blocks.CONCRETE.pick(DyeColor.MAGENTA).defaultBlockState());
			for (int y = FLOOR + 2; y < POST_TOP; y += 3) {
				HubPark.set(level, x, y, cz, Blocks.SEA_LANTERN.defaultBlockState());
			}
			HubPark.set(level, x, (int) POST_TOP + 1, cz, Blocks.GLOWSTONE.defaultBlockState());
			HubPark.box(level, x - 1, FLOOR + 1, cz - 1, x + 1, FLOOR + 1, cz + 1, Blocks.CONCRETE.pick(DyeColor.PURPLE).defaultBlockState());
		}
		HubPark.label(level, SLING.x, POST_TOP + 3, SLING.z, "SLINGSHOT", ChatFormatting.LIGHT_PURPLE, 3.0F);
		HubPark.label(level, SLING.x, FLOOR + 3.4, SLING.z - 3.2, "Stand on the gold... and FLY!", ChatFormatting.WHITE, 0.9F);
		HubHosts.add(level, new Vec3(cx - 2.5, FLOOR + 1, cz - 3.5), 0, VillagerProfession.CARTOGRAPHER, VillagerType.JUNGLE,
				new HubHosts.Host("slingshot", "Zip", "Slingshot", 0xFF60E0, "minecraft:firework_rocket",
						"I hook you up to the giant bungees... 3, 2, 1... and you're flung WAY up into the sky! Then you float gently "
								+ "back down. Steer a bit on the way down if you like.",
						"Slingshot: right-click Zip to fly!", HubHosts.play("Fling me!"), (p, key) -> load(p)));
	}

	private static void load(ServerPlayer p) {
		ServerLevel level = (ServerLevel) p.level();
		if (slingPlayer != null) {
			p.sendOverlayMessage(Component.literal("Someone's flying! You're next in a moment.").withStyle(ChatFormatting.YELLOW));
			return;
		}
		if (p.isPassenger()) {
			p.stopRiding();
		}
		p.teleportTo(SLING.x, SLING.y, SLING.z);
		p.setYRot(0);
		p.setDeltaMovement(Vec3.ZERO);
		slingPlayer = p.getUUID();
		slingT = 0;
		BlockState cord = Blocks.CONCRETE.pick(DyeColor.ORANGE).defaultBlockState();
		for (int side : new int[] {-1, 1}) {
			Display.BlockDisplay d = HubPark.beam(level, new Vec3(SLING.x + side * POST, POST_TOP + 0.5, SLING.z), new Vec3(SLING.x + side * 0.55, FLOOR + 1.0, SLING.z),
					0.12F, 0.12F, cord);
			if (d != null) {
				d.addTag(HubRides.TEMP);
				HubPark.live(d);
				CORDS.add(d.getUUID());
			}
		}
		p.sendOverlayMessage(Component.literal("Bungees on! Stay on the gold...").withStyle(ChatFormatting.LIGHT_PURPLE));
		level.playSound(null, BlockPos.containing(SLING), SoundEvents.CROSSBOW_LOADING_START.value(), SoundSource.PLAYERS, 1.5F, 0.7F);
	}

	private static void tickSling(ServerLevel level) {
		if (slingPlayer != null) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(slingPlayer);
			if (p == null || p.level() != level || p.position().distanceTo(SLING) > 2.5) {
				dropCords(level);
				slingPlayer = null;
				if (p != null) {
					p.sendOverlayMessage(Component.literal("You stepped off! Talk to Zip to try again.").withStyle(ChatFormatting.YELLOW));
				}
			} else {
				slingT++;
				if (slingT == 20 || slingT == 40 || slingT == 60) {
					p.sendOverlayMessage(Component.literal(String.valueOf(4 - slingT / 20)).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
					level.playSound(null, BlockPos.containing(SLING), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.5F, 0.8F + slingT / 60F);
				}
				if (slingT == 80) {
					dropCords(level);
					slingPlayer = null;
					p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 30, 0, false, false));
					p.addEffect(new MobEffectInstance(MobEffects.SPEED, 50, 6, false, false));
					p.setDeltaMovement(new Vec3(0, 3.1, 0));
					p.syncVelocity = true;
					FLYING.put(p.getUUID(), 0);
					p.sendOverlayMessage(Component.literal("WHOOOOOSH!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
					level.playSound(null, BlockPos.containing(SLING), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 3.0F, 0.5F);
					level.playSound(null, BlockPos.containing(SLING), SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 2.0F, 0.5F);
					level.sendParticles(ParticleTypes.FIREWORK, SLING.x, SLING.y + 0.5, SLING.z, 40, 0.5, 0.3, 0.5, 0.2);
				}
			}
		}
		for (Iterator<Map.Entry<UUID, Integer>> it = FLYING.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
			e.setValue(e.getValue() + 1);
			if (p == null || p.level() != level) {
				it.remove();
				continue;
			}
			if (e.getValue() % 3 == 0 && e.getValue() < 60) {
				level.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY(), p.getZ(), 2, 0.1, 0.1, 0.1, 0.01);
			}
			if (e.getValue() > 20 && p.onGround()) {
				p.removeEffect(MobEffects.SLOW_FALLING);
				p.resetFallDistance();
				p.sendOverlayMessage(Component.literal("Touchdown! Talk to Zip to fly again.").withStyle(ChatFormatting.GREEN));
				it.remove();
			} else if (e.getValue() > 20 * 40) {
				it.remove();
			}
		}
	}

	private static void dropCords(ServerLevel level) {
		for (UUID id : CORDS) {
			HubPark.discard(level, id);
		}
		CORDS.clear();
	}

	// ----- Shared -----

	private static boolean onAny(ServerPlayer p, UUID[] seats) {
		Entity v = p.getVehicle();
		if (v == null) {
			return false;
		}
		for (UUID id : seats) {
			if (v.getUUID().equals(id)) {
				return true;
			}
		}
		return false;
	}

	static void build(ServerLevel level) {
		swings(level);
		slingshot(level);
	}

	static void tick(ServerLevel level) {
		tickSwings(level);
		tickSling(level);
	}

	static boolean busy() {
		return swingT >= 0 || slingPlayer != null || !FLYING.isEmpty();
	}

	static void reset() {
		SWING_RIDERS.clear();
		FLYING.clear();
		CORDS.clear();
		slingPlayer = null;
		swingT = -1;
		speed = 0;
		height = LOW;
	}
}
