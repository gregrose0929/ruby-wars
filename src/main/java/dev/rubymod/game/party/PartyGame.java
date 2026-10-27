package dev.rubymod.game.party;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.entity.SoldierEntity;
import dev.rubymod.game.Alerts;
import dev.rubymod.game.Duels;
import dev.rubymod.game.Hub;
import dev.rubymod.game.SpawnShield;
import dev.rubymod.gun.GunItem;
import dev.rubymod.registry.ModComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.TeamColor;

/**
 * A party game: a short round-based mode reached from the hub (portal or H). Rounds
 * loop: a lobby (15-second countdown once there are enough players, or right away with
 * AI soldiers filling in where a game supports them), the round, 8 seconds of results,
 * then the next round's lobby, until everyone has left. Inventories are the game's own:
 * your Ruby Wars things stay saved in the hub's save, and nothing comes back.
 *
 * Each game lives in its own corner of the arena dimension (Ruby Rush has its own
 * island dimension), and is rebuilt before every round.
 */
public abstract class PartyGame {
	public final String id;
	public final String title;
	public final String about;
	public final String icon;
	protected final int minPlayers;
	/** Missing players are filled in with AI soldiers. */
	protected final boolean bots;
	/** People who join during a round play right away (otherwise they watch until the next one). */
	protected final boolean joinMidRound;
	protected final boolean teams;

	protected enum Phase { IDLE, LOBBY, PLAYING, ENDING }

	protected Phase phase = Phase.IDLE;
	protected int timer;
	/** Ticks since the round started. */
	protected int roundTicks;
	protected MinecraftServer server;
	protected final Map<UUID, Part> players = new LinkedHashMap<>();
	protected final Set<UUID> spawned = new HashSet<>();
	protected final ServerBossEvent bar;
	private int lobbyNag;

	/** One player in the game (and their per-game numbers). */
	protected static final class Part {
		final String name;
		int team;
		int score;
		int kills;
		int deaths;
		int level;
		boolean watching;
		int respawnIn = -1;
		Vec3 last;
		long time;

		Part(String name) {
			this.name = name;
		}
	}

	protected PartyGame(String id, String title, String about, String icon, int minPlayers, boolean bots, boolean joinMidRound, boolean teams) {
		this.id = id;
		this.title = title;
		this.about = about;
		this.icon = icon;
		this.minPlayers = minPlayers;
		this.bots = bots;
		this.joinMidRound = joinMidRound;
		this.teams = teams;
		this.bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(title), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
	}

	// ----- Where the game is -----

	protected ServerLevel level() {
		return server.getLevel(Duels.ARENA);
	}

	/** The middle of this game's arena (y = standing height on the arena floor). */
	protected abstract BlockPos center();

	/** How far out from the middle this game's things reach (for clearing up). */
	protected int reach() {
		return 80;
	}

	protected String tag() {
		return "rubymod_party_" + id;
	}

	// ----- What each game fills in -----

	/** Build (or repair) the arena before a round. */
	protected abstract void build(ServerLevel level);

	/** The round begins: teams, kits, spawns. */
	protected abstract void startRound();

	/** Put a player into play (start of round, mid-round join, or respawn). */
	protected abstract void spawn(ServerPlayer player, Part part);

	/** Every tick of a round. */
	protected void tickRound() {
	}

	/** A player would die. Default: down for 3 seconds, then back. Return false to let them watch. */
	protected void onDowned(ServerPlayer victim, Part part, Entity killer) {
		part.respawnIn = 60;
	}

	/** An entity this game spawned (a bot, a vehicle...) died. */
	protected void onEntityDied(LivingEntity entity, DamageSource source) {
	}

	/** A player left (by choice or disconnect). */
	protected void onLeft(UUID id, Part part) {
	}

	/** Line under the title in the picker and on the bar while playing. */
	protected String roundStatus() {
		return activeCount() + " playing";
	}

	protected String teamName(int team) {
		return team == 0 ? "Red" : "Blue";
	}

	// ----- Lifecycle -----

	public void attach(MinecraftServer server) {
		this.server = server;
	}

	public void reset() {
		phase = Phase.IDLE;
		players.clear();
		spawned.clear();
		bar.removeAllPlayers();
	}

	public boolean contains(Player player) {
		return players.containsKey(player.getUUID());
	}

	public boolean running() {
		return phase != Phase.IDLE;
	}

	public String status() {
		return switch (phase) {
			case IDLE -> "Nobody playing";
			case LOBBY -> players.size() < minPlayers && !bots ? "Waiting for players (" + players.size() + "/" + minPlayers + ")"
					: "Starting soon  •  " + players.size() + " in";
			case PLAYING -> roundStatus();
			case ENDING -> "Next round soon  •  " + players.size() + " in";
		};
	}

	public String canJoin(ServerPlayer player) {
		if (level() == null) {
			return "This game's world is missing.";
		}
		return null;
	}

	public void join(ServerPlayer player) {
		if (phase == Phase.IDLE) {
			openLobby();
		}
		Part part = new Part(player.getName().getString());
		players.put(player.getUUID(), part);
		bar.addPlayer(player);
		player.stopRiding();
		clearPlayer(player);
		if (phase == Phase.PLAYING) {
			if (joinMidRound) {
				part.team = smallerTeam();
				joinTeam(player, part.team);
				spawn(player, part);
				tell(Component.literal(part.name + " joined " + title + "!").withStyle(ChatFormatting.GREEN));
			} else {
				part.watching = true;
				watch(player, center().above(8));
				player.sendSystemMessage(Component.literal("A round is on: watch this one, you're in the next.").withStyle(ChatFormatting.GRAY));
			}
		} else {
			toLobby(player);
			tell(Component.literal(part.name + " joined " + title + ".").withStyle(ChatFormatting.GREEN));
		}
		player.sendSystemMessage(Component.literal(title + ": " + about + " (H or /hub to leave.)").withStyle(ChatFormatting.GOLD));
		updateBar();
	}

	/** Out of the game: its gear stays behind. */
	public void leave(ServerPlayer player) {
		drop(player.getUUID(), player);
		clearPlayer(player);
	}

	public void onDisconnect(ServerPlayer player) {
		drop(player.getUUID(), player);
	}

	private void drop(UUID id, ServerPlayer player) {
		Part part = players.remove(id);
		bar.removePlayer(player);
		leaveTeams(player);
		if (part == null || !server.isRunning()) {
			return;
		}
		tell(Component.literal(part.name + " left " + title + ".").withStyle(ChatFormatting.GRAY));
		if (players.isEmpty()) {
			stop();
			return;
		}
		if (phase == Phase.PLAYING) {
			onLeft(id, part);
		}
	}

	private void openLobby() {
		phase = Phase.LOBBY;
		timer = 15 * 20;
		lobbyNag = 0;
		clearArea();
		queueBuild();
	}

	// ----- Building a little at a time (no server freezes) -----

	/** Block changes still to make, done a few milliseconds' worth per tick. */
	private final java.util.ArrayDeque<Runnable> work = new java.util.ArrayDeque<>();
	private boolean queueing;
	/** When this tick's building time runs out (shared by all the games). */
	static long budgetEnd;

	/** Queue up building the arena (it happens over the next few seconds). */
	public void queueBuild() {
		if (level() == null) {
			return;
		}
		// A build still underway (the one at server start) is replaced, not doubled.
		work.clear();
		queueing = true;
		try {
			build(level());
		} finally {
			queueing = false;
		}
	}

	/** Run something after the building queued so far (or now, outside a build). */
	protected void later(Runnable task) {
		if (queueing) {
			work.add(task);
		} else {
			task.run();
		}
	}

	protected boolean building() {
		return !work.isEmpty();
	}

	/** Build for up to sliceNanos (within this tick's shared budget). */
	void runWork(long sliceNanos) {
		if (work.isEmpty()) {
			return;
		}
		long end = Math.min(budgetEnd, System.nanoTime() + sliceNanos);
		while (!work.isEmpty() && System.nanoTime() < end) {
			work.poll().run();
		}
		if (work.isEmpty()) {
			dev.rubymod.game.Arenas.markBuilt(id);
		}
	}

	private void stop() {
		phase = Phase.IDLE;
		bar.removeAllPlayers();
		clearArea();
		removeTeams();
	}

	protected void clearArea() {
		ServerLevel level = level();
		if (level == null) {
			return;
		}
		BlockPos c = center();
		int r = reach();
		for (Entity e : level.getEntities((Entity) null, new AABB(c.getX() - r, -64, c.getZ() - r, c.getX() + r, 400, c.getZ() + r),
				e -> !(e instanceof Player))) {
			e.discard();
		}
		spawned.clear();
	}

	/** Lobby spot: in the middle of the arena, waiting. */
	protected void toLobby(ServerPlayer player) {
		BlockPos c = center();
		teleport(player, c.getX() + 0.5 + player.getRandom().nextGaussian(), c.getY(), c.getZ() + 0.5 + player.getRandom().nextGaussian(), 0);
		player.setGameMode(GameType.ADVENTURE);
		player.setHealth(player.getMaxHealth());
	}

	public void tick() {
		if (server == null) {
			return;
		}
		if (phase == Phase.IDLE) {
			return;
		}
		if (players.isEmpty()) {
			stop();
			return;
		}
		unstick();
		switch (phase) {
			case LOBBY -> tickLobby();
			case PLAYING -> {
				roundTicks++;
				for (ServerPlayer p : online()) {
					Part part = players.get(p.getUUID());
					if (part.respawnIn > 0) {
						if (part.respawnIn % 20 == 0) {
							p.sendOverlayMessage(Component.literal("Back in " + part.respawnIn / 20 + "...").withStyle(ChatFormatting.YELLOW));
						}
						if (--part.respawnIn == 0) {
							part.respawnIn = -1;
							spawn(p, part);
						}
					}
				}
				tickRound();
				if (phase == Phase.PLAYING && roundTicks % 20 == 0) {
					updateBar();
				}
			}
			case ENDING -> {
				if (--timer <= 0) {
					openLobby();
					for (ServerPlayer p : online()) {
						Part part = players.get(p.getUUID());
						Part fresh = new Part(part.name);
						players.put(p.getUUID(), fresh);
						clearPlayer(p);
						toLobby(p);
					}
					updateBar();
				}
			}
			default -> {
			}
		}
	}

	private void tickLobby() {
		if (building()) {
			if (lobbyNag++ % 20 == 0) {
				bar.setName(Component.literal(title + "   \u2022   Building the arena..."));
			}
			return;
		}
		boolean enough = players.size() >= minPlayers || bots;
		if (!enough) {
			timer = 15 * 20;
			if (lobbyNag++ % 200 == 0) {
				tell(Component.literal("Waiting for " + (minPlayers - players.size()) + " more player" + (minPlayers - players.size() == 1 ? "" : "s")
						+ " to start " + title + ". Tell someone to join (H)!").withStyle(ChatFormatting.YELLOW));
			}
			if (lobbyNag % 20 == 0) {
				updateBar();
			}
			return;
		}
		timer--;
		if (timer % 20 == 0) {
			int seconds = timer / 20;
			if (seconds <= 5 && seconds > 0) {
				for (ServerPlayer p : online()) {
					Alerts.title(p, String.valueOf(seconds), title, ChatFormatting.YELLOW);
					p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 0.8F, 1.0F);
				}
			}
			updateBar();
		}
		if (timer <= 0) {
			phase = Phase.PLAYING;
			roundTicks = 0;
			int i = 0;
			for (ServerPlayer p : online()) {
				Part part = players.get(p.getUUID());
				part.team = teams ? i++ % 2 : 0;
				joinTeam(p, part.team);
			}
			startRound();
			for (ServerPlayer p : online()) {
				p.level().playSound(null, p.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.PLAYERS, 0.6F, 1.2F);
				Part part = players.get(p.getUUID());
				if (!part.watching && !PartyGames.TEST_PLAYERS.containsKey(p.getUUID())) {
					dev.rubymod.game.Stats.played(p.getUUID(), part.name, id);
				}
			}
			updateBar();
		}
	}

	/** The round is over (nobody in particular won). */
	protected void endRound(String headline, String detail, ChatFormatting color) {
		endRound(headline, detail, color, List.of());
	}

	/** The round is over: count it in everyone's stats, show the result, then the next round's lobby. */
	protected void endRound(String headline, String detail, ChatFormatting color, java.util.Collection<UUID> winners) {
		if (phase != Phase.PLAYING) {
			return;
		}
		phase = Phase.ENDING;
		for (Map.Entry<UUID, Part> e : players.entrySet()) {
			if (!dev.rubymod.game.party.PartyGames.TEST_PLAYERS.containsKey(e.getKey())) {
				dev.rubymod.game.Stats.partyKills(e.getKey(), e.getValue().name, e.getValue().kills);
			}
		}
		dev.rubymod.game.Stats.forEachWinner(winners, id -> {
			Part part = players.get(id);
			if (part != null && !dev.rubymod.game.party.PartyGames.TEST_PLAYERS.containsKey(id)) {
				dev.rubymod.game.Stats.won(id, part.name, this.id);
			}
		});
		timer = 8 * 20;
		for (UUID id : new ArrayList<>(spawned)) {
			Entity e = level().getEntity(id);
			if (e != null && !(e instanceof Player)) {
				e.discard();
			}
		}
		spawned.clear();
		for (ServerPlayer p : online()) {
			Alerts.title(p, headline, detail, color);
			p.sendSystemMessage(Component.literal("====== " + title.toUpperCase() + ": " + headline + " ======").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			p.sendSystemMessage(Component.literal(detail).withStyle(ChatFormatting.WHITE));
			p.sendSystemMessage(Component.literal("Next round in 8 seconds (H to leave).").withStyle(ChatFormatting.GRAY));
			p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8F, 1.0F);
			p.setGameMode(GameType.SPECTATOR);
		}
		bar.setName(Component.literal(title + ": " + headline));
		bar.setProgress(1);
	}

	protected void updateBar() {
		switch (phase) {
			case LOBBY -> {
				boolean enough = players.size() >= minPlayers || bots;
				bar.setName(Component.literal(title + "   •   " + (enough ? "Starting in " + Math.max(0, timer / 20)
						: "Waiting for players (" + players.size() + "/" + minPlayers + ")")));
				bar.setColor(BossEvent.BossBarColor.PURPLE);
				bar.setProgress(enough ? Math.max(0, timer) / 300.0F : players.size() / (float) minPlayers);
			}
			case PLAYING -> {
				bar.setName(Component.literal(title + "   •   " + roundStatus()));
				bar.setColor(BossEvent.BossBarColor.YELLOW);
				bar.setProgress(barProgress());
			}
			default -> {
			}
		}
	}

	protected float barProgress() {
		return 1.0F;
	}

	// ----- Deaths -----

	/** A player in this game would die: handled here instead. */
	public boolean onDeath(ServerPlayer victim, DamageSource source) {
		Part part = players.get(victim.getUUID());
		if (part == null) {
			return false;
		}
		victim.stopRiding();
		victim.setHealth(victim.getMaxHealth());
		victim.removeAllEffects();
		victim.clearFire();
		victim.setGameMode(GameType.SPECTATOR);
		if (phase != Phase.PLAYING || part.watching || part.respawnIn > 0) {
			return true;
		}
		part.deaths++;
		Entity killer = source.getEntity();
		if (killer instanceof ServerPlayer k && players.containsKey(k.getUUID()) && k != victim) {
			players.get(k.getUUID()).kills++;
		}
		onDowned(victim, part, killer);
		return true;
	}

	public void entityDied(LivingEntity entity, DamageSource source) {
		if (spawned.remove(entity.getUUID())) {
			onEntityDied(entity, source);
		}
	}

	/** Player-vs-player damage inside this game: same team never, otherwise only during a round. */
	public Boolean canHurt(Player attacker, Player victim) {
		Part a = players.get(attacker.getUUID());
		Part v = players.get(victim.getUUID());
		if (a == null && v == null) {
			return null;
		}
		if (a == null || v == null || phase != Phase.PLAYING || a.watching || v.watching) {
			return false;
		}
		return !teams || a.team != v.team;
	}

	// ----- Helpers -----

	protected List<ServerPlayer> online() {
		List<ServerPlayer> list = new ArrayList<>();
		for (UUID id : players.keySet()) {
			ServerPlayer p = PartyGames.player(server, id);
			if (p != null) {
				list.add(p);
			}
		}
		return list;
	}

	/** Bots switched on for this game in the game picker (saved). Off, only a lone player gets bots. */
	boolean botsOn;

	/** This game can fill in with bots. */
	protected boolean hasBots() {
		return false;
	}

	/** Bring bots in this round: switched on, or someone's playing alone. */
	protected boolean useBots() {
		return botsOn || activeCount() <= 1;
	}

	protected int activeCount() {
		return (int) players.values().stream().filter(p -> !p.watching).count();
	}

	protected void tell(Component message) {
		for (ServerPlayer p : online()) {
			p.sendSystemMessage(message);
		}
	}

	protected void titleAll(String title, String sub, ChatFormatting color) {
		for (ServerPlayer p : online()) {
			Alerts.title(p, title, sub, color);
		}
	}

	protected void teleport(ServerPlayer player, double x, double y, double z, float yaw) {
		player.teleportTo(level(), x, Hub.safeY(level(), x, y, z), z, Set.<Relative>of(), yaw, 0.0F, true);
		player.resetFallDistance();
	}

	/** Anyone inside blocks (an arena rebuilt around them, a bad spawn) is lifted out on top. */
	private void unstick() {
		ServerLevel level = level();
		for (ServerPlayer p : online()) {
			if (!p.isSpectator() && p.getVehicle() == null && p.level() == level && !level.noCollision(p, p.getBoundingBox().deflate(0.1))) {
				teleport(p, p.getX(), p.getY(), p.getZ(), p.getYRot());
			}
		}
	}

	/** Back into play: survival, full health, night vision, a moment of spawn protection. */
	protected void ready(ServerPlayer player) {
		player.setGameMode(GameType.SURVIVAL);
		player.setHealth(player.getMaxHealth());
		player.removeAllEffects();
		player.clearFire();
		player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, -1, 0, false, false));
		SpawnShield.grant(player);
	}

	protected void watch(ServerPlayer player, BlockPos at) {
		teleport(player, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0);
		player.setGameMode(GameType.SPECTATOR);
	}

	protected static void clearPlayer(ServerPlayer player) {
		player.getInventory().clearContent();
		player.removeAllEffects();
		player.clearFire();
		player.inventoryMenu.broadcastChanges();
	}

	/** A weapon ready to fire (guns come loaded; anything else is just the item). */
	protected static ItemStack gun(Item item) {
		ItemStack stack = new ItemStack(item);
		if (item instanceof GunItem g) {
			stack.set(ModComponents.AMMO, g.stats().magazine());
		}
		return stack;
	}

	protected static void give(ServerPlayer player, ItemStack... stacks) {
		for (ItemStack stack : stacks) {
			if (!player.getInventory().add(stack)) {
				player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
			}
		}
		player.inventoryMenu.broadcastChanges();
	}

	protected int smallerTeam() {
		if (!teams) {
			return 0;
		}
		long red = players.values().stream().filter(p -> !p.watching && p.team == 0).count();
		long blue = players.values().stream().filter(p -> !p.watching && p.team == 1).count();
		return red <= blue ? 0 : 1;
	}

	/** Everyone on a team (not watching). */
	protected List<UUID> teamIds(int team) {
		List<UUID> ids = new ArrayList<>();
		for (Map.Entry<UUID, Part> e : players.entrySet()) {
			if (!e.getValue().watching && e.getValue().team == team) {
				ids.add(e.getKey());
			}
		}
		return ids;
	}

	protected UUID idOf(Part part) {
		for (Map.Entry<UUID, Part> e : players.entrySet()) {
			if (e.getValue() == part) {
				return e.getKey();
			}
		}
		return null;
	}

	protected static List<UUID> one(UUID id) {
		return id == null ? List.of() : List.of(id);
	}

	protected Optional<Map.Entry<UUID, Part>> leader() {
		return players.entrySet().stream().max(Comparator.comparingInt(e -> e.getValue().score));
	}

	/** An AI soldier that hunts {@code prey}, counted as this game's. */
	protected SoldierEntity spawnBot(BlockPos at, ServerPlayer prey, String name) {
		SoldierEntity bot = SoldierEntity.duelBot(level(), at, prey);
		if (bot == null) {
			return null;
		}
		bot.setCustomName(Component.literal(name).withStyle(ChatFormatting.RED));
		bot.addTag(tag());
		level().addFreshEntity(bot);
		spawned.add(bot.getUUID());
		return bot;
	}

	// ----- Teams (scoreboard, for name colors and hidden name tags) -----

	private String scoreboardTeam(int team) {
		return ("rw_" + id).substring(0, Math.min(12, ("rw_" + id).length())) + team;
	}

	protected void joinTeam(ServerPlayer player, int team) {
		if (!teams && !hideNames()) {
			return;
		}
		var scoreboard = server.getScoreboard();
		String name = scoreboardTeam(team);
		PlayerTeam t = scoreboard.getPlayerTeam(name);
		if (t == null) {
			t = scoreboard.addPlayerTeam(name);
		}
		t.setColor(Optional.of(teams ? (team == 0 ? TeamColor.RED : TeamColor.BLUE) : TeamColor.WHITE));
		t.setAllowFriendlyFire(!teams);
		t.setNameTagVisibility(hideNames() ? Team.Visibility.NEVER : Team.Visibility.ALWAYS);
		scoreboard.addPlayerToTeam(player.getScoreboardName(), t);
	}

	/** A bot on a team: colored name and glow, like the players. */
	protected void joinTeam(Entity entity, int team) {
		var scoreboard = server.getScoreboard();
		String name = scoreboardTeam(team);
		PlayerTeam t = scoreboard.getPlayerTeam(name);
		if (t == null) {
			t = scoreboard.addPlayerTeam(name);
			t.setColor(Optional.of(team == 0 ? TeamColor.RED : TeamColor.BLUE));
		}
		scoreboard.addPlayerToTeam(entity.getScoreboardName(), t);
	}

	protected boolean hideNames() {
		return false;
	}

	protected void leaveTeams(ServerPlayer player) {
		var scoreboard = server.getScoreboard();
		for (int i = 0; i < 2; i++) {
			PlayerTeam t = scoreboard.getPlayerTeam(scoreboardTeam(i));
			if (t != null && t.getPlayers().contains(player.getScoreboardName())) {
				scoreboard.removePlayerFromTeam(player.getScoreboardName(), t);
			}
		}
	}

	private void removeTeams() {
		var scoreboard = server.getScoreboard();
		for (int i = 0; i < 2; i++) {
			PlayerTeam t = scoreboard.getPlayerTeam(scoreboardTeam(i));
			if (t != null) {
				scoreboard.removePlayerTeam(t);
			}
		}
	}

	// ----- Building -----

	protected static final int GROUND = Duels.GROUND;

	/** Set a block relative to the arena middle (y relative to the ground surface). */
	protected void set(ServerLevel level, int x, int y, int z, BlockState state) {
		BlockPos pos = new BlockPos(center().getX() + x, GROUND + y, center().getZ() + z);
		if (queueing) {
			work.add(() -> setNow(level, pos, state));
		} else {
			setNow(level, pos, state);
		}
	}

	private static void setNow(ServerLevel level, BlockPos pos, BlockState state) {
		if (level.getBlockState(pos) != state) {
			level.setBlock(pos, state, Block.UPDATE_CLIENTS);
		}
	}

	protected void box(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
					set(level, x, y, z, state);
				}
			}
		}
	}

	/** Load the chunks around the arena and flatten it: grass floor, clear air up to {@code height}, barrier walls at {@code half}. */
	protected void flatArena(ServerLevel level, int halfX, int halfZ, int height, BlockState floor, boolean walls) {
		BlockPos c = center();
		for (int cx = (c.getX() - halfX - 4) >> 4; cx <= (c.getX() + halfX + 4) >> 4; cx++) {
			for (int cz = (c.getZ() - halfZ - 4) >> 4; cz <= (c.getZ() + halfZ + 4) >> 4; cz++) {
				int fcx = cx;
				int fcz = cz;
				later(() -> level.getChunk(fcx, fcz));
			}
		}
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState dirt = Blocks.DIRT.defaultBlockState();
		BlockState barrier = Blocks.BARRIER.defaultBlockState();
		for (int x = -halfX - 2; x <= halfX + 2; x++) {
			for (int z = -halfZ - 2; z <= halfZ + 2; z++) {
				boolean edge = Math.abs(x) > halfX || Math.abs(z) > halfZ;
				int fx = x;
				int fz = z;
				// One column per task.
				later(() -> {
					for (int y = -2; y <= height; y++) {
						BlockState want = y < 0 ? dirt : y == 0 ? floor : walls && edge ? barrier : air;
						setNow(level, new BlockPos(c.getX() + fx, GROUND + y, c.getZ() + fz), want);
					}
				});
			}
		}
	}

	/** Leave to the hub after a round (used when a game can't go on). */
	protected void sendAllToHub() {
		for (ServerPlayer p : online()) {
			players.remove(p.getUUID());
			bar.removePlayer(p);
			leaveTeams(p);
			clearPlayer(p);
			Hub.enterHub(p);
		}
		stop();
	}

	// ----- For autotests -----

	public void skipLobby() {
		if (phase == Phase.LOBBY) {
			timer = 21;
		}
	}

	/** Jump the round clock (to just before something happens). */
	public void fastForward(int toTick) {
		if (phase == Phase.PLAYING && toTick > roundTicks) {
			roundTicks = toTick;
		}
	}

	public void forceTeam(ServerPlayer player, int team) {
		Part part = players.get(player.getUUID());
		if (part != null) {
			part.team = team;
			spawn(player, part);
		}
	}

	public String debugState() {
		return id + " " + phase + " t" + roundTicks + " | " + status() + " | " + players.values().stream()
				.map(p -> p.name + "(team " + p.team + " score " + p.score + " kills " + p.kills + " lvl " + p.level + (p.watching ? " watching" : "") + ")")
				.toList() + " | spawned " + spawned.size();
	}

	// ----- Hooks the rest of the mod asks about -----

	/** May this player use this item right now (no explosives in Build Battle, etc.)? */
	public boolean allowsItemUse(ServerPlayer player, ItemStack stack) {
		return true;
	}

	/** May this player fire guns right now? */
	public boolean allowsGuns(ServerPlayer player) {
		return true;
	}

	/** Right-clicking a block: return true when the game handled it (cancel the normal action). */
	public boolean onUseBlock(ServerPlayer player, BlockPos pos, ItemStack held, BlockPos placeAt) {
		return false;
	}

	/** May this player break that block? */
	public boolean allowsBreak(ServerPlayer player, BlockPos pos) {
		return true;
	}
}
