package dev.rubymod.industry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ready-made Blueprints sold at the Armory: put one in a Constructor, fill the chests next to it with the blocks, and
 * it builds a good-looking piece of base with no designing first. Drawn here layer by layer, bottom up; in each layer
 * the first row is the side nearest the Constructor.
 */
public final class ReadyBlueprints {
	public record Design(String id, String title, String blurb, Blueprints.Data data) {
	}

	private static final Map<Character, BlockState> KEY = new HashMap<>();

	static {
		KEY.put('S', Blocks.STONE_BRICKS.defaultBlockState());
		KEY.put('P', Blocks.OAK_PLANKS.defaultBlockState());
		KEY.put('F', Blocks.OAK_FENCE.defaultBlockState());
		KEY.put('L', Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
		KEY.put('C', ModBlocks.CONCRETE_WALL.defaultBlockState());
		KEY.put('R', ModBlocks.REINFORCED_CONCRETE.defaultBlockState());
		KEY.put('G', ModBlocks.REINFORCED_GLASS.defaultBlockState());
	}

	private ReadyBlueprints() {
	}

	/** Layers bottom up; each layer's rows run away from the Constructor; '.' is air. */
	private static Blueprints.Data draw(String[]... layers) {
		int sy = layers.length;
		int sz = layers[0].length;
		int sx = layers[0][0].length();
		List<BlockState> palette = new ArrayList<>();
		Map<BlockState, Integer> index = new HashMap<>();
		List<Integer> cells = new ArrayList<>(sx * sy * sz);
		for (String[] layer : layers) {
			if (layer.length != sz) {
				throw new IllegalStateException("layer depth");
			}
			for (String row : layer) {
				if (row.length() != sx) {
					throw new IllegalStateException("row width: " + row);
				}
				for (char ch : row.toCharArray()) {
					BlockState s = KEY.get(ch);
					cells.add(s == null ? -1 : index.computeIfAbsent(s, k -> {
						palette.add(k);
						return palette.size() - 1;
					}));
				}
			}
		}
		return new Blueprints.Data(sx, sy, sz, palette, cells);
	}

	private static String[] repeat(String[] layer, int times, List<String[]> out) {
		for (int i = 0; i < times; i++) {
			out.add(layer);
		}
		return layer;
	}

	/** A stone watchtower with a ladder up the inside and a railed lookout on top. */
	private static Blueprints.Data watchtower() {
		List<String[]> l = new ArrayList<>();
		String[] door = {"SS.SS", "S...S", "S...S", "S.L.S", "SSSSS"};
		String[] wall = {"SSSSS", "S...S", "S...S", "S.L.S", "SSSSS"};
		String[] slits = {"SSGSS", "S...S", "G...G", "S.L.S", "SSGSS"};
		repeat(door, 2, l);
		repeat(wall, 3, l);
		repeat(slits, 1, l);
		repeat(wall, 2, l);
		l.add(new String[] {"PPPPP", "PPPPP", "PPPPP", "PPLPP", "PPPPP"});
		l.add(new String[] {"FFFFF", "F...F", "F...F", "F...F", "FFFFF"});
		return draw(l.toArray(new String[0][]));
	}

	/** A low concrete bunker: a doorway at the front, glass slits on every side, a Reinforced Concrete roof. */
	private static Blueprints.Data bunker() {
		List<String[]> l = new ArrayList<>();
		l.add(new String[] {"CCC.CCC", "C.....C", "C.....C", "C.....C", "C.....C", "C.....C", "CCCCCCC"});
		l.add(new String[] {"CGC.CGC", "C.....C", "G.....G", "C.....C", "G.....G", "C.....C", "CCGCGCC"});
		l.add(new String[] {"CCCCCCC", "C.....C", "C.....C", "C.....C", "C.....C", "C.....C", "CCCCCCC"});
		l.add(new String[] {"RRRRRRR", "RRRRRRR", "RRRRRRR", "RRRRRRR", "RRRRRRR", "RRRRRRR", "RRRRRRR"});
		return draw(l.toArray(new String[0][]));
	}

	/** Two stone towers with a gateway between them and a walk along the top. */
	private static Blueprints.Data gatehouse() {
		List<String[]> l = new ArrayList<>();
		String[] gate = {"SSS.....SSS", "S.S.....S.S", "SSS.....SSS"};
		String[] over = {"SSSCCCCCSSS", "S.SCCCCCS.S", "SSS.....SSS"};
		repeat(gate, 3, l);
		repeat(over, 2, l);
		l.add(new String[] {"SSSPPPPPSSS", "SPSPPPPPSPS", "SSS.....SSS"});
		l.add(new String[] {"SSSC.C.CSSS", "S.S.....S.S", "SSS.....SSS"});
		l.add(new String[] {"S.S.....S.S", "...........", "S.S.....S.S"});
		return draw(l.toArray(new String[0][]));
	}

	/** A thick concrete wall with glass firing slits and battlements along the top. */
	private static Blueprints.Data wall() {
		List<String[]> l = new ArrayList<>();
		l.add(new String[] {"CCCCCCCCC", "CCCCCCCCC"});
		l.add(new String[] {"CCGCCCGCC", "CCGCCCGCC"});
		l.add(new String[] {"CCCCCCCCC", "CCCCCCCCC"});
		l.add(new String[] {"C.C.C.C.C", "........."});
		return draw(l.toArray(new String[0][]));
	}

	public static final List<Design> ALL = List.of(
			new Design("watchtower", "Watchtower", "A stone tower with a ladder inside and a lookout on top", watchtower()),
			new Design("bunker", "Bunker", "Concrete walls with glass slits and a Reinforced Concrete roof", bunker()),
			new Design("gatehouse", "Gatehouse", "Two towers either side of a gateway, with a walk along the top", gatehouse()),
			new Design("wall_section", "Wall Section", "A thick concrete wall with firing slits and battlements", wall()));

	/** The Blueprint item for a design, named, with what it needs on it. */
	public static ItemStack stack(Design design) {
		ItemStack stack = new ItemStack(ModItems.BLUEPRINT);
		stack.set(ModComponents.BLUEPRINT, design.data());
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Blueprint: " + design.title())
				.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.AQUA)));
		List<Component> lore = new ArrayList<>();
		lore.add(Component.literal(design.blurb()).withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY)));
		lore.add(Component.literal("Put it in a Constructor, and fill the chests next to it with:")
				.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.DARK_GRAY)));
		design.data().bill().entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> lore.add(
				Component.literal("  " + e.getValue() + " " + new ItemStack(e.getKey()).getHoverName().getString())
						.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.DARK_GRAY))));
		stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lore));
		return stack;
	}
}
