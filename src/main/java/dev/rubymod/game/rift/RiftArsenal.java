package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.gun.GunItem;
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

	/** "title|..." then three "arm:<item>|title|about|icon" choices. */
	static List<String> options(ServerPlayer p, RandomSource random) {
		List<Weapon> left = new ArrayList<>();
		for (Weapon w : WEAPONS) {
			if (p.getInventory().countItem(w.item()) == 0) {
				left.add(w);
			}
		}
		List<String> out = new ArrayList<>();
		out.add("title|The Arsenal: pick a new weapon");
		for (int i = 0; i < 3 && !left.isEmpty(); i++) {
			Weapon w = left.remove(random.nextInt(left.size()));
			out.add(PREFIX + BuiltInRegistries.ITEM.getKey(w.item()) + "|" + w.title() + "|" + w.about() + "|" + BuiltInRegistries.ITEM.getKey(w.item()));
		}
		while (out.size() < 4) {
			out.add(PREFIX + "grenades" + (out.size() > 2 ? "2" : "") + "|Grenade Belt|Eight frag grenades.|rubymod:frag_grenade");
		}
		return out;
	}

	static void give(ServerPlayer p, String option) {
		String id = option.substring(PREFIX.length());
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
