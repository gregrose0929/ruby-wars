package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.rubymod.game.Stats;
import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * The Codex: everything you've ever found in the Rift, kept between runs. Relics you've
 * held, combos you've switched on, legendaries you've forged, bosses you've beaten and
 * charms you've used. What you haven't found yet is a "???", something to hunt for.
 */
public final class RiftCodex {
	private RiftCodex() {
	}

	private static Set<String> found(ServerPlayer p) {
		Stats.Record r = Stats.get(p);
		if (r.riftCodex == null) {
			r.riftCodex = new HashSet<>();
		}
		return r.riftCodex;
	}

	/** Something new: into the Codex (and a little "NEW" note the first time; every 10 found pays 50 Rift Shards). */
	static void learn(ServerPlayer p, String key, String title) {
		if (found(p).add(key)) {
			Stats.markDirty();
			p.sendSystemMessage(Component.literal("\u2726 New in your Codex: ").withStyle(ChatFormatting.LIGHT_PURPLE)
					.append(Component.literal(title).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));
			p.level().playSound(null, p.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.8F, 1.2F);
			Stats.Record rec = Stats.get(p);
			while ((rec.riftCodexClaimed + 1) * 10 <= found(p).size()) {
				rec.riftCodexClaimed++;
				rec.riftShards += 50;
				p.sendSystemMessage(Component.literal("\u2726 Codex milestone: " + rec.riftCodexClaimed * 10 + " found! +50 Rift Shards")
						.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
				p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.6F, 1.6F);
			}
			for (String section : new String[] {"relics", "combos", "legendaries", "bosses", "charms"}) {
				if (key.startsWith(prefix(section)) && done(p, section)) {
					dev.rubymod.game.Alerts.title(p, "CODEX: " + section.toUpperCase() + " COMPLETE!", perk(section), ChatFormatting.GOLD);
				}
			}
		}
	}

	private static String prefix(String section) {
		return switch (section) {
			case "relics" -> "relic:";
			case "combos" -> "duo:";
			case "legendaries" -> "legend:";
			case "bosses" -> "boss:";
			default -> "charm:";
		};
	}

	private static int size(String section) {
		return switch (section) {
			case "relics" -> Relic.values().length;
			case "combos" -> RelicCombos.Duo.values().length;
			case "legendaries" -> RiftArsenal.legends().size();
			case "bosses" -> RiftBosses.Boss.values().length;
			default -> RiftCharms.Charm.values().length;
		};
	}

	/** Every entry of a section found. */
	static boolean done(ServerPlayer p, String section) {
		String pre = prefix(section);
		return found(p).stream().filter(k -> k.startsWith(pre)).count() >= size(section);
	}

	/** What finishing each section gives you, every run after. */
	static String perk(String section) {
		return switch (section) {
			case "relics" -> "Every run starts with a free Rare relic.";
			case "combos" -> "You can carry 4 charms instead of 3.";
			case "legendaries" -> "Your first weapon starts every run with a random power.";
			case "bosses" -> "Every run starts with 40 extra gold.";
			default -> "Every run starts with a random charm.";
		};
	}

	/** The start of a run: the perks you've earned. */
	static void startPerks(ServerPlayer p, Rift.Runner r) {
		var random = p.getRandom();
		if (done(p, "relics")) {
			List<Relic> rares = java.util.Arrays.stream(Relic.values()).filter(x -> x.tier == Relic.Tier.RARE && !r.has(x)).toList();
			if (!rares.isEmpty()) {
				Rift.give(p, r, rares.get(random.nextInt(rares.size())));
			}
		}
		if (done(p, "legendaries")) {
			for (int i = 0; i < 9; i++) {
				if (p.getInventory().getItem(i).getItem() instanceof dev.rubymod.gun.GunItem) {
					RiftSpring.temper(p, i);
					break;
				}
			}
		}
		if (done(p, "bosses")) {
			Rift.gold += 40;
		}
		if (done(p, "charms")) {
			RiftCharms.give(p, RiftCharms.Charm.values()[random.nextInt(RiftCharms.Charm.values().length)]);
		}
	}

	/** "count|section|found|total" per section, then "entry|section|id|title|icon|tier|about|found(1/0)". */
	static void send(ServerPlayer p) {
		Set<String> f = found(p);
		List<String> entries = new ArrayList<>();
		for (Relic r : Relic.values()) {
			entries.add(entry("relics", "relic:" + r.id(), r.title, r.icon, r.tier.name(), r.about, f));
		}
		for (RelicCombos.Duo d : RelicCombos.Duo.values()) {
			entries.add(entry("combos", "duo:" + d.id(), d.title, d.icon, "DUO", d.a.title + " + " + d.b.title + ": " + d.about, f));
		}
		for (RiftArsenal.Legend l : RiftArsenal.legends()) {
			entries.add(entry("legendaries", "legend:" + l.name(), l.name(), BuiltInRegistries.ITEM.getKey(l.base()).toString(), "LEGENDARY",
					l.base().getName(l.base().getDefaultInstance()).getString() + " + " + l.donor().getName(l.donor().getDefaultInstance()).getString() + ": "
							+ dev.rubymod.gun.Trait.legendAbout(l.name()),
					f));
		}
		for (RiftBosses.Boss b : RiftBosses.Boss.values()) {
			entries.add(entry("bosses", "boss:" + b.name(), b.title, b.icon, "BOSS", b.about, f));
		}
		for (RiftCharms.Charm c : RiftCharms.Charm.values()) {
			entries.add(entry("charms", "charm:" + c.name(), c.title, c.icon, "CHARM", c.about, f));
		}
		List<String> lines = new ArrayList<>();
		lines.add("title|The Codex");
		for (String section : new String[] {"relics", "combos", "legendaries", "bosses", "charms"}) {
			long total = entries.stream().filter(e -> e.startsWith("entry|" + section + "|")).count();
			long got = entries.stream().filter(e -> e.startsWith("entry|" + section + "|") && e.endsWith("|1")).count();
			lines.add("count|" + section + "|" + got + "|" + total);
		}
		lines.addAll(entries);
		// Rewards: the milestones, and each section's perk.
		int found = f.size();
		lines.add("milestone|" + found + "|" + ((found / 10 + 1) * 10));
		for (String section : new String[] {"relics", "combos", "legendaries", "bosses", "charms"}) {
			lines.add("perk|" + section + "|" + perk(section) + "|" + (done(p, section) ? 1 : 0));
		}
		Net.send(p, new MorePayloads.RiftScreen("codex", lines));
	}

	private static String entry(String section, String key, String title, String icon, String tier, String about, Set<String> found) {
		return "entry|" + section + "|" + key + "|" + title.replace("|", "/") + "|" + icon + "|" + tier + "|" + about.replace("|", "/") + "|"
				+ (found.contains(key) ? 1 : 0);
	}
}
