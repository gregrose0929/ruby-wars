package dev.rubymod.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * Practice for your base's defenses before Battle Day: a Practice Dummy put up inside your base stands for a minute,
 * your Sentry Turrets and Tesla Coils fire on it, and you're told what hit it. It can't be hurt, turrets don't use
 * ammo on it, and nothing is won or lost. Not saved: a dummy left over after a restart is taken down.
 */
public final class Practice {
	public static final String TAG = "rubywars_practice_dummy";
	private static final long LASTS = 60_000L;

	private static final class Session {
		final UUID owner;
		final String claimOwner;
		final long ends;
		final Map<String, Integer> hits = new LinkedHashMap<>();

		Session(UUID owner, String claimOwner, long ends) {
			this.owner = owner;
			this.claimOwner = claimOwner;
			this.ends = ends;
		}

		int total() {
			return hits.values().stream().mapToInt(Integer::intValue).sum();
		}
	}

	/** Dummy entity id -> its practice. */
	private static final Map<UUID, Session> SESSIONS = new HashMap<>();
	/** For tests: added to the clock. */
	private static long skew;

	private Practice() {
	}

	private static long now() {
		return System.currentTimeMillis() + skew;
	}

	/** Put a dummy up at {@code at} (the block above the one clicked). Returns why not, or null. */
	public static String place(ServerPlayer player, ServerLevel level, BlockPos at) {
		if (Rules.phase().isBattle()) {
			// Turrets shoot whatever's nearest: a dummy now would draw their fire away from real attackers.
			return "Practice is for before Battle Day: your defenses are needed for real now.";
		}
		SeasonData.Claim claim = SeasonData.get().claimAt(Rules.dim(level), at);
		if (claim == null || claim.destroyed || !claim.owner.equals(player.getUUID().toString())) {
			return "Put the Practice Dummy up inside your own base: your defenses only fire on things there.";
		}
		if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty() || !level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty()) {
			return "There's no room for it there.";
		}
		// One at a time.
		for (Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			if (e.getValue().owner.equals(player.getUUID())) {
				Entity old = level.getEntity(e.getKey());
				if (old != null) {
					old.discard();
				}
				it.remove();
			}
		}
		ArmorStand dummy = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
		if (dummy == null) {
			return "The dummy wouldn't stand up.";
		}
		dummy.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
		dummy.setYRot(player.getYRot() + 180.0F);
		dummy.setPermanentlyInvulnerable(true);
		dummy.setShowArms(true);
		dummy.addTag(TAG);
		dummy.setCustomNameVisible(true);
		dummy.setCustomName(Component.literal("Practice Dummy").withStyle(ChatFormatting.GOLD));
		// Known before it's added (a dummy nobody knows about is taken down as it loads).
		SESSIONS.put(dummy.getUUID(), new Session(player.getUUID(), claim.owner, now() + LASTS));
		if (!level.addFreshEntity(dummy)) {
			SESSIONS.remove(dummy.getUUID());
			return "The dummy wouldn't stand up.";
		}
		level.playSound(null, at, SoundEvents.ARMOR_STAND_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
		player.sendSystemMessage(Component.literal("Practice Dummy up for a minute: your Sentry Turrets and Tesla Coils will fire on it. "
				+ "Nothing is used up or lost.").withStyle(ChatFormatting.GOLD));
		return null;
	}

	/** Is this a practice dummy in that base (so its defenses may fire on it)? */
	public static boolean isDummyFor(SeasonData.Claim claim, Entity entity) {
		if (claim == null || !(entity instanceof ArmorStand) || !entity.entityTags().contains(TAG)) {
			return false;
		}
		Session s = SESSIONS.get(entity.getUUID());
		return s != null && s.claimOwner.equals(claim.owner) && now() < s.ends;
	}

	public static boolean isDummy(Entity entity) {
		return entity instanceof ArmorStand && SESSIONS.containsKey(entity.getUUID());
	}

	/** Is a practice on in this base (turrets wake up for it before Battle Day)? */
	public static boolean active(SeasonData.Claim claim) {
		if (claim == null || SESSIONS.isEmpty()) {
			return false;
		}
		for (Session s : SESSIONS.values()) {
			if (s.claimOwner.equals(claim.owner)) {
				return true;
			}
		}
		return false;
	}

	/** A defense hit the dummy. */
	public static void hit(Entity dummy, String by) {
		Session s = SESSIONS.get(dummy.getUUID());
		if (s == null || !(dummy.level() instanceof ServerLevel level)) {
			return;
		}
		s.hits.merge(by, 1, Integer::sum);
		Goals.add(level.getServer(), java.util.UUID.fromString(s.claimOwner), "dummy", 1);
		int n = s.total();
		dummy.setCustomName(Component.literal("Practice Dummy: " + n + (n == 1 ? " hit" : " hits")).withStyle(ChatFormatting.GOLD));
		level.sendParticles(ParticleTypes.CRIT, dummy.getX(), dummy.getY() + 1.2, dummy.getZ(), 6, 0.2, 0.3, 0.2, 0.2);
	}

	/** Once a second: take down dummies whose minute is up, and say how it went. */
	public static void tick(MinecraftServer server) {
		if (!SESSIONS.isEmpty() && server.getTickCount() % 20 == 0) {
			tickSecond(server);
		}
	}

	public static void tickSecond(MinecraftServer server) {
		long t = now();
		// Battle Day started: any still up come down (the defenses have real work to do).
		boolean battle = Rules.phase().isBattle();
		for (Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			Session s = e.getValue();
			ServerPlayer owner = server.getPlayerList().getPlayer(s.owner);
			if (t < s.ends && owner != null && !battle) {
				continue;
			}
			it.remove();
			for (ServerLevel level : server.getAllLevels()) {
				Entity dummy = level.getEntity(e.getKey());
				if (dummy != null) {
					level.sendParticles(ParticleTypes.POOF, dummy.getX(), dummy.getY() + 1, dummy.getZ(), 12, 0.3, 0.5, 0.3, 0.02);
					dummy.discard();
				}
			}
			if (owner != null) {
				owner.sendSystemMessage(Component.literal(report(s)).withStyle(s.total() > 0 ? ChatFormatting.GREEN : ChatFormatting.GOLD));
				owner.level().playSound(null, owner.blockPosition(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.8F, 1.2F);
			}
		}
	}

	/** "Practice over: your defenses hit the dummy 34 times (Sentry Turret 30, Tesla Coil 4)." */
	static String report(Session s) {
		if (s.total() == 0) {
			return "Practice over: nothing hit the dummy. Sentry Turrets need Heavy Ammo (right-click them), Tesla Coils need power, "
					+ "and both only fire at what they can see.";
		}
		List<String> parts = new ArrayList<>();
		for (var e : s.hits.entrySet()) {
			parts.add(e.getKey() + " " + e.getValue());
		}
		return "Practice over: your defenses hit the dummy " + s.total() + (s.total() == 1 ? " time" : " times") + " (" + String.join(", ", parts) + ").";
	}

	/** A dummy loaded from before a restart (its practice is gone): take it down. */
	public static boolean leftOver(Entity entity) {
		return entity instanceof ArmorStand && entity.entityTags().contains(TAG) && !SESSIONS.containsKey(entity.getUUID());
	}

	// ----- For tests -----

	public static int hitsOn(Entity dummy) {
		Session s = SESSIONS.get(dummy.getUUID());
		return s == null ? -1 : s.total();
	}

	public static void advance(long ms) {
		skew += ms;
	}

	public static void reset() {
		SESSIONS.clear();
		skew = 0;
	}
}
