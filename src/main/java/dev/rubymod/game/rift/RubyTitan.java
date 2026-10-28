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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The last boss, at the very bottom: the Ruby Titan, a golem three times your height grown
 * over with ruby crystal. Four Ruby Pylons around the hall beam power into it, and while
 * any stands the Titan can't be hurt: break them first. It pounds the ground, rains
 * crystal shards on red circles and calls up Crystal Husks. Half broken, two pylons grow
 * back and it gets faster. It dies in a burst of ruby.
 */
public final class RubyTitan implements RiftBoss {
	private static final String PYLON_TAG = Rift.TAG + "_pylon";
	private final UUID titan;
	/** The Titan itself (to tell why it's gone, if it goes without dying). */
	private Mob titanRef;
	private final java.util.Map<UUID, Mob> pylonRefs = new java.util.HashMap<>();
	private final RiftRoom room;
	private final List<UUID> pylons = new ArrayList<>();
	private final List<UUID> pylonLooks = new ArrayList<>();
	private final List<UUID> crystals = new ArrayList<>();
	private final ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("RUBY TITAN").withStyle(ChatFormatting.BOLD),
			BossEvent.BossBarColor.PINK, BossEvent.BossBarOverlay.NOTCHED_10);
	private final int players;
	private int t;
	private boolean regrown;
	private int poundAt = 100;
	private int poundWindup;
	private int rainAt = 140;
	private final List<Vec3> shards = new ArrayList<>();
	private int shardsIn;
	private int summonAt = 300;
	private int dying = -1;
	private Vec3 lastPos;
	private boolean done;

	private RubyTitan(UUID titan, RiftRoom room, int players) {
		this.titan = titan;
		this.room = room;
		this.players = players;
	}

	static RubyTitan spawn(ServerLevel level, RiftRoom room, int players) {
		Mob golem = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.EVENT);
		if (golem == null) {
			return null;
		}
		golem.snapTo(room.center.getX() + 0.5, room.center.getY() + 1, room.center.getZ() - room.half + 10, 0, 0);
		set(golem, Attributes.SCALE, 3.0);
		set(golem, Attributes.MAX_HEALTH, 700 * RiftBosses.healthScale(players));
		set(golem, Attributes.ATTACK_DAMAGE, 11);
		set(golem, Attributes.FOLLOW_RANGE, 80);
		set(golem, Attributes.KNOCKBACK_RESISTANCE, 1.0);
		golem.setHealth(golem.getMaxHealth());
		golem.setPersistenceRequired();
		golem.setCustomName(Component.literal("Ruby Titan").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		golem.addTag(Rift.TAG);
		golem.addTag(JuggernautMech.BOSS_TAG);
		level.addFreshEntity(golem);
		Rift.MOBS.add(golem.getUUID());
		RubyTitan boss = new RubyTitan(golem.getUUID(), room, players);
		boss.titanRef = golem;
		// Ruby crystals growing out of its shoulders and back.
		float[][] spots = {{-1.6F, 6.6F, -0.4F}, {1.0F, 6.8F, -0.5F}, {-0.3F, 6.0F, -1.4F}, {-1.2F, 4.6F, -1.3F}, {0.7F, 5.2F, -1.4F}};
		for (float[] s : spots) {
			Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (d != null) {
				d.snapTo(golem.getX(), golem.getY(), golem.getZ(), 0, 0);
				((BlockDisplayAccessor) d).rubymod$setBlockState(s[1] > 6.4F ? ModBlocks.RUBY_BLOCK.defaultBlockState() : Blocks.AMETHYST_BLOCK.defaultBlockState());
				((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(s[0], s[1], s[2]), new Quaternionf().rotateXYZ(0.5F, 0.6F, 0.3F),
						new Vector3f(0.7F, 1.4F, 0.7F), new Quaternionf()));
				((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(2);
				d.addTag(Rift.TAG);
				level.addFreshEntity(d);
				boss.crystals.add(d.getUUID());
			}
		}
		for (int i = 0; i < 4; i++) {
			boss.addPylon(level, i);
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

	private void addPylon(ServerLevel level, int i) {
		double a = Math.PI / 4 + i * Math.PI / 2;
		int r = room.half - 9;
		BlockPos at = room.center.offset((int) Math.round(Math.cos(a) * r), 1, (int) Math.round(Math.sin(a) * r));
		Mob core = EntityTypes.HUSK.create(level, EntitySpawnReason.EVENT);
		if (core == null) {
			return;
		}
		core.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
		core.setNoAi(true);
		core.setSilent(true);
		core.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false));
		set(core, Attributes.MAX_HEALTH, 70 * (1 + 0.5 * (players - 1)));
		core.setHealth(core.getMaxHealth());
		core.setPersistenceRequired();
		core.setCustomName(Component.literal("Ruby Pylon").withStyle(ChatFormatting.LIGHT_PURPLE));
		core.addTag(Rift.TAG);
		core.addTag(PYLON_TAG);
		level.addFreshEntity(core);
		Rift.MOBS.add(core.getUUID());
		pylons.add(core.getUUID());
		pylonRefs.put(core.getUUID(), core);
		Display.BlockDisplay look = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (look != null) {
			look.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
			((BlockDisplayAccessor) look).rubymod$setBlockState(ModBlocks.RUBY_BLOCK.defaultBlockState());
			((DisplayAccessor) look).rubymod$setTransformation(new Transformation(new Vector3f(-0.5F, 0, -0.5F), new Quaternionf().rotateY(0.785F),
					new Vector3f(1.0F, 3.2F, 1.0F), new Quaternionf()));
			look.addTag(Rift.TAG);
			level.addFreshEntity(look);
			pylonLooks.add(look.getUUID());
		}
		level.playSound(null, at, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 2.0F, 0.5F);
	}

	@Override
	public boolean shielded(Entity e) {
		return e.getUUID().equals(titan) && !pylons.isEmpty();
	}

	@Override
	public boolean dead() {
		return done;
	}

	@Override
	public net.minecraft.world.entity.LivingEntity body(ServerLevel level) {
		return level.getEntity(titan) instanceof net.minecraft.world.entity.LivingEntity e && e.isAlive() ? e : null;
	}

	@Override
	public String debug() {
		return "titan t" + t + " pylons " + pylons.size() + (regrown ? " regrown" : "") + (dying >= 0 ? " dying" : "");
	}

	@Override
	public void remove(ServerLevel level) {
		for (List<UUID> ids : List.of(List.of(titan), pylons, pylonLooks, crystals)) {
			for (UUID id : ids) {
				Entity e = level.getEntity(id);
				if (e != null) {
					e.discard();
				}
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
		// Pylons: the ones broken go (with their crystal); the rest beam into the Titan.
		for (int i = pylons.size() - 1; i >= 0; i--) {
			Entity p = level.getEntity(pylons.get(i));
			// Not found but never removed: its chunk is still loading, it's there.
			if (p == null && pylonRefs.get(pylons.get(i)) instanceof Mob ref && !ref.isRemoved()) {
				continue;
			}
			if (p == null || !p.isAlive()) {
				Mob ref = pylonRefs.get(pylons.get(i));
				dev.rubymod.RubyMod.LOGGER.info("Ruby Pylon down at tick {}: found {} | removed {} health {} at {}", t, p,
						ref == null ? "?" : ref.getRemovalReason(), ref == null ? -1 : ref.getHealth(), ref == null ? null : ref.position());
				Entity look = i < pylonLooks.size() ? level.getEntity(pylonLooks.get(i)) : null;
				if (look != null) {
					level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ModBlocks.RUBY_BLOCK.defaultBlockState()), look.getX(), look.getY() + 1.5,
							look.getZ(), 80, 0.5, 1.2, 0.5, 0.3);
					level.playSound(null, look.blockPosition(), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.HOSTILE, 2.0F, 0.6F);
					look.discard();
				}
				pylons.remove(i);
				if (i < pylonLooks.size()) {
					pylonLooks.remove(i);
				}
				for (ServerPlayer pl : targets) {
					pl.sendOverlayMessage(Component.literal(pylons.isEmpty() ? "The Titan's shield is DOWN!" : "A pylon shattered! " + pylons.size() + " left")
							.withStyle(ChatFormatting.LIGHT_PURPLE));
				}
			}
		}
		Entity e = level.getEntity(titan);
		if (e == null && titanRef != null && !titanRef.isRemoved()) {
			return; // still loading in: it's there
		}
		if (!(e instanceof Mob golem) || !golem.isAlive()) {
			dev.rubymod.RubyMod.LOGGER.info("Ruby Titan down at tick {}: found {} | removed {} health {} at {} | room {}", t, e,
					titanRef == null ? "?" : titanRef.getRemovalReason(), titanRef == null ? -1 : titanRef.getHealth(),
					titanRef == null ? null : titanRef.position(), room.center);
			dying = 0;
			for (ServerPlayer p : Rift.players(level.getServer())) {
				dev.rubymod.game.Alerts.title(p, "THE TITAN FALLS", "The Rift is breaking apart!", ChatFormatting.GOLD);
				RiftFx.shake(p, 1.6F, 50);
			}
			return;
		}
		lastPos = golem.position();
		float hp = golem.getHealth() / golem.getMaxHealth();
		bar.setProgress(Math.max(0, hp));
		bar.setName(Component.literal((pylons.isEmpty() ? "RUBY TITAN" : "RUBY TITAN  •  SHIELDED: break the pylons!") + RiftMutations.suffix())
				.withStyle(ChatFormatting.BOLD));
		for (ServerPlayer p : targets) {
			bar.addPlayer(p);
		}
		Vec3 chest = golem.position().add(0, 4.5, 0);
		if (t % 4 == 0) {
			for (UUID id : pylons) {
				Entity p = level.getEntity(id);
				if (p != null) {
					RiftFx.line(level, p.position().add(0, 3.2, 0), chest, RiftFx.dust(0xFF3D6E, 1.1F), 0.8);
				}
			}
			if (!pylons.isEmpty()) {
				RiftFx.ring(level, chest.add(0, -4.4, 0), 3.0, RiftFx.dust(0xFF3D6E, 1.2F), 20);
			}
		}
		// The crystals ride on its back.
		for (UUID id : crystals) {
			Entity c = level.getEntity(id);
			if (c != null) {
				c.teleportTo(golem.getX(), golem.getY(), golem.getZ());
				c.setYRot(golem.yBodyRot);
			}
		}
		if (!regrown && hp < 0.5F) {
			regrown = true;
			addPylon(level, 0);
			addPylon(level, 2);
			golem.addEffect(new MobEffectInstance(MobEffects.SPEED, -1, 1, false, false));
			level.playSound(null, golem.blockPosition(), SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 3.0F, 0.6F);
			for (ServerPlayer p : targets) {
				dev.rubymod.game.Alerts.title(p, "THE PYLONS REGROW", "Two more: break them again!", ChatFormatting.LIGHT_PURPLE);
			}
		}
		ServerPlayer target = null;
		for (ServerPlayer p : targets) {
			if (target == null || p.distanceToSqr(golem) < target.distanceToSqr(golem)) {
				target = p;
			}
		}
		if (target == null) {
			return;
		}
		if (golem.getTarget() != target) {
			golem.setTarget(target);
		}
		// Ground pound.
		if (poundWindup > 0) {
			poundWindup--;
			RiftFx.ring(level, golem.position().add(0, 0.2, 0), 7.0 * (1 - poundWindup / 20.0), RiftFx.dust(0xFF3D6E, 1.5F), 28);
			if (poundWindup == 0) {
				pound(level, golem, targets);
			}
		} else if (--poundAt <= 0 && target.distanceTo(golem) < 8) {
			poundAt = regrown ? 70 : 100;
			poundWindup = 20;
			level.playSound(null, golem.blockPosition(), SoundEvents.IRON_GOLEM_REPAIR, SoundSource.HOSTILE, 2.0F, 0.5F);
		}
		// Crystal rain.
		if (--rainAt <= 0) {
			rainAt = regrown ? 130 : 180;
			shards.clear();
			for (ServerPlayer p : targets) {
				for (int k = 0; k < (regrown ? 4 : 3); k++) {
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
					level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ModBlocks.RUBY_BLOCK.defaultBlockState()), s.x, s.y + 0.5, s.z, 30, 0.6, 0.3,
							0.6, 0.2);
					level.playSound(null, BlockPos.containing(s), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.HOSTILE, 1.5F, 0.6F);
					for (ServerPlayer p : targets) {
						if (p.position().distanceTo(s) < 2.2) {
							p.hurtServer(level, level.damageSources().mobAttack(golem), regrown ? 9 : 7);
						}
					}
				}
				shards.clear();
			}
		}
		// Crystal Husks rise from the floor.
		if (--summonAt <= 0) {
			summonAt = regrown ? 240 : 320;
			for (int k = 0; k < Math.min(4, 1 + targets.size()); k++) {
				Rift.summon(room.spawns.get(level.getRandom().nextInt(room.spawns.size())), false);
			}
		}
	}

	private void pound(ServerLevel level, Mob golem, List<ServerPlayer> targets) {
		Vec3 at = golem.position();
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
		for (int ring = 1; ring <= 4; ring++) {
			RiftFx.ring(level, at.add(0, 0.2, 0), ring * 1.8, new BlockParticleOption(ParticleTypes.BLOCK, ModBlocks.RUBY_BLOCK.defaultBlockState()), 16 * ring);
		}
		level.playSound(null, golem.blockPosition(), SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.HOSTILE, 2.5F, 0.4F);
		for (ServerPlayer p : targets) {
			double d = p.position().distanceTo(at);
			RiftFx.shake(p, (float) Math.max(0.3, 1.8 - d * 0.1), 14);
			if (d < 7.5) {
				p.hurtServer(level, level.damageSources().mobAttack(golem), regrown ? 13 : 10);
				Vec3 push = p.position().subtract(at).normalize().scale(1.4);
				p.setDeltaMovement(push.x, 0.8, push.z);
				p.needsSync = true;
			}
		}
	}

	private void tickDeath(ServerLevel level) {
		dying++;
		Vec3 at = lastPos == null ? Vec3.atCenterOf(room.center) : lastPos;
		if (dying % 4 == 0) {
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ModBlocks.RUBY_BLOCK.defaultBlockState()), at.x, at.y + 3, at.z, 60, 1.5, 2.5, 1.5,
					0.4);
			level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 3, at.z, 20, 1.5, 2.5, 1.5, 0.2);
			level.playSound(null, BlockPos.containing(at), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.HOSTILE, 2.0F, 0.5F + dying * 0.01F);
		}
		for (UUID id : crystals) {
			Entity c = level.getEntity(id);
			if (c != null) {
				c.teleportTo(c.getX(), c.getY() + 0.08, c.getZ());
				c.setYRot(c.getYRot() + 12);
			}
		}
		if (dying >= 70) {
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 2, at.z, 3, 1, 1, 1, 0);
			level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, at.x, at.y + 3, at.z, 200, 2, 3, 2, 0.6);
			remove(level);
			done = true;
		}
	}

	static boolean isPylon(LivingEntity e) {
		return e.entityTags().contains(PYLON_TAG);
	}
}
