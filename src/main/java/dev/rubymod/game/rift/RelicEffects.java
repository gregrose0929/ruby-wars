package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.entity.GrenadeEntity;
import dev.rubymod.gun.GunItem;
import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.mixin.ItemDisplayAccessor;
import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModItems;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What every relic does. Hooks: guns (fire rate, ammo, extra shots, bounces, damage),
 * hits and kills (lightning, fire, ice, explosions, statues, leeches, the dead rising),
 * getting hurt (mirror, slow time), abilities (double jump, blink), and things that live
 * in the world for a while: drones, turrets, black holes, burning ground, frozen enemies,
 * shadow clones, orbital strikes.
 */
public final class RelicEffects {
	/** Per runner: counters, cooldowns and their drones. */
	public static final class State {
		int hits;
		long orbitalAt;
		/** Mid-air jumps used since last on the ground. */
		int airJumps;
		long blinkAt;
		boolean bulletTimeUsed;
		double fallFrom = Double.NaN;
		final List<UUID> tank = new ArrayList<>();
		final List<UUID> turret = new ArrayList<>();
		Vec3 turretAt;
		int tankCooldown;
		int turretCooldown;
		boolean phoenixUsed;
		/** Second-wave relics: Echo Shot count, Static Charge, Hall of Mirrors cooldown, Ground Pound, Overclock, Meteor kills, Rewind. */
		int echo;
		float charge;
		long mirrorsAt;
		boolean pounding;
		/** Charms: Rage Brew's double damage, a charm's glow, and Meteor Call's second shower. */
		long rageUntil;
		long charmAura;
		int charmColor;
		long meteorCallAt;
		/** Where a Ground Pound started (Meteor Drop), and when Gravity Overload can next open a hole. */
		double poundFrom;
		long gravityAt;
		long overclockUntil;
		int meteorKills;
		int rewinds;
		final java.util.ArrayDeque<Object[]> history = new java.util.ArrayDeque<>();
		UUID dragon;
		long dragonAt;
		UUID hunter;
		/** Abilities: untouchable mid-Dash, no fall damage after a Grapple, Overdrive running. */
		long untouchableUntil;
		long noFallUntil;
		long overdriveUntil;
		/** Bottomless Mag: shots in a row (spinning up), and when the last one was. */
		int spin;
		long lastShot;
		int thunder;
		int still;
		boolean iceShield;
		Vec3 lastPos;
	}

	private record Bomblet(Vec3 at, long when, UUID owner) {
	}

	private static final List<Bomblet> BOMBLETS = new ArrayList<>();
	/** Cluster Grenades in flight: where each was last seen, and whose. */
	private static final Map<UUID, Bomblet> GRENADES = new HashMap<>();

	/** Anyone on the team has this (Bonds and team-wide effects). */
	static boolean team(ServerLevel level, Relic r) {
		for (ServerPlayer p : Rift.players(level.getServer())) {
			if (has(p, r)) {
				return true;
			}
		}
		return false;
	}

	/** Phoenix: instead of going down, a burst of flame and back on your feet. */
	static void phoenix(ServerPlayer p) {
		ServerLevel level = (ServerLevel) p.level();
		p.setHealth(p.getMaxHealth() * 0.5F);
		blast(level, p, p.position().add(0, 1, 0), 5.5, 20, 0xFFFF7A2E, false);
		level.sendParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 1, p.getZ(), 80, 1.2, 1.2, 1.2, 0.2);
		level.sendParticles(ParticleTypes.LAVA, p.getX(), p.getY() + 1, p.getZ(), 20, 1, 1, 1, 0);
		RiftFx.beam(level, p.position(), 16, ParticleTypes.FLAME, 0xFFFF7A2E);
		level.playSound(null, p.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.5F, 0.5F);
		level.playSound(null, p.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0F, 1.2F);
		RiftFx.screen(p, "flash", 0xFFFF7A2E, 20);
		dev.rubymod.game.Alerts.title(p, "PHOENIX", "Back from the flames (once an act)", ChatFormatting.GOLD);
	}

	/** Headhunter: a headshot arcs into the nearest other enemy for most of the damage. */
	public static void headshot(ServerPlayer p, Entity target, float damage) {
		if (!has(p, Relic.HEADHUNTER) || inEffect || !(p.level() instanceof ServerLevel level)) {
			return;
		}
		Vec3 from = target.getBoundingBox().getCenter();
		List<LivingEntity> near = enemiesNear(level, from, 10, target);
		near.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(target)));
		for (int i = 0; i < Math.min(lv(p, Relic.HEADHUNTER), near.size()); i++) {
			LivingEntity next = near.get(i);
			RiftFx.arc(level, from, next.getEyePosition(), 0xFFFFD040);
			level.sendParticles(ParticleTypes.CRIT, next.getX(), next.getEyeY(), next.getZ(), 12, 0.2, 0.2, 0.2, 0.3);
			hurt(p, next, damage * 0.75F, 0xFFFFD040);
		}
	}

	record Frozen(UUID ice, long until) {
	}

	record Patch(Vec3 at, long until, UUID owner) {
	}

	private record Hole(Vec3 at, long until, UUID owner, UUID core) {
	}

	private record Strike(Vec3 at, long when, UUID owner) {
	}

	record Ally(long until, UUID owner, boolean isClone) {
	}

	private record Statue(UUID display, long when) {
	}

	static final Map<UUID, Frozen> FROZEN = new HashMap<>();
	private static final Map<UUID, Integer> CHILL = new HashMap<>();
	static final List<Patch> PATCHES = new ArrayList<>();
	private static final List<Hole> HOLES = new ArrayList<>();
	private static final List<Strike> STRIKES = new ArrayList<>();
	static final Map<UUID, Ally> ALLIES = new HashMap<>();
	private static final List<Statue> STATUES = new ArrayList<>();
	/** Damage dealt by a relic (so it doesn't set off more relics forever), and its colour for the damage number. */
	static boolean inEffect;
	public static int effectColor = -1;

	private RelicEffects() {
	}

	private static Rift.Runner runner(Entity e) {
		return Rift.runner(e);
	}

	static boolean has(ServerPlayer p, Relic r) {
		Rift.Runner run = runner(p);
		return run != null && run.has(r);
	}

	/** 0 without the relic; its stars (1-3) with it. */
	static int lv(ServerPlayer p, Relic r) {
		Rift.Runner run = runner(p);
		return run == null ? 0 : run.level(r);
	}

	static long now(ServerLevel level) {
		return level.getGameTime();
	}

	// ----- Guns -----

	public static float fireIntervalScale(ServerPlayer p) {
		float scale = switch (lv(p, Relic.QUICK_HANDS)) {
			case 0 -> 1.0F;
			case 1 -> 0.5F;
			case 2 -> 0.4F;
			default -> 0.33F;
		};
		if (RiftAbilities.overdriven(p)) {
			scale *= 0.5F;
		}
		Rift.Runner oc = runner(p);
		if (oc != null && oc.has(Relic.OVERCLOCK) && p.level().getGameTime() < oc.fx.overclockUntil) {
			scale *= 0.5F;
		}
		Rift.Runner r = runner(p);
		if (r != null && r.has(Relic.BOTTOMLESS_MAG)) {
			// Spinning up: 2% faster every shot in a row, up to twice as fast (more with stars).
			float most = 1.0F / (1 + r.level(Relic.BOTTOMLESS_MAG) * 0.5F + 0.5F);
			if (RelicCombos.on(r, RelicCombos.Duo.BULLET_HOSE)) {
				most = Math.min(most, 1.0F / 3);
			}
			scale *= Math.max(most, 1.0F - r.fx.spin * 0.02F);
		}
		return scale;
	}

	public static boolean infiniteAmmo(ServerPlayer p) {
		return has(p, Relic.BOTTOMLESS_MAG) || has(p, Relic.BLOOD_AMMO);
	}

	public static int shotMultiplier(ServerPlayer p) {
		return 1 + lv(p, Relic.TWIN_BARRELS);
	}

	public static int extraBounces(ServerPlayer p) {
		int l = lv(p, Relic.RICOCHET);
		return l == 0 ? 0 : 1 + 2 * l;
	}

	/** Ricochet Rounds: after a bounce, bend toward the nearest enemy it can see. */
	public static Vec3 bounceAim(ServerLevel level, ServerPlayer p, Vec3 from, Vec3 reflected) {
		if (!has(p, Relic.RICOCHET)) {
			return reflected;
		}
		LivingEntity target = nearestEnemy(level, from, 24, null, true);
		if (target == null) {
			return reflected;
		}
		Vec3 to = target.getBoundingBox().getCenter().subtract(from).normalize();
		return reflected.scale(0.15).add(to.scale(0.85)).normalize();
	}

	public static float damage(ServerPlayer p, Entity target, float dmg, boolean headshot) {
		Rift.Runner r = runner(p);
		if (r == null) {
			return dmg;
		}
		if (headshot && r.has(Relic.HEADHUNTER)) {
			dmg *= 3 + r.level(Relic.HEADHUNTER);
		}
		if (RiftAbilities.overdriven(p)) {
			dmg *= 1.5F;
		}
		if (p.level().getGameTime() < r.fx.rageUntil) {
			dmg *= 2;
		}
		if (r.has(Relic.THE_HUNTED)) {
			dmg *= 2 + r.level(Relic.THE_HUNTED);
		}
		if (r.has(Relic.LUCKY_COIN) && !inEffect && (p.getRandom().nextFloat() < 0.05F + 0.05F * r.level(Relic.LUCKY_COIN)
				|| headshot && RelicCombos.on(r, RelicCombos.Duo.JACKPOT))) {
			dmg *= 4 + r.level(Relic.LUCKY_COIN);
			lastCrit = target.getUUID();
			if (p.level() instanceof ServerLevel level) {
				Vec3 c = target.getBoundingBox().getCenter();
				level.sendParticles(ParticleTypes.WAX_ON, c.x, c.y + 0.5, c.z, 12, 0.3, 0.4, 0.3, 0.6);
				level.sendParticles(RiftFx.dust(0xFFD040, 1.6F), c.x, c.y + 0.5, c.z, 10, 0.3, 0.4, 0.3, 0);
				level.playSound(null, target.blockPosition(), SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 1.0F, 1.6F);
			}
		}
		if (r.has(Relic.GLASS_CANNON)) {
			dmg *= 2 + r.level(Relic.GLASS_CANNON);
		}
		if (r.has(Relic.BLOOD_AMMO)) {
			dmg *= 2.5F + r.level(Relic.BLOOD_AMMO);
		}
		if (r.has(Relic.BERSERKER)) {
			float missing = 1 - p.getHealth() / p.getMaxHealth();
			dmg *= 1 + (2 + r.level(Relic.BERSERKER)) * missing;
		}
		return dmg;
	}

	public static void onShot(ServerLevel level, ServerPlayer p, ItemStack held) {
		Rift.Runner r = runner(p);
		if (r == null) {
			return;
		}
		if (r.has(Relic.ECHO_SHOT) && held.getItem() instanceof GunItem g
				&& ++r.fx.echo % (RelicCombos.on(r, RelicCombos.Duo.FIRING_SQUAD) ? Math.min(3, 6 - r.level(Relic.ECHO_SHOT)) : 6 - r.level(Relic.ECHO_SHOT)) == 0) {
			ECHOES.add(new Echo(p.getUUID(), level.getGameTime() + 8, g.stats(held)));
		}
		if (r.has(Relic.STATIC_CHARGE) && r.fx.charge >= 25) {
			release(level, p, r);
		}
		if (r.has(Relic.BOTTOMLESS_MAG)) {
			long now = level.getGameTime();
			r.fx.spin = now - r.fx.lastShot <= 12 ? r.fx.spin + 1 : 0;
			r.fx.lastShot = now;
			if (r.fx.spin >= 25 && r.fx.spin % 3 == 0) {
				// Spun all the way up: the barrel glows.
				Vec3 muzzle = p.getEyePosition().add(p.getLookAngle().scale(1.0));
				level.sendParticles(ParticleTypes.SMALL_FLAME, muzzle.x, muzzle.y - 0.2, muzzle.z, 2, 0.05, 0.05, 0.05, 0.01);
			}
		}
		if (r.has(Relic.BLOOD_AMMO) && p.getHealth() > 2) {
			p.setHealth(p.getHealth() - 0.5F);
			level.sendParticles(RiftFx.dust(0xB00020, 1.0F), p.getX(), p.getY() + 1, p.getZ(), 3, 0.2, 0.3, 0.2, 0);
		}
		if (r.has(Relic.ARSENAL)) {
			// Every other gun in the hotbar fires too.
			for (int i = 0; i < 9; i++) {
				ItemStack s = p.getInventory().getItem(i);
				if (s != held && s.getItem() instanceof GunItem gun) {
					dev.rubymod.gun.Gunfire.ghostShot(level, p, gun.stats(s));
				}
			}
		}
	}

	/** Overkill: what a killing shot didn't need goes on into the next enemy. */
	public static void overkill(ServerPlayer p, Entity target, float excess) {
		excess *= Math.max(1, lv(p, Relic.OVERKILL));
		if (excess < 1 || !has(p, Relic.OVERKILL) || !(p.level() instanceof ServerLevel level)) {
			return;
		}
		boolean unstoppable = RelicCombos.on(p, RelicCombos.Duo.UNSTOPPABLE);
		java.util.Set<Entity> done = new java.util.HashSet<>();
		Entity from = target;
		for (int jump = 0; jump < (unstoppable ? 8 : 1) && excess >= 1; jump++) {
			done.add(from);
			LivingEntity next = null;
			for (LivingEntity e : enemiesNear(level, from.position(), 10, null)) {
				if (!done.contains(e) && (next == null || e.distanceToSqr(from) < next.distanceToSqr(from))) {
					next = e;
				}
			}
			if (next == null) {
				return;
			}
			Vec3 a = from.getBoundingBox().getCenter();
			Vec3 b = next.getBoundingBox().getCenter();
			RiftFx.line(level, a, b, RiftFx.dust(0xFF4040, 1.2F), 0.3);
			float health = next.getHealth();
			hurt(p, next, excess, 0xFFFF4040);
			if (!unstoppable) {
				return;
			}
			// Each jump goes off like a shell, and carries on if it killed.
			RiftFx.line(level, a, b, ParticleTypes.FLAME, 0.5);
			blast(level, p, b, 2.2, 3 + excess * 0.3F, 0xFFFF4040, false);
			if (next.isAlive()) {
				return;
			}
			excess -= health;
			from = next;
		}
	}

	// ----- Hits and kills -----

	/** Relic damage: with a colour, and without setting off more relics. */
	static void hurt(ServerPlayer owner, LivingEntity target, float amount, int color) {
		if (!target.isAlive() || !(owner.level() instanceof ServerLevel level)) {
			return;
		}
		boolean before = inEffect;
		int beforeColor = effectColor;
		inEffect = true;
		effectColor = color;
		try {
			target.setInvulnerableTime(0);
			target.hurtServer(level, owner.damageSources().playerAttack(owner), amount);
		} finally {
			inEffect = before;
			effectColor = beforeColor;
		}
	}

	public static void onHit(ServerPlayer p, LivingEntity target, float amount) {
		Rift.Runner r = runner(p);
		if (r == null || inEffect || !Rift.isEnemy(target) || !(p.level() instanceof ServerLevel level)) {
			return;
		}
		boolean boss = JuggernautMech.isBoss(target);
		if (r.has(Relic.CHAIN_LIGHTNING)) {
			List<LivingEntity> near = enemiesNear(level, target.position(), 7, target);
			near.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(target)));
			for (int i = 0; i < Math.min(1 + 2 * r.level(Relic.CHAIN_LIGHTNING), near.size()); i++) {
				LivingEntity e = near.get(i);
				RiftFx.arc(level, target.getBoundingBox().getCenter(), e.getBoundingBox().getCenter(), 0xFF66E0FF);
				hurt(p, e, Math.max(3, amount * 0.6F), 0xFF66E0FF);
				RelicCombos.shocked(level, p, e, true);
				if (r.has(Relic.PLASMA_STORM)) {
					e.igniteForSeconds(4);
				}
			}
			if (!near.isEmpty()) {
				level.playSound(null, target.blockPosition(), SoundEvents.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 0.25F, 2.0F);
			}
		}
		if (r.has(Relic.VENOM_ROUNDS)) {
			poison(level, p, target, r.level(Relic.VENOM_ROUNDS));
		}
		if (FROZEN.containsKey(target.getUUID()) && (r.has(Relic.CHAIN_LIGHTNING) || r.has(Relic.THUNDERCLAP) || r.has(Relic.STATIC_CHARGE))
				&& team(level, Relic.SUPERCONDUCTOR)) {
			superconduct(level, p, target);
		}
		if (r.has(Relic.NAPALM)) {
			target.igniteForSeconds(3 + 2 * r.level(Relic.NAPALM));
			PATCHES.add(new Patch(new Vec3(target.getX(), Math.floor(target.getY()) + 0.05, target.getZ()), now(level) + 40 + 40 * r.level(Relic.NAPALM), p.getUUID()));
		}
		if (r.has(Relic.CRYO) && !boss) {
			Frozen f = FROZEN.get(target.getUUID());
			if (f != null) {
				shatter(level, p, target, amount);
			} else {
				int chill = CHILL.merge(target.getUUID(), 1, Integer::sum);
				level.sendParticles(ParticleTypes.SNOWFLAKE, target.getX(), target.getY() + 1, target.getZ(), 6, 0.3, 0.5, 0.3, 0.02);
				if (chill >= 4 - r.level(Relic.CRYO)) {
					CHILL.remove(target.getUUID());
					freeze(level, target);
				}
			}
		}
		if (r.has(Relic.MIDAS) && !boss && target instanceof Mob mob
				&& level.getRandom().nextFloat() < (RiftEnemies.twistOf(mob) != null ? 0.03F : 0.08F) * r.level(Relic.MIDAS)) {
			statue(level, p, mob);
			return;
		}
		if (r.has(Relic.THUNDERCLAP) && ++r.fx.thunder % (5 - r.level(Relic.THUNDERCLAP)) == 0) {
			var bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
			if (bolt != null) {
				bolt.snapTo(target.getX(), target.getY(), target.getZ(), 0, 0);
				bolt.setVisualOnly(true);
				level.addFreshEntity(bolt);
			}
			hurt(p, target, 12, 0xFF66E0FF);
			RelicCombos.shocked(level, p, target, true);
			for (LivingEntity e : enemiesNear(level, target.position(), 2.5, target)) {
				hurt(p, e, 6, 0xFF66E0FF);
			}
		}
		if (r.has(Relic.BLACK_HOLE) && ++r.fx.hits % (13 - 3 * r.level(Relic.BLACK_HOLE)) == 0) {
			blackHole(level, p, target.position().add(0, 1, 0));
		}
	}

	public static void onKill(ServerPlayer p, LivingEntity victim) {
		Rift.Runner r = runner(p);
		if (r == null || !(p.level() instanceof ServerLevel level) || JuggernautMech.isBoss(victim)) {
			return;
		}
		Vec3 at = victim.getBoundingBox().getCenter();
		if (r.has(Relic.BLOOD_AMMO)) {
			p.heal(1.0F);
		}
		if (victim.getUUID().equals(lastCrit) && r.has(Relic.LUCKY_COIN)) {
			boolean jackpot = RelicCombos.on(r, RelicCombos.Duo.JACKPOT);
			Rift.gold += jackpot ? 12 : 4;
			if (jackpot) {
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.GOLD_BLOCK.defaultBlockState()), at.x, at.y, at.z, 30, 0.4, 0.4, 0.4, 0.3);
				level.playSound(null, victim.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2F, 1.8F);
			}
			level.sendParticles(ParticleTypes.WAX_ON, at.x, at.y, at.z, 20, 0.4, 0.4, 0.4, 0.8);
			level.playSound(null, victim.blockPosition(), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 0.6F, 2.0F);
		}
		Poison venom = POISONED.remove(victim.getUUID());
		if (venom != null) {
			spreadPoison(level, victim, venom);
		}
		if (r.has(Relic.METEOR_SHOWER) && ++r.fx.meteorKills >= 10 - 2 * r.level(Relic.METEOR_SHOWER)) {
			r.fx.meteorKills = 0;
			meteors(level, p);
		}
		if (r.has(Relic.DOMINO)) {
			blast(level, p, at, 3.0 + 0.75 * r.level(Relic.DOMINO), 5 + 4 * r.level(Relic.DOMINO), 0xFFFFD040, false);
		}
		if (r.has(Relic.NAPALM) && victim.isOnFire()) {
			blast(level, p, at, 3.0, 4 + 4 * r.level(Relic.NAPALM), 0xFFFF7A2E, false);
			level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 30, 0.6, 0.6, 0.6, 0.12);
		}
		if (victim.isOnFire()) {
			RelicCombos.burnedKill(level, victim);
		}
		if (r.has(Relic.PLASMA_STORM) && victim.isOnFire()) {
			List<LivingEntity> near = enemiesNear(level, victim.position(), 7, victim);
			for (int i = 0; i < Math.min(2, near.size()); i++) {
				RiftFx.arc(level, at, near.get(i).getBoundingBox().getCenter(), 0xFFFF9040);
				hurt(p, near.get(i), 6, 0xFF66E0FF);
				RelicCombos.shocked(level, p, near.get(i), true);
			}
		}
		if (r.has(Relic.RUBY_SIPHON)) {
			for (ServerPlayer mate : Rift.players(level.getServer())) {
				if (!mate.isSpectator()) {
					mate.heal(2 * r.level(Relic.RUBY_SIPHON));
					RiftFx.line(level, at, mate.position().add(0, 1, 0), RiftFx.dust(0xFF3D6E, 0.8F), 0.6);
					level.sendParticles(ParticleTypes.HEART, mate.getX(), mate.getY() + 2.1, mate.getZ(), 1, 0.2, 0.1, 0.2, 0);
				}
			}
		}
		if (r.has(Relic.LEECH_SWARM)) {
			List<LivingEntity> near = enemiesNear(level, victim.position(), 9, victim);
			for (int i = 0; i < Math.min(2 + r.level(Relic.LEECH_SWARM), near.size()); i++) {
				LivingEntity e = near.get(i);
				RiftFx.line(level, at, e.getBoundingBox().getCenter(), RiftFx.dust(0x7A0010, 1.0F), 0.35);
				hurt(p, e, 5, 0xFFB00020);
				p.heal(1);
			}
		}
		if (r.has(Relic.NECROMANCER) && victim instanceof Mob mob && !FROZEN.containsKey(victim.getUUID())) {
			raise(level, p, mob, 600 * r.level(Relic.NECROMANCER));
		}
		if (r.has(Relic.SHADOW_CLONE) && level.getRandom().nextFloat() < 0.15F + 0.1F * r.level(Relic.SHADOW_CLONE)
				&& ALLIES.values().stream().filter(a -> a.isClone() && a.owner().equals(p.getUUID())).count() < 1 + r.level(Relic.SHADOW_CLONE)) {
			clone(level, p);
		}
	}

	/** Get hurt: false cancels it (Rocket Boots and the Avatar ignore falls; Mirror Guard sends shots back; allies can't hurt you). */
	public static boolean allowDamage(ServerPlayer victim, DamageSource source, float amount) {
		Rift.Runner r = runner(victim);
		if (r == null) {
			return true;
		}
		if (source.is(DamageTypes.FALL) && (r.has(Relic.ROCKET_BOOTS) || r.has(Relic.RUBY_AVATAR) || victim.level().getGameTime() < r.fx.noFallUntil)) {
			return false;
		}
		if (victim.level().getGameTime() < r.fx.untouchableUntil || RiftAbilities.domeBlocks(victim, source)) {
			return false;
		}
		if (r.fx.iceShield && amount > 0 && victim.level() instanceof ServerLevel level) {
			r.fx.iceShield = false;
			r.fx.still = 0;
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState()), victim.getX(), victim.getY() + 1, victim.getZ(), 40,
					0.6, 0.8, 0.6, 0.2);
			level.playSound(null, victim.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0F, 1.2F);
			return false;
		}
		Entity attacker = source.getEntity();
		if (attacker != null && ALLIES.containsKey(attacker.getUUID())) {
			return false;
		}
		if (source.is(DamageTypes.FALL) && r.fx.pounding) {
			return false;
		}
		if (r.has(Relic.REWIND) && r.fx.rewinds < r.level(Relic.REWIND) && victim.getHealth() - amount < victim.getMaxHealth() * 0.3F
				&& !r.fx.history.isEmpty() && victim.level() instanceof ServerLevel level) {
			rewind(level, victim, r);
			return false;
		}
		if (r.has(Relic.THORN_ARMOR) && attacker instanceof LivingEntity hitter && Rift.isEnemy(hitter) && victim.level() instanceof ServerLevel level) {
			RiftFx.line(level, victim.getBoundingBox().getCenter(), hitter.getBoundingBox().getCenter(), RiftFx.dust(0x4CAF50, 0.9F), 0.4);
			level.sendParticles(ParticleTypes.CRIT, hitter.getX(), hitter.getY() + 1, hitter.getZ(), 8, 0.3, 0.4, 0.3, 0.2);
			boolean fortress = RelicCombos.on(r, RelicCombos.Duo.LIVING_FORTRESS) && victim.getHealth() > victim.getMaxHealth() / 2;
			hurt(victim, hitter, amount * (1 + r.level(Relic.THORN_ARMOR)) * (fortress ? 2 : 1), 0xFF4CAF50);
			if (fortress) {
				Vec3 away = hitter.position().subtract(victim.position()).multiply(1, 0, 1).normalize();
				hitter.setDeltaMovement(away.scale(1.6).add(0, 0.7, 0));
				hitter.needsSync = true;
				level.sendParticles(ParticleTypes.SWEEP_ATTACK, hitter.getX(), hitter.getY() + 1, hitter.getZ(), 1, 0, 0, 0, 0);
				level.playSound(null, hitter.blockPosition(), SoundEvents.MACE_SMASH_AIR, SoundSource.PLAYERS, 0.8F, 1.2F);
			}
		}
		if (r.has(Relic.MIRROR_GUARD) && victim.isShiftKeyDown() && attacker instanceof LivingEntity shooter && Rift.isEnemy(shooter)
				&& (source.getDirectEntity() != attacker || source.is(dev.rubymod.registry.ModDamage.BULLET)) && victim.level() instanceof ServerLevel level) {
			RiftFx.line(level, victim.getEyePosition(), shooter.getBoundingBox().getCenter(), RiftFx.dust(0xFFFFFF, 1.0F), 0.4);
			level.playSound(null, victim.blockPosition(), SoundEvents.SHIELD_BLOCK.value(), SoundSource.PLAYERS, 1.0F, 1.3F);
			hurt(victim, shooter, amount * (1 + r.level(Relic.MIRROR_GUARD)) * (RelicCombos.on(r, RelicCombos.Duo.PERFECT_PARRY) ? 2 : 1), 0xFFFFFFFF);
			return false;
		}
		return true;
	}

	public static void afterDamage(ServerPlayer victim) {
		Rift.Runner hm = runner(victim);
		if (hm != null && hm.has(Relic.HALL_OF_MIRRORS) && victim.level() instanceof ServerLevel lvl && now(lvl) >= hm.fx.mirrorsAt) {
			mirrors(lvl, victim, hm);
		}
		Rift.Runner r = runner(victim);
		if (r == null || !r.has(Relic.BULLET_TIME) || r.fx.bulletTimeUsed || victim.getHealth() > victim.getMaxHealth() / 3
				|| !(victim.level() instanceof ServerLevel level)) {
			return;
		}
		r.fx.bulletTimeUsed = true;
		int ticks = 60 + 40 * r.level(Relic.BULLET_TIME);
		for (UUID id : Rift.MOBS) {
			if (level.getEntity(id) instanceof LivingEntity e) {
				e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 5, false, true));
				e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 2, false, true));
			}
		}
		victim.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 1, false, false));
		for (ServerPlayer p : Rift.players(level.getServer())) {
			RiftFx.screen(p, "slowmo", 0xFF66E0FF, ticks);
		}
		level.playSound(null, victim.blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.0F, 0.5F);
		victim.sendOverlayMessage(Component.literal("BULLET TIME").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
	}

	// ----- Abilities -----

	public static void ability(ServerPlayer p, String kind) {
		Rift.Runner r = runner(p);
		if (r == null || p.isSpectator() || !(p.level() instanceof ServerLevel level)) {
			return;
		}
		if (kind.equals("active")) {
			RiftAbilities.use(p, r);
		} else if (kind.equals("jump") && r.has(Relic.ROCKET_BOOTS) && !p.onGround() && r.fx.airJumps < r.level(Relic.ROCKET_BOOTS)) {
			r.fx.airJumps++;
			Vec3 look = p.getLookAngle().multiply(1, 0, 1).normalize();
			p.setDeltaMovement(p.getDeltaMovement().multiply(1, 0, 1).add(look.scale(0.35)).add(0, 0.85, 0));
			p.needsSync = true;
			level.sendParticles(ParticleTypes.FIREWORK, p.getX(), p.getY(), p.getZ(), 16, 0.2, 0.05, 0.2, 0.08);
			level.sendParticles(ParticleTypes.FLAME, p.getX(), p.getY(), p.getZ(), 8, 0.15, 0.05, 0.15, 0.02);
			level.playSound(null, p.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 0.7F, 1.4F);
		} else if (kind.equals("blink") && r.has(Relic.BLINK) && now(level) >= r.fx.blinkAt) {
			Vec3 eye = p.getEyePosition();
			Vec3 look = p.getLookAngle();
			double reach = 4 + 4 * r.level(Relic.BLINK);
			var hit = level.clip(new ClipContext(eye, eye.add(look.scale(reach)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
			Vec3 to = hit.getType() == HitResult.Type.MISS ? eye.add(look.scale(reach)) : hit.getLocation().subtract(look.scale(0.6));
			Vec3 from = p.position();
			if (RelicCombos.on(r, RelicCombos.Duo.PHANTOM_STEP)) {
				LivingEntity decoy = clone(level, p);
				if (decoy != null) {
					decoy.teleportTo(from.x, from.y, from.z);
					ALLIES.put(decoy.getUUID(), new Ally(now(level) + 50, p.getUUID(), true));
					RelicCombos.DECOYS.add(decoy.getUUID());
					for (LivingEntity e : enemiesNear(level, from, 16, null)) {
						if (e instanceof Mob m && !JuggernautMech.isBoss(m)) {
							m.setTarget(decoy);
						}
					}
				}
			}
			p.teleportTo(to.x, to.y - p.getEyeHeight() + 0.2, to.z);
			p.resetFallDistance();
			r.fx.blinkAt = now(level) + 60 - 15 * r.level(Relic.BLINK);
			RiftFx.line(level, from.add(0, 1, 0), p.position().add(0, 1, 0), ParticleTypes.REVERSE_PORTAL, 0.3);
			level.sendParticles(ParticleTypes.PORTAL, from.x, from.y + 1, from.z, 30, 0.3, 0.6, 0.3, 0.5);
			level.sendParticles(RiftFx.dust(0xC27BFF, 1.5F), p.getX(), p.getY() + 1, p.getZ(), 20, 0.4, 0.6, 0.4, 0);
			level.playSound(null, p.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8F, 1.3F);
		}
	}

	// ----- Stats (health, size) -----

	private static final Identifier HEALTH_ID = Identifier.fromNamespaceAndPath("rubymod", "rift_health");
	private static final Identifier SCALE_ID = Identifier.fromNamespaceAndPath("rubymod", "rift_scale");

	/** Re-apply everything that changes your body (call after gaining a relic or being revived). */
	public static void apply(ServerPlayer p, Rift.Runner r) {
		AttributeInstance health = p.getAttribute(Attributes.MAX_HEALTH);
		AttributeInstance scale = p.getAttribute(Attributes.SCALE);
		if (health != null) {
			float before = p.getMaxHealth();
			double extra = 0;
			if (r.has(Relic.TITAN_BLOOD)) {
				extra += 10 + 10 * r.level(Relic.TITAN_BLOOD);
			}
			if (r.has(Relic.RUBY_AVATAR)) {
				extra += 20;
			}
			if (r.has(Relic.GLASS_CANNON)) {
				extra = 6 - health.getBaseValue();
			}
			health.removeModifier(HEALTH_ID);
			if (extra != 0) {
				health.addTransientModifier(new AttributeModifier(HEALTH_ID, extra, AttributeModifier.Operation.ADD_VALUE));
			}
			if (p.getHealth() > p.getMaxHealth()) {
				p.setHealth(p.getMaxHealth());
			} else if (p.getMaxHealth() > before) {
				// New hearts arrive full.
				p.heal(p.getMaxHealth() - before);
			}
		}
		if (scale != null) {
			scale.removeModifier(SCALE_ID);
			if (r.has(Relic.RUBY_AVATAR)) {
				scale.addTransientModifier(new AttributeModifier(SCALE_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
			}
		}
	}

	/** Out of the Rift (or a new run): back to normal, drones gone. */
	public static void cleanup(ServerPlayer p, Rift.Runner r) {
		AttributeInstance health = p.getAttribute(Attributes.MAX_HEALTH);
		if (health != null) {
			health.removeModifier(HEALTH_ID);
		}
		AttributeInstance scale = p.getAttribute(Attributes.SCALE);
		if (scale != null) {
			scale.removeModifier(SCALE_ID);
		}
		if (p.getHealth() > p.getMaxHealth()) {
			p.setHealth(p.getMaxHealth());
		}
		if (r != null && p.level() instanceof ServerLevel level) {
			discard(level, r.fx.tank);
			discard(level, r.fx.turret);
			r.fx.turretAt = null;
		}
	}

	public static void onRoomStart(ServerPlayer p, Rift.Runner r, ServerLevel level) {
		r.fx.bulletTimeUsed = false;
		r.fx.rewinds = 0;
		r.fx.history.clear();
		rollDice(level, p, r);
		if (r.has(Relic.THE_HUNTED)) {
			hunter(level, p, r);
		}
		discard(level, r.fx.turret);
		r.fx.turretAt = null;
		discard(level, r.fx.tank);
		if (r.has(Relic.DROP_TURRET)) {
			Vec3 at = p.position().add(2, 0, -2);
			r.fx.turretAt = new Vec3(Math.floor(at.x) + 0.5, Math.floor(at.y), Math.floor(at.z) + 0.5);
			level.sendParticles(ParticleTypes.EXPLOSION, r.fx.turretAt.x, r.fx.turretAt.y + 0.5, r.fx.turretAt.z, 1, 0, 0, 0, 0);
			level.playSound(null, BlockPos.containing(r.fx.turretAt), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.6F, 1.4F);
			r.fx.turret.addAll(model(level, r.fx.turretAt, 0, TURRET));
		}
		if (r.has(Relic.GRAVITY_GRENADES)) {
			p.getInventory().add(new ItemStack(ModItems.FRAG_GRENADE, 2 + 2 * r.level(Relic.GRAVITY_GRENADES)));
		}
		if (r.has(Relic.CLUSTER_GRENADES)) {
			p.getInventory().add(new ItemStack(ModItems.FRAG_GRENADE, 1 + 2 * r.level(Relic.CLUSTER_GRENADES)));
		}
		for (int g = 0; g < (r.level(Relic.RUBY_GOLEM) >= 3 ? 2 : r.has(Relic.RUBY_GOLEM) ? 1 : 0); g++) {
			Mob golem = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.EVENT);
			if (golem != null) {
				golem.snapTo(p.getX() + (g == 0 ? 2 : -2), p.getY(), p.getZ() + 1, p.getYRot(), 0);
				AttributeInstance size = golem.getAttribute(Attributes.SCALE);
				if (size != null) {
					size.setBaseValue(r.level(Relic.RUBY_GOLEM) >= 2 ? 1.8 : 1.4);
				}
				AttributeInstance hp = golem.getAttribute(Attributes.MAX_HEALTH);
				if (hp != null && r.level(Relic.RUBY_GOLEM) >= 2) {
					hp.setBaseValue(hp.getBaseValue() * 1.6);
					golem.setHealth(golem.getMaxHealth());
				}
				golem.setCustomName(Component.literal(p.getName().getString() + "'s Ruby Golem").withStyle(ChatFormatting.RED));
				golem.addTag(Rift.TAG);
				level.addFreshEntity(golem);
				ALLIES.put(golem.getUUID(), new Ally(Long.MAX_VALUE, p.getUUID(), false));
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ModBlocks.RUBY_BLOCK.defaultBlockState()), golem.getX(), golem.getY() + 1.5,
						golem.getZ(), 50, 0.8, 1.2, 0.8, 0.2);
			}
		}
	}

	// ----- Every tick -----

	public static void tick(ServerPlayer p, Rift.Runner r, ServerLevel level) {
		if (r == null || p.isSpectator() || p.level() != level) {
			return;
		}
		State s = r.fx;
		if (p.onGround()) {
			s.airJumps = 0;
		}
		RiftCharms.tick(p, r, level);
		tickSecondWave(level, p, r, s);
		// Ruby Avatar: land from a jump to stomp.
		if (r.has(Relic.RUBY_AVATAR)) {
			if (!p.onGround()) {
				s.fallFrom = Double.isNaN(s.fallFrom) ? p.getY() : Math.max(s.fallFrom, p.getY());
			} else {
				if (!Double.isNaN(s.fallFrom) && s.fallFrom - p.getY() >= 1.1) {
					stomp(level, p);
				}
				s.fallFrom = Double.NaN;
			}
			if (level.getGameTime() % 5 == 0) {
				level.sendParticles(RiftFx.dust(0xFF3D6E, 1.0F), p.getX(), p.getY() + 0.2, p.getZ(), 2, 0.6, 0.1, 0.6, 0);
			}
		}
		boolean fighting = Rift.state == Rift.State.FIGHT;
		// Glacier Shield: stand still to grow it.
		if (r.has(Relic.GLACIER_SHIELD)) {
			boolean moved = s.lastPos != null && s.lastPos.distanceToSqr(p.position()) > 0.003;
			s.lastPos = p.position();
			s.still = moved ? 0 : s.still + 1;
			if (s.still == 30 - 8 * r.level(Relic.GLACIER_SHIELD) && !s.iceShield) {
				s.iceShield = true;
				level.playSound(null, p.blockPosition(), SoundEvents.GLASS_PLACE, SoundSource.PLAYERS, 0.8F, 1.6F);
			}
			if (s.iceShield && level.getGameTime() % 6 == 0) {
				RiftFx.ring(level, p.position().add(0, 1, 0), 0.9, RiftFx.dust(0xA8E8FF, 1.0F), 10);
				level.sendParticles(ParticleTypes.SNOWFLAKE, p.getX(), p.getY() + 1, p.getZ(), 1, 0.4, 0.5, 0.4, 0.01);
			}
		}
		// Ruby Conduit: lightning between you and each teammate.
		if (r.has(Relic.RUBY_CONDUIT) && fighting && level.getGameTime() % 8 == 0) {
			for (ServerPlayer mate : Rift.players(level.getServer())) {
				if (mate == p || mate.isSpectator() || mate.getUUID().compareTo(p.getUUID()) < 0 || mate.distanceTo(p) > 20) {
					continue;
				}
				Vec3 a = p.position().add(0, 1.2, 0);
				Vec3 b = mate.position().add(0, 1.2, 0);
				RiftFx.arc(level, a, b, 0xFFFF3D6E);
				for (LivingEntity e : enemiesNear(level, a.lerp(b, 0.5), a.distanceTo(b) / 2 + 1, null)) {
					Vec3 c = e.getBoundingBox().getCenter();
					Vec3 ab = b.subtract(a);
					double tt = Math.max(0, Math.min(1, c.subtract(a).dot(ab) / Math.max(0.01, ab.lengthSqr())));
					if (a.add(ab.scale(tt)).distanceTo(c) < 1.4) {
						hurt(p, e, 6, 0xFFFF3D6E);
					}
				}
			}
		}
		// Orbital Strike: every 12 seconds, on what you're aiming at.
		if (r.has(Relic.ORBITAL_STRIKE) && fighting && now(level) >= s.orbitalAt) {
			LivingEntity target = aimed(level, p, 60);
			if (target == null) {
				target = nearestEnemy(level, p.position(), 40, null, false);
			}
			if (target != null) {
				s.orbitalAt = now(level) + 300 - 60 * r.level(Relic.ORBITAL_STRIKE);
				STRIKES.add(new Strike(new Vec3(target.getX(), Math.floor(target.getY()) + 0.1, target.getZ()), now(level) + 25, p.getUUID()));
				level.playSound(null, target.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.2F, 2.0F);
			}
		}
		// Pocket Tank: follows you, shells the nearest enemy.
		if (r.has(Relic.POCKET_TANK)) {
			double yaw = Math.toRadians(p.getYRot());
			Vec3 at = p.position().add(Math.cos(yaw) * 1.6 + Math.sin(yaw) * 1.2, 0, Math.sin(yaw) * 1.6 - Math.cos(yaw) * 1.2);
			at = new Vec3(at.x, dev.rubymod.game.Hub.safeY(level, at.x, p.getY() + 1, at.z), at.z);
			LivingEntity target = fighting ? nearestEnemy(level, at, 28, null, true) : null;
			float facing = target != null ? (float) Math.toDegrees(Math.atan2(-(target.getX() - at.x), target.getZ() - at.z)) : p.getYRot();
			if (s.tank.isEmpty()) {
				s.tank.addAll(model(level, at, facing, TANK));
			}
			move(level, s.tank, at, facing);
			if (target != null && --s.tankCooldown <= 0) {
				boolean platoon = r.has(Relic.TANK_PLATOON);
				s.tankCooldown = (platoon ? 22 : 45) / r.level(Relic.POCKET_TANK);
				Vec3 muzzle = at.add(0, 0.5, 0);
				Vec3 hit = target.getBoundingBox().getCenter();
				RiftFx.line(level, muzzle, hit, ParticleTypes.SMOKE, 0.5);
				RiftFx.line(level, muzzle, hit, RiftFx.dust(0xFFD040, 0.8F), 0.8);
				level.playSound(null, BlockPos.containing(muzzle), dev.rubymod.registry.ModSounds.ROCKET_LAUNCH, SoundSource.PLAYERS, 0.6F, 1.5F);
				blast(level, p, hit, 3.0, platoon ? 28 : 14, 0xFFFFD040, false);
			}
		}
		// Drop Turret: sits where it landed, spraying the nearest enemy.
		if (s.turretAt != null && !s.turret.isEmpty() && fighting) {
			LivingEntity target = nearestEnemy(level, s.turretAt.add(0, 1, 0), 22, null, true);
			if (target != null) {
				float facing = (float) Math.toDegrees(Math.atan2(-(target.getX() - s.turretAt.x), target.getZ() - s.turretAt.z));
				move(level, s.turret, s.turretAt, facing);
				if (--s.turretCooldown <= 0) {
					s.turretCooldown = Math.max(2, 7 - 2 * r.level(Relic.DROP_TURRET));
					Vec3 muzzle = s.turretAt.add(0, 1.0, 0);
					ItemStack held = p.getMainHandItem();
					if (RelicCombos.on(r, RelicCombos.Duo.GUN_NEST) && held.getItem() instanceof GunItem gun) {
						// Gun Nest: a copy of the gun in your hand, in ruby light.
						var stats = gun.stats(held);
						s.turretCooldown = Math.max(2, stats.fireInterval());
						RiftFx.line(level, muzzle, target.getBoundingBox().getCenter(), RiftFx.dust(0xFF3D6E, 0.9F), 0.5);
						level.sendParticles(ParticleTypes.FLAME, muzzle.x, muzzle.y, muzzle.z, 2, 0.05, 0.05, 0.05, 0.01);
						level.playSound(null, BlockPos.containing(muzzle), dev.rubymod.registry.ModSounds.RIFLE_SHOT, SoundSource.PLAYERS, 0.45F, 1.1F);
						hurt(p, target, stats.damage() * Math.min(4, stats.pellets()) * 0.8F, 0xFFFF3D6E);
					} else {
						RiftFx.line(level, muzzle, target.getBoundingBox().getCenter(), ParticleTypes.CRIT, 0.7);
						level.playSound(null, BlockPos.containing(muzzle), dev.rubymod.registry.ModSounds.RIFLE_SHOT, SoundSource.PLAYERS, 0.35F, 1.4F);
						hurt(p, target, 2.5F + r.level(Relic.DROP_TURRET), 0xFFB8C4D0);
					}
				}
			}
		}
	}

	/** The world's relic things: burning ground, black holes, strikes, frozen enemies, allies, statues, gravity grenades. */
	public static void tickWorld(ServerLevel level) {
		long t = now(level);
		var server = level.getServer();
		tickSecondWaveWorld(level, t);
		RelicCombos.tickWorld(level, t);
		for (Iterator<Map.Entry<UUID, Frozen>> it = FROZEN.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			if (t >= e.getValue().until() || !(level.getEntity(e.getKey()) instanceof LivingEntity mob) || !mob.isAlive()) {
				unfreeze(level, e.getKey(), e.getValue());
				if (level.getEntity(e.getKey()) instanceof LivingEntity thawed && thawed.isAlive() && team(level, Relic.FROSTFIRE)) {
					thawed.igniteForSeconds(6);
					level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, thawed.getX(), thawed.getY() + 1, thawed.getZ(), 30, 0.4, 0.6, 0.4, 0.05);
				}
				it.remove();
			}
		}
		for (Iterator<Patch> it = PATCHES.iterator(); it.hasNext();) {
			Patch p = it.next();
			if (t >= p.until()) {
				it.remove();
				continue;
			}
			if (t % 3 == 0) {
				level.sendParticles(ParticleTypes.FLAME, p.at().x, p.at().y, p.at().z, 3, 0.7, 0.02, 0.7, 0.01);
				level.sendParticles(RiftFx.dust(0xFF7A2E, 1.2F), p.at().x, p.at().y + 0.1, p.at().z, 2, 0.8, 0.05, 0.8, 0);
			}
			if (t % 10 == 0) {
				for (LivingEntity e : enemiesNear(level, p.at(), 1.6, null)) {
					e.igniteForSeconds(4);
				}
			}
		}
		for (Iterator<Hole> it = HOLES.iterator(); it.hasNext();) {
			Hole h = it.next();
			Entity core = level.getEntity(h.core());
			if (t >= h.until()) {
				if (core != null) {
					core.discard();
				}
				level.sendParticles(ParticleTypes.REVERSE_PORTAL, h.at().x, h.at().y, h.at().z, 80, 0.2, 0.2, 0.2, 1.2);
				level.playSound(null, BlockPos.containing(h.at()), SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.PLAYERS, 1.0F, 0.6F);
				it.remove();
				continue;
			}
			if (core != null) {
				core.setYRot((t * 18) % 360);
			}
			double spin = t * 0.5;
			for (int k = 0; k < 6; k++) {
				double a = spin + k * Math.PI / 3;
				double rad = 3.5 - (t % 20) * 0.15;
				level.sendParticles(RiftFx.dust(0x6020A0, 1.6F), true, false, h.at().x + Math.cos(a) * rad, h.at().y + Math.sin(a * 0.5) * 0.4,
						h.at().z + Math.sin(a) * rad, 1, 0, 0, 0, 0);
			}
			level.sendParticles(ParticleTypes.SQUID_INK, h.at().x, h.at().y, h.at().z, 2, 0.3, 0.3, 0.3, 0.01);
			ServerPlayer owner = server.getPlayerList().getPlayer(h.owner());
			for (LivingEntity e : enemiesNear(level, h.at(), 9, null)) {
				Vec3 pull = h.at().subtract(e.position()).normalize().scale(0.35);
				e.setDeltaMovement(e.getDeltaMovement().scale(0.5).add(pull));
				e.needsSync = true;
				if (owner != null && t % 10 == 0) {
					hurt(owner, e, 2, 0xFF8040FF);
				}
				if (owner != null && e.position().distanceTo(h.at()) < 1.6 && e.getMaxHealth() <= 60 && !JuggernautMech.isBoss(e)) {
					hurt(owner, e, 9999, 0xFF8040FF);
					level.sendParticles(ParticleTypes.REVERSE_PORTAL, e.getX(), e.getY() + 1, e.getZ(), 30, 0.2, 0.4, 0.2, 0.6);
				}
			}
		}
		for (Iterator<Strike> it = STRIKES.iterator(); it.hasNext();) {
			Strike s = it.next();
			long left = s.when() - t;
			if (left > 0) {
				RiftFx.ring(level, s.at(), 3.0, RiftFx.dust(0xFF2020, 1.4F), 24);
				RiftFx.ring(level, s.at(), 3.0 * left / 25.0, RiftFx.dust(0xFFC0C0, 1.0F), 16);
				continue;
			}
			it.remove();
			ServerPlayer owner = server.getPlayerList().getPlayer(s.owner());
			RiftFx.beam(level, s.at(), 30, ParticleTypes.END_ROD, 0xFFFFE0F0);
			for (double y = 0; y < 30; y += 0.6) {
				level.sendParticles(RiftFx.dust(0xFF3D6E, 2.5F), true, true, s.at().x, s.at().y + y, s.at().z, 2, 0.25, 0.1, 0.25, 0);
			}
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, s.at().x, s.at().y + 0.5, s.at().z, 1, 0, 0, 0, 0);
			level.playSound(null, BlockPos.containing(s.at()), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 2.0F, 0.7F);
			level.playSound(null, BlockPos.containing(s.at()), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.8F, 1.5F);
			if (owner != null) {
				blast(level, owner, s.at().add(0, 0.5, 0), 3.5, 40, 0xFFFF3D6E, false);
				for (ServerPlayer p : Rift.players(server)) {
					if (p.position().distanceTo(s.at()) < 30) {
						RiftFx.shake(p, 0.8F, 8);
					}
				}
			}
		}
		for (Iterator<Map.Entry<UUID, Ally>> it = ALLIES.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			Entity ent = level.getEntity(e.getKey());
			Ally a = e.getValue();
			if (ent == null || !ent.isAlive() || t >= a.until()) {
				if (ent != null) {
					level.sendParticles(ParticleTypes.SOUL, ent.getX(), ent.getY() + 1, ent.getZ(), 12, 0.3, 0.5, 0.3, 0.03);
					RelicCombos.decoyGone(level, server.getPlayerList().getPlayer(a.owner()), ent);
					ent.discard();
				}
				RelicCombos.SHADOWS.remove(e.getKey());
				it.remove();
				continue;
			}
			if (a.isClone()) {
				tickClone(level, (LivingEntity) ent, server.getPlayerList().getPlayer(a.owner()));
			} else if (ent instanceof Mob mob && t % 10 == 0) {
				LivingEntity target = nearestEnemy(level, mob.position(), 24, null, false);
				mob.setTarget(target);
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, mob.getX(), mob.getY() + mob.getBbHeight(), mob.getZ(), 1, 0.1, 0.1, 0.1, 0.01);
			}
			if (ent instanceof Mob mob && mob.getTarget() instanceof Player) {
				mob.setTarget(null);
			}
		}
		for (Iterator<Statue> it = STATUES.iterator(); it.hasNext();) {
			Statue s = it.next();
			if (t < s.when()) {
				continue;
			}
			it.remove();
			Entity d = level.getEntity(s.display());
			if (d != null) {
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ModBlocks.RUBY_BLOCK.defaultBlockState()), d.getX(), d.getY() + 1, d.getZ(),
						60, 0.4, 0.7, 0.4, 0.2);
				level.playSound(null, d.blockPosition(), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, 1.2F, 0.8F);
				d.discard();
			}
		}
		// Cluster Grenades: when one goes off, four more explosions around it.
		for (ServerPlayer p : Rift.players(server)) {
			if (has(p, Relic.CLUSTER_GRENADES) && p.level() == level) {
				for (GrenadeEntity g : level.getEntitiesOfClass(GrenadeEntity.class, p.getBoundingBox().inflate(40), g -> g.getOwner() == p)) {
					GRENADES.put(g.getUUID(), new Bomblet(g.position(), t, p.getUUID()));
				}
			}
		}
		for (Iterator<Map.Entry<UUID, Bomblet>> it = GRENADES.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			if (level.getEntity(e.getKey()) instanceof GrenadeEntity g && g.isAlive()) {
				continue;
			}
			it.remove();
			ServerPlayer thrower = server.getPlayerList().getPlayer(e.getValue().owner());
			int n = thrower == null ? 4 : 2 + 2 * Math.max(1, lv(thrower, Relic.CLUSTER_GRENADES));
			for (int k = 0; k < n; k++) {
				double a = k * Math.PI * 2 / n + 0.4;
				double reach = 2.6 + (k % 2) * 0.8 * (n > 4 ? 1 : 0);
				BOMBLETS.add(new Bomblet(e.getValue().at().add(Math.cos(a) * reach, 0, Math.sin(a) * reach), t + 6 + k * 3, e.getValue().owner()));
			}
		}
		for (Iterator<Bomblet> it = BOMBLETS.iterator(); it.hasNext();) {
			Bomblet b = it.next();
			if (t >= b.when()) {
				it.remove();
				ServerPlayer owner = server.getPlayerList().getPlayer(b.owner());
				if (owner != null) {
					blast(level, owner, b.at(), 2.6, 8, 0xFFFFD040, false);
				}
			}
		}
		// Gravity Grenades: everything near one is dragged in before it blows.
		if (t % 2 == 0) {
			for (ServerPlayer p : Rift.players(server)) {
				if (!has(p, Relic.GRAVITY_GRENADES) || p.level() != level) {
					continue;
				}
				for (GrenadeEntity g : level.getEntitiesOfClass(GrenadeEntity.class, p.getBoundingBox().inflate(40), g -> g.getOwner() == p)) {
					Vec3 c = g.position();
					for (LivingEntity e : enemiesNear(level, c, 8, null)) {
						e.setDeltaMovement(e.getDeltaMovement().scale(0.4).add(c.subtract(e.position()).normalize().scale(0.45)));
						e.needsSync = true;
					}
					RiftFx.ring(level, c, 2.5 + Math.sin(t * 0.4), RiftFx.dust(0x8040FF, 1.0F), 14);
				}
			}
		}
	}

	/** Leaving a room: the things that only belong to it go. */
	public static void clearWorld(ServerLevel level) {
		ECHOES.clear();
		METEORS.clear();
		RelicCombos.clearWorld(level);
		POISONED.clear();
		for (Rift.Runner run : Rift.RUNNERS.values()) {
			if (run.fx.hunter != null && level.getEntity(run.fx.hunter) instanceof Entity h) {
				h.discard();
			}
			run.fx.hunter = null;
			if (run.fx.dragon != null && level.getEntity(run.fx.dragon) instanceof Entity d) {
				d.discard();
			}
			run.fx.dragon = null;
		}
		for (var e : FROZEN.entrySet()) {
			unfreeze(level, e.getKey(), e.getValue());
		}
		FROZEN.clear();
		CHILL.clear();
		PATCHES.clear();
		for (Hole h : HOLES) {
			Entity core = level.getEntity(h.core());
			if (core != null) {
				core.discard();
			}
		}
		HOLES.clear();
		STRIKES.clear();
		for (UUID id : ALLIES.keySet()) {
			Entity e = level.getEntity(id);
			if (e != null) {
				e.discard();
			}
		}
		ALLIES.clear();
		for (Statue s : STATUES) {
			Entity d = level.getEntity(s.display());
			if (d != null) {
				d.discard();
			}
		}
		STATUES.clear();
		BOMBLETS.clear();
		GRENADES.clear();
	}

	// ----- The effects themselves -----

	/** An explosion that only hurts the Rift's monsters (unless {@code hurtsPlayers}: a Volatile elite going off). */
	static void blast(ServerLevel level, ServerPlayer owner, Vec3 at, double radius, float damage, int color, boolean hurtsPlayers) {
		level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 2, radius * 0.25, radius * 0.25, radius * 0.25, 0);
		RiftFx.burst(level, at, color, 20, radius * 0.35);
		level.playSound(null, BlockPos.containing(at), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.9F, 1.1F + level.getRandom().nextFloat() * 0.3F);
		if (owner != null && has(owner, Relic.SHATTERBOMB)) {
			for (LivingEntity e : enemiesNear(level, at, radius, null)) {
				if (FROZEN.containsKey(e.getUUID())) {
					shatter(level, owner, e, damage);
				}
			}
		}
		for (LivingEntity e : enemiesNear(level, at, radius, null)) {
			double falloff = 1 - Math.min(0.6, e.position().distanceTo(at) / radius * 0.6);
			if (owner != null) {
				hurt(owner, e, (float) (damage * falloff), color);
				if (!e.isAlive()) {
					RelicCombos.blastKill(level, owner, e);
				}
			}
			Vec3 push = e.position().subtract(at).normalize().scale(0.5);
			e.setDeltaMovement(e.getDeltaMovement().add(push.x, 0.3, push.z));
		}
		RelicCombos.onBlast(level, owner, at, radius);
		if (hurtsPlayers) {
			for (ServerPlayer p : Rift.players(level.getServer())) {
				if (!p.isSpectator() && p.position().distanceTo(at) < radius) {
					p.hurtServer(level, level.damageSources().explosion(null, null), damage);
				}
			}
		}
	}

	private static void stomp(ServerLevel level, ServerPlayer p) {
		Vec3 at = p.position();
		BlockState under = level.getBlockState(p.blockPosition().below());
		for (int ring = 1; ring <= 3; ring++) {
			RiftFx.ring(level, at.add(0, 0.2, 0), ring * 1.6, new BlockParticleOption(ParticleTypes.BLOCK, under.isAir() ? Blocks.STONE.defaultBlockState() : under),
					12 * ring);
		}
		RiftFx.ring(level, at.add(0, 0.3, 0), 4.5, RiftFx.dust(0xFF3D6E, 1.8F), 36);
		level.playSound(null, p.blockPosition(), SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 1.0F, 0.7F);
		RiftFx.shake(p, 1.2F, 10);
		int stars = Math.max(1, lv(p, Relic.RUBY_AVATAR));
		for (LivingEntity e : enemiesNear(level, at, stars >= 3 ? 10 : 5, null)) {
			hurt(p, e, 14 * stars, 0xFFFF3D6E);
			e.setDeltaMovement(e.getDeltaMovement().add(e.position().subtract(at).normalize().scale(0.8)).add(0, 0.6, 0));
		}
	}

	static void freeze(ServerLevel level, LivingEntity target) {
		if (target instanceof Mob mob) {
			mob.setNoAi(true);
		}
		Display.BlockDisplay ice = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (ice != null) {
			float w = target.getBbWidth() + 0.3F;
			float h = target.getBbHeight() + 0.3F;
			ice.snapTo(target.getX(), target.getY(), target.getZ(), 0, 0);
			((BlockDisplayAccessor) ice).rubymod$setBlockState(Blocks.ICE.defaultBlockState());
			((DisplayAccessor) ice).rubymod$setTransformation(new Transformation(new Vector3f(-w / 2, -0.15F, -w / 2), new Quaternionf(),
					new Vector3f(w, h, w), new Quaternionf()));
			ice.addTag(Rift.TAG);
			level.addFreshEntity(ice);
			FROZEN.put(target.getUUID(), new Frozen(ice.getUUID(), now(level) + 70));
		}
		level.sendParticles(ParticleTypes.SNOWFLAKE, target.getX(), target.getY() + 1, target.getZ(), 30, 0.4, 0.6, 0.4, 0.05);
		level.playSound(null, target.blockPosition(), SoundEvents.GLASS_PLACE, SoundSource.PLAYERS, 1.0F, 0.6F);
	}

	private static void unfreeze(ServerLevel level, UUID id, Frozen f) {
		Entity ice = level.getEntity(f.ice());
		if (ice != null) {
			ice.discard();
		}
		if (level.getEntity(id) instanceof Mob mob) {
			mob.setNoAi(false);
		}
	}

	private static void shatter(ServerLevel level, ServerPlayer p, LivingEntity target, float amount) {
		Frozen f = FROZEN.remove(target.getUUID());
		unfreeze(level, target.getUUID(), f);
		Vec3 at = target.getBoundingBox().getCenter();
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState()), at.x, at.y, at.z, 60, 0.5, 0.7, 0.5, 0.3);
		level.sendParticles(ParticleTypes.SNOWFLAKE, at.x, at.y, at.z, 40, 1.5, 0.8, 1.5, 0.15);
		level.playSound(null, target.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.4F, 0.7F);
		hurt(p, target, amount * 3 + 8, 0xFFA8E8FF);
		for (LivingEntity e : enemiesNear(level, at, 4, target)) {
			hurt(p, e, 10, 0xFFA8E8FF);
			e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 2));
		}
		if (has(p, Relic.FROSTFIRE)) {
			PATCHES.add(new Patch(new Vec3(at.x, Math.floor(target.getY()) + 0.05, at.z), now(level) + 100, p.getUUID()));
			for (LivingEntity e : enemiesNear(level, at, 4, null)) {
				e.igniteForSeconds(5);
			}
		}
		if (has(p, Relic.SHATTERBOMB)) {
			blast(level, p, at, 3.2, 10, 0xFFFFD040, false);
		}
		RelicCombos.shattered(level, p, at);
	}

	private static void statue(ServerLevel level, ServerPlayer p, Mob mob) {
		Display.BlockDisplay ruby = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (ruby != null) {
			float w = mob.getBbWidth();
			float h = mob.getBbHeight();
			ruby.snapTo(mob.getX(), mob.getY(), mob.getZ(), mob.getYRot(), 0);
			((BlockDisplayAccessor) ruby).rubymod$setBlockState(ModBlocks.RUBY_BLOCK.defaultBlockState());
			((DisplayAccessor) ruby).rubymod$setTransformation(new Transformation(new Vector3f(-w / 2, 0, -w / 2), new Quaternionf(),
					new Vector3f(w, h, w), new Quaternionf()));
			ruby.addTag(Rift.TAG);
			level.addFreshEntity(ruby);
			STATUES.add(new Statue(ruby.getUUID(), now(level) + 50));
		}
		level.sendParticles(ParticleTypes.WAX_ON, mob.getX(), mob.getY() + 1, mob.getZ(), 30, 0.4, 0.6, 0.4, 0.5);
		level.playSound(null, mob.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.5F, 0.6F);
		mob.setInvisible(true);
		Rift.gold += 15;
		hurt(p, mob, 99999, 0xFFFF3D6E);
	}

	static void blackHole(ServerLevel level, ServerPlayer p, Vec3 at) {
		Display.BlockDisplay core = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		UUID id = null;
		if (core != null) {
			core.snapTo(at.x, at.y, at.z, 0, 0);
			((BlockDisplayAccessor) core).rubymod$setBlockState(Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState());
			((DisplayAccessor) core).rubymod$setTransformation(new Transformation(new Vector3f(-0.6F, -0.6F, -0.6F),
					new Quaternionf().rotateXYZ(0.6F, 0, 0.6F), new Vector3f(1.2F, 1.2F, 1.2F), new Quaternionf()));
			((DisplayAccessor) core).rubymod$setPosRotInterpolationDuration(1);
			core.addTag(Rift.TAG);
			level.addFreshEntity(core);
			id = core.getUUID();
		}
		HOLES.add(new Hole(at, now(level) + 70, p.getUUID(), id));
		level.playSound(null, BlockPos.containing(at), SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 0.6F, 1.8F);
	}

	/** Necromancer: the fallen get back up, on your side, for 30 seconds. */
	private static void raise(ServerLevel level, ServerPlayer p, Mob dead, int ticks) {
		@SuppressWarnings("unchecked")
		EntityType<? extends Mob> type = (EntityType<? extends Mob>) dead.getType();
		Mob mob = type.create(level, EntitySpawnReason.EVENT);
		if (mob == null || mob instanceof dev.rubymod.entity.SoldierEntity) {
			return;
		}
		mob.snapTo(dead.getX(), dead.getY(), dead.getZ(), dead.getYRot(), 0);
		mob.setCustomName(Component.literal("Risen " + (dead.getCustomName() == null ? "" : dead.getCustomName().getString())).withStyle(ChatFormatting.GREEN));
		mob.setPersistenceRequired();
		mob.addTag(Rift.TAG);
		level.addFreshEntity(mob);
		boolean legion = RelicCombos.on(p, RelicCombos.Duo.SHADOW_LEGION);
		ALLIES.put(mob.getUUID(), new Ally(legion ? Long.MAX_VALUE : now(level) + ticks, p.getUUID(), false));
		if (legion) {
			// Shadow Legion: a warrior for the whole room, hitting twice as hard.
			mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, -1, 2, false, false));
			mob.setCustomName(Component.literal("Shadow " + (dead.getCustomName() == null ? "Warrior" : dead.getCustomName().getString()))
					.withStyle(ChatFormatting.DARK_PURPLE));
			RelicCombos.SHADOWS.add(mob.getUUID());
			level.sendParticles(RiftFx.dust(0x6020A0, 1.8F), mob.getX(), mob.getY() + 1, mob.getZ(), 30, 0.4, 0.7, 0.4, 0);
		}
		level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, mob.getX(), mob.getY() + 1, mob.getZ(), 30, 0.3, 0.6, 0.3, 0.05);
		level.playSound(null, mob.blockPosition(), SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.PLAYERS, 0.5F, 1.4F);
	}

	/** Shadow Clone: a copy of you (your skin and all) that fights beside you for 10 seconds. */
	static LivingEntity clone(ServerLevel level, ServerPlayer p) {
		var mannequin = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.EVENT);
		if (mannequin == null) {
			return null;
		}
		Vec3 at = p.position().add(level.getRandom().nextDouble() * 2 - 1, 0, level.getRandom().nextDouble() * 2 - 1);
		mannequin.snapTo(at.x, at.y, at.z, p.getYRot(), 0);
		mannequin.getEntityData().set(dev.rubymod.mixin.MannequinAccessor.rubymod$profile(),
				net.minecraft.world.item.component.ResolvableProfile.createResolved(p.getGameProfile()));
		mannequin.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, p.getMainHandItem().copy());
		mannequin.setPermanentlyInvulnerable(true);
		mannequin.setNoGravity(true);
		mannequin.setCustomName(Component.literal("Shadow of " + p.getName().getString()).withStyle(ChatFormatting.DARK_PURPLE));
		mannequin.addTag(Rift.TAG);
		level.addFreshEntity(mannequin);
		ALLIES.put(mannequin.getUUID(), new Ally(now(level) + 200, p.getUUID(), true));
		level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 1, at.z, 20, 0.3, 0.6, 0.3, 0.02);
		level.playSound(null, BlockPos.containing(at), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 1.0F, 1.0F);
		return mannequin;
	}

	private static void tickClone(ServerLevel level, LivingEntity clone, ServerPlayer owner) {
		long t = now(level);
		if (owner == null) {
			return;
		}
		// Stays near you, faces the nearest enemy and shoots it.
		LivingEntity target = nearestEnemy(level, clone.position(), 26, null, true);
		Vec3 want = owner.position().add(Math.cos(t * 0.05 + clone.getId()) * 2.5, 0, Math.sin(t * 0.05 + clone.getId()) * 2.5);
		Vec3 now = clone.position();
		Vec3 step = want.subtract(now);
		if (step.length() > 0.4) {
			step = step.normalize().scale(0.4);
		}
		double y = dev.rubymod.game.Hub.safeY(level, now.x + step.x, owner.getY() + 1, now.z + step.z);
		clone.teleportTo(now.x + step.x, y, now.z + step.z);
		if (target != null) {
			float yaw = (float) Math.toDegrees(Math.atan2(-(target.getX() - clone.getX()), target.getZ() - clone.getZ()));
			clone.setYRot(yaw);
			clone.setYHeadRot(yaw);
			clone.yBodyRot = yaw;
			if (t % 8 == 0) {
				Vec3 muzzle = clone.getEyePosition().add(clone.getLookAngle().scale(0.6));
				RiftFx.line(level, muzzle, target.getBoundingBox().getCenter(), RiftFx.dust(0x8040FF, 0.7F), 0.6);
				level.playSound(null, clone.blockPosition(), dev.rubymod.registry.ModSounds.RIFLE_SHOT, SoundSource.PLAYERS, 0.4F, 0.8F);
				hurt(owner, target, 6, 0xFFC27BFF);
			}
		}
		if (t % 4 == 0) {
			level.sendParticles(ParticleTypes.SMOKE, clone.getX(), clone.getY() + 0.3, clone.getZ(), 2, 0.2, 0.1, 0.2, 0.01);
		}
	}

	// ----- Little models built from blocks (drones) -----

	/** Part: block, size, offset (from the model's middle, before turning). */
	private record Part(BlockState block, float sx, float sy, float sz, float ox, float oy, float oz) {
	}

	private static final Part[] TANK = {
			new Part(Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(), 0.22F, 0.28F, 1.2F, -0.55F, 0.0F, -0.6F),
			new Part(Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(), 0.22F, 0.28F, 1.2F, 0.33F, 0.0F, -0.6F),
			new Part(Blocks.CONCRETE.pick(DyeColor.GREEN).defaultBlockState(), 0.8F, 0.3F, 1.1F, -0.4F, 0.12F, -0.55F),
			new Part(Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState(), 0.48F, 0.24F, 0.5F, -0.24F, 0.42F, -0.3F),
			new Part(Blocks.POLISHED_BLACKSTONE.defaultBlockState(), 0.1F, 0.1F, 0.75F, -0.05F, 0.5F, 0.15F),
			new Part(Blocks.REDSTONE_LAMP.defaultBlockState().setValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT, true), 0.12F, 0.08F, 0.12F,
					-0.06F, 0.66F, -0.2F)};
	private static final Part[] TURRET = {
			new Part(Blocks.IRON_BLOCK.defaultBlockState(), 0.7F, 0.5F, 0.7F, -0.35F, 0.0F, -0.35F),
			new Part(Blocks.POLISHED_BLACKSTONE.defaultBlockState(), 0.2F, 0.4F, 0.2F, -0.1F, 0.5F, -0.1F),
			new Part(Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState(), 0.5F, 0.3F, 0.5F, -0.25F, 0.85F, -0.25F),
			new Part(Blocks.POLISHED_BLACKSTONE.defaultBlockState(), 0.08F, 0.08F, 0.7F, -0.04F, 0.97F, 0.2F),
			new Part(Blocks.SEA_LANTERN.defaultBlockState(), 0.1F, 0.1F, 0.06F, -0.05F, 1.0F, -0.27F)};

	private static List<UUID> model(ServerLevel level, Vec3 at, float yaw, Part[] parts) {
		List<UUID> out = new ArrayList<>();
		for (Part part : parts) {
			Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (d == null) {
				continue;
			}
			d.snapTo(at.x, at.y, at.z, yaw, 0);
			((BlockDisplayAccessor) d).rubymod$setBlockState(part.block());
			((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(part.ox(), part.oy(), part.oz()), new Quaternionf(),
					new Vector3f(part.sx(), part.sy(), part.sz()), new Quaternionf()));
			((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(3);
			d.addTag(Rift.TAG);
			level.addFreshEntity(d);
			out.add(d.getUUID());
		}
		return out;
	}

	private static void move(ServerLevel level, List<UUID> parts, Vec3 at, float yaw) {
		for (UUID id : parts) {
			Entity e = level.getEntity(id);
			if (e != null) {
				e.teleportTo(at.x, at.y, at.z);
				e.setYRot(yaw);
			}
		}
	}

	private static void discard(ServerLevel level, List<UUID> ids) {
		for (UUID id : ids) {
			Entity e = level.getEntity(id);
			if (e != null) {
				e.discard();
			}
		}
		ids.clear();
	}

	// ----- Finding things -----

	static List<LivingEntity> enemiesNear(ServerLevel level, Vec3 at, double radius, Entity except) {
		List<LivingEntity> out = new ArrayList<>();
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(radius), e -> e.isAlive() && Rift.isEnemy(e) && e != except)) {
			if (e.position().distanceTo(at) <= radius + e.getBbWidth()) {
				out.add(e);
			}
		}
		return out;
	}

	static LivingEntity nearestEnemy(ServerLevel level, Vec3 at, double radius, Entity except, boolean mustSee) {
		LivingEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (LivingEntity e : enemiesNear(level, at, radius, except)) {
			double d = e.position().distanceToSqr(at);
			if (d < bestD && (!mustSee || level.clip(new ClipContext(at, e.getBoundingBox().getCenter(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
					e)).getType() == HitResult.Type.MISS)) {
				best = e;
				bestD = d;
			}
		}
		return best;
	}

	/** The enemy under your crosshair. */
	static LivingEntity aimed(ServerLevel level, ServerPlayer p, double range) {
		Vec3 eye = p.getEyePosition();
		Vec3 end = eye.add(p.getLookAngle().scale(range));
		var block = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		Vec3 stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
		LivingEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (LivingEntity e : enemiesNear(level, eye.lerp(stop, 0.5), eye.distanceTo(stop) / 2 + 2, null)) {
			var hit = e.getBoundingBox().inflate(0.6).clip(eye, stop);
			if (hit.isPresent() && hit.get().distanceToSqr(eye) < bestD) {
				best = e;
				bestD = hit.get().distanceToSqr(eye);
			}
		}
		return best;
	}

	public static boolean isAlly(Entity e) {
		return e != null && ALLIES.containsKey(e.getUUID());
	}

	// ----- The second wave of relics -----

	/** The last Lucky Coin crit (a kill on it drops gold). */
	private static UUID lastCrit;

	private record Echo(UUID owner, long when, dev.rubymod.gun.GunStats stats) {
	}

	private record Meteor(Vec3 at, long when, UUID owner) {
	}

	record Poison(UUID owner, int stacks, long until) {
	}

	private static final List<Echo> ECHOES = new ArrayList<>();
	private static final List<Meteor> METEORS = new ArrayList<>();
	static final Map<UUID, Poison> POISONED = new HashMap<>();

	/** Split Shot: does this shooter's bullet split on a wall, and how hard do the fragments hit? */
	public static boolean splitShot(ServerPlayer p) {
		return has(p, Relic.SPLIT_SHOT);
	}

	public static float splitDamage(ServerPlayer p) {
		return 0.35F + 0.15F * lv(p, Relic.SPLIT_SHOT);
	}

	/** Overclock: a reload just finished. */
	public static void onReloaded(ServerPlayer p) {
		Rift.Runner r = runner(p);
		if (r != null && r.has(Relic.OVERCLOCK) && p.level() instanceof ServerLevel level) {
			r.fx.overclockUntil = now(level) + 40 + 20 * r.level(Relic.OVERCLOCK);
			level.playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.PLAYERS, 0.8F, 2.0F);
			p.sendOverlayMessage(Component.literal("OVERCLOCKED").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
			if (RelicCombos.on(r, RelicCombos.Duo.GRAVITY_OVERLOAD) && now(level) >= r.fx.gravityAt && Rift.state == Rift.State.FIGHT) {
				r.fx.gravityAt = now(level) + 60;
				LivingEntity target = aimed(level, p, 40);
				Vec3 eye = p.getEyePosition();
				var hit = level.clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(30)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
				Vec3 at = target != null ? target.position().add(0, 1, 0) : hit.getLocation().subtract(p.getLookAngle().scale(1.0));
				RiftFx.line(level, eye.add(p.getLookAngle()), at, RiftFx.dust(0x8040FF, 1.2F), 0.4);
				blackHole(level, p, at);
			}
		}
	}

	static void poison(ServerLevel level, ServerPlayer p, LivingEntity target, int stars) {
		Poison old = POISONED.get(target.getUUID());
		int stacks = Math.min(12 * stars, (old == null ? 0 : old.stacks()) + stars);
		POISONED.put(target.getUUID(), new Poison(p.getUUID(), stacks, now(level) + 100));
		level.sendParticles(RiftFx.dust(0x7AE33A, 1.0F), target.getX(), target.getY() + 1.2, target.getZ(), 4, 0.3, 0.4, 0.3, 0);
	}

	private static void spreadPoison(ServerLevel level, LivingEntity victim, Poison venom) {
		boolean inferno = team(level, Relic.TOXIC_INFERNO);
		double reach = inferno ? 8 : 4;
		Vec3 at = victim.getBoundingBox().getCenter();
		level.sendParticles(ParticleTypes.SNEEZE, at.x, at.y, at.z, 30, reach * 0.3, 0.4, reach * 0.3, 0.05);
		level.sendParticles(RiftFx.dust(0x7AE33A, 1.6F), at.x, at.y, at.z, 30, reach * 0.3, 0.4, reach * 0.3, 0);
		for (LivingEntity e : enemiesNear(level, victim.position(), reach, victim)) {
			Poison old = POISONED.get(e.getUUID());
			POISONED.put(e.getUUID(), new Poison(venom.owner(), Math.max(old == null ? 0 : old.stacks(), venom.stacks() / 2 + 1), now(level) + 100));
			RiftFx.line(level, at, e.getBoundingBox().getCenter(), RiftFx.dust(0x7AE33A, 0.8F), 0.5);
		}
	}

	private static void superconduct(ServerLevel level, ServerPlayer p, LivingEntity from) {
		for (UUID id : new ArrayList<>(FROZEN.keySet())) {
			if (!id.equals(from.getUUID()) && level.getEntity(id) instanceof LivingEntity e && e.isAlive()) {
				RiftFx.arc(level, from.getBoundingBox().getCenter(), e.getBoundingBox().getCenter(), 0xFFA8F0FF);
				hurt(p, e, 8, 0xFFA8F0FF);
				RelicCombos.shocked(level, p, e, true);
			}
		}
	}

	/** Static Charge: let it loose where you're aiming. */
	private static void release(ServerLevel level, ServerPlayer p, Rift.Runner r) {
		float charge = r.fx.charge;
		r.fx.charge = 0;
		Vec3 eye = p.getEyePosition();
		var hit = level.clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(40)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		LivingEntity aimedAt = aimed(level, p, 40);
		Vec3 at = aimedAt != null ? aimedAt.getBoundingBox().getCenter() : hit.getLocation();
		var bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
		if (bolt != null) {
			bolt.snapTo(at.x, at.y, at.z, 0, 0);
			bolt.setVisualOnly(true);
			level.addFreshEntity(bolt);
		}
		RiftFx.arc(level, eye.add(p.getLookAngle()), at, 0xFF66E0FF);
		blast(level, p, at, 2.5 + charge / 40, 4 + charge * 0.25F, 0xFF66E0FF, false);
		if (aimedAt != null) {
			RelicCombos.shocked(level, p, aimedAt, false);
		}
		level.playSound(null, p.blockPosition(), SoundEvents.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 0.8F, 1.4F);
	}

	private static void mirrors(ServerLevel level, ServerPlayer victim, Rift.Runner r) {
		int stars = r.level(Relic.HALL_OF_MIRRORS);
		r.fx.mirrorsAt = now(level) + 220 - 50 * stars;
		int before = ALLIES.size();
		for (int i = 0; i < 1 + stars; i++) {
			clone(level, victim);
		}
		List<LivingEntity> images = new ArrayList<>();
		for (UUID id : ALLIES.keySet()) {
			if (level.getEntity(id) instanceof LivingEntity e && ALLIES.get(id).isClone() && ALLIES.get(id).owner().equals(victim.getUUID())) {
				images.add(e);
			}
		}
		if (images.isEmpty() || ALLIES.size() == before) {
			return;
		}
		// Everything chasing you turns on the images.
		for (UUID id : Rift.MOBS) {
			if (level.getEntity(id) instanceof net.minecraft.world.entity.Mob m && m.getTarget() == victim && !JuggernautMech.isBoss(m)) {
				m.setTarget(images.get(level.getRandom().nextInt(images.size())));
			}
		}
		level.sendParticles(ParticleTypes.END_ROD, victim.getX(), victim.getY() + 1, victim.getZ(), 30, 0.6, 0.8, 0.6, 0.05);
		level.playSound(null, victim.blockPosition(), SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 1.0F, 1.2F);
	}

	private static void rewind(ServerLevel level, ServerPlayer p, Rift.Runner r) {
		r.fx.rewinds++;
		Object[] then = r.fx.history.peekFirst();
		Vec3 back = (Vec3) then[0];
		float hp = (Float) then[1];
		Vec3 from = p.position();
		RiftFx.line(level, from.add(0, 1, 0), back.add(0, 1, 0), ParticleTypes.REVERSE_PORTAL, 0.25);
		for (Object[] h : r.fx.history) {
			Vec3 at = (Vec3) h[0];
			level.sendParticles(RiftFx.dust(0x66E0FF, 1.0F), at.x, at.y + 1, at.z, 1, 0.1, 0.3, 0.1, 0);
		}
		p.teleportTo(back.x, back.y, back.z);
		p.setHealth(Math.max(p.getHealth(), Math.min(p.getMaxHealth(), hp)));
		p.resetFallDistance();
		r.fx.history.clear();
		RiftFx.screen(p, "flash", 0xFF66E0FF, 25);
		level.playSound(null, p.blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.0F, 1.8F);
		level.playSound(null, p.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8F, 0.6F);
		dev.rubymod.game.Alerts.title(p, "REWIND", "Three seconds back", ChatFormatting.AQUA);
		if (RelicCombos.on(r, RelicCombos.Duo.PERFECT_PARRY)) {
			Vec3 c = p.position();
			for (int ring = 1; ring <= 4; ring++) {
				RiftFx.ring(level, c.add(0, 0.3 + ring * 0.2, 0), ring * 1.8, RiftFx.dust(0xFFFFFF, 1.6F), 16 * ring);
			}
			for (LivingEntity e : enemiesNear(level, c, 7, null)) {
				hurt(p, e, 14, 0xFFFFFFFF);
				e.setDeltaMovement(e.position().subtract(c).multiply(1, 0, 1).normalize().scale(1.8).add(0, 0.6, 0));
				e.needsSync = true;
			}
			level.playSound(null, p.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 0.8F, 1.6F);
		}
	}

	static void meteors(ServerLevel level, ServerPlayer p) {
		List<LivingEntity> targets = enemiesNear(level, p.position(), 40, null);
		java.util.Collections.shuffle(targets, new java.util.Random(level.getRandom().nextLong()));
		long now = now(level);
		for (int i = 0; i < Math.min(6, targets.size()); i++) {
			LivingEntity e = targets.get(i);
			METEORS.add(new Meteor(new Vec3(e.getX(), Math.floor(e.getY()) + 0.1, e.getZ()), now + 24 + i * 5, p.getUUID()));
		}
		level.playSound(null, p.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.2F, 0.5F);
		p.sendOverlayMessage(Component.literal("METEOR SHOWER!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
	}

	private static void rollDice(ServerLevel level, ServerPlayer p, Rift.Runner r) {
		r.boosted = null;
		r.disabled = null;
		if (!r.relics.contains(Relic.GAMBLERS_DICE)) {
			return;
		}
		boolean bounty = r.relics.contains(Relic.THE_HUNTED);
		List<Relic> pool = r.relics.stream().filter(x -> x != Relic.GAMBLERS_DICE && x.tier != Relic.Tier.BOND).toList();
		if (pool.size() < 2) {
			return;
		}
		var random = level.getRandom();
		r.boosted = pool.get(random.nextInt(pool.size()));
		Relic off;
		if (bounty && pool.size() == 2 && pool.contains(Relic.THE_HUNTED) && r.boosted != Relic.THE_HUNTED) {
			r.boosted = Relic.THE_HUNTED;
		}
		do {
			off = pool.get(random.nextInt(pool.size()));
		} while (off == r.boosted || bounty && off == Relic.THE_HUNTED);
		r.disabled = off;
		apply(p, r);
		p.sendSystemMessage(Component.literal("\u2684 Gambler's Dice: ").withStyle(ChatFormatting.LIGHT_PURPLE)
				.append(Component.literal(r.boosted.title + " is doubled").withStyle(ChatFormatting.GREEN))
				.append(Component.literal(", " + r.disabled.title + " is off this room.").withStyle(ChatFormatting.RED)));
		level.playSound(null, p.blockPosition(), SoundEvents.BONE_BLOCK_PLACE, SoundSource.PLAYERS, 1.0F, 1.6F);
	}

	private static void hunter(ServerLevel level, ServerPlayer p, Rift.Runner r) {
		var vex = EntityTypes.VEX.create(level, EntitySpawnReason.EVENT);
		if (vex == null) {
			return;
		}
		Vec3 at = p.position().add(0, 6, -8);
		vex.snapTo(at.x, at.y, at.z, 0, 0);
		boolean bounty = r.relics.contains(Relic.GAMBLERS_DICE);
		if (bounty) {
			// Double or Nothing: it can die, and it's worth it.
			AttributeInstance hp = vex.getAttribute(Attributes.MAX_HEALTH);
			if (hp != null) {
				hp.setBaseValue(60);
				vex.setHealth(60);
			}
		} else {
			vex.setPermanentlyInvulnerable(true);
		}
		vex.setCustomName(Component.literal(bounty ? "The Hunter (bounty: 50 gold)" : "The Hunter").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD));
		vex.addTag(Rift.TAG);
		vex.setTarget(p);
		level.addFreshEntity(vex);
		r.fx.hunter = vex.getUUID();
		level.playSound(null, p.blockPosition(), SoundEvents.VEX_CHARGE, SoundSource.HOSTILE, 1.5F, 0.6F);
	}

	private static void tickSecondWave(ServerLevel level, ServerPlayer p, Rift.Runner r, State s) {
		long now = now(level);
		boolean fighting = Rift.state == Rift.State.FIGHT;
		// Rewind remembers the last 3 seconds.
		if (r.has(Relic.REWIND)) {
			s.history.addLast(new Object[] {p.position(), p.getHealth()});
			while (s.history.size() > 60) {
				s.history.removeFirst();
			}
		}
		// Static Charge: sprinting builds it, with crackling sparks.
		if (r.has(Relic.STATIC_CHARGE) && p.isSprinting() && s.charge < 100) {
			s.charge = Math.min(100, s.charge + 0.6F * r.level(Relic.STATIC_CHARGE));
			if (now % 3 == 0) {
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 0.8, p.getZ(), 2 + (int) (s.charge / 30), 0.3, 0.5, 0.3, 0.1);
			}
			if (s.charge >= 100 && now % 20 == 0) {
				p.sendOverlayMessage(Component.literal("STATIC: FULLY CHARGED").withStyle(ChatFormatting.AQUA));
			}
		}
		// Ground Pound: crouch in the air to slam down.
		if (r.has(Relic.GROUND_POUND)) {
			if (!p.onGround() && p.isShiftKeyDown() && !s.pounding && p.getDeltaMovement().y < 0.3) {
				s.pounding = true;
				s.poundFrom = p.getY();
				p.setDeltaMovement(0, -1.8, 0);
				p.needsSync = true;
				level.playSound(null, p.blockPosition(), SoundEvents.BREEZE_JUMP, SoundSource.PLAYERS, 1.0F, 0.6F);
			} else if (s.pounding && !p.onGround()) {
				level.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.5, p.getZ(), 2, 0.2, 0.2, 0.2, 0.01);
			} else if (s.pounding && p.onGround()) {
				s.pounding = false;
				int stars = r.level(Relic.GROUND_POUND);
				double reach = 3.5 + 1.5 * stars;
				// Meteor Drop: the higher you came from, the bigger it gets, and it burns.
				boolean meteor = RelicCombos.on(r, RelicCombos.Duo.METEOR_DROP);
				double height = Math.max(0, s.poundFrom - p.getY());
				if (meteor) {
					reach += Math.min(6, height * 0.4);
					level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, p.getX(), p.getY() + 0.3, p.getZ(), 1, 0, 0, 0, 0);
					RiftFx.ring(level, p.position().add(0, 0.25, 0), reach * 0.9, ParticleTypes.FLAME, 50);
					RiftFx.ring(level, p.position().add(0, 0.3, 0), reach * 0.6, ParticleTypes.LAVA, 16);
				}
				var under = level.getBlockState(p.blockPosition().below());
				for (int ring = 1; ring <= (int) reach; ring++) {
					RiftFx.ring(level, p.position().add(0, 0.2, 0), ring, new BlockParticleOption(ParticleTypes.BLOCK, under.isAir() ? Blocks.STONE.defaultBlockState()
							: under), 8 * ring);
				}
				RiftFx.ring(level, p.position().add(0, 0.3, 0), reach, RiftFx.dust(0xFFFFFF, 1.6F), 40);
				for (LivingEntity e : enemiesNear(level, p.position(), reach, null)) {
					hurt(p, e, 6 + 5 * stars + (meteor ? (float) Math.min(20, height * 1.5) : 0), meteor ? 0xFFFF7A2E : 0xFFFFFFFF);
					if (meteor) {
						e.igniteForSeconds(5);
					}
					e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 50, 6, false, false));
					e.setDeltaMovement(e.getDeltaMovement().add(0, 0.6, 0));
				}
				level.playSound(null, p.blockPosition(), SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 1.4F, 0.7F);
				RiftFx.shake(p, 1.2F, 10);
			}
		}
		// Overclock: a blue shimmer while it runs.
		if (r.has(Relic.OVERCLOCK) && now < s.overclockUntil && now % 2 == 0) {
			level.sendParticles(RiftFx.dust(0x66E0FF, 0.8F), p.getX(), p.getY() + 1.2, p.getZ(), 2, 0.3, 0.4, 0.3, 0);
		}
		// The Ruby Dragon: circles your head, breathes fire at the nearest enemy.
		if (r.has(Relic.RUBY_DRAGON)) {
			double a = now * 0.08;
			Vec3 at = p.position().add(Math.cos(a) * 2.2, 2.6 + Math.sin(now * 0.15) * 0.25, Math.sin(a) * 2.2);
			if (s.dragon == null || !(level.getEntity(s.dragon) instanceof Display.ItemDisplay)) {
				Display.ItemDisplay d = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
				if (d != null) {
					d.snapTo(at.x, at.y, at.z, 0, 0);
					((ItemDisplayAccessor) d).rubymod$setItemStack(new ItemStack(net.minecraft.world.item.Items.DRAGON_HEAD));
					((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(2);
					d.addTag(Rift.TAG);
					level.addFreshEntity(d);
					s.dragon = d.getUUID();
				}
			}
			LivingEntity target = fighting ? nearestEnemy(level, at, 14, null, true) : null;
			if (level.getEntity(s.dragon) instanceof Display.ItemDisplay d) {
				d.setPos(at);
				float yaw = target != null ? (float) Math.toDegrees(Math.atan2(-(target.getX() - at.x), target.getZ() - at.z)) : (float) Math.toDegrees(-a);
				((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY((float) Math.toRadians(-yaw)),
						new Vector3f(0.7F, 0.7F, 0.7F), new Quaternionf()));
			}
			if (now % 4 == 0) {
				level.sendParticles(RiftFx.dust(0xFF3D6E, 0.9F), at.x, at.y, at.z, 2, 0.2, 0.1, 0.2, 0);
			}
			int every = 48 / (1 + r.level(Relic.RUBY_DRAGON));
			if (target != null && now >= s.dragonAt) {
				s.dragonAt = now + every;
				Vec3 to = target.getBoundingBox().getCenter();
				for (double d = 0; d < at.distanceTo(to); d += 0.5) {
					Vec3 f = at.add(to.subtract(at).normalize().scale(d));
					level.sendParticles(ParticleTypes.FLAME, true, false, f.x, f.y, f.z, 2, 0.1, 0.1, 0.1, 0.02);
				}
				target.igniteForSeconds(3);
				hurt(p, target, 5 + 2 * r.level(Relic.RUBY_DRAGON), 0xFFFF7A2E);
				level.playSound(null, BlockPos.containing(at), SoundEvents.ENDER_DRAGON_SHOOT, SoundSource.PLAYERS, 0.5F, 1.6F);
			}
		}
		// The Hunter keeps after you.
		if (s.hunter != null && now % 20 == 0 && level.getEntity(s.hunter) instanceof net.minecraft.world.entity.Mob h) {
			h.setTarget(p);
		}
	}

	private static void tickSecondWaveWorld(ServerLevel level, long t) {
		var server = level.getServer();
		for (Iterator<Echo> it = ECHOES.iterator(); it.hasNext();) {
			Echo e = it.next();
			if (t < e.when()) {
				continue;
			}
			it.remove();
			ServerPlayer p = server.getPlayerList().getPlayer(e.owner());
			if (p != null && !p.isSpectator()) {
				// A ghost of the gun fires again.
				Vec3 m = p.getEyePosition().add(p.getLookAngle().scale(0.8)).add(0, -0.2, 0);
				level.sendParticles(ParticleTypes.SCULK_SOUL, m.x, m.y, m.z, 4, 0.1, 0.1, 0.1, 0.02);
				int volleys = RelicCombos.on(p, RelicCombos.Duo.FIRING_SQUAD) ? shotMultiplier(p) : 1;
				for (int v = 0; v < volleys; v++) {
					dev.rubymod.gun.Gunfire.ghostShot(level, p, e.stats());
				}
				if (volleys > 1) {
					level.sendParticles(ParticleTypes.SCULK_SOUL, m.x, m.y, m.z, 8, 0.3, 0.2, 0.3, 0.03);
				}
				level.playSound(null, p.blockPosition(), SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.PLAYERS, 0.15F, 2.0F);
			}
		}
		for (Iterator<Meteor> it = METEORS.iterator(); it.hasNext();) {
			Meteor m = it.next();
			long left = m.when() - t;
			if (left > 0) {
				if (left % 3 == 0) {
					RiftFx.ring(level, m.at(), 2.2, RiftFx.dust(0xFF7A2E, 1.3F), 16);
				}
				if (left < 16) {
					Vec3 f = m.at().add(left * 0.5, left * 1.4, left * 0.4);
					level.sendParticles(ParticleTypes.FLAME, true, false, f.x, f.y, f.z, 6, 0.25, 0.25, 0.25, 0.02);
					level.sendParticles(ParticleTypes.LAVA, true, false, f.x, f.y, f.z, 1, 0.1, 0.1, 0.1, 0);
				}
				continue;
			}
			it.remove();
			ServerPlayer p = server.getPlayerList().getPlayer(m.owner());
			if (p != null) {
				blast(level, p, m.at().add(0, 0.5, 0), 3.2, 14, 0xFFFF7A2E, false);
				level.sendParticles(ParticleTypes.LAVA, m.at().x, m.at().y + 0.5, m.at().z, 16, 1, 0.5, 1, 0);
			}
		}
		if (t % 20 == 0) {
			boolean inferno = team(level, Relic.TOXIC_INFERNO);
			for (Iterator<Map.Entry<UUID, Poison>> it = POISONED.entrySet().iterator(); it.hasNext();) {
				var e = it.next();
				Poison v = e.getValue();
				if (t >= v.until() || !(level.getEntity(e.getKey()) instanceof LivingEntity mob) || !mob.isAlive()) {
					it.remove();
					continue;
				}
				ServerPlayer p = server.getPlayerList().getPlayer(v.owner());
				if (p != null) {
					float dmg = RelicCombos.poisonTick(level, p, mob, v.stacks() * (inferno && mob.isOnFire() ? 1.6F : 0.8F));
					hurt(p, mob, dmg, 0xFF7AE33A);
					level.sendParticles(RiftFx.dust(0x7AE33A, 1.1F), mob.getX(), mob.getY() + mob.getBbHeight() * 0.7, mob.getZ(), 3 + v.stacks() / 3, 0.3,
							0.3, 0.3, 0);
				}
			}
		}
	}
}
