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

	/** Something new: into the Codex (and a little "NEW" note the first time). */
	static void learn(ServerPlayer p, String key, String title) {
		if (found(p).add(key)) {
			Stats.markDirty();
			p.sendSystemMessage(Component.literal("✦ New in your Codex: ").withStyle(ChatFormatting.LIGHT_PURPLE)
					.append(Component.literal(title).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));
			p.level().playSound(null, p.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.8F, 1.2F);
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
		Net.send(p, new MorePayloads.RiftScreen("codex", lines));
	}

	private static String entry(String section, String key, String title, String icon, String tier, String about, Set<String> found) {
		return "entry|" + section + "|" + key + "|" + title.replace("|", "/") + "|" + icon + "|" + tier + "|" + about.replace("|", "/") + "|"
				+ (found.contains(key) ? 1 : 0);
	}
}
