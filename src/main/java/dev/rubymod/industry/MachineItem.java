package dev.rubymod.industry;

import java.util.function.Consumer;

import dev.rubymod.registry.ModComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/** A machine's item: shows what it does and its tier (kept when the machine is picked up). */
public class MachineItem extends BlockItem {
	public MachineItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		if (!(getBlock() instanceof MachineBlock block)) {
			return;
		}
		MachineKind kind = block.kind();
		int tier = stack.getOrDefault(ModComponents.MACHINE_TIER, 1);
		builder.accept(Component.literal(MachineKind.TIER_NAMES[tier - 1]).withStyle(ChatFormatting.LIGHT_PURPLE));
		builder.accept(Component.literal(kind.description).withStyle(ChatFormatting.GRAY));
		if (kind.output(tier) > 0) {
			builder.accept(Component.literal("Makes " + kind.output(tier) * 20 + " charge/s").withStyle(ChatFormatting.DARK_GRAY));
		}
		if (kind.draw(tier) > 0) {
			builder.accept(Component.literal("Uses " + kind.draw(tier) * 20 + " charge/s while working").withStyle(ChatFormatting.DARK_GRAY));
		}
		if (kind.batteryCapacity(tier) > 0) {
			builder.accept(Component.literal("Stores " + kind.batteryCapacity(tier) + " charge").withStyle(ChatFormatting.DARK_GRAY));
		}
		builder.accept(Component.literal("Right-click to open; upgrade with rubies").withStyle(ChatFormatting.DARK_GRAY));
	}
}
