package dev.rubymod.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.RubyMod;
import dev.rubymod.entity.SoldierEntity;
import dev.rubymod.gun.GunItem;
import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/**
 * Co-op Horde: everyone in it fights together in a walled outpost (in the arena
 * dimension) against waves of zombies, skeletons, spiders and enemy soldiers, with a
 * Brute (a ravager) every 5th wave. Clear a wave and everyone gets a reward (a new gun,
 * sandbags to build cover, armor...), a refill and 15 seconds to breathe; anyone who
 * went down comes back for the next wave. If the whole team is down, it's game over and
 * everyone goes back to the hub. Your Ruby Wars things are safe in the hub's save the
 * whole time; nothing from Horde comes back with you.
 */
public final class Horde {
	/** Middle of the outpost, far from the duel arenas. */
	public static final int CX = -20000;
	public static final int CZ = 0;
	private static final int GROUND = Duels.GROUND;
	/** Half the outpost's inside width. */
	private static final int HALF = 34;
	private static final int MAX_ALIVE = 26;
	private static final int BREAK_TICKS = 15 * 20;
	private static final String MOB_TAG = "rubymod_horde";
	/** The raid id horde soldiers carry (they're raiders that don't loot anyone's bank). */
	public static final int SOLDIER_RAID_ID = -2;

	private enum State { IDLE, COUNTDOWN, WAVE, BREAK, OVER }

	private static final class Fighter {
		final String name;
		boolean alive = true;
		int kills;

		Fighter(String name) {
			this.name = name;
		}
	}

	private static State state = State.IDLE;
	private static int wave;
	private static int timer;
	private static int toSpawn;
	private static int bosses;
	private static int total;
	private static final Map<UUID, Fighter> FIGHTERS = new LinkedHashMap<>();
	private static final Set<UUID> MOBS = new HashSet<>();
	private static final ServerBossEvent BAR = new ServerBossEvent(UUID.randomUUID(), Component.literal("Co-op Horde"),
			BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
	private static int bestWave = -1;
	private static String bestTeam = "";

	private Horde() {
	}

	public static void reset() {
		state = State.IDLE;
		FIGHTERS.clear();
		MOBS.clear();
		BAR.removeAllPlayers();
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

	/** Waves cleared so far this game. */
	private static int cleared() {
		return state == State.WAVE ? wave - 1 : wave;
	}

	/** One line for hub signs and the game picker. */
	public static String status() {
		String best = bestWave > 0 ? "  •  Best: wave " + bestWave + (bestTeam.isEmpty() ? "" : " (" + bestTeam + ")") : "";
		return switch (state) {
			case IDLE -> "Nobody playing" + best;
			case COUNTDOWN -> "Starting  •  " + FIGHTERS.size() + " playing" + best;
			case OVER -> "Game over, back soon" + best;
			default -> "Wave " + wave + "  •  " + FIGHTERS.size() + " playing" + best;
		};
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

	/** Into the outpost (from the hub). Starts a game if nobody's playing. */
	public static void join(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		ServerLevel arena = server.getLevel(Duels.ARENA);
		loadBest(server);
		if (state == State.IDLE) {
			startGame(arena);
		}
		FIGHTERS.put(player.getUUID(), new Fighter(player.getName().getString()));
		BAR.addPlayer(player);
		player.stopRiding();
		place(arena, player);
		giveKit(player, cleared());
		tell(server, Component.literal(player.getName().getString() + " joined the Horde!").withStyle(ChatFormatting.GREEN));
		player.sendSystemMessage(Component.literal("Co-op Horde: hold the outpost against wave after wave. Clear a wave for a reward; "
				+ "anyone who goes down comes back next wave. H or /hub to leave.").withStyle(ChatFormatting.GOLD));
		updateBar();
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
		Fighter fighter = FIGHTERS.remove(player.getUUID());
		BAR.removePlayer(player);
		if (fighter == null || !server.isRunning()) {
			return;
		}
		tell(server, Component.literal(fighter.name + " left the Horde.").withStyle(ChatFormatting.GRAY));
		ServerLevel arena = server.getLevel(Duels.ARENA);
		if (FIGHTERS.isEmpty()) {
			stop(arena);
		} else if (state == State.WAVE && FIGHTERS.values().stream().noneMatch(f -> f.alive)) {
			gameOver(server, arena);
		} else {
			updateBar();
		}
	}

	// ----- The game -----

	private static void startGame(ServerLevel arena) {
		clearArea(arena);
		Outpost.build(arena);
		wave = 0;
		state = State.COUNTDOWN;
		timer = 10 * 20;
		MOBS.clear();
	}

	private static void stop(ServerLevel arena) {
		state = State.IDLE;
		wave = 0;
		BAR.removeAllPlayers();
		if (arena != null) {
			clearArea(arena);
		}
		MOBS.clear();
	}

	private static void clearArea(ServerLevel arena) {
		BlockPos c = center();
		int r = HALF + 20;
		for (Entity e : arena.getEntities((Entity) null, new AABB(c.getX() - r, -64, c.getZ() - r, c.getX() + r, 400, c.getZ() + r),
				e -> !(e instanceof Player))) {
			e.discard();
		}
	}

	private static BlockPos center() {
		return new BlockPos(CX, GROUND + 1, CZ);
	}

	public static void tick(MinecraftServer server) {
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
		switch (state) {
			case COUNTDOWN, BREAK -> {
				timer--;
				if (timer > 0 && timer % 20 == 0) {
					int seconds = timer / 20;
					if (seconds <= 5) {
						for (ServerPlayer p : players(server)) {
							Alerts.title(p, String.valueOf(seconds), "Wave " + (wave + 1) + " is coming", ChatFormatting.YELLOW);
							p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 0.8F, 1.0F);
						}
					}
					updateBar();
				}
				if (timer <= 0) {
					startWave(server, arena, wave + 1);
				}
			}
			case WAVE -> {
				if (server.getTickCount() % 10 == 0 && (toSpawn > 0 || bosses > 0) && MOBS.size() < MAX_ALIVE) {
					spawnOne(arena);
				}
				if (server.getTickCount() % 20 == 0) {
					tidy(server, arena);
					if (toSpawn == 0 && bosses == 0 && MOBS.isEmpty()) {
						waveCleared(server);
					} else {
						updateBar();
					}
				}
			}
			case OVER -> {
				if (--timer <= 0) {
					for (ServerPlayer p : players(server)) {
						FIGHTERS.remove(p.getUUID());
						BAR.removePlayer(p);
						p.getInventory().clearContent();
						p.removeAllEffects();
						Hub.enterHub(p);
					}
					FIGHTERS.clear();
					stop(arena);
				}
			}
			default -> {
			}
		}
	}

	private static List<ServerPlayer> players(MinecraftServer server) {
		List<ServerPlayer> list = new ArrayList<>();
		for (UUID id : FIGHTERS.keySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) {
				list.add(p);
			}
		}
		return list;
	}

	private static void startWave(MinecraftServer server, ServerLevel arena, int n) {
		wave = n;
		state = State.WAVE;
		bosses = n % 5 == 0 ? n / 5 : 0;
		toSpawn = 4 + 3 * n + (FIGHTERS.size() - 1) * (2 + n);
		total = toSpawn + bosses;
		for (ServerPlayer p : players(server)) {
			Fighter f = FIGHTERS.get(p.getUUID());
			if (!f.alive) {
				f.alive = true;
				place(arena, p);
				p.sendSystemMessage(Component.literal("You're back in!").withStyle(ChatFormatting.GREEN));
			}
			Alerts.title(p, "WAVE " + n, bosses > 0 ? "A Brute is coming!" : total + " enemies", bosses > 0 ? ChatFormatting.DARK_RED : ChatFormatting.RED);
			p.level().playSound(null, p.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.PLAYERS, 0.7F, 1.0F);
			if (n == 5) {
				Goals.add(server, p.getUUID(), "horde5", 1);
			}
		}
		updateBar();
	}

	private static void waveCleared(MinecraftServer server) {
		state = State.BREAK;
		timer = BREAK_TICKS;
		if (wave > bestWave) {
			bestWave = wave;
			bestTeam = String.join(", ", FIGHTERS.values().stream().map(f -> f.name).toList());
			saveBest(server);
		}
		String reward = rewardName(wave);
		for (ServerPlayer p : players(server)) {
			reward(p, wave);
			topUp(p, wave);
			if (!p.isSpectator()) {
				p.setHealth(p.getMaxHealth());
			}
			Alerts.title(p, "WAVE " + wave + " CLEARED", "Reward: " + reward, ChatFormatting.GREEN);
			p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8F, 1.0F);
			p.sendSystemMessage(Component.literal("Wave " + wave + " cleared! Reward: " + reward + ", plus ammo and full health. Next wave in 15 seconds.")
					.withStyle(ChatFormatting.GREEN));
		}
		updateBar();
	}

	private static void gameOver(MinecraftServer server, ServerLevel arena) {
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
		StringBuilder kills = new StringBuilder();
		FIGHTERS.values().stream().sorted(Comparator.comparingInt((Fighter f) -> f.kills).reversed())
				.forEach(f -> kills.append(kills.isEmpty() ? "" : ", ").append(f.name).append(" ").append(f.kills));
		for (ServerPlayer p : players(server)) {
			Alerts.title(p, "GAME OVER", "You survived " + survived + (survived == 1 ? " wave" : " waves"), ChatFormatting.RED);
			p.level().playSound(null, p.blockPosition(), SoundEvents.WITHER_DEATH, SoundSource.PLAYERS, 0.5F, 1.0F);
			p.sendSystemMessage(Component.literal("====== HORDE: wave " + wave + " ======").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			p.sendSystemMessage(Component.literal("Kills: " + kills).withStyle(ChatFormatting.GRAY));
			p.sendSystemMessage(Component.literal("Best ever: wave " + Math.max(bestWave, survived) + ". Back to the hub in 10 seconds.")
					.withStyle(ChatFormatting.GRAY));
		}
		updateBar();
	}

	private static void updateBar() {
		int left = toSpawn + bosses + MOBS.size();
		switch (state) {
			case WAVE -> {
				BAR.setName(Component.literal("Wave " + wave + "   •   " + left + " enemies left"));
				BAR.setColor(BossEvent.BossBarColor.RED);
				BAR.setProgress(total == 0 ? 0 : Math.min(1.0F, left / (float) total));
			}
			case OVER -> {
				BAR.setName(Component.literal("Game over: wave " + wave));
				BAR.setProgress(0);
			}
			default -> {
				BAR.setName(Component.literal((wave == 0 ? "Get ready!" : "Wave " + wave + " cleared!") + "   Next wave in " + Math.max(0, timer / 20)));
				BAR.setColor(BossEvent.BossBarColor.GREEN);
				BAR.setProgress(Math.max(0, timer) / (float) (wave == 0 ? 200 : BREAK_TICKS));
			}
		}
	}

	private static void tell(MinecraftServer server, Component message) {
		for (ServerPlayer p : players(server)) {
			p.sendSystemMessage(message);
		}
	}

	// ----- Players -----

	/** Stand in the command post, ready to fight. */
	private static void place(ServerLevel arena, ServerPlayer player) {
		RandomSource random = player.getRandom();
		double angle = random.nextDouble() * Math.PI * 2;
		BlockPos c = center();
		player.teleportTo(arena, c.getX() + 0.5 + Math.cos(angle) * 2.5, c.getY(), c.getZ() + 0.5 + Math.sin(angle) * 2.5, Set.<Relative>of(),
				random.nextFloat() * 360.0F, 0.0F, true);
		player.setGameMode(GameType.SURVIVAL);
		player.setHealth(player.getMaxHealth());
		player.removeAllEffects();
		player.clearFire();
		player.resetFallDistance();
		player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, -1, 0, false, false));
		SpawnShield.grant(player);
	}

	/** Someone in Horde would die: they're down until the next wave instead. True when handled. */
	public static boolean onDeath(ServerPlayer victim, DamageSource source) {
		Fighter fighter = FIGHTERS.get(victim.getUUID());
		if (fighter == null) {
			return false;
		}
		MinecraftServer server = victim.level().getServer();
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
		boolean anyoneLeft = FIGHTERS.values().stream().anyMatch(f -> f.alive);
		Alerts.title(victim, "DOWN!", anyoneLeft ? "Your team has to clear this wave to bring you back" : "", ChatFormatting.RED);
		tell(server, Component.literal(fighter.name + " is down!").withStyle(ChatFormatting.RED));
		if (!anyoneLeft) {
			gameOver(server, server.getLevel(Duels.ARENA));
		}
		return true;
	}

	/** A non-player died: if it was a Horde enemy, count it (and the kill). */
	public static void onEntityDeath(LivingEntity entity, DamageSource source) {
		if (!MOBS.remove(entity.getUUID())) {
			return;
		}
		if (source.getEntity() instanceof ServerPlayer killer && FIGHTERS.containsKey(killer.getUUID())) {
			FIGHTERS.get(killer.getUUID()).kills++;
			int left = toSpawn + bosses + MOBS.size();
			killer.sendOverlayMessage(Component.literal("+1 kill  (" + left + " left)").withStyle(ChatFormatting.GREEN));
		}
		updateBar();
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

	/** The starting kit, plus every reward already earned this game (for anyone joining late). */
	private static void giveKit(ServerPlayer player, int cleared) {
		player.getInventory().clearContent();
		// The rifle takes Heavy Ammo, the pistol Light Ammo.
		add(player, gun(ModItems.ARR_RIFLE), gun(ModItems.SERVICE_PISTOL), new ItemStack(ModItems.RUBY_SWORD), new ItemStack(ModItems.FRAG_GRENADE, 2),
				new ItemStack(ModItems.HEAVY_AMMO, 64), new ItemStack(ModItems.HEAVY_AMMO, 64), new ItemStack(ModItems.LIGHT_AMMO, 48));
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
		player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
		player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
		for (int w = 1; w <= cleared; w++) {
			reward(player, w);
		}
		player.getInventory().setSelectedSlot(0);
		player.inventoryMenu.broadcastChanges();
	}

	private static String rewardName(int w) {
		return switch (w) {
			case 1 -> "Combat Shotgun";
			case 2 -> "24 Sandbags (build some cover!)";
			case 3 -> "Longshot Sniper";
			case 4 -> "Ruby Armor";
			case 5 -> "Rocket Launcher";
			case 6 -> "Concrete Walls and grenades";
			case 7 -> "Ruby Blaster";
			case 8 -> "Jetpack";
			case 9 -> "Lancer";
			default -> "Rockets and grenades";
		};
	}

	private static void reward(ServerPlayer player, int w) {
		switch (w) {
			case 1 -> add(player, gun(ModItems.COMBAT_SHOTGUN), new ItemStack(ModItems.SHOTGUN_SHELL, 32));
			case 2 -> add(player, new ItemStack(ModBlocks.SANDBAGS, 24));
			case 3 -> add(player, gun(ModItems.LONGSHOT_SNIPER), new ItemStack(ModItems.SNIPER_ROUND, 16));
			case 4 -> {
				player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.RUBY_HELMET));
				player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.RUBY_CHESTPLATE));
				player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModItems.RUBY_LEGGINGS));
				player.setItemSlot(EquipmentSlot.FEET, new ItemStack(ModItems.RUBY_BOOTS));
			}
			case 5 -> add(player, new ItemStack(ModItems.ROCKET_LAUNCHER), new ItemStack(ModItems.ROCKET, 8));
			case 6 -> add(player, new ItemStack(ModBlocks.CONCRETE_WALL, 16), new ItemStack(ModItems.FRAG_GRENADE, 3));
			case 7 -> add(player, gun(ModItems.RUBY_BLASTER), new ItemStack(ModItems.RUBY_CELL, 64));
			case 8 -> add(player, new ItemStack(ModItems.JETPACK));
			case 9 -> add(player, new ItemStack(ModItems.LANCER), new ItemStack(ModItems.AT_MISSILE, 6));
			default -> add(player, new ItemStack(ModItems.ROCKET, 6), new ItemStack(ModItems.FRAG_GRENADE, 3));
		}
		player.inventoryMenu.broadcastChanges();
	}

	/** Ammo for everything you've earned, after each wave. */
	private static void topUp(ServerPlayer player, int cleared) {
		add(player, new ItemStack(ModItems.HEAVY_AMMO, 64), new ItemStack(ModItems.LIGHT_AMMO, 24));
		if (cleared >= 1) {
			add(player, new ItemStack(ModItems.SHOTGUN_SHELL, 16));
		}
		if (cleared >= 3) {
			add(player, new ItemStack(ModItems.SNIPER_ROUND, 8));
		}
		if (cleared >= 5) {
			add(player, new ItemStack(ModItems.ROCKET, 3));
		}
		if (cleared >= 7) {
			add(player, new ItemStack(ModItems.RUBY_CELL, 32));
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
		Mob mob = boss ? EntityTypes.RAVAGER.create(arena, EntitySpawnReason.EVENT) : create(arena, random);
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
		// Tougher every wave.
		AttributeInstance health = mob.getAttribute(Attributes.MAX_HEALTH);
		if (health != null) {
			health.setBaseValue(health.getBaseValue() * (1.0 + 0.06 * (wave - 1)));
			mob.setHealth(mob.getMaxHealth());
		}
		AttributeInstance follow = mob.getAttribute(Attributes.FOLLOW_RANGE);
		if (follow != null) {
			follow.setBaseValue(96.0);
		}
		// A helmet keeps zombies and skeletons from burning in daylight.
		if (!(mob instanceof SoldierEntity) && !boss && mob.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
			ItemStack helmet = new ItemStack(random.nextBoolean() ? Items.IRON_HELMET : Items.CHAINMAIL_HELMET);
			helmet.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
			mob.setItemSlot(EquipmentSlot.HEAD, helmet);
		}
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			mob.setDropChance(slot, 0.0F);
		}
		mob.setPersistenceRequired();
		mob.addTag(MOB_TAG);
		arena.addFreshEntity(mob);
		MOBS.add(mob.getUUID());
	}

	/** A random enemy for this wave: zombies first, then skeletons, spiders, husks, and soldiers. */
	private static Mob create(ServerLevel arena, RandomSource random) {
		BlockPos c = center();
		if (arena.getDifficulty() == Difficulty.PEACEFUL) {
			// Monsters can't exist in Peaceful; soldiers still fight.
			return SoldierEntity.horde(arena, c, wave >= 6 && random.nextInt(5) == 0 ? SoldierEntity.AT_GUNNER : SoldierEntity.RIFLEMAN);
		}
		int[] weights = {
				10, // zombie
				wave >= 2 ? 4 : 0, // husk
				wave >= 2 ? 3 : 0, // spider
				wave >= 3 ? 5 : 0, // skeleton
				wave >= 4 ? 3 + wave / 3 : 0, // rifleman
				wave >= 7 ? 1 + wave / 6 : 0, // AT gunner
		};
		int sum = 0;
		for (int w : weights) {
			sum += w;
		}
		int roll = random.nextInt(sum);
		int kind = 0;
		while (roll >= weights[kind]) {
			roll -= weights[kind];
			kind++;
		}
		return switch (kind) {
			case 1 -> EntityTypes.HUSK.create(arena, EntitySpawnReason.EVENT);
			case 2 -> EntityTypes.SPIDER.create(arena, EntitySpawnReason.EVENT);
			case 3 -> {
				var skeleton = EntityTypes.SKELETON.create(arena, EntitySpawnReason.EVENT);
				if (skeleton != null) {
					skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
					skeleton.reassessWeaponGoal();
				}
				yield skeleton;
			}
			case 4 -> SoldierEntity.horde(arena, c, SoldierEntity.RIFLEMAN);
			case 5 -> SoldierEntity.horde(arena, c, SoldierEntity.AT_GUNNER);
			default -> {
				var zombie = EntityTypes.ZOMBIE.create(arena, EntitySpawnReason.EVENT);
				if (zombie != null && wave >= 3 && random.nextInt(3) == 0) {
					zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(random.nextBoolean() ? Items.IRON_SWORD : Items.IRON_SHOVEL));
				}
				yield zombie;
			}
		};
	}

	/** Every second: forget the dead, point everyone at the nearest fighter, rescue strays, sweep up drops. */
	private static void tidy(MinecraftServer server, ServerLevel arena) {
		List<ServerPlayer> targets = new ArrayList<>();
		for (ServerPlayer p : players(server)) {
			if (FIGHTERS.get(p.getUUID()).alive && !p.isSpectator() && p.level() == arena) {
				targets.add(p);
			}
		}
		BlockPos c = center();
		int left = toSpawn + bosses + MOBS.size();
		for (Iterator<UUID> it = MOBS.iterator(); it.hasNext();) {
			Entity e = arena.getEntity(it.next());
			if (!(e instanceof Mob mob) || !mob.isAlive()) {
				it.remove();
				continue;
			}
			double away = Math.max(Math.abs(mob.getX() - c.getX()), Math.abs(mob.getZ() - c.getZ()));
			if (away > HALF + 14 || mob.getY() < GROUND - 4) {
				mob.teleportTo(c.getX() + 0.5, c.getY(), c.getZ() + HALF + 9.5);
			}
			ServerPlayer nearest = targets.stream().min(Comparator.comparingDouble(p -> p.distanceToSqr(mob))).orElse(null);
			if (nearest != null) {
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
		for (ItemEntity item : arena.getEntitiesOfClass(ItemEntity.class, new AABB(c).inflate(HALF + 16, 40, HALF + 16), i -> {
			ItemStack stack = i.getItem();
			return stack.is(Items.ROTTEN_FLESH) || stack.is(Items.BONE) || stack.is(Items.ARROW) || stack.is(Items.STRING)
					|| stack.is(Items.SPIDER_EYE) || stack.is(Items.SADDLE);
		})) {
			item.discard();
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

	// ----- The outpost -----

	/**
	 * A walled outpost with a gate on each side (enemies pour in through tunnels behind
	 * them), a sandbagged command post in the middle, and the same cover in every
	 * quarter. Rebuilt at the start of every game.
	 */
	static final class Outpost {
		private static final int FLAGS = Block.UPDATE_CLIENTS;

		private Outpost() {
		}

		private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
			BlockPos pos = new BlockPos(CX + x, GROUND + y, CZ + z);
			if (level.getBlockState(pos) != state) {
				level.setBlock(pos, state, FLAGS);
			}
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
					level.getChunk(cx, cz);
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
			// The command post: a sandbag ring with a doorway on each side, crates and a flag.
			BlockState sandbags = ModBlocks.SANDBAGS.defaultBlockState();
			quad(level, -6, 1, -6, 6, 1, -6, sandbags); // waist high: shoot over it, crouch behind it
			quad(level, -1, 1, -6, 1, 1, -6, air);
			quad(level, 4, 1, -4, 4, 1, -4, Blocks.BARREL.defaultBlockState());
			box(level, 0, 1, 0, 0, 6, 0, Blocks.DARK_OAK_FENCE.defaultBlockState());
			box(level, 0, 7, 0, 0, 7, 0, Blocks.LANTERN.defaultBlockState());
			box(level, 1, 5, 0, 2, 6, 0, Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState());
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
