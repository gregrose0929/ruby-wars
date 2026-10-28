package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.gun.GunItem;
import dev.rubymod.gun.Trait;
import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;
import dev.rubymod.registry.ModComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The Ruby Spring: a rest stop on the safe lane (and before every boss). Each runner takes
 * one gift: heal, give one of their relics another star, or temper a weapon (a random new
 * power, without using up another gun).
 */
final class RiftSpring {
	static final String PREFIX = "spr:";

	private RiftSpring() {
	}

	static List<String> options(ServerPlayer p, Rift.Runner r) {
		List<String> out = new ArrayList<>();
		out.add("title|The Ruby Spring: take one gift");
		int missing = (int) Math.ceil(p.getMaxHealth() - p.getHealth());
		out.add(PREFIX + "heal|Drink from the Spring|Heal 40% of your health" + (missing > 0 ? " (you're missing " + missing / 2 + " hearts)." : ".")
				+ "|minecraft:potion");
		if (r.relics.stream().anyMatch(r::canCombine)) {
			out.add(PREFIX + "relic|Strengthen a Relic|One of your relics gains a star. You choose which.|minecraft:nether_star");
		}
		if (!Rift.KEYS.contains("life")) {
			out.add(PREFIX + "key|Take the Key of Life|A Rift Key, instead of a gift. Find all three (flame, gold, life) to open a secret act.|minecraft:trial_key");
		}
		if (!temperable(p).isEmpty()) {
			out.add(PREFIX + "temper|Temper a Weapon|One of your weapons gains a random new power. You choose which weapon.|minecraft:anvil");
		}
		return out;
	}

	/** Weapon slots that can take another power. */
	private static List<Integer> temperable(ServerPlayer p) {
		List<Integer> out = new ArrayList<>();
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize() && out.size() < 5; i++) {
			ItemStack s = inv.getItem(i);
			if (s.getItem() instanceof GunItem && Trait.of(s).size() < 3 && Trait.legendary(s) == null && !powers(s).isEmpty()) {
				out.add(i);
			}
		}
		return out;
	}

	/** The powers a weapon could gain (the exotic ones only take powers that work on hits). */
	private static List<Trait> powers(ItemStack s) {
		List<Trait> has = Trait.of(s);
		boolean exotic = s.getItem() instanceof GunItem gun && RiftWeapons.handles(gun.stats().special());
		List<Trait> out = new ArrayList<>();
		for (Trait t : Trait.values()) {
			if (!has.contains(t) && t != Trait.donor(s.getItem()) && (!exotic || t.kind == Trait.Kind.HIT || t == Trait.QUICKDRAW)) {
				out.add(t);
			}
		}
		return out;
	}

	private static void show(ServerPlayer p, Rift.Runner r, List<String> lines) {
		r.event = lines;
		Net.send(p, new MorePayloads.RiftScreen("event", lines));
	}

	static void choose(ServerPlayer p, Rift.Runner r, String option) {
		String id = option.substring(PREFIX.length());
		ServerLevel level = (ServerLevel) p.level();
		if (id.equals("back")) {
			show(p, r, options(p, r));
		} else if (id.equals("heal")) {
			p.heal(p.getMaxHealth() * 0.4F);
			level.sendParticles(ParticleTypes.HEART, p.getX(), p.getY() + 1.2, p.getZ(), 12, 0.5, 0.6, 0.5, 0);
			RiftFx.beam(level, p.position(), 10, ParticleTypes.END_ROD, 0xFF6EE38A);
			RiftFx.ring(level, p.position().add(0, 0.2, 0), 1.4, RiftFx.dust(0xFF3D6E, 1.4F), 24);
			level.playSound(null, p.blockPosition(), SoundEvents.GENERIC_DRINK.value(), SoundSource.PLAYERS, 1.0F, 1.0F);
			level.playSound(null, p.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.8F, 1.4F);
			dev.rubymod.game.Alerts.title(p, "REFRESHED", "The Spring heals you", ChatFormatting.GREEN);
		} else if (id.equals("key")) {
			Rift.grantKey(level.getServer(), "life", "Key of Life");
		} else if (id.equals("relic")) {
			List<String> lines = new ArrayList<>();
			lines.add("title|Which relic gains a star?");
			for (Relic relic : r.relics) {
				if (r.canCombine(relic) && lines.size() < 6) {
					int to = r.level(relic) + 1;
					lines.add(PREFIX + "up:" + relic.id() + "|" + relic.title(to) + "|" + relic.upgrade(to) + "|" + relic.icon);
				}
			}
			lines.add(PREFIX + "back|Never mind|Back to the Spring.|minecraft:barrier");
			show(p, r, lines);
		} else if (id.equals("temper")) {
			List<String> lines = new ArrayList<>();
			lines.add("title|Which weapon gets a new power?");
			for (int slot : temperable(p)) {
				ItemStack s = p.getInventory().getItem(slot);
				lines.add(PREFIX + "t:" + slot + "|" + s.getHoverName().getString() + "|Gains a random new power (it keeps the ones it has).|"
						+ BuiltInRegistries.ITEM.getKey(s.getItem()));
			}
			lines.add(PREFIX + "back|Never mind|Back to the Spring.|minecraft:barrier");
			show(p, r, lines);
		} else if (id.startsWith("up:")) {
			Relic relic = Relic.byId(id.substring(3));
			if (relic != null && r.canCombine(relic)) {
				Rift.give(p, r, relic);
			}
		} else if (id.startsWith("t:")) {
			temper(p, Integer.parseInt(id.substring(2)));
		}
	}

	static void temper(ServerPlayer p, int slot) {
		ItemStack s = p.getInventory().getItem(slot);
		List<Trait> can = powers(s);
		if (!(s.getItem() instanceof GunItem gun) || can.isEmpty() || Trait.of(s).size() >= 3) {
			return;
		}
		ServerLevel level = (ServerLevel) p.level();
		Trait t = can.get(level.getRandom().nextInt(can.size()));
		List<String> traits = new ArrayList<>(s.getOrDefault(ModComponents.TRAITS, List.<String>of()));
		traits.add(t.name());
		s.set(ModComponents.TRAITS, traits);
		String name = RiftArsenal.fusedName(s, Trait.of(s), null);
		s.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
				.withStyle(st -> st.withItalic(false)));
		s.set(ModComponents.AMMO, gun.stats(s).magazine());
		p.inventoryMenu.broadcastChanges();
		// Hammered on the anvil: sparks in the power's colour.
		Vec3 at = p.position().add(0, 1.2, 0);
		level.sendParticles(ParticleTypes.LAVA, at.x, at.y, at.z, 10, 0.3, 0.2, 0.3, 0);
		RiftFx.burst(level, at, t.color, 40, 0.8);
		level.playSound(null, p.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 1.0F, 1.0F);
		level.playSound(null, p.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.7F, 1.6F);
		dev.rubymod.game.Alerts.title(p, "TEMPERED!", name + ": " + t.about, ChatFormatting.AQUA);
		p.sendSystemMessage(Component.literal("Tempered: " + name + " gained " + t.title + " (" + t.about + ").").withStyle(ChatFormatting.AQUA));
	}
}
