package dev.rubymod.registry;

import dev.rubymod.RubyMod;
import dev.rubymod.industry.MachineMenu;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public class ModMenus {
	public static final ExtendedMenuType<MachineMenu, MachineMenu.Opening> MACHINE = Registry.register(
			BuiltInRegistries.MENU,
			RubyMod.id("machine"),
			new ExtendedMenuType<>(MachineMenu::new, MachineMenu.Opening.CODEC));

	public static final ExtendedMenuType<dev.rubymod.game.ArmoryMenu, Integer> ARMORY = Registry.register(
			BuiltInRegistries.MENU,
			RubyMod.id("armory"),
			new ExtendedMenuType<>(dev.rubymod.game.ArmoryMenu::new, net.minecraft.network.codec.ByteBufCodecs.VAR_INT.cast()));

	public static void initialize() {
	}
}
