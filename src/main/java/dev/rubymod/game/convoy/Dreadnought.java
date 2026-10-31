package dev.rubymod.game.convoy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.rubymod.entity.VehicleEntity;
import dev.rubymod.game.Alerts;
import dev.rubymod.game.rift.RiftFx;
import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.registry.ModDamage;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The Iron Dreadnought: the Legion's giant tank, guarding the vault gate in the Iron
 * Fortress. It rolls back and forth across the courtyard. Its big cannon marks a red
 * circle (usually under the truck) before it fires; its machine guns rattle at whoever's
 * closest; now and then it lobs mortars onto circles round everyone. It calls for
 * reinforcements twice, and under half health its armor cracks (it glows, and takes more
 * damage). When it blows up, so does the gate.
 */
final class Dreadnought {
	private record Part(BlockState block, float sx, float sy, float sz, float ox, float oy, float oz) {
	}

	private static final float S = 2.0F;
	private static final BlockState CORE = Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true);
	private static final List<Part> MODEL = List.of(
			new Part(Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(), 0.8F, 1.0F, 4.6F, -2.2F, 0.0F, -2.3F),
			new Part(Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(), 0.8F, 1.0F, 4.6F, 1.4F, 0.0F, -2.3F),
			new Part(Blocks.IRON_BLOCK.defaultBlockState(), 0.9F, 0.35F, 4.7F, -2.25F, 0.95F, -2.35F),
			new Part(Blocks.IRON_BLOCK.defaultBlockState(), 0.9F, 0.35F, 4.7F, 1.35F, 0.95F, -2.35F),
			new Part(Blocks.DEEPSLATE_TILES.defaultBlockState(), 3.4F, 1.1F, 4.4F, -1.7F, 0.5F, -2.2F),
			new Part(Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState(), 3.45F, 0.25F, 0.5F, -1.725F, 1.35F, 1.4F),
			new Part(Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 2.2F, 1.1F, 2.2F, -1.1F, 1.6F, -1.3F),
			new Part(Blocks.POLISHED_BLACKSTONE.defaultBlockState(), 0.3F, 0.3F, 3.0F, -0.55F, 2.0F, 0.6F),
			new Part(Blocks.POLISHED_BLACKSTONE.defaultBlockState(), 0.3F, 0.3F, 3.0F, 0.25F, 2.0F, 0.6F),
			new Part(Blocks.IRON_BLOCK.defaultBlockState(), 0.5F, 0.5F, 0.4F, -0.25F, 2.0F, 0.3F),
			new Part(CORE, 0.8F, 0.5F, 0.08F, -0.4F, 1.9F, 0.92F),
			new Part(Blocks.POLISHED_BLACKSTONE.defaultBlockState(), 0.5F, 0.4F, 0.5F, 0.6F, 2.7F, -0.8F),
			new Part(Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState(), 0.05F, 0.6F, 0.4F, -1.15F, 1.8F, -1.0F));
	private static final int CORE_PART = 10;

	private final UUID base;
	private final List<UUID> parts = new ArrayList<>();
	private final ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("THE IRON DREADNOUGHT").withStyle(ChatFormatting.BOLD),
			BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
	private int t;
	private int cannonAt = 80;
	private int gunAt = 50;
	private int mortarAt = 220;
	private Vec3 cannonAim;
	private int cannonIn;
	private final List<Vec3> mortars = new ArrayList<>();
	private int mortarsIn;
	private int calls;
	private boolean cracked;
	private int dying = -1;
	private boolean done;
	private double drift = 1;
	private Vec3 lastPos;

	private Dreadnought(UUID base) {
		this.base = base;
	}

	static Dreadnought spawn(ServerLevel level, int players) {
		Mob heart = EntityTypes.RAVAGER.create(level, EntitySpawnReason.EVENT);
		if (heart == null) {
			return null;
		}
		double z = ConvoyRoute.COURTYARD + 22;
		heart.snapTo(ConvoyRoute.cx(z), ConvoyRoute.Y + 1, z, 180, 0);
		set(heart, Attributes.SCALE, 2.3);
		set(heart, Attributes.MAX_HEALTH, 450 * ConvoyEnemies.toughness(4, players));
		set(heart, Attributes.MOVEMENT_SPEED, 0.08);
		set(heart, Attributes.KNOCKBACK_RESISTANCE, 1.0);
		set(heart, Attributes.FOLLOW_RANGE, 80);
		heart.setHealth(heart.getMaxHealth());
		heart.setPersistenceRequired();
		heart.setSilent(true);
		heart.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false));
		heart.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, -1, 0, false, false));
		heart.setCustomName(Component.literal("The Iron Dreadnought").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
		heart.addTag(Convoy.TAG);
		heart.addTag(Convoy.BOSS_TAG);
		level.addFreshEntity(heart);
		Convoy.MOBS.add(heart.getUUID());
		Dreadnought boss = new Dreadnought(heart.getUUID());
		for (Part p : MODEL) {
			Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (d == null) {
				continue;
			}
			d.snapTo(heart.getX(), heart.getY(), heart.getZ(), 180, 0);
			((BlockDisplayAccessor) d).rubymod$setBlockState(p.block());
			((DisplayAccessor) d).rubymod$setTransformation(transform(p));
			((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(3);
			((DisplayAccessor) d).rubymod$setViewRange(5.0F);
			d.addTag(Convoy.TAG);
			level.addFreshEntity(d);
			boss.parts.add(d.getUUID());
		}
		return boss;
	}

	private static Transformation transform(Part p) {
		return new Transformation(new Vector3f(p.ox() * S, p.oy() * S, p.oz() * S), new Quaternionf(), new Vector3f(p.sx() * S, p.sy() * S, p.sz() * S),
				new Quaternionf());
	}

	private static void set(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr, double value) {
		AttributeInstance a = mob.getAttribute(attr);
		if (a != null) {
			a.setBaseValue(value);
		}
	}

	boolean dead() {
		return done;
	}

	Mob body(ServerLevel level) {
		return level.getEntity(base) instanceof Mob m && m.isAlive() ? m : null;
	}

	void remove(ServerLevel level) {
		Entity e = level.getEntity(base);
		if (e != null) {
			e.discard();
		}
		for (UUID id : parts) {
			Entity d = level.getEntity(id);
			if (d != null) {
				d.discard();
			}
		}
		bar.removeAllPlayers();
	}

	String debug() {
		Mob m = lastPos == null ? null : null;
		return "t" + t + (cracked ? " cracked" : "") + " calls " + calls + (dying >= 0 ? " dying" : "") + (m == null ? "" : "");
	}

	void tick(ServerLevel level, ConvoyTruck truck, List<ServerPlayer> targets) {
		t++;
		RandomSource random = level.getRandom();
		if (dying >= 0) {
			tickDeath(level, targets);
			return;
		}
		Mob mob = body(level);
		if (mob == null) {
			dying = 0;
			for (ServerPlayer p : targets) {
				Alerts.title(p, "IT'S GOING UP!", "Stand back!", ChatFormatting.GOLD);
			}
			return;
		}
		lastPos = mob.position();
		mob.setTarget(null); // it doesn't bite: it shoots
		float hp = mob.getHealth() / mob.getMaxHealth();
		bar.setProgress(Math.max(0, hp));
		bar.setName(Component.literal(cracked ? "THE IRON DREADNOUGHT  •  ARMOR CRACKED" : "THE IRON DREADNOUGHT").withStyle(ChatFormatting.BOLD));
		for (ServerPlayer p : targets) {
			bar.addPlayer(p);
		}
		// Back and forth across the courtyard, its guns toward the truck.
		Vec3 home = new Vec3(ConvoyRoute.cx(ConvoyRoute.COURTYARD + 22), ConvoyRoute.Y + 1, ConvoyRoute.COURTYARD + 22);
		if (Math.abs(mob.getX() - home.x) > 13) {
			drift = mob.getX() > home.x ? -1 : 1;
		}
		if (t % 20 == 0) {
			mob.getNavigation().moveTo(home.x + drift * 14, home.y, home.z + Math.sin(t * 0.01) * 3, 1.0);
		}
		Vec3 tp = truck.position();
		float yaw = (float) -Math.toDegrees(Math.atan2(tp.x - mob.getX(), tp.z - mob.getZ()));
		for (UUID id : parts) {
			Entity d = level.getEntity(id);
			if (d != null) {
				d.teleportTo(mob.getX(), mob.getY(), mob.getZ());
				d.setYRot(yaw);
			}
		}
		if (t % 3 == 0) {
			level.sendParticles(ParticleTypes.LARGE_SMOKE, mob.getX(), mob.getY() + 6.5, mob.getZ(), 1, 0.3, 0.1, 0.3, 0.02);
		}
		if (cracked && t % 2 == 0) {
			level.sendParticles(ParticleTypes.FLAME, mob.getX(), mob.getY() + 4, mob.getZ(), 3, 1.2, 0.6, 1.2, 0.02);
		}
		// Calls for help at 70% and 35%.
		if (calls == 0 && hp < 0.7F || calls == 1 && hp < 0.35F) {
			calls++;
			ConvoyEvents.ambush(level, 4, truck, targets, random, 0.8);
			level.playSound(null, mob.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 3.0F, 0.8F);
			for (ServerPlayer p : targets) {
				Alerts.title(p, "REINFORCEMENTS!", "The Dreadnought called for help", ChatFormatting.RED);
			}
		}
		if (!cracked && hp < 0.5F) {
			cracked = true;
			mob.removeEffect(MobEffects.RESISTANCE);
			Entity core = level.getEntity(parts.get(CORE_PART));
			if (core instanceof Display.BlockDisplay d) {
				((BlockDisplayAccessor) d).rubymod$setBlockState(Blocks.SHROOMLIGHT.defaultBlockState());
				d.setGlowingTag(true);
				((DisplayAccessor) d).rubymod$setGlowColorOverride(0xFFA020);
			}
			level.playSound(null, mob.blockPosition(), SoundEvents.ANVIL_BREAK, SoundSource.HOSTILE, 3.0F, 0.5F);
			for (ServerPlayer p : targets) {
				Alerts.title(p, "ARMOR CRACKED!", "It's glowing: hit it hard now!", ChatFormatting.GOLD);
			}
		}
		Vec3 muzzle = mob.position().add(Vec3.directionFromRotation(0, yaw).scale(5.5)).add(0, 4.2, 0);
		// The big cannon.
		if (cannonIn > 0) {
			cannonIn--;
			RiftFx.ring(level, cannonAim, 3.5, RiftFx.dust(0xFF2020, 1.5F), 22);
			RiftFx.ring(level, cannonAim, 3.5 * cannonIn / 30.0, RiftFx.dust(0xFFFFFF, 1.0F), 14);
			if (cannonIn == 0) {
				RiftFx.line(level, muzzle, cannonAim, ParticleTypes.SMOKE, 0.5);
				RiftFx.line(level, muzzle, cannonAim, RiftFx.dust(0xFF4020, 1.2F), 0.8);
				level.sendParticles(ParticleTypes.FLAME, muzzle.x, muzzle.y, muzzle.z, 20, 0.3, 0.3, 0.3, 0.08);
				level.playSound(null, BlockPos.containing(muzzle), dev.rubymod.registry.ModSounds.TANK_CANNON, SoundSource.HOSTILE, 3.0F, 0.7F);
				level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, cannonAim.x, cannonAim.y + 0.5, cannonAim.z, 1, 0, 0, 0, 0);
				level.sendParticles(ParticleTypes.LARGE_SMOKE, cannonAim.x, cannonAim.y + 0.5, cannonAim.z, 20, 1.2, 0.6, 1.2, 0.04);
				level.playSound(null, BlockPos.containing(cannonAim), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2.5F, 0.8F);
				if (truck.distanceTo(cannonAim) < 3.5) {
					truck.hurt(level, 24, cannonAim.add(0, 1, 0));
				}
				for (ServerPlayer p : targets) {
					if (p.position().distanceTo(cannonAim) < 3.5) {
						if (p.getVehicle() instanceof VehicleEntity v) {
							v.hurtServer(level, ModDamage.source(level, ModDamage.ANTI_ARMOR, mob, mob), 26);
						} else {
							p.hurtServer(level, level.damageSources().explosion(mob, mob), 8);
						}
					}
					RiftFx.shake(p, 1.0F, 10);
				}
			}
		} else if (--cannonAt <= 0) {
			cannonAt = (cracked ? 90 : 120) + random.nextInt(40);
			ServerPlayer near = nearest(mob, targets);
			Vec3 spot = near == null || random.nextFloat() < 0.6F ? truck.local((random.nextDouble() - 0.5) * 2, 0, (random.nextDouble() - 0.5) * 5)
					: near.position();
			cannonAim = new Vec3(spot.x, Math.floor(spot.y) + 0.1, spot.z);
			cannonIn = 30;
			level.playSound(null, mob.blockPosition(), SoundEvents.PISTON_EXTEND, SoundSource.HOSTILE, 2.0F, 0.4F);
		}
		// Machine guns: a burst at whoever's closest.
		ServerPlayer near = nearest(mob, targets);
		if (near != null && --gunAt <= 0) {
			if (gunAt % 3 == 0) {
				gun(level, mob, muzzle, near);
			}
			if (gunAt <= -18) {
				gunAt = (cracked ? 40 : 60) + random.nextInt(30);
			}
		}
		// Mortars on circles round everyone.
		if (mortarsIn > 0) {
			mortarsIn--;
			for (Vec3 r : mortars) {
				RiftFx.ring(level, r, 2.3, RiftFx.dust(0xFF2020, 1.3F), 16);
				RiftFx.ring(level, r, 2.3 * mortarsIn / 36.0, RiftFx.dust(0xFFFFFF, 0.9F), 10);
			}
			if (mortarsIn == 0) {
				for (Vec3 r : mortars) {
					level.sendParticles(ParticleTypes.EXPLOSION, r.x, r.y + 0.5, r.z, 2, 0.5, 0.3, 0.5, 0);
					level.playSound(null, BlockPos.containing(r), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.0F, 1.1F);
					for (ServerPlayer p : targets) {
						if (p.position().distanceTo(r) < 2.6) {
							p.hurtServer(level, level.damageSources().explosion(mob, mob), 6);
						}
					}
				}
				mortars.clear();
			}
		} else if (--mortarAt <= 0) {
			mortarAt = 240 + random.nextInt(80);
			mortars.clear();
			for (ServerPlayer p : targets) {
				for (int k = 0; k < 2; k++) {
					mortars.add(new Vec3(p.getX() + random.nextInt(7) - 3, Math.floor(p.getY()) + 0.1, p.getZ() + random.nextInt(7) - 3));
				}
			}
			mortarsIn = 36;
			level.playSound(null, mob.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE, 3.0F, 0.5F);
		}
	}

	private static ServerPlayer nearest(Entity from, List<ServerPlayer> targets) {
		ServerPlayer best = null;
		for (ServerPlayer p : targets) {
			if (best == null || p.distanceToSqr(from) < best.distanceToSqr(from)) {
				best = p;
			}
		}
		return best;
	}

	private void gun(ServerLevel level, Mob mob, Vec3 muzzle, ServerPlayer target) {
		var random = level.getRandom();
		Vec3 aim = target.position().add(0, 1, 0).add((random.nextDouble() - 0.5) * 2.4, (random.nextDouble() - 0.5) * 1.2, (random.nextDouble() - 0.5) * 2.4);
		var hit = level.clip(new ClipContext(muzzle, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
		Vec3 end = hit.getType() == HitResult.Type.MISS ? aim : hit.getLocation();
		RiftFx.line(level, muzzle, end, ParticleTypes.CRIT, 1.0);
		level.playSound(null, BlockPos.containing(muzzle), dev.rubymod.registry.ModSounds.RIFLE_SHOT, SoundSource.HOSTILE, 1.4F, 0.6F);
		if (end.distanceTo(target.position().add(0, 1, 0)) < 1.1) {
			target.hurtServer(level, level.damageSources().mobAttack(mob), 1.5F);
		}
	}

	private void tickDeath(ServerLevel level, List<ServerPlayer> targets) {
		dying++;
		Vec3 at = lastPos == null ? new Vec3(ConvoyRoute.cx(ConvoyRoute.COURTYARD + 22), ConvoyRoute.Y + 1, ConvoyRoute.COURTYARD + 22) : lastPos;
		RandomSource random = level.getRandom();
		if (dying % 6 == 0) {
			double x = at.x + (random.nextDouble() - 0.5) * 6;
			double z = at.z + (random.nextDouble() - 0.5) * 8;
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, at.y + 2, z, 1, 0, 0, 0, 0);
			level.playSound(null, BlockPos.containing(x, at.y, z), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 3.0F, 0.6F + random.nextFloat() * 0.3F);
			for (ServerPlayer p : targets) {
				RiftFx.shake(p, 1.0F, 8);
			}
		}
		if (dying >= 60) {
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 3, at.z, 4, 2, 1, 2, 0);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 3, at.z, 80, 3, 2, 3, 0.08);
			remove(level);
			done = true;
		}
	}
}
