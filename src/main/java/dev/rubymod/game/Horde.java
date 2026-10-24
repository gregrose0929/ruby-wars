package dev.rubymod.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.math.Transformation;

import dev.rubymod.RubyMod;
import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.RocketEntity;
import dev.rubymod.entity.SoldierEntity;
import dev.rubymod.gun.GunItem;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.mixin.TextDisplayAccessor;
import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;
import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModDamage;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Unit;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Difficulty;
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
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Co-op Horde: everyone in it fights together in a walled outpost (in the arena
 * dimension) to protect the Supply Cache in the middle from waves of enemies pouring
 * in through four gates. Some enemies hunt players, some go for the cache.
 *
 * <ul>
 * <li>Waves scale with the team: a solo game is much smaller than a game for six, and
 * enemies get tougher per player as well as per wave. Easy / Normal / Hard on top.</li>
 * <li>Clear a wave: everyone picks 1 of 3 rewards, gets ammo and full health, and the
 * cache is patched up. 15 seconds to get ready.</li>
 * <li>Go down and a teammate can crouch next to you for 3 seconds to bring you back;
 * otherwise you're back at the next wave. Everyone gets one Second Wind per game for
 * when the whole team is down. It's over when the team is down for good or the cache
 * is destroyed.</li>
 * <li>Every 5th wave brings Brutes, every 5th from wave 4 is a fog wave, and from wave
 * 7 every 5th is an air raid with an enemy gunship (everyone gets a Skyhammer).</li>
 * </ul>
 *
 * Your Ruby Wars things are safe in the hub's save the whole time; nothing from Horde
 * comes back with you.
 */
public final class Horde {
	/** Middle of the outpost, far from the duel arenas. */
	public static final int CX = -20000;
	public static final int CZ = 0;
	private static final int GROUND = Duels.GROUND;
	/** Half the outpost's inside width. */
	private static final int HALF = 34;
	private static final int BREAK_TICKS = 15 * 20;
	private static final int REVIVE_TICKS = 60;
	private static final String MOB_TAG = "rubymod_horde";
	private static final String RAIDER_TAG = "rubymod_horde_raider";
	/** The raid id horde soldiers carry (they're raiders that don't loot anyone's bank). */
	public static final int SOLDIER_RAID_ID = -2;

	public enum Challenge {
		EASY("Easy", 0.7, 0.75, 0.7, 1.5),
		NORMAL("Normal", 1.0, 1.0, 1.0, 1.0),
		HARD("Hard", 1.35, 1.3, 1.25, 0.8);

		public final String title;
		final double count;
		final double health;
		final double damage;
		final double cache;

		Challenge(String title, double count, double health, double damage, double cache) {
			this.title = title;
			this.count = count;
			this.health = health;
			this.damage = damage;
			this.cache = cache;
		}

		public static Challenge byName(String name) {
			for (Challenge c : values()) {
				if (c.name().equalsIgnoreCase(name)) {
					return c;
				}
			}
			return null;
		}
	}

	/** What you can pick after a wave. Unique ones are offered until you have them. */
	private enum Reward {
		SHOTGUN("Combat Shotgun", "Huge damage up close", "rubymod:combat_shotgun", 1, true),
		SNIPER("Longshot Sniper", "One shot, one kill, from far away", "rubymod:longshot_sniper", 2, true),
		ARMOR("Ruby Armor", "A full set, tougher than iron", "rubymod:ruby_chestplate", 3, true),
		ROCKETS("Rocket Launcher", "Blows up crowds and Brutes", "rubymod:rocket_launcher", 4, true),
		BLASTER("Ruby Blaster", "Bouncing energy shots", "rubymod:ruby_blaster", 5, true),
		JETPACK("Jetpack", "Wear it, hold Space in the air to fly", "rubymod:jetpack", 6, true),
		LANCER("Lancer", "Guided missiles for big targets", "rubymod:lancer", 7, true),
		SANDBAGS("24 Sandbags", "Build cover anywhere", "rubymod:sandbags", 1, false),
		WALLS("16 Concrete Walls", "Strong walls: block a gate!", "rubymod:concrete_wall", 3, false),
		TURRET("Sentry Turret", "Shoots enemies on its own (load it with Heavy Ammo)", "rubymod:sentry_turret", 4, false),
		APPLES("3 Golden Apples", "Eat one to heal fast", "minecraft:golden_apple", 1, false),
		GRENADES("5 Frag Grenades", "Throw them into a crowd", "rubymod:frag_grenade", 1, false),
		AMMO("Ammo Crate", "Lots of ammo for every gun you have", "rubymod:heavy_ammo", 2, false);

		final String title;
		final String about;
		final String icon;
		final int fromWave;
		final boolean unique;

		Reward(String title, String about, String icon, int fromWave, boolean unique) {
			this.title = title;
			this.about = about;
			this.icon = icon;
			this.fromWave = fromWave;
			this.unique = unique;
		}
	}

	/** What a late joiner gets for each wave already cleared (and the usual first pick). */
	private static final Reward[] STANDARD = {Reward.SHOTGUN, Reward.SANDBAGS, Reward.SNIPER, Reward.ARMOR, Reward.ROCKETS,
			Reward.WALLS, Reward.BLASTER, Reward.JETPACK, Reward.LANCER};

	private enum State { IDLE, COUNTDOWN, WAVE, BREAK, OVER }

	private enum Kind { ZOMBIE, HUSK, SPIDER, RUNNER, SKELETON, SAPPER, RIFLEMAN, SHIELD, JUGGERNAUT, AT_GUNNER }

	private static final class Fighter {
		final String name;
		boolean alive = true;
		boolean secondWind = true;
		int kills;
		int revives;
		/** Downed: where (a teammate revives you there), the sign over the spot, and revive progress in ticks. */
		Vec3 downAt;
		UUID marker;
		int reviveProgress;
		/** Ticks until a Second Wind gets you back up (-1: none coming). */
		int windIn = -1;
		/** Rewards on offer right now (null: nothing to pick). */
		List<Reward> offer;
		final Set<Reward> owned = EnumSet.noneOf(Reward.class);

		Fighter(String name) {
			this.name = name;
		}
	}

	private static State state = State.IDLE;
	private static Challenge challenge = Challenge.NORMAL;
	private static int wave;
	private static int timer;
	private static int toSpawn;
	private static int bosses;
	private static int total;
	private static boolean fogWave;
	private static UUID gunship;
	private static int gunshipBurst;
	private static int cacheHp;
	private static int cacheMax;
	private static long cacheAlertAt;
	private static final Map<UUID, Fighter> FIGHTERS = new LinkedHashMap<>();
	/** For autotests: stand-in players that aren't on the player list. */
	static final Map<UUID, ServerPlayer> TEST_PLAYERS = new HashMap<>();
	private static final Set<UUID> MOBS = new java.util.HashSet<>();
	private static final ServerBossEvent BAR = new ServerBossEvent(UUID.randomUUID(), Component.literal("Co-op Horde"),
			BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
	private static final ServerBossEvent CACHE_BAR = new ServerBossEvent(UUID.randomUUID(), Component.literal("Supply Cache"),
			BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.NOTCHED_10);
	private static int bestWave = -1;
	private static String bestTeam = "";

	private Horde() {
	}

	/** For a crash: send everyone in Horde to the hub and stop the game. */
	public static void crashed(MinecraftServer server, Throwable e) {
		RubyMod.LOGGER.error("Co-op Horde hit an error; sending its players to the hub", e);
		try {
			for (ServerPlayer p : players(server)) {
				p.sendSystemMessage(Component.literal("Oops, something went wrong in Co-op Horde. Sorry! Back to the hub.").withStyle(ChatFormatting.RED));
				Net.send(p, new MorePayloads.HordeOffer(0, List.of()));
				p.getInventory().clearContent();
				p.removeAllEffects();
				if (!TEST_PLAYERS.containsKey(p.getUUID())) {
					Hub.enterHub(p);
				}
			}
		} catch (RuntimeException again) {
			RubyMod.LOGGER.error("Couldn't clean up after Horde", again);
		}
		FIGHTERS.clear();
		stop(server.getLevel(Duels.ARENA));
	}

	public static void reset() {
		state = State.IDLE;
		FIGHTERS.clear();
		TEST_PLAYERS.clear();
		MOBS.clear();
		BAR.removeAllPlayers();
		CACHE_BAR.removeAllPlayers();
		gunship = null;
		bestWave = -1;
	}

	public static boolean playing(Player player) {
		return FIGHTERS.containsKey(player.getUUID());
	}

	/** Horde players never hurt each other (null: not a Horde matter). */
	public static Boolean canHurt(Player attacker, Player victim) {
		if (!playing(attacker) && !playing(victim)) {
			return null;
		}
		return false;
	}

	/** True for a block inside the outpost while a game is on (sentry turrets there shoot Horde enemies). */
	public static boolean inOutpost(ServerLevel at, BlockPos pos) {
		return state != State.IDLE && at.dimension() == Duels.ARENA && Math.abs(pos.getX() - CX) <= HALF + 12 && Math.abs(pos.getZ() - CZ) <= HALF + 12;
	}

	public static boolean isEnemy(Entity e) {
		return MOBS.contains(e.getUUID());
	}

	/** Waves cleared so far this game. */
	private static int cleared() {
		return state == State.WAVE ? wave - 1 : wave;
	}

	// ----- How big and how tough -----

	/** How many enemies (Brutes not counted) in wave n for this many players. */
	public static int waveSize(int n, int players, Challenge difficulty) {
		double perTeam = 0.55 + 0.45 * Math.max(1, players); // 1 player: x1, 2: x1.45, 4: x2.35, 6: x3.25
		return (int) Math.round((3 + 2.5 * n) * perTeam * difficulty.count);
	}

	private static int bossCount(int n, int players) {
		return n % 5 == 0 ? n / 5 + (players >= 4 ? 1 : 0) : 0;
	}

	private static double healthScale(boolean boss) {
		int p = Math.max(1, FIGHTERS.size());
		double scale = (1 + 0.06 * (wave - 1)) * (1 + 0.12 * (p - 1)) * challenge.health;
		return boss ? scale * (1 + 0.5 * (p - 1)) : scale;
	}

	private static int maxAlive() {
		return Math.min(40, 8 + 6 * Math.max(1, FIGHTERS.size()));
	}

	private static int spawnInterval() {
		return Math.max(3, 12 - 2 * FIGHTERS.size());
	}

	/** One line for hub signs and the game picker. */
	public static String status() {
		String best = bestWave > 0 ? "  •  Best: wave " + bestWave + (bestTeam.isEmpty() ? "" : " (" + bestTeam + ")") : "";
		return switch (state) {
			case IDLE -> "Nobody playing" + best;
			case COUNTDOWN -> "Starting (" + challenge.title + ")  •  " + FIGHTERS.size() + " playing" + best;
			case OVER -> "Game over, back soon" + best;
			default -> "Wave " + wave + " (" + challenge.title + ")  •  " + FIGHTERS.size() + " playing" + best;
		};
	}

	public static boolean running() {
		return state != State.IDLE;
	}

	// ----- Joining and leaving -----

	public static String canJoin(ServerPlayer player) {
		if (state == State.OVER) {
			return "A Horde game just ended: try again in a few seconds.";
		}
		if (player.level().getServer().getLevel(Duels.ARENA) == null) {
			return "The arena dimension is missing.";
		}
		return null;
	}

	/** Into the outpost (from the hub). Starts a game (at this difficulty) if nobody's playing. */
	public static void join(ServerPlayer player, Challenge difficulty) {
		MinecraftServer server = player.level().getServer();
		ServerLevel arena = server.getLevel(Duels.ARENA);
		loadBest(server);
		boolean starting = state == State.IDLE;
		if (starting) {
			challenge = difficulty == null ? Challenge.NORMAL : difficulty;
			startGame(arena);
		}
		int before = FIGHTERS.size();
		Fighter fighter = new Fighter(player.getName().getString());
		FIGHTERS.put(player.getUUID(), fighter);
		BAR.addPlayer(player);
		CACHE_BAR.addPlayer(player);
		player.stopRiding();
		place(arena, player, null);
		player.setHealth(player.getMaxHealth());
		giveKit(player, fighter, cleared());
		if (state == State.WAVE) {
			// A bigger team: more enemies still to come this wave.
			int extra = waveSize(wave, before + 1, challenge) - waveSize(wave, before, challenge);
			double left = total == 0 ? 0 : (toSpawn + MOBS.size()) / (double) total;
			int add = (int) Math.round(extra * left);
			toSpawn += add;
			total += add;
		}
		tell(server, Component.literal(fighter.name + " joined the Horde!").withStyle(ChatFormatting.GREEN));
		player.sendSystemMessage(Component.literal("Co-op Horde (" + challenge.title + "): protect the Supply Cache in the middle and survive. "
				+ "Clear a wave to pick a reward. If a teammate goes down, crouch next to them to bring them back. H or /hub to leave.")
				.withStyle(ChatFormatting.GOLD));
		if (starting && difficulty == null) {
			player.sendSystemMessage(difficultyPicker());
		}
		updateBars();
	}

	private static Component difficultyPicker() {
		MutableComponent line = Component.literal("Difficulty before wave 1: ").withStyle(ChatFormatting.GRAY);
		for (Challenge l : Challenge.values()) {
			ChatFormatting color = l == Challenge.EASY ? ChatFormatting.GREEN : l == Challenge.NORMAL ? ChatFormatting.YELLOW : ChatFormatting.RED;
			line.append(Component.literal("[" + l.title.toUpperCase() + "]").withStyle(s -> s.withColor(color).withBold(true)
					.withClickEvent(new ClickEvent.RunCommand("/play horde " + l.name().toLowerCase()))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Play on " + l.title)))));
			line.append(" ");
		}
		return line;
	}

	/** Change the difficulty (only before the first wave). Returns an error, or null. */
	public static String setDifficulty(ServerPlayer player, Challenge difficulty) {
		if (!playing(player)) {
			return "You're not in Horde.";
		}
		if (state != State.COUNTDOWN) {
			return "The difficulty can only change before wave 1 (it's " + challenge.title + ").";
		}
		challenge = difficulty;
		resetCache();
		tell(player.level().getServer(), Component.literal(player.getName().getString() + " set the difficulty to " + challenge.title + ".")
				.withStyle(ChatFormatting.YELLOW));
		updateBars();
		return null;
	}

	/** Out of the game (to go somewhere else): Horde gear stays behind. */
	public static void leave(ServerPlayer player) {
		drop(player.level().getServer(), player);
		player.getInventory().clearContent();
		player.removeAllEffects();
		player.clearFire();
		player.inventoryMenu.broadcastChanges();
	}

	public static void onLeave(MinecraftServer server, ServerPlayer player) {
		if (playing(player)) {
			drop(server, player);
		}
	}

	private static void drop(MinecraftServer server, ServerPlayer player) {
		int before = FIGHTERS.size();
		Fighter fighter = FIGHTERS.remove(player.getUUID());
		BAR.removePlayer(player);
		CACHE_BAR.removePlayer(player);
		Net.send(player, new MorePayloads.HordeOffer(0, List.of()));
		if (fighter == null || !server.isRunning()) {
			return;
		}
		ServerLevel arena = server.getLevel(Duels.ARENA);
		removeMarker(arena, fighter);
		tell(server, Component.literal(fighter.name + " left the Horde.").withStyle(ChatFormatting.GRAY));
		if (FIGHTERS.isEmpty()) {
			stop(arena);
			return;
		}
		if (state == State.WAVE) {
			// A smaller team: fewer enemies still to come.
			int less = waveSize(wave, before, challenge) - waveSize(wave, before - 1, challenge);
			toSpawn = Math.max(0, toSpawn - less);
			if (noneUp()) {
				teamDown(server, arena, null);
			}
		}
		updateBars();
	}

	// ----- The game -----

	private static void startGame(ServerLevel arena) {
		clearArea(arena);
		Outpost.build(arena);
		wave = 0;
		state = State.COUNTDOWN;
		timer = 15 * 20;
		gunship = null;
		MOBS.clear();
		resetCache();
	}

	private static void resetCache() {
		cacheMax = (int) Math.round(300 * challenge.cache);
		cacheHp = cacheMax;
	}

	private static void stop(ServerLevel arena) {
		state = State.IDLE;
		wave = 0;
		BAR.removeAllPlayers();
		CACHE_BAR.removeAllPlayers();
		if (arena != null) {
			clearArea(arena);
		}
		MOBS.clear();
		gunship = null;
		TEST_PLAYERS.clear();
	}

	private static void clearArea(ServerLevel arena) {
		BlockPos c = center();
		int r = HALF + 30;
		for (Entity e : arena.getEntities((Entity) null, new AABB(c.getX() - r, -64, c.getZ() - r, c.getX() + r, 400, c.getZ() + r),
				e -> !(e instanceof Player))) {
			e.discard();
		}
	}

	private static BlockPos center() {
		return new BlockPos(CX, GROUND + 1, CZ);
	}

	private static Vec3 cachePos() {
		return Vec3.atBottomCenterOf(center());
	}

	public static void tick(MinecraftServer server) {
		Outpost.runWork(5_000_000L);
		if (state == State.IDLE) {
			return;
		}
		ServerLevel arena = server.getLevel(Duels.ARENA);
		if (arena == null) {
			return;
		}
		if (FIGHTERS.isEmpty()) {
			stop(arena);
			return;
		}
		if (server.getTickCount() % 2 == 0) {
			tickDowned(server, arena);
		}
		switch (state) {
			case COUNTDOWN, BREAK -> {
				if (!Outpost.WORK.isEmpty()) {
					break; // still building the outpost
				}
				timer--;
				if (timer > 0 && timer % 20 == 0) {
					int seconds = timer / 20;
					if (seconds <= 5) {
						String next = waveName(wave + 1);
						for (ServerPlayer p : players(server)) {
							Alerts.title(p, String.valueOf(seconds), next, ChatFormatting.YELLOW);
							p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 0.8F, 1.0F);
						}
					}
					updateBars();
				}
				if (timer <= 0) {
					startWave(server, arena, wave + 1);
				}
			}
			case WAVE -> {
				if (server.getTickCount() % spawnInterval() == 0 && (toSpawn > 0 || bosses > 0) && MOBS.size() < maxAlive()) {
					spawnOne(arena);
				}
				tickGunship(server, arena);
				if (server.getTickCount() % 20 == 0) {
					tidy(server, arena);
					if (state == State.WAVE && toSpawn == 0 && bosses == 0 && MOBS.isEmpty()) {
						waveCleared(server, arena);
					} else {
						updateBars();
					}
				}
			}
			case OVER -> {
				if (--timer <= 0) {
					for (ServerPlayer p : players(server)) {
						FIGHTERS.remove(p.getUUID());
						BAR.removePlayer(p);
						CACHE_BAR.removePlayer(p);
						Net.send(p, new MorePayloads.HordeOffer(0, List.of()));
						p.getInventory().clearContent();
						p.removeAllEffects();
						if (!TEST_PLAYERS.containsKey(p.getUUID())) {
							Hub.enterHub(p);
						}
					}
					FIGHTERS.clear();
					stop(arena);
				}
			}
			default -> {
			}
		}
	}

	private static ServerPlayer online(MinecraftServer server, UUID id) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		return p != null ? p : TEST_PLAYERS.get(id);
	}

	private static List<ServerPlayer> players(MinecraftServer server) {
		List<ServerPlayer> list = new ArrayList<>();
		for (UUID id : FIGHTERS.keySet()) {
			ServerPlayer p = online(server, id);
			if (p != null) {
				list.add(p);
			}
		}
		return list;
	}

	private static boolean noneUp() {
		return FIGHTERS.values().stream().noneMatch(f -> f.alive || f.windIn >= 0);
	}

	private static boolean bossWave(int n) {
		return n % 5 == 0;
	}

	private static boolean fogWave(int n) {
		return n % 5 == 4;
	}

	private static boolean airRaid(int n) {
		return n >= 7 && n % 5 == 2;
	}

	private static String waveName(int n) {
		return bossWave(n) ? "Wave " + n + ": BRUTES!" : fogWave(n) ? "Wave " + n + ": FOG" : airRaid(n) ? "Wave " + n + ": AIR RAID" : "Wave " + n + " is coming";
	}

	private static void startWave(MinecraftServer server, ServerLevel arena, int n) {
		wave = n;
		state = State.WAVE;
		int p = Math.max(1, FIGHTERS.size());
		bosses = bossWave(n) ? bossCount(n, p) : 0;
		toSpawn = waveSize(n, p, challenge);
		total = toSpawn + bosses + (airRaid(n) ? 1 : 0);
		fogWave = fogWave(n);
		for (ServerPlayer player : players(server)) {
			Fighter f = FIGHTERS.get(player.getUUID());
			// Anything not picked from the last offer: take the first one.
			if (f.offer != null) {
				give(player, f, f.offer.get(0));
				player.sendSystemMessage(Component.literal("Picked for you: " + f.offer.get(0).title).withStyle(ChatFormatting.GRAY));
				f.offer = null;
				Net.send(player, new MorePayloads.HordeOffer(0, List.of()));
			}
			if (!f.alive) {
				revive(arena, player, f, null);
				player.sendSystemMessage(Component.literal("You're back in!").withStyle(ChatFormatting.GREEN));
			}
			String sub = bosses > 0 ? (bosses == 1 ? "A Brute is coming!" : bosses + " Brutes are coming!")
					: fogWave ? "Fog: you can't see far, but they glow" : airRaid(n) ? "Shoot down the gunship with your Skyhammer!" : total + " enemies";
			Alerts.title(player, "WAVE " + n, sub, bosses > 0 || airRaid(n) ? ChatFormatting.DARK_RED : ChatFormatting.RED);
			player.level().playSound(null, player.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.PLAYERS, 0.7F, 1.0F);
			if (fogWave) {
				// Darkness closes in (night vision stays on, so it's spooky rather than pitch black).
				player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 20 * 60 * 10, 0, false, false));
			}
			if (airRaid(n) && !has(player, ModItems.SKYHAMMER)) {
				add(player, new ItemStack(ModItems.SKYHAMMER), new ItemStack(ModItems.AA_MISSILE, 6));
				player.sendSystemMessage(Component.literal("You got a Skyhammer: aim at the gunship and hold right-click to lock on.")
						.withStyle(ChatFormatting.GOLD));
			} else if (airRaid(n)) {
				add(player, new ItemStack(ModItems.AA_MISSILE, 4));
			}
			if (n == 5) {
				Goals.add(server, player.getUUID(), "horde5", 1);
			}
		}
		if (airRaid(n)) {
			spawnGunship(arena);
		}
		updateBars();
	}

	private static void waveCleared(MinecraftServer server, ServerLevel arena) {
		state = State.BREAK;
		timer = BREAK_TICKS;
		if (wave > bestWave) {
			bestWave = wave;
			bestTeam = String.join(", ", FIGHTERS.values().stream().map(f -> f.name).toList()) + ", " + challenge.title;
			saveBest(server);
		}
		// Patch up the cache a bit.
		cacheHp = Math.min(cacheMax, cacheHp + cacheMax * 3 / 10);
		for (ServerPlayer p : players(server)) {
			Fighter f = FIGHTERS.get(p.getUUID());
			topUp(p, f, 1);
			if (f.alive && !p.isSpectator()) {
				p.setHealth(p.getMaxHealth());
			}
			if (fogWave) {
				p.removeEffect(MobEffects.DARKNESS);
			}
			offer(p, f);
			Alerts.title(p, "WAVE " + wave + " CLEARED", "Pick your reward!", ChatFormatting.GREEN);
			p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8F, 1.0F);
			p.sendSystemMessage(Component.literal("Wave " + wave + " cleared! Pick a reward (the menu that just opened). Ammo and health refilled, "
					+ "the cache patched up. Next wave in 15 seconds.").withStyle(ChatFormatting.GREEN));
		}
		fogWave = false;
		updateBars();
	}

	/** The team is down for good, or the cache fell. */
	private static void gameOver(MinecraftServer server, ServerLevel arena, String why) {
		state = State.OVER;
		timer = 10 * 20;
		int survived = wave - 1;
		for (UUID id : new ArrayList<>(MOBS)) {
			Entity e = arena.getEntity(id);
			if (e != null) {
				e.discard();
			}
		}
		MOBS.clear();
		gunship = null;
		for (Fighter f : FIGHTERS.values()) {
			removeMarker(arena, f);
		}
		for (Map.Entry<UUID, Fighter> e : FIGHTERS.entrySet()) {
			if (!TEST_PLAYERS.containsKey(e.getKey())) {
				Stats.horde(e.getKey(), e.getValue().name, survived, e.getValue().kills, e.getValue().revives);
			}
		}
		StringBuilder kills = new StringBuilder();
		FIGHTERS.values().stream().sorted(Comparator.comparingInt((Fighter f) -> f.kills).reversed())
				.forEach(f -> kills.append(kills.isEmpty() ? "" : ", ").append(f.name).append(" ").append(f.kills)
						.append(f.revives > 0 ? " (" + f.revives + (f.revives == 1 ? " revive)" : " revives)") : ""));
		for (ServerPlayer p : players(server)) {
			Alerts.title(p, "GAME OVER", why, ChatFormatting.RED);
			p.level().playSound(null, p.blockPosition(), SoundEvents.WITHER_DEATH, SoundSource.PLAYERS, 0.5F, 1.0F);
			p.removeEffect(MobEffects.DARKNESS);
			Net.send(p, new MorePayloads.HordeOffer(0, List.of()));
			p.sendSystemMessage(Component.literal("====== HORDE (" + challenge.title + "): you survived " + survived + (survived == 1 ? " wave" : " waves")
					+ " ======").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			p.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.RED));
			p.sendSystemMessage(Component.literal("Kills: " + kills).withStyle(ChatFormatting.GRAY));
			p.sendSystemMessage(Component.literal("Best ever: wave " + Math.max(bestWave, survived) + ". Back to the hub in 10 seconds.")
					.withStyle(ChatFormatting.GRAY));
		}
		updateBars();
	}

	private static void updateBars() {
		int left = toSpawn + bosses + MOBS.size();
		switch (state) {
			case WAVE -> {
				BAR.setName(Component.literal("Wave " + wave + (fogWave ? " (fog)" : "") + "   •   " + left + " enemies left"));
				BAR.setColor(BossEvent.BossBarColor.RED);
				BAR.setProgress(total == 0 ? 0 : Math.min(1.0F, left / (float) total));
			}
			case OVER -> {
				BAR.setName(Component.literal("Game over: wave " + wave));
				BAR.setProgress(0);
			}
			default -> {
				String head = wave == 0 ? "Get ready! (" + challenge.title + ")" : "Wave " + wave + " cleared!";
				BAR.setName(Component.literal(head + "   Next wave in " + Math.max(0, timer / 20)));
				BAR.setColor(BossEvent.BossBarColor.GREEN);
				BAR.setProgress(Math.max(0, timer) / (float) (wave == 0 ? 300 : BREAK_TICKS));
			}
		}
		CACHE_BAR.setName(Component.literal("Supply Cache  " + Math.max(0, cacheHp * 100 / Math.max(1, cacheMax)) + "%"));
		CACHE_BAR.setProgress(Math.max(0, Math.min(1.0F, cacheHp / (float) Math.max(1, cacheMax))));
		CACHE_BAR.setColor(cacheHp < cacheMax / 3 ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.YELLOW);
	}

	private static void tell(MinecraftServer server, Component message) {
		for (ServerPlayer p : players(server)) {
			p.sendSystemMessage(message);
		}
	}

	// ----- Players: placing, going down, reviving -----

	/** Stand ready to fight: by the cache, or at {@code at} (a revive). */
	private static void place(ServerLevel arena, ServerPlayer player, Vec3 at) {
		RandomSource random = player.getRandom();
		double x;
		double z;
		double y;
		if (at == null) {
			double angle = random.nextDouble() * Math.PI * 2;
			BlockPos c = center();
			x = c.getX() + 0.5 + Math.cos(angle) * 2.5;
			z = c.getZ() + 0.5 + Math.sin(angle) * 2.5;
			y = c.getY();
		} else {
			x = at.x;
			y = at.y;
			z = at.z;
		}
		player.teleportTo(arena, x, y, z, Set.<Relative>of(), at == null ? random.nextFloat() * 360.0F : player.getYRot(), 0.0F, true);
		player.setGameMode(GameType.SURVIVAL);
		player.removeAllEffects();
		player.clearFire();
		player.resetFallDistance();
		player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, -1, 0, false, false));
		if (fogWave && state == State.WAVE) {
			player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 20 * 60 * 10, 0, false, false));
		}
		SpawnShield.grant(player);
	}

	private static void revive(ServerLevel arena, ServerPlayer player, Fighter f, Vec3 at) {
		f.alive = true;
		f.windIn = -1;
		f.reviveProgress = 0;
		removeMarker(arena, f);
		f.downAt = null;
		place(arena, player, at);
		player.setHealth(at != null ? player.getMaxHealth() * 0.5F : player.getMaxHealth());
	}

	/** Someone in Horde would die: they're down instead. True when handled. */
	public static boolean onDeath(ServerPlayer victim, DamageSource source) {
		Fighter fighter = FIGHTERS.get(victim.getUUID());
		if (fighter == null) {
			return false;
		}
		MinecraftServer server = victim.level().getServer();
		ServerLevel arena = server.getLevel(Duels.ARENA);
		Vec3 where = victim.position();
		victim.stopRiding();
		victim.setHealth(victim.getMaxHealth());
		victim.removeAllEffects();
		victim.clearFire();
		victim.setGameMode(GameType.SPECTATOR);
		victim.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, -1, 0, false, false));
		if (!fighter.alive || state == State.OVER) {
			return true;
		}
		fighter.alive = false;
		fighter.downAt = where.y < GROUND - 2 ? cachePos() : where;
		fighter.reviveProgress = 0;
		if (noneUp()) {
			teamDown(server, arena, fighter);
			return true;
		}
		spawnMarker(arena, fighter);
		Alerts.title(victim, "DOWN!", "A teammate can crouch next to you to bring you back", ChatFormatting.RED);
		for (ServerPlayer p : players(server)) {
			if (p != victim) {
				p.sendSystemMessage(Component.literal(fighter.name + " is down! Crouch next to them for 3 seconds to bring them back.")
						.withStyle(ChatFormatting.RED));
				p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_HURT_SWEET_BERRY_BUSH, SoundSource.PLAYERS, 0.8F, 0.6F);
			}
		}
		return true;
	}

	/** Nobody is left standing: a Second Wind if someone still has one, otherwise game over. */
	private static void teamDown(MinecraftServer server, ServerLevel arena, Fighter last) {
		Fighter wind = last != null && last.secondWind ? last
				: FIGHTERS.values().stream().filter(f -> f.secondWind).findFirst().orElse(null);
		if (wind == null) {
			gameOver(server, arena, "The whole team is down.");
			return;
		}
		wind.secondWind = false;
		wind.windIn = 60;
		removeMarker(arena, wind);
		for (ServerPlayer p : players(server)) {
			Alerts.title(p, "SECOND WIND", wind.name + " gets back up in 3 seconds (once per game)", ChatFormatting.GOLD);
		}
		if (last != null && last != wind) {
			spawnMarker(arena, last);
		}
	}

	/** Every 2 ticks: revives in progress, markers, and Second Winds. */
	private static void tickDowned(MinecraftServer server, ServerLevel arena) {
		for (Map.Entry<UUID, Fighter> e : FIGHTERS.entrySet()) {
			Fighter f = e.getValue();
			ServerPlayer downed = online(server, e.getKey());
			if (f.alive || downed == null) {
				continue;
			}
			if (f.windIn >= 0) {
				f.windIn -= 2;
				if (f.windIn <= 0 && state != State.OVER) {
					revive(arena, downed, f, null);
					Alerts.title(downed, "BACK UP!", "Second Wind: that was your one", ChatFormatting.GOLD);
				}
				continue;
			}
			if (f.downAt == null || state == State.OVER) {
				continue;
			}
			if (server.getTickCount() % 10 == 0) {
				arena.sendParticles(new DustParticleOptions(0xFF3030, 1.5F), f.downAt.x, f.downAt.y + 0.3, f.downAt.z, 6, 0.4, 0.1, 0.4, 0);
			}
			ServerPlayer reviver = null;
			for (ServerPlayer p : players(server)) {
				Fighter pf = FIGHTERS.get(p.getUUID());
				if (pf != null && pf.alive && p.isShiftKeyDown() && !p.isSpectator() && p.level() == arena
						&& p.position().distanceTo(f.downAt) < 2.5) {
					reviver = p;
					break;
				}
			}
			if (reviver == null) {
				f.reviveProgress = Math.max(0, f.reviveProgress - 2);
				continue;
			}
			f.reviveProgress += 2;
			int filled = Math.min(10, f.reviveProgress * 10 / REVIVE_TICKS);
			String bar = "█".repeat(filled) + "░".repeat(10 - filled);
			reviver.sendOverlayMessage(Component.literal("Reviving " + f.name + "  " + bar).withStyle(ChatFormatting.GREEN));
			downed.sendOverlayMessage(Component.literal(reviver.getName().getString() + " is reviving you  " + bar).withStyle(ChatFormatting.GREEN));
			if (f.reviveProgress >= REVIVE_TICKS) {
				FIGHTERS.get(reviver.getUUID()).revives++;
				revive(arena, downed, f, f.downAt);
				Alerts.title(downed, "REVIVED", "by " + reviver.getName().getString(), ChatFormatting.GREEN);
				reviver.sendOverlayMessage(Component.literal("You brought " + f.name + " back!").withStyle(ChatFormatting.GREEN));
				arena.playSound(null, reviver.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.6F, 1.3F);
			}
		}
	}

	private static void spawnMarker(ServerLevel arena, Fighter f) {
		removeMarker(arena, f);
		Display.TextDisplay sign = EntityTypes.TEXT_DISPLAY.create(arena, EntitySpawnReason.EVENT);
		if (sign == null || f.downAt == null) {
			return;
		}
		sign.snapTo(f.downAt.x, f.downAt.y + 1.0, f.downAt.z, 0, 0);
		((DisplayAccessor) sign).rubymod$setBillboard(Display.BillboardConstraints.CENTER);
		((TextDisplayAccessor) sign).rubymod$setText(Component.literal("✚ " + f.name + " is down\n").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
				.append(Component.literal("Crouch here to revive").withStyle(ChatFormatting.WHITE)));
		((TextDisplayAccessor) sign).rubymod$setBackground(0xA0000000);
		arena.addFreshEntity(sign);
		f.marker = sign.getUUID();
	}

	private static void removeMarker(ServerLevel arena, Fighter f) {
		if (f.marker != null && arena != null) {
			Entity e = arena.getEntity(f.marker);
			if (e != null) {
				e.discard();
			}
		}
		f.marker = null;
	}

	/** A non-player died: if it was a Horde enemy, count it (and the kill). */
	public static void onEntityDeath(LivingEntity entity, DamageSource source) {
		if (!MOBS.remove(entity.getUUID())) {
			return;
		}
		if (entity.getUUID().equals(gunship)) {
			gunship = null;
		}
		if (source.getEntity() instanceof ServerPlayer killer && FIGHTERS.containsKey(killer.getUUID())) {
			FIGHTERS.get(killer.getUUID()).kills++;
			int left = toSpawn + bosses + MOBS.size();
			killer.sendOverlayMessage(Component.literal("+1 kill  (" + left + " left)").withStyle(ChatFormatting.GREEN));
		}
		updateBars();
	}

	// ----- Kits and rewards -----

	private static ItemStack gun(Item item) {
		ItemStack stack = new ItemStack(item);
		stack.set(ModComponents.AMMO, ((GunItem) item).stats().magazine());
		return stack;
	}

	private static void add(ServerPlayer player, ItemStack... stacks) {
		for (ItemStack stack : stacks) {
			if (!player.getInventory().add(stack)) {
				player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
			}
		}
	}

	private static boolean has(ServerPlayer player, Item item) {
		var inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (inventory.getItem(i).is(item)) {
				return true;
			}
		}
		return false;
	}

	/** The starting kit, plus the usual rewards for every wave already cleared (for anyone joining late). */
	private static void giveKit(ServerPlayer player, Fighter fighter, int cleared) {
		player.getInventory().clearContent();
		// The rifle takes Heavy Ammo, the pistol Light Ammo.
		add(player, gun(ModItems.ARR_RIFLE), gun(ModItems.SERVICE_PISTOL), new ItemStack(ModItems.RUBY_SWORD), new ItemStack(ModItems.FRAG_GRENADE, 2),
				new ItemStack(ModItems.HEAVY_AMMO, 64), new ItemStack(ModItems.HEAVY_AMMO, 64), new ItemStack(ModItems.LIGHT_AMMO, 48));
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
		player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
		player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
		for (int w = 1; w <= cleared; w++) {
			give(player, fighter, w <= STANDARD.length ? STANDARD[w - 1] : Reward.GRENADES);
		}
		player.getInventory().setSelectedSlot(0);
		player.inventoryMenu.broadcastChanges();
	}

	/** Three rewards to choose from (new gear you don't have yet comes up more often). */
	private static void offer(ServerPlayer player, Fighter f) {
		RandomSource random = player.getRandom();
		List<Reward> pool = new ArrayList<>();
		for (Reward r : Reward.values()) {
			if (r.fromWave <= wave && !(r.unique && f.owned.contains(r))) {
				pool.add(r);
			}
		}
		List<Reward> picks = new ArrayList<>();
		while (picks.size() < 3 && !pool.isEmpty()) {
			int sum = 0;
			for (Reward r : pool) {
				sum += r.unique ? 3 : 2;
			}
			int roll = random.nextInt(sum);
			for (Reward r : pool) {
				roll -= r.unique ? 3 : 2;
				if (roll < 0) {
					picks.add(r);
					pool.remove(r);
					break;
				}
			}
		}
		f.offer = picks;
		List<String> lines = new ArrayList<>();
		for (Reward r : picks) {
			lines.add(r.name() + "|" + r.title + "|" + r.about + "|" + r.icon);
		}
		Net.send(player, new MorePayloads.HordeOffer(wave, lines));
	}

	/** The player clicked a reward. */
	public static void pick(ServerPlayer player, String id) {
		Fighter f = FIGHTERS.get(player.getUUID());
		if (f == null || f.offer == null) {
			return;
		}
		for (Reward r : f.offer) {
			if (r.name().equals(id)) {
				give(player, f, r);
				f.offer = null;
				player.sendSystemMessage(Component.literal("You took: " + r.title).withStyle(ChatFormatting.GREEN));
				player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8F, 1.0F);
				Net.send(player, new MorePayloads.HordeOffer(0, List.of()));
				return;
			}
		}
	}

	private static void give(ServerPlayer player, Fighter f, Reward r) {
		switch (r) {
			case SHOTGUN -> add(player, gun(ModItems.COMBAT_SHOTGUN), new ItemStack(ModItems.SHOTGUN_SHELL, 32));
			case SNIPER -> add(player, gun(ModItems.LONGSHOT_SNIPER), new ItemStack(ModItems.SNIPER_ROUND, 16));
			case ARMOR -> {
				player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.RUBY_HELMET));
				if (!player.getItemBySlot(EquipmentSlot.CHEST).is(ModItems.JETPACK)) {
					player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.RUBY_CHESTPLATE));
				} else {
					add(player, new ItemStack(ModItems.RUBY_CHESTPLATE));
				}
				player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModItems.RUBY_LEGGINGS));
				player.setItemSlot(EquipmentSlot.FEET, new ItemStack(ModItems.RUBY_BOOTS));
			}
			case ROCKETS -> add(player, new ItemStack(ModItems.ROCKET_LAUNCHER), new ItemStack(ModItems.ROCKET, 8));
			case BLASTER -> add(player, gun(ModItems.RUBY_BLASTER), new ItemStack(ModItems.RUBY_CELL, 64));
			case JETPACK -> add(player, new ItemStack(ModItems.JETPACK));
			case LANCER -> add(player, new ItemStack(ModItems.LANCER), new ItemStack(ModItems.AT_MISSILE, 6));
			case SANDBAGS -> add(player, new ItemStack(ModBlocks.SANDBAGS, 24));
			case WALLS -> add(player, new ItemStack(ModBlocks.CONCRETE_WALL, 16));
			case TURRET -> add(player, new ItemStack(ModBlocks.SENTRY_TURRET), new ItemStack(ModItems.HEAVY_AMMO, 64));
			case APPLES -> add(player, new ItemStack(Items.GOLDEN_APPLE, 3));
			case GRENADES -> add(player, new ItemStack(ModItems.FRAG_GRENADE, 5));
			case AMMO -> topUp(player, f, 2);
		}
		f.owned.add(r);
		player.inventoryMenu.broadcastChanges();
	}

	/** Ammo for everything you own, after each wave ({@code times} over for an Ammo Crate). */
	private static void topUp(ServerPlayer player, Fighter f, int times) {
		for (int i = 0; i < times; i++) {
			add(player, new ItemStack(ModItems.HEAVY_AMMO, 64), new ItemStack(ModItems.LIGHT_AMMO, 24));
			if (f.owned.contains(Reward.SHOTGUN)) {
				add(player, new ItemStack(ModItems.SHOTGUN_SHELL, 16));
			}
			if (f.owned.contains(Reward.SNIPER)) {
				add(player, new ItemStack(ModItems.SNIPER_ROUND, 8));
			}
			if (f.owned.contains(Reward.ROCKETS)) {
				add(player, new ItemStack(ModItems.ROCKET, 3));
			}
			if (f.owned.contains(Reward.BLASTER)) {
				add(player, new ItemStack(ModItems.RUBY_CELL, 32));
			}
			if (f.owned.contains(Reward.LANCER)) {
				add(player, new ItemStack(ModItems.AT_MISSILE, 2));
			}
		}
	}

	// ----- Enemies -----

	private static void spawnOne(ServerLevel arena) {
		RandomSource random = arena.getRandom();
		Direction gate = Direction.from2DDataValue(random.nextInt(4));
		BlockPos c = center();
		int along = HALF + 8 + random.nextInt(3);
		int side = random.nextInt(3) - 1;
		int x = c.getX() + gate.getStepX() * along + gate.getStepZ() * side;
		int z = c.getZ() + gate.getStepZ() * along + gate.getStepX() * side;
		boolean boss = bosses > 0;
		Kind kind = boss ? null : pickKind(arena, random);
		Mob mob = boss ? EntityTypes.RAVAGER.create(arena, EntitySpawnReason.EVENT) : create(arena, kind);
		if (mob == null) {
			return;
		}
		if (boss) {
			bosses--;
			mob.setCustomName(Component.literal("Brute").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
		} else {
			toSpawn--;
		}
		mob.snapTo(x + 0.5, c.getY(), z + 0.5, gate.getOpposite().toYRot(), 0);
		// Tougher every wave, and for every extra player.
		double extraHealth = kind == Kind.JUGGERNAUT ? 3.0 : kind == Kind.RUNNER ? 0.6 : 1.0;
		AttributeInstance health = mob.getAttribute(Attributes.MAX_HEALTH);
		if (health != null) {
			health.setBaseValue(health.getBaseValue() * healthScale(boss) * extraHealth);
			mob.setHealth(mob.getMaxHealth());
		}
		AttributeInstance attack = mob.getAttribute(Attributes.ATTACK_DAMAGE);
		if (attack != null) {
			attack.setBaseValue(attack.getBaseValue() * challenge.damage * (1 + 0.03 * (wave - 1)));
		}
		// Some go for the cache (they only notice players close by); the rest hunt players.
		boolean raider = boss || kind == Kind.SAPPER || kind != Kind.JUGGERNAUT && random.nextFloat() < 0.35F;
		if (raider) {
			mob.addTag(RAIDER_TAG);
		}
		AttributeInstance follow = mob.getAttribute(Attributes.FOLLOW_RANGE);
		if (follow != null) {
			follow.setBaseValue(raider ? 10.0 : 96.0);
		}
		// A helmet keeps zombies and skeletons from burning in daylight.
		if (!(mob instanceof SoldierEntity) && !(mob instanceof Ravager) && mob.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
			ItemStack helmet = new ItemStack(random.nextBoolean() ? Items.IRON_HELMET : Items.CHAINMAIL_HELMET);
			helmet.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
			mob.setItemSlot(EquipmentSlot.HEAD, helmet);
		}
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			mob.setDropChance(slot, 0.0F);
		}
		if (fogWave) {
			mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, -1, 0, false, false));
		}
		mob.setPersistenceRequired();
		mob.addTag(MOB_TAG);
		arena.addFreshEntity(mob);
		MOBS.add(mob.getUUID());
	}

	/** Which enemy next: zombies at first, then more and nastier kinds as the waves go on. */
	private static Kind pickKind(ServerLevel arena, RandomSource random) {
		if (arena.getDifficulty() == Difficulty.PEACEFUL) {
			// Monsters can't exist in Peaceful; soldiers still fight.
			int roll = random.nextInt(10);
			return wave >= 8 && roll == 0 ? Kind.AT_GUNNER : wave >= 5 && roll < 3 ? Kind.SHIELD : Kind.RIFLEMAN;
		}
		Map<Kind, Integer> weights = new LinkedHashMap<>();
		weights.put(Kind.ZOMBIE, 10);
		weights.put(Kind.HUSK, wave >= 2 ? 4 : 0);
		weights.put(Kind.SPIDER, wave >= 2 ? 3 : 0);
		weights.put(Kind.RUNNER, wave >= 3 ? 4 : 0);
		weights.put(Kind.SKELETON, wave >= 3 ? 5 : 0);
		weights.put(Kind.SAPPER, wave >= 4 ? 2 + wave / 4 : 0);
		weights.put(Kind.RIFLEMAN, wave >= 4 ? 3 + wave / 3 : 0);
		weights.put(Kind.SHIELD, wave >= 5 ? 2 + wave / 4 : 0);
		weights.put(Kind.JUGGERNAUT, wave >= 6 ? 1 + wave / 5 : 0);
		weights.put(Kind.AT_GUNNER, wave >= 8 ? 1 + wave / 6 : 0);
		int sum = weights.values().stream().mapToInt(Integer::intValue).sum();
		int roll = random.nextInt(sum);
		for (Map.Entry<Kind, Integer> e : weights.entrySet()) {
			roll -= e.getValue();
			if (roll < 0) {
				return e.getKey();
			}
		}
		return Kind.ZOMBIE;
	}

	private static Mob create(ServerLevel arena, Kind kind) {
		BlockPos c = center();
		RandomSource random = arena.getRandom();
		return switch (kind) {
			case HUSK -> EntityTypes.HUSK.create(arena, EntitySpawnReason.EVENT);
			case SPIDER -> EntityTypes.SPIDER.create(arena, EntitySpawnReason.EVENT);
			case SKELETON -> {
				var skeleton = EntityTypes.SKELETON.create(arena, EntitySpawnReason.EVENT);
				if (skeleton != null) {
					skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
					skeleton.reassessWeaponGoal();
				}
				yield skeleton;
			}
			case RUNNER -> {
				Zombie zombie = EntityTypes.ZOMBIE.create(arena, EntitySpawnReason.EVENT);
				if (zombie != null) {
					zombie.setBaby(true);
					zombie.setCustomName(Component.literal("Runner").withStyle(ChatFormatting.YELLOW));
				}
				yield zombie;
			}
			case JUGGERNAUT -> {
				Zombie zombie = EntityTypes.ZOMBIE.create(arena, EntitySpawnReason.EVENT);
				if (zombie != null) {
					zombie.setCustomName(Component.literal("Juggernaut").withStyle(ChatFormatting.DARK_RED));
					armor(zombie, EquipmentSlot.HEAD, Items.DIAMOND_HELMET);
					armor(zombie, EquipmentSlot.CHEST, Items.IRON_CHESTPLATE);
					armor(zombie, EquipmentSlot.LEGS, Items.IRON_LEGGINGS);
					armor(zombie, EquipmentSlot.FEET, Items.IRON_BOOTS);
					armor(zombie, EquipmentSlot.MAINHAND, Items.IRON_AXE);
					AttributeInstance speed = zombie.getAttribute(Attributes.MOVEMENT_SPEED);
					if (speed != null) {
						speed.setBaseValue(speed.getBaseValue() * 0.8);
					}
					AttributeInstance knockback = zombie.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
					if (knockback != null) {
						knockback.setBaseValue(0.8);
					}
				}
				yield zombie;
			}
			case SAPPER -> {
				Creeper creeper = EntityTypes.CREEPER.create(arena, EntitySpawnReason.EVENT);
				if (creeper != null) {
					creeper.setCustomName(Component.literal("Sapper").withStyle(ChatFormatting.GREEN));
				}
				yield creeper;
			}
			case RIFLEMAN -> SoldierEntity.horde(arena, c, SoldierEntity.RIFLEMAN);
			case SHIELD -> {
				SoldierEntity soldier = SoldierEntity.horde(arena, c, SoldierEntity.RIFLEMAN);
				if (soldier != null) {
					soldier.setShielded();
				}
				yield soldier;
			}
			case AT_GUNNER -> SoldierEntity.horde(arena, c, SoldierEntity.AT_GUNNER);
			default -> {
				Zombie zombie = EntityTypes.ZOMBIE.create(arena, EntitySpawnReason.EVENT);
				if (zombie != null && wave >= 3 && random.nextInt(3) == 0) {
					zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(random.nextBoolean() ? Items.IRON_SWORD : Items.IRON_SHOVEL));
				}
				yield zombie;
			}
		};
	}

	private static void armor(Mob mob, EquipmentSlot slot, Item item) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
		mob.setItemSlot(slot, stack);
	}

	/** How hard an enemy at the cache hits it each second. */
	private static int cacheDamage(Mob mob) {
		String name = mob.getCustomName() == null ? "" : mob.getCustomName().getString();
		double base = mob instanceof Ravager ? 12 : mob instanceof SoldierEntity ? 3 : name.equals("Juggernaut") ? 8 : 4;
		return (int) Math.max(1, Math.round(base * challenge.damage));
	}

	private static void damageCache(MinecraftServer server, ServerLevel arena, int amount) {
		if (state != State.WAVE) {
			return;
		}
		cacheHp -= amount;
		Vec3 c = cachePos();
		arena.sendParticles(ParticleTypes.CRIT, c.x, c.y + 1.2, c.z, 8, 0.4, 0.5, 0.4, 0.2);
		if (server.getTickCount() % 40 == 0) {
			arena.playSound(null, center(), SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.4F, 1.6F);
		}
		long now = System.currentTimeMillis();
		if (now - cacheAlertAt > 6000) {
			cacheAlertAt = now;
			for (ServerPlayer p : players(server)) {
				p.sendOverlayMessage(Component.literal("⚠ The Supply Cache is under attack!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			}
		}
		if (cacheHp <= 0) {
			cacheHp = 0;
			gameOver(server, arena, "The Supply Cache was destroyed.");
		}
	}

	/** Every second: forget the dead, send everyone after a player or the cache, rescue strays, sweep up drops. */
	private static void tidy(MinecraftServer server, ServerLevel arena) {
		List<ServerPlayer> targets = new ArrayList<>();
		for (ServerPlayer p : players(server)) {
			if (FIGHTERS.get(p.getUUID()).alive && !p.isSpectator() && p.level() == arena) {
				targets.add(p);
			}
		}
		BlockPos c = center();
		Vec3 cache = cachePos();
		int left = toSpawn + bosses + MOBS.size();
		for (Iterator<UUID> it = MOBS.iterator(); it.hasNext();) {
			Entity e = arena.getEntity(it.next());
			if (!(e instanceof Mob mob) || !mob.isAlive()) {
				it.remove();
				continue;
			}
			if (mob instanceof AircraftEntity) {
				continue; // the gunship flies itself
			}
			double away = Math.max(Math.abs(mob.getX() - c.getX()), Math.abs(mob.getZ() - c.getZ()));
			if (away > HALF + 14 || mob.getY() < GROUND - 4) {
				mob.teleportTo(c.getX() + 0.5, c.getY(), c.getZ() + HALF + 9.5);
			}
			ServerPlayer nearest = targets.stream().min(Comparator.comparingDouble(p -> p.distanceToSqr(mob))).orElse(null);
			boolean raider = mob.entityTags().contains(RAIDER_TAG) || mob instanceof Ravager;
			double toCache = Math.hypot(mob.getX() - cache.x, mob.getZ() - cache.z);
			if (raider && (nearest == null || nearest.distanceTo(mob) > (mob instanceof Creeper ? 5 : 8))) {
				// Going for the cache.
				if (mob instanceof Creeper && toCache < 2.8) {
					arena.explode(mob, mob.getX(), mob.getY(), mob.getZ(), 3.0F, Level.ExplosionInteraction.NONE);
					mob.discard();
					damageCache(server, arena, (int) Math.round(30 * challenge.damage));
					if (state != State.WAVE) {
						return;
					}
					continue;
				}
				if (toCache < (mob instanceof SoldierEntity ? 6.0 : mob instanceof Ravager ? 3.5 : 2.4)) {
					mob.getLookControl().setLookAt(cache.x, cache.y + 1, cache.z);
					damageCache(server, arena, cacheDamage(mob));
					if (state != State.WAVE) {
						return;
					}
				} else if (mob instanceof SoldierEntity soldier) {
					soldier.setObjective(c);
				} else {
					mob.setTarget(null);
					mob.getNavigation().moveTo(cache.x, cache.y, cache.z, mob instanceof Ravager ? 0.9 : 1.0);
				}
			} else if (nearest != null) {
				if (mob instanceof SoldierEntity soldier) {
					soldier.setObjective(nearest.blockPosition());
				} else if (!(mob.getTarget() instanceof ServerPlayer t) || !targets.contains(t)) {
					mob.setTarget(nearest);
				}
			}
			if (left <= 4 && toSpawn == 0 && bosses == 0) {
				// The last few glow, so nobody hunts for a hidden spider.
				mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false));
			}
		}
		// Keep the cache standing (explosions nearby can't knock it out).
		if (!arena.getBlockState(c).is(Blocks.IRON_BLOCK)) {
			arena.setBlock(c, Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		if (!arena.getBlockState(c.above()).is(Blocks.BEACON)) {
			arena.setBlock(c.above(), Blocks.BEACON.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		for (ItemEntity item : arena.getEntitiesOfClass(ItemEntity.class, new AABB(c).inflate(HALF + 16, 40, HALF + 16), i -> {
			ItemStack stack = i.getItem();
			return stack.is(Items.ROTTEN_FLESH) || stack.is(Items.BONE) || stack.is(Items.ARROW) || stack.is(Items.STRING)
					|| stack.is(Items.SPIDER_EYE) || stack.is(Items.SADDLE) || stack.is(Items.GUNPOWDER);
		})) {
			item.discard();
		}
	}

	// ----- The air raid gunship -----

	private static void spawnGunship(ServerLevel arena) {
		Vec3 c = cachePos().add(0, 24, 0);
		AircraftEntity heli = AircraftEntity.gunship(arena, c, 26);
		AttributeInstance health = heli.getAttribute(Attributes.MAX_HEALTH);
		if (health != null) {
			int p = Math.max(1, FIGHTERS.size());
			health.setBaseValue(health.getBaseValue() * (1 + 0.4 * (p - 1)) * challenge.health);
			heli.setHealth(heli.getMaxHealth());
		}
		heli.setCustomName(Component.literal("Enemy Gunship").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
		heli.addTag(MOB_TAG);
		arena.addFreshEntity(heli);
		MOBS.add(heli.getUUID());
		gunship = heli.getUUID();
		gunshipBurst = 0;
	}

	/** The gunship rakes the nearest player it can see with its gun, and fires a rocket every few seconds. */
	private static void tickGunship(MinecraftServer server, ServerLevel arena) {
		if (gunship == null) {
			return;
		}
		Entity e = arena.getEntity(gunship);
		if (!(e instanceof AircraftEntity heli) || !heli.isAlive()) {
			return;
		}
		int t = server.getTickCount();
		Vec3 gun = heli.position().add(0, -1.2, 0);
		ServerPlayer target = null;
		double best = Double.MAX_VALUE;
		for (ServerPlayer p : players(server)) {
			Fighter f = FIGHTERS.get(p.getUUID());
			if (f.alive && !p.isSpectator() && p.level() == arena && p.distanceToSqr(heli) < best
					&& arena.clip(new ClipContext(gun, p.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, heli)).getType() == HitResult.Type.MISS) {
				best = p.distanceToSqr(heli);
				target = p;
			}
		}
		if (target == null) {
			return;
		}
		Vec3 aim = target.position().add(0, 1.0, 0);
		Vec3 dir = aim.subtract(gun).normalize();
		RandomSource random = arena.getRandom();
		if (t % (int) Math.round(80 / challenge.damage) == 0) {
			Vec3 spread = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).scale(0.06);
			arena.addFreshEntity(RocketEntity.shell(arena, heli, gun.add(dir.scale(3.5)), dir.add(spread).normalize().scale(1.6), 2.0F));
			arena.playSound(null, heli.blockPosition(), dev.rubymod.registry.ModSounds.ROCKET_LAUNCH, SoundSource.HOSTILE, 2.0F, 0.9F);
		}
		if (t % 50 == 0) {
			gunshipBurst = 6;
		}
		if (gunshipBurst > 0 && t % 3 == 0) {
			gunshipBurst--;
			arena.playSound(null, heli.blockPosition(), dev.rubymod.registry.ModSounds.RIFLE_SHOT, SoundSource.HOSTILE, 2.0F, 0.7F);
			Vec3 end = aim.add(new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).scale(1.2));
			Vec3 step = end.subtract(gun).normalize();
			for (double d = 2; d < end.distanceTo(gun); d += 2.5) {
				Vec3 p = gun.add(step.scale(d));
				arena.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 1, 0, 0, 0, 0);
			}
			if (random.nextFloat() < 0.35F) {
				target.setInvulnerableTime(0);
				target.hurtServer(arena, ModDamage.source(arena, ModDamage.BULLET, heli, heli), (float) (2.5 * challenge.damage));
			}
		}
	}

	// ----- The best wave, kept across restarts -----

	private static Path bestFile(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("horde_best.txt");
	}

	private static void loadBest(MinecraftServer server) {
		if (bestWave >= 0) {
			return;
		}
		bestWave = 0;
		try {
			Path file = bestFile(server);
			if (Files.exists(file)) {
				List<String> lines = Files.readAllLines(file);
				bestWave = Integer.parseInt(lines.get(0).trim());
				bestTeam = lines.size() > 1 ? lines.get(1) : "";
			}
		} catch (IOException | RuntimeException e) {
			RubyMod.LOGGER.warn("Couldn't read the Horde record", e);
		}
	}

	/** For the hub signs: make sure the record is loaded. */
	public static void load(MinecraftServer server) {
		loadBest(server);
	}

	private static void saveBest(MinecraftServer server) {
		try {
			Path file = bestFile(server);
			Files.createDirectories(file.getParent());
			Files.writeString(file, bestWave + "\n" + bestTeam + "\n");
		} catch (IOException e) {
			RubyMod.LOGGER.warn("Couldn't save the Horde record", e);
		}
	}

	// ----- Testing -----

	/** For autotests: a stand-in teammate (a fake player) joins the game. */
	public static void addTestFighter(ServerPlayer fake) {
		TEST_PLAYERS.put(fake.getUUID(), fake);
		join(fake, null);
	}

	/** For autotests: knock a player down as if they'd died. */
	public static void knockDown(ServerPlayer player) {
		onDeath(player, player.damageSources().generic());
	}

	/** For autotests: jump to just before wave n (all enemies gone). */
	public static void jumpTo(MinecraftServer server, int n) {
		ServerLevel arena = server.getLevel(Duels.ARENA);
		for (ServerPlayer p : players(server)) {
			p.removeEffect(MobEffects.DARKNESS);
		}
		fogWave = false;
		for (UUID id : new ArrayList<>(MOBS)) {
			Entity e = arena.getEntity(id);
			if (e != null) {
				e.discard();
			}
		}
		MOBS.clear();
		gunship = null;
		toSpawn = 0;
		bosses = 0;
		wave = n - 1;
		state = State.BREAK;
		timer = 21;
	}

	/** For autotests: skip the countdown or the break. */
	public static void skipWait() {
		if (state == State.COUNTDOWN || state == State.BREAK) {
			timer = 21;
		}
	}

	/** For autotests: the scaling table, one line per team size. */
	public static List<String> scalingTable() {
		List<String> lines = new ArrayList<>();
		for (int p = 1; p <= 6; p++) {
			StringBuilder b = new StringBuilder(p + (p == 1 ? " player: " : " players:"));
			for (int n : new int[] {1, 3, 5, 10}) {
				b.append("  w").append(n).append("=").append(waveSize(n, p, Challenge.NORMAL)).append("+").append(bossCount(n, p));
			}
			b.append("  (easy w5=").append(waveSize(5, p, Challenge.EASY)).append(", hard w5=").append(waveSize(5, p, Challenge.HARD)).append(")");
			b.append("  max alive ").append(Math.min(40, 8 + 6 * p));
			lines.add(b.toString());
		}
		return lines;
	}

	public static String debugState() {
		return state + " wave " + wave + " toSpawn " + toSpawn + " alive " + MOBS.size() + " cache " + cacheHp + "/" + cacheMax + " fighters "
				+ FIGHTERS.values().stream().map(f -> f.name + (f.alive ? "" : "(down)") + (f.offer != null ? "(choosing)" : "")).toList()
				+ " difficulty " + challenge.title;
	}

	// ----- The outpost -----

	/**
	 * A walled outpost with a gate on each side (enemies pour in through tunnels behind
	 * them), the Supply Cache in a sandbagged command post in the middle, and the same
	 * cover in every quarter. Rebuilt at the start of every game.
	 */
	static final class Outpost {
		private static final int FLAGS = Block.UPDATE_CLIENTS;
		/** Building happens a few milliseconds a tick (no freezes), in order. */
		static final java.util.ArrayDeque<Runnable> WORK = new java.util.ArrayDeque<>();

		private Outpost() {
		}

		static void runWork(long nanos) {
			long end = System.nanoTime() + nanos;
			while (!WORK.isEmpty() && System.nanoTime() < end) {
				WORK.poll().run();
			}
		}

		private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
			BlockPos pos = new BlockPos(CX + x, GROUND + y, CZ + z);
			WORK.add(() -> {
				if (level.getBlockState(pos) != state) {
					level.setBlock(pos, state, FLAGS);
				}
			});
		}

		/** A box relative to the middle (y 0 = the ground's top block). */
		private static void box(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
			for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
				for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
					for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
						set(level, x, y, z, state);
					}
				}
			}
		}

		/** The same box in all four quarters (turned 90 degrees each time). */
		private static void quad(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
			int[] a = {x1, z1};
			int[] b = {x2, z2};
			for (int k = 0; k < 4; k++) {
				box(level, a[0], y1, a[1], b[0], y2, b[1], state);
				a = new int[] {-a[1], a[0]};
				b = new int[] {-b[1], b[0]};
			}
		}

		static void build(ServerLevel level) {
			int outer = HALF + 14;
			for (int cx = (CX - outer) >> 4; cx <= (CX + outer) >> 4; cx++) {
				for (int cz = (CZ - outer) >> 4; cz <= (CZ + outer) >> 4; cz++) {
					int fcx = cx;
					int fcz = cz;
					WORK.add(() -> level.getChunk(fcx, fcz));
				}
			}
			BlockState air = Blocks.AIR.defaultBlockState();
			BlockState barrier = Blocks.BARRIER.defaultBlockState();
			BlockState dirt = Blocks.DIRT.defaultBlockState();
			// Ground: grass with worn patches, and gravel roads from each gate to the middle.
			for (int x = -outer; x <= outer; x++) {
				for (int z = -outer; z <= outer; z++) {
					double h = dev.rubymod.world.WarzoneTerrain.hash3(x, 7, z);
					BlockState floor = Math.min(Math.abs(x), Math.abs(z)) <= 1 && Math.max(Math.abs(x), Math.abs(z)) > 6 ? Blocks.GRAVEL.defaultBlockState()
							: h < 0.12 ? Blocks.COARSE_DIRT.defaultBlockState() : h < 0.16 ? Blocks.PODZOL.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
					for (int y = -3; y <= 40; y++) {
						set(level, x, y, z, y < 0 ? dirt : y == 0 ? floor : y == 40 ? barrier : air);
					}
				}
			}
			BlockState wall = Blocks.STONE_BRICKS.defaultBlockState();
			BlockState cracked = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
			BlockState mossy = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
			int w = HALF + 1;
			// The outer wall, with an invisible cap up to the ceiling, and a gate in each side.
			for (int i = -w; i <= w; i++) {
				for (int[] p : new int[][] {{i, -w}, {i, w}, {-w, i}, {w, i}}) {
					double h = dev.rubymod.world.WarzoneTerrain.hash3(p[0], 3, p[1]);
					box(level, p[0], 1, p[1], p[0], 5, p[1], h < 0.2 ? cracked : h < 0.35 ? mossy : wall);
					box(level, p[0], 6, p[1], p[0], 39, p[1], barrier);
				}
			}
			quad(level, -2, 1, -w, 2, 39, -w, air);
			// Gate tunnels (where the enemies come from), closed at the far end.
			quad(level, -3, 1, -w - 11, -3, 5, -w - 1, wall);
			quad(level, 3, 1, -w - 11, 3, 5, -w - 1, wall);
			quad(level, -3, 1, -w - 12, 3, 5, -w - 12, wall);
			quad(level, -3, 6, -w - 12, 3, 39, -w - 1, barrier);
			quad(level, -2, 6, -w - 11, 2, 39, -w - 1, air);
			quad(level, -2, 0, -w - 11, 2, 0, -w - 1, Blocks.COARSE_DIRT.defaultBlockState());
			quad(level, -3, 5, -w, 3, 5, -w, Blocks.POLISHED_ANDESITE.defaultBlockState()); // lintel over each gate
			quad(level, -2, 5, -w, 2, 5, -w, air);
			// The command post: a waist-high sandbag ring with a doorway on each side, crates,
			// and the Supply Cache (an iron block and a beacon) in the middle.
			BlockState sandbags = ModBlocks.SANDBAGS.defaultBlockState();
			quad(level, -6, 1, -6, 6, 1, -6, sandbags); // shoot over it, crouch behind it
			quad(level, -1, 1, -6, 1, 1, -6, air);
			quad(level, 4, 1, -4, 4, 1, -4, Blocks.BARREL.defaultBlockState());
			box(level, 0, 1, 0, 0, 1, 0, Blocks.IRON_BLOCK.defaultBlockState());
			box(level, 0, 2, 0, 0, 2, 0, Blocks.BEACON.defaultBlockState());
			box(level, 0, 3, 0, 0, 7, 0, air);
			// Cover in each quarter: crates, low walls, sandbag lines and a ruined house.
			BlockState crate = Blocks.BARREL.defaultBlockState();
			quad(level, 10, 1, -14, 11, 1, -14, crate);
			quad(level, 10, 1, -13, 10, 2, -13, crate);
			quad(level, 15, 1, -8, 15, 2, -4, wall);
			quad(level, 7, 1, -20, 12, 1, -20, sandbags);
			quad(level, 18, 1, -18, 18, 1, -14, sandbags);
			quad(level, -12, 1, -11, -8, 2, -11, mossy);
			// The ruined house.
			quad(level, 20, 1, -26, 27, 3, -26, wall);
			quad(level, 20, 1, -26, 20, 3, -20, cracked);
			quad(level, 27, 1, -26, 27, 2, -20, wall);
			quad(level, 23, 1, -26, 24, 2, -26, air);
			quad(level, 20, 1, -23, 20, 2, -22, air);
			quad(level, 25, 3, -26, 27, 3, -26, air);
			// Watchtowers in the corners, with a ladder up.
			BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
			quad(level, 27, 1, 27, 31, 5, 31, planks);
			quad(level, 28, 1, 28, 30, 4, 30, air); // hollow, with a floor up top to shoot from
			quad(level, 27, 6, 27, 31, 6, 31, Blocks.SPRUCE_FENCE.defaultBlockState());
			quad(level, 28, 6, 28, 30, 6, 30, air);
			quad(level, 27, 1, 29, 27, 2, 29, air); // doorway
			for (int k = 0; k < 4; k++) {
				// Ladder on the inner face of the back wall, facing into the tower.
				int[] at = rotate(29, 30, k);
				Direction facing = switch (k) {
					case 0 -> Direction.NORTH;
					case 1 -> Direction.EAST;
					case 2 -> Direction.SOUTH;
					default -> Direction.WEST;
				};
				box(level, at[0], 1, at[1], at[0], 5, at[1], Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, facing));
				int[] lamp = rotate(9, 9, k);
				box(level, lamp[0], 1, lamp[1], lamp[0], 3, lamp[1], Blocks.DARK_OAK_FENCE.defaultBlockState());
				box(level, lamp[0], 4, lamp[1], lamp[0], 4, lamp[1], Blocks.LANTERN.defaultBlockState());
			}
			WORK.add(() -> label(level));
		}

		/** A floating label over the cache. */
		private static void label(ServerLevel level) {
			Display.TextDisplay sign = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (sign != null) {
				Vec3 c = cachePos();
				sign.snapTo(c.x, c.y + 2.4, c.z, 0, 0);
				((DisplayAccessor) sign).rubymod$setBillboard(Display.BillboardConstraints.VERTICAL);
				((DisplayAccessor) sign).rubymod$setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
						new Vector3f(1.4F, 1.4F, 1.4F), new Quaternionf()));
				((TextDisplayAccessor) sign).rubymod$setText(Component.literal("SUPPLY CACHE\n").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
						.append(Component.literal("Don't let them wreck it!").withStyle(ChatFormatting.WHITE)));
				((TextDisplayAccessor) sign).rubymod$setBackground(0x80000000);
				level.addFreshEntity(sign);
			}
		}

		private static int[] rotate(int x, int z, int k) {
			int[] p = {x, z};
			for (int i = 0; i < k; i++) {
				p = new int[] {-p[1], p[0]};
			}
			return p;
		}
	}
}
