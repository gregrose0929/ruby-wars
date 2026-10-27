package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.registry.ModBlocks;

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
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
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
 * Act 1's boss: the Juggernaut Mech, a walking war machine five blocks tall, built from
 * blocks around an unseen heart (a Ravager does the walking and stomping). It sprays its
 * minigun, rains rockets onto red circles, and slams the ground at anyone close. Its
 * armor shrugs off most damage, until it overheats (at two thirds and one third
 * health): it stops dead, its core glows, and everything hits hard. Under a third it
 * goes berserk. When it dies it comes apart in a chain of explosions.
 */
public final class JuggernautMech {
	private static final String BOSS_TAG = Rift.TAG + "_boss";

	/** One block of the body: block, size, offset from the feet (before turning), whether it's a leg (they walk). */
	private record Part(BlockState block, float sx, float sy, float sz, float ox, float oy, float oz, int leg) {
	}

	private static final BlockState HULL = Blocks.NETHERITE_BLOCK.defaultBlockState();
	private static final BlockState STEEL = Blocks.IRON_BLOCK.defaultBlockState();
	private static final BlockState DARK = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
	private static final BlockState RED = Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState();
	private static final BlockState GLASS = Blocks.STAINED_GLASS.pick(DyeColor.RED).defaultBlockState();
	private static final BlockState CORE_COLD = Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true);
	private static final BlockState CORE_HOT = Blocks.SHROOMLIGHT.defaultBlockState();
	private static final List<Part> BODY = List.of(
			new Part(STEEL, 0.75F, 2.2F, 0.75F, -1.35F, 0.25F, -0.38F, 1), new Part(STEEL, 0.75F, 2.2F, 0.75F, 0.6F, 0.25F, -0.38F, 2),
			new Part(DARK, 1.0F, 0.35F, 1.3F, -1.47F, 0.0F, -0.5F, 1), new Part(DARK, 1.0F, 0.35F, 1.3F, 0.48F, 0.0F, -0.5F, 2),
			new Part(DARK, 2.8F, 0.6F, 1.3F, -1.4F, 2.3F, -0.65F, 0),
			new Part(HULL, 3.0F, 2.1F, 2.4F, -1.5F, 2.8F, -1.2F, 0),
			new Part(GLASS, 1.7F, 0.7F, 0.12F, -0.85F, 4.0F, 1.2F, 0),
			new Part(CORE_COLD, 0.9F, 0.9F, 0.12F, -0.45F, 3.0F, 1.21F, 0),
			new Part(STEEL, 0.95F, 0.95F, 0.95F, -2.4F, 4.0F, -0.48F, 0), new Part(STEEL, 0.95F, 0.95F, 0.95F, 1.45F, 4.0F, -0.48F, 0),
			new Part(DARK, 0.45F, 0.45F, 2.3F, -2.15F, 3.25F, -0.6F, 0), new Part(DARK, 0.25F, 0.25F, 0.4F, -2.05F, 3.35F, 1.7F, 0),
			new Part(RED, 0.95F, 0.95F, 1.4F, 1.45F, 3.0F, -0.5F, 0), new Part(DARK, 0.7F, 0.7F, 0.1F, 1.57F, 3.12F, 0.9F, 0),
			new Part(Blocks.END_ROD.defaultBlockState(), 0.3F, 1.2F, 0.3F, 0.7F, 4.9F, -0.6F, 0),
			new Part(ModBlocks.RUBY_BLOCK.defaultBlockState(), 0.35F, 0.35F, 0.35F, 0.68F, 6.0F, -0.62F, 0));
	private static final int CORE_PART = 7;

	private final UUID heart;
	private final List<UUID> parts = new ArrayList<>();
	private final ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("JUGGERNAUT MECH").withStyle(ChatFormatting.BOLD),
			BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_6);
	private int t;
	private int overheat;
	private int overheats;
	private boolean berserk;
	private int gunFrom = 60;
	private int barrageAt = 160;
	private int slamAt = 80;
	private int slamWindup;
	private final List<Vec3> rockets = new ArrayList<>();
	private int rocketsIn;
	private int dying = -1;
	private boolean done;
	private Vec3 lastPos;

	private JuggernautMech(UUID heart) {
		this.heart = heart;
	}

	public static boolean isBoss(Entity e) {
		return e != null && e.entityTags().contains(BOSS_TAG);
	}

	static JuggernautMech spawn(ServerLevel level, RiftRoom room, int players, int floor) {
		Mob heart = EntityTypes.RAVAGER.create(level, EntitySpawnReason.EVENT);
		if (heart == null) {
			return null;
		}
		heart.snapTo(room.center.getX() + 0.5, room.center.getY() + 1, room.center.getZ() - room.half + 8, 0, 0);
		AttributeInstance size = heart.getAttribute(Attributes.SCALE);
		if (size != null) {
			size.setBaseValue(1.7);
		}
		AttributeInstance hp = heart.getAttribute(Attributes.MAX_HEALTH);
		if (hp != null) {
			hp.setBaseValue(420 * (1 + 0.65 * (players - 1)));
		}
		AttributeInstance speed = heart.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.setBaseValue(speed.getBaseValue() * 0.7);
		}
		AttributeInstance follow = heart.getAttribute(Attributes.FOLLOW_RANGE);
		if (follow != null) {
			follow.setBaseValue(80);
		}
		AttributeInstance knock = heart.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
		if (knock != null) {
			knock.setBaseValue(1.0);
		}
		heart.setHealth(heart.getMaxHealth());
		heart.setPersistenceRequired();
		heart.setSilent(true);
		heart.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false));
		heart.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, -1, 2, false, false));
		heart.setCustomName(Component.literal("Juggernaut Mech").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		heart.setCustomNameVisible(false);
		heart.addTag(Rift.TAG);
		heart.addTag(BOSS_TAG);
		level.addFreshEntity(heart);
		Rift.MOBS.add(heart.getUUID());
		JuggernautMech mech = new JuggernautMech(heart.getUUID());
		for (Part p : BODY) {
			Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (d == null) {
				continue;
			}
			d.snapTo(heart.getX(), heart.getY(), heart.getZ(), 0, 0);
			((BlockDisplayAccessor) d).rubymod$setBlockState(p.block());
			((DisplayAccessor) d).rubymod$setTransformation(transform(p, 0));
			((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(2);
			((DisplayAccessor) d).rubymod$setViewRange(4.0F);
			d.addTag(Rift.TAG);
			level.addFreshEntity(d);
			mech.parts.add(d.getUUID());
		}
		for (ServerPlayer p : Rift.players(level.getServer())) {
			mech.bar.addPlayer(p);
		}
		return mech;
	}

	private static Transformation transform(Part p, float swing) {
		float dz = p.leg() == 1 ? swing : p.leg() == 2 ? -swing : 0;
		float lift = p.leg() == 0 ? 0 : Math.max(0, (p.leg() == 1 ? swing : -swing)) * 0.25F;
		return new Transformation(new Vector3f(p.ox(), p.oy() + lift, p.oz() + dz), new Quaternionf(), new Vector3f(p.sx(), p.sy(), p.sz()),
				new Quaternionf());
	}

	boolean dead() {
		return done;
	}

	String debug() {
		return "t" + t + " overheats " + overheats + (overheat > 0 ? " HOT" : "") + (berserk ? " berserk" : "") + (dying >= 0 ? " dying" : "");
	}

	void remove(ServerLevel level) {
		Entity h = level.getEntity(heart);
		if (h != null) {
			h.discard();
		}
		for (UUID id : parts) {
			Entity e = level.getEntity(id);
			if (e != null) {
				e.discard();
			}
		}
		bar.removeAllPlayers();
	}

	void tick(ServerLevel level, List<ServerPlayer> targets) {
		t++;
		Entity e = level.getEntity(heart);
		if (dying >= 0) {
			tickDeath(level);
			return;
		}
		if (!(e instanceof Mob mob) || !mob.isAlive()) {
			dev.rubymod.RubyMod.LOGGER.info("Juggernaut Mech down at tick {}: entity {} removed {} health {}", t, e,
					e == null ? "gone" : e.getRemovalReason(), e instanceof LivingEntity l ? l.getHealth() : -1);
			startDeath(level, e != null ? e.position() : lastPos);
			return;
		}
		lastPos = mob.position();
		float hp = mob.getHealth() / mob.getMaxHealth();
		bar.setProgress(Math.max(0, hp));
		bar.setName(Component.literal(overheat > 0 ? "JUGGERNAUT MECH  •  OVERHEATED! HIT THE CORE!" : berserk ? "JUGGERNAUT MECH  •  BERSERK"
				: "JUGGERNAUT MECH").withStyle(ChatFormatting.BOLD));
		bar.setColor(overheat > 0 ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.RED);
		for (ServerPlayer p : targets) {
			bar.addPlayer(p);
		}
		// Overheat at two thirds and one third: it stops, the core glows, the armor's off.
		if (overheat == 0 && (overheats == 0 && hp < 0.66F || overheats == 1 && hp < 0.33F)) {
			overheats++;
			overheat = 140;
			mob.setNoAi(true);
			mob.removeEffect(MobEffects.RESISTANCE);
			mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, 140, 0, false, false));
			setCore(level, CORE_HOT);
			level.playSound(null, mob.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE, 2.0F, 0.5F);
			for (ServerPlayer p : targets) {
				dev.rubymod.game.Alerts.title(p, "OVERHEATED!", "Its armor is down: hit it with everything!", ChatFormatting.GOLD);
			}
		}
		if (overheat > 0) {
			overheat--;
			Vec3 core = corePos(mob);
			level.sendParticles(ParticleTypes.CLOUD, core.x, core.y + 1.5, core.z, 3, 0.8, 0.3, 0.8, 0.05);
			level.sendParticles(ParticleTypes.LAVA, core.x, core.y, core.z, 1, 0.3, 0.3, 0.3, 0);
			if (overheat == 0) {
				mob.setNoAi(false);
				mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, -1, berserk || overheats >= 2 ? 1 : 2, false, false));
				setCore(level, CORE_COLD);
				if (overheats >= 2) {
					berserk = true;
					mob.addEffect(new MobEffectInstance(MobEffects.SPEED, -1, 1, false, false));
					level.playSound(null, mob.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2.0F, 0.6F);
					for (ServerPlayer p : targets) {
						dev.rubymod.game.Alerts.title(p, "BERSERK!", "The Mech is falling apart, and furious", ChatFormatting.DARK_RED);
						RiftFx.shake(p, 1.0F, 20);
					}
				}
			}
			placeBody(level, mob, 0);
			return;
		}
		ServerPlayer target = mob.getTarget() instanceof ServerPlayer p && !p.isSpectator() ? p
				: targets.stream().min(java.util.Comparator.comparingDouble(p -> p.distanceToSqr(mob))).orElse(null);
		if (target != null && mob.getTarget() != target) {
			mob.setTarget(target);
		}
		// Walking legs, and the body following the heart.
		double moved = lastMoved(mob);
		float swing = (float) Math.sin(t * 0.35) * (float) Math.min(0.6, moved * 4);
		placeBody(level, mob, swing);
		if (berserk && t % 3 == 0) {
			level.sendParticles(RiftFx.dust(0xFF2020, 1.6F), mob.getX(), mob.getY() + 3, mob.getZ(), 4, 1.2, 1.5, 1.2, 0);
		}
		if (t % 5 == 0) {
			level.sendParticles(ParticleTypes.LARGE_SMOKE, mob.getX() - Math.sin(Math.toRadians(mob.yBodyRot)) * -1.2, mob.getY() + 5.2,
					mob.getZ() + Math.cos(Math.toRadians(mob.yBodyRot)) * -1.2, 1, 0.1, 0.1, 0.1, 0.02);
		}
		if (target == null) {
			return;
		}
		int pace = berserk ? 2 : 1;
		// Minigun: bursts from the left arm.
		if (--gunFrom <= 0 && gunFrom > -36) {
			if (gunFrom % 2 == 0) {
				minigun(level, mob, target);
			}
		} else if (gunFrom <= -36) {
			gunFrom = (berserk ? 40 : 70) + level.getRandom().nextInt(30);
		}
		// Rocket barrage: red circles, then rockets come down on them.
		if (--barrageAt <= 0) {
			barrageAt = (berserk ? 140 : 220) + level.getRandom().nextInt(60);
			rockets.clear();
			for (ServerPlayer p : targets) {
				for (int k = 0; k < 2 * pace + 1; k++) {
					rockets.add(new Vec3(p.getX() + level.getRandom().nextInt(9) - 4, Math.floor(p.getY()) + 0.1, p.getZ() + level.getRandom().nextInt(9) - 4));
				}
			}
			rocketsIn = 35;
			level.playSound(null, mob.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE, 2.0F, 0.5F);
			Vec3 pod = mob.position().add(0, 4.5, 0);
			level.sendParticles(ParticleTypes.FLAME, pod.x, pod.y, pod.z, 30, 0.5, 0.3, 0.5, 0.1);
		}
		if (rocketsIn > 0) {
			rocketsIn--;
			for (Vec3 r : rockets) {
				RiftFx.ring(level, r, 2.2, RiftFx.dust(0xFF2020, 1.3F), 18);
				RiftFx.ring(level, r, 2.2 * rocketsIn / 35.0, RiftFx.dust(0xFFFFFF, 0.9F), 10);
			}
			if (rocketsIn == 0) {
				for (Vec3 r : rockets) {
					RiftFx.line(level, r.add(0, 18, 0), r, ParticleTypes.FLAME, 0.7);
					level.sendParticles(ParticleTypes.EXPLOSION, r.x, r.y + 0.5, r.z, 2, 0.5, 0.3, 0.5, 0);
					level.playSound(null, BlockPos.containing(r), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.0F, 0.9F);
					for (ServerPlayer p : targets) {
						if (p.position().distanceTo(r) < 2.6) {
							p.hurtServer(level, level.damageSources().explosion(mob, mob), berserk ? 9 : 7);
							RiftFx.shake(p, 0.8F, 8);
						}
					}
				}
				rockets.clear();
			}
		}
		// Ground slam: anyone close gets flattened (with a moment's warning).
		if (slamWindup > 0) {
			slamWindup--;
			RiftFx.ring(level, mob.position().add(0, 0.2, 0), 6.0 * (1 - slamWindup / 18.0), RiftFx.dust(0xFFB000, 1.4F), 24);
			if (slamWindup == 0) {
				slam(level, mob, targets);
			}
		} else if (--slamAt <= 0 && target.distanceTo(mob) < 6) {
			slamAt = berserk ? 50 : 80;
			slamWindup = 18;
			level.playSound(null, mob.blockPosition(), SoundEvents.PISTON_EXTEND, SoundSource.HOSTILE, 2.0F, 0.5F);
		}
	}

	private double lastMoved(Mob mob) {
		return Math.hypot(mob.getX() - mob.xo, mob.getZ() - mob.zo);
	}

	private void placeBody(ServerLevel level, Mob mob, float swing) {
		float yaw = mob.yBodyRot;
		for (int i = 0; i < parts.size() && i < BODY.size(); i++) {
			Entity d = level.getEntity(parts.get(i));
			if (d == null) {
				continue;
			}
			d.teleportTo(mob.getX(), mob.getY(), mob.getZ());
			d.setYRot(yaw);
			Part p = BODY.get(i);
			if (p.leg() != 0 && t % 2 == 0) {
				((DisplayAccessor) d).rubymod$setTransformation(transform(p, swing));
				((DisplayAccessor) d).rubymod$setTransformationInterpolationDuration(2);
				((DisplayAccessor) d).rubymod$setTransformationInterpolationDelay(0);
			}
		}
	}

	private Vec3 corePos(Mob mob) {
		double yaw = Math.toRadians(mob.yBodyRot);
		return mob.position().add(-Math.sin(yaw) * 1.3, 3.4, Math.cos(yaw) * 1.3);
	}

	private void setCore(ServerLevel level, BlockState state) {
		if (parts.size() > CORE_PART && level.getEntity(parts.get(CORE_PART)) instanceof Display.BlockDisplay d) {
			((BlockDisplayAccessor) d).rubymod$setBlockState(state);
		}
	}

	private void minigun(ServerLevel level, Mob mob, ServerPlayer target) {
		double yaw = Math.toRadians(mob.yBodyRot);
		Vec3 side = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
		Vec3 muzzle = mob.position().add(0, 3.4, 0).add(side.scale(1.9)).add(-Math.sin(yaw) * 1.9, 0, Math.cos(yaw) * 1.9);
		var random = level.getRandom();
		Vec3 aim = target.position().add(0, 1.0, 0).add((random.nextDouble() - 0.5) * 2.4, (random.nextDouble() - 0.5) * 1.6,
				(random.nextDouble() - 0.5) * 2.4);
		var hit = level.clip(new ClipContext(muzzle, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
		Vec3 end = hit.getType() == HitResult.Type.MISS ? aim : hit.getLocation();
		RiftFx.line(level, muzzle, end, ParticleTypes.CRIT, 0.8);
		level.sendParticles(ParticleTypes.FLAME, muzzle.x, muzzle.y, muzzle.z, 2, 0.05, 0.05, 0.05, 0.02);
		level.playSound(null, BlockPos.containing(muzzle), dev.rubymod.registry.ModSounds.RIFLE_SHOT, SoundSource.HOSTILE, 1.0F, 0.6F);
		if (hit.getType() == HitResult.Type.MISS && target.getBoundingBox().inflate(0.6).contains(aim) || end.distanceTo(target.position().add(0, 1, 0)) < 1.0) {
			target.hurtServer(level, level.damageSources().mobAttack(mob), berserk ? 2.5F : 2.0F);
		}
	}

	private void slam(ServerLevel level, Mob mob, List<ServerPlayer> targets) {
		Vec3 at = mob.position();
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
		for (int ring = 1; ring <= 4; ring++) {
			RiftFx.ring(level, at.add(0, 0.2, 0), ring * 1.6, ParticleTypes.CLOUD, 16 * ring);
		}
		level.playSound(null, mob.blockPosition(), SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.HOSTILE, 2.0F, 0.5F);
		for (ServerPlayer p : targets) {
			double d = p.position().distanceTo(at);
			RiftFx.shake(p, (float) Math.max(0.3, 1.6 - d * 0.1), 12);
			if (d < 6.5) {
				p.hurtServer(level, level.damageSources().mobAttack(mob), berserk ? 12 : 9);
				Vec3 push = p.position().subtract(at).normalize().scale(1.2);
				p.setDeltaMovement(push.x, 0.7, push.z);
				p.needsSync = true;
			}
		}
	}

	private void startDeath(ServerLevel level, Vec3 at) {
		dying = 0;
		lastPos = at;
		bar.setProgress(0);
		for (ServerPlayer p : Rift.players(level.getServer())) {
			dev.rubymod.game.Alerts.title(p, "CRITICAL FAILURE", "The Mech is coming apart!", ChatFormatting.GOLD);
			RiftFx.shake(p, 1.5F, 60);
		}
	}

	private void tickDeath(ServerLevel level) {
		dying++;
		Vec3 at = lastPos == null ? Vec3.ZERO : lastPos;
		var random = level.getRandom();
		if (dying % 6 == 0) {
			Vec3 b = at.add((random.nextDouble() - 0.5) * 3, 1 + random.nextDouble() * 4, (random.nextDouble() - 0.5) * 3);
			level.sendParticles(ParticleTypes.EXPLOSION, b.x, b.y, b.z, 2, 0.3, 0.3, 0.3, 0);
			level.sendParticles(ParticleTypes.LAVA, b.x, b.y, b.z, 6, 0.4, 0.4, 0.4, 0);
			level.playSound(null, BlockPos.containing(b), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.4F, 0.8F + random.nextFloat() * 0.4F);
		}
		// The body flies apart.
		for (int i = 0; i < parts.size(); i++) {
			Entity d = level.getEntity(parts.get(i));
			if (d != null && dying % 2 == 0) {
				double a = i * 2.3;
				d.teleportTo(d.getX() + Math.cos(a) * 0.15, d.getY() + 0.12 - dying * 0.004, d.getZ() + Math.sin(a) * 0.15);
				d.setYRot(d.getYRot() + 9);
			}
		}
		if (dying >= 60) {
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 2, at.z, 3, 1, 1, 1, 0);
			level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 2, at.z, 120, 2, 2, 2, 0.4);
			level.playSound(null, BlockPos.containing(at), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 3.0F, 0.5F);
			remove(level);
			done = true;
		}
	}

	/** Called if someone damages the boss: an overheated Mech takes big hits (the core's exposed). */
	static float damageTaken(LivingEntity e, float amount, JuggernautMech mech) {
		return amount;
	}
}
