package dev.rubymod.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.RubyMod;
import dev.rubymod.gun.GunItem;
import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.BossEvent;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
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
import net.minecraft.world.phys.Vec3;

/**
 * 1v1 test duels: /duel <guns|vehicles|planes> <player> challenges someone; when
 * they accept, both of you go to an arena in the Duel Arena dimension (a flat
 * world of its own, so nothing in the season world is touched) with every weapon
 * of that kind. Dying respawns you after 3 seconds with a fresh kit; the first to
 * 10 kills (or the number you chose) wins. Afterwards you both go back exactly
 * where you were, with your own inventory.
 *
 * Your inventory and position are saved to world/rubywars/duels/<uuid>.dat at the
 * start, so a crash or a disconnect mid-duel still gives everything back the next
 * time you join. Duel deaths don't count for season stats, bounties or graves.
 */
public final class Duels {
	public static final ResourceKey<Level> ARENA = ResourceKey.create(Registries.DIMENSION, RubyMod.id("arena"));
	/** Top of the arena dimension's flat ground (players stand at GROUND + 1). */
	public static final int GROUND = 64;
	private static final int RESPAWN_TICKS = 60;
	private static final int CHALLENGE_TICKS = 20 * 60;
	private static final int SLOT_SPACING = 5000;

	public enum Mode {
		GUNS("guns", "Gun Duel", 30, 26),
		VEHICLES("vehicles", "Vehicle Duel", 120, 100),
		PLANES("planes", "Air Duel", 900, 250);

		public final String id;
		public final String title;
		/** Half the arena's width (walls for guns and vehicles; a warning line for planes). */
		final int half;
		/** Each side's spawn, this far from the centre along z. */
		final int spawnZ;

		Mode(String id, String title, int half, int spawnZ) {
			this.id = id;
			this.title = title;
			this.half = half;
			this.spawnZ = spawnZ;
		}

		public static Mode byId(String id) {
			for (Mode mode : values()) {
				if (mode.id.equals(id)) {
					return mode;
				}
			}
			return null;
		}
	}

	private record Challenge(UUID from, Mode mode, int kills, long expires) {
	}

	/** One side's numbers for the end-of-match scoreboard. */
	private static final class Stats {
		int kills;
		int deaths;
		int shots;
		int hits;
		float damage;
		int longest;
		int streak;
		int bestStreak;
		final Map<String, Integer> weapons = new HashMap<>();

		void kill(String weapon, int distance) {
			kills++;
			streak++;
			bestStreak = Math.max(bestStreak, streak);
			longest = Math.max(longest, distance);
			if (!weapon.isEmpty()) {
				weapons.merge(weapon, 1, Integer::sum);
			}
		}

		String favorite() {
			return weapons.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("-");
		}
	}

	private static final class Duel {
		final Mode mode;
		final UUID[] players;
		final String[] names;
		final int[] score = new int[2];
		final int target;
		final int slot;
		final ServerBossEvent bar;
		final int[] respawnIn = new int[2];
		/** Ticks until each side is put in their fresh tank or jet, and which one. */
		final int[] mountIn = new int[2];
		final UUID[] mount = new UUID[2];
		final List<UUID> spawned = new ArrayList<>();
		/** Side 1 is the practice bot (an AI soldier) instead of a player; players[1] is its current entity. */
		final boolean bot;
		final Stats[] stats = {new Stats(), new Stats()};
		int endIn = -1;
		int outside;

		Duel(Mode mode, ServerPlayer a, ServerPlayer b, int target, int slot) {
			this(mode, a, b.getUUID(), b.getName().getString(), false, target, slot);
		}

		Duel(Mode mode, ServerPlayer a, UUID b, String bName, boolean bot, int target, int slot) {
			this.mode = mode;
			this.players = new UUID[] {a.getUUID(), b};
			this.names = new String[] {a.getName().getString(), bName};
			this.bot = bot;
			this.target = target;
			this.slot = slot;
			this.bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(mode.title), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
		}

		int side(UUID player) {
			return players[0].equals(player) ? 0 : players[1].equals(player) ? 1 : -1;
		}

		BlockPos center() {
			return new BlockPos(slot * SLOT_SPACING, GROUND + 1, 0);
		}
	}

	private static final Map<UUID, Challenge> CHALLENGES = new HashMap<>();
	private static final List<Duel> DUELS = new ArrayList<>();
	private static long now;

	/** The self-test's fake players (they aren't on the player list). */
	static final Map<UUID, ServerPlayer> TEST_PLAYERS = new HashMap<>();

	private Duels() {
	}

	private static ServerPlayer online(MinecraftServer server, UUID id) {
		ServerPlayer player = server.getPlayerList().getPlayer(id);
		return player != null ? player : TEST_PLAYERS.get(id);
	}

	/** For /rubywars selftest: start a duel between two fake players right away. */
	public static void startForTest(ServerPlayer a, ServerPlayer b, Mode mode, int kills) {
		TEST_PLAYERS.put(a.getUUID(), a);
		TEST_PLAYERS.put(b.getUUID(), b);
		start(a, b, mode, kills);
	}

	public static void endTest() {
		TEST_PLAYERS.clear();
	}

	/** For the self-test: the duel score as "a-b", or null when not duelling. */
	public static String score(Player player) {
		Duel duel = duelOf(player.getUUID());
		return duel == null ? null : duel.score[0] + "-" + duel.score[1] + (duel.endIn >= 0 ? " over" : "");
	}

	/** Forget everything on server start (singleplayer can restart the server in the same game). */
	public static void reset() {
		CHALLENGES.clear();
		DUELS.clear();
	}

	// ----- Lookups used by the rest of the mod -----

	private static Duel duelOf(UUID player) {
		for (Duel duel : DUELS) {
			if (duel.side(player) >= 0) {
				return duel;
			}
		}
		return null;
	}

	public static boolean inDuel(Player player) {
		return duelOf(player.getUUID()) != null;
	}

	/**
	 * Player-vs-player damage: null when neither is duelling (normal season rules
	 * apply), true for the two duellists, false across the arena boundary.
	 */
	public static Boolean canHurt(Player attacker, Player victim) {
		Duel a = duelOf(attacker.getUUID());
		Duel v = duelOf(victim.getUUID());
		if (a == null && v == null) {
			return null;
		}
		return a == v && a.endIn < 0;
	}

	// ----- Commands -----

	public static String challenge(ServerPlayer from, ServerPlayer to, Mode mode, int kills) {
		String error = canJoin(from, "You");
		if (error == null) {
			error = canJoin(to, to.getName().getString());
		}
		if (error != null) {
			return error;
		}
		if (from == to) {
			return "You can't duel yourself. Pick another player.";
		}
		CHALLENGES.put(to.getUUID(), new Challenge(from.getUUID(), mode, kills, now + CHALLENGE_TICKS));
		Component accept = Component.literal("[ACCEPT]").withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)
				.withClickEvent(new ClickEvent.RunCommand("/duel accept"))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Go to the arena now"))));
		Component decline = Component.literal("[DECLINE]").withStyle(s -> s.withColor(ChatFormatting.GRAY)
				.withClickEvent(new ClickEvent.RunCommand("/duel decline")));
		to.sendSystemMessage(Component.literal(from.getName().getString() + " challenges you to a " + mode.title + " (first to " + kills + " kills). ")
				.withStyle(ChatFormatting.GOLD).append(accept).append(" ").append(decline));
		to.level().playSound(null, to.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0F, 1.4F);
		return null;
	}

	private static String canJoin(ServerPlayer player, String who) {
		boolean you = who.equals("You");
		if (duelOf(player.getUUID()) != null) {
			return you ? "You're already in a duel. /duel leave to quit it." : who + " is already in a duel.";
		}
		if (Rules.isEliminated(player.getUUID()) || player.isSpectator()) {
			return you ? "Spectators can't duel." : who + " is spectating.";
		}
		if (Rules.phase() == Phase.SUDDEN_DEATH || Rules.phase() == Phase.FINAL_CIRCLE) {
			return "No duels during " + Rules.phase().title() + ".";
		}
		if (CombatTag.tagged(player.getUUID())) {
			return you ? "You're in combat. Wait a few seconds." : who + " is in combat right now.";
		}
		if (Files.exists(snapshotFile(player.level().getServer(), player.getUUID()))) {
			return (you ? "Your" : who + "'s") + " last duel hasn't been restored yet; rejoin the server first.";
		}
		return null;
	}

	public static String accept(ServerPlayer player) {
		Challenge challenge = CHALLENGES.remove(player.getUUID());
		if (challenge == null || challenge.expires() < now) {
			return "You don't have a duel challenge (they last a minute).";
		}
		ServerPlayer from = online(player.level().getServer(), challenge.from());
		if (from == null) {
			return "They've left the server.";
		}
		String error = canJoin(from, from.getName().getString());
		if (error == null) {
			error = canJoin(player, "You");
		}
		if (error != null) {
			return error;
		}
		start(from, player, challenge.mode(), challenge.kills());
		return null;
	}

	public static String decline(ServerPlayer player) {
		Challenge challenge = CHALLENGES.remove(player.getUUID());
		if (challenge == null) {
			return "You don't have a duel challenge.";
		}
		ServerPlayer from = online(player.level().getServer(), challenge.from());
		if (from != null) {
			from.sendSystemMessage(Component.literal(player.getName().getString() + " declined your duel.").withStyle(ChatFormatting.GRAY));
		}
		return null;
	}

	public static String leave(ServerPlayer player) {
		Duel duel = duelOf(player.getUUID());
		if (duel == null) {
			return "You're not in a duel.";
		}
		MinecraftServer server = player.level().getServer();
		int side = duel.side(player.getUUID());
		if (duel.endIn < 0) {
			announce(server, duel, duel.names[side] + " left the duel. " + duel.names[1 - side] + " wins!");
		}
		finish(server, duel);
		return null;
	}

	public static List<String> status() {
		List<String> lines = new ArrayList<>();
		for (Duel duel : DUELS) {
			lines.add(duel.mode.title + ": " + duel.names[0] + " " + duel.score[0] + " - " + duel.score[1] + " " + duel.names[1]
					+ " (first to " + duel.target + ")");
		}
		return lines;
	}

	// ----- Starting and ending -----

	private static void start(ServerPlayer a, ServerPlayer b, Mode mode, int kills) {
		MinecraftServer server = a.level().getServer();
		ServerLevel arena = server.getLevel(ARENA);
		if (arena == null) {
			a.sendSystemMessage(Component.literal("The duel arena dimension is missing (is the mod's data pack disabled?).").withStyle(ChatFormatting.RED));
			return;
		}
		int slot = 0;
		while (slotTaken(slot)) {
			slot++;
		}
		Duel duel = new Duel(mode, a, b, kills, slot);
		for (ServerPlayer p : new ServerPlayer[] {a, b}) {
			saveSnapshot(p);
		}
		DUELS.add(duel);
		clearEntities(arena, duel);
		ArenaBuilder.build(arena, mode, duel.center());
		for (ServerPlayer p : new ServerPlayer[] {a, b}) {
			p.stopRiding();
			CHALLENGES.remove(p.getUUID());
			duel.bar.addPlayer(p);
			respawn(arena, duel, p);
			p.sendSystemMessage(Component.literal(mode.title + " vs " + duel.names[1 - duel.side(p.getUUID())] + ": first to " + kills
					+ " kills wins. /duel leave to quit; you'll get your own gear back.").withStyle(ChatFormatting.GOLD));
		}
		updateBar(duel);
		Alerts.broadcast(server, Component.literal(duel.names[0] + " and " + duel.names[1] + " started a " + mode.title + ".")
				.withStyle(ChatFormatting.GRAY));
	}

	private static boolean slotTaken(int slot) {
		for (Duel duel : DUELS) {
			if (duel.slot == slot) {
				return true;
			}
		}
		return false;
	}

	private static void finish(MinecraftServer server, Duel duel) {
		DUELS.remove(duel);
		duel.bar.removeAllPlayers();
		ServerLevel arena = server.getLevel(ARENA);
		for (UUID id : duel.players) {
			ServerPlayer p = online(server, id);
			if (p != null) {
				restore(p);
			}
			// Offline players get theirs back when they next join.
		}
		if (arena != null) {
			clearEntities(arena, duel);
		}
	}

	private static void clearEntities(ServerLevel arena, Duel duel) {
		BlockPos c = duel.center();
		int r = duel.mode.half + 40;
		for (Entity e : arena.getEntities((Entity) null, new AABB(c.getX() - r, -64, c.getZ() - r, c.getX() + r, 400, c.getZ() + r),
				e -> !(e instanceof Player))) {
			e.discard();
		}
		duel.spawned.clear();
	}

	private static void announce(MinecraftServer server, Duel duel, String message) {
		for (UUID id : duel.players) {
			ServerPlayer p = online(server, id);
			if (p != null) {
				p.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GOLD));
			}
		}
	}

	// ----- Deaths and respawns -----

	/** A duellist would die: score it and start their respawn instead. Returns true when handled (cancel the death). */
	public static boolean onDeath(ServerPlayer victim, DamageSource source) {
		Duel duel = duelOf(victim.getUUID());
		if (duel == null) {
			return false;
		}
		int side = duel.side(victim.getUUID());
		MinecraftServer server = victim.level().getServer();
		victim.stopRiding();
		victim.setHealth(victim.getMaxHealth());
		victim.removeAllEffects();
		victim.clearFire();
		victim.setGameMode(GameType.SPECTATOR);
		if (duel.endIn >= 0 || duel.respawnIn[side] > 0) {
			duel.respawnIn[side] = Math.max(duel.respawnIn[side], 1);
			return true;
		}
		duel.respawnIn[side] = RESPAWN_TICKS;
		// Every duel death is a point for the other side (crashes and your own rockets included).
		duel.score[1 - side]++;
		ServerPlayer killer = online(server, duel.players[1 - side]);
		boolean byOpponent = source.getEntity() != null && source.getEntity().getUUID().equals(duel.players[1 - side]);
		duel.stats[side].deaths++;
		duel.stats[side].streak = 0;
		if (byOpponent) {
			duel.stats[1 - side].kill(BattleDirector.weaponName(source.getEntity(), source), Math.round(source.getEntity().distanceTo(victim)));
		}
		BattleDirector.sendDeathInfo(victim, source);
		String how = byOpponent ? duel.names[1 - side] + " got you" : "You died";
		String score = duel.score[side] + " - " + duel.score[1 - side];
		Alerts.title(victim, "KILLED", how + "  (" + score + ")  respawning...", ChatFormatting.RED);
		if (killer != null) {
			Alerts.title(killer, "+1", (byOpponent ? "You got " + duel.names[side] : duel.names[side] + " died") + "  ("
					+ duel.score[1 - side] + " - " + duel.score[side] + ")", ChatFormatting.GREEN);
			killer.level().playSound(null, killer.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0F, 1.2F);
		}
		updateBar(duel);
		if (duel.score[1 - side] >= duel.target) {
			win(server, duel, 1 - side);
		}
		return true;
	}

	private static void win(MinecraftServer server, Duel duel, int winner) {
		duel.endIn = 100;
		if (!(duel.bot && winner == 1)) {
			Goals.add(server, duel.players[winner], "duel", 1);
		}
		String result = duel.names[winner] + " wins " + duel.score[winner] + " - " + duel.score[1 - winner];
		for (int i = 0; i < 2; i++) {
			ServerPlayer p = online(server, duel.players[i]);
			if (p != null) {
				Alerts.title(p, i == winner ? "VICTORY" : "DEFEAT", result, i == winner ? ChatFormatting.GOLD : ChatFormatting.RED);
				p.level().playSound(null, p.blockPosition(), i == winner ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.RAID_HORN.value(),
						SoundSource.PLAYERS, 1.0F, 1.0F);
			}
		}
		Alerts.broadcast(server, Component.literal(duel.mode.title + ": " + result + ".").withStyle(ChatFormatting.GOLD));
		for (int i = 0; i < 2; i++) {
			ServerPlayer p = online(server, duel.players[i]);
			if (p != null) {
				for (Component line : scoreboard(duel, winner)) {
					p.sendSystemMessage(line);
				}
			}
		}
	}

	/** The end-of-match scoreboard: kills, deaths, accuracy, damage, longest kill, best streak, favorite weapon. */
	private static List<Component> scoreboard(Duel duel, int winner) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal("====== " + duel.mode.title.toUpperCase() + " RESULTS ======").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		for (int i : new int[] {winner, 1 - winner}) {
			Stats s = duel.stats[i];
			String accuracy = s.shots > 0 ? Math.min(100, Math.round(s.hits * 100.0F / s.shots)) + "%" : "-";
			lines.add(Component.literal((i == winner ? "\u2605 " : "  ") + duel.names[i] + "  " + duel.score[i] + " pts").withStyle(
					i == winner ? ChatFormatting.YELLOW : ChatFormatting.WHITE, ChatFormatting.BOLD));
			lines.add(Component.literal("    " + s.kills + (s.kills == 1 ? " kill, " : " kills, ") + s.deaths + (s.deaths == 1 ? " death" : " deaths")
					+ ", accuracy " + accuracy + ", " + Math.round(s.damage) + " damage")
					.withStyle(ChatFormatting.GRAY));
			lines.add(Component.literal("    longest kill " + (s.longest > 0 ? s.longest + "m" : "-") + ", best streak " + s.bestStreak + ", favorite: "
					+ s.favorite()).withStyle(ChatFormatting.GRAY));
		}
		return lines;
	}

	/** Gunfire: a duellist fired (pellets = shots, so a shotgun blast counts each pellet). */
	public static void onShot(ServerPlayer shooter, int pellets) {
		Duel duel = duelOf(shooter.getUUID());
		if (duel != null && duel.endIn < 0) {
			duel.stats[duel.side(shooter.getUUID())].shots += pellets;
		}
	}

	/** Any damage: count the duellist's hits and damage on their opponent. */
	public static void onHit(net.minecraft.world.entity.LivingEntity victim, DamageSource source, float amount) {
		if (source.getEntity() == null || DUELS.isEmpty()) {
			return;
		}
		Duel duel = duelOf(source.getEntity().getUUID());
		if (duel == null || duel.endIn >= 0) {
			return;
		}
		int side = duel.side(source.getEntity().getUUID());
		if (!victim.getUUID().equals(duel.players[1 - side])) {
			return;
		}
		duel.stats[side].damage += amount;
		if (source.is(dev.rubymod.registry.ModDamage.BULLET) || source.is(dev.rubymod.registry.ModDamage.HEADSHOT)) {
			duel.stats[side].hits++;
		}
	}

	// ----- The practice bot -----

	/** /duel guns bot: you against an AI soldier, same rules (first to N, respawns, scoreboard). */
	public static String startBot(ServerPlayer player, Mode mode, int kills) {
		if (mode != Mode.GUNS) {
			return "The practice bot only does gun duels (it can't drive or fly yet). Try /duel guns bot";
		}
		String error = canJoin(player, "You");
		if (error != null) {
			return error;
		}
		MinecraftServer server = player.level().getServer();
		ServerLevel arena = server.getLevel(ARENA);
		if (arena == null) {
			return "The duel arena dimension is missing.";
		}
		int slot = 0;
		while (slotTaken(slot)) {
			slot++;
		}
		Duel duel = new Duel(mode, player, UUID.randomUUID(), "Practice Bot", true, kills, slot);
		saveSnapshot(player);
		DUELS.add(duel);
		clearEntities(arena, duel);
		ArenaBuilder.build(arena, mode, duel.center());
		player.stopRiding();
		CHALLENGES.remove(player.getUUID());
		duel.bar.addPlayer(player);
		respawn(arena, duel, player);
		spawnBot(arena, duel);
		player.sendSystemMessage(Component.literal(mode.title + " vs the Practice Bot: first to " + kills
				+ " kills wins. It hunts you down, so keep moving! /duel leave to quit.").withStyle(ChatFormatting.GOLD));
		updateBar(duel);
		return null;
	}

	private static void spawnBot(ServerLevel arena, Duel duel) {
		Entity old = arena.getEntity(duel.players[1]);
		if (old != null) {
			old.discard();
		}
		BlockPos c = duel.center();
		BlockPos at = new BlockPos(c.getX(), c.getY(), c.getZ() + duel.mode.spawnZ);
		ServerPlayer prey = online(arena.getServer(), duel.players[0]);
		var bot = dev.rubymod.entity.SoldierEntity.duelBot(arena, at, prey);
		if (bot != null) {
			arena.addFreshEntity(bot);
			duel.players[1] = bot.getUUID();
		}
	}

	/** A non-player died: if it's a duel's practice bot, that's a point for the player. */
	public static void onEntityDeath(net.minecraft.world.entity.LivingEntity entity, DamageSource source) {
		for (Duel duel : DUELS) {
			if (!duel.bot || !entity.getUUID().equals(duel.players[1]) || duel.endIn >= 0 || duel.respawnIn[1] > 0) {
				continue;
			}
			MinecraftServer server = entity.level().getServer();
			duel.score[0]++;
			duel.respawnIn[1] = RESPAWN_TICKS;
			duel.stats[1].deaths++;
			duel.stats[1].streak = 0;
			ServerPlayer player = online(server, duel.players[0]);
			if (source.getEntity() == player && player != null) {
				duel.stats[0].kill(BattleDirector.weaponName(player, source), Math.round(player.distanceTo(entity)));
				Alerts.title(player, "+1", "You got the bot  (" + duel.score[0] + " - " + duel.score[1] + ")", ChatFormatting.GREEN);
				player.level().playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0F, 1.2F);
			}
			updateBar(duel);
			if (duel.score[0] >= duel.target) {
				win(server, duel, 0);
			}
			return;
		}
	}

	/** After a real death somehow got through (it shouldn't), put a duellist back in the arena. */
	public static boolean onRespawn(ServerPlayer player) {
		Duel duel = duelOf(player.getUUID());
		if (duel == null) {
			return false;
		}
		ServerLevel arena = player.level().getServer().getLevel(ARENA);
		if (arena != null) {
			respawn(arena, duel, player);
		}
		return true;
	}

	private static void respawn(ServerLevel arena, Duel duel, ServerPlayer player) {
		int side = duel.side(player.getUUID());
		BlockPos c = duel.center();
		int dir = side == 0 ? 1 : -1;
		double x = c.getX() + 0.5;
		double z = c.getZ() - dir * duel.mode.spawnZ + 0.5;
		float yaw = side == 0 ? 0.0F : 180.0F; // facing the other side
		player.stopRiding();
		player.teleportTo(arena, x, c.getY(), z, Set.<Relative>of(), yaw, 0.0F, true);
		player.setGameMode(GameType.SURVIVAL);
		player.setHealth(player.getMaxHealth());
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(10.0F);
		player.removeAllEffects();
		player.clearFire();
		player.resetFallDistance();
		player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, -1, 0, false, false));
		Kits.give(player, duel.mode);
		SpawnShield.grant(player);

		// Vehicles and planes start you in a fresh tank / jet; your old empty ones are cleared away.
		for (Iterator<UUID> it = duel.spawned.iterator(); it.hasNext();) {
			Entity old = arena.getEntity(it.next());
			if (old == null || !old.isAlive()) {
				it.remove();
			} else if (!old.isVehicle() && old.entityTags().contains("duel_" + side)) {
				old.discard();
				it.remove();
			}
		}
		EntityType<? extends Mob> ride = switch (duel.mode) {
			case VEHICLES -> ModEntities.TANK;
			case PLANES -> ModEntities.FIGHTER_JET;
			default -> null;
		};
		if (ride != null) {
			Mob vehicle = ride.create(arena, EntitySpawnReason.SPAWN_ITEM_USE);
			if (vehicle != null) {
				vehicle.snapTo(x, c.getY(), z + dir * 3, yaw, 0);
				vehicle.yBodyRot = yaw;
				vehicle.yHeadRot = yaw;
				vehicle.addTag("duel_" + side);
				arena.addFreshEntity(vehicle);
				duel.spawned.add(vehicle.getUUID());
				// Board a moment later, once your client has the arena's ground loaded (a jet
				// simulated over unloaded chunks falls through and "crashes").
				duel.mount[side] = vehicle.getUUID();
				duel.mountIn[side] = 30;
			}
		}
	}

	// ----- Ticking -----

	public static void tick(MinecraftServer server) {
		now++;
		CHALLENGES.values().removeIf(c -> c.expires() < now);
		ServerLevel arena = server.getLevel(ARENA);
		for (Duel duel : new ArrayList<>(DUELS)) {
			if (duel.endIn >= 0) {
				if (--duel.endIn <= 0) {
					finish(server, duel);
				}
				continue;
			}
			if (duel.bot && duel.respawnIn[1] > 0 && --duel.respawnIn[1] == 0 && arena != null) {
				spawnBot(arena, duel);
			}
			for (int i = 0; i < 2; i++) {
				ServerPlayer p = online(server, duel.players[i]);
				if (p == null) {
					continue;
				}
				if (duel.respawnIn[i] > 0) {
					if (duel.respawnIn[i] % 20 == 0) {
						p.sendOverlayMessage(Component.literal("Respawning in " + duel.respawnIn[i] / 20 + "...").withStyle(ChatFormatting.YELLOW));
					}
					if (--duel.respawnIn[i] == 0 && arena != null) {
						respawn(arena, duel, p);
					}
					continue;
				}
				if (duel.mountIn[i] > 0 && --duel.mountIn[i] == 0 && arena != null && duel.mount[i] != null) {
					Entity ride = arena.getEntity(duel.mount[i]);
					if (ride != null && ride.isAlive() && !ride.isVehicle() && p.getVehicle() == null && p.distanceTo(ride) < 12) {
						p.startRiding(ride, true, true);
					}
				}
				// Wandered (or flew) off: you can't get back into the season world from here, but warn anyway.
				if (p.level() != arena) {
					respawn(arena, duel, p);
				} else if (server.getTickCount() % 20 == 0) {
					BlockPos c = duel.center();
					double away = Math.max(Math.abs(p.getX() - c.getX()), Math.abs(p.getZ() - c.getZ()));
					if (away > duel.mode.half + 8) {
						p.sendOverlayMessage(Component.literal("Leaving the arena: turn back!").withStyle(ChatFormatting.RED));
					}
				}
			}
		}
	}

	private static void updateBar(Duel duel) {
		duel.bar.setName(Component.literal(duel.mode.title + "   " + duel.names[0] + " " + duel.score[0] + "  -  " + duel.score[1] + " " + duel.names[1]
				+ "   (first to " + duel.target + ")"));
		duel.bar.setProgress(Math.max(duel.score[0], duel.score[1]) / (float) duel.target);
	}

	// ----- Joining and leaving the server -----

	/** On join: a player whose duel ended while they were away (or the server stopped) gets their own things back. */
	public static void onJoin(ServerPlayer player) {
		if (duelOf(player.getUUID()) == null && Files.exists(snapshotFile(player.level().getServer(), player.getUUID()))) {
			restore(player);
		}
	}

	/** On disconnect: the other side wins. The leaver is restored when they come back. */
	public static void onLeave(MinecraftServer server, ServerPlayer player) {
		Duel duel = duelOf(player.getUUID());
		CHALLENGES.remove(player.getUUID());
		if (duel == null) {
			return;
		}
		int side = duel.side(player.getUUID());
		if (server.isRunning() && duel.endIn < 0) {
			announce(server, duel, duel.names[side] + " left the server. " + duel.names[1 - side] + " wins!");
		}
		DUELS.remove(duel);
		duel.bar.removeAllPlayers();
		ServerPlayer other = online(server, duel.players[1 - side]);
		if (other != null && server.isRunning()) {
			restore(other);
		}
		ServerLevel arena = server.getLevel(ARENA);
		if (arena != null && server.isRunning()) {
			clearEntities(arena, duel);
		}
	}

	// ----- Saving and restoring what you had -----

	private static Path snapshotFile(MinecraftServer server, UUID player) {
		return server.getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("duels").resolve(player + ".dat");
	}

	private static void saveSnapshot(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
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
		out.putInt("Food", player.getFoodData().getFoodLevel());
		Path file = snapshotFile(server, player.getUUID());
		try {
			Files.createDirectories(file.getParent());
			NbtIo.writeCompressed(out.buildResult(), file);
		} catch (IOException e) {
			RubyMod.LOGGER.error("Couldn't save {}'s pre-duel inventory", player.getName().getString(), e);
		}
	}

	private static void restore(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Path file = snapshotFile(server, player.getUUID());
		player.stopRiding();
		player.removeAllEffects();
		player.clearFire();
		SpawnShield.clear(player);
		CompoundTag tag;
		try {
			tag = Files.exists(file) ? NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()) : null;
		} catch (IOException e) {
			RubyMod.LOGGER.error("Couldn't read {}'s pre-duel inventory", player.getName().getString(), e);
			tag = null;
		}
		if (tag == null) {
			// Nothing saved: at least get them out of the arena with nothing from it.
			player.getInventory().clearContent();
			ServerLevel overworld = server.overworld();
			BlockPos spawn = overworld.getRespawnData().pos();
			player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, Set.<Relative>of(), 0, 0, true);
			player.setGameMode(GameType.SURVIVAL);
			return;
		}
		ValueInput in = TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), tag);
		var inventory = player.getInventory();
		inventory.clearContent();
		for (ItemStackWithSlot item : in.listOrEmpty("Items", ItemStackWithSlot.CODEC)) {
			if (item.isValidInContainer(inventory.getContainerSize())) {
				inventory.setItem(item.slot(), item.stack());
			}
		}
		ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(in.getStringOr("Dim", "minecraft:overworld"))));
		if (level == null || level.dimension() == ARENA) {
			level = server.overworld();
		}
		player.teleportTo(level, in.getDoubleOr("X", 0), in.getDoubleOr("Y", 100), in.getDoubleOr("Z", 0), Set.<Relative>of(),
				in.getFloatOr("Yaw", 0), in.getFloatOr("Pitch", 0), true);
		player.setGameMode(GameType.byName(in.getStringOr("Mode", "survival"), GameType.SURVIVAL));
		player.setHealth(Math.max(1.0F, in.getFloatOr("Health", player.getMaxHealth())));
		player.getFoodData().setFoodLevel(in.getIntOr("Food", 20));
		player.resetFallDistance();
		player.inventoryMenu.broadcastChanges();
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			RubyMod.LOGGER.warn("Couldn't delete {}", file, e);
		}
		player.sendSystemMessage(Component.literal("You're back where you were, with your own gear.").withStyle(ChatFormatting.GRAY));
	}

	// ----- Kits -----

	static final class Kits {
		private Kits() {
		}

		static void give(ServerPlayer player, Mode mode) {
			var inventory = player.getInventory();
			inventory.clearContent();
			switch (mode) {
				case GUNS -> {
					add(player, gun(ModItems.ARR_RIFLE), gun(ModItems.COMBAT_SHOTGUN), gun(ModItems.LONGSHOT_SNIPER), gun(ModItems.SERVICE_PISTOL),
							gun(ModItems.RUBY_BLASTER), new ItemStack(ModItems.ROCKET_LAUNCHER), new ItemStack(ModItems.FRAG_GRENADE, 8),
							new ItemStack(ModItems.C4, 4), new ItemStack(ModItems.RUBY_SWORD),
							new ItemStack(ModItems.LANCER), new ItemStack(ModItems.SKYHAMMER), new ItemStack(ModItems.SMOKE_GRENADE, 4),
							new ItemStack(ModItems.BREACHING_CHARGE, 2), new ItemStack(ModItems.AIR_STRIKE_FLARE), new ItemStack(ModItems.RECON_DRONE),
							new ItemStack(ModItems.RANGEFINDER), new ItemStack(ModItems.ROCKET, 12), new ItemStack(ModItems.AT_MISSILE, 4),
							new ItemStack(ModItems.AA_MISSILE, 4), new ItemStack(ModItems.LIGHT_AMMO, 64), new ItemStack(ModItems.LIGHT_AMMO, 64),
							new ItemStack(ModItems.HEAVY_AMMO, 64), new ItemStack(ModItems.SHOTGUN_SHELL, 64), new ItemStack(ModItems.SNIPER_ROUND, 32),
							new ItemStack(ModItems.RUBY_CELL, 64));
				}
				case VEHICLES -> {
					add(player, new ItemStack(ModItems.LANCER), new ItemStack(ModItems.ROCKET_LAUNCHER), new ItemStack(ModItems.C4, 6),
							new ItemStack(ModBlocks.AT_MINE, 4), gun(ModItems.ARR_RIFLE), new ItemStack(ModItems.JEEP), new ItemStack(ModItems.TANK),
							new ItemStack(ModItems.RAILGUN), new ItemStack(ModItems.FRAG_GRENADE, 4),
							new ItemStack(ModItems.AT_MISSILE, 16), new ItemStack(ModItems.ROCKET, 16), new ItemStack(ModItems.SMOKE_GRENADE, 4),
							new ItemStack(ModItems.RANGEFINDER), new ItemStack(ModItems.LIGHT_AMMO, 64), new ItemStack(ModItems.HEAVY_AMMO, 64));
				}
				case PLANES -> {
					add(player, new ItemStack(ModItems.SKYHAMMER), new ItemStack(ModItems.HEAVY_AA_GUN), new ItemStack(ModItems.FIGHTER_JET),
							new ItemStack(ModItems.ATTACK_JET), new ItemStack(ModItems.BOMBER), new ItemStack(ModItems.ATTACK_HELI),
							gun(ModItems.ARR_RIFLE), new ItemStack(ModItems.LANCER), new ItemStack(ModItems.RANGEFINDER),
							new ItemStack(ModItems.AA_MISSILE, 16), new ItemStack(ModItems.AT_MISSILE, 8), new ItemStack(ModItems.HEAVY_AMMO, 64),
							new ItemStack(ModItems.LIGHT_AMMO, 64));
				}
			}
			player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.RUBY_HELMET));
			player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.RUBY_CHESTPLATE));
			player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModItems.RUBY_LEGGINGS));
			player.setItemSlot(EquipmentSlot.FEET, new ItemStack(ModItems.RUBY_BOOTS));
			inventory.setSelectedSlot(0);
			player.inventoryMenu.broadcastChanges();
		}

		private static ItemStack gun(Item item) {
			ItemStack stack = new ItemStack(item);
			stack.set(ModComponents.AMMO, ((GunItem) item).stats().magazine());
			return stack;
		}

		private static void add(ServerPlayer player, ItemStack... stacks) {
			for (ItemStack stack : stacks) {
				player.getInventory().add(stack);
			}
		}
	}

	// ----- Arenas -----

	/**
	 * The three arenas, rebuilt (and repaired) at the start of every duel. Each is
	 * symmetric, so neither side has the better spawn.
	 */
	static final class ArenaBuilder {
		private static final int FLAGS = Block.UPDATE_CLIENTS;

		private ArenaBuilder() {
		}

		static void build(ServerLevel level, Mode mode, BlockPos c) {
			int r = switch (mode) {
				case GUNS -> mode.half + 4;
				case VEHICLES -> mode.half + 4;
				case PLANES -> 40;
			};
			// Generate the chunks first (the arena dimension is a flat world, so this is quick).
			int span = mode == Mode.PLANES ? mode.spawnZ + 80 : r;
			for (int cx = (c.getX() - r) >> 4; cx <= (c.getX() + r) >> 4; cx++) {
				for (int cz = (c.getZ() - span) >> 4; cz <= (c.getZ() + span) >> 4; cz++) {
					level.getChunk(cx, cz);
				}
			}
			switch (mode) {
				case GUNS -> guns(level, c);
				case VEHICLES -> vehicles(level, c);
				case PLANES -> planes(level, c);
			}
		}

		/** Restore flat ground (craters filled, debris cleared) over a rectangle. */
		private static void flatten(ServerLevel level, BlockPos c, int x1, int z1, int x2, int z2, int clearHeight, BlockState floor) {
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			BlockState dirt = Blocks.DIRT.defaultBlockState();
			for (int x = x1; x <= x2; x++) {
				for (int z = z1; z <= z2; z++) {
					for (int y = GROUND - 3; y <= GROUND + clearHeight; y++) {
						BlockState want = y < GROUND ? dirt : y == GROUND ? floor : Blocks.AIR.defaultBlockState();
						p.set(c.getX() + x, y, c.getZ() + z);
						if (level.getBlockState(p) != want) {
							level.setBlock(p, want, FLAGS);
						}
					}
				}
			}
		}

		/** Fill a box given relative to the arena centre (y relative to the ground surface). */
		private static void box(ServerLevel level, BlockPos c, int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
			for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
				for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
					for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
						level.setBlock(new BlockPos(c.getX() + x, GROUND + y, c.getZ() + z), state, FLAGS);
					}
				}
			}
		}

		/** The same box and its mirror on the other side (rotated 180 degrees about the centre). */
		private static void pair(ServerLevel level, BlockPos c, int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
			box(level, c, x1, y1, z1, x2, y2, z2, state);
			box(level, c, -x1, y1, -z1, -x2, y2, -z2, state);
		}

		private static void guns(ServerLevel level, BlockPos c) {
			int h = Mode.GUNS.half;
			BlockState bricks = Blocks.STONE_BRICKS.defaultBlockState();
			BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
			BlockState crate = Blocks.BARREL.defaultBlockState();
			BlockState sand = Blocks.SMOOTH_SANDSTONE.defaultBlockState();
			BlockState glass = Blocks.GLASS.defaultBlockState();
			flatten(level, c, -h - 3, -h - 3, h + 3, h + 3, 30, Blocks.POLISHED_ANDESITE.defaultBlockState());
			// Floor markings: a centre line and spawn pads.
			box(level, c, -h, 0, 0, h, 0, 0, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState());
			box(level, c, -3, 0, -Mode.GUNS.spawnZ - 2, 3, 0, -Mode.GUNS.spawnZ + 2, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState());
			box(level, c, -3, 0, Mode.GUNS.spawnZ - 2, 3, 0, Mode.GUNS.spawnZ + 2, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.BLUE).defaultBlockState());
			// Outer walls with an invisible cap so nobody climbs out.
			for (int[] side : new int[][] {{-h - 1, -h - 1, h + 1, -h - 1}, {-h - 1, h + 1, h + 1, h + 1}, {-h - 1, -h - 1, -h - 1, h + 1},
					{h + 1, -h - 1, h + 1, h + 1}}) {
				box(level, c, side[0], 1, side[1], side[2], 6, side[3], bricks);
				box(level, c, side[0], 7, side[1], side[2], 30, side[3], Blocks.BARRIER.defaultBlockState());
			}
			// Spawn shields: a low wall in front of each spawn.
			pair(level, c, -4, 1, -Mode.GUNS.spawnZ + 4, 4, 2, -Mode.GUNS.spawnZ + 4, sand);
			// Centre: a cross of walls with doorways.
			box(level, c, -8, 1, -1, 8, 3, 1, bricks);
			box(level, c, -1, 1, -8, 1, 3, 8, bricks);
			box(level, c, -1, 1, -1, 1, 4, 1, bricks);
			box(level, c, -5, 1, -1, -4, 2, 1, Blocks.AIR.defaultBlockState());
			box(level, c, 4, 1, -1, 5, 2, 1, Blocks.AIR.defaultBlockState());
			box(level, c, -1, 1, -5, 1, 2, -4, Blocks.AIR.defaultBlockState());
			box(level, c, -1, 1, 4, 1, 2, 5, Blocks.AIR.defaultBlockState());
			// Watchtowers (with a ladder) on opposite corners.
			for (int s : new int[] {1, -1}) {
				int tx = 20 * s;
				int tz = -14 * s;
				box(level, c, tx - 2, 1, tz - 2, tx + 2, 5, tz + 2, planks);
				box(level, c, tx - 1, 1, tz - 1, tx + 1, 4, tz + 1, Blocks.AIR.defaultBlockState());
				box(level, c, tx - 2, 2, tz, tx - 2, 3, tz, glass);
				box(level, c, tx + 2, 2, tz, tx + 2, 3, tz, glass);
				box(level, c, tx, 1, tz - 2 * s, tx, 2, tz - 2 * s, Blocks.AIR.defaultBlockState()); // door, facing your side
				box(level, c, tx - 2, 6, tz - 2, tx + 2, 6, tz + 2, Blocks.SPRUCE_FENCE.defaultBlockState());
				box(level, c, tx - 1, 6, tz - 1, tx + 1, 6, tz + 1, Blocks.AIR.defaultBlockState());
				BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING,
						s > 0 ? net.minecraft.core.Direction.NORTH : net.minecraft.core.Direction.SOUTH);
				box(level, c, tx, 1, tz + s, tx, 5, tz + s, ladder); // on the back wall, up through a hatch in the roof
			}
			// Crates, sandbags and low walls, mirrored.
			int[][] crates = {{-14, -20}, {-13, -20}, {-14, -19}, {12, -18}, {12, -17}, {-22, -6}, {-22, -5}, {6, -10}, {-6, -12}, {24, -4}, {25, -4}};
			for (int[] k : crates) {
				pair(level, c, k[0], 1, k[1], k[0], 1, k[1], crate);
			}
			pair(level, c, -14, 2, -20, -14, 2, -20, crate);
			pair(level, c, 12, 2, -18, 12, 2, -18, crate);
			pair(level, c, -20, 1, -16, -14, 1, -16, sand);
			pair(level, c, 4, 1, -20, 10, 1, -20, sand);
			pair(level, c, 14, 1, -8, 18, 2, -8, bricks);
			pair(level, c, -18, 1, -10, -18, 2, -4, bricks);
			pair(level, c, -10, 1, -4, -6, 2, -4, Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
			pair(level, c, 6, 1, -6, 10, 1, -6, sand);
			// Pillars along the edges.
			for (int z = -24; z <= -4; z += 10) {
				pair(level, c, -27, 1, z, -27, 3, z, bricks);
				pair(level, c, 27, 1, z, 27, 3, z, bricks);
			}
		}

		private static void vehicles(ServerLevel level, BlockPos c) {
			int h = Mode.VEHICLES.half;
			BlockState bricks = Blocks.STONE_BRICKS.defaultBlockState();
			BlockState cracked = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
			BlockState dirt = Blocks.DIRT.defaultBlockState();
			BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
			BlockState log = Blocks.OAK_LOG.defaultBlockState();
			BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
			flatten(level, c, -h - 3, -h - 3, h + 3, h + 3, 16, grass);
			// Perimeter wall, 4 high: nothing on wheels or tracks gets over it.
			for (int[] side : new int[][] {{-h - 1, -h - 1, h + 1, -h - 1}, {-h - 1, h + 1, h + 1, h + 1}, {-h - 1, -h - 1, -h - 1, h + 1},
					{h + 1, -h - 1, h + 1, h + 1}}) {
				box(level, c, side[0], 1, side[1], side[2], 4, side[3], bricks);
			}
			// Spawn pads.
			box(level, c, -6, 0, -Mode.VEHICLES.spawnZ - 6, 6, 0, -Mode.VEHICLES.spawnZ + 8, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState());
			box(level, c, -6, 0, Mode.VEHICLES.spawnZ - 8, 6, 0, Mode.VEHICLES.spawnZ + 6, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.BLUE).defaultBlockState());
			// A gravel road down the middle.
			box(level, c, -3, 0, -h, 3, 0, h, Blocks.GRAVEL.defaultBlockState());
			// Low hills to hide behind (stepped, so tanks can drive up them).
			int[][] hills = {{-50, -40, 10}, {45, -60, 8}, {0, 0, 12}, {-80, -10, 9}, {80, -30, 7}};
			for (int[] hill : hills) {
				for (int s : hill[0] == 0 && hill[1] == 0 ? new int[] {1} : new int[] {1, -1}) {
					int hx = hill[0] * s;
					int hz = hill[1] * s;
					int rad = hill[2];
					for (int dx = -rad; dx <= rad; dx++) {
						for (int dz = -rad; dz <= rad; dz++) {
							double d = Math.sqrt(dx * dx + dz * dz);
							int top = (int) Math.round((rad - d) / rad * 4.0);
							if (top > 0) {
								box(level, c, hx + dx, 1, hz + dz, hx + dx, top - 1, hz + dz, dirt);
								box(level, c, hx + dx, top, hz + dz, hx + dx, top, hz + dz, grass);
							}
						}
					}
				}
			}
			// Ruined buildings: broken walls 3 high with gaps.
			int[][] ruins = {{-30, -70}, {30, -30}, {-60, -80}, {70, -85}, {-25, -15}, {95, -60}, {-95, -50}};
			for (int[] ruin : ruins) {
				int rx = ruin[0];
				int rz = ruin[1];
				pair(level, c, rx - 6, 1, rz - 5, rx + 6, 3, rz - 5, bricks);
				pair(level, c, rx - 6, 1, rz - 5, rx - 6, 2, rz + 5, cracked);
				pair(level, c, rx + 6, 1, rz + 1, rx + 6, 3, rz + 5, bricks);
				pair(level, c, rx - 1, 1, rz - 5, rx + 1, 3, rz - 5, Blocks.AIR.defaultBlockState());
				pair(level, c, rx + 3, 3, rz - 5, rx + 6, 3, rz - 5, Blocks.AIR.defaultBlockState());
			}
			// Trees.
			int[][] trees = {{-15, -50}, {20, -75}, {-40, -95}, {60, -15}, {-70, -30}, {100, -100}, {-105, -85}, {35, -100}, {-12, -28}};
			for (int[] tree : trees) {
				pair(level, c, tree[0] - 2, 4, tree[1] - 2, tree[0] + 2, 5, tree[1] + 2, leaves);
				pair(level, c, tree[0] - 1, 6, tree[1] - 1, tree[0] + 1, 6, tree[1] + 1, leaves);
				pair(level, c, tree[0], 1, tree[1], tree[0], 5, tree[1], log);
			}
		}

		private static void planes(ServerLevel level, BlockPos c) {
			int z0 = Mode.PLANES.spawnZ;
			BlockState runway = Blocks.SMOOTH_STONE.defaultBlockState();
			BlockState stripe = Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState();
			BlockState edge = Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.YELLOW).defaultBlockState();
			// Each side's runway points at the other: take off straight into the fight.
			for (int s : new int[] {1, -1}) {
				int start = -z0 * s;
				int a = start - 12 * s;
				int b = start + 150 * s;
				flatten(level, c, -14, Math.min(a, b) - 2, 14, Math.max(a, b) + 2, 20, Blocks.GRASS_BLOCK.defaultBlockState());
				box(level, c, -7, 0, a, 7, 0, b, runway);
				box(level, c, -7, 0, a, -7, 0, b, edge);
				box(level, c, 7, 0, a, 7, 0, b, edge);
				for (int z = Math.min(a, b) + 4; z < Math.max(a, b); z += 12) {
					box(level, c, 0, 0, z, 0, 0, z + 5, stripe);
				}
				box(level, c, -6, 0, a, 6, 0, a + 2 * s, s > 0 ? Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState() : Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.BLUE).defaultBlockState());
				// A sandbagged AA pit next to the runway (bring your Heavy AA Gun).
				box(level, c, 11, 1, start + 8 * s, 15, 1, start + 8 * s, Blocks.SMOOTH_SANDSTONE.defaultBlockState());
				box(level, c, 11, 1, start + 14 * s, 15, 1, start + 14 * s, Blocks.SMOOTH_SANDSTONE.defaultBlockState());
			}
		}
	}

	/** Item list for /duel help. */
	public static String kitSummary(Mode mode) {
		return switch (mode) {
			case GUNS -> "every gun, rocket launcher, Lancer, Skyhammer, grenades, C4, breaching charges, an air strike flare and a recon drone";
			case VEHICLES -> "a tank to start in, a jeep, a railgun, the Lancer, rocket launcher, C4 and AT mines";
			case PLANES -> "a fighter jet to start in, attack jet, bomber, attack helicopter, Skyhammer AA launcher and a Heavy AA Gun";
		};
	}
}
