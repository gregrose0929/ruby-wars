package dev.rubymod.item;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import dev.rubymod.game.Rules;
import dev.rubymod.registry.ModComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Architect's Wand: right-click to set a first corner, right-click again to
 * build a line, wall, floor, solid box or hollow room between the corners out of
 * the block in your off hand (taken from your inventory). A live outline shows
 * what you'll get. Sneak + right-click switches shape, R undoes the last build
 * (the blocks come back).
 */
public class ArchitectWandItem extends Item {
	private static final int UNDO_DEPTH = 8;

	private record Placed(BlockPos pos, Item item) {
	}

	private static final Map<UUID, Deque<List<Placed>>> HISTORY = new HashMap<>();

	public ArchitectWandItem(Properties properties) {
		super(properties);
	}

	public static BuildShapes.Mode mode(ItemStack stack) {
		BuildShapes.Mode[] modes = BuildShapes.Mode.values();
		return modes[Math.floorMod(stack.getOrDefault(ModComponents.WAND_MODE, 1), modes.length)];
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (player.isShiftKeyDown()) {
			int next = Math.floorMod(stack.getOrDefault(ModComponents.WAND_MODE, 1) + 1, BuildShapes.Mode.values().length);
			stack.set(ModComponents.WAND_MODE, next);
			stack.remove(ModComponents.CORNER);
			if (player instanceof ServerPlayer sp) {
				sp.sendOverlayMessage(Component.literal("Architect's Wand: " + mode(stack).title).withStyle(ChatFormatting.AQUA));
			}
			level.playSound(null, player.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.5F, 1.6F);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		ItemStack stack = context.getItemInHand();
		if (player == null) {
			return InteractionResult.PASS;
		}
		if (player.isShiftKeyDown()) {
			return use(context.getLevel(), player, context.getHand());
		}
		BlockPos pos = target(context);
		BlockPos corner = stack.get(ModComponents.CORNER);
		if (corner == null) {
			stack.set(ModComponents.CORNER, pos);
			if (player instanceof ServerPlayer sp) {
				sp.sendOverlayMessage(Component.literal(mode(stack).title + ": first corner set. Right-click the second corner.").withStyle(ChatFormatting.AQUA));
			}
			return InteractionResult.SUCCESS;
		}
		stack.remove(ModComponents.CORNER);
		if (context.getLevel() instanceof ServerLevel level && player instanceof ServerPlayer sp) {
			build(level, sp, mode(stack), corner, pos);
		}
		return InteractionResult.SUCCESS;
	}

	/** The block position a click points at: the clicked block if it's replaceable, else the one in front of the face. */
	public static BlockPos target(UseOnContext context) {
		BlockPlaceContext place = new BlockPlaceContext(context);
		return place.getClickedPos();
	}

	private static void build(ServerLevel level, ServerPlayer player, BuildShapes.Mode mode, BlockPos a, BlockPos b) {
		ItemStack material = player.getOffhandItem();
		if (!(material.getItem() instanceof BlockItem blockItem)) {
			player.sendOverlayMessage(Component.literal("Hold the block to build with in your off hand.").withStyle(ChatFormatting.RED));
			return;
		}
		BlockState state = blockItem.getBlock().defaultBlockState();
		if (state.hasBlockEntity()) {
			player.sendOverlayMessage(Component.literal("The wand can't build with that block.").withStyle(ChatFormatting.RED));
			return;
		}
		List<Placed> placed = new ArrayList<>();
		int blocked = 0;
		for (BlockPos p : BuildShapes.shape(mode, a, b)) {
			if (!level.getBlockState(p).canBeReplaced() || !Rules.canBuild(player, level, p) || !level.getWorldBorder().isWithinBounds(p)) {
				blocked++;
				continue;
			}
			if (!player.isCreative() && !take(player.getInventory(), blockItem)) {
				break;
			}
			level.setBlock(p, state, 2);
			placed.add(new Placed(p.immutable(), blockItem));
		}
		if (placed.isEmpty()) {
			player.sendOverlayMessage(Component.literal("Nothing built: out of " + blockItem.getName(material).getString() + " or no room.")
					.withStyle(ChatFormatting.RED));
			return;
		}
		Deque<List<Placed>> history = HISTORY.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
		history.push(placed);
		while (history.size() > UNDO_DEPTH) {
			history.removeLast();
		}
		level.playSound(null, b, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.9F);
		level.playSound(null, b, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 1.2F);
		player.sendOverlayMessage(Component.literal(mode.title + ": placed " + placed.size() + (blocked > 0 ? " (" + blocked + " spots blocked)" : "")
				+ ". R to undo.").withStyle(ChatFormatting.AQUA));
	}

	/** R while holding the wand: take back the last build. */
	public static void undo(ServerPlayer player) {
		Deque<List<Placed>> history = HISTORY.get(player.getUUID());
		if (history == null || history.isEmpty()) {
			player.sendOverlayMessage(Component.literal("Nothing to undo."));
			return;
		}
		ServerLevel level = (ServerLevel) player.level();
		int restored = 0;
		for (Placed p : history.pop()) {
			BlockState now = level.getBlockState(p.pos());
			if (now.getBlock().asItem() == p.item() && Rules.canBuild(player, level, p.pos())) {
				level.setBlock(p.pos(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
				if (!player.isCreative()) {
					giveOrDrop(player, new ItemStack(p.item()));
				}
				restored++;
			}
		}
		level.playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8F, 0.8F);
		player.sendOverlayMessage(Component.literal("Undid " + restored + " blocks.").withStyle(ChatFormatting.AQUA));
	}

	public static void giveOrDrop(Player player, ItemStack stack) {
		if (!player.getInventory().add(stack) && player instanceof ServerPlayer sp) {
			sp.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
		}
	}

	private static boolean take(Inventory inventory, Item item) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack s = inventory.getItem(i);
			if (s.is(item)) {
				s.shrink(1);
				return true;
			}
		}
		return false;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.literal("Shape: " + mode(stack).title).withStyle(ChatFormatting.AQUA));
		builder.accept(Component.literal("Right-click two corners to build with your off-hand block").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("Sneak + right-click: change shape   R: undo").withStyle(ChatFormatting.GRAY));
	}
}
