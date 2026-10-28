package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Hive Queen (act 1, instead of the Juggernaut sometimes): a giant spider queen in a
 * crown of slime. She spits arcs of acid at you and leaps across the hall. Each time she's
 * hurt badly (75%, 50%, 25%) she screams and lays three egg sacs around the room: smash
 * them before they hatch into a swarm of brood spiders. She dies in a burst of green.
 */
public final class HiveQueen implements RiftBoss {
	private final UUID queen;
	private Mob queenRef;
	private final RiftRoom room;
	private final int players;
	private final ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("HIVE QUEEN").withStyle(ChatFormatting.BOLD),
			BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.NOTCHED_10);
	private final List<UUID> crown = new ArrayList<>();
	/** Egg sacs: the hittable core, its look, and when it hatches. */
	private record Sac(UUID core, UUID look, int hatchAt) {
	}

	private final List<Sac> sacs = new ArrayList<>();
	private int t;
	private int laid;
	/** One batch of eggs at a time, however fast she's hurt. */
	private int layAgainAt;
	private int spitAt = 60;
	private int leapAt = 160;
	private int leapWindup;
	private Vec3 leapTo;
	private int dying = -1;
	private Vec3 lastPos;
	private boolean done;

	private HiveQueen(UUID queen, RiftRoom room, int players) {
		this.queen = queen;
		this.room = room;
		this.players = players;
	}

	static HiveQueen spawn(ServerLevel level, RiftRoom room, int players) {
		Mob spider = EntityTypes.SPIDER.create(level, EntitySpawnReason.EVENT);
		if (spider == null) {
			return null;
		}
		spider.snapTo(room.center.getX() + 0.5, room.center.getY() + 1, room.center.getZ() - room.half + 10, 0, 0);
		set(spider, Attributes.SCALE, 3.2);
		set(spider, Attributes.MAX_HEALTH, 520 * (1 + 0.65 * (players - 1)));
		set(spider, Attributes.ATTACK_DAMAGE, 9);
		set(spider, Attributes.FOLLOW_RANGE, 80);
		set(spider, Attributes.MOVEMENT_SPEED, 0.32);
		set(spider, Attributes.KNOCKBACK_RESISTANCE, 1.0);
		spider.setHealth(spider.getMaxHealth());
		spider.setPersistenceRequired();
		spider.setCustomName(Component.literal("Hive Queen").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
		spider.addTag(Rift.TAG);
		spider.addTag(JuggernautMech.BOSS_TAG);
		level.addFreshEntity(spider);
		Rift.MOBS.add(spider.getUUID());
		HiveQueen boss = new HiveQueen(spider.getUUID(), room, players);
		boss.queenRef = spider;
		// A crown of slime and emerald on her back.
		float[][] spots = {{-0.9F, 2.6F, -0.6F}, {0.3F, 2.8F, -0.4F}, {-0.3F, 3.2F, -0.5F}, {-1.3F, 2.3F, -1.6F}, {0.7F, 2.3F, -1.6F}};
		for (int i = 0; i < spots.length; i++) {
			float[] s = spots[i];
			Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (d != null) {
				d.snapTo(spider.getX(), spider.getY(), spider.getZ(), 0, 0);
				((BlockDisplayAccessor) d).rubymod$setBlockState(i < 3 ? Blocks.EMERALD_BLOCK.defaultBlockState() : Blocks.SLIME_BLOCK.defaultBlockState());
				((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(s[0], s[1], s[2]), new Quaternionf().rotateXYZ(0.3F, 0.5F, 0.2F),
						new Vector3f(i < 3 ? 0.5F : 0.9F, i < 3 ? 0.9F : 0.6F, i < 3 ? 0.5F : 0.9F), new Quaternionf()));
				((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(2);
				d.addTag(Rift.TAG);
				level.addFreshEntity(d);
				boss.crown.add(d.getUUID());
			}
		}
		for (ServerPlayer p : Rift.players(level.getServer())) {
			boss.bar.addPlayer(p);
		}
		return boss;
	}

	private static void set(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> a, double v) {
		AttributeInstance i = mob.getAttribute(a);
		if (i != null) {
			i.setBaseValue(v);
		}
	}

	@Override
	public boolean dead() {
		return done;
	}

	@Override
	public net.minecraft.world.entity.LivingEntity body(ServerLevel level) {
		return level.getEntity(queen) instanceof net.minecraft.world.entity.LivingEntity e && e.isAlive() ? e : null;
	}

	@Override
	public String debug() {
		return "queen t" + t + " laid " + laid + " sacs " + sacs.size() + (dying >= 0 ? " dying" : "");
	}

	@Override
	public void remove(ServerLevel level) {
		List<UUID> all = new ArrayList<>(crown);
		all.add(queen);
		for (Sac s : sacs) {
			all.add(s.core());
			all.add(s.look());
		}
		for (UUID id : all) {
			Entity e = id == null ? null : level.getEntity(id);
			if (e != null) {
				e.discard();
			}
		}
		bar.removeAllPlayers();
	}

	@Override
	public void tick(ServerLevel level, List<ServerPlayer> targets) {
		t++;
		if (dying >= 0) {
			tickDeath(level);
			return;
		}
		tickSacs(level, targets);
		Entity e = level.getEntity(queen);
		if (e == null && queenRef != null && !queenRef.isRemoved()) {
			return;
		}
		if (!(e instanceof Mob spider) || !spider.isAlive()) {
			dying = 0;
			for (ServerPlayer p : Rift.players(level.getServer())) {
				dev.rubymod.game.Alerts.title(p, "THE QUEEN IS DEAD", "Her hive falls silent", ChatFormatting.GREEN);
				RiftFx.shake(p, 1.2F, 30);
			}
			return;
		}
		lastPos = spider.position();
		float hp = spider.getHealth() / spider.getMaxHealth();
		bar.setProgress(Math.max(0, hp));
		bar.setName(Component.literal("HIVE QUEEN" + (sacs.isEmpty() ? "" : "  •  " + sacs.size() + " EGG SACS: smash them!") + RiftMutations.suffix())
				.withStyle(ChatFormatting.BOLD));
		for (ServerPlayer p : targets) {
			bar.addPlayer(p);
		}
		for (UUID id : crown) {
			Entity c = level.getEntity(id);
			if (c != null) {
				c.teleportTo(spider.getX(), spider.getY(), spider.getZ());
				c.setYRot(spider.yBodyRot);
			}
		}
		if (t % 5 == 0) {
			level.sendParticles(ParticleTypes.ITEM_SLIME, spider.getX(), spider.getY() + 1.5, spider.getZ(), 3, 1.0, 0.5, 1.0, 0.05);
		}
		// Hurt enough: she lays eggs (at 75%, 50%, 25%).
		int due = hp < 0.25F ? 3 : hp < 0.5F ? 2 : hp < 0.75F ? 1 : 0;
		if (laid < due && t >= layAgainAt) {
			laid++;
			layAgainAt = t + 160;
			layEggs(level, spider, targets);
			if (laid >= 2) {
				spider.addEffect(new MobEffectInstance(MobEffects.SPEED, -1, laid - 2, false, false));
			}
		}
		ServerPlayer target = null;
		for (ServerPlayer p : targets) {
			if (target == null || p.distanceToSqr(spider) < target.distanceToSqr(spider)) {
				target = p;
			}
		}
		if (target == null) {
			return;
		}
		if (spider.getTarget() != target) {
			spider.setTarget(target);
		}
		// Acid spit: three blobs in a spread, each leaving a puddle.
		if (--spitAt <= 0 && leapWindup == 0) {
			spitAt = laid >= 2 ? 50 : 70;
			spit(level, spider, target, targets);
		}
		// The leap: she crouches (a green ring where she'll land), then jumps on it.
		if (leapWindup > 0) {
			leapWindup--;
			RiftFx.ring(level, leapTo.add(0, 0.2, 0), 4.0, RiftFx.dust(0x6EE38A, 1.5F), 24);
			RiftFx.ring(level, leapTo.add(0, 0.2, 0), 4.0 * leapWindup / 25.0, RiftFx.dust(0xFFFFFF, 1.0F), 12);
			if (leapWindup == 0) {
				Vec3 d = leapTo.subtract(spider.position());
				spider.setDeltaMovement(d.x * 0.17, 1.0, d.z * 0.17);
				spider.needsSync = true;
				level.playSound(null, spider.blockPosition(), SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 3.0F, 0.4F);
			}
		} else if (--leapAt <= 0) {
			leapAt = laid >= 2 ? 130 : 180;
			leapTo = new Vec3(target.getX(), Math.floor(target.getY()) + 0.1, target.getZ());
			leapWindup = 25;
			level.playSound(null, spider.blockPosition(), SoundEvents.SPIDER_STEP, SoundSource.HOSTILE, 3.0F, 0.5F);
		}
		// Landing from a leap: a slam.
		if (leapTo != null && leapWindup == 0 && spider.onGround() && spider.position().distanceTo(leapTo) < 3.5 && t % 2 == 0 && spider.getDeltaMovement().y <= 0) {
			slam(level, spider, targets);
			leapTo = null;
		}
	}

	private void spit(ServerLevel level, Mob spider, ServerPlayer target, List<ServerPlayer> targets) {
		Vec3 mouth = spider.position().add(0, 1.4, 0).add(spider.getLookAngle().scale(2.0));
		level.playSound(null, spider.blockPosition(), SoundEvents.LLAMA_SPIT, SoundSource.HOSTILE, 2.0F, 0.6F);
		for (int k = -1; k <= 1; k++) {
			Vec3 to = target.position().add(spider.getLookAngle().cross(new Vec3(0, 1, 0)).normalize().scale(k * 2.5));
			// An arc of acid.
			for (double s = 0; s <= 1.0; s += 0.06) {
				Vec3 p = mouth.lerp(to, s).add(0, Math.sin(s * Math.PI) * 3.0, 0);
				level.sendParticles(RiftFx.dust(0x7AE33A, 1.4F), true, false, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0);
			}
			level.sendParticles(ParticleTypes.ITEM_SLIME, to.x, to.y + 0.3, to.z, 12, 0.6, 0.2, 0.6, 0.1);
			for (ServerPlayer p : targets) {
				if (p.position().distanceTo(to) < 2.0) {
					p.hurtServer(level, level.damageSources().mobAttack(spider), 5);
					p.addEffect(new MobEffectInstance(MobEffects.POISON, 60, 0, false, true));
					p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 1, false, true));
				}
			}
			RiftFx.ring(level, to.add(0, 0.1, 0), 1.6, RiftFx.dust(0x3A8A2A, 1.4F), 14);
		}
	}

	private void slam(ServerLevel level, Mob spider, List<ServerPlayer> targets) {
		Vec3 at = spider.position();
		level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 3, 1.5, 0.2, 1.5, 0);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SLIME_BLOCK.defaultBlockState()), at.x, at.y + 0.3, at.z, 60, 2, 0.3, 2, 0.3);
		level.playSound(null, spider.blockPosition(), SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 3.0F, 0.5F);
		level.playSound(null, spider.blockPosition(), SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.HOSTILE, 2.0F, 0.7F);
		for (ServerPlayer p : targets) {
			double d = p.position().distanceTo(at);
			RiftFx.shake(p, (float) Math.max(0.3, 1.4 - d * 0.1), 10);
			if (d < 5) {
				p.hurtServer(level, level.damageSources().mobAttack(spider), laid >= 2 ? 11 : 8);
				Vec3 push = p.position().subtract(at).normalize().scale(1.2);
				p.setDeltaMovement(push.x, 0.7, push.z);
				p.needsSync = true;
			}
		}
	}

	private void layEggs(ServerLevel level, Mob spider, List<ServerPlayer> targets) {
		level.playSound(null, spider.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 3.0F, 1.4F);
		level.playSound(null, spider.blockPosition(), SoundEvents.SPIDER_DEATH, SoundSource.HOSTILE, 3.0F, 0.5F);
		for (ServerPlayer p : targets) {
			dev.rubymod.game.Alerts.title(p, "SHE LAYS HER EGGS", "Smash the sacs before they hatch!", ChatFormatting.GREEN);
		}
		for (int k = 0; k < 3; k++) {
			double a = level.getRandom().nextDouble() * Math.PI * 2;
			double r = 6 + level.getRandom().nextDouble() * (room.half - 12);
			BlockPos at = room.center.offset((int) Math.round(Math.cos(a) * r), 1, (int) Math.round(Math.sin(a) * r));
			Mob core = EntityTypes.HUSK.create(level, EntitySpawnReason.EVENT);
			if (core == null) {
				continue;
			}
			core.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
			core.setNoAi(true);
			core.setSilent(true);
			core.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false));
			set(core, Attributes.MAX_HEALTH, 40 * (1 + 0.5 * (players - 1)));
			core.setHealth(core.getMaxHealth());
			core.setPersistenceRequired();
			core.setCustomName(Component.literal("Egg Sac").withStyle(ChatFormatting.GREEN));
			core.addTag(Rift.TAG);
			level.addFreshEntity(core);
			Rift.MOBS.add(core.getUUID());
			Display.BlockDisplay look = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			UUID lookId = null;
			if (look != null) {
				look.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
				((BlockDisplayAccessor) look).rubymod$setBlockState(Blocks.SLIME_BLOCK.defaultBlockState());
				((DisplayAccessor) look).rubymod$setTransformation(new Transformation(new Vector3f(-0.6F, 0, -0.6F), new Quaternionf(), new Vector3f(1.2F, 1.6F, 1.2F),
						new Quaternionf()));
				look.addTag(Rift.TAG);
				level.addFreshEntity(look);
				lookId = look.getUUID();
			}
			// A trail of slime from her to where it lands.
			RiftFx.line(level, spider.position().add(0, 2, 0), Vec3.atBottomCenterOf(at).add(0, 1, 0), RiftFx.dust(0x6EE38A, 1.2F), 0.6);
			sacs.add(new Sac(core.getUUID(), lookId, t + 200));
		}
	}

	private void tickSacs(ServerLevel level, List<ServerPlayer> targets) {
		for (int i = sacs.size() - 1; i >= 0; i--) {
			Sac s = sacs.get(i);
			Entity core = level.getEntity(s.core());
			Entity look = s.look() == null ? null : level.getEntity(s.look());
			boolean broken = core == null || !core.isAlive();
			if (broken || t >= s.hatchAt()) {
				Vec3 at = look != null ? look.position() : core != null ? core.position() : null;
				if (look != null) {
					look.discard();
				}
				if (core != null && core.isAlive()) {
					core.discard();
					Rift.MOBS.remove(s.core());
				}
				sacs.remove(i);
				if (at == null) {
					continue;
				}
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SLIME_BLOCK.defaultBlockState()), at.x, at.y + 0.8, at.z, 40, 0.4, 0.6, 0.4,
						0.2);
				level.playSound(null, BlockPos.containing(at), SoundEvents.SLIME_DEATH, SoundSource.HOSTILE, 2.0F, broken ? 1.2F : 0.5F);
				if (!broken) {
					// Hatched: a swarm of brood spiders.
					for (int k = 0; k < 3 + players; k++) {
						Mob brood = EntityTypes.CAVE_SPIDER.create(level, EntitySpawnReason.EVENT);
						if (brood != null) {
							brood.snapTo(at.x + level.getRandom().nextDouble() - 0.5, at.y, at.z + level.getRandom().nextDouble() - 0.5, 0, 0);
							brood.setCustomName(Component.literal("Brood").withStyle(ChatFormatting.DARK_GREEN));
							brood.addTag(Rift.TAG);
							level.addFreshEntity(brood);
							Rift.MOBS.add(brood.getUUID());
						}
					}
					for (ServerPlayer p : targets) {
						p.sendOverlayMessage(Component.literal("An egg sac hatched!").withStyle(ChatFormatting.RED));
					}
				}
				continue;
			}
			// Pulsing, faster as it gets close to hatching.
			int left = s.hatchAt() - t;
			if (look != null && t % Math.max(2, left / 20) == 0) {
				float swell = 1.2F + (float) Math.sin(t * 0.5) * 0.1F + (200 - left) / 400F;
				((DisplayAccessor) look).rubymod$setTransformation(new Transformation(new Vector3f(-swell / 2, 0, -swell / 2), new Quaternionf(),
						new Vector3f(swell, swell * 1.3F, swell), new Quaternionf()));
				level.sendParticles(ParticleTypes.ITEM_SLIME, look.getX(), look.getY() + 1, look.getZ(), 2, 0.3, 0.4, 0.3, 0.02);
			}
		}
	}

	private void tickDeath(ServerLevel level) {
		dying++;
		Vec3 at = lastPos == null ? Vec3.atCenterOf(room.center) : lastPos;
		if (dying % 4 == 0) {
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SLIME_BLOCK.defaultBlockState()), at.x, at.y + 1.5, at.z, 50, 2, 1, 2, 0.3);
			level.sendParticles(RiftFx.dust(0x7AE33A, 2.0F), at.x, at.y + 1.5, at.z, 20, 2, 1, 2, 0);
			level.playSound(null, BlockPos.containing(at), SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 2.0F, 0.5F + dying * 0.01F);
		}
		for (UUID id : crown) {
			Entity c = level.getEntity(id);
			if (c != null) {
				c.teleportTo(c.getX(), c.getY() + 0.06, c.getZ());
				c.setYRot(c.getYRot() + 15);
			}
		}
		if (dying >= 50) {
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 1, at.z, 2, 1, 1, 1, 0);
			level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, at.x, at.y + 2, at.z, 150, 2, 2, 2, 0.6);
			remove(level);
			done = true;
		}
	}
}
