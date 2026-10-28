package dev.rubymod.game.rift;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.RubyMod;
import dev.rubymod.game.Alerts;
import dev.rubymod.game.Duels;
import dev.rubymod.game.Hub;
import dev.rubymod.game.SpawnShield;
import dev.rubymod.gun.GunItem;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.mixin.ItemDisplayAccessor;
import dev.rubymod.mixin.TextDisplayAccessor;
import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModItems;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
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
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Ruby Rift: a co-op roguelike for 1-4 players. A run is one descent through the Rift:
 * pick your route on a branching map, fight through freshly built rooms, and after each
 * one choose a relic (one of three) that changes how you play for the rest of the run.
 * Elites, a merchant, mystery events, the Arsenal (new weapons), treasure, and a boss at the bottom (the
 * Juggernaut Mech). Go down and a teammate can bring you back; if everyone's down the
 * run is over. Your Ruby Wars things are safe in the hub the whole time.
 */
public final class Rift {
	public static final String TAG = "rubymod_rift";
	private static final int X = -90000;
	private static final int GROUND = Duels.GROUND;
	private static final BlockPos THRESHOLD = new BlockPos(X, GROUND, -400);
	private static final BlockPos[] SLOTS = {new BlockPos(X, GROUND, 0), new BlockPos(X, GROUND, 400)};
	private static final int STAGING_TICKS = 40 * 20;
	private static final int VOTE_TICKS = 25 * 20;
	private static final int REWARD_TICKS = 45 * 20;

	enum State { IDLE, STAGING, VOTE, TRAVEL, FIGHT, CALM, OVER }

	static final class Runner {
		final UUID id;
		final String name;
		final List<Relic> relics = new ArrayList<>();
		boolean alive = true;
		Vec3 downAt;
		int revive;
		UUID marker;
		List<Relic> offer = List.of();
		int vote = -1;
		int kills;
		boolean placed;
		RelicEffects.State fx = new RelicEffects.State();
		/** Event rooms: this runner's options (and whether they've chosen). */
		List<String> event = List.of();
		/** Damage dealt to the Rift's monsters this run (for the summary). */
		float damage;
		/** Shrine of Blessings: "speed", "health", "power" (for the run). */
		final Set<String> blessings = new java.util.HashSet<>();
		/** The active ability (Z), and when it's ready again. */
		RiftAbilities.Ability ability = RiftAbilities.Ability.DASH;
		long abilityReadyAt;

		Runner(UUID id, String name) {
			this.id = id;
			this.name = name;
		}

		/** Gambler's Dice: this room's boosted relic and switched-off one. */
		Relic boosted;
		Relic disabled;

		boolean has(Relic r) {
			return relics.contains(r) && r != disabled;
		}

		/** Relics combined: how many stars (2 or 3); 1 for an ordinary one. */
		final Map<Relic, Integer> levels = new HashMap<>();

		/** 0 without it; 1, 2 or 3 with it. */
		int level(Relic r) {
			return has(r) ? levels.getOrDefault(r, 1) + (r == boosted ? 1 : 0) : 0;
		}

		boolean canCombine(Relic r) {
			return relics.contains(r) && levels.getOrDefault(r, 1) < r.maxLevel();
		}
	}

	static final Map<UUID, Runner> RUNNERS = new LinkedHashMap<>();
	static final Set<UUID> MOBS = new HashSet<>();
	private static final List<Pending> PENDING = new ArrayList<>();
	static final ArrayDeque<Runnable> WORK = new ArrayDeque<>();
	private static final ServerBossEvent BAR = new ServerBossEvent(UUID.randomUUID(), Component.literal("Ruby Rift"), BossEvent.BossBarColor.RED,
			BossEvent.BossBarOverlay.NOTCHED_10);

	static State state = State.IDLE;
	private static int timer;
	static RiftMap map;
	static RiftMap.Node node;
	static RiftRoom room;
	private static int slot;
	static int gold;
	private static boolean secondWind = true;
	private static boolean timeLoopUsed;
	private static int wave;
	private static int waves;
	private static int waveBudget;
	private static boolean portalOpen;
	private static UUID portalDisplay;
	private static RiftBoss boss;
	/** Which act (1-3); each ends with its own boss. */
	static int act = 1;
	/** The act's boss is down: the portal leads to the next act. */
	private static boolean nextAct;
	/** This run so far (for Rift Shards and the Daily Rift score). */
	private static int roomsCleared;
	private static int elitesKilled;
	private static int bossesKilled;
	private static long seed;
	private static int ticks;
	/** Room events and the shop: what's for sale ("relic id" -> price), shared. */
	private static final Map<Relic, Integer> SHOP = new LinkedHashMap<>();
	private static final Map<UUID, Integer> STRAY_CHECK = new HashMap<>();

	/** A monster about to come through a tear in the Rift. */
	private record Pending(BlockPos at, RiftEnemies.Kind kind, RiftEnemies.Twist twist, int[] left, boolean tank) {
		Pending(BlockPos at, RiftEnemies.Kind kind, RiftEnemies.Twist twist, int[] left) {
			this(at, kind, twist, left, false);
		}
	}

	/** How deep the team is overall (act and floor): what makes monsters tougher. */
	static int depth() {
		return (act - 1) * (RiftMap.FLOORS + 1) + (node == null ? 0 : node.floor);
	}

	/** Bring a monster in at {@code at} (bosses calling for help): a trooper, or one of the room's own. */
	static void summon(BlockPos at, boolean trooper) {
		if (room == null || node == null) {
			return;
		}
		PENDING.add(new Pending(at, trooper ? RiftEnemies.TROOPER : RiftEnemies.pick(node.theme, RandomSource.create()), null, new int[] {25}));
	}

	/** Has this player unlocked a locked relic (with Rift Shards)? */
	static boolean unlocked(UUID player, String relic) {
		var rec = dev.rubymod.game.Stats.get(player, null);
		return rec.riftUnlocks != null && rec.riftUnlocks.contains(relic);
	}

	/** The Rift's rooms: nothing in them can be broken. */
	public static boolean protects(net.minecraft.world.level.Level level, BlockPos pos) {
		return level.dimension() == Duels.ARENA && Math.abs(pos.getX() - X) <= 160;
	}

	/** True while hurting {@code e} is pointless (the Ruby Titan behind its pylons). */
	public static boolean shielded(Entity e) {
		return boss != null && boss.shielded(e);
	}

	/** The same, telling the boss what bounced off its shield. */
	public static boolean shielded(Entity e, DamageSource source, float amount) {
		if (boss != null && boss.shielded(e)) {
			if (e.level() instanceof ServerLevel level) {
				boss.blocked(level, e, source, amount);
			}
			return true;
		}
		return false;
	}

	private Rift() {
	}

	// ----- Who's in -----

	public static boolean playing(Player player) {
		return RUNNERS.containsKey(player.getUUID());
	}

	static Runner runner(Entity e) {
		return e == null ? null : RUNNERS.get(e.getUUID());
	}

	/** For autotests: one of the players' allies (golems, clones, the risen)? */
	public static boolean isAllyEntity(Entity e) {
		return RelicEffects.isAlly(e);
	}

	public static boolean isEnemy(Entity e) {
		return e != null && MOBS.contains(e.getUUID());
	}

	/** Runners never hurt each other (null: not a Rift matter). */
	public static Boolean canHurt(Player a, Player b) {
		return playing(a) || playing(b) ? Boolean.FALSE : null;
	}

	static List<ServerPlayer> players(MinecraftServer server) {
		List<ServerPlayer> out = new ArrayList<>();
		for (UUID id : RUNNERS.keySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) {
				out.add(p);
			}
		}
		return out;
	}

	static ServerLevel level(MinecraftServer server) {
		return server.getLevel(Duels.ARENA);
	}

	public static String status() {
		return switch (state) {
			case IDLE -> "Nobody in the Rift";
			case STAGING -> RUNNERS.size() + " getting ready";
			case OVER -> "Run ending";
			default -> RUNNERS.size() + " in a run  \u2022  act " + act + ", floor " + (node == null ? 1 : node.floor + 1) + "/" + (RiftMap.FLOORS + 1);
		};
	}

	public static boolean running() {
		return state != State.IDLE && state != State.STAGING;
	}

	public static String canJoin(ServerPlayer player) {
		if (RUNNERS.size() >= 4) {
			return "The Rift run is full (4 players).";
		}
		return null;
	}

	public static void join(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Runner r = new Runner(player.getUUID(), player.getName().getString());
		r.ability = RiftAbilities.Ability.byId(RiftMeta.rec(player).riftAbility);
		RUNNERS.put(player.getUUID(), r);
		BAR.addPlayer(player);
		if (state == State.IDLE) {
			startStaging(server);
		}
		if (state == State.STAGING || state == State.OVER) {
			r.placed = false;
			if (WORK.isEmpty() && room != null) {
				place(player, room.entry);
			} else {
				hold(player);
			}
		} else {
			// Mid-run: watch until the next room, then join in with a free relic.
			r.alive = false;
			hold(player);
			player.sendSystemMessage(Component.literal("A run is going: you'll join in at the next room (with a relic to catch up).")
					.withStyle(ChatFormatting.LIGHT_PURPLE));
		}
		tell(server, Component.literal(player.getName().getString() + " entered the Rift.").withStyle(ChatFormatting.LIGHT_PURPLE));
		player.sendSystemMessage(Component.literal("RUBY RIFT: a co-op roguelike. Fight room to room, pick a relic after each one, and beat the boss at the "
				+ "bottom. If you go down, a teammate can crouch next to you to bring you back. H or /hub to leave.").withStyle(ChatFormatting.LIGHT_PURPLE));
	}

	public static void leave(ServerPlayer player) {
		Runner r = RUNNERS.remove(player.getUUID());
		BAR.removePlayer(player);
		if (r == null) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		removeMarker(server, r);
		RelicEffects.cleanup(player, r);
		player.getInventory().clearContent();
		player.removeAllEffects();
		Net.send(player, new MorePayloads.RiftHud("", 0, List.of(), List.of()));
		Net.send(player, new MorePayloads.RiftScreen("close", List.of()));
		if (RUNNERS.isEmpty()) {
			stop(server);
		} else if (running() && RUNNERS.values().stream().noneMatch(x -> x.alive)) {
			teamDown(server);
		}
	}

	public static void onDisconnect(ServerPlayer player) {
		if (playing(player)) {
			leave(player);

		}
	}

	/** The chunks of the room in play: kept loaded, so a boss or pylon in a far corner is really there. */
	private static final List<long[]> HELD = new ArrayList<>();

	private static void hold(ServerLevel level, RiftRoom r) {
		release(level);
		int pad = r.half + 4;
		for (int cx = (r.center.getX() - pad) >> 4; cx <= (r.center.getX() + pad) >> 4; cx++) {
			for (int cz = (r.center.getZ() - pad) >> 4; cz <= (r.center.getZ() + pad) >> 4; cz++) {
				level.setChunkForced(cx, cz, true);
				HELD.add(new long[] {cx, cz});
			}
		}
	}

	private static void release(ServerLevel level) {
		for (long[] c : HELD) {
			level.setChunkForced((int) c[0], (int) c[1], false);
		}
		HELD.clear();
	}

	/** Everyone out: tidy the Rift. */
	private static void stop(MinecraftServer server) {
		ServerLevel level = level(server);
		release(level);
		clearMobs(level);
		if (boss != null) {
			boss.remove(level);
			boss = null;
		}
		removePortal(level);
		RelicEffects.clearWorld(level);
		RiftAbilities.clear();
		RiftWeapons.clear(level);
		RiftObjectives.clear(level);
		RiftTanks.clear(level);
		state = State.IDLE;
		map = null;
		node = null;
		BAR.removeAllPlayers();
		BAR.setVisible(false);
	}

	public static void reset() {
		HELD.clear();
		RUNNERS.clear();
		MOBS.clear();
		PENDING.clear();
		WORK.clear();
		state = State.IDLE;
		map = null;
		node = null;
		room = null;
		boss = null;
		BAR.removeAllPlayers();
	}

	// ----- Placing people -----

	private static void hold(ServerPlayer player) {
		ServerLevel level = level(player.level().getServer());
		player.teleportTo(level, THRESHOLD.getX() + 0.5, THRESHOLD.getY() + 30, THRESHOLD.getZ() + 0.5, Set.<Relative>of(), 0, 0, true);
		player.setGameMode(GameType.SPECTATOR);
	}

	static void place(ServerPlayer player, Vec3 at) {
		ServerLevel level = level(player.level().getServer());
		Runner r = RUNNERS.get(player.getUUID());
		RandomSource random = player.getRandom();
		double x = at.x + random.nextInt(5) - 2;
		double z = at.z - random.nextInt(2);
		player.teleportTo(level, x, Hub.safeY(level, x, at.y, z), z, Set.<Relative>of(), 180, 0, true);
		player.setGameMode(GameType.ADVENTURE);
		player.clearFire();
		player.resetFallDistance();
		SpawnShield.grant(player);
		if (r != null) {
			r.placed = true;
			r.alive = true;
		}
	}

	private static ItemStack gun(Item item) {
		ItemStack s = new ItemStack(item);
		s.set(ModComponents.AMMO, ((GunItem) item).stats().magazine());
		return s;
	}

	/** The starting kit (and a fresh one between runs). */
	private static void kit(ServerPlayer player) {
		player.getInventory().clearContent();
		player.removeAllEffects();
		player.getInventory().add(gun(ModItems.ARR_RIFLE));
		player.getInventory().add(gun(ModItems.COMBAT_SHOTGUN));
		player.getInventory().add(new ItemStack(ModItems.RUBY_SWORD));
		player.getInventory().add(new ItemStack(ModItems.FRAG_GRENADE, 3));
		ammo(player);
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
		player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
		player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
		player.getInventory().setSelectedSlot(0);
		player.setHealth(player.getMaxHealth());
	}

	/** Topped up with ammo. */
	/** Ammo never runs out in the Rift, so the bags are cleared of it: the hotbar is for weapons. */
	private static void ammo(ServerPlayer player) {
		var inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(ModItems.LIGHT_AMMO) || s.is(ModItems.HEAVY_AMMO) || s.is(ModItems.SHOTGUN_SHELL) || s.is(ModItems.SNIPER_ROUND)
					|| s.is(ModItems.RUBY_CELL) || s.is(ModItems.ROCKET) || s.is(ModItems.AA_MISSILE) || s.is(ModItems.AT_MISSILE)) {
				inv.setItem(i, ItemStack.EMPTY);
			}
		}
		player.inventoryMenu.broadcastChanges();
	}

	// ----- The run -----

	private static void startStaging(MinecraftServer server) {
		ServerLevel level = level(server);
		state = State.STAGING;
		timer = STAGING_TICKS;
		gold = 0;
		secondWind = true;
		timeLoopUsed = false;
		seed = level.getRandom().nextLong();
		dropBoss(level);
		clearMobs(level);
		removePortal(level);
		room = RiftRoom.build(WORK, level, THRESHOLD, RiftRoom.Theme.THRESHOLD, RiftMap.Type.REST, seed);
		// Kept loaded while it's in play (the builder loads it bit by bit; this keeps it there).
		hold(level, room);
		BAR.setVisible(true);
	}

	public static void tick(MinecraftServer server) {
		ticks++;
		if (!WORK.isEmpty()) {
			long end = System.nanoTime() + 8_000_000L;
			while (!WORK.isEmpty() && System.nanoTime() < end) {
				WORK.poll().run();
			}
			if (WORK.isEmpty()) {
				roomReady(server);
			}
		}
		if (state == State.IDLE) {
			return;
		}
		ServerLevel level = level(server);
		if (room != null && WORK.isEmpty() && ticks % 3 == 0) {
			room.ambient(level, level.getRandom());
		}
		tickPending(level);
		for (ServerPlayer p : players(server)) {
			Runner r = RUNNERS.get(p.getUUID());
			RelicEffects.tick(p, r, level);
			if (ticks % 20 == 0) {
				RiftMeta.riftify(p, room == null ? RiftRoom.Theme.THRESHOLD : room.theme);
			}
		}
		RelicEffects.tickWorld(level);
		RiftAbilities.tick(level);
		RiftWeapons.tick(level);
		if (portalOpen) {
			tickPortal(server, level);
		}
		tickRevives(server, level);
		switch (state) {
			case STAGING -> {
				if (WORK.isEmpty()) {
					timer--;
					if (timer % 20 == 0) {
						BAR.setName(Component.literal("Ruby Rift  •  The run starts in " + timer / 20 + "s  (step into the portal to start now)"));
						BAR.setProgress(Math.max(0, timer) / (float) STAGING_TICKS);
					}
					if (timer <= 0) {
						beginRun(server);
					}
				}
			}
			case VOTE -> {
				timer--;
				if (timer % 20 == 0) {
					BAR.setName(Component.literal("Choose the next room  •  " + timer / 20 + "s"));
					BAR.setProgress(Math.max(0, timer) / (float) VOTE_TICKS);
				}
				boolean all = RUNNERS.values().stream().allMatch(r -> r.vote >= 0);
				if (all || timer <= 0) {
					travel(server);
				}
			}
			case FIGHT -> tickFight(server, level);
			case CALM -> {
				if (timer > 0 && --timer == 0) {
					// Out of time to choose: take the first offer.
					for (Runner r : RUNNERS.values()) {
						if (!r.offer.isEmpty()) {
							choose(server, r, r.offer.get(0));
						}
					}
				}
			}
			case OVER -> {
				if (--timer <= 0) {
					backToThreshold(server);
				}
			}
			default -> {
			}
		}
		if (ticks % 10 == 0 || hudSoon) {
			hudSoon = false;
			syncHud(server);
		}
	}

	private static void beginRun(MinecraftServer server) {
		act = 1;
		nextAct = false;
		roomsCleared = 0;
		elitesKilled = 0;
		bossesKilled = 0;
		if (RiftMeta.daily) {
			seed = RiftMeta.dailySeed();
		}
		map = RiftMap.generate(RandomSource.create(seed), act);
		RiftRooms.guardians = 0;
		KEYS.clear();
		FACED.clear();
		RiftEvents.SEEN.clear();
		RiftExtras.newRun();
		endless = 0;
		runStart = ticks;
		heroRooms = 0;
		doubleNext = false;
		doubling = false;
		node = null;
		for (ServerPlayer p : players(server)) {
			RiftMeta.kit(p, RUNNERS.get(p.getUUID()));
			ammo(p);
			RelicEffects.apply(p, RUNNERS.get(p.getUUID()));
			RiftCodex.startPerks(p, RUNNERS.get(p.getUUID()));
			Alerts.title(p, "THE RIFT OPENS", (RiftMeta.daily ? "Today's Daily Rift" : "Choose your path")
					+ (RiftMeta.heat > 0 ? "  \u2022  Heat " + RiftMeta.heat : ""), ChatFormatting.LIGHT_PURPLE);
			p.level().playSound(null, p.blockPosition(), SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 0.7F, 1.2F);
		}
		openVote(server);
	}

	private static void openVote(MinecraftServer server) {
		if (nextAct) {
			nextAct = false;
			act++;
			map = endless > 0 ? RiftMap.endless(RandomSource.create(seed + act * 104729L), endless)
					: RiftMap.generate(RandomSource.create(seed + act * 104729L), act);
			for (ServerPlayer p : players(server)) {
				Alerts.title(p, endless > 0 ? "DEPTH " + endless : act >= 4 ? "THE HEART OF THE RIFT" : "ACT " + act, endless > 0
						? "Deeper and harder: " + map.boss.title + " waits at the bottom" : act >= 4 ? "The secret act: the Ruby Sovereign waits"
								: "Deeper: " + map.boss.title + " waits at the bottom", ChatFormatting.LIGHT_PURPLE);
				p.level().playSound(null, p.blockPosition(), SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 0.7F, 0.9F);
			}
			for (Runner r : RUNNERS.values()) {
				r.fx.phoenixUsed = false;
			}
			RiftRooms.guardians = 0;
		}
		state = State.VOTE;
		timer = VOTE_TICKS;
		portalOpen = false;
		removePortal(level(server));
		for (Runner r : RUNNERS.values()) {
			r.vote = -1;
		}
		sendMap(server);
		BAR.setColor(BossEvent.BossBarColor.PURPLE);
	}

	private static void sendMap(MinecraftServer server) {
		List<String> lines = new ArrayList<>(map.lines());
		Map<Integer, Integer> votes = new HashMap<>();
		for (Runner r : RUNNERS.values()) {
			if (r.vote >= 0) {
				votes.merge(r.vote, 1, Integer::sum);
			}
		}
		lines.add("votes|" + String.join(",", votes.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).toList()));
		lines.add("keys|" + String.join(",", KEYS));
		for (ServerPlayer p : players(server)) {
			Net.send(p, new MorePayloads.RiftScreen("map", lines));
		}
	}

	static void vote(ServerPlayer player, int nodeId) {
		Runner r = RUNNERS.get(player.getUUID());
		if (r == null || state != State.VOTE) {
			return;
		}
		if (map.choices().stream().noneMatch(n -> n.id == nodeId)) {
			return;
		}
		r.vote = nodeId;
		player.level().playSound(null, player.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.5F, 1.4F);
		sendMap(player.level().getServer());
	}

	/** Off to the room with the most votes (ties: the first). */
	private static void travel(MinecraftServer server) {
		Map<Integer, Integer> votes = new HashMap<>();
		for (Runner r : RUNNERS.values()) {
			if (r.vote >= 0) {
				votes.merge(r.vote, 1, Integer::sum);
			}
		}
		List<RiftMap.Node> choices = map.choices();
		RiftMap.Node pick = choices.get(0);
		int best = -1;
		for (RiftMap.Node n : choices) {
			int v = votes.getOrDefault(n.id, 0);
			if (v > best) {
				best = v;
				pick = n;
			}
		}
		node = pick;
		map.visited.add(pick.id);
		state = State.TRAVEL;
		ServerLevel level = level(server);
		clearMobs(level);
		removePortal(level);
		RelicEffects.clearWorld(level);
		RiftAbilities.clear();
		RiftWeapons.clear(level);
		RiftObjectives.clear(level);
		RiftRooms.clear(level);
		RiftEvents.clear(level);
		RiftExtras.clearPets();
		RiftSecrets.clear();
		RiftTanks.clear(level);
		slot = 1 - slot;
		room = RiftRoom.build(WORK, level, SLOTS[slot], pick.theme, pick.type, seed + pick.id * 7919L);
		// Kept loaded while it's in play (the builder loads it bit by bit; this keeps it there).
		hold(level, room);
		for (ServerPlayer p : players(server)) {
			Net.send(p, new MorePayloads.RiftScreen("close", List.of()));
			p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0, false, false));
			RiftFx.screen(p, "flash", 0xFF1A0010, 30);
			Alerts.title(p, pick.type == RiftMap.Type.BOSS ? "ACT " + act + " BOSS" : pick.burning ? "BURNING ELITE" : pick.type.title.toUpperCase(),
					pick.theme.title, ChatFormatting.LIGHT_PURPLE);
			p.level().playSound(null, p.blockPosition(), SoundEvents.PORTAL_TRAVEL, SoundSource.PLAYERS, 0.25F, 1.4F);
		}
		BAR.setName(Component.literal("Into the " + pick.theme.title + "..."));
		BAR.setVisible(true);
	}

	/** A room finished building: bring everyone in. */
	private static void roomReady(MinecraftServer server) {
		ServerLevel level = level(server);
		if (state == State.STAGING || state == State.OVER) {
			music(server, "theme:THRESHOLD");
			for (ServerPlayer p : players(server)) {
				place(p, room.entry);
				if (state == State.STAGING) {
					RiftMeta.kit(p, null);
					ammo(p);
					RiftMeta.send(p);
					p.sendSystemMessage(Component.literal("The Threshold: press J for the Altar of Echoes (loadouts, unlocks, Heat, the Daily Rift). "
							+ "Step into the portal when you're ready.").withStyle(ChatFormatting.LIGHT_PURPLE));
				}
			}
			openPortal(level);
			return;
		}
		if (state != State.TRAVEL) {
			return;
		}
		for (ServerPlayer p : players(server)) {
			Runner r = RUNNERS.get(p.getUUID());
			boolean late = !r.alive && r.downAt == null;
			removeMarker(server, r);
			r.downAt = null;
			place(p, room.entry);
			ammo(p);
			RelicEffects.onRoomStart(p, r, level);
			RiftExtras.onRoomStart(p);
			RiftFx.screen(p, "flash", node.theme.color, 20);
			if (late) {
				// Joined mid-run: a free relic to catch up.
				r.offer = RelicOffers.offer(r, RiftMap.Type.FIGHT, RandomSource.create(seed ^ p.getUUID().hashCode()));
				sendOffer(p, r, "A relic to catch up");
			}
		}
		RiftRooms.bringGuardians(level, players(server));
		RiftExtras.bringCompanions(level, players(server));
		music(server, node.type == RiftMap.Type.BOSS ? "boss" + (map.boss == RiftBosses.Boss.SOVEREIGN ? 4 : map.boss.act) : "theme:" + node.theme.name());
		if (node.type == RiftMap.Type.FIGHT || node.type == RiftMap.Type.ELITE || node.type == RiftMap.Type.GAUNTLET || node.type == RiftMap.Type.DARK
				|| node.type == RiftMap.Type.HOLD || node.type == RiftMap.Type.RESCUE) {
			RiftSecrets.place(level, room);
		}
		if (heroRooms > 0 && node.type != RiftMap.Type.SHOP && node.type != RiftMap.Type.EVENT) {
			bringHero(level, players(server));
		}
		switch (node.type) {
			case FIGHT, ELITE, TANK -> startFight(server, level);
			case GAUNTLET, HEIST, RESCUE, DARK -> {
				startFight(server, level);
				RiftRooms.start(level, room, node.type, players(server));
			}
			case DEFEND, HOLD -> {
				startFight(server, level);
				RiftObjectives.start(level, room, node.type, RUNNERS.size());
			}
			case BOSS -> {
				state = State.FIGHT;
				wave = 0;
				waveStartTicks = ticks;
				waves = 0;
				if (map.boss != RiftBosses.Boss.SOVEREIGN) {
					FACED.add(map.boss);
				}
				boss = RiftBosses.spawn(map.boss, level, room, RUNNERS.size(), node.floor);
				bossStart = ticks;
				raged = false;
				pitySaid = false;
				String[] intro = {map.boss.title.toUpperCase(), map.boss.tip};
				BAR.setVisible(false);
				if (boss != null) {
					RiftMutations.roll(level, boss, RiftMeta.heat, level.getRandom());
				}
				if (RiftMeta.heat >= 5) {
					for (UUID id : MOBS) {
						if (level.getEntity(id) instanceof LivingEntity e && RiftBoss.isBoss(e)) {
							var hp = e.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
							if (hp != null) {
								hp.setBaseValue(hp.getBaseValue() * 1.5);
								e.setHealth(e.getMaxHealth());
							}
						}
					}
				}
				for (ServerPlayer p : players(server)) {
					Alerts.title(p, intro[0], intro[1], ChatFormatting.RED);
					p.level().playSound(null, p.blockPosition(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.7F, 0.7F);
					RiftFx.shake(p, 1.5F, 30);
				}
			}
			default -> startCalm(server, level);
		}
	}

	// ----- Fights -----

	private static void startFight(MinecraftServer server, ServerLevel level) {
		state = State.FIGHT;
		boolean elite = node.type == RiftMap.Type.ELITE;
		waves = node.type == RiftMap.Type.HOLD || node.type == RiftMap.Type.GAUNTLET || node.type == RiftMap.Type.HEIST ? 99
				: node.type == RiftMap.Type.DEFEND || node.type == RiftMap.Type.RESCUE ? 3 : elite || node.type == RiftMap.Type.TANK ? 2 : node.floor >= 4 ? 3 : 2;
		wave = 0;
		int players = Math.max(1, RUNNERS.size());
		waveBudget = (int) Math.round((5 + depth() * 1.6) * (1 + 0.6 * (players - 1)));
		waveBudget = (int) Math.round(waveBudget * RiftExtras.budgetScale());
		if (doubleNext) {
			// Double or Nothing: a bigger fight, for triple gold.
			doubleNext = false;
			doubling = true;
			waveBudget = (int) Math.round(waveBudget * 1.5);
			for (ServerPlayer p : players(server)) {
				Alerts.title(p, "DOUBLE OR NOTHING", "More monsters, triple gold!", ChatFormatting.RED);
			}
		}
		if (node.type == RiftMap.Type.TANK) {
			RiftTanks.giveTanks(level, room, players(server));
			for (ServerPlayer p : players(server)) {
				Alerts.title(p, "TANK ASSAULT", "Climb in and roll out!", ChatFormatting.GOLD);
			}
		}
		BAR.setColor(elite ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.RED);
		nextWave(server, level);
	}

	private static void nextWave(MinecraftServer server, ServerLevel level) {
		wave++;
		waveStartTicks = ticks;
		RandomSource random = level.getRandom();
		int budget = waveBudget;
		boolean elite = (node.type == RiftMap.Type.ELITE || RiftMeta.heat >= 2 && node.type == RiftMap.Type.FIGHT) && wave == waves;
		List<BlockPos> spawns = new ArrayList<>(room.spawns);
		java.util.Collections.shuffle(spawns, new java.util.Random(random.nextLong()));
		int i = 0;
		if (elite) {
			int count = (node.floor >= 5 || RUNNERS.size() >= 3 ? 2 : 1) + (node.burning ? 1 : 0);
			for (int e = 0; e < count; e++) {
				RiftEnemies.Twist twist = RiftEnemies.Twist.values()[random.nextInt(RiftEnemies.Twist.values().length)];
				RiftEnemies.Kind kind = RiftEnemies.pick(node.theme, random);
				PENDING.add(new Pending(spawns.get(i++ % spawns.size()), kind, twist, new int[] {40 + e * 20}));
			}
			budget /= 2;
		}
		if (node.type == RiftMap.Type.TANK) {
			int tanks = 2 + depth() / 5 + RUNNERS.size() / 2;
			for (int t = 0; t < tanks; t++) {
				PENDING.add(new Pending(spawns.get(i++ % spawns.size()), null, null, new int[] {30 + t * 25}, true));
			}
			budget /= 2;
		}
		int gunmen = 0;
		while (budget > 0) {
			RiftEnemies.Kind kind = RiftEnemies.pick(node.theme, random);
			// Early on, only a couple of gunmen at a time.
			if (kind == RiftEnemies.TROOPER && gunmen >= RiftEnemies.maxGunmen()) {
				continue;
			}
			if (kind == RiftEnemies.TROOPER) {
				gunmen++;
			}
			PENDING.add(new Pending(spawns.get(i++ % spawns.size()), kind, null, new int[] {30 + random.nextInt(40)}));
			budget -= kind.cost();
		}
		for (ServerPlayer p : players(server)) {
			if (wave > 1) {
				p.sendOverlayMessage(Component.literal("⚠ Wave " + wave + " of " + waves + " is tearing through!").withStyle(ChatFormatting.LIGHT_PURPLE));
			}
		}
	}

	private static void tickPending(ServerLevel level) {
		for (var it = PENDING.iterator(); it.hasNext();) {
			Pending p = it.next();
			Vec3 at = Vec3.atBottomCenterOf(p.at());
			int left = --p.left()[0];
			if (left % 3 == 0) {
				// A tear in the Rift: a swirl of light where something's coming through.
				double a = left * 0.4;
				int color = p.twist() != null ? p.twist().color : room != null ? room.theme.color : 0xFF3D6E;
				for (int k = 0; k < 3; k++) {
					double ang = a + k * Math.PI * 2 / 3;
					level.sendParticles(RiftFx.dust(color, 1.4F), true, false, at.x + Math.cos(ang) * 0.8, at.y + 0.2 + (40 - left) * 0.04,
							at.z + Math.sin(ang) * 0.8, 1, 0, 0, 0, 0);
				}
				level.sendParticles(ParticleTypes.REVERSE_PORTAL, true, false, at.x, at.y + 1, at.z, 3, 0.3, 0.6, 0.3, 0.05);
			}
			if (left <= 0) {
				it.remove();
				Mob mob = p.tank() ? RiftTanks.spawnEnemy(level, p.at(), RiftEnemies.toughness(depth(), RUNNERS.size()))
						: RiftEnemies.create(level, p.kind(), p.at(), room.center, RiftEnemies.toughness(depth(), RUNNERS.size()), p.twist());
				if (mob != null) {
					if (!p.tank()) {
						level.addFreshEntity(mob);
						RiftExtras.onSpawn(mob);
					}
					if (node != null && node.burning && p.twist() != null) {
						// A Burning Elite: much tougher, wreathed in fire, and fire can't hurt it.
						var hp = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
						if (hp != null) {
							hp.setBaseValue(hp.getBaseValue() * 1.7);
							mob.setHealth(mob.getMaxHealth());
						}
						mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, -1, 0, false, false));
						mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, -1, 0, false, false));
						BURNING.add(mob.getUUID());
					}
					MOBS.add(mob.getUUID());
					RiftFx.burst(level, at.add(0, 1, 0), p.twist() != null ? p.twist().color : room.theme.color, 18, 0.4);
					level.playSound(null, p.at(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.6F, 0.6F);
					if (p.twist() != null) {
						level.playSound(null, p.at(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.0F, 1.2F);
					}
				}
			}
		}
	}

	/** When the current wave (or boss) began, for spotting stragglers. */
	private static int waveStartTicks;

	private static void tickFight(MinecraftServer server, ServerLevel level) {
		// Tidy: forget the dead, send strays back in, point everyone at a player.
		List<ServerPlayer> targets = players(server).stream().filter(p -> !p.isSpectator() && RUNNERS.get(p.getUUID()).alive).toList();
		boolean late = ticks - waveStartTicks > 600;
		for (UUID id : new ArrayList<>(MOBS)) {
			Entity e = level.getEntity(id);
			if (!(e instanceof Mob mob) || !mob.isAlive()) {
				MOBS.remove(id);
				continue;
			}
			if (ticks % 20 == 0) {
				if (!room.inside(mob.position()) && !RiftBoss.isBoss(mob)) {
					BlockPos back = room.spawns.get(level.getRandom().nextInt(room.spawns.size()));
					mob.teleportTo(back.getX() + 0.5, back.getY(), back.getZ() + 0.5);
				}
				if (RiftObjectives.goesForCore(mob, targets)) {
					// Heading for the core (Defend the Core).
				} else if ((mob.getTarget() == null || !mob.getTarget().isAlive() || mob.getTarget() instanceof Player t && t.isSpectator()) && !targets.isEmpty()) {
					mob.setTarget(targets.stream().min(Comparator.comparingDouble(p -> p.distanceToSqr(mob))).orElse(null));
				}
				// A wave dragging on (30 seconds): the ones hanging back far away glow, so nobody has to hunt for them.
				if (late && !RiftBoss.isBoss(mob) && targets.stream().allMatch(p -> p.distanceToSqr(mob) > 16 * 16)) {
					mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false));
				}
			}
			RiftEnemies.Twist twist = RiftEnemies.twistOf(mob);
			if (BURNING.contains(id) && ticks % 2 == 0) {
				level.sendParticles(ParticleTypes.FLAME, mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(), 3, mob.getBbWidth() * 0.4,
						mob.getBbHeight() * 0.35, mob.getBbWidth() * 0.4, 0.02);
				if (ticks % 6 == 0) {
					level.sendParticles(ParticleTypes.LAVA, mob.getX(), mob.getY() + mob.getBbHeight(), mob.getZ(), 1, 0.2, 0.1, 0.2, 0);
				}
			}
			if (twist != null && ticks % 4 == 0) {
				RiftFx.ring(level, mob.position().add(0, 0.1, 0), mob.getBbWidth() * 0.9, RiftFx.dust(twist.color, 1.0F), 10);
				if (twist == RiftEnemies.Twist.SUMMONER && ticks % 160 == 0 && MOBS.size() < 30) {
					for (int k = 0; k < 2; k++) {
						PENDING.add(new Pending(mob.blockPosition().offset(level.getRandom().nextInt(5) - 2, 0, level.getRandom().nextInt(5) - 2),
								RiftEnemies.pick(node.theme, level.getRandom()), null, new int[] {20}));
					}
				}
			}
		}
		if (node.type == RiftMap.Type.TANK) {
			RiftTanks.tick(level, targets);
		}
		if (boss != null) {
			if (!pitySaid && (ticks - bossStart) / 20 > 75) {
				pitySaid = true;
				for (ServerPlayer p : targets) {
					Alerts.title(p, "THE RIFT IS ON YOUR SIDE", "Your hits grow stronger the longer this fight lasts", ChatFormatting.GOLD);
				}
			}
			boss.tick(level, targets);
			RiftMutations.tick(level, boss, targets, ticks);
			LivingEntity bossBody = boss.body(level);
			if (!raged && bossBody != null && bossBody.getHealth() < bossBody.getMaxHealth() * 0.5F && map.boss != RiftBosses.Boss.SOVEREIGN) {
				// Half gone: the music turns.
				raged = true;
				music(server, "rage");
			}
			if (boss.dead()) {
				boss = null;
				// Whatever it brought along (brood, husks, reflections) goes with it.
				PENDING.clear();
				for (UUID id : new ArrayList<>(MOBS)) {
					if (level.getEntity(id) instanceof LivingEntity left) {
						RiftFx.burst(level, left.getBoundingBox().getCenter(), 0xFFFFC02E, 12, 0.4);
						left.discard();
					}
				}
				MOBS.clear();
				RiftMutations.clear();
				if (endless > 0) {
					endless++;
					for (ServerPlayer p : players(server)) {
						var rec = dev.rubymod.game.Stats.get(p);
						if (endless - 1 > rec.riftDeepest) {
							rec.riftDeepest = endless - 1;
							dev.rubymod.game.Stats.markDirty();
							p.sendSystemMessage(Component.literal("\u2726 New best Endless Descent: depth " + (endless - 1) + "!").withStyle(ChatFormatting.LIGHT_PURPLE));
						}
					}
					actCleared(server, level);
				} else if (act < 3 || act == 3 && KEYS.size() >= 3) {
					actCleared(server, level);
				} else {
					victory(server);
				}
				return;
			}
		}
		if (RiftObjectives.kind != RiftObjectives.Kind.NONE) {
			RiftObjectives.tick(level, targets);
			if (RiftObjectives.captured()) {
				// The zone's taken: everything left burns away.
				RiftObjectives.flare(level);
				PENDING.clear();
				for (UUID id : new ArrayList<>(MOBS)) {
					if (level.getEntity(id) instanceof LivingEntity e) {
						RiftFx.burst(level, e.getBoundingBox().getCenter(), 0xFFFFC02E, 12, 0.4);
						e.hurtServer(level, level.damageSources().magic(), 10000);
					}
				}
				MOBS.clear();
				cleared(server, level);
				return;
			}
		}
		if (RiftRooms.tick(level, room, players(server))) {
			// Time's up (the Gauntlet survived, the vault sealed): everything left fades away.
			PENDING.clear();
			for (UUID id : new ArrayList<>(MOBS)) {
				if (level.getEntity(id) instanceof LivingEntity e) {
					RiftFx.burst(level, e.getBoundingBox().getCenter(), 0xFFFFC02E, 12, 0.4);
					e.discard();
				}
			}
			MOBS.clear();
			cleared(server, level);
			return;
		}
		int alive = MOBS.size();
		String special = ticks % 20 == 0 ? RiftRooms.bar(level, alive + PENDING.size()) : null;
		if (special != null && boss == null) {
			BAR.setName(Component.literal(special));
			BAR.setProgress(Math.max(0, Math.min(1, RiftRooms.progress(level))));
		} else if (ticks % 20 == 0 && boss == null) {
			if (RiftObjectives.kind != RiftObjectives.Kind.NONE) {
				BAR.setName(Component.literal(RiftObjectives.bar() + "  •  Wave " + wave + (waves < 99 ? "/" + waves : "") + "  •  " + (alive + PENDING.size())
						+ " enemies"));
				BAR.setProgress(RiftObjectives.barProgress());
			} else {
				BAR.setName(Component.literal(node.theme.title + "  •  Wave " + wave + "/" + waves + "  •  " + (alive + PENDING.size()) + " enemies"));
				BAR.setProgress(Math.min(1, wave / (float) Math.max(1, waves)));
			}
		}
		if (boss == null && PENDING.isEmpty() && (alive == 0 || wave < waves && alive <= 2)) {
			if (wave < waves) {
				nextWave(server, level);
			} else if (alive == 0) {
				cleared(server, level);
			}
		}
	}

	/** The Rift Keys found this run (flame, gold, life): all three open the secret fourth act. */
	static final java.util.Set<String> KEYS = new java.util.LinkedHashSet<>();
	/** The bosses this run has fought (the finale brings back their echoes). */
	static final List<RiftBosses.Boss> FACED = new ArrayList<>();

	static void grantKey(MinecraftServer server, String id, String name) {
		if (!KEYS.add(id)) {
			return;
		}
		for (ServerPlayer p : players(server)) {
			Alerts.title(p, "RIFT KEY: " + name.toUpperCase(), KEYS.size() + " of 3" + (KEYS.size() == 3 ? ": the Heart of the Rift will open!" : ""),
					ChatFormatting.LIGHT_PURPLE);
			p.level().playSound(null, p.blockPosition(), SoundEvents.VAULT_OPEN_SHUTTER, SoundSource.PLAYERS, 1.0F, 1.0F);
			p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.6F, 1.4F);
			RiftFx.beam((ServerLevel) p.level(), p.position(), 16, ParticleTypes.END_ROD, 0xFFC27BFF);
			p.sendSystemMessage(Component.literal("\u2726 Rift Key found: " + name + " (" + KEYS.size() + "/3). "
					+ (KEYS.size() == 3 ? "Beat the act 3 boss to open the Heart of the Rift!" : "Find all three to open a secret act.")).withStyle(ChatFormatting.LIGHT_PURPLE));
		}
	}

	/** When the boss fight began (the Rift helps out if it drags on), and whether it's said so. */
	private static int bossStart;
	private static boolean pitySaid;
	/** The boss music has turned (it's badly hurt). */
	private static boolean raged;

	/** What the music should be: "theme:CRYSTAL", "boss1".."boss4", "rage", "victory". */
	static void music(MinecraftServer server, String id) {
		for (ServerPlayer p : players(server)) {
			RiftFx.screen(p, "music:" + id, 0, 0);
		}
	}

	/** Damage against a boss: more and more once the fight's gone on 75 seconds (up to x2.5). */
	public static float bossPity(Entity target) {
		if (boss == null || !RiftBoss.isBoss(target)) {
			return 1;
		}
		float seconds = (ticks - bossStart) / 20F;
		return seconds <= 75 ? 1 : 1 + Math.min(1.5F, (seconds - 75) / 40F);
	}

	/** Mysteries: rooms left with the hired Wandering Hero; a Double or Nothing waiting, or running. */
	static int heroRooms;
	static boolean doubleNext;
	private static boolean doubling;

	/** Sir Ruby, the Wandering Hero: an axe-swinging ally for this room. */
	private static void bringHero(ServerLevel level, List<ServerPlayer> players) {
		if (players.isEmpty()) {
			return;
		}
		ServerPlayer owner = players.get(0);
		Mob hero = EntityTypes.VINDICATOR.create(level, EntitySpawnReason.EVENT);
		if (hero == null) {
			return;
		}
		hero.snapTo(owner.getX() - 2, owner.getY(), owner.getZ() + 2, owner.getYRot(), 0);
		var hp = hero.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
		if (hp != null) {
			hp.setBaseValue(80);
			hero.setHealth(80);
		}
		hero.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_AXE));
		hero.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
		hero.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 0);
		hero.setDropChance(net.minecraft.world.entity.EquipmentSlot.HEAD, 0);
		hero.addEffect(new MobEffectInstance(MobEffects.STRENGTH, -1, 1, false, false));
		hero.setCustomName(Component.literal("Sir Ruby (" + heroRooms + " rooms)").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		hero.addTag(TAG);
		level.addFreshEntity(hero);
		RelicEffects.ALLIES.put(hero.getUUID(), new RelicEffects.Ally(Long.MAX_VALUE, owner.getUUID(), false));
		RiftFx.beam(level, hero.position(), 8, ParticleTypes.END_ROD, 0xFFFFC02E);
	}

	/** This room's Burning Elites (they burn, and fire can't touch them). */
	private static final java.util.Set<UUID> BURNING = new java.util.HashSet<>();

	public static void onMobDeath(LivingEntity e, DamageSource source) {
		if (!MOBS.remove(e.getUUID())) {
			return;
		}
		RiftEnemies.Twist twist = e instanceof Mob m ? RiftEnemies.twistOf(m) : null;
		RiftRooms.onKill();
		int value = twist != null ? 30 : 6;
		gold += value;
		ServerLevel level = (ServerLevel) e.level();
		if (twist == RiftEnemies.Twist.VOLATILE) {
			RelicEffects.blast(level, null, e.position().add(0, 0.5, 0), 4.5, 10, 0xFFFF7A2E, true);
		}
		if (source.getEntity() instanceof ServerPlayer killer) {
			Runner r = RUNNERS.get(killer.getUUID());
			if (r != null) {
				r.kills++;
				killer.sendOverlayMessage(Component.literal("+" + value + " gold").withStyle(ChatFormatting.GOLD));
			}
		}
		RiftFx.burst(level, e.position().add(0, e.getBbHeight() / 2, 0), 0xFFFFC02E, 6, 0.3);
	}

	/** An act's boss is down: a Legendary each, everyone healed, and the way to the next act. */
	private static void actCleared(MinecraftServer server, ServerLevel level) {
		for (ServerPlayer p : players(server)) {
			RiftCodex.learn(p, "boss:" + map.boss.name(), map.boss.title);
		}
		nextAct = true;
		bossesKilled++;
		gold += 100;
		reviveAll(server, level, 1.0F);
		fireworks(level, room.portal, 8);
		RandomSource random = level.getRandom();
		for (ServerPlayer p : players(server)) {
			Runner r = RUNNERS.get(p.getUUID());
			p.setHealth(p.getMaxHealth());
			Alerts.title(p, act == 3 ? "THE HEART OF THE RIFT OPENS" : "ACT " + act + " CLEARED!",
					act == 3 ? "All three keys turn... one more battle waits" : "+100 gold  \u2022  choose a Legendary relic", act == 3 ? ChatFormatting.LIGHT_PURPLE
							: ChatFormatting.GOLD);
			RiftFx.screen(p, "flash", 0xFFFFC02E, 25);
			p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0F, 1.0F);
			r.offer = RelicOffers.offer(r, RiftMap.Type.BOSS, random);
			sendOffer(p, r, "Act " + act + " cleared: a Legendary relic");
		}
		state = State.CALM;
		timer = REWARD_TICKS;
		BAR.setVisible(true);
		BAR.setName(Component.literal("Act " + act + " cleared!  \u2022  choose a relic, then step into the portal"));
		BAR.setProgress(1);
		openPortal(level);
	}

	private static void cleared(MinecraftServer server, ServerLevel level) {
		if (node.type == RiftMap.Type.TANK) {
			RiftTanks.clear(level);
		}
		boolean job = RiftObjectives.kind != RiftObjectives.Kind.NONE;
		boolean jobDone = job && RiftObjectives.succeeded();
		RiftObjectives.clear(level);
		int bonus = (node.burning ? 100 : node.type == RiftMap.Type.ELITE || node.type == RiftMap.Type.TANK || jobDone ? 60 : 30) + RiftRooms.bonusGold();
		BURNING.clear();
		if (node.burning) {
			grantKey(server, "flame", "Key of Flame");
		}
		if (RiftRooms.loot() >= 100) {
			grantKey(server, "gold", "Key of Gold");
		}
		if (doubling) {
			doubling = false;
			bonus *= 3;
		}
		if (heroRooms > 0) {
			heroRooms--;
			if (heroRooms == 0) {
				tell(server, Component.literal("Sir Ruby salutes and goes his own way.").withStyle(ChatFormatting.GOLD));
			}
		}
		RiftMap.Type special = RiftRooms.active() ? RiftRooms.rewardTier() : null;
		String specialTitle = RiftRooms.rewardTitle();
		RiftRooms.clear(level);
		roomsCleared++;
		if (node.type == RiftMap.Type.ELITE) {
			elitesKilled++;
		}
		gold += bonus;
		for (ServerPlayer p : players(server)) {
			Runner r = RUNNERS.get(p.getUUID());
			Alerts.title(p, "ROOM CLEARED", "+" + bonus + " gold  •  choose a relic", ChatFormatting.GOLD);
			p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.7F, 1.1F);
			if (r.alive && RiftMeta.heat < 3) {
				p.heal(p.getMaxHealth() * 0.3F);
			}
		}
		reviveAll(server, level, 0.5F);
		fireworks(level, room.portal, 4);
		RiftCharms.drop(players(server), node.burning || node.type == RiftMap.Type.ELITE ? 0.65F : 0.3F, level.getRandom());
		RandomSource random = level.getRandom();
		for (ServerPlayer p : players(server)) {
			Runner r = RUNNERS.get(p.getUUID());
			r.offer = RelicOffers.offer(r, node.burning ? RiftMap.Type.BOSS : special != null ? special : jobDone ? RiftMap.Type.ELITE
					: job ? RiftMap.Type.FIGHT : node.type, random);
			sendOffer(p, r, node.burning ? "Burning Elite slain: a Legendary relic" : specialTitle != null ? specialTitle
					: node.type == RiftMap.Type.ELITE ? "Elite slain: a better relic" : jobDone ? "Job done: a better relic" : "Choose a relic");
		}
		state = State.CALM;
		timer = REWARD_TICKS;
		BAR.setName(Component.literal("Room cleared  •  choose a relic, then step into the portal"));
		BAR.setProgress(1);
		openPortal(level);
	}

	// ----- Calm rooms: treasure, shop, mystery, the Arsenal -----

	private static void startCalm(MinecraftServer server, ServerLevel level) {
		state = State.CALM;
		timer = REWARD_TICKS;
		BAR.setProgress(1);
		BAR.setColor(BossEvent.BossBarColor.PURPLE);
		BAR.setName(Component.literal(switch (node.type) {
			case TREASURE -> "Treasure!  \u2022  choose a relic, then step into the portal";
			case SHOP -> "The Rift Merchant  \u2022  J to shop, then step into the portal";
			case EVENT -> "Something strange...  \u2022  choose, then step into the portal";
			case SPRING -> "The Ruby Spring  \u2022  take one gift, then step into the portal";
			default -> "The Arsenal  \u2022  pick a new weapon, then step into the portal";
		}));
		RandomSource random = level.getRandom();
		switch (node.type) {
			case TREASURE -> {
				level.setBlock(BlockPos.containing(room.portal).above(), net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
				fireworks(level, room.portal, 3);
				for (ServerPlayer p : players(server)) {
					Runner r = RUNNERS.get(p.getUUID());
					r.offer = RelicOffers.offer(r, RiftMap.Type.TREASURE, random);
					sendOffer(p, r, "Treasure! One of these is Legendary");
					RiftCharms.give(p, RiftCharms.Charm.values()[random.nextInt(RiftCharms.Charm.values().length)]);
				}
			}
			case SHOP -> {
				restock(random);
				Mob trader = EntityTypes.WANDERING_TRADER.create(level, EntitySpawnReason.EVENT);
				if (trader != null) {
					trader.snapTo(room.portal.x + 3, room.center.getY() + 1, room.portal.z, 180, 0);
					trader.setNoAi(true);
					trader.setPermanentlyInvulnerable(true);
					trader.setCustomName(Component.literal("Rift Merchant").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
					trader.addTag(TAG);
					level.addFreshEntity(trader);
				}
				for (ServerPlayer p : players(server)) {
					sendShop(p);
				}
				timer = 0;
			}
			case EVENT -> {
				level.setBlock(BlockPos.containing(room.portal).above(), net.minecraft.world.level.block.Blocks.ENCHANTING_TABLE.defaultBlockState(), 3);
				int kind = forceEvent >= 0 ? forceEvent : RiftEvents.pick(random);
				forceEvent = -1;
				for (ServerPlayer p : players(server)) {
					Runner r = RUNNERS.get(p.getUUID());
					r.event = RiftEvents.options(kind);
					Net.send(p, new MorePayloads.RiftScreen("event", r.event));
				}
				timer = 0;
			}
			case SPRING -> {
				BlockPos at = BlockPos.containing(room.portal).above();
				level.setBlock(at, net.minecraft.world.level.block.Blocks.WATER_CAULDRON.defaultBlockState()
						.setValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL, 3), 3);
				RiftFx.beam(level, Vec3.atBottomCenterOf(at), 12, ParticleTypes.END_ROD, 0xFFFF3D6E);
				reviveAll(server, level, RiftMeta.heat >= 3 ? 0.5F : 1.0F);
				for (ServerPlayer p : players(server)) {
					Runner r = RUNNERS.get(p.getUUID());
					r.event = RiftSpring.options(p, r);
					Net.send(p, new MorePayloads.RiftScreen("event", r.event));
					Alerts.title(p, "THE RUBY SPRING", "Take one gift", ChatFormatting.LIGHT_PURPLE);
					p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.0F, 1.2F);
					RiftFx.ring(level, p.position().add(0, 0.2, 0), 1.6, RiftFx.dust(0xFF3D6E, 1.4F), 24);
				}
				timer = 0;
			}
			case REST -> {
				level.setBlock(BlockPos.containing(room.portal).above(), net.minecraft.world.level.block.Blocks.SMITHING_TABLE.defaultBlockState(), 3);
				reviveAll(server, level, RiftMeta.heat >= 3 ? 0.5F : 1.0F);
				for (ServerPlayer p : players(server)) {
					Runner r = RUNNERS.get(p.getUUID());
					r.event = RiftArsenal.options(p, random);
					Net.send(p, new MorePayloads.RiftScreen("event", r.event));
					Alerts.title(p, "THE ARSENAL", "Pick a new weapon", ChatFormatting.GOLD);
					p.level().playSound(null, p.blockPosition(), SoundEvents.ARMOR_EQUIP_NETHERITE.value(), SoundSource.PLAYERS, 1.0F, 0.9F);
					RiftFx.burst(level, p.position().add(0, 1, 0), 0xFFFFC02E, 24, 0.6);
				}
				timer = 0;
			}
			default -> {
			}
		}
		openPortal(level);
	}

	static void sendOffer(ServerPlayer p, Runner r, String title) {
		List<String> lines = new ArrayList<>();
		lines.add("title|" + title);
		for (Relic relic : r.offer) {
			lines.add(hinted(relic.line(-1, r.has(relic) ? r.level(relic) + 1 : 0), r, relic));
		}
		Net.send(p, new MorePayloads.RiftScreen("offer", lines));
	}

	static void sendShop(ServerPlayer p) {
		List<String> lines = new ArrayList<>();
		lines.add("title|Rift Merchant  •  the team has " + gold + " gold");
		lines.add("gold|" + gold);
		Runner me = RUNNERS.get(p.getUUID());
		for (Map.Entry<Relic, Integer> e : SHOP.entrySet()) {
			// Yours already: buy another copy to combine them, or OWNED (-2) once it can't go higher.
			Relic relic = e.getKey();
			if (me != null && me.has(relic)) {
				lines.add(me.canCombine(relic) ? relic.line(e.getValue(), me.level(relic) + 1) : relic.line(-2));
			} else {
				lines.add(hinted(relic.line(e.getValue()), me, relic));
			}
		}
		lines.add("restock|Restock|New relics on the merchant's shelves.|minecraft:emerald|" + RESTOCK_PRICE);
		Net.send(p, new MorePayloads.RiftScreen("shop", lines));
	}

	/** A card line, plus (as its 9th field) the combo it would complete for this runner. */
	private static String hinted(String line, Runner r, Relic relic) {
		String hint = RelicCombos.hint(r, relic);
		if (hint.isEmpty()) {
			return line;
		}
		return line + (line.split("[|]", -1).length >= 8 ? "" : "|0") + "|" + hint.replace("|", "/");
	}

	/** A relic picked from an offer. */
	static void pick(ServerPlayer player, String id) {
		Runner r = RUNNERS.get(player.getUUID());
		Relic relic = Relic.byId(id);
		if (r == null || relic == null || !r.offer.contains(relic)) {
			return;
		}
		choose(player.level().getServer(), r, relic);
	}

	private static void choose(MinecraftServer server, Runner r, Relic relic) {
		r.offer = List.of();
		ServerPlayer p = server.getPlayerList().getPlayer(r.id);
		if (p == null) {
			return;
		}
		give(p, r, relic);
		Net.send(p, new MorePayloads.RiftScreen("close", List.of()));
	}

	/** A second copy of a relic you have: the two fuse into one with another star. */
	private static void combine(ServerPlayer p, Runner r, Relic relic) {
		int level = r.level(relic) + 1;
		r.levels.put(relic, level);
		RelicEffects.apply(p, r);
		ServerLevel level0 = (ServerLevel) p.level();
		Vec3 at = p.position();
		// Two copies spiral together and fuse in a flash.
		for (int i = 0; i < 24; i++) {
			double a = i * Math.PI / 6;
			double rad = 2.2 - i * 0.08;
			for (int side = 0; side < 2; side++) {
				double aa = a + side * Math.PI;
				level0.sendParticles(RiftFx.dust(relic.tier.color, 1.3F), true, false, at.x + Math.cos(aa) * rad, at.y + 0.3 + i * 0.07, at.z + Math.sin(aa) * rad,
						1, 0, 0, 0, 0);
			}
		}
		RiftFx.burst(level0, at.add(0, 2, 0), 0xFFFFE680, 50, 0.9);
		RiftFx.beam(level0, at, 18, ParticleTypes.END_ROD, 0xFFFFE680);
		RiftFx.screen(p, "relic:" + relic.icon, 0xFFFFE680, 50);
		level0.playSound(null, p.blockPosition(), SoundEvents.SMITHING_TABLE_USE, SoundSource.PLAYERS, 1.0F, 0.8F);
		level0.playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.7F, 1.4F);
		Alerts.title(p, "COMBINED!", relic.title(level) + ": " + relic.upgrade(level), ChatFormatting.GOLD);
	}

	/** Gain a relic, with all the flash it deserves. */
	static void give(ServerPlayer p, Runner r, Relic relic) {
		if (r.has(relic)) {
			if (r.canCombine(relic)) {
				combine(p, r, relic);
			}
			return;
		}
		if (relic.tier == Relic.Tier.BOND) {
			// A Bond is the whole team's.
			for (ServerPlayer mate : players(p.level().getServer())) {
				Runner m = RUNNERS.get(mate.getUUID());
				if (mate != p && !m.has(relic)) {
					m.relics.add(relic);
					RiftCodex.learn(mate, "relic:" + relic.id(), relic.title);
					RiftFx.beam((ServerLevel) mate.level(), mate.position(), 10, ParticleTypes.END_ROD, relic.tier.color);
					RiftFx.screen(mate, "relic:" + relic.icon, relic.tier.color, 40);
				}
			}
		}
		r.relics.add(relic);
		RelicEffects.apply(p, r);
		RiftCodex.learn(p, "relic:" + relic.id(), relic.title);
		RelicCombos.announce(p, r, relic);
		ServerLevel level = (ServerLevel) p.level();
		Vec3 at = p.position();
		RiftFx.beam(level, at, 14, ParticleTypes.END_ROD, relic.tier.color);
		RiftFx.ring(level, at.add(0, 0.2, 0), 1.6, RiftFx.dust(relic.tier.color, 1.6F), 24);
		RiftFx.burst(level, at.add(0, 1, 0), relic.element != Relic.Element.NONE ? relic.element.color : relic.tier.color, 30, 0.7);
		RiftFx.screen(p, "relic:" + relic.icon, relic.tier.color, 40);
		level.playSound(null, p.blockPosition(), relic.tier == Relic.Tier.LEGENDARY ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.BEACON_POWER_SELECT,
				SoundSource.PLAYERS, 1.0F, relic.tier == Relic.Tier.CURSED ? 0.6F : 1.2F);
		if (relic.tier == Relic.Tier.LEGENDARY || relic.tier == Relic.Tier.CURSED) {
			var bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
			if (bolt != null) {
				bolt.snapTo(at.x, at.y, at.z, 0, 0);
				bolt.setVisualOnly(true);
				level.addFreshEntity(bolt);
			}
			RiftFx.screen(p, "flash", relic.tier.color, 16);
		}
		p.sendOverlayMessage(Component.literal(relic.title).withColor(relic.tier.color & 0xFFFFFF));
	}

	private static final int RESTOCK_PRICE = 30;

	/** Fresh stock: nothing every one of you already has. */
	private static void restock(RandomSource random) {
		SHOP.clear();
		for (Relic r : RelicOffers.shopStock(random, RUNNERS.values())) {
			SHOP.put(r, RelicOffers.price(r));
		}
	}

	static void buy(ServerPlayer player, String id) {
		Runner r = RUNNERS.get(player.getUUID());
		if (r == null || node == null || node.type != RiftMap.Type.SHOP) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		if (id.equals("restock")) {
			if (gold < RESTOCK_PRICE) {
				player.sendOverlayMessage(Component.literal("Not enough gold (the team has " + gold + ").").withStyle(ChatFormatting.RED));
				return;
			}
			gold -= RESTOCK_PRICE;
			restock(player.getRandom());
			player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_TRADE, SoundSource.PLAYERS, 1.0F, 1.0F);
		} else {
			Relic relic = Relic.byId(id);
			Integer price = relic == null ? null : SHOP.get(relic);
			if (price == null || r.has(relic) && !r.canCombine(relic)) {
				return;
			}
			if (gold < price) {
				player.sendOverlayMessage(Component.literal("Not enough gold (the team has " + gold + ").").withStyle(ChatFormatting.RED));
				return;
			}
			gold -= price;
			SHOP.remove(relic);
			give(player, r, relic);
		}
		for (ServerPlayer p : players(server)) {
			sendShop(p);
		}
	}

	static void event(ServerPlayer player, String option) {
		Runner r = RUNNERS.get(player.getUUID());
		if (r == null || r.event.isEmpty()) {
			return;
		}
		r.event = List.of();
		if (option.startsWith(RiftArsenal.PREFIX)) {
			RiftArsenal.give(player, r, option);
		} else if (option.equals("end:descend")) {
			descend(player.level().getServer());
		} else if (option.equals("end:home")) {
			player.sendSystemMessage(Component.literal("Heading home. (Step into the portal, or wait.)").withStyle(ChatFormatting.GRAY));
		} else if (option.startsWith(RiftSpring.PREFIX)) {
			RiftSpring.choose(player, r, option);
		} else {
			RiftEvents.resolve(player, r, option);
		}
		if (r.event.isEmpty()) {
			Net.send(player, new MorePayloads.RiftScreen("close", List.of()));
		}
	}

	// ----- The way onward -----

	private static void openPortal(ServerLevel level) {
		removePortal(level);
		portalOpen = true;
		Display.ItemDisplay gem = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (gem != null) {
			gem.snapTo(room.portal.x, room.portal.y + 1.6, room.portal.z, 0, 0);
			((ItemDisplayAccessor) gem).rubymod$setItemStack(new ItemStack(ModItems.RUBY));
			((DisplayAccessor) gem).rubymod$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(2.4F, 2.4F, 2.4F),
					new Quaternionf()));
			((DisplayAccessor) gem).rubymod$setPosRotInterpolationDuration(2);
			gem.addTag(TAG);
			level.addFreshEntity(gem);
			portalDisplay = gem.getUUID();
		}
	}

	private static void removePortal(ServerLevel level) {
		portalOpen = false;
		if (portalDisplay != null && level.getEntity(portalDisplay) instanceof Entity e) {
			e.discard();
		}
		portalDisplay = null;
	}

	private static void tickPortal(MinecraftServer server, ServerLevel level) {
		if (room == null || WORK.size() > 0) {
			return;
		}
		Vec3 c = room.portal;
		// A spinning ruby over a swirling vortex.
		if (portalDisplay != null && level.getEntity(portalDisplay) instanceof Entity gem) {
			gem.teleportTo(c.x, c.y + 1.6 + Math.sin(ticks * 0.1) * 0.2, c.z);
			gem.setYRot(ticks * 6 % 360);
		}
		for (int k = 0; k < 3; k++) {
			double a = ticks * 0.25 + k * Math.PI * 2 / 3;
			double rr = 1.4 + Math.sin(ticks * 0.05 + k) * 0.2;
			level.sendParticles(RiftFx.dust(0xFF3D6E, 1.5F), true, false, c.x + Math.cos(a) * rr, c.y + (ticks % 40) * 0.08, c.z + Math.sin(a) * rr,
					1, 0, 0, 0, 0);
		}
		if (ticks % 2 == 0) {
			level.sendParticles(ParticleTypes.END_ROD, true, false, c.x, c.y + 0.5, c.z, 1, 0.3, 1.2, 0.3, 0.02);
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, true, false, c.x, c.y + 1, c.z, 4, 0.6, 0.8, 0.6, 0.1);
		}
		// Step in: the next part begins (once everyone's picked their relic).
		List<String> picking = RUNNERS.values().stream().filter(r -> !r.offer.isEmpty() || !r.event.isEmpty()).map(r -> r.name).toList();
		for (ServerPlayer p : players(server)) {
			double flat = Math.hypot(p.getX() - c.x, p.getZ() - c.z);
			if (p.isSpectator() || flat > 2.6 || Math.abs(p.getY() - c.y) > 3) {
				continue;
			}
			Runner me = RUNNERS.get(p.getUUID());
			if (!me.offer.isEmpty() || !me.event.isEmpty()) {
				// You haven't chosen yet: here's the choice again.
				if (ticks % 30 == 0) {
					if (!me.offer.isEmpty()) {
						sendOffer(p, me, "Pick a relic first, then step in!");
					} else {
						Net.send(p, new MorePayloads.RiftScreen("event", me.event));
					}
					Alerts.title(p, "", "Choose first!", ChatFormatting.GOLD);
				}
				continue;
			}
			if (!picking.isEmpty()) {
				if (ticks % 30 == 0) {
					p.sendOverlayMessage(Component.literal("Waiting for " + String.join(", ", picking) + " to choose...").withStyle(ChatFormatting.LIGHT_PURPLE));
				}
				continue;
			}
			level.playSound(null, p.blockPosition(), SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 0.4F, 1.6F);
			if (state == State.STAGING) {
				beginRun(server);
			} else if (state == State.OVER) {
				timer = 1;
			} else {
				openVote(server);
			}
			return;
		}
	}

	// ----- Going down and getting up -----

	/** A runner would die: they're down instead. True when handled. */
	public static boolean onDeath(ServerPlayer victim, DamageSource source) {
		Runner r = RUNNERS.get(victim.getUUID());
		if (r == null) {
			return false;
		}
		MinecraftServer server = victim.level().getServer();
		victim.stopRiding();
		victim.setHealth(victim.getMaxHealth());
		victim.removeAllEffects();
		victim.clearFire();
		if (!running() || !r.alive) {
			if (room != null) {
				place(victim, room.entry);
			}
			return true;
		}
		if (r.has(Relic.PHOENIX) && !r.fx.phoenixUsed) {
			r.fx.phoenixUsed = true;
			RelicEffects.phoenix(victim);
			return true;
		}
		victim.setGameMode(GameType.SPECTATOR);
		r.alive = false;
		r.downAt = room != null && room.inside(victim.position()) ? victim.position() : room.entry;
		r.revive = 0;
		if (RUNNERS.values().stream().noneMatch(x -> x.alive)) {
			teamDown(server);
			return true;
		}
		spawnMarker(level(server), r);
		Alerts.title(victim, "DOWN!", "A teammate can crouch next to you to bring you back", ChatFormatting.RED);
		for (ServerPlayer p : players(server)) {
			if (p != victim) {
				p.sendSystemMessage(Component.literal(r.name + " is down! Crouch next to them to bring them back.").withStyle(ChatFormatting.RED));
			}
		}
		return true;
	}

	private static void tickRevives(MinecraftServer server, ServerLevel level) {
		for (Runner down : RUNNERS.values()) {
			if (down.alive || down.downAt == null) {
				continue;
			}
			if (ticks % 6 == 0) {
				RiftFx.ring(level, down.downAt.add(0, 0.15, 0), 1.0, RiftFx.dust(0xFF3030, 1.2F), 12);
			}
			ServerPlayer helper = null;
			boolean instant = false;
			for (ServerPlayer p : players(server)) {
				Runner r = RUNNERS.get(p.getUUID());
				if (r.alive && !p.isSpectator() && p.position().distanceTo(down.downAt) < 2.2) {
					if (r.has(Relic.FIELD_MEDIC)) {
						helper = p;
						instant = true;
						break;
					}
					if (p.isShiftKeyDown()) {
						helper = p;
					}
				}
			}
			if (helper == null) {
				down.revive = Math.max(0, down.revive - 2);
				continue;
			}
			down.revive += instant ? 60 : 1;
			if (ticks % 4 == 0) {
				helper.sendOverlayMessage(Component.literal("Reviving " + down.name + "... " + Math.min(100, down.revive * 100 / 60) + "%")
						.withStyle(ChatFormatting.GREEN));
				RiftFx.line(level, helper.position().add(0, 1, 0), down.downAt.add(0, 0.5, 0), RiftFx.dust(0x6EE38A, 1.0F), 0.4);
			}
			if (down.revive >= 60) {
				Runner medic = RUNNERS.get(helper.getUUID());
				boolean secondWind = instant && RelicCombos.on(medic, RelicCombos.Duo.SECOND_WIND);
				revive(server, level, down, down.downAt, secondWind ? 1.0F : 0.5F);
				ServerPlayer up = server.getPlayerList().getPlayer(down.id);
				if (secondWind && up != null) {
					// Second Wind: back at full health, untouchable for 5 seconds.
					down.fx.untouchableUntil = level.getGameTime() + 100;
					RiftFx.beam(level, up.position(), 14, ParticleTypes.END_ROD, 0xFF6EE38A);
					RiftFx.ring(level, up.position().add(0, 1, 0), 1.4, RiftFx.dust(0xFFE680, 1.4F), 24);
					Alerts.title(up, "SECOND WIND", "Untouchable for 5 seconds", ChatFormatting.GREEN);
				}
			}
		}
	}

	private static void revive(MinecraftServer server, ServerLevel level, Runner r, Vec3 at, float health) {
		ServerPlayer p = server.getPlayerList().getPlayer(r.id);
		removeMarker(server, r);
		r.downAt = null;
		r.revive = 0;
		if (p == null) {
			return;
		}
		place(p, at);
		RelicEffects.apply(p, r);
		p.setHealth(p.getMaxHealth() * health);
		RiftFx.burst(level, p.position().add(0, 1, 0), 0xFF6EE38A, 24, 0.6);
		level.playSound(null, p.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.6F, 1.2F);
	}

	private static void reviveAll(MinecraftServer server, ServerLevel level, float health) {
		for (Runner r : RUNNERS.values()) {
			if (!r.alive && r.downAt != null) {
				revive(server, level, r, r.downAt, health);
			}
		}
	}

	/** Everyone's down: the team's Second Wind, a Time Loop, or the end. */
	private static void teamDown(MinecraftServer server) {
		ServerLevel level = level(server);
		Runner looper = RUNNERS.values().stream().filter(r -> r.has(Relic.TIME_LOOP)).findFirst().orElse(null);
		if (looper != null && !timeLoopUsed) {
			timeLoopUsed = true;
			for (Runner r : RUNNERS.values()) {
				revive(server, level, r, r.downAt != null ? r.downAt : room.entry, 1.0F);
			}
			for (UUID id : MOBS) {
				if (level.getEntity(id) instanceof Mob mob) {
					BlockPos back = room.spawns.get(level.getRandom().nextInt(room.spawns.size()));
					mob.teleportTo(back.getX() + 0.5, back.getY(), back.getZ() + 0.5);
					mob.setHealth(mob.getMaxHealth());
				}
			}
			for (ServerPlayer p : players(server)) {
				Alerts.title(p, "TIME LOOP", looper.name + "'s relic turned back time", ChatFormatting.AQUA);
				RiftFx.screen(p, "rewind", 0xFF66E0FF, 40);
				p.level().playSound(null, p.blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.0F, 0.5F);
			}
			return;
		}
		if (secondWind) {
			secondWind = false;
			Runner last = RUNNERS.values().iterator().next();
			revive(server, level, last, last.downAt != null ? last.downAt : room.entry, 0.6F);
			for (ServerPlayer p : players(server)) {
				Alerts.title(p, "SECOND WIND", last.name + " gets back up! (once per run)", ChatFormatting.GOLD);
			}
			return;
		}
		defeat(server);
	}

	private static void spawnMarker(ServerLevel level, Runner r) {
		Display.TextDisplay sign = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (sign != null) {
			sign.snapTo(r.downAt.x, r.downAt.y + 1.4, r.downAt.z, 0, 0);
			((DisplayAccessor) sign).rubymod$setBillboard(Display.BillboardConstraints.CENTER);
			((TextDisplayAccessor) sign).rubymod$setText(Component.literal("✚ " + r.name + " is down\n").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
					.append(Component.literal("crouch here to revive").withStyle(ChatFormatting.WHITE)));
			((TextDisplayAccessor) sign).rubymod$setBackground(0x80000000);
			sign.addTag(TAG);
			level.addFreshEntity(sign);
			r.marker = sign.getUUID();
		}
	}

	private static void removeMarker(MinecraftServer server, Runner r) {
		if (r.marker != null && level(server).getEntity(r.marker) instanceof Entity e) {
			e.discard();
		}
		r.marker = null;
	}

	// ----- Endings -----

	/** The boss (and its health bar) gone: the run ended or moved on without killing it. */
	private static void dropBoss(ServerLevel level) {
		RiftMutations.clear();
		RiftObjectives.clear(level);
		if (boss != null) {
			boss.remove(level);
			boss = null;
		}
	}

	private static void defeat(MinecraftServer server) {
		dropBoss(level(server));
		state = State.OVER;
		timer = 12 * 20;
		int floor = node == null ? 1 : node.floor + 1;
		for (ServerPlayer p : players(server)) {
			Alerts.title(p, "THE RIFT CLAIMS YOU", "You reached floor " + floor + " of " + (RiftMap.FLOORS + 1), ChatFormatting.DARK_RED);
			p.level().playSound(null, p.blockPosition(), SoundEvents.WITHER_DEATH, SoundSource.PLAYERS, 0.5F, 0.8F);
			RiftFx.screen(p, "flash", 0xFF400010, 40);
			Runner r = RUNNERS.get(p.getUUID());
			p.sendSystemMessage(Component.literal("====== RUN OVER: floor " + floor + ", " + r.kills + " kills, " + r.relics.size() + " relics ======")
					.withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
			dev.rubymod.game.Stats.rift(p.getUUID(), r.name, floor, false);
		}
		endRun(server, false);
	}

	/** Shards for everyone, and the Daily Rift score. */
	/** The Endless Descent: how deep (0: not descending). */
	static int endless;

	private static void offerDescent(ServerPlayer p, Runner r) {
		r.event = List.of("title|Victory! Keep going down?",
				"end:descend|Endless Descent|Keep going: deeper and harder, as far as you can make it. Your best depth goes on the Altar's leaderboard.|minecraft:crying_obsidian",
				"end:home|Go Home|The run ends here. Back to the Threshold.|minecraft:ender_pearl");
		Net.send(p, new MorePayloads.RiftScreen("event", r.event));
	}

	/** Somebody chose to keep going: the next depth opens. */
	private static void descend(MinecraftServer server) {
		if (state != State.OVER) {
			return;
		}
		ServerLevel level = level(server);
		endless++;
		nextAct = true;
		state = State.CALM;
		timer = REWARD_TICKS;
		for (Runner r : RUNNERS.values()) {
			r.event = List.of();
		}
		reviveAll(server, level, 1.0F);
		for (ServerPlayer p : players(server)) {
			Net.send(p, new MorePayloads.RiftScreen("close", List.of()));
			Alerts.title(p, "ENDLESS DESCENT", "Depth " + endless + ": step into the portal", ChatFormatting.DARK_PURPLE);
			p.level().playSound(null, p.blockPosition(), SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 0.8F, 0.6F);
		}
		BAR.setName(Component.literal("Endless Descent  \u2022  depth " + endless + "  \u2022  step into the portal"));
		openPortal(level);
	}

	/** For autotests: the next Mystery (-1: random). */
	private static int forceEvent = -1;

	/** When this run began (for the summary's time). */
	private static int runStart;

	/** A hit on a Rift monster, for the summary. */
	public static void recordDamage(ServerPlayer attacker, Entity target, float amount) {
		Runner r = RUNNERS.get(attacker.getUUID());
		if (r != null && isEnemy(target)) {
			r.damage += amount;
		}
	}

	/** The end-of-run summary: how far, how long, and a card for each runner (MVP: most damage). */
	private static void sendSummary(MinecraftServer server, boolean won) {
		List<String> lines = new ArrayList<>();
		lines.add("title|" + (won ? act >= 4 && endless == 0 ? "TRUE VICTORY!" : "VICTORY!" : endless > 0 ? "DEPTH " + endless + " REACHED" : "RUN OVER"));
		String where = endless > 0 ? "Endless Descent, depth " + endless : act >= 4 ? "The Heart of the Rift" : "Act " + act + "  \u2022  Floor " + (node == null ? 1 : node.floor + 1) + " of " + (RiftMap.FLOORS + 1)
				+ (node == null ? "" : "  \u2022  " + node.theme.title);
		int seconds = Math.max(0, (ticks - runStart) / 20);
		lines.add("info|" + where + "|" + seconds / 60 + ":" + String.format("%02d", seconds % 60) + "|" + roomsCleared + "|" + elitesKilled + "|" + bossesKilled
				+ "|" + KEYS.size());
		Runner mvp = RUNNERS.values().stream().max(Comparator.comparingDouble(r -> r.damage)).orElse(null);
		for (Runner r : RUNNERS.values()) {
			ServerPlayer p = server.getPlayerList().getPlayer(r.id);
			List<String> legends = new ArrayList<>();
			if (p != null) {
				for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
					String l = dev.rubymod.gun.Trait.legendary(p.getInventory().getItem(i));
					if (l != null) {
						legends.add(l);
					}
				}
			}
			lines.add("runner|" + r.name + "|" + r.kills + "|" + Math.round(r.damage) + "|" + (r == mvp && RUNNERS.size() > 1 ? 1 : 0) + "|"
					+ String.join(",", r.relics.stream().map(x -> x.icon + ";" + x.tier.name()).toList()) + "|"
					+ String.join(",", RelicCombos.active(r).stream().map(d -> d.title).toList()) + "|" + String.join(",", legends));
		}
		for (ServerPlayer p : players(server)) {
			Net.send(p, new MorePayloads.RiftScreen("summary", lines));
		}
	}

	private static void endRun(MinecraftServer server, boolean won) {
		sendSummary(server, won);
		RiftPing.clear();
		RiftMeta.award(server, roomsCleared, elitesKilled, bossesKilled, won);
		if (RiftMeta.daily) {
			int kills = RUNNERS.values().stream().mapToInt(r -> r.kills).sum();
			RiftMeta.recordDaily(server, depth() * 100 + kills * 2 + gold + (won ? 1000 : 0));
		}
	}

	private static void victory(MinecraftServer server) {
		for (ServerPlayer p : players(server)) {
			RiftCodex.learn(p, "boss:" + map.boss.name(), map.boss.title);
		}
		state = State.OVER;
		timer = 20 * 20;
		ServerLevel level = level(server);
		fireworks(level, room.portal, 12);
		music(server, "victory");
		for (ServerPlayer p : players(server)) {
			Runner r = RUNNERS.get(p.getUUID());
			Alerts.title(p, act >= 4 ? "TRUE VICTORY!" : "RIFT CONQUERED!", act >= 4 ? "The Ruby Sovereign has fallen. The Rift is yours!"
					: "The " + map.boss.title + " has fallen. You win!", ChatFormatting.GOLD);
			RiftFx.screen(p, "flash", 0xFFFFC02E, 30);
			p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0F, 1.0F);
			p.sendSystemMessage(Component.literal("====== VICTORY: " + r.kills + " kills, " + r.relics.size() + " relics ======")
					.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			dev.rubymod.game.Stats.rift(p.getUUID(), r.name, RiftMap.FLOORS + 1, true);
		}
		bossesKilled++;
		endRun(server, true);
		reviveAll(server, level, 1.0F);
		openPortal(level);
		timer = 20 * 40;
		for (ServerPlayer p : players(server)) {
			offerDescent(p, RUNNERS.get(p.getUUID()));
		}
	}

	/** After a run: back to the Threshold, relics gone, ready for another. */
	private static void backToThreshold(MinecraftServer server) {
		ServerLevel level = level(server);
		for (ServerPlayer p : players(server)) {
			Runner r = RUNNERS.get(p.getUUID());
			RelicEffects.cleanup(p, r);
			r.relics.clear();
			r.blessings.clear();
			r.damage = 0;
			p.removeEffect(MobEffects.SLOW_FALLING);
			p.removeEffect(MobEffects.JUMP_BOOST);
			r.alive = true;
			r.kills = 0;
			removeMarker(server, r);
			r.downAt = null;
			Net.send(p, new MorePayloads.RiftScreen("close", List.of()));
		}
		startStaging(server);
	}

	// ----- Odds and ends -----

	private static void clearMobs(ServerLevel level) {
		for (UUID id : MOBS) {
			if (level.getEntity(id) instanceof Entity e) {
				e.discard();
			}
		}
		MOBS.clear();
		PENDING.clear();
		if (room != null) {
			AABB box = new AABB(room.center).inflate(room.half + 4, RiftRoom.HEIGHT + 6, room.half + 4);
			for (Entity e : level.getEntities((Entity) null, box, e -> !(e instanceof Player) && (e.entityTags().contains(TAG) || e instanceof Mob))) {
				e.discard();
			}
		}
	}

	static void fireworks(ServerLevel level, Vec3 at, int count) {
		for (int i = 0; i < count; i++) {
			ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
			var colors = it.unimi.dsi.fastutil.ints.IntList.of(0xFF3D6E, 0xFFC02E, 0xC27BFF, 0x66E0FF);
			rocket.set(net.minecraft.core.component.DataComponents.FIREWORKS, new net.minecraft.world.item.component.Fireworks(1,
					List.of(new net.minecraft.world.item.component.FireworkExplosion(net.minecraft.world.item.component.FireworkExplosion.Shape.values()[i % 4],
							colors, it.unimi.dsi.fastutil.ints.IntList.of(0xFFFFFF), true, true))));
			double a = i * Math.PI * 2 / Math.max(1, count) + 0.3;
			var fw = new net.minecraft.world.entity.projectile.FireworkRocketEntity(level, at.x + Math.cos(a) * 9, at.y + 1, at.z + Math.sin(a) * 9, rocket);
			level.addFreshEntity(fw);
		}
	}

	static void tell(MinecraftServer server, Component message) {
		for (ServerPlayer p : players(server)) {
			p.sendSystemMessage(message);
		}
	}

	private static boolean hudSoon;

	/** Send everyone's HUD next tick (an ability just went on cooldown). */
	static void sendHudSoon() {
		hudSoon = true;
	}

	private static void syncHud(MinecraftServer server) {
		String where = state == State.STAGING ? "THE THRESHOLD" + (RiftMeta.heat > 0 ? "  \u2022  HEAT " + RiftMeta.heat : "")
				+ (RiftMeta.daily ? "  \u2022  DAILY" : "") : node == null ? "RUBY RIFT"
				: endless > 0 ? "ENDLESS DESCENT  \u2022  DEPTH " + endless + "  \u2022  FLOOR " + (node.floor + 1) + "/" + (RiftMap.FLOORS + 1)
				: act >= 4 && map != null && map.boss == RiftBosses.Boss.SOVEREIGN ? "THE HEART OF THE RIFT" + (KEYS.isEmpty() ? "" : "  \u2022  KEYS " + KEYS.size() + "/3")
						: "ACT " + act + "  \u2022  FLOOR " + (node.floor + 1) + "/" + (RiftMap.FLOORS + 1) + "  \u2022  " + node.theme.title.toUpperCase()
								+ (KEYS.isEmpty() ? "" : "  \u2022  KEYS " + KEYS.size() + "/3");
		List<String> team = new ArrayList<>();
		for (Runner r : RUNNERS.values()) {
			ServerPlayer p = server.getPlayerList().getPlayer(r.id);
			if (p != null) {
				team.add(r.name + "|" + (int) Math.ceil(p.getHealth()) + "|" + (int) p.getMaxHealth() + "|" + (r.alive ? "up" : "down"));
			}
		}
		for (ServerPlayer p : players(server)) {
			Runner r = RUNNERS.get(p.getUUID());
			List<String> relics = new ArrayList<>(r.relics.stream().map(x -> x.id() + "|" + x.icon + "|" + x.tier.name() + "|" + x.title(r.level(x)) + "|" + x.about
					+ (r.level(x) > 1 ? " Combined: " + x.upgrade(r.level(x)) : "")).toList());
			// Your ability and how long until it's ready: "@ability|id|title|icon|ticks left|cooldown".
			relics.addAll(RelicCombos.hudLines(r));
			relics.add("@ability|" + r.ability.id() + "|" + r.ability.title + "|" + r.ability.icon + "|"
					+ Math.max(0, r.abilityReadyAt - level(server).getGameTime()) + "|" + r.ability.cooldown);
			Net.send(p, new MorePayloads.RiftHud(where, state == State.STAGING ? RiftMeta.rec(p).riftShards : gold, relics, team));
		}
	}

	/** For autotests: where the boss is (null: none). */
	public static Vec3 bossPos(MinecraftServer server) {
		LivingEntity b = boss == null ? null : boss.body(level(server));
		return b == null ? null : b.position();
	}

	/** For autotests: what the boss is doing. */
	public static String bossDebug() {
		return boss == null ? "no boss" : boss.debug();
	}

	/** For autotests: the middle of the room (the cage, the core). */
	public static Vec3 roomCenter() {
		return room == null ? null : Vec3.atBottomCenterOf(room.center.above());
	}

	/** Where this room starts (a Pocket Portal goes there). */
	static Vec3 entry() {
		return room == null ? null : room.entry;
	}

	/** For autotests: the team's mutators. */
	public static String mutators() {
		return RiftExtras.MUTATORS.toString();
	}

	/** For autotests: each runner's active combos and bonds. */
	public static String debugCombos() {
		StringBuilder out = new StringBuilder();
		for (Runner r : RUNNERS.values()) {
			out.append(r.name).append(" duos ").append(RelicCombos.active(r)).append(" bonds ")
					.append(r.relics.stream().filter(x -> x.tier == Relic.Tier.BOND).toList()).append("; ");
		}
		return out.toString();
	}

	/** For autotests: the Hold the Zone ring (null when there isn't one) and the job's line on the bar. */
	public static Vec3 zonePos() {
		return RiftObjectives.zone();
	}

	/** For autotests: the room from above, one character a block ('#' solid at head height, '+' raised floor, '.' open, 's' a spawn). */
	public static String floorPlan(MinecraftServer server) {
		if (room == null) {
			return "";
		}
		ServerLevel level = level(server);
		StringBuilder out = new StringBuilder("\n");
		int r = room.half + 2;
		for (int z = -r; z <= r; z += 1) {
			for (int x = -r; x <= r; x += 1) {
				BlockPos at = room.center.offset(x, 2, z);
				boolean spawn = room.spawns.contains(room.center.offset(x, 1, z));
				char c = spawn ? 's' : !level.getBlockState(at).isAir() ? '#' : !level.getBlockState(at.below()).isAir() ? '+' : '.';
				out.append(c);
			}
			out.append('\n');
		}
		return out.toString();
	}

	public static String jobLine() {
		return RiftObjectives.bar() + " | room shape " + (room == null ? "-" : room.shape);
	}

	/** For autotests: where the way onward is. */
	public static Vec3 portalPos() {
		return room == null ? null : room.portal;
	}

	/** For autotests: the state in one line. */
	public static String debugState() {
		return state + " timer " + timer + " work " + WORK.size() + " node " + (node == null ? "-" : node.id + ":" + node.type + "/" + node.theme)
				+ " wave " + wave + "/" + waves + " mobs " + MOBS.size() + " pending " + PENDING.size() + " gold " + gold + " runners "
				+ RUNNERS.values().stream().map(r -> r.name + (r.alive ? "" : "(down)") + r.relics.stream().map(Relic::id).toList()).toList()
				+ (boss != null ? " boss " + boss.debug() : "");
	}

	/** For autotests: skip ahead. */
	public static void testSkip(MinecraftServer server, String what) {
		switch (what) {
			case "start" -> timer = 1;
			case "clear" -> {
				ServerLevel level = level(server);
				PENDING.clear();
				for (UUID id : new ArrayList<>(MOBS)) {
					if (level.getEntity(id) instanceof LivingEntity e) {
						e.hurtServer(level, level.damageSources().generic(), 10000);
					}
				}
				wave = waves;
			}
			case "act2", "act3" -> {
				act = what.equals("act2") ? 2 : 3;
				nextAct = false;
				for (Runner r : RUNNERS.values()) {
					r.offer = List.of();
				}
				map = RiftMap.generate(RandomSource.create(seed + act), act);
				testSkip(server, "boss");
			}
			case "tank" -> {
				if (map != null) {
					RiftMap.Node t = new RiftMap.Node(map.nodes.size(), 3, 0, 1, RiftMap.Type.TANK, RiftRoom.Theme.FORGE);
					map.nodes.add(t);
					RiftMap.Node from = map.visited.isEmpty() ? map.floor(0).get(0) : map.node(map.visited.get(map.visited.size() - 1));
					from.next.add(t.id);
					if (!map.visited.contains(from.id)) {
						map.visited.add(from.id);
					}
					for (Runner r : RUNNERS.values()) {
						r.vote = t.id;
					}
					state = State.VOTE;
					timer = 1;
				}
			}
			case "boss" -> {
				if (map != null) {
					RiftMap.Node b = map.nodes.stream().filter(n -> n.type == RiftMap.Type.BOSS).findFirst().orElseThrow();
					map.visited.add(map.floor(RiftMap.FLOORS - 1).get(0).id);
					for (Runner r : RUNNERS.values()) {
						r.vote = b.id;
					}
					node = b;
					state = State.VOTE;
					timer = 1;
				}
			}
			default -> {
				if (what.equals("spawn") && room != null && node != null) {
					// Six of the room's monsters, on the spawn points.
					for (int k = 0; k < 6; k++) {
						PENDING.add(new Pending(room.spawns.get(k % room.spawns.size()), RiftEnemies.pick(node.theme, RandomSource.create()), null, new int[] {5 + k}));
					}
					return;
				}
				if (what.startsWith("shape:")) {
					RiftRoom.forceShape = RiftRoom.Shape.valueOf(what.substring(6));
					return;
				}
				// "bossfight:STORM_WYRM": straight to that boss.
				if (what.startsWith("bossfight:") && map != null) {
					map.boss = RiftBosses.Boss.valueOf(what.substring(10));
					RiftMap.Node b = map.nodes.stream().filter(n -> n.type == RiftMap.Type.BOSS).findFirst().orElseThrow();
					map.nodes.set(b.id, new RiftMap.Node(b.id, b.floor, b.col, b.cols, RiftMap.Type.BOSS, map.boss.theme));
					map.node(b.id).next.addAll(b.next);
					testSkip(server, "boss");
					return;
				}
				if (what.startsWith("kills:")) {
					for (Runner rn : RUNNERS.values()) {
						rn.kills += Integer.parseInt(what.substring(6));
					}
					return;
				}
				if (what.equals("secret")) {
					RiftSecrets.force = true;
					return;
				}
				if (what.equals("opensecret") && RiftSecrets.where() != null) {
					RiftSecrets.onBlast(level(server), RiftSecrets.where(), 1);
					return;
				}
				if (what.equals("descend")) {
					descend(server);
					return;
				}
				if (what.startsWith("event:")) {
					forceEvent = Integer.parseInt(what.substring(6));
					testSkip(server, "room:EVENT");
					return;
				}
				if (what.equals("win")) {
					victory(server);
					return;
				}
				if (what.equals("killboss") && boss != null) {
					LivingEntity b = boss.body(level(server));
					if (b != null) {
						b.discard();
					}
					return;
				}
				if (what.startsWith("key:")) {
					grantKey(server, what.substring(4), "Key of " + what.substring(4));
					return;
				}
				if (what.startsWith("charm:")) {
					for (ServerPlayer p : players(server)) {
						RiftCharms.give(p, RiftCharms.Charm.valueOf(what.substring(6)));
					}
					return;
				}
				// "offer:a,b,c": show these relics as the offer; "nooffer" takes it away. "do:blink", "do:reloaded": as if you did.
				if (what.startsWith("offer:") || what.equals("nooffer") || what.equals("do:blink") || what.equals("do:reloaded")) {
					for (ServerPlayer p : players(server)) {
						Runner rn = RUNNERS.get(p.getUUID());
						if (what.startsWith("offer:")) {
							rn.offer = java.util.Arrays.stream(what.substring(6).split(",")).map(Relic::byId).toList();
							sendOffer(p, rn, "Test offer");
						} else if (what.equals("nooffer")) {
							rn.offer = List.of();
							rn.event = List.of();
						} else if (what.equals("do:blink")) {
							RelicEffects.ability(p, "blink");
						} else {
							RelicEffects.onReloaded(p);
						}
					}
					return;
				}
				// "room:SHOP", "room:REST"...: the next room is one of those.
				if (what.startsWith("room:") && map != null) {
					String[] parts = what.split(":");
					RiftMap.Type type = RiftMap.Type.valueOf(parts[1]);
					RiftMap.Node t = new RiftMap.Node(map.nodes.size(), 3, 0, 1, type, RiftRoom.Theme.CRYSTAL);
					t.burning = parts.length > 2 && parts[2].equals("burning");
					map.nodes.add(t);
					RiftMap.Node from = map.visited.isEmpty() ? map.floor(0).get(0) : map.node(map.visited.get(map.visited.size() - 1));
					from.next.add(t.id);
					if (!map.visited.contains(from.id)) {
						map.visited.add(from.id);
					}
					for (Runner rn : RUNNERS.values()) {
						rn.vote = t.id;
					}
					state = State.VOTE;
					timer = 1;
					return;
				}
				Relic r = Relic.byId(what);
				if (r != null) {
					for (ServerPlayer p : players(server)) {
						give(p, RUNNERS.get(p.getUUID()), r);
					}
				}
			}
		}
	}
}
