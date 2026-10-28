package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.gun.GunItem;
import dev.rubymod.gun.Trait;
import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The Arsenal (the room before each boss, and sometimes elsewhere): pick one of three
 * weapons you don't have yet. They come loaded, glow in the Rift's colours, and never
 * need ammo here. Got everything? Grenades instead.
 */
final class RiftArsenal {
	static final String PREFIX = "arm:";

	private record Weapon(Item item, String title, String about) {
	}

	private static final List<Weapon> WEAPONS = List.of(
			new Weapon(ModItems.SERVICE_PISTOL, "Service Pistol", "Quick and accurate. A handy backup."),
			new Weapon(ModItems.ARR_RIFLE, "AR-R Rifle", "Full-auto rifle that's good at any range."),
			new Weapon(ModItems.COMBAT_SHOTGUN, "Combat Shotgun", "Devastating up close: clears a crowd in a blast or two."),
			new Weapon(ModItems.LONGSHOT_SNIPER, "Longshot Sniper", "One huge shot. Aim for the head."),
			new Weapon(ModItems.RUBY_BLASTER, "Ruby Blaster", "Energy rifle whose bolts bounce off walls."),
			new Weapon(ModItems.ROCKET_LAUNCHER, "Rocket Launcher", "Blows whole groups apart."),
			new Weapon(ModItems.LANCER, "Lancer AT", "Guided missile that wrecks tanks and mechs."),
			new Weapon(ModItems.SKYHAMMER, "Skyhammer AA", "Locks onto anything that flies: gunships beware."),
			new Weapon(ModItems.RUBY_SWORD, "Ruby Sword", "For when they get too close."),
			new Weapon(ModItems.RUBY_MINIGUN, "Ruby Minigun", "Spins up from a stutter to a roar. Hold the trigger."),
			new Weapon(ModItems.RAIL_LANCE, "Rail Lance", "A heartbeat of charge, then a beam through every enemy in a line."),
			new Weapon(ModItems.RUBY_CHAKRAM, "Ruby Chakram", "A spinning blade that ricochets through six enemies, then flies home."),
			new Weapon(ModItems.DRAGONS_BREATH, "Dragon's Breath", "A roaring cone of flame that sets everything alight."),
			new Weapon(ModItems.SWARM_LAUNCHER, "Swarm Launcher", "Six mini missiles, each picking its own target."),
			new Weapon(ModItems.TESLA_COILGUN, "Tesla Coilgun", "A crackling beam that jumps from enemy to enemy."),
			new Weapon(ModItems.BOUNCER, "Bouncer", "Grenades that bounce off walls and burst on anything they touch."),
			new Weapon(ModItems.SIX_SHOOTER, "Six-Shooter", "Fan the hammer. Headshots ricochet into the next head."),
			new Weapon(ModItems.RUBY_WARHAMMER, "Ruby Warhammer", "Slam the ground and throw them into the air. Jump first for a bigger slam."),
			new Weapon(ModItems.SINGULARITY_CANNON, "Singularity Cannon", "A slow orb that drags everything in, then crushes it."));

	private RiftArsenal() {
	}

	/** "title|..." then three "arm:<item>|title|about|icon" choices, and FUSE when you've two weapons that can be fused. */
	static List<String> options(ServerPlayer p, RandomSource random) {
		List<Weapon> left = new ArrayList<>();
		for (Weapon w : WEAPONS) {
			if (p.getInventory().countItem(w.item()) == 0) {
				left.add(w);
			}
		}
		List<String> out = new ArrayList<>();
		out.add("title|The Arsenal: pick a new weapon, or fuse two");
		for (int i = 0; i < 3 && !left.isEmpty(); i++) {
			Weapon w = left.remove(random.nextInt(left.size()));
			out.add(PREFIX + BuiltInRegistries.ITEM.getKey(w.item()) + "|" + w.title() + "|" + w.about() + "|" + BuiltInRegistries.ITEM.getKey(w.item()));
		}
		while (out.size() < 4) {
			out.add(PREFIX + "grenades" + (out.size() > 2 ? "2" : "") + "|Grenade Belt|Eight frag grenades.|rubymod:frag_grenade");
		}
		if (!fusions(p, random).isEmpty()) {
			out.add(PREFIX + "fuse|FUSE TWO WEAPONS|One of your weapons gives its power to another (and is used up). "
					+ "Some pairs make a legendary.|minecraft:smithing_table");
		}
		return out;
	}

	// ----- Fusion -----

	/** The named legendary fusions: base + donor -> name, and the bonus trait it gets. */
	private record Legend(Item base, Item donor, String name, Trait bonus) {
	}

	private static final List<Legend> LEGENDS = List.of(
			new Legend(ModItems.RUBY_MINIGUN, ModItems.DRAGONS_BREATH, "Inferno Gatling", Trait.BLAST),
			new Legend(ModItems.RUBY_CHAKRAM, ModItems.TESLA_COILGUN, "Storm Disc", Trait.BLAST),
			new Legend(ModItems.LONGSHOT_SNIPER, ModItems.SINGULARITY_CANNON, "Event Horizon", Trait.BLAST),
			new Legend(ModItems.SWARM_LAUNCHER, ModItems.ROCKET_LAUNCHER, "Apocalypse Pod", Trait.IGNITE),
			new Legend(ModItems.COMBAT_SHOTGUN, ModItems.SIX_SHOOTER, "Dragon Duster", Trait.IGNITE),
			new Legend(ModItems.RUBY_WARHAMMER, ModItems.RUBY_SWORD, "Ruby Titan Blade", Trait.VORTEX));

	private record Fusion(int base, int donor, Trait trait, Legend legend) {
	}

	private static Legend legend(Item base, Item donor) {
		for (Legend l : LEGENDS) {
			if (l.base() == base && l.donor() == donor) {
				return l;
			}
		}
		return null;
	}

	/** Every fusion this player could make right now: legendaries first, then up to five others. */
	private static List<Fusion> fusions(ServerPlayer p, RandomSource random) {
		var inv = p.getInventory();
		List<Fusion> legends = new ArrayList<>();
		List<Fusion> plain = new ArrayList<>();
		for (int b = 0; b < inv.getContainerSize(); b++) {
			ItemStack base = inv.getItem(b);
			if (!(base.getItem() instanceof GunItem gun)) {
				continue;
			}
			List<Trait> has = Trait.of(base);
			if (has.size() >= 3 || Trait.legendary(base) != null) {
				continue;
			}
			boolean exotic = RiftWeapons.handles(gun.stats().special());
			for (int d = 0; d < inv.getContainerSize(); d++) {
				ItemStack donor = inv.getItem(d);
				Trait t = donor.isEmpty() || d == b ? null : Trait.donor(donor.getItem());
				if (t == null || has.contains(t) || t == Trait.donor(base.getItem()) || donor.getItem() == base.getItem()) {
					continue;
				}
				// The exotic weapons fire their own way: only traits that act on hits (or Quickdraw) work on them.
				if (exotic && t.kind != Trait.Kind.HIT && t != Trait.QUICKDRAW) {
					continue;
				}
				Legend l = legend(base.getItem(), donor.getItem());
				(l != null ? legends : plain).add(new Fusion(b, d, t, l));
			}
		}
		java.util.Collections.shuffle(plain, new java.util.Random(random.nextLong()));
		List<Fusion> out = new ArrayList<>(legends.subList(0, Math.min(5, legends.size())));
		for (Fusion f : plain) {
			if (out.size() >= 5) {
				break;
			}
			out.add(f);
		}
		return out;
	}

	/** The name a fused weapon gets: "Explosive Blazing AR-R Rifle", or a legendary's own. */
	private static String fusedName(ItemStack base, List<Trait> traits, Legend l) {
		if (l != null) {
			return l.name();
		}
		StringBuilder name = new StringBuilder();
		for (Trait t : traits) {
			name.append(t.adjective).append(' ');
		}
		return name + base.getItem().getName(base).getString();
	}

	private static void showFusions(ServerPlayer p, Rift.Runner r) {
		var inv = p.getInventory();
		List<String> lines = new ArrayList<>();
		lines.add("title|Fuse: the second gun powers up the first");
		for (Fusion f : fusions(p, p.getRandom())) {
			ItemStack base = inv.getItem(f.base());
			ItemStack donor = inv.getItem(f.donor());
			List<Trait> after = new ArrayList<>(Trait.of(base));
			after.add(f.trait());
			String name = fusedName(base, after, f.legend());
			String about = (f.legend() != null ? "LEGENDARY (+50% damage, and " + f.legend().bonus().about + "). " : "")
					+ base.getItem().getName(base).getString() + " gains " + f.trait().title + ": " + f.trait().about + ". The "
					+ donor.getItem().getName(donor).getString() + " is used up.";
			lines.add(PREFIX + "fz:" + f.base() + ":" + f.donor() + "|" + (f.legend() != null ? "\u2605 " : "") + name + "|" + about + "|"
					+ BuiltInRegistries.ITEM.getKey(base.getItem()));
		}
		lines.add(PREFIX + "back|Never mind|Back to the Arsenal's weapons.|minecraft:barrier");
		r.event = lines;
		Net.send(p, new MorePayloads.RiftScreen("event", lines));
	}

	private static void fuse(ServerPlayer p, int b, int d) {
		var inv = p.getInventory();
		ItemStack base = inv.getItem(b);
		ItemStack donor = inv.getItem(d);
		Trait t = donor.isEmpty() ? null : Trait.donor(donor.getItem());
		if (!(base.getItem() instanceof GunItem) || t == null || b == d) {
			return;
		}
		Legend l = legend(base.getItem(), donor.getItem());
		List<String> traits = new ArrayList<>(base.getOrDefault(ModComponents.TRAITS, List.<String>of()));
		traits.add(t.name());
		if (l != null) {
			traits.add(l.bonus().name());
			traits.add("LEGEND:" + l.name());
		}
		base.set(ModComponents.TRAITS, traits);
		List<Trait> now = Trait.of(base);
		String name = fusedName(base, now, l);
		base.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(l != null ? ChatFormatting.GOLD
				: ChatFormatting.AQUA, ChatFormatting.BOLD).withStyle(s -> s.withItalic(false)));
		if (base.getItem() instanceof GunItem gun) {
			base.set(ModComponents.AMMO, gun.stats(base).magazine());
		}
		inv.setItem(d, ItemStack.EMPTY);
		p.inventoryMenu.broadcastChanges();
		// The flash: the donor's light pours into the base.
		ServerLevel level = (ServerLevel) p.level();
		Vec3 at = p.position().add(0, 1.2, 0);
		for (int i = 0; i < 30; i++) {
			double a = i * 0.5;
			double rad = 2.4 - i * 0.07;
			level.sendParticles(RiftFx.dust(l != null ? 0xFFC02E : t.color & 0xFFFFFF, 1.4F), true, false, at.x + Math.cos(a) * rad, at.y + i * 0.04,
					at.z + Math.sin(a) * rad, 1, 0, 0, 0, 0);
		}
		RiftFx.burst(level, at.add(0, 0.8, 0), l != null ? 0xFFFFC02E : t.color, 50, 0.9);
		RiftFx.beam(level, p.position(), l != null ? 20 : 12, net.minecraft.core.particles.ParticleTypes.END_ROD, l != null ? 0xFFFFC02E : t.color);
		level.playSound(null, p.blockPosition(), SoundEvents.SMITHING_TABLE_USE, SoundSource.PLAYERS, 1.0F, 0.9F);
		level.playSound(null, p.blockPosition(), l != null ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS,
				0.8F, 1.2F);
		dev.rubymod.game.Alerts.title(p, l != null ? "LEGENDARY FUSION!" : "FUSED!", name + ": " + t.about, l != null ? ChatFormatting.GOLD
				: ChatFormatting.AQUA);
		p.sendSystemMessage(Component.literal("Fused: " + name + ". Hover it in your inventory to see its traits.").withStyle(ChatFormatting.AQUA));
	}

	static void give(ServerPlayer p, Rift.Runner r, String option) {
		String id = option.substring(PREFIX.length());
		if (id.equals("fuse")) {
			showFusions(p, r);
			return;
		}
		if (id.equals("back")) {
			r.event = options(p, p.getRandom());
			Net.send(p, new MorePayloads.RiftScreen("event", r.event));
			return;
		}
		if (id.startsWith("fz:")) {
			String[] f = id.split(":");
			fuse(p, Integer.parseInt(f[1]), Integer.parseInt(f[2]));
			return;
		}
		ItemStack stack;
		String name;
		if (id.startsWith("grenades")) {
			stack = new ItemStack(ModItems.FRAG_GRENADE, 8);
			name = "a Grenade Belt";
		} else {
			Item item = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id));
			Weapon w = WEAPONS.stream().filter(x -> x.item() == item).findFirst().orElse(null);
			if (w == null) {
				return;
			}
			stack = new ItemStack(item);
			if (item instanceof GunItem gun) {
				stack.set(ModComponents.AMMO, gun.stats().magazine());
			}
			name = "the " + w.title();
		}
		if (!p.getInventory().add(stack)) {
			p.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
		}
		p.inventoryMenu.broadcastChanges();
		p.sendSystemMessage(Component.literal("You took " + name + "! (Scroll or press its number to switch weapons.)").withStyle(ChatFormatting.GOLD));
		p.level().playSound(null, p.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 1.0F, 1.1F);
		RiftFx.burst((ServerLevel) p.level(), p.position().add(0, 1, 0), 0xFFFFC02E, 16, 0.5);
	}
}
