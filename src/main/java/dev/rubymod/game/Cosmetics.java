package dev.rubymod.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.math.Transformation;

import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.mixin.ItemDisplayAccessor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Cosmetics, earned by playing (they never change how anything plays): a hat that
 * floats above your head, a trail of particles when you move, and an effect when you
 * get a kill. Unlocked by stats (win a party game, reach a Horde wave...), picked on the
 * profile screen (P). Hidden while you're hiding in Hide & Seek, invisible, or watching.
 */
public final class Cosmetics {
	public enum Kind { HAT, TRAIL, KILL }

	public record Cosmetic(String id, Kind kind, String name, String icon, String how, Predicate<Stats.Record> earned) {
	}

	public static final List<Cosmetic> ALL = List.of(
			new Cosmetic("hat_ruby", Kind.HAT, "Floating Ruby", "rubymod:ruby", "Win any party game", r -> r.totalWins() >= 1),
			new Cosmetic("hat_cake", Kind.HAT, "Cake Hat", "minecraft:cake", "Play 10 party games", r -> r.totalPlayed() >= 10),
			new Cosmetic("hat_pumpkin", Kind.HAT, "Pumpkin Head", "minecraft:carved_pumpkin", "Win Holo Hide & Seek", r -> r.wins("hideseek") >= 1),
			new Cosmetic("hat_tank", Kind.HAT, "Mini Tank", "rubymod:tank", "Win Tank Battle", r -> r.wins("tanks") >= 1),
			new Cosmetic("hat_diamond", Kind.HAT, "Diamond", "minecraft:diamond", "Win 10 party games", r -> r.totalWins() >= 10),
			new Cosmetic("hat_heart", Kind.HAT, "Ruby Heart", "rubymod:ruby_heart", "Reach wave 10 in Co-op Horde", r -> r.hordeBestWave >= 10),
			new Cosmetic("hat_crown", Kind.HAT, "Golden Crown", "minecraft:golden_helmet", "Win a Ruby Wars season", r -> r.seasonsWon >= 1),
			new Cosmetic("trail_ruby", Kind.TRAIL, "Ruby Sparkles", "rubymod:ruby", "Everyone starts with this one", r -> true),
			new Cosmetic("trail_hearts", Kind.TRAIL, "Hearts", "minecraft:red_dye", "Revive a teammate in Horde", r -> r.hordeRevives >= 1),
			new Cosmetic("trail_snow", Kind.TRAIL, "Snowflakes", "minecraft:snowball", "Reach wave 5 in Co-op Horde", r -> r.hordeBestWave >= 5),
			new Cosmetic("trail_clouds", Kind.TRAIL, "Clouds", "rubymod:jetpack", "Finish a Sky Race", r -> r.skyBest > 0),
			new Cosmetic("trail_notes", Kind.TRAIL, "Music Notes", "minecraft:note_block", "Win Build Battle", r -> r.wins("build") >= 1),
			new Cosmetic("trail_flames", Kind.TRAIL, "Flames", "minecraft:blaze_powder", "Get 25 kills in party games", r -> r.partyKills >= 25),
			new Cosmetic("trail_magic", Kind.TRAIL, "Magic", "minecraft:amethyst_shard", "Win 3 duels", r -> r.duelsWon >= 3),
			new Cosmetic("kill_confetti", Kind.KILL, "Confetti", "minecraft:firework_rocket", "Everyone starts with this one", r -> true),
			new Cosmetic("kill_hearts", Kind.KILL, "Heart Burst", "minecraft:pink_dye", "Get 50 kills in party games", r -> r.partyKills >= 50),
			new Cosmetic("kill_lightning", Kind.KILL, "Lightning", "minecraft:lightning_rod", "Win Gun Game", r -> r.wins("gungame") >= 1),
			new Cosmetic("kill_ruby", Kind.KILL, "Ruby Explosion", "rubymod:ruby_block", "Win Ruby Rush", r -> r.wins("rush") >= 1));

	/** Each player's hat display entity, and the world it's in. */
	private record Hat(UUID entity, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> level) {
	}

	private static final Map<UUID, Hat> HATS = new HashMap<>();
	private static final String HAT_TAG = "rubymod_hat";

	private Cosmetics() {
	}

	public static Cosmetic byId(String id) {
		for (Cosmetic c : ALL) {
			if (c.id().equals(id)) {
				return c;
			}
		}
		return null;
	}

	public static void reset() {
		HATS.clear();
	}

	/** Unlock anything newly earned, and say so. */
	public static void checkUnlocks(ServerPlayer player) {
		Stats.Record r = Stats.get(player);
		for (Cosmetic c : ALL) {
			if (!r.unlocked.contains(c.id()) && c.earned().test(r)) {
				r.unlocked.add(c.id());
				Stats.markDirty();
				boolean free = c.how().startsWith("Everyone");
				if (free && c.kind() == Kind.TRAIL && r.trail.isEmpty()) {
					r.trail = c.id();
				} else if (free && c.kind() == Kind.KILL && r.killEffect.isEmpty()) {
					r.killEffect = c.id();
				}
				if (!free) {
					player.sendSystemMessage(Component.literal("✨ New " + kindName(c.kind()) + " unlocked: " + c.name() + "! Press P to wear it.")
							.withStyle(ChatFormatting.LIGHT_PURPLE));
					player.level().playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.5F, 1.4F);
				}
			}
		}
	}

	public static String kindName(Kind kind) {
		return switch (kind) {
			case HAT -> "hat";
			case TRAIL -> "trail";
			case KILL -> "kill effect";
		};
	}

	/** Wear (or take off, with "none:HAT" etc.) a cosmetic. Returns an error or null. */
	public static String select(ServerPlayer player, String id) {
		Stats.Record r = Stats.get(player);
		if (id.startsWith("none:")) {
			switch (Kind.valueOf(id.substring(5))) {
				case HAT -> r.hat = "";
				case TRAIL -> r.trail = "";
				case KILL -> r.killEffect = "";
			}
			Stats.markDirty();
			return null;
		}
		Cosmetic c = byId(id);
		if (c == null || !r.unlocked.contains(id)) {
			return "You haven't unlocked that yet.";
		}
		switch (c.kind()) {
			case HAT -> r.hat = id;
			case TRAIL -> r.trail = id;
			case KILL -> r.killEffect = id;
		}
		Stats.markDirty();
		return null;
	}

	/** For the profile screen: "id|kind|name|icon|how|unlocked|wearing" per cosmetic. */
	public static List<String> lines(ServerPlayer player) {
		Stats.Record r = Stats.get(player);
		List<String> lines = new ArrayList<>();
		for (Cosmetic c : ALL) {
			boolean wearing = c.id().equals(r.hat) || c.id().equals(r.trail) || c.id().equals(r.killEffect);
			lines.add(c.id() + "|" + c.kind() + "|" + c.name() + "|" + c.icon() + "|" + c.how() + "|" + r.unlocked.contains(c.id()) + "|" + wearing);
		}
		return lines;
	}

	/** Cosmetics are hidden while hiding, invisible or watching. */
	private static boolean hidden(ServerPlayer player) {
		return player.isSpectator() || player.hasEffect(MobEffects.INVISIBILITY) || player.isDeadOrDying();
	}

	// ----- Hats and trails -----

	public static void tick(MinecraftServer server) {
		int t = server.getTickCount();
		if (t % 3 == 0) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				Stats.Record r = Stats.get(p);
				if (r.trail.isEmpty() || hidden(p)) {
					continue;
				}
				Vec3 move = p.getDeltaMovement();
				if (move.horizontalDistanceSqr() < 0.002 && Math.abs(move.y) < 0.05) {
					continue;
				}
				trail((ServerLevel) p.level(), r.trail, p.position().add(0, 0.15, 0));
			}
		}
		if (t % 20 == 0) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				keepHat(p);
			}
			for (var it = HATS.entrySet().iterator(); it.hasNext();) {
				var e = it.next();
				if (server.getPlayerList().getPlayer(e.getKey()) == null) {
					ServerLevel where = server.getLevel(e.getValue().level());
					Entity old = where == null ? null : where.getEntity(e.getValue().entity());
					if (old != null) {
						old.discard();
					}
					it.remove();
				}
			}
		}
		// Hats follow their wearer's head (smoothed over 2 ticks on the client).
		for (var e : HATS.entrySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null) {
				continue;
			}
			Entity hat = ((ServerLevel) p.level()).getEntity(e.getValue().entity());
			if (hat != null) {
				hat.teleportTo(p.getX(), p.getY() + p.getBbHeight() + 0.3, p.getZ());
			}
		}
	}

	private static void trail(ServerLevel level, String id, Vec3 at) {
		ParticleOptions particle = switch (id) {
			case "trail_hearts" -> ParticleTypes.HEART;
			case "trail_snow" -> ParticleTypes.SNOWFLAKE;
			case "trail_clouds" -> ParticleTypes.CLOUD;
			case "trail_notes" -> ParticleTypes.NOTE;
			case "trail_flames" -> ParticleTypes.FLAME;
			case "trail_magic" -> ParticleTypes.WITCH;
			default -> new DustParticleOptions(0xE0202A, 1.0F);
		};
		level.sendParticles(particle, at.x, at.y, at.z, 1, 0.15, 0.05, 0.15, 0.0);
	}

	/** Keep the player's hat (a floating item riding them) in place, or take it away. */
	private static void keepHat(ServerPlayer player) {
		Stats.Record r = Stats.get(player);
		Hat existing = HATS.get(player.getUUID());
		Entity display = null;
		if (existing != null) {
			ServerLevel where = player.level().getServer().getLevel(existing.level());
			display = where == null ? null : where.getEntity(existing.entity());
			if (display != null && display.level() != player.level()) {
				display.discard(); // left behind in another world
				display = null;
			}
		}
		boolean want = !r.hat.isEmpty() && !hidden(player);
		if (!want) {
			if (display != null) {
				display.discard();
			}
			HATS.remove(player.getUUID());
			return;
		}
		Cosmetic c = byId(r.hat);
		if (c == null) {
			return;
		}
		ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(c.icon())));
		if (display instanceof Display.ItemDisplay item && item.isAlive()) {
			((ItemDisplayAccessor) item).rubymod$setItemStack(stack);
			return;
		}
		if (display != null) {
			display.discard();
		}
		Display.ItemDisplay hat = EntityTypes.ITEM_DISPLAY.create(player.level(), EntitySpawnReason.EVENT);
		if (hat == null) {
			return;
		}
		hat.snapTo(player.getX(), player.getY() + player.getBbHeight() + 0.3, player.getZ(), 0, 0);
		((DisplayAccessor) hat).rubymod$setPosRotInterpolationDuration(2);
		((ItemDisplayAccessor) hat).rubymod$setItemStack(stack);
		((DisplayAccessor) hat).rubymod$setBillboard(Display.BillboardConstraints.VERTICAL);
		((DisplayAccessor) hat).rubymod$setTransformation(new Transformation(new Vector3f(0, 0, 0), new Quaternionf(),
				new Vector3f(0.55F, 0.55F, 0.55F), new Quaternionf()));
		hat.addTag(HAT_TAG);
		// Register it before it's added, or the stray-hat cleanup would take it straight away.
		HATS.put(player.getUUID(), new Hat(hat.getUUID(), player.level().dimension()));
		player.level().addFreshEntity(hat);
		HATS.put(player.getUUID(), new Hat(hat.getUUID(), player.level().dimension()));
	}

	/** Old hats left behind (a restart, a crash): clean them up as they load. */
	public static boolean strayHat(Entity e) {
		if (!e.entityTags().contains(HAT_TAG)) {
			return false;
		}
		for (Hat hat : HATS.values()) {
			if (hat.entity().equals(e.getUUID())) {
				return false;
			}
		}
		return true;
	}

	// ----- Kill effects -----

	public static void onKill(ServerPlayer killer, Vec3 at) {
		Stats.Record r = Stats.get(killer);
		String id = r.killEffect;
		if (id.isEmpty()) {
			return;
		}
		ServerLevel level = (ServerLevel) killer.level();
		switch (id) {
			case "kill_hearts" -> level.sendParticles(ParticleTypes.HEART, at.x, at.y + 1, at.z, 12, 0.5, 0.5, 0.5, 0.1);
			case "kill_lightning" -> {
				LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
				if (bolt != null) {
					bolt.snapTo(at.x, at.y, at.z, 0, 0);
					bolt.setVisualOnly(true);
					level.addFreshEntity(bolt);
				}
			}
			case "kill_ruby" -> {
				level.sendParticles(new DustParticleOptions(0xE0202A, 2.0F), at.x, at.y + 1, at.z, 40, 0.8, 0.8, 0.8, 0.2);
				level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0);
				level.playSound(null, BlockPos.containing(at), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.5F, 0.8F);
			}
			default -> level.sendParticles(ParticleTypes.FIREWORK, at.x, at.y + 1, at.z, 20, 0.3, 0.5, 0.3, 0.15);
		}
	}
}
