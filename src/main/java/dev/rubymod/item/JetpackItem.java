package dev.rubymod.item;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/** Worn in the chest slot instead of a chestplate: hold Space in the air to fly (see the client's Jetpack). */
public class JetpackItem extends Item {
	public JetpackItem(Properties properties) {
		super(properties);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.literal("Wear it in your chest slot (instead of a chestplate)").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("Jump, then hold Space to fly; WASD steers").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("Fuel refills on the ground. No fall damage while the jets fire.").withStyle(ChatFormatting.DARK_GRAY));
	}
}
