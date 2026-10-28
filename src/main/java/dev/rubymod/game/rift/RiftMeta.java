package dev.rubymod.game.rift;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import dev.rubymod.RubyMod;
import dev.rubymod.game.Stats;
import dev.rubymod.gun.GunItem;
import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;

/**
 * What carries over between Rift runs. Rift Shards (earned every run: deeper, elites,
 * bosses, more Heat = more) unlock new starting loadouts and new relics at the Altar of
 * Echoes in the Threshold. Heat 1-5 makes runs harder for more shards (win at a Heat to
 * unlock the next). The Daily Rift: the same run for everyone today, with a leaderboard.
 */
public final class RiftMeta {
	public enum Loadout {
		SOLDIER("Soldier", "rubymod:arr_rifle", "Rifle, shotgun, sword and grenades. Solid all round.", 0, null),
		ENGINEER("Engineer", "rubymod:sentry_turret", "Rifle and pistol. Starts with Drop Turret.", 25, Relic.DROP_TURRET),
		DEMOLITIONIST("Demolitionist", "rubymod:rocket_launcher", "Rocket launcher and a pile of grenades. Starts with Gravity Grenades.", 30,
				Relic.GRAVITY_GRENADES),
		SNIPER("Sniper", "rubymod:longshot_sniper", "Longshot sniper and pistol. Starts with Headhunter.", 30, Relic.HEADHUNTER),
		MEDIC("Medic", "minecraft:golden_apple", "Rifle and golden apples. Starts with Field Medic.", 25, Relic.FIELD_MEDIC);

		public final String title;
		public final String icon;
		public final String about;
		public final int cost;
		public final Relic starts;

		Loadout(String title, String icon, String about, int cost, Relic starts) {
			this.title = title;
			this.icon = icon;
			this.about = about;
			this.cost = cost;
			this.starts = starts;
		}

		static Loadout byId(String id) {
			for (Loadout l : values()) {
				if (l.name().equalsIgnoreCase(id)) {
					return l;
				}
			}
			return SOLDIER;
		}
	}

	public static final String[] HEAT = {"No Heat", "Hardened", "Elite Swarm", "No Respite", "Bloodthirst", "Apex"};
	public static final String[] HEAT_ABOUT = {"The Rift as it is.", "Monsters have 30% more health.", "Every fight ends with an elite.",
			"No healing after rooms (the Arsenal revives at half health).", "Monsters hit 40% harder and move faster.", "Bosses have 50% more health."};

	/** The team's Heat for the next run, and whether it's the Daily Rift. */
	static int heat;
	static boolean daily;
	private static Map<String, Map<String, Integer>> dailyScores;
	private static Path dailyFile;

	private RiftMeta() {
	}

	static Stats.Record rec(ServerPlayer p) {
		Stats.Record r = Stats.get(p);
		if (r.riftUnlocks == null) {
			r.riftUnlocks = new HashSet<>();
		}
		if (r.riftLoadout == null) {
			r.riftLoadout = "soldier";
		}
		return r;
	}

	static boolean owns(ServerPlayer p, Loadout l) {
		return l.cost == 0 || rec(p).riftUnlocks.contains("loadout:" + l.name().toLowerCase());
	}

	static int unlockCost(Relic r) {
		return r.tier == Relic.Tier.LEGENDARY ? 40 : r.tier == Relic.Tier.RARE ? 22 : 15;
	}

	/** The most Heat anyone here has unlocked (they can bring the team along). */
	static int maxHeat(MinecraftServer server) {
		int max = 0;
		for (ServerPlayer p : Rift.players(server)) {
			max = Math.max(max, rec(p).riftHeatUnlocked);
		}
		return Math.min(5, max);
	}

	// ----- The Altar of Echoes screen -----

	static void send(ServerPlayer p) {
		Stats.Record r = rec(p);
		List<String> lines = new ArrayList<>();
		lines.add("shards|" + r.riftShards);
		lines.add("heat|" + heat + "|" + maxHeat(p.level().getServer()));
		lines.add("daily|" + (daily ? "on" : "off") + "|" + String.join(";", dailyTop(p.level().getServer(), 3)));
		for (Loadout l : Loadout.values()) {
			String state = !owns(p, l) ? "locked" : Loadout.byId(r.riftLoadout) == l ? "selected" : "owned";
			lines.add("loadout|" + l.name().toLowerCase() + "|" + l.title + "|" + l.icon + "|" + l.about + "|" + l.cost + "|" + state);
		}
		for (Relic relic : Relic.values()) {
			if (relic.locked) {
				lines.add("relic|" + relic.id() + "|" + relic.title + "|" + relic.icon + "|" + relic.about + "|" + unlockCost(relic) + "|"
						+ (r.riftUnlocks.contains(relic.id()) ? "owned" : "locked") + "|" + relic.tier.name());
			}
		}
		Net.send(p, new MorePayloads.RiftScreen("threshold", lines));
	}

	static void choose(ServerPlayer p, String kind, String id) {
		if (Rift.state != Rift.State.STAGING && Rift.state != Rift.State.OVER) {
			p.sendOverlayMessage(Component.literal("You can change that in the Threshold, between runs.").withStyle(ChatFormatting.RED));
			return;
		}
		Stats.Record r = rec(p);
		MinecraftServer server = p.level().getServer();
		switch (kind) {
			case "loadout" -> {
				Loadout l = Loadout.byId(id);
				if (!owns(p, l)) {
					if (r.riftShards < l.cost) {
						p.sendOverlayMessage(Component.literal("You need " + l.cost + " Rift Shards for the " + l.title + ".").withStyle(ChatFormatting.RED));
						return;
					}
					r.riftShards -= l.cost;
					r.riftUnlocks.add("loadout:" + l.name().toLowerCase());
					unlockFx(p, l.title);
				}
				r.riftLoadout = l.name().toLowerCase();
				Stats.markDirty();
			}
			case "unlock" -> {
				Relic relic = Relic.byId(id);
				if (relic == null || !relic.locked || r.riftUnlocks.contains(relic.id())) {
					return;
				}
				int cost = unlockCost(relic);
				if (r.riftShards < cost) {
					p.sendOverlayMessage(Component.literal("You need " + cost + " Rift Shards for " + relic.title + ".").withStyle(ChatFormatting.RED));
					return;
				}
				r.riftShards -= cost;
				r.riftUnlocks.add(relic.id());
				Stats.markDirty();
				unlockFx(p, relic.title + " (it can now turn up in your relic offers)");
			}
			case "heat" -> {
				int h = Math.max(0, Math.min(maxHeat(server), Integer.parseInt(id)));
				if (h != heat) {
					heat = h;
					Rift.tell(server, Component.literal(p.getName().getString() + " set the Heat to " + h + ": " + HEAT[h]
							+ (h > 0 ? " (" + HEAT_ABOUT[h] + " Shards x" + String.format("%.2f", 1 + 0.25 * h) + ")" : "")).withStyle(ChatFormatting.GOLD));
				}
			}
			case "daily" -> {
				daily = !daily;
				Rift.tell(server, Component.literal(p.getName().getString() + (daily ? " turned the Daily Rift ON: today's run, the same for everyone."
						: " turned the Daily Rift off.")).withStyle(ChatFormatting.LIGHT_PURPLE));
			}
			default -> {
			}
		}
		for (ServerPlayer mate : Rift.players(server)) {
			send(mate);
		}
	}

	private static void unlockFx(ServerPlayer p, String what) {
		p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0F, 1.2F);
		RiftFx.screen(p, "flash", 0xFFC27BFF, 18);
		RiftFx.burst((net.minecraft.server.level.ServerLevel) p.level(), p.position().add(0, 1, 0), 0xFFC27BFF, 30, 0.7);
		p.sendSystemMessage(Component.literal("Unlocked: " + what).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
	}

	// ----- Starting a run -----

	private static ItemStack gun(Item item) {
		ItemStack s = new ItemStack(item);
		s.set(ModComponents.AMMO, ((GunItem) item).stats().magazine());
		return s;
	}

	/** Your loadout's kit, and its starting relic (quietly: no fanfare). */
	static void kit(ServerPlayer p, Rift.Runner r) {
		Loadout l = Loadout.byId(rec(p).riftLoadout);
		var inv = p.getInventory();
		inv.clearContent();
		p.removeAllEffects();
		switch (l) {
			case ENGINEER -> {
				inv.add(gun(ModItems.ARR_RIFLE));
				inv.add(gun(ModItems.SERVICE_PISTOL));
				inv.add(new ItemStack(ModItems.RUBY_SWORD));
				inv.add(new ItemStack(ModItems.FRAG_GRENADE, 2));
				inv.add(new ItemStack(ModItems.LIGHT_AMMO, 64));
			}
			case DEMOLITIONIST -> {
				inv.add(new ItemStack(ModItems.ROCKET_LAUNCHER));
				inv.add(gun(ModItems.ARR_RIFLE));
				inv.add(new ItemStack(ModItems.RUBY_SWORD));
				inv.add(new ItemStack(ModItems.FRAG_GRENADE, 6));
				inv.add(new ItemStack(ModItems.ROCKET, 16));
				inv.add(new ItemStack(ModItems.ROCKET, 16));
			}
			case SNIPER -> {
				inv.add(gun(ModItems.LONGSHOT_SNIPER));
				inv.add(gun(ModItems.SERVICE_PISTOL));
				inv.add(new ItemStack(ModItems.RUBY_SWORD));
				inv.add(new ItemStack(ModItems.FRAG_GRENADE, 2));
				inv.add(new ItemStack(ModItems.SNIPER_ROUND, 64));
				inv.add(new ItemStack(ModItems.LIGHT_AMMO, 64));
			}
			case MEDIC -> {
				inv.add(gun(ModItems.ARR_RIFLE));
				inv.add(gun(ModItems.COMBAT_SHOTGUN));
				inv.add(new ItemStack(ModItems.RUBY_SWORD));
				inv.add(new ItemStack(Items.GOLDEN_APPLE, 6));
			}
			default -> {
				inv.add(gun(ModItems.ARR_RIFLE));
				inv.add(gun(ModItems.COMBAT_SHOTGUN));
				inv.add(new ItemStack(ModItems.RUBY_SWORD));
				inv.add(new ItemStack(ModItems.FRAG_GRENADE, 3));
			}
		}
		p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
		p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
		p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
		inv.setSelectedSlot(0);
		if (r != null && l.starts != null && !r.has(l.starts)) {
			r.relics.add(l.starts);
		}
	}

	/**
	 * The color Rift weapons glow in each level: Crystal Caverns amethyst, Molten Forge
	 * lava orange, Frozen Vault ice blue, Overgrown Lab toxic green, Mech Foundry hazard
	 * yellow, Sky Dock electric blue, the Ruby Heart a pulsing crimson, the Threshold ruby.
	 */
	static int glowColor(RiftRoom.Theme theme) {
		return switch (theme) {
			case CRYSTAL -> 0xB450FF;
			case FORGE -> 0xFF8C1A;
			case FROST -> 0x5CE8FF;
			case LAB -> 0x6BFF4A;
			case FOUNDRY -> 0xFFD21A;
			case SKYDOCK -> 0x3D8BFF;
			case HEART -> 0xFF1F4F;
			case THRESHOLD -> 0xFF3264;
		};
	}

	/**
	 * Rift weapons look the part: glowing reskins (picked by the "rift" custom model data),
	 * their glowing parts in the color of the level ({@code theme}).
	 */
	static void riftify(ServerPlayer p, RiftRoom.Theme theme) {
		var look = new net.minecraft.world.item.component.CustomModelData(List.of(), List.of(),
				theme == RiftRoom.Theme.HEART ? List.of("rift", "pulse") : List.of("rift"), List.of(glowColor(theme)));
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			boolean weapon = s.getItem() instanceof GunItem || s.is(ModItems.RUBY_SWORD) || s.is(ModItems.FRAG_GRENADE) || s.is(ModItems.ROCKET_LAUNCHER)
					|| s.is(ModItems.SKYHAMMER) || s.is(ModItems.LANCER);
			if (weapon && !look.equals(s.get(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA))) {
				s.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA, look);
			}
		}
	}

	// ----- Rewards at the end of a run -----

	/** Shards for a run: two a room, three an elite, ten a boss; more with Heat. */
	static void award(MinecraftServer server, int rooms, int elites, int bosses, boolean won) {
		double mult = 1 + 0.25 * heat;
		int shards = (int) Math.round((rooms * 2 + elites * 3 + bosses * 10 + (won ? 15 : 0)) * mult);
		for (ServerPlayer p : Rift.players(server)) {
			Stats.Record r = rec(p);
			r.riftShards += shards;
			if (won && heat >= r.riftHeatUnlocked && heat < 5) {
				r.riftHeatUnlocked = heat + 1;
				p.sendSystemMessage(Component.literal("Heat " + (heat + 1) + " (" + HEAT[heat + 1] + ") unlocked!").withStyle(ChatFormatting.GOLD,
						ChatFormatting.BOLD));
			}
			p.sendSystemMessage(Component.literal("+" + shards + " Rift Shards (" + r.riftShards + " now). Spend them at the Altar of Echoes: press J in the Threshold.")
					.withStyle(ChatFormatting.LIGHT_PURPLE));
		}
		Stats.markDirty();
	}

	// ----- The Daily Rift -----

	static long dailySeed() {
		return java.time.LocalDate.now().toEpochDay() * 0x9E3779B97F4A7C15L + 0x52494654L;
	}

	private static String today() {
		return java.time.LocalDate.now().toString();
	}

	private static void loadDaily(MinecraftServer server) {
		if (dailyScores != null) {
			return;
		}
		dailyScores = new HashMap<>();
		dailyFile = server.getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("rift_daily.json");
		try {
			if (Files.exists(dailyFile)) {
				try (Reader reader = Files.newBufferedReader(dailyFile)) {
					Map<String, Map<String, Integer>> loaded = new Gson().fromJson(reader, new TypeToken<Map<String, Map<String, Integer>>>() { }.getType());
					if (loaded != null) {
						dailyScores = loaded;
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			RubyMod.LOGGER.warn("Couldn't read the Daily Rift scores", e);
		}
	}

	static void recordDaily(MinecraftServer server, int score) {
		loadDaily(server);
		Map<String, Integer> day = dailyScores.computeIfAbsent(today(), k -> new HashMap<>());
		for (ServerPlayer p : Rift.players(server)) {
			String name = p.getName().getString();
			if (score > day.getOrDefault(name, 0)) {
				day.put(name, score);
			}
			p.sendSystemMessage(Component.literal("Daily Rift score: " + score + ". Today's best: " + String.join(", ", dailyTop(server, 3)))
					.withStyle(ChatFormatting.LIGHT_PURPLE));
		}
		try {
			Files.createDirectories(dailyFile.getParent());
			try (Writer w = Files.newBufferedWriter(dailyFile)) {
				new Gson().toJson(dailyScores, w);
			}
		} catch (IOException e) {
			RubyMod.LOGGER.warn("Couldn't save the Daily Rift scores", e);
		}
	}

	public static List<String> dailyTop(MinecraftServer server, int n) {
		loadDaily(server);
		Map<String, Integer> day = dailyScores.getOrDefault(today(), Map.of());
		return day.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(n).map(e -> e.getKey() + " " + e.getValue()).toList();
	}
}
