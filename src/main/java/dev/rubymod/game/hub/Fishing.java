package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Fishing, properly: when something bites, it fights! A needle swings across a bar: right-click when it's in the green
 * to land it. Thirty kinds of fish (from minnows to the Golden Ruby Fish), a fish book of the ones you've caught, and
 * the family aquarium in the fishing village, where one of every kind anyone's caught swims about.
 */
final class Fishing {
	private Fishing() {
	}

	/** A kind of fish: its name, what it looks like in your hand and in the tank, how rare (1 common .. 4 legendary), how big. */
	private record Fish(String name, Item item, EntityType<? extends Mob> swims, int rarity, int minCm, int maxCm) {
	}

	private static final Fish[] FISH = {
		new Fish("Minnow", Items.COD, EntityTypes.COD, 1, 3, 8), new Fish("Goldfish", Items.TROPICAL_FISH, EntityTypes.TROPICAL_FISH, 1, 5, 15),
		new Fish("Perch", Items.COD, EntityTypes.COD, 1, 10, 30), new Fish("Sunfish", Items.TROPICAL_FISH, EntityTypes.TROPICAL_FISH, 1, 8, 20),
		new Fish("Carp", Items.COD, EntityTypes.COD, 1, 20, 60), new Fish("Roach", Items.COD, EntityTypes.COD, 1, 10, 25),
		new Fish("Bluegill", Items.TROPICAL_FISH, EntityTypes.TROPICAL_FISH, 1, 10, 22), new Fish("Tadpole Fish", Items.COD, EntityTypes.COD, 1, 2, 5),
		new Fish("Pond Salmon", Items.SALMON, EntityTypes.SALMON, 2, 30, 70), new Fish("Rainbow Trout", Items.SALMON, EntityTypes.SALMON, 2, 25, 60),
		new Fish("Catfish", Items.COD, EntityTypes.COD, 2, 30, 90), new Fish("Pike", Items.COD, EntityTypes.COD, 2, 40, 100),
		new Fish("Clownfish", Items.TROPICAL_FISH, EntityTypes.TROPICAL_FISH, 2, 8, 12), new Fish("Angelfish", Items.TROPICAL_FISH, EntityTypes.TROPICAL_FISH, 2, 10, 20),
		new Fish("Puffer", Items.PUFFERFISH, EntityTypes.PUFFERFISH, 2, 10, 30), new Fish("Bass", Items.COD, EntityTypes.COD, 2, 25, 55),
		new Fish("Koi", Items.TROPICAL_FISH, EntityTypes.TROPICAL_FISH, 3, 30, 80), new Fish("Sturgeon", Items.SALMON, EntityTypes.SALMON, 3, 80, 200),
		new Fish("Pufferfish King", Items.PUFFERFISH, EntityTypes.PUFFERFISH, 3, 30, 50), new Fish("Glowing Lanternfish", Items.TROPICAL_FISH,
			EntityTypes.TROPICAL_FISH, 3, 10, 25),
		new Fish("Rainbow Fish", Items.TROPICAL_FISH, EntityTypes.TROPICAL_FISH, 3, 15, 35), new Fish("Swordfish", Items.SALMON, EntityTypes.SALMON, 3, 100, 250),
		new Fish("Blue Tang", Items.TROPICAL_FISH, EntityTypes.TROPICAL_FISH, 3, 15, 30), new Fish("Eel", Items.COD, EntityTypes.COD, 3, 50, 150),
		new Fish("Mega Carp", Items.COD, EntityTypes.COD, 3, 100, 160), new Fish("Ghost Fish", Items.COD, EntityTypes.COD, 4, 20, 40),
		new Fish("Crystal Trout", Items.SALMON, EntityTypes.SALMON, 4, 30, 60), new Fish("Moonfish", Items.TROPICAL_FISH, EntityTypes.TROPICAL_FISH, 4, 40, 90),
		new Fish("Ancient Coelacanth", Items.COD, EntityTypes.COD, 4, 120, 200), new Fish("Golden Ruby Fish", Items.TROPICAL_FISH, EntityTypes.TROPICAL_FISH, 4, 50, 99)};
	private static final String[] RARITY = {"", "Common", "Uncommon", "Rare", "LEGENDARY"};
	private static final ChatFormatting[] RARITY_COLOR = {ChatFormatting.WHITE, ChatFormatting.WHITE, ChatFormatting.GREEN, ChatFormatting.AQUA, ChatFormatting.GOLD};

	/** A fish on the line: which, where the needle is, where the green is, how long's left. */
	private static final class Fight {
		Fish fish;
		double needle;
		double speed;
		int green;
		int width;
		int time;
	}

	private static final Map<UUID, Fight> FIGHTS = new HashMap<>();
	/** Everyone's fish book: name to the kinds they've caught. */
	private static final Map<String, Set<String>> BOOK = new HashMap<>();
	private static final List<UUID> TANK = new ArrayList<>();
	private static boolean loaded;
	/** The aquarium, on Splash Island by the fishing village: inside of the tank. */
	private static final int TX1 = -1;
	private static final int TX2 = 3;
	private static final int TZ1 = -152;
	private static final int TZ2 = -150;

	/** Something bit (the hook came up with a catch): the fight starts. True if it did. */
	static boolean bite(ServerPlayer p, ServerLevel level) {
		if (FIGHTS.containsKey(p.getUUID())) {
			return true;
		}
		var random = level.getRandom();
		int roll = random.nextInt(100);
		int rarity = roll < 55 ? 1 : roll < 82 ? 2 : roll < 96 ? 3 : 4;
		List<Fish> pool = new ArrayList<>();
		for (Fish f : FISH) {
			if (f.rarity() == rarity) {
				pool.add(f);
			}
		}
		Fight fight = new Fight();
		fight.fish = pool.get(random.nextInt(pool.size()));
		fight.width = 7 - rarity;
		fight.green = 2 + random.nextInt(20 - fight.width - 2);
		fight.speed = 0.6 + rarity * 0.25;
		fight.time = 100;
		FIGHTS.put(p.getUUID(), fight);
		level.playSound(null, p.blockPosition(), SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.PLAYERS, 1.0F, 1.0F);
		dev.rubymod.game.Alerts.title(p, "", "Something's biting! Right-click in the green!", RARITY_COLOR[rarity]);
		return true;
	}

	/** Right-clicking the rod while a fish fights: is the needle in the green? True if a fight was on. */
	static boolean reel(ServerPlayer p) {
		Fight f = FIGHTS.remove(p.getUUID());
		if (f == null) {
			return false;
		}
		ServerLevel level = (ServerLevel) p.level();
		if (f.needle >= f.green && f.needle < f.green + f.width) {
			land(p, level, f.fish);
		} else {
			p.sendOverlayMessage(Component.literal("Oh no, it got away! Cast again...").withStyle(ChatFormatting.GRAY));
			level.playSound(null, p.blockPosition(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.PLAYERS, 1.0F, 0.6F);
		}
		return true;
	}

	private static void land(ServerPlayer p, ServerLevel level, Fish fish) {
		var random = level.getRandom();
		int cm = fish.minCm() + random.nextInt(fish.maxCm() - fish.minCm() + 1);
		var stack = HubGames.toy(fish.item(), 1, fish.name() + " (" + cm + " cm)", RARITY_COLOR[fish.rarity()]);
		HubGames.hand(p, stack);
		load(level);
		boolean fresh = BOOK.computeIfAbsent(p.getName().getString(), k -> new LinkedHashSet<>()).add(fish.name());
		boolean newToTank = fresh && everyone().contains(fish.name()) && countIn(fish.name()) == 1;
		save(level);
		dev.rubymod.game.Alerts.title(p, fish.name() + "!", cm + " cm  " + RARITY[fish.rarity()] + (fresh ? "  NEW for your fish book!" : ""),
				RARITY_COLOR[fish.rarity()]);
		level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.4F);
		level.sendParticles(ParticleTypes.SPLASH, p.getX(), p.getY() + 1, p.getZ(), 30, 0.5, 0.5, 0.5, 0.2);
		HubGames.logCatch(level, p.getName().getString() + ": " + fish.name() + " (" + cm + " cm)");
		if (fish.rarity() == 4) {
			HubPark.burst(level, p.position().add(0, 4, 0));
			for (ServerPlayer q : level.players()) {
				q.sendSystemMessage(Component.literal("★ " + p.getName().getString() + " caught a " + fish.name() + "! ★").withStyle(ChatFormatting.GOLD,
						ChatFormatting.BOLD));
			}
		}
		if (newToTank) {
			stock(level);
			p.sendSystemMessage(Component.literal("Your " + fish.name() + " is now swimming in the family aquarium!").withStyle(ChatFormatting.AQUA));
		}
	}

	private static Set<String> everyone() {
		Set<String> all = new LinkedHashSet<>();
		for (Set<String> s : BOOK.values()) {
			all.addAll(s);
		}
		return all;
	}

	private static int countIn(String name) {
		int n = 0;
		for (Set<String> s : BOOK.values()) {
			if (s.contains(name)) {
				n++;
			}
		}
		return n;
	}

	/** Your fish book, in chat. */
	static void book(ServerPlayer p) {
		load((ServerLevel) p.level());
		Set<String> mine = BOOK.getOrDefault(p.getName().getString(), Set.of());
		MutableComponent text = Component.literal("🐟 " + p.getName().getString() + "'s Fish Book: " + mine.size() + " of " + FISH.length + "\n")
				.withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
		for (Fish f : FISH) {
			boolean got = mine.contains(f.name());
			text.append(Component.literal(got ? f.name() : "???").withStyle(got ? RARITY_COLOR[f.rarity()] : ChatFormatting.DARK_GRAY)).append(Component.literal("  "));
		}
		p.sendSystemMessage(text);
	}

	static void tick(ServerLevel level) {
		for (var it = FIGHTS.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
			Fight f = e.getValue();
			if (p == null || --f.time <= 0) {
				it.remove();
				if (p != null) {
					p.sendOverlayMessage(Component.literal("Too slow! It swam away. Cast again...").withStyle(ChatFormatting.GRAY));
				}
				continue;
			}
			// The needle swings back and forth (rarer fish: faster, a smaller green).
			f.needle += f.speed;
			if (f.needle >= 20 || f.needle <= 0) {
				f.speed = -f.speed;
				f.needle = Math.max(0, Math.min(19.99, f.needle));
			}
			MutableComponent bar = Component.literal("[");
			for (int i = 0; i < 20; i++) {
				boolean green = i >= f.green && i < f.green + f.width;
				boolean here = (int) f.needle == i;
				bar.append(Component.literal(here ? "▼" : "|").withStyle(here ? ChatFormatting.WHITE : green ? ChatFormatting.GREEN : ChatFormatting.RED));
			}
			bar.append(Component.literal("]  right-click!"));
			p.sendOverlayMessage(bar);
		}
	}

	// ----- The aquarium -----

	static void build(ServerLevel level) {
		TANK.clear();
		for (int x = TX1 - 1; x <= TX2 + 1; x++) {
			for (int z = TZ1 - 1; z <= TZ2 + 1; z++) {
				boolean edge = x == TX1 - 1 || x == TX2 + 1 || z == TZ1 - 1 || z == TZ2 + 1;
				HubPark.set(level, x, FLOOR, z, Blocks.SAND.defaultBlockState());
				for (int y = 1; y <= 3; y++) {
					HubPark.set(level, x, FLOOR + y, z, edge ? Blocks.GLASS.defaultBlockState() : Blocks.WATER.defaultBlockState());
				}
				HubPark.set(level, x, FLOOR + 4, z, edge ? Blocks.DARK_OAK_SLAB.defaultBlockState() : Blocks.GLASS.defaultBlockState());
			}
		}
		HubPark.set(level, TX1, FLOOR + 1, TZ1, Blocks.SEAGRASS.defaultBlockState());
		HubPark.set(level, TX2, FLOOR + 1, TZ2, Blocks.SEA_PICKLE.defaultBlockState());
		HubPark.set(level, (TX1 + TX2) / 2, FLOOR + 1, TZ1, Blocks.TUBE_CORAL.defaultBlockState());
		HubPark.label(level, (TX1 + TX2) / 2.0 + 0.5, FLOOR + 6, TZ2 + 2.0, "FAMILY AQUARIUM", ChatFormatting.AQUA, 1.4F);
		HubPark.label(level, (TX1 + TX2) / 2.0 + 0.5, FLOOR + 5.2, TZ2 + 2.0, "One of every fish you've caught", ChatFormatting.WHITE, 0.6F);
		load(level);
		stock(level);
	}

	/** Fill the tank: one of every kind anyone's caught. */
	private static void stock(ServerLevel level) {
		for (UUID id : TANK) {
			HubPark.discard(level, id);
		}
		TANK.clear();
		var random = level.getRandom();
		for (String name : everyone()) {
			for (Fish f : FISH) {
				if (f.name().equals(name)) {
					Mob m = f.swims().create(level, EntitySpawnReason.EVENT);
					if (m == null) {
						continue;
					}
					m.snapTo(TX1 + 0.5 + random.nextDouble() * (TX2 - TX1), FLOOR + 1.5 + random.nextDouble(), TZ1 + 0.5 + random.nextDouble() * (TZ2 - TZ1),
							random.nextFloat() * 360, 0);
					m.setPersistenceRequired();
					m.setPermanentlyInvulnerable(true);
					HubPark.keep(m);
					level.addFreshEntity(m);
					TANK.add(m.getUUID());
				}
			}
		}
	}

	private static Path file(ServerLevel level) {
		return level.getServer().getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("fishbook.txt");
	}

	private static void load(ServerLevel level) {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Path f = file(level);
			if (Files.exists(f)) {
				for (String line : Files.readAllLines(f)) {
					String[] parts = line.split("\\|", 2);
					if (parts.length == 2) {
						Set<String> s = new LinkedHashSet<>();
						for (String n : parts[1].split(",")) {
							if (!n.isEmpty()) {
								s.add(n);
							}
						}
						BOOK.put(parts[0], s);
					}
				}
			}
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't read the fish book", e);
		}
	}

	private static void save(ServerLevel level) {
		StringBuilder b = new StringBuilder();
		for (var e : BOOK.entrySet()) {
			b.append(e.getKey()).append('|').append(String.join(",", e.getValue())).append('\n');
		}
		try {
			Files.createDirectories(file(level).getParent());
			Files.writeString(file(level), b.toString());
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't save the fish book", e);
		}
	}

	static void reset() {
		FIGHTS.clear();
		TANK.clear();
	}

	static boolean fighting(ServerPlayer p) {
		return FIGHTS.containsKey(p.getUUID());
	}

	static Entity unused() {
		return null;
	}
}
