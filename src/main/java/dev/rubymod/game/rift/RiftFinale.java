package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * The secret fourth act's battle, in the Heart of the Rift (only with all three Rift Keys):
 * echoes of the three bosses you beat this run, one after another at half strength, and
 * then the Ruby Sovereign itself: a giant warden of the Rift, crowned in ruby, that rains
 * crystal, cracks the floor with shockwaves and, hurt, calls its whole court.
 */
public final class RiftFinale implements RiftBoss {
	private final RiftRoom room;
	private final int players;
	private final List<RiftBosses.Boss> echoes;
	private int echo = -1;
	private RiftBoss current;
	private int pause = 40;
	private UUID sovereign;
	private Mob sovereignRef;
	private final ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("THE RUBY SOVEREIGN").withStyle(ChatFormatting.BOLD),
			BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_20);
	private int t;
	private int rainAt = 80;
	private final List<Vec3> shards = new ArrayList<>();
	private int shardsIn;
	private int waveAt = 140;
	private int courtAt;
	private boolean called;
	private int dying = -1;
	private Vec3 lastPos;
	private boolean done;

	private RiftFinale(RiftRoom room, int players, List<RiftBosses.Boss> echoes) {
		this.room = room;
		this.players = players;
		this.echoes = echoes;
	}

	static RiftFinale spawn(ServerLevel level, RiftRoom room, int players, List<RiftBosses.Boss> faced) {
		List<RiftBosses.Boss> echoes = new ArrayList<>(faced);
		if (echoes.isEmpty()) {
			echoes.add(RiftBosses.Boss.JUGGERNAUT);
		}
		return new RiftFinale(room, players, echoes);
	}

	@Override
	public boolean shielded(Entity e) {
		return current != null && current.shielded(e);
	}

	@Override
	public void blocked(ServerLevel level, Entity e, DamageSource source, float amount) {
		if (current != null) {
			current.blocked(level, e, source, amount);
		}
	}

	@Override
	public boolean dead() {
		return done;
	}

	@Override
	public LivingEntity body(ServerLevel level) {
		if (current != null) {
			return current.body(level);
		}
		return sovereign != null && level.getEntity(sovereign) instanceof LivingEntity e && e.isAlive() ? e : null;
	}

	@Override
	public String debug() {
		return "finale echo " + (echo + 1) + "/" + echoes.size() + (current != null ? " [" + current.debug() + "]" : "") + (sovereign != null ? " sovereign" : "")
				+ (dying >= 0 ? " dying" : "");
	}

	@Override
	public void remove(ServerLevel level) {
		if (current != null) {
			current.remove(level);
		}
		if (sovereign != null && level.getEntity(sovereign) instanceof Entity e) {
			e.discard();
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
		// The echoes, one after another.
		if (current != null) {
			current.tick(level, targets);
			if (current.dead()) {
				current = null;
				pause = 80;
				for (ServerPlayer p : targets) {
					dev.rubymod.game.Alerts.title(p, "ECHO BROKEN", echo + 1 < echoes.size() ? "Another stirs..." : "The Sovereign awakens!", ChatFormatting.LIGHT_PURPLE);
				}
			}
			return;
		}
		if (sovereign == null) {
			if (--pause > 0) {
				if (pause % 10 == 0) {
					RiftFx.ring(level, Vec3.atBottomCenterOf(room.center).add(0, 1.2, 0), 3 + (80 - pause) * 0.05, RiftFx.dust(0xFF3D6E, 1.6F), 30);
				}
				return;
			}
			echo++;
			if (echo < echoes.size()) {
				RiftBosses.Boss b = echoes.get(echo);
				current = RiftBosses.spawn(b, level, room, players, RiftMap.FLOORS);
				LivingEntity body = current == null ? null : current.body(level);
				if (body != null) {
					// An echo: half its strength, wreathed in ruby light.
					AttributeInstance hp = body.getAttribute(Attributes.MAX_HEALTH);
					if (hp != null) {
						hp.setBaseValue(hp.getBaseValue() * 0.5);
						body.setHealth(body.getMaxHealth());
					}
					body.setCustomName(Component.literal("Echo of the " + b.title).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
				}
				for (ServerPlayer p : targets) {
					dev.rubymod.game.Alerts.title(p, "ECHO " + (echo + 1) + " OF " + echoes.size(), "The " + b.title + " returns!", ChatFormatting.LIGHT_PURPLE);
					level.playSound(null, p.blockPosition(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6F, 1.2F);
				}
				return;
			}
			wake(level, targets);
			return;
		}
		tickSovereign(level, targets);
	}

	private void wake(ServerLevel level, List<ServerPlayer> targets) {
		Mob w = EntityTypes.WARDEN.create(level, EntitySpawnReason.EVENT);
		if (w == null) {
			done = true;
			return;
		}
		w.snapTo(room.center.getX() + 0.5, room.center.getY() + 1, room.center.getZ() + 0.5, 180, 0);
		set(w, Attributes.SCALE, 1.9);
		set(w, Attributes.MAX_HEALTH, 900 * (1 + 0.65 * (players - 1)));
		set(w, Attributes.ATTACK_DAMAGE, 16);
		set(w, Attributes.FOLLOW_RANGE, 80);
		set(w, Attributes.KNOCKBACK_RESISTANCE, 1.0);
		w.setHealth(w.getMaxHealth());
		w.setPersistenceRequired();
		w.setCustomName(Component.literal("The Ruby Sovereign").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
		w.addTag(Rift.TAG);
		w.addTag(JuggernautMech.BOSS_TAG);
		level.addFreshEntity(w);
		Rift.MOBS.add(w.getUUID());
		sovereign = w.getUUID();
		sovereignRef = w;
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, w.getX(), w.getY() + 2, w.getZ(), 3, 1, 1, 1, 0);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, dev.rubymod.registry.ModBlocks.RUBY_BLOCK.defaultBlockState()), w.getX(), w.getY() + 2,
				w.getZ(), 150, 2, 2, 2, 0.5);
		RiftFx.beam(level, w.position(), 40, ParticleTypes.END_ROD, 0xFFFF3D6E);
		for (ServerPlayer p : targets) {
			dev.rubymod.game.Alerts.title(p, "THE RUBY SOVEREIGN", "The heart of the Rift itself. End it!", ChatFormatting.DARK_RED);
			RiftFx.shake(p, 2.0F, 40);
			bar.addPlayer(p);
			level.playSound(null, p.blockPosition(), SoundEvents.WARDEN_EMERGE, SoundSource.HOSTILE, 2.0F, 0.7F);
		}
	}

	private static void set(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> a, double v) {
		AttributeInstance i = mob.getAttribute(a);
		if (i != null) {
			i.setBaseValue(v);
		}
	}

	private void tickSovereign(ServerLevel level, List<ServerPlayer> targets) {
		Entity e = level.getEntity(sovereign);
		if (e == null && sovereignRef != null && !sovereignRef.isRemoved()) {
			return;
		}
		if (!(e instanceof Mob w) || !w.isAlive()) {
			dying = 0;
			for (ServerPlayer p : Rift.players(level.getServer())) {
				dev.rubymod.game.Alerts.title(p, "THE SOVEREIGN FALLS", "The heart of the Rift is still", ChatFormatting.GOLD);
				RiftFx.shake(p, 2.0F, 60);
			}
			return;
		}
		lastPos = w.position();
		float hp = w.getHealth() / w.getMaxHealth();
		bar.setProgress(Math.max(0, hp));
		bar.setName(Component.literal("THE RUBY SOVEREIGN" + RiftMutations.suffix()).withStyle(ChatFormatting.BOLD));
		for (ServerPlayer p : targets) {
			bar.addPlayer(p);
		}
		// A crown of ruby light, always.
		if (t % 3 == 0) {
			RiftFx.ring(level, w.position().add(0, w.getBbHeight() + 0.4, 0), 1.2, RiftFx.dust(0xFF3D6E, 1.4F), 12);
		}
		ServerPlayer target = null;
		for (ServerPlayer p : targets) {
			if (target == null || p.distanceToSqr(w) < target.distanceToSqr(w)) {
				target = p;
			}
		}
		if (target != null && w.getTarget() != target) {
			w.setTarget(target);
		}
		boolean angry = hp < 0.5F;
		// Crystal rain on red circles.
		if (--rainAt <= 0) {
			rainAt = angry ? 100 : 140;
			shards.clear();
			for (ServerPlayer p : targets) {
				for (int k = 0; k < (angry ? 5 : 3); k++) {
					shards.add(new Vec3(p.getX() + level.getRandom().nextInt(11) - 5, Math.floor(p.getY()) + 0.1, p.getZ() + level.getRandom().nextInt(11) - 5));
				}
			}
			shardsIn = 30;
		}
		if (shardsIn > 0) {
			shardsIn--;
			for (Vec3 s : shards) {
				RiftFx.ring(level, s, 1.8, RiftFx.dust(0xFF3D6E, 1.3F), 14);
				RiftFx.ring(level, s, 1.8 * shardsIn / 30.0, RiftFx.dust(0xFFFFFF, 0.8F), 8);
			}
			if (shardsIn == 0) {
				for (Vec3 s : shards) {
					RiftFx.line(level, s.add(0, 14, 0), s, RiftFx.dust(0xFF3D6E, 1.6F), 0.5);
					level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, dev.rubymod.registry.ModBlocks.RUBY_BLOCK.defaultBlockState()), s.x, s.y + 0.5, s.z,
							30, 0.6, 0.3, 0.6, 0.2);
					level.playSound(null, BlockPos.containing(s), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.HOSTILE, 1.5F, 0.6F);
					for (ServerPlayer p : targets) {
						if (p.position().distanceTo(s) < 2.2) {
							p.hurtServer(level, level.damageSources().mobAttack(w), angry ? 10 : 8);
						}
					}
				}
				shards.clear();
			}
		}
		// A shockwave ring racing out across the floor: jump it!
		if (--waveAt <= 0) {
			waveAt = angry ? 120 : 170;
			courtAt = 1;
			level.playSound(null, w.blockPosition(), SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 3.0F, 0.6F);
		}
		if (courtAt > 0) {
			double r = courtAt * 0.9;
			RiftFx.ring(level, w.position().add(0, 0.3, 0), r, RiftFx.dust(0xFF3D6E, 1.8F), (int) (8 + r * 5));
			for (ServerPlayer p : targets) {
				double d = p.position().multiply(1, 0, 1).distanceTo(w.position().multiply(1, 0, 1));
				if (Math.abs(d - r) < 0.9 && p.onGround()) {
					p.hurtServer(level, level.damageSources().mobAttack(w), 9);
					p.setDeltaMovement(p.position().subtract(w.position()).multiply(1, 0, 1).normalize().scale(1.0).add(0, 0.6, 0));
					p.needsSync = true;
				}
			}
			if (++courtAt > 30) {
				courtAt = 0;
			}
		}
		// Hurt: it calls its court (twice in the fight).
		if (!called && angry) {
			called = true;
			w.addEffect(new MobEffectInstance(MobEffects.SPEED, -1, 0, false, false));
			for (int k = 0; k < 3 + targets.size() * 2; k++) {
				Rift.summon(room.spawns.get(level.getRandom().nextInt(room.spawns.size())), k % 3 == 0);
			}
			for (ServerPlayer p : targets) {
				dev.rubymod.game.Alerts.title(p, "THE COURT ARRIVES", "The Sovereign calls its guard", ChatFormatting.DARK_RED);
			}
		}
	}

	private void tickDeath(ServerLevel level) {
		dying++;
		Vec3 at = lastPos == null ? Vec3.atCenterOf(room.center) : lastPos;
		if (dying % 3 == 0) {
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, dev.rubymod.registry.ModBlocks.RUBY_BLOCK.defaultBlockState()), at.x, at.y + 3, at.z, 80,
					2, 3, 2, 0.5);
			level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 3, at.z, 30, 2, 3, 2, 0.3);
			level.playSound(null, BlockPos.containing(at), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.HOSTILE, 2.5F, 0.4F + dying * 0.01F);
		}
		if (dying >= 90) {
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 2, at.z, 5, 2, 2, 2, 0);
			level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, at.x, at.y + 3, at.z, 300, 3, 3, 3, 0.8);
			remove(level);
			done = true;
		}
	}
}
