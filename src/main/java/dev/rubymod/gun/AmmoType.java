package dev.rubymod.gun;

import java.util.function.Supplier;

import dev.rubymod.registry.ModItems;

import net.minecraft.world.item.Item;

public enum AmmoType {
	LIGHT(() -> ModItems.LIGHT_AMMO),
	HEAVY(() -> ModItems.HEAVY_AMMO),
	SHELLS(() -> ModItems.SHOTGUN_SHELL),
	SNIPER(() -> ModItems.SNIPER_ROUND),
	RUBY_CELL(() -> ModItems.RUBY_CELL);

	private final Supplier<Item> item;

	AmmoType(Supplier<Item> item) {
		this.item = item;
	}

	public Item item() {
		return item.get();
	}
}
