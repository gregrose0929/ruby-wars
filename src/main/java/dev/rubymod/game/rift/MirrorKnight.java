package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.mixin.ItemDisplayAccessor;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Mirror Knight (act 2, instead of the Gunship sometimes): a giant knight of polished
 * steel that copies your power. Every so often it holds up a mirror to one of you and takes
 * one of your relics for itself: lightning, fire, frost, explosions, ruby, poison, or
 * plain speed and strength, each its own attack. It raises a Mirror Stance (a glassy shell:
 * shots bounce back at whoever fired them), and at half health splits off two Reflections.
 */
public final class MirrorKnight implements RiftBoss {
	private final UUID knight;
	private Mob knightRef;
	private final RiftRoom room;
	private final int players;
	private final ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("THE MIRROR KNIGHT").withStyle(ChatFormatting.BOLD),
			BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.NOTCHED_10);
	private Relic copied;
	private UUID copiedLook;
	private final List<UUID> shell = new ArrayList<>();
	private final List<UUID> reflections = new ArrayList<>();
	private boolean split;
	private int t;
	private int copyAt = 80;
	private int powerAt = 120;
	private int stanceAt = 260;
	private int stance;
	private final List<Vec3> blasts = new ArrayList<>();
	private int blastsIn;
	private int dying = -1;
	private Vec3 lastPos;
	private boolean done;

	private MirrorKnight(UUID knight, RiftRoom room, int players) {
		this.knight = knight;
		this.room = room;
		this.players = players;
	}

	static MirrorKnight spawn(ServerLevel level, RiftRoom room, int players) {
		Mob k = EntityTypes.WITHER_SKELETON.create(level, EntitySpawnReason.EVENT);
		if (k == null) {
			return null;
		}
		k.snapTo(room.center.getX() + 0.5, room.center.getY() + 1, room.center.getZ() - room.half + 10, 0, 0);
		set(k, Attributes.SCALE, 2.2);
		set(k, Attributes.MAX_HEALTH, 600 * (1 + 0.65 * (players - 1)));
		set(k, Attributes.ATTACK_DAMAGE, 10);
		set(k, Attributes.FOLLOW_RANGE, 80);
		set(k, Attributes.MOVEMENT_SPEED, 0.3);
		set(k, Attributes.KNOCKBACK_RESISTANCE, 1.0);
		k.setHealth(k.getMaxHealth());
		dress(k);
		k.setPersistenceRequired();
		k.setCustomName(Component.literal("The Mirror Knight").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
		k.addTag(Rift.TAG);
		k.addTag(JuggernautMech.BOSS_TAG);
		level.addFreshEntity(k);
		Rift.MOBS.add(k.getUUID());
		MirrorKnight boss = new MirrorKnight(k.getUUID(), room, players);
		boss.knightRef = k;
		for (ServerPlayer p : Rift.players(level.getServer())) {
			boss.bar.addPlayer(p);
		}
		return boss;
	}

	private static void dress(Mob k) {
		k.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
		k.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		k.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
		k.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
		k.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
		k.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
		for (EquipmentSlot s : EquipmentSlot.values()) {
			k.setDropChance(s, 0);
		}
	}

	private static void set(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> a, double v) {
		AttributeInstance i = mob.getAttribute(a);
		if (i != null) {
			i.setBaseValue(v);
		}
	}

	@Override
	public boolean shielded(Entity e) {
		return stance > 0 && e.getUUID().equals(knight);
	}

	/** A shot at the Mirror Stance: it comes straight back. */
	@Override
	public void blocked(ServerLevel level, Entity e, DamageSource source, float amount) {
		if (source.getEntity() instanceof ServerPlayer shooter && !RelicEffects.inEffect) {
			Vec3 from = e.getBoundingBox().getCenter();
			RiftFx.line(level, from, shooter.getEyePosition(), RiftFx.dust(0xFFFFFF, 1.0F), 0.4);
			level.sendParticles(ParticleTypes.END_ROD, from.x, from.y, from.z, 4, 0.5, 0.8, 0.5, 0.05);
			shooter.hurtServer(level, level.damageSources().magic(), Math.min(6, Math.max(1, amount * 0.4F)));
			if (t % 4 == 0) {
				level.playSound(null, e.blockPosition(), SoundEvents.SHIELD_BLOCK.value(), SoundSource.HOSTILE, 1.0F, 1.6F);
			}
		}
	}

	@Override
	public boolean dead() {
		return done;
	}

	@Override
	public LivingEntity body(ServerLevel level) {
		return level.getEntity(knight) instanceof LivingEntity e && e.isAlive() ? e : null;
	}

	@Override
	public String debug() {
		return "knight t" + t + " copied " + copied + " stance " + stance + (split ? " split" : "") + (dying >= 0 ? " dying" : "");
	}

	@Override
	public void remove(ServerLevel level) {
		List<UUID> all = new ArrayList<>(shell);
		all.addAll(reflections);
		all.add(knight);
		if (copiedLook != null) {
			all.add(copiedLook);
		}
		for (UUID id : all) {
			Entity e = level.getEntity(id);
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
		Entity e = level.getEntity(knight);
		if (e == null && knightRef != null && !knightRef.isRemoved()) {
			return;
		}
		if (!(e instanceof Mob k) || !k.isAlive()) {
			dying = 0;
			for (ServerPlayer p : Rift.players(level.getServer())) {
				dev.rubymod.game.Alerts.title(p, "THE MIRROR SHATTERS", "The Knight is broken", ChatFormatting.WHITE);
				RiftFx.shake(p, 1.2F, 30);
			}
			return;
		}
		lastPos = k.position();
		float hp = k.getHealth() / k.getMaxHealth();
		bar.setProgress(Math.max(0, hp));
		bar.setName(Component.literal("THE MIRROR KNIGHT" + (copied != null ? "  •  copying your " + copied.title.toUpperCase() : "")
				+ (stance > 0 ? "  •  MIRROR STANCE: stop shooting!" : "") + RiftMutations.suffix()).withStyle(ChatFormatting.BOLD));
		for (ServerPlayer p : targets) {
			bar.addPlayer(p);
		}
		// The copied relic floats over its head.
		if (copiedLook != null && level.getEntity(copiedLook) instanceof Display.ItemDisplay d) {
			d.teleportTo(k.getX(), k.getY() + k.getBbHeight() + 0.9, k.getZ());
			((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY(t * 0.08F), new Vector3f(1.1F, 1.1F, 1.1F),
					new Quaternionf()));
		}
		if (t % 6 == 0) {
			level.sendParticles(ParticleTypes.END_ROD, k.getX(), k.getY() + 2.5, k.getZ(), 1, 0.6, 1.0, 0.6, 0.01);
		}
		if (!split && hp < 0.5F) {
			split = true;
			splitOff(level, k, targets);
		}
		ServerPlayer target = null;
		for (ServerPlayer p : targets) {
			if (target == null || p.distanceToSqr(k) < target.distanceToSqr(k)) {
				target = p;
			}
		}
		if (target == null) {
			return;
		}
		if (k.getTarget() != target) {
			k.setTarget(target);
		}
		// Copy someone's relic.
		if (--copyAt <= 0) {
			copyAt = split ? 240 : 300;
			copy(level, k, targets);
		}
		// The Mirror Stance.
		if (stance > 0) {
			stance--;
			k.setDeltaMovement(0, k.getDeltaMovement().y, 0);
			if (t % 3 == 0) {
				RiftFx.ring(level, k.position().add(0, 0.2 + (t % 15) * 0.3, 0), 2.2, RiftFx.dust(0xFFFFFF, 1.2F), 18);
			}
			if (stance == 0) {
				dropShell(level);
			}
		} else if (--stanceAt <= 0) {
			stanceAt = split ? 200 : 260;
			stance = 60;
			raiseShell(level, k, targets);
		}
		// The copied power.
		if (copied != null && --powerAt <= 0 && stance == 0) {
			powerAt = split ? 55 : 75;
			usePower(level, k, target, targets);
		}
		if (blastsIn > 0) {
			blastsIn--;
			for (Vec3 b : blasts) {
				RiftFx.ring(level, b, 2.5, RiftFx.dust(0xFFD040, 1.3F), 14);
				RiftFx.ring(level, b, 2.5 * blastsIn / 25.0, RiftFx.dust(0xFFFFFF, 0.8F), 8);
			}
			if (blastsIn == 0) {
				for (Vec3 b : blasts) {
					level.sendParticles(ParticleTypes.EXPLOSION, b.x, b.y + 0.5, b.z, 2, 0.5, 0.3, 0.5, 0);
					level.playSound(null, BlockPos.containing(b), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.2F, 1.1F);
					for (ServerPlayer p : targets) {
						if (p.position().distanceTo(b) < 2.6) {
							p.hurtServer(level, level.damageSources().mobAttack(k), 8);
						}
					}
				}
				blasts.clear();
			}
		}
		// The Reflections fade if the Knight is far gone.
		reflections.removeIf(id -> !(level.getEntity(id) instanceof LivingEntity r) || !r.isAlive());
	}

	private void copy(ServerLevel level, Mob k, List<ServerPlayer> targets) {
		List<Relic> pool = new ArrayList<>();
		ServerPlayer victim = null;
		for (ServerPlayer p : targets) {
			Rift.Runner r = Rift.runner(p);
			if (r != null) {
				for (Relic x : r.relics) {
					if (x.tier != Relic.Tier.BOND) {
						pool.add(x);
					}
				}
				victim = victim == null || level.getRandom().nextBoolean() ? p : victim;
			}
		}
		copied = pool.isEmpty() ? Relic.QUICK_HANDS : pool.get(level.getRandom().nextInt(pool.size()));
		if (victim != null) {
			// A beam of light from the victim's relic into the Knight's mirror.
			RiftFx.line(level, victim.position().add(0, 1.2, 0), k.position().add(0, 3, 0), RiftFx.dust(0xFFFFFF, 1.4F), 0.4);
		}
		if (copiedLook != null && level.getEntity(copiedLook) instanceof Entity old) {
			old.discard();
		}
		Display.ItemDisplay d = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d != null) {
			d.snapTo(k.getX(), k.getY() + k.getBbHeight() + 0.9, k.getZ(), 0, 0);
			((ItemDisplayAccessor) d).rubymod$setItemStack(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(copied.icon))));
			((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(2);
			d.addTag(Rift.TAG);
			level.addFreshEntity(d);
			copiedLook = d.getUUID();
		}
		RiftFx.burst(level, k.position().add(0, 3, 0), copied.element != Relic.Element.NONE ? copied.element.color : 0xFFFFFFFF, 40, 1.0);
		level.playSound(null, k.blockPosition(), SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.HOSTILE, 2.5F, 0.8F);
		for (ServerPlayer p : targets) {
			dev.rubymod.game.Alerts.title(p, "COPIED: " + copied.title.toUpperCase(),
					(victim != null ? victim.getName().getString() + "'s relic" : "Your relic") + " turned against you", ChatFormatting.WHITE);
		}
		powerAt = 40;
	}

	private void usePower(ServerLevel level, Mob k, ServerPlayer target, List<ServerPlayer> targets) {
		Vec3 at = k.position();
		switch (copied.element) {
			case SHOCK -> {
				for (ServerPlayer p : targets) {
					if (p.distanceTo(k) < 20) {
						RiftFx.arc(level, at.add(0, 3, 0), p.getBoundingBox().getCenter(), 0xFF66E0FF);
						p.hurtServer(level, level.damageSources().mobAttack(k), 6);
					}
				}
				level.playSound(null, k.blockPosition(), SoundEvents.TRIDENT_THUNDER.value(), SoundSource.HOSTILE, 1.5F, 1.4F);
			}
			case FIRE -> {
				for (int ring = 1; ring <= 3; ring++) {
					RiftFx.ring(level, at.add(0, 0.3, 0), ring * 2.0, ParticleTypes.FLAME, 14 * ring);
				}
				for (ServerPlayer p : targets) {
					if (p.distanceTo(k) < 6.5) {
						p.igniteForSeconds(4);
						p.hurtServer(level, level.damageSources().mobAttack(k), 5);
					}
				}
				level.playSound(null, k.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 2.0F, 0.6F);
			}
			case FROST -> {
				level.sendParticles(ParticleTypes.SNOWFLAKE, at.x, at.y + 1.5, at.z, 120, 5, 1, 5, 0.1);
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState()), at.x, at.y + 1, at.z, 50, 3, 0.5, 3, 0.2);
				for (ServerPlayer p : targets) {
					if (p.distanceTo(k) < 9) {
						p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 70, 3, false, true));
						p.hurtServer(level, level.damageSources().mobAttack(k), 4);
					}
				}
				level.playSound(null, k.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 2.0F, 0.6F);
			}
			case EXPLOSIVE -> {
				blasts.clear();
				for (ServerPlayer p : targets) {
					blasts.add(new Vec3(p.getX(), Math.floor(p.getY()) + 0.1, p.getZ()));
					blasts.add(new Vec3(p.getX() + level.getRandom().nextInt(7) - 3, Math.floor(p.getY()) + 0.1, p.getZ() + level.getRandom().nextInt(7) - 3));
				}
				blastsIn = 25;
			}
			case RUBY -> {
				k.heal(k.getMaxHealth() * 0.03F);
				RiftFx.line(level, target.position().add(0, 1, 0), at.add(0, 2.5, 0), RiftFx.dust(0xFF3D6E, 1.2F), 0.4);
				target.hurtServer(level, level.damageSources().mobAttack(k), 5);
				level.sendParticles(ParticleTypes.HEART, at.x, at.y + 4, at.z, 4, 0.5, 0.3, 0.5, 0);
			}
			case POISON -> {
				level.sendParticles(RiftFx.dust(0x7AE33A, 2.0F), at.x, at.y + 1, at.z, 80, 4, 1, 4, 0);
				for (ServerPlayer p : targets) {
					if (p.distanceTo(k) < 10) {
						p.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 1, false, true));
					}
				}
			}
			default -> {
				// A plain relic: it's just faster and harder-hitting while it lasts.
				k.addEffect(new MobEffectInstance(MobEffects.SPEED, 80, 1, false, false));
				k.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 80, 1, false, false));
				Vec3 d = target.position().subtract(at).multiply(1, 0, 1).normalize();
				k.setDeltaMovement(d.x * 1.4, 0.3, d.z * 1.4);
				k.needsSync = true;
				level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 0.5, at.z, 20, 0.5, 0.2, 0.5, 0.05);
				level.playSound(null, k.blockPosition(), SoundEvents.BREEZE_JUMP, SoundSource.HOSTILE, 2.0F, 0.7F);
			}
		}
	}

	private void raiseShell(ServerLevel level, Mob k, List<ServerPlayer> targets) {
		dropShell(level);
		float[][] panes = {{-1.4F, 0, -1.4F, 2.8F, 4.6F, 0.15F}, {-1.4F, 0, 1.25F, 2.8F, 4.6F, 0.15F}, {-1.4F, 0, -1.4F, 0.15F, 4.6F, 2.8F},
				{1.25F, 0, -1.4F, 0.15F, 4.6F, 2.8F}};
		for (float[] p : panes) {
			Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (d != null) {
				d.snapTo(k.getX(), k.getY(), k.getZ(), 0, 0);
				((BlockDisplayAccessor) d).rubymod$setBlockState(Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState());
				((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(p[0], p[1], p[2]), new Quaternionf(), new Vector3f(p[3], p[4], p[5]),
						new Quaternionf()));
				d.addTag(Rift.TAG);
				level.addFreshEntity(d);
				shell.add(d.getUUID());
			}
		}
		level.playSound(null, k.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 2.5F, 1.6F);
		for (ServerPlayer p : targets) {
			p.sendOverlayMessage(Component.literal("MIRROR STANCE: your shots will bounce back!").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
		}
	}

	private void dropShell(ServerLevel level) {
		for (UUID id : shell) {
			Entity d = level.getEntity(id);
			if (d != null) {
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState()), d.getX(), d.getY() + 2, d.getZ(), 30,
						1, 1.5, 1, 0.2);
				d.discard();
			}
		}
		if (!shell.isEmpty()) {
			level.playSound(null, BlockPos.containing(lastPos == null ? Vec3.atCenterOf(room.center) : lastPos), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE,
					2.0F, 1.0F);
		}
		shell.clear();
	}

	private void splitOff(ServerLevel level, Mob k, List<ServerPlayer> targets) {
		for (int i = 0; i < 2; i++) {
			Mob r = EntityTypes.WITHER_SKELETON.create(level, EntitySpawnReason.EVENT);
			if (r == null) {
				continue;
			}
			r.snapTo(k.getX() + (i == 0 ? 3 : -3), k.getY(), k.getZ(), k.getYRot(), 0);
			set(r, Attributes.SCALE, 1.3);
			set(r, Attributes.MAX_HEALTH, 70 * (1 + 0.5 * (players - 1)));
			r.setHealth(r.getMaxHealth());
			dress(r);
			r.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 10, 0, false, false));
			r.setCustomName(Component.literal("Reflection").withStyle(ChatFormatting.GRAY));
			r.addTag(Rift.TAG);
			level.addFreshEntity(r);
			Rift.MOBS.add(r.getUUID());
			reflections.add(r.getUUID());
			RiftFx.line(level, k.position().add(0, 2, 0), r.position().add(0, 1, 0), RiftFx.dust(0xFFFFFF, 1.4F), 0.3);
		}
		level.playSound(null, k.blockPosition(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.HOSTILE, 3.0F, 0.7F);
		for (ServerPlayer p : targets) {
			dev.rubymod.game.Alerts.title(p, "REFLECTIONS", "The Knight splits its image in two", ChatFormatting.WHITE);
		}
	}

	private void tickDeath(ServerLevel level) {
		dying++;
		Vec3 at = lastPos == null ? Vec3.atCenterOf(room.center) : lastPos;
		if (dying == 1) {
			dropShell(level);
			for (UUID id : reflections) {
				if (level.getEntity(id) instanceof LivingEntity r) {
					r.discard();
					Rift.MOBS.remove(id);
				}
			}
		}
		if (dying % 4 == 0) {
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState()), at.x, at.y + 2.5, at.z, 50, 1.2, 2,
					1.2, 0.4);
			level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 2.5, at.z, 15, 1.2, 2, 1.2, 0.15);
			level.playSound(null, BlockPos.containing(at), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 2.0F, 0.6F + dying * 0.01F);
		}
		if (dying >= 50) {
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 2, at.z, 2, 1, 1, 1, 0);
			level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, at.x, at.y + 3, at.z, 150, 2, 2, 2, 0.6);
			remove(level);
			done = true;
		}
	}
}
