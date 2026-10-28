package dev.rubymod.game.rift;

import java.util.ArrayDeque;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Storm Wyrm (act 3, instead of the Titan sometimes): a long serpent of copper and sea
 * lanterns that swims through the air. It circles high over the hall calling down lightning
 * on red circles, then marks one of you, roars, and dives. Where it hits the ground it lies
 * stunned for a few seconds, crackling: that's the time to hit it hardest. Below half health
 * it's faster and the storm gets worse. When it dies, its body comes apart piece by piece.
 */
public final class StormWyrm implements RiftBoss {
	private enum Move { CIRCLE, MARK, DIVE, REST, RISE }

	private static final int SEGMENTS = 20;
	/** Blocks of path between one body segment and the next. */
	private static final double SPACING = 1.25;
	private final UUID head;
	private Mob headRef;
	private final RiftRoom room;
	private final ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("STORM WYRM").withStyle(ChatFormatting.BOLD),
			BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.NOTCHED_10);
	private final List<UUID> body = new ArrayList<>();
	private final UUID[] skull = new UUID[3];
	/** Where the head has been (the body follows along it). */
	private final ArrayDeque<Vec3> trail = new ArrayDeque<>();
	private Move move = Move.CIRCLE;
	private int moveT;
	private double angle;
	private Vec3 pos;
	private Vec3 diveTo;
	private Vec3 diveFrom;
	private ServerPlayer marked;
	private final List<Vec3> strikes = new ArrayList<>();
	private int strikesIn;
	private int stormAt = 60;
	private int t;
	private int dying = -1;
	private boolean done;
	private boolean enraged;

	private StormWyrm(UUID head, RiftRoom room) {
		this.head = head;
		this.room = room;
	}

	static StormWyrm spawn(ServerLevel level, RiftRoom room, int players) {
		Mob h = EntityTypes.HUSK.create(level, EntitySpawnReason.EVENT);
		if (h == null) {
			return null;
		}
		Vec3 start = Vec3.atBottomCenterOf(room.center).add(0, 13, -room.half + 8);
		h.snapTo(start.x, start.y, start.z, 0, 0);
		h.setNoAi(true);
		h.setNoGravity(true);
		h.setSilent(true);
		h.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false));
		h.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, -1, 0, false, false));
		set(h, Attributes.SCALE, 2.4);
		set(h, Attributes.MAX_HEALTH, 640 * (1 + 0.65 * (players - 1)));
		set(h, Attributes.KNOCKBACK_RESISTANCE, 1.0);
		h.setHealth(h.getMaxHealth());
		h.setPersistenceRequired();
		h.setCustomName(Component.literal("Storm Wyrm").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		h.addTag(Rift.TAG);
		h.addTag(JuggernautMech.BOSS_TAG);
		level.addFreshEntity(h);
		Rift.MOBS.add(h.getUUID());
		StormWyrm boss = new StormWyrm(h.getUUID(), room);
		boss.headRef = h;
		boss.pos = start;
		// The skull: a copper snout, two glowing eyes.
		BlockState[] skullParts = {Blocks.CUT_COPPER.waxed().pick(net.minecraft.world.level.block.WeatheringCopper.WeatherState.OXIDIZED).defaultBlockState(), Blocks.SEA_LANTERN.defaultBlockState(), Blocks.SEA_LANTERN.defaultBlockState()};
		for (int i = 0; i < 3; i++) {
			Display.BlockDisplay d = boss.display(level, start, skullParts[i]);
			boss.skull[i] = d == null ? null : d.getUUID();
		}
		// The body: copper and prismarine plates with a glowing spine.
		for (int i = 0; i < SEGMENTS; i++) {
			BlockState b = i % 4 == 3 ? Blocks.SEA_LANTERN.defaultBlockState() : i % 2 == 0 ? Blocks.CUT_COPPER.waxed().pick(net.minecraft.world.level.block.WeatheringCopper.WeatherState.WEATHERED).defaultBlockState()
					: Blocks.PRISMARINE_BRICKS.defaultBlockState();
			Display.BlockDisplay d = boss.display(level, start, b);
			if (d != null) {
				boss.body.add(d.getUUID());
			}
		}
		for (int i = 0; i < 200; i++) {
			boss.trail.addFirst(start);
		}
		for (ServerPlayer p : Rift.players(level.getServer())) {
			boss.bar.addPlayer(p);
		}
		return boss;
	}

	private Display.BlockDisplay display(ServerLevel level, Vec3 at, BlockState state) {
		Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d == null) {
			return null;
		}
		d.snapTo(at.x, at.y, at.z, 0, 0);
		((BlockDisplayAccessor) d).rubymod$setBlockState(state);
		((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(2);
		d.addTag(Rift.TAG);
		level.addFreshEntity(d);
		return d;
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
	public LivingEntity body(ServerLevel level) {
		return level.getEntity(head) instanceof LivingEntity e && e.isAlive() ? e : null;
	}

	@Override
	public String debug() {
		return "wyrm t" + t + " " + move + " at " + (pos == null ? "-" : (int) pos.x + "," + (int) pos.y + "," + (int) pos.z) + (dying >= 0 ? " dying" : "");
	}

	@Override
	public void remove(ServerLevel level) {
		List<UUID> all = new ArrayList<>(body);
		all.add(head);
		for (UUID s : skull) {
			if (s != null) {
				all.add(s);
			}
		}
		for (UUID id : all) {
			Entity e = level.getEntity(id);
			if (e != null) {
				e.discard();
			}
		}
		bar.removeAllPlayers();
	}

	private Vec3 center() {
		return Vec3.atBottomCenterOf(room.center).add(0, 1, 0);
	}

	@Override
	public void tick(ServerLevel level, List<ServerPlayer> targets) {
		t++;
		if (dying >= 0) {
			tickDeath(level);
			return;
		}
		Entity e = level.getEntity(head);
		if (e == null && headRef != null && !headRef.isRemoved()) {
			return;
		}
		if (!(e instanceof Mob h) || !h.isAlive()) {
			dying = 0;
			for (ServerPlayer p : Rift.players(level.getServer())) {
				dev.rubymod.game.Alerts.title(p, "THE WYRM FALLS", "The storm breaks", ChatFormatting.AQUA);
				RiftFx.shake(p, 1.4F, 40);
			}
			return;
		}
		float hp = h.getHealth() / h.getMaxHealth();
		if (!enraged && hp < 0.5F) {
			enraged = true;
			level.playSound(null, h.blockPosition(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 3.0F, 1.2F);
			for (ServerPlayer p : targets) {
				dev.rubymod.game.Alerts.title(p, "THE STORM RAGES", "Faster, and twice the lightning", ChatFormatting.AQUA);
			}
		}
		bar.setProgress(Math.max(0, hp));
		bar.setName(Component.literal("STORM WYRM" + (move == Move.REST ? "  •  STUNNED: hit it now!" : move == Move.MARK ? "  •  IT'S DIVING!" : "")
				+ RiftMutations.suffix()).withStyle(ChatFormatting.BOLD));
		for (ServerPlayer p : targets) {
			bar.addPlayer(p);
		}
		fly(level, h, targets);
		h.teleportTo(pos.x, pos.y - 1.0, pos.z);
		h.setDeltaMovement(Vec3.ZERO);
		trail.addFirst(pos);
		while (trail.size() > 200) {
			trail.removeLast();
		}
		pose(level);
		storm(level, h, targets);
	}

	/** The path: circling high, marking a target, the dive, lying stunned, climbing back up. */
	private void fly(ServerLevel level, Mob h, List<ServerPlayer> targets) {
		moveT++;
		double speed = enraged ? 0.85 : 0.65;
		double r = room.half - 9;
		switch (move) {
			case CIRCLE -> {
				angle += speed / r;
				Vec3 want = center().add(Math.cos(angle) * r, 12 + Math.sin(t * 0.05) * 2, Math.sin(angle) * r);
				pos = pos.add(want.subtract(pos).scale(0.25));
				if (moveT > (enraged ? 90 : 130) && !targets.isEmpty()) {
					marked = targets.get(level.getRandom().nextInt(targets.size()));
					move = Move.MARK;
					moveT = 0;
					level.playSound(null, h.blockPosition(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 3.0F, 0.8F);
					marked.sendOverlayMessage(Component.literal("THE WYRM IS COMING FOR YOU: MOVE!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
				}
			}
			case MARK -> {
				// It hangs in the air, eyes on its target; a red circle follows them.
				pos = pos.add(0, Math.sin(moveT * 0.3) * 0.05, 0);
				if (marked != null && marked.isAlive()) {
					diveTo = new Vec3(marked.getX(), Math.floor(marked.getY()) + 0.5, marked.getZ());
				}
				if (diveTo != null) {
					RiftFx.ring(level, diveTo.add(0, -0.3, 0), 3.5, RiftFx.dust(0xFF2020, 1.5F), 24);
					RiftFx.ring(level, diveTo.add(0, -0.3, 0), 3.5 * (1 - moveT / 30.0), RiftFx.dust(0xFFFFFF, 1.0F), 12);
				}
				if (moveT >= 30 && diveTo != null) {
					move = Move.DIVE;
					moveT = 0;
					diveFrom = pos;
				}
			}
			case DIVE -> {
				Vec3 d = diveTo.subtract(pos);
				double step = enraged ? 1.6 : 1.3;
				pos = d.length() <= step ? diveTo : pos.add(d.normalize().scale(step));
				for (ServerPlayer p : targets) {
					if (p.position().add(0, 1, 0).distanceTo(pos) < 2.6) {
						p.hurtServer(level, level.damageSources().mobAttack(h), 10);
						p.setDeltaMovement(d.normalize().scale(1.2).add(0, 0.6, 0));
						p.needsSync = true;
					}
				}
				if (pos.equals(diveTo)) {
					impact(level, h, targets);
					move = Move.REST;
					moveT = 0;
				}
			}
			case REST -> {
				// Stunned on the ground: crackling, open to everything.
				if (moveT % 3 == 0) {
					level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y + 0.5, pos.z, 8, 1.2, 0.6, 1.2, 0.2);
				}
				if (moveT >= (enraged ? 50 : 70)) {
					move = Move.RISE;
					moveT = 0;
					level.playSound(null, BlockPos.containing(pos), SoundEvents.PHANTOM_FLAP, SoundSource.HOSTILE, 3.0F, 0.5F);
				}
			}
			case RISE -> {
				Vec3 up = new Vec3(pos.x, center().y + 12, pos.z);
				pos = pos.add(up.subtract(pos).normalize().scale(0.8));
				if (pos.y >= center().y + 11) {
					angle = Math.atan2(pos.z - center().z, pos.x - center().x);
					move = Move.CIRCLE;
					moveT = 0;
				}
			}
		}
	}

	private void impact(ServerLevel level, Mob h, List<ServerPlayer> targets) {
		var bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
		if (bolt != null) {
			bolt.snapTo(pos.x, pos.y, pos.z, 0, 0);
			bolt.setVisualOnly(true);
			level.addFreshEntity(bolt);
		}
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
		BlockState under = level.getBlockState(BlockPos.containing(pos).below());
		for (int ring = 1; ring <= 4; ring++) {
			RiftFx.ring(level, pos.add(0, -0.3, 0), ring * 1.6, new BlockParticleOption(ParticleTypes.BLOCK, under.isAir() ? Blocks.STONE.defaultBlockState() : under),
					14 * ring);
		}
		RiftFx.ring(level, pos.add(0, -0.2, 0), 6, RiftFx.dust(0x66E0FF, 1.8F), 40);
		level.playSound(null, BlockPos.containing(pos), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 2.0F, 0.8F);
		level.playSound(null, BlockPos.containing(pos), SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.HOSTILE, 2.5F, 0.5F);
		for (ServerPlayer p : targets) {
			double d = p.position().distanceTo(pos);
			RiftFx.shake(p, (float) Math.max(0.3, 1.8 - d * 0.08), 14);
			if (d < 5.5) {
				p.hurtServer(level, level.damageSources().mobAttack(h), enraged ? 12 : 9);
				Vec3 push = p.position().subtract(pos).multiply(1, 0, 1).normalize().scale(1.3);
				p.setDeltaMovement(push.x, 0.8, push.z);
				p.needsSync = true;
			}
		}
		for (ServerPlayer p : targets) {
			p.sendOverlayMessage(Component.literal("The Wyrm is stunned: hit it now!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		}
	}

	/** Lightning on red circles, now and then (twice as many once it's enraged). */
	private void storm(ServerLevel level, Mob h, List<ServerPlayer> targets) {
		if (move == Move.CIRCLE && --stormAt <= 0 && !targets.isEmpty()) {
			stormAt = enraged ? 45 : 70;
			strikes.clear();
			for (ServerPlayer p : targets) {
				for (int k = 0; k < (enraged ? 2 : 1); k++) {
					strikes.add(new Vec3(p.getX() + level.getRandom().nextInt(5) - 2, Math.floor(p.getY()) + 0.1, p.getZ() + level.getRandom().nextInt(5) - 2));
				}
			}
			strikesIn = 28;
		}
		if (strikesIn > 0) {
			strikesIn--;
			for (Vec3 s : strikes) {
				RiftFx.ring(level, s, 2.2, RiftFx.dust(0x66E0FF, 1.3F), 14);
				RiftFx.ring(level, s, 2.2 * strikesIn / 28.0, RiftFx.dust(0xFFFFFF, 0.8F), 8);
			}
			if (strikesIn == 0) {
				for (Vec3 s : strikes) {
					var bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
					if (bolt != null) {
						bolt.snapTo(s.x, s.y, s.z, 0, 0);
						bolt.setVisualOnly(true);
						level.addFreshEntity(bolt);
					}
					level.sendParticles(ParticleTypes.ELECTRIC_SPARK, s.x, s.y + 0.5, s.z, 30, 0.8, 0.3, 0.8, 0.3);
					for (ServerPlayer p : targets) {
						if (p.position().distanceTo(s) < 2.4) {
							p.hurtServer(level, level.damageSources().mobAttack(h), 7);
						}
					}
				}
				strikes.clear();
			}
		}
	}

	/** Head and body along the trail. */
	private void pose(ServerLevel level) {
		Vec3 ahead = trail.size() > 2 ? pos.subtract(trail.stream().skip(2).findFirst().orElse(pos)) : new Vec3(0, 0, 1);
		if (ahead.lengthSqr() < 1.0E-4) {
			ahead = new Vec3(Math.cos(angle + Math.PI / 2), 0, Math.sin(angle + Math.PI / 2));
		}
		Quaternionf facing = look(ahead);
		// The skull: snout and eyes.
		float[][] parts = {{-0.9F, -0.7F, -0.9F, 1.8F, 1.4F, 2.6F}, {-0.75F, 0.15F, 1.2F, 0.4F, 0.35F, 0.4F}, {0.35F, 0.15F, 1.2F, 0.4F, 0.35F, 0.4F}};
		for (int i = 0; i < 3; i++) {
			if (skull[i] != null && level.getEntity(skull[i]) instanceof Display.BlockDisplay d) {
				d.teleportTo(pos.x, pos.y, pos.z);
				((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(), facing, new Vector3f(1, 1, 1),
						new Quaternionf()).compose(new Transformation(new Vector3f(parts[i][0], parts[i][1], parts[i][2]), new Quaternionf(),
								new Vector3f(parts[i][3], parts[i][4], parts[i][5]), new Quaternionf())));
			}
		}
		// Segments evenly spaced along the path behind the head, however fast it went.
		List<Vec3> path = new ArrayList<>(trail);
		List<Vec3> spots = new ArrayList<>();
		double want = SPACING * 1.4;
		double walked = 0;
		for (int k = 1; k < path.size() && spots.size() < body.size(); k++) {
			Vec3 a = path.get(k - 1);
			Vec3 b = path.get(k);
			double len = a.distanceTo(b);
			while (len > 0 && walked + len >= want && spots.size() < body.size()) {
				double f = (want - walked) / len;
				spots.add(a.lerp(b, f));
				want += SPACING;
			}
			walked += len;
		}
		while (spots.size() < body.size()) {
			spots.add(spots.isEmpty() ? pos : spots.get(spots.size() - 1));
		}
		for (int i = 0; i < body.size(); i++) {
			Vec3 at = spots.get(i);
			Vec3 prev = i == 0 ? pos : spots.get(i - 1);
			float size = 2.0F - i * 0.055F;
			if (level.getEntity(body.get(i)) instanceof Display.BlockDisplay d) {
				d.teleportTo(at.x, at.y, at.z);
				Vec3 dir = prev.subtract(at);
				((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(), look(dir.lengthSqr() < 1.0E-4 ? ahead : dir),
						new Vector3f(1, 1, 1), new Quaternionf()).compose(new Transformation(new Vector3f(-size / 2, -size / 2, -size / 2), new Quaternionf(),
								new Vector3f(size, size, size * 1.3F), new Quaternionf())));
			}
		}
		if (t % 4 == 0) {
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, true, false, pos.x, pos.y, pos.z, 2, 0.6, 0.4, 0.6, 0.05);
		}
	}

	private static Quaternionf look(Vec3 dir) {
		Vec3 d = dir.normalize();
		float yaw = (float) Math.atan2(d.x, d.z);
		float pitch = (float) -Math.asin(Math.max(-1, Math.min(1, d.y)));
		return new Quaternionf().rotateY(yaw).rotateX(pitch);
	}

	private void tickDeath(ServerLevel level) {
		dying++;
		// The body comes apart from the tail up.
		int fall = body.size() - 1 - dying / 3;
		if (dying % 3 == 0 && fall >= 0 && level.getEntity(body.get(fall)) instanceof Entity seg) {
			level.sendParticles(ParticleTypes.EXPLOSION, seg.getX(), seg.getY(), seg.getZ(), 1, 0, 0, 0, 0);
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, seg.getX(), seg.getY(), seg.getZ(), 20, 0.6, 0.6, 0.6, 0.3);
			level.playSound(null, seg.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.0F, 1.2F);
			seg.discard();
		}
		if (dying >= body.size() * 3 + 15) {
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 2, 1, 1, 1, 0);
			level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, pos.x, pos.y, pos.z, 150, 2, 2, 2, 0.6);
			var bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
			if (bolt != null) {
				bolt.snapTo(pos.x, pos.y, pos.z, 0, 0);
				bolt.setVisualOnly(true);
				level.addFreshEntity(bolt);
			}
			remove(level);
			done = true;
		}
	}
}
