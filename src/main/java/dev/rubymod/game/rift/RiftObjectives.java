package dev.rubymod.game.rift;

import java.util.List;
import java.util.UUID;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.math.Transformation;

import dev.rubymod.game.Alerts;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.mixin.ItemDisplayAccessor;
import dev.rubymod.registry.ModBlocks;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The jobs some fights come with.
 *
 * DEFEND THE CORE: a great ruby crystal turns over the dais in the middle. Half the
 * monsters go for it and drain it while they're close; keep them off it until the last
 * wave is down. Lose it and it shatters (it hurts), and the reward's an ordinary one.
 *
 * HOLD THE ZONE: a ring of light somewhere in the room, under a tall glowing pillar you
 * can see through walls (and a pointer on screen telling you which way). Stand in it to
 * fill it; a monster inside slows it (contested). Full: the zone flares and everything left
 * dies. Up to five waves come; clear them all and the room's done anyway (a lesser reward).
 */
final class RiftObjectives {
	enum Kind { NONE, DEFEND, HOLD }

	static Kind kind = Kind.NONE;
	private static UUID coreDisplay;
	private static Vec3 core;
	private static float coreHp;
	private static float coreMax;
	private static boolean coreLost;
	private static Vec3 zone;
	private static final double ZONE_R = 4.5;
	private static final int ZONE_FULL = 600;
	/** The pillar of light over the zone (glowing, so it shows through walls). */
	private static UUID pillar;
	private static float progress;
	private static boolean contested;
	private static int t;

	private RiftObjectives() {
	}

	static void start(ServerLevel level, RiftRoom room, RiftMap.Type type, int players) {
		clear(level);
		t = 0;
		if (type == RiftMap.Type.DEFEND) {
			kind = Kind.DEFEND;
			core = Vec3.atBottomCenterOf(room.center).add(0, 2.2, 0);
			coreMax = 60 + 25 * (players - 1);
			coreHp = coreMax;
			coreLost = false;
			Display.ItemDisplay d = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (d != null) {
				d.snapTo(core.x, core.y, core.z, 0, 0);
				((ItemDisplayAccessor) d).rubymod$setItemStack(new ItemStack(ModBlocks.RUBY_BLOCK));
				((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateXYZ(0.6F, 0, 0.6F),
						new Vector3f(2.0F, 2.0F, 2.0F), new Quaternionf()));
				((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(3);
				d.addTag(Rift.TAG);
				level.addFreshEntity(d);
				coreDisplay = d.getUUID();
			}
			for (ServerPlayer p : Rift.players(level.getServer())) {
				Alerts.title(p, "DEFEND THE CORE", "Keep the monsters off the ruby in the middle", ChatFormatting.GOLD);
			}
		} else if (type == RiftMap.Type.HOLD) {
			kind = Kind.HOLD;
			progress = 0;
			// Somewhere off the middle, on open floor.
			var random = level.getRandom();
			for (int tries = 0; tries < 40; tries++) {
				double a = random.nextDouble() * Math.PI * 2;
				int r = room.half / 2 + random.nextInt(3);
				int x = (int) Math.round(Math.cos(a) * r);
				int z = (int) Math.round(Math.sin(a) * r);
				if (room.open(x, z, 5)) {
					zone = Vec3.atBottomCenterOf(room.center.offset(x, 1, z));
					break;
				}
			}
			if (zone == null) {
				zone = Vec3.atBottomCenterOf(room.center.offset(0, 1, -room.half / 2));
			}
			Display.BlockDisplay beam = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (beam != null) {
				beam.snapTo(zone.x, zone.y, zone.z, 0, 0);
				((dev.rubymod.mixin.BlockDisplayAccessor) beam).rubymod$setBlockState(net.minecraft.world.level.block.Blocks.STAINED_GLASS
						.pick(net.minecraft.world.item.DyeColor.YELLOW).defaultBlockState());
				((DisplayAccessor) beam).rubymod$setTransformation(new Transformation(new Vector3f(-0.35F, 0, -0.35F), new Quaternionf(),
						new Vector3f(0.7F, 16F, 0.7F), new Quaternionf()));
				beam.setGlowingTag(true);
				beam.addTag(Rift.TAG);
				level.addFreshEntity(beam);
				pillar = beam.getUUID();
			}
			for (ServerPlayer p : Rift.players(level.getServer())) {
				Alerts.title(p, "HOLD THE ZONE", "Stand in the ring of light until it fills", ChatFormatting.GOLD);
			}
		}
	}

	/** The Defend core's position (for monsters to walk to), or null. */
	static Vec3 core() {
		return kind == Kind.DEFEND && !coreLost ? core : null;
	}

	/** Should this monster go for the core instead of a player? Half of them do, unless someone's right on top of them. */
	static boolean goesForCore(Mob mob, List<ServerPlayer> targets) {
		if (core() == null || RiftBoss.isBoss(mob) || mob instanceof dev.rubymod.entity.SoldierEntity || (mob.getUUID().getLeastSignificantBits() & 1) == 0) {
			return false;
		}
		for (ServerPlayer p : targets) {
			if (p.distanceToSqr(mob) < 16) {
				return false;
			}
		}
		mob.setTarget(null);
		mob.getNavigation().moveTo(core.x, core.y - 1.2, core.z, 1.1);
		return true;
	}

	static Vec3 zone() {
		return zone;
	}

	/** The HOLD zone is full. */
	static boolean captured() {
		return kind == Kind.HOLD && progress >= ZONE_FULL;
	}

	/** Did the job go well (the core still standing, the zone taken)? */
	static boolean succeeded() {
		return kind == Kind.DEFEND ? !coreLost : kind == Kind.HOLD && progress >= ZONE_FULL;
	}

	/** The boss bar line while there's a job. */
	static String bar() {
		return switch (kind) {
			case DEFEND -> coreLost ? "The core shattered: survive the waves" : "DEFEND THE CORE  •  " + Math.round(100 * coreHp / coreMax) + "%";
			case HOLD -> "HOLD THE ZONE  •  " + Math.min(100, Math.round(100 * progress / ZONE_FULL)) + "%" + (contested ? "  •  CONTESTED!" : "");
			default -> "";
		};
	}

	static float barProgress() {
		return switch (kind) {
			case DEFEND -> coreLost ? 0 : coreHp / coreMax;
			case HOLD -> Math.min(1, progress / ZONE_FULL);
			default -> 1;
		};
	}

	static void tick(ServerLevel level, List<ServerPlayer> targets) {
		t++;
		if (kind == Kind.DEFEND && !coreLost) {
			tickCore(level, targets);
		} else if (kind == Kind.HOLD) {
			tickZone(level, targets);
		}
	}

	private static void tickCore(ServerLevel level, List<ServerPlayer> targets) {
		// Turning slowly, light rising off it.
		if (coreDisplay != null && level.getEntity(coreDisplay) instanceof Entity d) {
			d.setYRot(d.getYRot() + 3);
			d.setPos(core.x, core.y + Math.sin(t / 20.0) * 0.15, core.z);
		}
		if (t % 4 == 0) {
			level.sendParticles(ParticleTypes.END_ROD, core.x, core.y + 1.2, core.z, 1, 0.3, 0.3, 0.3, 0.03);
			RiftFx.ring(level, core.subtract(0, 1.1, 0), 3.0, RiftFx.dust(coreHp / coreMax > 0.35F ? 0xFF3D6E : 0xFF0000, 1.0F), 18);
		}
		if (t % 20 != 0) {
			return;
		}
		// Monsters near it drain it (elites much faster).
		float drain = 0;
		for (UUID id : Rift.MOBS) {
			if (level.getEntity(id) instanceof Mob mob && mob.isAlive() && mob.position().distanceTo(core) < 3.6) {
				drain += RiftEnemies.twistOf(mob) != null ? 4 : 1.5F;
				RiftFx.line(level, mob.getBoundingBox().getCenter(), core, RiftFx.dust(0x900010, 1.0F), 0.4);
			}
		}
		if (drain > 0) {
			coreHp -= drain;
			level.playSound(null, BlockPos.containing(core), SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.HOSTILE, 1.4F, 0.6F);
			if (coreHp <= coreMax * 0.3F && coreHp + drain > coreMax * 0.3F) {
				for (ServerPlayer p : targets) {
					p.sendOverlayMessage(net.minecraft.network.chat.Component.literal("⚠ THE CORE IS CRACKING!").withStyle(ChatFormatting.RED,
							ChatFormatting.BOLD));
				}
			}
		}
		if (coreHp <= 0) {
			coreLost = true;
			if (level.getEntity(coreDisplay) instanceof Entity d) {
				d.discard();
			}
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, core.x, core.y, core.z, 2, 0.5, 0.5, 0.5, 0);
			level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, ModBlocks.RUBY_BLOCK.defaultBlockState()),
					core.x, core.y, core.z, 120, 1.2, 1.2, 1.2, 0.4);
			level.playSound(null, BlockPos.containing(core), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 3.0F, 0.5F);
			level.playSound(null, BlockPos.containing(core), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2.0F, 0.6F);
			for (ServerPlayer p : targets) {
				Alerts.title(p, "THE CORE SHATTERED", "Survive the waves (the reward won't be as good)", ChatFormatting.RED);
				if (p.position().distanceTo(core) < 7) {
					p.hurtServer(level, level.damageSources().magic(), 6);
				}
				RiftFx.shake(p, 1.2F, 20);
			}
		}
	}

	private static void tickZone(ServerLevel level, List<ServerPlayer> targets) {
		int inside = 0;
		for (ServerPlayer p : targets) {
			if (p.position().distanceTo(zone) < ZONE_R && Math.abs(p.getY() - zone.y) < 3) {
				inside++;
			}
		}
		contested = false;
		for (UUID id : Rift.MOBS) {
			if (level.getEntity(id) instanceof LivingEntity e && e.isAlive() && e.position().distanceTo(zone) < ZONE_R) {
				contested = true;
				break;
			}
		}
		if (inside > 0 && progress < ZONE_FULL) {
			// Contested (a monster in the ring): it still fills, but slowly.
			progress = Math.min(ZONE_FULL, progress + (1 + 0.5F * (inside - 1)) * (contested ? 0.3F : 1.0F));
			if (t % 20 == 0) {
				level.playSound(null, BlockPos.containing(zone), SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 0.8F, 0.6F + progress / ZONE_FULL);
			}
		}
		// Anyone outside it: which way it is, and a short trail of light pointing there.
		if (t % 20 == 0) {
			for (ServerPlayer p : targets) {
				double dist = p.position().distanceTo(zone);
				if (dist < ZONE_R) {
					p.sendOverlayMessage(net.minecraft.network.chat.Component.literal(contested ? "In the zone, but a monster's in it too: clear it out!"
							: "In the zone: hold it!").withStyle(contested ? ChatFormatting.RED : ChatFormatting.GOLD));
					continue;
				}
				Vec3 to = zone.subtract(p.position()).multiply(1, 0, 1).normalize();
				Vec3 look = p.getLookAngle().multiply(1, 0, 1).normalize();
				double ahead = to.dot(look);
				double side = look.x * to.z - look.z * to.x;
				String way = ahead > 0.7 ? "ahead" : ahead < -0.5 ? "behind you" : side > 0 ? "to your right" : "to your left";
				String arrow = ahead > 0.7 ? "\u25B2" : ahead < -0.5 ? "\u25BC" : side > 0 ? "\u25B6" : "\u25C0";
				p.sendOverlayMessage(net.minecraft.network.chat.Component.literal(arrow + "  The zone: " + Math.round(dist) + " blocks " + way + "  " + arrow)
						.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
				for (double d = 1.5; d < Math.min(7, dist); d += 0.5) {
					Vec3 at = p.position().add(to.scale(d)).add(0, 0.2, 0);
					level.sendParticles(p, RiftFx.dust(0xFFC02E, 1.0F), true, false, at.x, at.y, at.z, 1, 0, 0, 0, 0);
				}
			}
		}
		// The ring: lit gold as far round as it's filled, a beam in the middle, red flicker when contested.
		if (t % 3 == 0) {
			int points = 48;
			int lit = Math.round(points * progress / ZONE_FULL);
			for (int i = 0; i < points; i++) {
				double a = Math.PI * 2 * i / points - Math.PI / 2;
				int color = contested && t % 6 == 0 ? 0xFF2020 : i < lit ? 0xFFC02E : 0x605070;
				level.sendParticles(RiftFx.dust(color, 1.3F), true, false, zone.x + Math.cos(a) * ZONE_R, zone.y + 0.15, zone.z + Math.sin(a) * ZONE_R, 1, 0, 0, 0, 0);
			}
			level.sendParticles(RiftFx.dust(inside > 0 ? 0xFFE680 : 0xC27BFF, 1.6F), true, false, zone.x, zone.y + 1 + (t % 30) * 0.25, zone.z, 2, 0.1, 0.1, 0.1, 0);
		}
	}

	/** Captured: the zone flares and every monster in the room burns away. */
	static void flare(ServerLevel level) {
		RiftFx.beam(level, zone, 24, ParticleTypes.END_ROD, 0xFFFFC02E);
		for (int ring = 1; ring <= 8; ring++) {
			RiftFx.ring(level, zone.add(0, 0.3, 0), ring * 2.5, RiftFx.dust(0xFFC02E, 1.8F), 20 * ring);
		}
		level.playSound(null, BlockPos.containing(zone), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 2.0F, 1.0F);
		level.playSound(null, BlockPos.containing(zone), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 1.0F, 1.6F);
		for (ServerPlayer p : Rift.players(level.getServer())) {
			Alerts.title(p, "ZONE CAPTURED!", "The light burns the Rift clean", ChatFormatting.GOLD);
			RiftFx.screen(p, "flash", 0xFFFFC02E, 20);
		}
	}

	static void clear(ServerLevel level) {
		if (coreDisplay != null && level != null && level.getEntity(coreDisplay) instanceof Entity d) {
			d.discard();
		}
		coreDisplay = null;
		if (pillar != null && level != null && level.getEntity(pillar) instanceof Entity b) {
			b.discard();
		}
		pillar = null;
		kind = Kind.NONE;
		zone = null;
		core = null;
		progress = 0;
		contested = false;
		coreLost = false;
	}
}
