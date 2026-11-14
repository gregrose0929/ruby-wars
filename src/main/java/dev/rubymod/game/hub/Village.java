package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.UUID;

import dev.rubymod.game.Props;
import dev.rubymod.mixin.TextDisplayAccessor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Pond, as a little fishing village: a longer dock with lanterns, a boathouse, Captain Finn who hands out rods,
 * a board of the latest catches, and a picnic spot by the swings.
 */
final class Village {
	private Village() {
	}

	private static UUID board;

	static void build(ServerLevel level) {
		// The old benches by the travel pad go (the boathouse is there now).
		HubPark.box(level, 9, FLOOR + 1, 50, 9, FLOOR + 1, 52, Blocks.AIR.defaultBlockState());
		// The dock: out into the pond, then along, with lantern posts.
		BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState();
		HubPark.box(level, -1, FLOOR, 46, 0, FLOOR, 52, plank);
		HubPark.box(level, -5, FLOOR, 51, 0, FLOOR, 52, plank);
		for (int[] post : new int[][] {{-1, 46}, {-1, 52}, {-5, 52}, {0, 49}}) {
			HubPark.set(level, post[0], FLOOR + 1, post[1], Blocks.SPRUCE_FENCE.defaultBlockState());
			HubPark.set(level, post[0], FLOOR + 2, post[1], Blocks.LANTERN.defaultBlockState());
		}
		// The boathouse.
		BlockState wall = Blocks.SPRUCE_PLANKS.defaultBlockState();
		BlockState log = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
		for (int x = 7; x <= 11; x++) {
			for (int z = 52; z <= 56; z++) {
				boolean edge = x == 7 || x == 11 || z == 52 || z == 56;
				boolean corner = (x == 7 || x == 11) && (z == 52 || z == 56);
				for (int y = FLOOR + 1; y <= FLOOR + 3; y++) {
					boolean door = x == 7 && z == 54 && y <= FLOOR + 2;
					boolean window = edge && !corner && y == FLOOR + 2 && (z == 54 && x == 11 || x == 9 && (z == 52 || z == 56));
					HubPark.set(level, x, y, z, !edge || door ? Blocks.AIR.defaultBlockState() : window ? Blocks.GLASS_PANE.defaultBlockState() : corner ? log : wall);
				}
			}
		}
		for (int i = 0; i <= 3; i++) {
			for (int z = 51; z <= 57; z++) {
				HubPark.set(level, 6 + i, FLOOR + 4 + i, z, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
				HubPark.set(level, 12 - i, FLOOR + 4 + i, z, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
			}
		}
		HubPark.box(level, 9, FLOOR + 7, 51, 9, FLOOR + 7, 57, Blocks.DARK_OAK_SLAB.defaultBlockState());
		HubPark.set(level, 8, FLOOR + 1, 55, Blocks.BARREL.defaultBlockState());
		HubPark.set(level, 10, FLOOR + 1, 55, Blocks.BARREL.defaultBlockState());
		HubPark.set(level, 6, FLOOR + 1, 53, Blocks.BARREL.defaultBlockState());
		HubPark.label(level, 9.5, FLOOR + 8.6, 54.5, "BOATHOUSE", ChatFormatting.GOLD, 1.0F);
		// The catch board.
		HubPark.box(level, 3, FLOOR + 1, 43, 6, FLOOR + 3, 43, Blocks.DARK_OAK_PLANKS.defaultBlockState());
		HubPark.box(level, 3, FLOOR + 4, 43, 6, FLOOR + 4, 43, Blocks.DARK_OAK_SLAB.defaultBlockState());
		Display.TextDisplay t = Props.text(level, new Vec3(5.0, FLOOR + 1.3, 44.0), Component.literal("BIG CATCH BOARD"), 0.6F, HubPark.TAG);
		HubPark.keep(t);
		board = t == null ? null : t.getUUID();
		catchBoard(level);
		// A picnic by the swings.
		for (int x = 1; x <= 3; x++) {
			for (int z = 58; z <= 60; z++) {
				HubPark.set(level, x, FLOOR + 1, z, Blocks.CARPET.pick((x + z) % 2 == 0 ? DyeColor.RED : DyeColor.WHITE).defaultBlockState());
			}
		}
		HubPark.set(level, 4, FLOOR + 1, 59, Blocks.BARREL.defaultBlockState());
		HubHosts.add(level, new Vec3(-3.5, FLOOR + 1, 44.5), 180, VillagerProfession.FISHERMAN, VillagerType.SWAMP,
				new HubHosts.Host("fishing", "Captain Finn", "Fishing", 0x40C8FF, "minecraft:fishing_rod",
						"Grab a rod and cast into the pond (right-click), then right-click again when the bobber dips! You never know what you'll catch.",
						"Fishing: right-click Captain Finn for a rod!", HubHosts.play("Give me a rod!"), (p, key) -> {
							HubGames.fishingRods(p);
							HubGames.hold(p, "Pond Rod");
						}));
	}

	/** Write the latest catches on the board. */
	static void catchBoard(ServerLevel level) {
		if (board == null || !(level.getEntity(board) instanceof Display.TextDisplay t)) {
			return;
		}
		MutableComponent text = Component.literal("BIG CATCH BOARD").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		if (HubGames.CATCH_LOG.isEmpty()) {
			text.append(Component.literal("\nNothing caught yet.\nTalk to Captain Finn!").withStyle(ChatFormatting.WHITE));
		}
		for (String c : HubGames.CATCH_LOG) {
			text.append(Component.literal("\n" + c).withStyle(ChatFormatting.WHITE));
		}
		((TextDisplayAccessor) t).rubymod$setText(text);
	}
}
