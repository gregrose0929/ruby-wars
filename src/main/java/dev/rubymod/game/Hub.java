package dev.rubymod.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.math.Transformation;

import dev.rubymod.RubyMod;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.mixin.TextDisplayAccessor;
import dev.rubymod.registry.ModBlocks;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.AABB;

/**
 * The hub: a floating plaza where everyone arrives when they join the server. In front of
 * you, the four main games side by side: Ruby Wars, the Ruby Rift, the Ruby Convoy and
 * Co-op Horde. Behind you, the Party Games portal. Press H anywhere to switch games, or use /hub and /play.
 *
 * Leaving Ruby Wars saves where you were and everything you carried (to
 * world/rubywars/hub/<uuid>.dat); going back puts it all back. The hub and Horde never
 * touch your Ruby Wars things. During Battle Day, players still in the season are kept
 * in (and pulled back to) Ruby Wars; eliminated players can go wherever they like.
 */
public final class Hub {
	public static final ResourceKey<Level> HUB = ResourceKey.create(Registries.DIMENSION, RubyMod.id("hub"));
	/** The plaza's floor level (players stand on FLOOR + 1). */
	public static final int FLOOR = 100;
	private static final int RADIUS = 24;
	private static final int PORTAL_Z = -14;
	/** Where you arrive, facing the portals. */
	private static final int SPAWN_Z = 8;
	private static final int RUBY_X = -14;
	private static final int RIFT_X = -5;
	private static final int CONVOY_X = 5;
	private static final int HORDE_X = 14;
	/** The Party Games arch stands at the back of the plaza, behind where you arrive. */
	private static final int PARTY_Z = 15;
	private static final String LABEL_TAG = "rubymod_hub_label";

	public enum Where { RUBY_WARS, HUB, HORDE, DUEL, PARTY, RIFT, CONVOY }

	private static boolean built;
	private static final Map<UUID, Integer> PORTAL_COOLDOWN = new HashMap<>();
	/** Players who just joined, and how many ticks until they're sorted out. */
	private static final Map<UUID, Integer> JOINING = new HashMap<>();

	private Hub() {
	}

	/**
	 * The lowest height at or above {@code y} where a player fits (feet and head both free),
	 * so nobody is ever put inside a block. Searches up to 64 blocks, then gives up.
	 */
	public static double safeY(ServerLevel level, double x, double y, double z) {
		BlockPos start = BlockPos.containing(x, y, z);
		level.getChunk(start.getX() >> 4, start.getZ() >> 4);
		for (int i = 0; i < 64; i++) {
			BlockPos feet = start.above(i);
			if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
					&& level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
				return i == 0 ? y : feet.getY();
			}
		}
		return y;
	}

	/** Players are sent to the hub when they join (dedicated servers only; singleplayer worlds start where you left off). */
	public static boolean routesJoins(MinecraftServer server) {
		return server.isDedicatedServer() && GameConfig.get().hub;
	}

	public static void reset() {
		built = false;
		PORTAL_COOLDOWN.clear();
		JOINING.clear();
	}

	/** Called on join: a few ticks later, duels give back what they owe and the hub takes the player in. */
	public static void queueJoin(ServerPlayer player) {
		JOINING.put(player.getUUID(), 5);
	}

	private static void tickJoins(MinecraftServer server) {
		if (JOINING.isEmpty()) {
			return;
		}
		for (UUID id : new ArrayList<>(JOINING.keySet())) {
			int left = JOINING.get(id) - 1;
			if (left > 0) {
				JOINING.put(id, left);
				continue;
			}
			JOINING.remove(id);
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null) {
				Duels.onJoin(player);
				onJoin(player);
			}
		}
	}

	// ----- Where is everyone? -----

	public static Where where(ServerPlayer player) {
		if (Duels.inDuel(player)) {
			return Where.DUEL;
		}
		if (Horde.playing(player)) {
			return Where.HORDE;
		}
		if (dev.rubymod.game.rift.Rift.playing(player)) {
			return Where.RIFT;
		}
		if (dev.rubymod.game.convoy.Convoy.playing(player)) {
			return Where.CONVOY;
		}
		if (dev.rubymod.game.party.PartyGames.of(player) != null) {
			return Where.PARTY;
		}
		return inRubyWarsWorld(player) ? Where.RUBY_WARS : Where.HUB;
	}

	/** In the season's worlds (overworld, Nether, End), not the hub or the arena. */
	public static boolean inRubyWarsWorld(Player player) {
		ResourceKey<Level> dim = player.level().dimension();
		return dim != HUB && dim != Duels.ARENA && dim != dev.rubymod.game.party.RubyRush.RUSH;
	}

	/** Season players can't leave Ruby Wars on Battle Day (unless they're out). */
	private static boolean mustStay(ServerPlayer player) {
		SeasonData data = SeasonData.get();
		if (!data.phase.isBattle()) {
			return false;
		}
		SeasonData.PlayerRecord record = data.player(player.getUUID());
		return record != null && !record.eliminated && !player.isCreative();
	}

	// ----- Switching games -----

	/** Go to "rubywars", "horde" (or "horde:easy|normal|hard") or "hub". Returns an error to show, or null when it worked. */
	public static String go(ServerPlayer player, String target) {
		dev.rubymod.game.party.PartyGame game = null;
		if (target.startsWith("game:")) {
			game = dev.rubymod.game.party.PartyGames.byId(target.substring(5));
			if (game == null) {
				return "Unknown game.";
			}
			target = "party";
		}
		Horde.Challenge challenge = null;
		if (target.startsWith("horde:")) {
			challenge = Horde.Challenge.byName(target.substring(6));
			target = "horde";
		}
		Where from = where(player);
		if (from == Where.HORDE && target.equals("horde") && challenge != null) {
			return Horde.setDifficulty(player, challenge);
		}
		if (from == Where.DUEL) {
			return "Finish your duel first (or /duel leave).";
		}
		Where to = switch (target) {
			case "rubywars" -> Where.RUBY_WARS;
			case "horde" -> Where.HORDE;
			case "rift" -> Where.RIFT;
			case "convoy" -> Where.CONVOY;
			case "hub" -> Where.HUB;
			case "party" -> Where.PARTY;
			default -> null;
		};
		if (to == null) {
			return "Unknown game. Try /play rubywars or /play horde.";
		}
		dev.rubymod.game.party.PartyGame current = dev.rubymod.game.party.PartyGames.of(player);
		if (to == Where.PARTY && current == game) {
			return "You're already in " + game.title + ".";
		}
		if (to == from && to != Where.PARTY) {
			return switch (to) {
				case RUBY_WARS -> "You're already in Ruby Wars.";
				case HORDE -> "You're already in Horde.";
				case RIFT -> "You're already in the Rift.";
				case CONVOY -> "You're already on the convoy.";
				default -> "You're already in the hub.";
			};
		}
		if (from == Where.RUBY_WARS) {
			if (mustStay(player)) {
				return "It's Battle Day! You can't leave Ruby Wars until it's over.";
			}
			if (CombatTag.tagged(player.getUUID())) {
				return "You're in combat. Wait a few seconds.";
			}
		}
		if (to == Where.RIFT) {
			String error = dev.rubymod.game.rift.Rift.canJoin(player);
			if (error != null) {
				return error;
			}
		}
		if (to == Where.CONVOY) {
			String error = dev.rubymod.game.convoy.Convoy.canJoin(player);
			if (error != null) {
				return error;
			}
		}
		if (to == Where.HORDE) {
			String error = Horde.canJoin(player);
			if (error != null) {
				return error;
			}
		}
		if (to == Where.PARTY) {
			String error = game.canJoin(player);
			if (error != null) {
				return error;
			}
		}
		if (to == Where.RUBY_WARS && Rules.phase() == Phase.ENDED && SeasonData.get().player(player.getUUID()) == null) {
			// Nothing to stop you looking around; just so you know.
			player.sendSystemMessage(Component.literal("The season is over: wait for the next one to start.").withStyle(ChatFormatting.GRAY));
		}
		// Leave where you are...
		if (from == Where.RUBY_WARS) {
			if (!saveRubyWars(player)) {
				return "Couldn't save your Ruby Wars things, so you're staying put. Tell an admin.";
			}
		} else if (from == Where.HORDE) {
			Horde.leave(player);
		} else if (from == Where.RIFT) {
			dev.rubymod.game.rift.Rift.leave(player);
		} else if (from == Where.CONVOY) {
			dev.rubymod.game.convoy.Convoy.leave(player);
		} else if (from == Where.PARTY) {
			current.leave(player);
		}
		// ... and arrive.
		switch (to) {
			case RUBY_WARS -> enterRubyWars(player);
			case HORDE -> Horde.join(player, challenge);
			case RIFT -> dev.rubymod.game.rift.Rift.join(player);
			case CONVOY -> dev.rubymod.game.convoy.Convoy.join(player);
			case PARTY -> game.join(player);
			default -> enterHub(player);
		}
		return null;
	}

	/** Into the hub (after you've left wherever you were). */
	public static void enterHub(ServerPlayer player) {
		ServerLevel hub = player.level().getServer().getLevel(HUB);
		if (hub == null) {
			player.sendSystemMessage(Component.literal("The hub dimension is missing.").withStyle(ChatFormatting.RED));
			return;
		}
		ensureBuilt(hub);
		player.stopRiding();
		player.teleportTo(hub, 0.5, FLOOR + 1, SPAWN_Z + 0.5, Set.<Relative>of(), 180.0F, 0.0F, true);
		if (!player.isCreative()) {
			player.setGameMode(GameType.ADVENTURE);
		}
		player.removeAllEffects();
		player.clearFire();
		player.setHealth(player.getMaxHealth());
		player.resetFallDistance();
		Alerts.title(player, "THE HUB", "Walk into a portal to play  \u2022  H switches games", ChatFormatting.AQUA);
	}

	/** Back into Ruby Wars: where you were, with what you had. */
	public static void enterRubyWars(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Path file = saveFile(server, player.getUUID());
		CompoundTag tag = null;
		try {
			tag = Files.exists(file) ? NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()) : null;
		} catch (IOException e) {
			RubyMod.LOGGER.error("Couldn't read {}'s saved Ruby Wars things", player.getName().getString(), e);
		}
		player.stopRiding();
		player.removeAllEffects();
		player.clearFire();
		if (tag == null) {
			// First time in (or nothing saved): the world spawn, the Ruby Crater on the Ruby Wars Map.
			ServerLevel overworld = server.overworld();
			BlockPos spawn = overworld.getRespawnData().pos();
			overworld.getChunk(spawn.getX() >> 4, spawn.getZ() >> 4);
			int top = overworld.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ());
			double y = safeY(overworld, spawn.getX() + 0.5, Math.max(spawn.getY(), top), spawn.getZ() + 0.5);
			player.teleportTo(overworld, spawn.getX() + 0.5, y, spawn.getZ() + 0.5, Set.<Relative>of(), 0, 0, true);
			player.setGameMode(Rules.isEliminated(player.getUUID()) ? GameType.SPECTATOR : GameType.SURVIVAL);
			player.setHealth(player.getMaxHealth());
		} else {
			ValueInput in = TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), tag);
			var inventory = player.getInventory();
			inventory.clearContent();
			for (ItemStackWithSlot item : in.listOrEmpty("Items", ItemStackWithSlot.CODEC)) {
				if (item.isValidInContainer(inventory.getContainerSize())) {
					inventory.setItem(item.slot(), item.stack());
				}
			}
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(in.getStringOr("Dim", "minecraft:overworld"))));
			if (level == null || level.dimension() == HUB || level.dimension() == Duels.ARENA) {
				level = server.overworld();
			}
			// Where you left off (moved up if something's been built there since).
			double x = in.getDoubleOr("X", 0);
			double z = in.getDoubleOr("Z", 0);
			double y = safeY(level, x, in.getDoubleOr("Y", 100), z);
			player.teleportTo(level, x, y, z, Set.<Relative>of(), in.getFloatOr("Yaw", 0), in.getFloatOr("Pitch", 0), true);
			GameType mode = GameType.byName(in.getStringOr("Mode", "survival"), GameType.SURVIVAL);
			if (mode == GameType.ADVENTURE) {
				mode = GameType.SURVIVAL;
			}
			// Knocked out while you were away? Then you watch.
			player.setGameMode(Rules.isEliminated(player.getUUID()) && mode != GameType.CREATIVE ? GameType.SPECTATOR : mode);
			player.setHealth(Math.max(1.0F, in.getFloatOr("Health", player.getMaxHealth())));
			player.experienceLevel = in.getIntOr("XpLevel", 0);
			player.experienceProgress = in.getFloatOr("XpProgress", 0);
			player.totalExperience = in.getIntOr("XpTotal", 0);
			player.setExperienceLevels(player.experienceLevel);
			player.inventoryMenu.broadcastChanges();
			try {
				Files.deleteIfExists(file);
			} catch (IOException e) {
				RubyMod.LOGGER.warn("Couldn't delete {}", file, e);
			}
		}
		player.resetFallDistance();
		SpawnShield.grant(player);
		if (Rules.phase() != Phase.LOBBY) {
			BattleDirector.giveHeartIfNeeded(player);
		}
		Research.sync(player);
		Bounties.sync(player);
		Alerts.title(player, "RUBY WARS", Rules.phase().title(), ChatFormatting.RED);
	}

	// ----- Saving your Ruby Wars things -----

	private static Path saveFile(MinecraftServer server, UUID player) {
		return server.getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("hub").resolve(player + ".dat");
	}

	/** True if this player's Ruby Wars things are saved away (they're out of Ruby Wars). */
	public static boolean hasSave(MinecraftServer server, UUID player) {
		return Files.exists(saveFile(server, player));
	}

	/** Save where you are in Ruby Wars and what you carry, then empty your hands. False (and nothing changed) if it couldn't be saved. */
	private static boolean saveRubyWars(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		player.stopRiding();
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
		var items = out.list("Items", ItemStackWithSlot.CODEC);
		var inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty()) {
				items.add(new ItemStackWithSlot(i, stack.copy()));
			}
		}
		out.putString("Dim", player.level().dimension().identifier().toString());
		out.putDouble("X", player.getX());
		out.putDouble("Y", player.getY());
		out.putDouble("Z", player.getZ());
		out.putFloat("Yaw", player.getYRot());
		out.putFloat("Pitch", player.getXRot());
		out.putString("Mode", player.gameMode.getGameModeForPlayer().getName());
		out.putFloat("Health", player.getHealth());
		out.putInt("XpLevel", player.experienceLevel);
		out.putFloat("XpProgress", player.experienceProgress);
		out.putInt("XpTotal", player.totalExperience);
		Path file = saveFile(server, player.getUUID());
		try {
			Files.createDirectories(file.getParent());
			if (Files.exists(file)) {
				// Never overwrite a save that wasn't given back (keep it beside the new one).
				Files.move(file, file.resolveSibling(player.getUUID() + "-" + System.currentTimeMillis() + ".dat.bak"));
			}
			NbtIo.writeCompressed(out.buildResult(), file);
		} catch (IOException e) {
			RubyMod.LOGGER.error("Couldn't save {}'s Ruby Wars things", player.getName().getString(), e);
			return false;
		}
		inventory.clearContent();
		player.experienceLevel = 0;
		player.experienceProgress = 0;
		player.totalExperience = 0;
		player.setExperienceLevels(0);
		player.inventoryMenu.broadcastChanges();
		return true;
	}

	// ----- Joining and ticking -----

	/** Just after joining (after duels have given back what they owe): send people to the hub, or wherever Battle Day needs them. */
	private static void onJoin(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		boolean gameWorld = player.level().dimension() == Duels.ARENA || player.level().dimension() == dev.rubymod.game.party.RubyRush.RUSH;
		if (gameWorld && !Duels.inDuel(player) && !Horde.playing(player) && dev.rubymod.game.party.PartyGames.of(player) == null
				&& !dev.rubymod.game.convoy.Convoy.playing(player)) {
			// Left in the middle of a Horde game: their Horde gear stays behind.
			player.getInventory().clearContent();
			player.removeAllEffects();
			if (mustStay(player)) {
				enterRubyWars(player);
			} else {
				enterHub(player);
			}
			return;
		}
		if (!routesJoins(server)) {
			return;
		}
		Where where = where(player);
		if (where == Where.RUBY_WARS) {
			if (mustStay(player)) {
				player.sendSystemMessage(Component.literal("It's Battle Day: straight into Ruby Wars. Good luck!").withStyle(ChatFormatting.RED));
				return;
			}
			if (saveRubyWars(player)) {
				enterHub(player);
			}
		} else if (where == Where.HUB) {
			if (mustStay(player)) {
				enterRubyWars(player);
			} else {
				enterHub(player);
			}
		}
	}

	public static void tick(MinecraftServer server) {
		tickJoins(server);
		ServerLevel hub = server.getLevel(HUB);
		if (hub == null || server.getTickCount() % 4 != 0) {
			return;
		}
		ambience(hub);
		PORTAL_COOLDOWN.replaceAll((id, t) -> t - 4);
		PORTAL_COOLDOWN.values().removeIf(t -> t <= 0);
		for (ServerPlayer player : new ArrayList<>(hub.players())) {
			if (player.isSpectator() || PORTAL_COOLDOWN.containsKey(player.getUUID())) {
				continue;
			}
			AABB box = player.getBoundingBox();
			if (box.intersects(new AABB(-2, FLOOR + 1, PARTY_Z + 0.2, 3, FLOOR + 7, PARTY_Z + 0.8))) {
				// The Party Games portal opens the game picker.
				PORTAL_COOLDOWN.put(player.getUUID(), 40);
				player.teleportTo(hub, player.getX(), FLOOR + 1, PARTY_Z - 3.5, Set.<Relative>of(), 0.0F, 0.0F, true);
				dev.rubymod.net.Net.send(player, new dev.rubymod.net.MorePayloads.ModeInfo(whereLabel(player), modeLines(server)));
				dev.rubymod.net.Net.send(player, new dev.rubymod.net.MorePayloads.OpenModes());
				continue;
			}
			String target = box.intersects(portalBox(RUBY_X)) ? "rubywars" : box.intersects(portalBox(RIFT_X)) ? "rift"
					: box.intersects(portalBox(CONVOY_X)) ? "convoy" : box.intersects(portalBox(HORDE_X)) ? "horde" : null;
			if (target == null) {
				continue;
			}
			PORTAL_COOLDOWN.put(player.getUUID(), 60);
			String error = go(player, target);
			if (error != null) {
				player.sendSystemMessage(Component.literal(error).withStyle(ChatFormatting.RED));
				player.teleportTo(hub, player.getX(), FLOOR + 1, PORTAL_Z + 4.5, Set.<Relative>of(), 180.0F, 0.0F, true);
				hub.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.8F, 1.0F);
			} else {
				player.level().playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.6F, 1.2F);
			}
		}
	}

	/** Motes of each game's colour drifting up through its gate, and a sparkle off the fountain. */
	private static void ambience(ServerLevel hub) {
		if (hub.players().isEmpty()) {
			return;
		}
		var random = hub.getRandom();
		int[][] gates = {{RUBY_X, PORTAL_Z, 0xFF3D3D}, {RIFT_X, PORTAL_Z, 0xFF3D9E}, {CONVOY_X, PORTAL_Z, 0xFF9A30}, {HORDE_X, PORTAL_Z, 0x5BE37D},
				{0, PARTY_Z, 0xC27BFF}};
		for (int[] g : gates) {
			for (int k = 0; k < 2; k++) {
				double x = g[0] + 0.5 + (random.nextDouble() - 0.5) * 5;
				double y = FLOOR + 1 + random.nextDouble() * 6;
				hub.sendParticles(new net.minecraft.core.particles.DustParticleOptions(g[2], 1.2F), x, y, g[1] + 0.5, 1, 0.05, 0.3, 0.05, 0);
			}
			hub.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, g[0] + 0.5, FLOOR + 1.2 + random.nextDouble() * 5, g[1] + 0.5, 1, 1.5, 0.3, 0.2,
					0.01);
		}
		hub.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH, 0.5, FLOOR + 1.1, 0.5, 6, 1.4, 0.1, 1.4, 0.1);
		hub.sendParticles(net.minecraft.core.particles.ParticleTypes.WAX_ON, 0.5, FLOOR + 2.8, 0.5, 1, 0.3, 0.3, 0.3, 0.3);
	}

	private static AABB portalBox(int cx) {
		return new AABB(cx - 2, FLOOR + 1, PORTAL_Z + 0.2, cx + 3, FLOOR + 7, PORTAL_Z + 0.8);
	}

	/** For the game picker: where you are ("PARTY:gungame" for a party game). */
	public static String whereLabel(ServerPlayer player) {
		Where where = where(player);
		if (where == Where.PARTY) {
			return "PARTY:" + dev.rubymod.game.party.PartyGames.of(player).id;
		}
		return where.name();
	}

	/** Every second: hub housekeeping, and Battle Day calling everyone in. */
	public static void tickSecond(MinecraftServer server) {
		ServerLevel hub = server.getLevel(HUB);
		if (hub == null) {
			return;
		}
		for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
			Where where = where(player);
			if ((where == Where.HORDE || where == Where.PARTY || where == Where.RIFT || where == Where.CONVOY) && mustStay(player)) {
				if (where == Where.HORDE) {
					Horde.leave(player);
				} else if (where == Where.RIFT) {
					dev.rubymod.game.rift.Rift.leave(player);
				} else if (where == Where.CONVOY) {
					dev.rubymod.game.convoy.Convoy.leave(player);
				} else {
					dev.rubymod.game.party.PartyGames.of(player).leave(player);
				}
				player.sendSystemMessage(Component.literal("Battle Day has begun! Back to Ruby Wars.").withStyle(ChatFormatting.RED));
				enterRubyWars(player);
			}
		}
		for (ServerPlayer player : new ArrayList<>(hub.players())) {
			if (mustStay(player)) {
				player.sendSystemMessage(Component.literal("Battle Day has begun! Back to Ruby Wars.").withStyle(ChatFormatting.RED));
				enterRubyWars(player);
				continue;
			}
			if (player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL || player.isSpectator()) {
				player.setGameMode(GameType.ADVENTURE);
			}
			if (player.getY() < FLOOR - 24) {
				player.teleportTo(hub, 0.5, FLOOR + 1, SPAWN_Z + 0.5, Set.<Relative>of(), 180.0F, 0.0F, true);
				player.resetFallDistance();
			}
		}
		if (!hub.players().isEmpty() && server.getTickCount() % 40 == 0) {
			updateLabels(hub);
		}
	}

	// ----- Building the plaza -----

	private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
		BlockPos pos = new BlockPos(x, y, z);
		if (level.getBlockState(pos) != state) {
			level.setBlock(pos, state, Block.UPDATE_CLIENTS);
		}
	}

	/** Build (or repair) the plaza. Cheap enough to run on every server start. */
	public static void ensureBuilt(ServerLevel level) {
		if (built) {
			return;
		}
		built = true;
		for (int cx = -3; cx <= 2; cx++) {
			for (int cz = -3; cz <= 2; cz++) {
				level.getChunk(cx, cz);
			}
		}
		BlockState ruby = ModBlocks.RUBY_BLOCK.defaultBlockState();
		BlockState tiles = Blocks.DEEPSLATE_TILES.defaultBlockState();
		BlockState polished = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
		BlockState barrier = Blocks.BARRIER.defaultBlockState();
		BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
		// The island: a stone plaza in the middle, a garden ring round the edge, a rocky underside.
		for (int x = -RADIUS - 1; x <= RADIUS + 1; x++) {
			for (int z = -RADIUS - 1; z <= RADIUS + 1; z++) {
				double r = Math.sqrt(x * x + z * z);
				if (r > RADIUS + 0.5) {
					continue;
				}
				BlockState floor;
				if (r < GARDEN_R) {
					floor = ((int) r) % 6 == 5 ? Blocks.CALCITE.defaultBlockState() : ((int) r) % 2 == 0 ? tiles : polished;
				} else {
					floor = grass;
				}
				set(level, x, FLOOR, z, floor);
				// The underside: dirt under the garden, stone below, tapering to a point, with ruby veins.
				for (int d = 1; d <= 10; d++) {
					boolean solid = r < RADIUS - d * 1.9 + Math.sin(x * 0.7 + z * 0.4) * 1.2;
					BlockState under = !solid ? Blocks.AIR.defaultBlockState() : d == 1 && r >= GARDEN_R ? Blocks.DIRT.defaultBlockState()
							: (x * 7 + z * 13 + d * 5) % 41 == 0 ? ruby : d < 4 ? Blocks.STONE.defaultBlockState() : Blocks.DEEPSLATE.defaultBlockState();
					set(level, x, FLOOR - d, z, under);
				}
				for (int y = FLOOR + 1; y <= FLOOR + 12; y++) {
					boolean edge = r > RADIUS - 0.5;
					BlockState want = edge && y <= FLOOR + 4 ? barrier : Blocks.AIR.defaultBlockState();
					set(level, x, y, z, want);
				}
			}
		}
		// A low hedge along the edge (the barrier keeps you on).
		for (int x = -RADIUS; x <= RADIUS; x++) {
			for (int z = -RADIUS; z <= RADIUS; z++) {
				double r = Math.sqrt(x * x + z * z);
				if (r > RADIUS - 1.5 && r <= RADIUS - 0.5) {
					set(level, x, FLOOR + 1, z, Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState()
							.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
				}
			}
		}
		// The ruby fountain where you arrive.
		fountain(level);
		// Paths: from round the fountain to each gate.
		path(level, -3, -3, RUBY_X, PORTAL_Z + 2, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState());
		path(level, -1, -4, RIFT_X, PORTAL_Z + 2, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.MAGENTA).defaultBlockState());
		path(level, 1, -4, CONVOY_X, PORTAL_Z + 2, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.ORANGE).defaultBlockState());
		path(level, 3, -3, HORDE_X, PORTAL_Z + 2, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.LIME).defaultBlockState());
		for (int z = SPAWN_Z + 1; z <= PARTY_Z - 2; z++) {
			set(level, 0, FLOOR, z, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.PURPLE).defaultBlockState());
			set(level, -1, FLOOR, z, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.PURPLE).defaultBlockState());
		}
		// The five gates, each in its own style.
		gate(level, RUBY_X, PORTAL_Z, Blocks.RED_NETHER_BRICKS.defaultBlockState(), ruby, Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.RED)
				.defaultBlockState(), Blocks.LANTERN.defaultBlockState(), ModBlocks.RUBY_PORTAL.defaultBlockState());
		gate(level, RIFT_X, PORTAL_Z, Blocks.CRYING_OBSIDIAN.defaultBlockState(), Blocks.AMETHYST_BLOCK.defaultBlockState(),
				Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.MAGENTA).defaultBlockState(), Blocks.SOUL_LANTERN.defaultBlockState(),
				ModBlocks.RIFT_PORTAL.defaultBlockState());
		gate(level, CONVOY_X, PORTAL_Z, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(), Blocks.OCHRE_FROGLIGHT.defaultBlockState(),
				Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.ORANGE).defaultBlockState(), Blocks.LANTERN.defaultBlockState(),
				ModBlocks.CONVOY_PORTAL.defaultBlockState());
		gate(level, HORDE_X, PORTAL_Z, Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), Blocks.VERDANT_FROGLIGHT.defaultBlockState(),
				Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.LIME).defaultBlockState(), Blocks.LANTERN.defaultBlockState(),
				ModBlocks.HORDE_PORTAL.defaultBlockState());
		gate(level, 0, PARTY_Z, Blocks.PURPUR_BLOCK.defaultBlockState(), Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState(),
				Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.PURPLE).defaultBlockState(), Blocks.SOUL_LANTERN.defaultBlockState(),
				ModBlocks.PARTY_PORTAL.defaultBlockState());
		// The garden: cherry trees, flowers, lanterns.
		for (int[] t : new int[][] {{-15, -6}, {15, -6}, {-14, 8}, {14, 8}, {-7, 16}, {7, 16}, {-17, 2}, {17, 2}}) {
			cherry(level, t[0], t[1], 4 + Math.abs(t[0] * 3 + t[1]) % 2);
		}
		java.util.Random rng = new java.util.Random(7);
		BlockState[] flowers = {Blocks.POPPY.defaultBlockState(), Blocks.CORNFLOWER.defaultBlockState(), Blocks.ALLIUM.defaultBlockState(),
				Blocks.AZURE_BLUET.defaultBlockState(), Blocks.OXEYE_DAISY.defaultBlockState(), Blocks.PINK_PETALS.defaultBlockState(),
				Blocks.SHORT_GRASS.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState(), Blocks.LILY_OF_THE_VALLEY.defaultBlockState()};
		for (int x = -RADIUS; x <= RADIUS; x++) {
			for (int z = -RADIUS; z <= RADIUS; z++) {
				double r = Math.sqrt(x * x + z * z);
				if (r >= GARDEN_R + 0.5 && r < RADIUS - 1.5 && rng.nextFloat() < 0.35F && level.getBlockState(new BlockPos(x, FLOOR, z)).is(Blocks.GRASS_BLOCK)
						&& level.getBlockState(new BlockPos(x, FLOOR + 1, z)).isAir()) {
					set(level, x, FLOOR + 1, z, flowers[rng.nextInt(flowers.length)]);
				}
			}
		}
		for (int[] p : new int[][] {{-11, -2}, {11, -2}, {-10, 9}, {10, 9}, {-4, 12}, {4, 12}}) {
			for (int y = 1; y <= 3; y++) {
				set(level, p[0], FLOOR + y, p[1], Blocks.DARK_OAK_FENCE.defaultBlockState());
			}
			set(level, p[0], FLOOR + 4, p[1], Blocks.LANTERN.defaultBlockState());
		}
		// Two waterfalls spilling off the island's edge.
		for (int[] w : new int[][] {{-23, 6}, {23, 6}}) {
			set(level, w[0], FLOOR, w[1], Blocks.WATER.defaultBlockState());
			set(level, w[0], FLOOR, w[1] + 1, Blocks.WATER.defaultBlockState());
		}
		// Little islands floating round about.
		islet(level, -32, FLOOR - 6, -10, 5);
		islet(level, 30, FLOOR + 3, -18, 4);
		islet(level, 34, FLOOR - 9, 14, 6);
		islet(level, -28, FLOOR + 5, 22, 4);
		islet(level, 4, FLOOR - 12, -36, 5);
		islet(level, -6, FLOOR + 8, 36, 3);
	}

	/** The plaza's inner circle: stone to here, the garden beyond. */
	private static final int GARDEN_R = 14;

	/** A round ruby fountain: a quartz rim, water, and a ruby spire with a crystal on top. */
	private static void fountain(ServerLevel level) {
		for (int x = -4; x <= 4; x++) {
			for (int z = -4; z <= 4; z++) {
				double r = Math.sqrt(x * x + z * z);
				if (r < 3.0) {
					set(level, x, FLOOR, z, Blocks.WATER.defaultBlockState());
					set(level, x, FLOOR - 1, z, Blocks.QUARTZ_BRICKS.defaultBlockState());
				} else if (r < 3.9) {
					set(level, x, FLOOR, z, Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState());
					set(level, x, FLOOR + 1, z, Blocks.QUARTZ_SLAB.defaultBlockState());
				}
			}
		}
		for (int y = 0; y <= 1; y++) {
			set(level, 0, FLOOR + y, 0, y == 0 ? Blocks.QUARTZ_PILLAR.defaultBlockState() : ModBlocks.RUBY_BLOCK.defaultBlockState());
		}
		set(level, 0, FLOOR + 2, 0, Blocks.AMETHYST_CLUSTER.defaultBlockState());
		for (int[] o : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
			set(level, o[0], FLOOR + 1, o[1], Blocks.SEA_LANTERN.defaultBlockState());
		}
	}

	/** A cherry tree: a short trunk and a round pink crown. */
	private static void cherry(ServerLevel level, int x, int z, int height) {
		for (int y = 1; y <= height; y++) {
			set(level, x, FLOOR + y, z, Blocks.CHERRY_LOG.defaultBlockState());
		}
		BlockState leaves = Blocks.CHERRY_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dy = -1; dy <= 2; dy++) {
				for (int dz = -3; dz <= 3; dz++) {
					double d = Math.sqrt(dx * dx + dy * dy * 2.2 + dz * dz);
					if (d < 3.2 && !(dx == 0 && dz == 0 && dy < 1) && (dx * 31 + dz * 17 + dy * 7 & 7) != 0) {
						BlockPos at = new BlockPos(x + dx, FLOOR + height + dy, z + dz);
						if (level.getBlockState(at).isAir()) {
							set(level, at.getX(), at.getY(), at.getZ(), leaves);
						}
					}
				}
			}
		}
	}

	/** A small floating island with a little cherry tree. */
	private static void islet(ServerLevel level, int cx, int cy, int cz, int radius) {
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				double r = Math.sqrt(x * x + z * z);
				if (r > radius + 0.3) {
					continue;
				}
				set(level, cx + x, cy, cz + z, Blocks.GRASS_BLOCK.defaultBlockState());
				for (int d = 1; d <= radius; d++) {
					if (r < radius - d * 1.1) {
						set(level, cx + x, cy - d, cz + z, d == 1 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState());
					}
				}
			}
		}
		for (int y = 1; y <= 3; y++) {
			set(level, cx, cy + y, cz, Blocks.CHERRY_LOG.defaultBlockState());
		}
		BlockState leaves = Blocks.CHERRY_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = 0; dy <= 1; dy++) {
					if (Math.abs(dx) + Math.abs(dz) + dy < 4) {
						set(level, cx + dx, cy + 3 + dy, cz + dz, leaves);
					}
				}
			}
		}
	}

	/**
	 * A grand gate round a portal: the arch, tall pillars either side crowned with glowing
	 * blocks, a pointed crown over the top, glass windows in its colour, and a brazier at each foot.
	 */
	private static void gate(ServerLevel level, int cx, int z, BlockState stone, BlockState glow, BlockState glass, BlockState fire, BlockState portal) {
		arch(level, cx, z, stone, glow, portal);
		for (int side : new int[] {-1, 1}) {
			int px = cx + side * 4;
			for (int y = 0; y <= 9; y++) {
				set(level, px, FLOOR + y, z, y == 9 ? glow : y == 4 || y == 7 ? glass : stone);
			}
			set(level, px, FLOOR + 10, z, Blocks.END_ROD.defaultBlockState());
			// A lantern on a pedestal at its foot, in front.
			set(level, px, FLOOR + 1, z + (z < 0 ? 2 : -2), stone);
			set(level, px, FLOOR + 2, z + (z < 0 ? 2 : -2), fire);
		}
		// The crown: stepping up to a point over the arch.
		for (int x = cx - 3; x <= cx + 3; x++) {
			int h = 3 - Math.abs(x - cx);
			for (int y = 8; y <= 7 + h; y++) {
				set(level, x, FLOOR + y, z, y == 7 + h ? glow : stone);
			}
		}
		set(level, cx, FLOOR + 11, z, glass);
	}

	private static void path(ServerLevel level, int x0, int z0, int x1, int z1, BlockState state) {
		int steps = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
		for (int i = 0; i <= steps; i++) {
			int x = Math.round(x0 + (x1 - x0) * i / (float) steps);
			int z = Math.round(z0 + (z1 - z0) * i / (float) steps);
			set(level, x, FLOOR, z, state);
			set(level, x + (x1 < x0 ? -1 : 1), FLOOR, z, state);
		}
	}

	private static void arch(ServerLevel level, int cx, BlockState frame, BlockState corner, BlockState portal) {
		arch(level, cx, PORTAL_Z, frame, corner, portal);
	}

	private static void arch(ServerLevel level, int cx, int PORTAL_Z, BlockState frame, BlockState corner, BlockState portal) {
		for (int y = 1; y <= 7; y++) {
			set(level, cx - 3, FLOOR + y, PORTAL_Z, y == 7 || y == 1 ? corner : frame);
			set(level, cx + 3, FLOOR + y, PORTAL_Z, y == 7 || y == 1 ? corner : frame);
		}
		for (int x = cx - 3; x <= cx + 3; x++) {
			set(level, x, FLOOR + 7, PORTAL_Z, x == cx ? corner : frame);
			set(level, x, FLOOR, PORTAL_Z, corner);
			set(level, x, FLOOR, PORTAL_Z - 1, frame);
		}
		for (int x = cx - 2; x <= cx + 2; x++) {
			for (int y = 1; y <= 6; y++) {
				set(level, x, FLOOR + y, PORTAL_Z, portal);
			}
		}
	}

	// ----- Floating signs -----

	private record Label(String key, double x, double y, double z, float scale) {
	}

	private static final List<Label> LABELS = List.of(
			new Label("ruby_title", RUBY_X + 0.5, FLOOR + 13.2, PORTAL_Z + 1.0, 3.6F),
			new Label("ruby_info", RUBY_X + 0.5, FLOOR + 3.0, PORTAL_Z + 2.6, 1.4F),
			new Label("rift_title", RIFT_X + 0.5, FLOOR + 13.2, PORTAL_Z + 1.0, 3.6F),
			new Label("rift_info", RIFT_X + 0.5, FLOOR + 3.0, PORTAL_Z + 2.6, 1.4F),
			new Label("convoy_title", CONVOY_X + 0.5, FLOOR + 13.2, PORTAL_Z + 1.0, 3.6F),
			new Label("convoy_info", CONVOY_X + 0.5, FLOOR + 3.0, PORTAL_Z + 2.6, 1.4F),
			new Label("horde_title", HORDE_X + 0.5, FLOOR + 13.2, PORTAL_Z + 1.0, 3.6F),
			new Label("horde_info", HORDE_X + 0.5, FLOOR + 3.0, PORTAL_Z + 2.6, 1.4F),
			new Label("party_title", 0.5, FLOOR + 13.2, PARTY_Z, 3.0F),
			new Label("party_info", 0.5, FLOOR + 3.0, PARTY_Z - 2.1, 1.4F),
			new Label("welcome", 0.5, FLOOR + 3.6, 0.5, 0.9F),
			new Label("board_party", -9.0, FLOOR + 1.0, 5.5, 1.6F),
			new Label("board_records", 10.0, FLOOR + 1.0, 5.5, 1.6F));

	private static Component labelText(MinecraftServer server, String key) {
		return switch (key) {
			case "ruby_title" -> Component.literal("RUBY WARS").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
			case "ruby_info" -> Component.literal("Build a base, then fight!\n").withStyle(ChatFormatting.WHITE)
					.append(Component.literal(rubyWarsSign(server)).withStyle(ChatFormatting.GOLD));
			case "horde_title" -> Component.literal("CO-OP HORDE").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
			case "rift_title" -> Component.literal("RUBY RIFT").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD);
			case "convoy_title" -> Component.literal("RUBY CONVOY").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
			case "convoy_info" -> Component.literal("Guard the ruby truck to the vault!\n").withStyle(ChatFormatting.WHITE)
					.append(Component.literal(dev.rubymod.game.convoy.Convoy.hubStatus()).withStyle(ChatFormatting.GOLD));
			case "rift_info" -> Component.literal("Fight room to room, collect relics!\n").withStyle(ChatFormatting.WHITE)
					.append(Component.literal(dev.rubymod.game.rift.Rift.hubStatus(server)).withStyle(ChatFormatting.GOLD));
			case "party_title" -> Component.literal("PARTY GAMES").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD);
			case "board_party" -> partyBoard();
			case "board_records" -> recordsBoard(server);
			case "party_info" -> Component.literal("9 quick games to play together\n").withStyle(ChatFormatting.WHITE)
					.append(Component.literal(partyPlaying() + " playing now").withStyle(ChatFormatting.GOLD));
			case "horde_info" -> Component.literal("Survive the waves together!\n").withStyle(ChatFormatting.WHITE)
					.append(Component.literal(Horde.status().replace("  \u2022  ", "\n")).withStyle(ChatFormatting.GOLD));
			default -> {
				MutableComponent text = Component.literal("Welcome!\n").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
				yield text.append(Component.literal("Walk into a portal to play.\nPress H any time to switch games.").withStyle(ChatFormatting.WHITE));
			}
		};
	}

	private static int partyPlaying() {
		int n = 0;
		for (dev.rubymod.game.party.PartyGame game : dev.rubymod.game.party.PartyGames.ALL) {
			n += game.running() ? 1 : 0;
		}
		return n == 0 ? 0 : (int) dev.rubymod.game.party.PartyGames.playerCount();
	}

	/** Short lines for the sign over the Ruby Wars portal. */
	private static String rubyWarsSign(MinecraftServer server) {
		return (Rules.phase() == Phase.LOBBY ? "Season starts soon" : Rules.phase().title()) + "\n" + playingInRubyWars(server) + " playing";
	}

	private static int playingInRubyWars(MinecraftServer server) {
		int playing = 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (where(p) == Where.RUBY_WARS) {
				playing++;
			}
		}
		return playing;
	}

	/** Leaderboard: most party game wins. */
	private static Component partyBoard() {
		MutableComponent text = Component.literal("\u2605 PARTY CHAMPIONS \u2605\n").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		var top = Stats.top(java.util.Comparator.comparingInt((Stats.Record r) -> r.totalWins()).reversed(), 5, r -> r.totalWins() > 0);
		if (top.isEmpty()) {
			return text.append(Component.literal("Win a party game\nto be the first!").withStyle(ChatFormatting.GRAY));
		}
		for (int i = 0; i < top.size(); i++) {
			Stats.Record r = top.get(i);
			text.append(Component.literal((i + 1) + ". " + r.name + "  " + r.totalWins() + (r.totalWins() == 1 ? " win" : " wins") + (i + 1 < top.size() ? "\n" : ""))
					.withStyle(i == 0 ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
		}
		return text;
	}

	/** Leaderboard: Horde waves, Sky Race times, most party kills. */
	private static Component recordsBoard(MinecraftServer server) {
		MutableComponent text = Component.literal("\u2605 RECORDS \u2605\n").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
		var horde = Stats.top(java.util.Comparator.comparingInt((Stats.Record r) -> r.hordeBestWave).reversed(), 3, r -> r.hordeBestWave > 0);
		text.append(Component.literal("Co-op Horde\n").withStyle(ChatFormatting.GREEN));
		if (horde.isEmpty()) {
			text.append(Component.literal("  nobody yet\n").withStyle(ChatFormatting.GRAY));
		}
		for (Stats.Record r : horde) {
			text.append(Component.literal("  wave " + r.hordeBestWave + "  " + r.name + "\n").withStyle(ChatFormatting.WHITE));
		}
		var race = Stats.top(java.util.Comparator.comparingInt((Stats.Record r) -> r.skyBest), 3, r -> r.skyBest > 0);
		text.append(Component.literal("Sky Race\n").withStyle(ChatFormatting.AQUA));
		if (race.isEmpty()) {
			text.append(Component.literal("  nobody yet\n").withStyle(ChatFormatting.GRAY));
		}
		for (Stats.Record r : race) {
			text.append(Component.literal("  " + Stats.raceTime(r.skyBest) + "  " + r.name + "\n").withStyle(ChatFormatting.WHITE));
		}
		var rift = Stats.top(java.util.Comparator.comparingInt((Stats.Record r) -> r.riftWins * 100 + r.riftBestFloor).reversed(), 3, r -> r.riftRuns > 0);
		text.append(Component.literal("Ruby Rift\n").withStyle(ChatFormatting.LIGHT_PURPLE));
		if (rift.isEmpty()) {
			text.append(Component.literal("  nobody yet\n").withStyle(ChatFormatting.GRAY));
		}
		for (Stats.Record r : rift) {
			text.append(Component.literal("  " + (r.riftWins > 0 ? r.riftWins + " wins" : "floor " + r.riftBestFloor) + "  " + r.name + "\n")
					.withStyle(ChatFormatting.WHITE));
		}
		var convoy = Stats.top(java.util.Comparator.comparingInt((Stats.Record r) -> r.convoyWins * 10 + r.convoyBest).reversed(), 2, r -> r.convoyRuns > 0);
		text.append(Component.literal("Ruby Convoy\n").withStyle(ChatFormatting.GOLD));
		if (convoy.isEmpty()) {
			text.append(Component.literal("  nobody yet\n").withStyle(ChatFormatting.GRAY));
		}
		for (Stats.Record r : convoy) {
			text.append(Component.literal("  " + (r.convoyWins > 0 ? r.convoyWins + " delivered" : "stage " + r.convoyBest) + "  " + r.name + "\n")
					.withStyle(ChatFormatting.WHITE));
		}
		var daily = dev.rubymod.game.rift.RiftMeta.dailyTop(server, 1);
		if (!daily.isEmpty()) {
			text.append(Component.literal("Today's Daily Rift\n  " + daily.get(0) + "\n").withStyle(ChatFormatting.LIGHT_PURPLE));
		}
		var kills = Stats.top(java.util.Comparator.comparingInt((Stats.Record r) -> r.partyKills).reversed(), 1, r -> r.partyKills > 0);
		text.append(Component.literal("Most party kills\n").withStyle(ChatFormatting.RED));
		text.append(Component.literal(kills.isEmpty() ? "  nobody yet" : "  " + kills.get(0).partyKills + "  " + kills.get(0).name)
				.withStyle(kills.isEmpty() ? ChatFormatting.GRAY : ChatFormatting.WHITE));
		return text;
	}

	/** One line about the season for the game picker. */
	public static String rubyWarsStatus(MinecraftServer server) {
		int playing = 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (where(p) == Where.RUBY_WARS) {
				playing++;
			}
		}
		String phase = Rules.phase() == Phase.LOBBY ? "Waiting for the season to start" : Rules.phase().title();
		return phase + "  •  " + playing + " playing";
	}

	/** Make sure each sign exists exactly once, and refresh what it says. */
	private static void updateLabels(ServerLevel hub) {
		Map<String, Display.TextDisplay> found = new HashMap<>();
		for (Display.TextDisplay display : hub.getEntitiesOfClass(Display.TextDisplay.class, new AABB(-40, FLOOR - 10, -40, 40, FLOOR + 30, 40),
				d -> d.entityTags().contains(LABEL_TAG))) {
			String key = display.entityTags().stream().filter(t -> t.startsWith("label_")).findFirst().map(t -> t.substring(6)).orElse("");
			if (found.containsKey(key) || LABELS.stream().noneMatch(l -> l.key().equals(key))) {
				display.discard(); // a duplicate, or an old sign
			} else {
				found.put(key, display);
			}
		}
		for (Label label : LABELS) {
			Display.TextDisplay display = found.get(label.key());
			if (display == null) {
				display = EntityTypes.TEXT_DISPLAY.create(hub, EntitySpawnReason.COMMAND);
				if (display == null) {
					continue;
				}
				display.snapTo(label.x(), label.y(), label.z(), 0, 0);
				display.addTag(LABEL_TAG);
				display.addTag("label_" + label.key());
				((DisplayAccessor) display).rubymod$setBillboard(Display.BillboardConstraints.VERTICAL);
				((DisplayAccessor) display).rubymod$setViewRange(2.0F);
				((DisplayAccessor) display).rubymod$setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
						new Vector3f(label.scale(), label.scale(), label.scale()), new Quaternionf()));
				((TextDisplayAccessor) display).rubymod$setBackground(label.scale() > 2 ? 0 : 0x90000000);
				hub.addFreshEntity(display);
			}
			Component text = labelText(hub.getServer(), label.key());
			if (!text.equals(((TextDisplayAccessor) display).rubymod$getText())) {
				((TextDisplayAccessor) display).rubymod$setText(text);
			}
		}
	}

	/** For the game picker (H): "id|title|status..." for each game. */
	public static List<String> modeLines(MinecraftServer server) {
		List<String> lines = new ArrayList<>();
		lines.add("rubywars|Ruby Wars|" + rubyWarsStatus(server));
		lines.add("horde|Co-op Horde|" + Horde.status() + "|" + Horde.running());
		lines.add("rift|Ruby Rift|" + dev.rubymod.game.rift.Rift.status() + "|" + dev.rubymod.game.rift.Rift.running());
		lines.add("convoy|Ruby Convoy|" + dev.rubymod.game.convoy.Convoy.status() + "|" + dev.rubymod.game.convoy.Convoy.running());
		lines.addAll(dev.rubymod.game.party.PartyGames.lines());
		return lines;
	}
}
