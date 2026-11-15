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

import dev.rubymod.mixin.DisplayAccessor;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/**
 * Butterflies flutter round the park's gardens. Bea in Toyland lends you a net: swish it near one to catch it (gently:
 * it goes in your butterfly book and flutters off again). Every kind the family's caught lives in the Butterfly Garden.
 */
final class Butterflies {
	private Butterflies() {
	}

	private record Kind(String name, String model, int rarity) {
	}

	private static final Kind[] KINDS = {new Kind("Red Admiral", "butterfly_red", 1), new Kind("Orange Tip", "butterfly_orange", 1),
		new Kind("Brimstone", "butterfly_yellow", 1), new Kind("Green Hairstreak", "butterfly_lime", 1), new Kind("Sky Blue", "butterfly_sky", 1),
		new Kind("Royal Blue", "butterfly_blue", 2), new Kind("Purple Emperor", "butterfly_purple", 2), new Kind("Pink Fairy", "butterfly_pink", 2),
		new Kind("Snow White", "butterfly_white", 2), new Kind("Golden Glory", "butterfly_gold", 3), new Kind("Ruby Wing", "butterfly_ruby", 3)};
	private static final String NET = "Butterfly Net";
	/** Where butterflies live: x, z, radius (the plaza garden, Toyland's meadow, Splash Island's pond, the Fun Park's flower beds). */
	private static final int[][] GARDENS = {{0, 0, 16}, {6, 72, 14}, {8, -134, 10}, {60, 10, 10}, {-44, 44, 12}};
	/** The Butterfly Garden (a glass house in Toyland): inside. */
	private static final int GX1 = 8;
	private static final int GX2 = 14;
	private static final int GZ1 = 81;
	private static final int GZ2 = 86;

	private static final class Fly {
		UUID id;
		Kind kind;
		Vec3 pos;
		Vec3 target;
		int garden;
		int hidden;
		boolean tame;
	}

	private static final List<Fly> FLIES = new ArrayList<>();
	private static final Map<String, Set<String>> BOOK = new HashMap<>();
	private static boolean loaded;
	private static int tick;

	static void build(ServerLevel level) {
		FLIES.clear();
		load(level);
		var random = level.getRandom();
		for (int i = 0; i < 22; i++) {
			int g = i % GARDENS.length;
			Kind k = pick(random);
			spawn(level, k, g, false);
		}
		// The Butterfly Garden.
		for (int x = GX1 - 1; x <= GX2 + 1; x++) {
			for (int z = GZ1 - 1; z <= GZ2 + 1; z++) {
				boolean edge = x == GX1 - 1 || x == GX2 + 1 || z == GZ1 - 1 || z == GZ2 + 1;
				boolean door = z == GZ1 - 1 && x == (GX1 + GX2) / 2;
				HubPark.set(level, x, FLOOR, z, Blocks.MOSS_BLOCK.defaultBlockState());
				for (int y = 1; y <= 5; y++) {
					HubPark.set(level, x, FLOOR + y, z, edge && !(door && y <= 2) ? Blocks.GLASS.defaultBlockState() : Blocks.AIR.defaultBlockState());
				}
				HubPark.set(level, x, FLOOR + 6, z, Blocks.GLASS.defaultBlockState());
				if (!edge && (x * 3 + z) % 4 == 0) {
					HubPark.set(level, x, FLOOR + 1, z, (x % 2 == 0 ? Blocks.ALLIUM : Blocks.CORNFLOWER).defaultBlockState());
				}
			}
		}
		HubPark.label(level, (GX1 + GX2) / 2.0 + 0.5, FLOOR + 7.6, GZ1 - 1.0, "BUTTERFLY GARDEN", ChatFormatting.LIGHT_PURPLE, 1.4F);
		stockGarden(level);
		HubHosts.add(level, new Vec3((GX1 + GX2) / 2.0 + 2.5, FLOOR + 1, GZ1 - 2.5), 0, VillagerProfession.FLETCHER, VillagerType.SWAMP,
				new HubHosts.Host("butterflies", "Bea", "Butterflies", 0xFF90E0, "minecraft:allium",
						"Butterflies flutter round the gardens all over the park! Take a net, creep up close and right-click to catch one. "
								+ "Every kind you catch goes in your butterfly book, and lives here in the Butterfly Garden.",
						"Butterflies: right-click Bea for a net!", p -> List.of("net|A butterfly net, please!", "book|My butterfly book"), (p, key) -> {
							if ("book".equals(key)) {
								book(p);
							} else {
								HubGames.hand(p, HubGames.toy(Items.STRING, 1, NET, ChatFormatting.LIGHT_PURPLE));
								p.sendOverlayMessage(Component.literal("Creep up close to a butterfly and right-click!").withStyle(ChatFormatting.LIGHT_PURPLE));
							}
						}));
	}

	private static Kind pick(net.minecraft.util.RandomSource random) {
		int roll = random.nextInt(100);
		int r = roll < 65 ? 1 : roll < 92 ? 2 : 3;
		List<Kind> pool = new ArrayList<>();
		for (Kind k : KINDS) {
			if (k.rarity() == r) {
				pool.add(k);
			}
		}
		return pool.get(random.nextInt(pool.size()));
	}

	private static Vec3 somewhere(net.minecraft.util.RandomSource random, int g) {
		if (g < 0) {
			return new Vec3(GX1 + 0.5 + random.nextDouble() * (GX2 - GX1), FLOOR + 1.5 + random.nextDouble() * 3, GZ1 + 0.5 + random.nextDouble() * (GZ2 - GZ1));
		}
		int[] gd = GARDENS[g];
		double a = random.nextDouble() * Math.PI * 2;
		double r = random.nextDouble() * gd[2];
		return new Vec3(gd[0] + Math.cos(a) * r, FLOOR + 1.6 + random.nextDouble() * 2.5, gd[1] + Math.sin(a) * r);
	}

	private static void spawn(ServerLevel level, Kind k, int garden, boolean tame) {
		var random = level.getRandom();
		Vec3 at = somewhere(random, garden);
		Display.ItemDisplay d = HubPark.tempModel(level, at, random.nextFloat() * 360, k.model(), 0.5F, 0);
		if (d == null) {
			return;
		}
		((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(3);
		Fly f = new Fly();
		f.id = d.getUUID();
		f.kind = k;
		f.pos = at;
		f.target = somewhere(random, garden);
		f.garden = garden;
		f.tame = tame;
		FLIES.add(f);
	}

	/** One of every kind the family's caught, in the glass house. */
	private static void stockGarden(ServerLevel level) {
		FLIES.removeIf(f -> {
			if (f.tame) {
				HubPark.discard(level, f.id);
			}
			return f.tame;
		});
		Set<String> all = new LinkedHashSet<>();
		for (Set<String> s : BOOK.values()) {
			all.addAll(s);
		}
		for (Kind k : KINDS) {
			if (all.contains(k.name())) {
				spawn(level, k, -1, true);
			}
		}
	}

	/** A swish of the net. True if it was the net. */
	static boolean swish(ServerPlayer p, ItemStack held) {
		if (!HubGames.isToy(held) || !held.getHoverName().getString().equals(NET)) {
			return false;
		}
		if (p.getCooldowns().isOnCooldown(held)) {
			return true;
		}
		p.getCooldowns().addCooldown(held, 10);
		ServerLevel level = (ServerLevel) p.level();
		level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.6F, 1.6F);
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getLookAngle();
		for (Fly f : FLIES) {
			if (f.tame || f.hidden > 0) {
				continue;
			}
			Vec3 to = f.pos.subtract(eye);
			if (to.length() < 3.8 && to.normalize().dot(look) > 0.75) {
				// Caught! Into the book... and off it flutters again (somewhere else, in a bit).
				load(level);
				boolean fresh = BOOK.computeIfAbsent(p.getName().getString(), k -> new LinkedHashSet<>()).add(f.kind.name());
				save(level);
				f.hidden = 200 + level.getRandom().nextInt(200);
				Entity e = level.getEntity(f.id);
				if (e != null) {
					e.teleportTo(f.pos.x, f.pos.y - 40, f.pos.z);
				}
				level.sendParticles(ParticleTypes.HAPPY_VILLAGER, f.pos.x, f.pos.y, f.pos.z, 10, 0.3, 0.3, 0.3, 0.02);
				level.playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.6F);
				dev.rubymod.game.Alerts.title(p, f.kind.name() + "!", fresh ? "NEW for your butterfly book! (It flutters off again...)" : "Caught! (It flutters off again...)",
						f.kind.rarity() == 3 ? ChatFormatting.GOLD : ChatFormatting.LIGHT_PURPLE);
				if (fresh) {
					stockGarden(level);
				}
				return true;
			}
		}
		return true;
	}

	static void book(ServerPlayer p) {
		load((ServerLevel) p.level());
		Set<String> mine = BOOK.getOrDefault(p.getName().getString(), Set.of());
		MutableComponent text = Component.literal("🦋 " + p.getName().getString() + "'s Butterfly Book: " + mine.size() + " of " + KINDS.length + "\n")
				.withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD);
		for (Kind k : KINDS) {
			boolean got = mine.contains(k.name());
			text.append(Component.literal(got ? k.name() : "???").withStyle(got ? (k.rarity() == 3 ? ChatFormatting.GOLD : ChatFormatting.WHITE) : ChatFormatting.DARK_GRAY))
					.append(Component.literal("  "));
		}
		p.sendSystemMessage(text);
	}

	static void tick(ServerLevel level) {
		tick++;
		if (tick % 3 != 0) {
			return;
		}
		var random = level.getRandom();
		for (Fly f : FLIES) {
			Entity e = level.getEntity(f.id);
			if (!(e instanceof Display d)) {
				continue;
			}
			if (f.hidden > 0) {
				f.hidden -= 3;
				if (f.hidden <= 0) {
					f.pos = somewhere(random, f.garden);
					f.target = somewhere(random, f.garden);
					e.teleportTo(f.pos.x, f.pos.y, f.pos.z);
				}
				continue;
			}
			// Flutter towards somewhere, wobbling, then pick somewhere else.
			Vec3 to = f.target.subtract(f.pos);
			if (to.length() < 0.6) {
				f.target = somewhere(random, f.garden);
				continue;
			}
			Vec3 step = to.normalize().scale(0.22).add((random.nextDouble() - 0.5) * 0.15, Math.sin(tick * 0.4 + f.id.hashCode()) * 0.08, (random.nextDouble() - 0.5) * 0.15);
			f.pos = f.pos.add(step);
			e.teleportTo(f.pos.x, f.pos.y, f.pos.z);
			e.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
			// Wings flap: open, shut, open.
			float w = (tick / 3 + f.id.hashCode()) % 2 == 0 ? 0.5F : 0.15F;
			((DisplayAccessor) d).rubymod$setTransformationInterpolationDelay(0);
			((DisplayAccessor) d).rubymod$setTransformationInterpolationDuration(3);
			((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY((float) Math.PI), new Vector3f(w, 0.5F, 0.5F),
					new Quaternionf()));
		}
	}

	private static Path file(ServerLevel level) {
		return level.getServer().getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("butterflies.txt");
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
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't read the butterfly book", e);
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
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't save the butterfly book", e);
		}
	}

	static void reset() {
		FLIES.clear();
	}
}
