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
 * The hub: a floating plaza where everyone arrives when they join the server, with a
 * walk-in portal for each game: Ruby Wars (the main one) and Co-op Horde. Press H
 * anywhere to switch games, or use /hub and /play.
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
	private static final int RADIUS = 20;
	private static final int PORTAL_Z = -14;
	/** Where you arrive, facing the portals. */
	private static final int SPAWN_Z = 4;
	private static final int RUBY_X = -7;
	private static final int HORDE_X = 7;
	private static final String LABEL_TAG = "rubymod_hub_label";

	public enum Where { RUBY_WARS, HUB, HORDE, DUEL }

	private static boolean built;
	private static final Map<UUID, Integer> PORTAL_COOLDOWN = new HashMap<>();
	/** Players who just joined, and how many ticks until they're sorted out. */
	private static final Map<UUID, Integer> JOINING = new HashMap<>();

	private Hub() {
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
		return inRubyWarsWorld(player) ? Where.RUBY_WARS : Where.HUB;
	}

	/** In the season's worlds (overworld, Nether, End), not the hub or the arena. */
	public static boolean inRubyWarsWorld(Player player) {
		ResourceKey<Level> dim = player.level().dimension();
		return dim != HUB && dim != Duels.ARENA;
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
			case "hub" -> Where.HUB;
			default -> null;
		};
		if (to == null) {
			return "Unknown game. Try /play rubywars or /play horde.";
		}
		if (to == from) {
			return switch (to) {
				case RUBY_WARS -> "You're already in Ruby Wars.";
				case HORDE -> "You're already in Horde.";
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
		if (to == Where.HORDE) {
			String error = Horde.canJoin(player);
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
		}
		// ... and arrive.
		switch (to) {
			case RUBY_WARS -> enterRubyWars(player);
			case HORDE -> Horde.join(player, challenge);
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
		Alerts.title(player, "THE HUB", "Walk into a portal to play  •  H switches games", ChatFormatting.AQUA);
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
			player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, Set.<Relative>of(), 0, 0, true);
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
			player.teleportTo(level, in.getDoubleOr("X", 0), in.getDoubleOr("Y", 100), in.getDoubleOr("Z", 0), Set.<Relative>of(),
					in.getFloatOr("Yaw", 0), in.getFloatOr("Pitch", 0), true);
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
		if (player.level().dimension() == Duels.ARENA && !Duels.inDuel(player) && !Horde.playing(player)) {
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
		PORTAL_COOLDOWN.replaceAll((id, t) -> t - 4);
		PORTAL_COOLDOWN.values().removeIf(t -> t <= 0);
		for (ServerPlayer player : new ArrayList<>(hub.players())) {
			if (player.isSpectator() || PORTAL_COOLDOWN.containsKey(player.getUUID())) {
				continue;
			}
			AABB box = player.getBoundingBox();
			String target = box.intersects(portalBox(RUBY_X)) ? "rubywars" : box.intersects(portalBox(HORDE_X)) ? "horde" : null;
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

	private static AABB portalBox(int cx) {
		return new AABB(cx - 2, FLOOR + 1, PORTAL_Z + 0.2, cx + 3, FLOOR + 7, PORTAL_Z + 0.8);
	}

	/** Every second: hub housekeeping, and Battle Day calling everyone in. */
	public static void tickSecond(MinecraftServer server) {
		ServerLevel hub = server.getLevel(HUB);
		if (hub == null) {
			return;
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
		BlockState rim = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
		BlockState lantern = Blocks.SEA_LANTERN.defaultBlockState();
		BlockState barrier = Blocks.BARRIER.defaultBlockState();
		for (int x = -RADIUS - 1; x <= RADIUS + 1; x++) {
			for (int z = -RADIUS - 1; z <= RADIUS + 1; z++) {
				double r = Math.sqrt(x * x + z * z);
				if (r > RADIUS + 0.5) {
					continue;
				}
				BlockState floor;
				if (r < 2.5) {
					floor = ruby;
				} else if (r > RADIUS - 1.5) {
					floor = (x + z) % 4 == 0 ? lantern : rim;
				} else if (((int) r) % 6 == 5) {
					floor = lantern;
				} else {
					floor = ((int) r) % 2 == 0 ? tiles : polished;
				}
				set(level, x, FLOOR, z, floor);
				// A floating-island underside, tapering down.
				for (int d = 1; d <= 6; d++) {
					set(level, x, FLOOR - d, z, r < RADIUS - d * 2.6 ? (d < 3 ? Blocks.DEEPSLATE : Blocks.COBBLED_DEEPSLATE).defaultBlockState()
							: Blocks.AIR.defaultBlockState());
				}
				for (int y = FLOOR + 1; y <= FLOOR + 10; y++) {
					boolean edge = r > RADIUS - 0.5;
					BlockState want = edge && y == FLOOR + 1 ? Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState() : edge && y <= FLOOR + 4 ? barrier
							: Blocks.AIR.defaultBlockState();
					set(level, x, y, z, want);
				}
			}
		}
		// Coloured paths from where you arrive to each portal.
		path(level, 0, SPAWN_Z - 1, RUBY_X, PORTAL_Z + 1, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState());
		path(level, 0, SPAWN_Z - 1, HORDE_X, PORTAL_Z + 1, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.LIME).defaultBlockState());
		set(level, 0, FLOOR, SPAWN_Z, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState());
		arch(level, RUBY_X, Blocks.RED_NETHER_BRICKS.defaultBlockState(), ruby, ModBlocks.RUBY_PORTAL.defaultBlockState());
		arch(level, HORDE_X, Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), Blocks.VERDANT_FROGLIGHT.defaultBlockState(),
				ModBlocks.HORDE_PORTAL.defaultBlockState());
		// Lamp posts around the plaza.
		for (int[] p : new int[][] {{-13, 6}, {13, 6}, {-15, -4}, {15, -4}, {0, 16}}) {
			for (int y = 1; y <= 3; y++) {
				set(level, p[0], FLOOR + y, p[1], Blocks.DARK_OAK_FENCE.defaultBlockState());
			}
			set(level, p[0], FLOOR + 4, p[1], Blocks.LANTERN.defaultBlockState());
		}
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
			new Label("ruby_title", RUBY_X + 0.5, FLOOR + 10.4, PORTAL_Z + 0.5, 3.6F),
			new Label("ruby_info", RUBY_X + 0.5, FLOOR + 8.1, PORTAL_Z + 0.5, 2.0F),
			new Label("horde_title", HORDE_X + 0.5, FLOOR + 10.4, PORTAL_Z + 0.5, 3.6F),
			new Label("horde_info", HORDE_X + 0.5, FLOOR + 8.1, PORTAL_Z + 0.5, 2.0F),
			new Label("welcome", 0.5, FLOOR + 1.1, 0.5, 0.9F));

	private static Component labelText(MinecraftServer server, String key) {
		return switch (key) {
			case "ruby_title" -> Component.literal("RUBY WARS").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
			case "ruby_info" -> Component.literal("Build a base, then fight!\n").withStyle(ChatFormatting.WHITE)
					.append(Component.literal(rubyWarsSign(server)).withStyle(ChatFormatting.GOLD));
			case "horde_title" -> Component.literal("CO-OP HORDE").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
			case "horde_info" -> Component.literal("Survive the waves together!\n").withStyle(ChatFormatting.WHITE)
					.append(Component.literal(Horde.status().replace("  \u2022  ", "\n")).withStyle(ChatFormatting.GOLD));
			default -> {
				MutableComponent text = Component.literal("Welcome!\n").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
				yield text.append(Component.literal("Walk into a portal to play.\nPress H any time to switch games.").withStyle(ChatFormatting.WHITE));
			}
		};
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

	/** For the game picker (H): "id|title|status" for each game. */
	public static List<String> modeLines(MinecraftServer server) {
		return List.of(
				"rubywars|Ruby Wars|" + rubyWarsStatus(server),
				"horde|Co-op Horde|" + Horde.status() + "|" + Horde.running());
	}
}
