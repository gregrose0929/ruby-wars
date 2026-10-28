package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Relic combos. Duos: two particular relics on the same runner switch on a third power by
 * themselves ("COMBO!"), shown on the HUD and hinted on any card that would complete one.
 * Also the newer element Bonds' effects (Thunderbomb, Neurotoxin, Firestorm, Ember Heart,
 * Crystal Heart, Black Ice, Gem Bomb, Plague Bomb, Bloodsucker), hooked from RelicEffects.
 */
public final class RelicCombos {
	public enum Duo {
		BULLET_HOSE(Relic.BOTTOMLESS_MAG, Relic.QUICK_HANDS, "Bullet Hose", "rubymod:heavy_ammo",
				"Spin-up reaches 3x fire rate, and fully spun-up shots pierce through enemies."),
		FIRING_SQUAD(Relic.TWIN_BARRELS, Relic.ECHO_SHOT, "Firing Squad", "minecraft:crossbow", "Every 3rd shot echoes, and each echo fires a full twin volley."),
		LIVING_FORTRESS(Relic.TITAN_BLOOD, Relic.THORN_ARMOR, "Living Fortress", "minecraft:cactus",
				"Above half health, Thorn Armor hits back twice as hard and knocks attackers flying."),
		METEOR_DROP(Relic.ROCKET_BOOTS, Relic.GROUND_POUND, "Meteor Drop", "minecraft:mace",
				"The higher you Ground Pound from, the bigger the shockwave, and it sets everything on fire."),
		SECOND_WIND(Relic.FIELD_MEDIC, Relic.TIME_LOOP, "Second Wind", "minecraft:glistering_melon_slice",
				"Teammates you revive come back at full health, untouchable for 5 seconds."),
		JACKPOT(Relic.HEADHUNTER, Relic.LUCKY_COIN, "Jackpot", "minecraft:gold_nugget", "Headshots are always critical hits, and critical kills shower triple gold."),
		PINBALL(Relic.RICOCHET, Relic.SPLIT_SHOT, "Pinball", "minecraft:slime_ball", "Every bounce splits off two extra fragments."),
		UNSTOPPABLE(Relic.OVERKILL, Relic.GLASS_CANNON, "Unstoppable", "minecraft:iron_sword",
				"Leftover damage keeps jumping from kill to kill, and every jump explodes."),
		GUN_NEST(Relic.DROP_TURRET, Relic.ARSENAL, "Gun Nest", "rubymod:sentry_turret", "Your turret fires a copy of the gun in your hand."),
		PHANTOM_STEP(Relic.BLINK, Relic.HALL_OF_MIRRORS, "Phantom Step", "minecraft:ender_pearl",
				"Blinking leaves a mirror image behind that lures enemies in, then explodes."),
		SHADOW_LEGION(Relic.SHADOW_CLONE, Relic.NECROMANCER, "Shadow Legion", "minecraft:wither_skeleton_skull",
				"The risen fight for the whole room as shadow warriors that hit twice as hard."),
		PERFECT_PARRY(Relic.MIRROR_GUARD, Relic.REWIND, "Perfect Parry", "minecraft:shield",
				"Reflected shots hit twice as hard, and every Rewind ends in a shockwave that blasts enemies away."),
		GRAVITY_OVERLOAD(Relic.BLACK_HOLE, Relic.OVERCLOCK, "Gravity Overload", "minecraft:ender_eye", "Every reload opens a black hole where you're aiming."),
		DOUBLE_OR_NOTHING(Relic.THE_HUNTED, Relic.GAMBLERS_DICE, "Double or Nothing", "minecraft:wither_rose",
				"The Hunter can be killed: it pays 50 gold and powers up one of your relics. The Dice never switch off The Hunted.");

		public final Relic a;
		public final Relic b;
		public final String title;
		public final String icon;
		public final String about;

		Duo(Relic a, Relic b, String title, String icon, String about) {
			this.a = a;
			this.b = b;
			this.title = title;
			this.icon = icon;
			this.about = about;
		}

		public String id() {
			return "duo_" + name().toLowerCase();
		}
	}

	/** The duo colour (cards, HUD, banner). */
	public static final int COLOR = 0xFFFF9A2E;

	private RelicCombos() {
	}

	static boolean on(Rift.Runner r, Duo d) {
		return r != null && r.has(d.a) && r.has(d.b);
	}

	static boolean on(ServerPlayer p, Duo d) {
		return on(Rift.runner(p), d);
	}

	/** Bullet Hose: spun all the way up, shots go through. */
	public static boolean pierces(ServerPlayer p) {
		Rift.Runner r = Rift.runner(p);
		return on(r, Duo.BULLET_HOSE) && r.fx.spin >= 25;
	}

	public static boolean pinball(ServerPlayer p) {
		return on(p, Duo.PINBALL);
	}

	static List<Duo> active(Rift.Runner r) {
		List<Duo> out = new ArrayList<>();
		for (Duo d : Duo.values()) {
			if (on(r, d)) {
				out.add(d);
			}
		}
		return out;
	}

	/** What a card for {@code relic} would complete: "with your Blink: Phantom Step - ..." (empty: nothing). */
	static String hint(Rift.Runner r, Relic relic) {
		if (r == null || r.relics.contains(relic)) {
			return "";
		}
		for (Duo d : Duo.values()) {
			Relic other = d.a == relic ? d.b : d.b == relic ? d.a : null;
			if (other != null && r.relics.contains(other)) {
				return "With your " + other.title + ": " + d.title + "! " + d.about;
			}
		}
		return "";
	}

	/** A relic just arrived: any duo it completes goes off with a bang. */
	static void announce(ServerPlayer p, Rift.Runner r, Relic gained) {
		for (Duo d : Duo.values()) {
			if ((d.a == gained || d.b == gained) && r.relics.contains(d.a) && r.relics.contains(d.b)) {
				RiftCodex.learn(p, "duo:" + d.id(), d.title);
				ServerLevel level = (ServerLevel) p.level();
				Vec3 at = p.position();
				// Two streams, one per relic, twisting up into one.
				for (int i = 0; i < 30; i++) {
					double a = i * 0.45;
					double rad = 2.6 - i * 0.08;
					level.sendParticles(RiftFx.dust(d.a.tier.color & 0xFFFFFF, 1.4F), true, false, at.x + Math.cos(a) * rad, at.y + 0.2 + i * 0.08,
							at.z + Math.sin(a) * rad, 1, 0, 0, 0, 0);
					level.sendParticles(RiftFx.dust(d.b.tier.color & 0xFFFFFF, 1.4F), true, false, at.x - Math.cos(a) * rad, at.y + 0.2 + i * 0.08,
							at.z - Math.sin(a) * rad, 1, 0, 0, 0, 0);
				}
				RiftFx.burst(level, at.add(0, 2.6, 0), COLOR, 60, 1.0);
				RiftFx.beam(level, at, 22, ParticleTypes.END_ROD, COLOR);
				RiftFx.ring(level, at.add(0, 0.2, 0), 3.0, RiftFx.dust(COLOR & 0xFFFFFF, 1.8F), 40);
				RiftFx.screen(p, "relic:" + d.icon, COLOR, 60);
				RiftFx.shake(p, 0.6F, 8);
				level.playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0F, 1.0F);
				level.playSound(null, p.blockPosition(), SoundEvents.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 0.5F, 1.6F);
				dev.rubymod.game.Alerts.title(p, "COMBO! " + d.title, d.a.title + " + " + d.b.title, ChatFormatting.GOLD);
				p.sendSystemMessage(Component.literal("⚡ COMBO: " + d.title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
						.append(Component.literal(" (" + d.a.title + " + " + d.b.title + "): " + d.about).withStyle(ChatFormatting.YELLOW)));
			}
		}
	}

	/** HUD relic lines for the active duos: "id|icon|DUO|title|about". */
	static List<String> hudLines(Rift.Runner r) {
		List<String> out = new ArrayList<>();
		for (Duo d : active(r)) {
			out.add(d.id() + "|" + d.icon + "|DUO|⚡ " + d.title + "|" + d.a.title + " + " + d.b.title + ": " + d.about);
		}
		return out;
	}

	// ----- Element Bonds -----

	private record Cloud(Vec3 at, double radius, long until, UUID owner) {
	}

	private static final List<Cloud> CLOUDS = new ArrayList<>();
	/** Phantom Step decoys that blow up when they fade, and Shadow Legion's warriors. */
	static final Set<UUID> DECOYS = new HashSet<>();
	static final Set<UUID> SHADOWS = new HashSet<>();
	private static boolean inBomb;

	/**
	 * Relic lightning just struck {@code e}. Neurotoxin: it carries poison, and stuns the
	 * already poisoned. Thunderbomb: the strike ends in a little explosion.
	 */
	static void shocked(ServerLevel level, ServerPlayer p, LivingEntity e, boolean canExplode) {
		if (!e.isAlive()) {
			return;
		}
		if (RelicEffects.team(level, Relic.NEUROTOXIN)) {
			if (RelicEffects.POISONED.containsKey(e.getUUID())) {
				e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 7, false, false));
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, e.getX(), e.getY() + e.getBbHeight(), e.getZ(), 10, 0.3, 0.2, 0.3, 0.1);
			}
			RelicEffects.poison(level, p, e, 2);
			level.sendParticles(RiftFx.dust(0x9AF0A0, 1.0F), e.getX(), e.getY() + 1, e.getZ(), 6, 0.3, 0.5, 0.3, 0);
		}
		if (canExplode && !inBomb && RelicEffects.team(level, Relic.THUNDERBOMB)) {
			inBomb = true;
			try {
				RelicEffects.blast(level, p, e.getBoundingBox().getCenter(), 1.8, 4, 0xFF66E0FF, false);
			} finally {
				inBomb = false;
			}
		}
	}

	/** Every relic explosion (RelicEffects.blast), after its damage. */
	static void onBlast(ServerLevel level, ServerPlayer owner, Vec3 at, double radius) {
		if (owner == null) {
			return;
		}
		// Thunderbomb: lightning leaps out of the blast to enemies just beyond it.
		if (!inBomb && RelicEffects.team(level, Relic.THUNDERBOMB)) {
			List<LivingEntity> near = RelicEffects.enemiesNear(level, at, radius + 6, null);
			near.removeIf(e -> e.position().distanceTo(at) < radius);
			near.sort(java.util.Comparator.comparingDouble(e -> e.position().distanceToSqr(at)));
			for (int i = 0; i < Math.min(3, near.size()); i++) {
				LivingEntity e = near.get(i);
				RiftFx.arc(level, at, e.getBoundingBox().getCenter(), 0xFF66E0FF);
				RelicEffects.hurt(owner, e, 6, 0xFF66E0FF);
				shocked(level, owner, e, false);
			}
		}
		// Firestorm: everything caught is set alight, and the ground around burns.
		if (RelicEffects.team(level, Relic.FIRESTORM)) {
			for (LivingEntity e : RelicEffects.enemiesNear(level, at, radius, null)) {
				e.igniteForSeconds(5);
			}
			long until = RelicEffects.now(level) + 80;
			double y = Math.floor(at.y - 0.4) + 0.05;
			RelicEffects.PATCHES.add(new RelicEffects.Patch(new Vec3(at.x, y, at.z), until, owner.getUUID()));
			for (int k = 0; k < 4; k++) {
				double a = k * Math.PI / 2 + 0.4;
				RelicEffects.PATCHES.add(new RelicEffects.Patch(new Vec3(at.x + Math.cos(a) * radius * 0.7, y, at.z + Math.sin(a) * radius * 0.7), until,
						owner.getUUID()));
			}
			RiftFx.ring(level, new Vec3(at.x, y + 0.2, at.z), radius * 0.8, ParticleTypes.FLAME, 24);
		}
		// Plague Bomb: a green cloud hangs where it went off.
		if (RelicEffects.team(level, Relic.PLAGUE_BOMB)) {
			CLOUDS.add(new Cloud(at, Math.max(2.5, radius), RelicEffects.now(level) + 70, owner.getUUID()));
			level.sendParticles(RiftFx.dust(0x7AE33A, 2.0F), at.x, at.y, at.z, 30, radius * 0.4, 0.5, radius * 0.4, 0);
		}
	}

	/** An enemy killed by a relic explosion. Gem Bomb: it pays out. */
	static void blastKill(ServerLevel level, ServerPlayer owner, LivingEntity e) {
		if (owner == null || !RelicEffects.team(level, Relic.GEM_BOMB)) {
			return;
		}
		Rift.gold += 3;
		owner.heal(2);
		Vec3 at = e.getBoundingBox().getCenter();
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, dev.rubymod.registry.ModBlocks.RUBY_BLOCK.defaultBlockState()), at.x, at.y, at.z, 16, 0.3,
				0.3, 0.3, 0.2);
		level.sendParticles(ParticleTypes.WAX_ON, at.x, at.y, at.z, 10, 0.3, 0.3, 0.3, 0.5);
		RiftFx.line(level, at, owner.position().add(0, 1, 0), RiftFx.dust(0xFFC02E, 0.8F), 0.6);
		level.playSound(null, e.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.4F);
	}

	/** An enemy died while burning. Ember Heart: embers drift to everyone near and heal them. */
	static void burnedKill(ServerLevel level, LivingEntity victim) {
		if (!RelicEffects.team(level, Relic.EMBER_HEART)) {
			return;
		}
		Vec3 at = victim.getBoundingBox().getCenter();
		level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 24, 0.4, 0.5, 0.4, 0.08);
		for (ServerPlayer mate : Rift.players(level.getServer())) {
			if (!mate.isSpectator() && mate.position().distanceTo(at) < 14) {
				mate.heal(2);
				RiftFx.line(level, at, mate.position().add(0, 1.2, 0), RiftFx.dust(0xFF9A2E, 1.0F), 0.5);
				level.sendParticles(ParticleTypes.HEART, mate.getX(), mate.getY() + 2.1, mate.getZ(), 1, 0.2, 0.1, 0.2, 0);
			}
		}
	}

	/** A frozen enemy shattered. Crystal Heart: ruby shards that cut and heal. Black Ice: a poison cloud. */
	static void shattered(ServerLevel level, ServerPlayer p, Vec3 at) {
		if (RelicEffects.team(level, Relic.CRYSTAL_HEART)) {
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, dev.rubymod.registry.ModBlocks.RUBY_BLOCK.defaultBlockState()), at.x, at.y, at.z, 40,
					0.6, 0.6, 0.6, 0.35);
			for (LivingEntity e : RelicEffects.enemiesNear(level, at, 6, null)) {
				RiftFx.line(level, at, e.getBoundingBox().getCenter(), RiftFx.dust(0xFF3D6E, 1.0F), 0.4);
				RelicEffects.hurt(p, e, 6, 0xFFFF3D6E);
			}
			for (ServerPlayer mate : Rift.players(level.getServer())) {
				if (!mate.isSpectator() && mate.position().distanceTo(at) < 20) {
					mate.heal(2);
					RiftFx.line(level, at, mate.position().add(0, 1.2, 0), RiftFx.dust(0xFF3D6E, 0.8F), 0.6);
				}
			}
		}
		if (RelicEffects.team(level, Relic.BLACK_ICE)) {
			for (LivingEntity e : RelicEffects.enemiesNear(level, at, 5, null)) {
				RelicEffects.poison(level, p, e, 4);
			}
			level.sendParticles(ParticleTypes.SNEEZE, at.x, at.y, at.z, 40, 1.4, 0.6, 1.4, 0.05);
			level.sendParticles(RiftFx.dust(0x3A8A6A, 1.8F), at.x, at.y, at.z, 30, 1.5, 0.6, 1.5, 0);
		}
	}

	/** Poison ticking on an enemy: Black Ice doubles it on the frozen; Bloodsucker turns some into health. */
	static float poisonTick(ServerLevel level, ServerPlayer owner, LivingEntity mob, float dmg) {
		if (RelicEffects.FROZEN.containsKey(mob.getUUID()) && RelicEffects.team(level, Relic.BLACK_ICE)) {
			dmg *= 2;
			level.sendParticles(ParticleTypes.SNOWFLAKE, mob.getX(), mob.getY() + 1, mob.getZ(), 4, 0.3, 0.4, 0.3, 0.02);
		}
		if (RelicEffects.team(level, Relic.BLOODSUCKER)) {
			owner.heal(dmg * 0.25F);
			RiftFx.line(level, mob.getBoundingBox().getCenter(), owner.position().add(0, 1, 0), RiftFx.dust(0xB00020, 0.7F), 0.8);
		}
		return dmg;
	}

	static void tickWorld(ServerLevel level, long t) {
		var server = level.getServer();
		for (Iterator<Cloud> it = CLOUDS.iterator(); it.hasNext();) {
			Cloud c = it.next();
			if (t >= c.until()) {
				it.remove();
				continue;
			}
			if (t % 3 == 0) {
				level.sendParticles(RiftFx.dust(0x7AE33A, 1.6F), c.at().x, c.at().y, c.at().z, 6, c.radius() * 0.45, 0.4, c.radius() * 0.45, 0);
				level.sendParticles(ParticleTypes.SNEEZE, c.at().x, c.at().y, c.at().z, 2, c.radius() * 0.4, 0.3, c.radius() * 0.4, 0.01);
			}
			ServerPlayer owner = server.getPlayerList().getPlayer(c.owner());
			if (owner != null && t % 10 == 0) {
				for (LivingEntity e : RelicEffects.enemiesNear(level, c.at(), c.radius(), null)) {
					RelicEffects.poison(level, owner, e, 1);
				}
			}
		}
		if (t % 6 == 0) {
			for (UUID id : SHADOWS) {
				if (level.getEntity(id) instanceof LivingEntity e) {
					level.sendParticles(RiftFx.dust(0x6020A0, 1.2F), e.getX(), e.getY() + e.getBbHeight() * 0.6, e.getZ(), 3, 0.3, 0.4, 0.3, 0);
					level.sendParticles(ParticleTypes.SMOKE, e.getX(), e.getY() + 0.2, e.getZ(), 2, 0.2, 0.1, 0.2, 0.01);
				}
			}
		}
	}

	/** A Phantom Step decoy faded: it goes off. */
	static void decoyGone(ServerLevel level, ServerPlayer owner, Entity decoy) {
		if (!DECOYS.remove(decoy.getUUID()) || owner == null) {
			return;
		}
		Vec3 at = decoy.position().add(0, 1, 0);
		level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 40, 0.5, 0.8, 0.5, 0.25);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, net.minecraft.world.level.block.Blocks.TINTED_GLASS.defaultBlockState()), at.x, at.y,
				at.z, 40, 0.5, 0.8, 0.5, 0.3);
		level.playSound(null, BlockPos.containing(at), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.4F, 0.6F);
		RelicEffects.blast(level, owner, at, 4.0, 18, 0xFFC27BFF, false);
	}

	/** Double or Nothing: a Hunter died. */
	public static void onDeath(LivingEntity dead) {
		if (!(dead.level() instanceof ServerLevel level)) {
			return;
		}
		for (Rift.Runner r : Rift.RUNNERS.values()) {
			if (dead.getUUID().equals(r.fx.hunter)) {
				r.fx.hunter = null;
				ServerPlayer p = level.getServer().getPlayerList().getPlayer(r.id);
				if (p == null) {
					return;
				}
				Rift.gold += 50;
				List<Relic> up = r.relics.stream().filter(r::canCombine).toList();
				Vec3 at = dead.getBoundingBox().getCenter();
				level.sendParticles(ParticleTypes.WAX_ON, at.x, at.y, at.z, 60, 0.6, 0.6, 0.6, 1.0);
				RiftFx.burst(level, at, 0xFFFFC02E, 60, 1.0);
				level.playSound(null, dead.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.2F, 0.8F);
				dev.rubymod.game.Alerts.title(p, "BOUNTY CLAIMED!", "+50 gold" + (up.isEmpty() ? "" : " and a relic powered up"), ChatFormatting.GOLD);
				if (!up.isEmpty()) {
					Rift.give(p, r, up.get(level.getRandom().nextInt(up.size())));
				}
				return;
			}
		}
	}

	static void clearWorld(ServerLevel level) {
		CLOUDS.clear();
		DECOYS.clear();
		SHADOWS.clear();
	}
}
