package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Tower's water-park dressing: a sandy deck, a striped tower, umbrellas and loungers, a lifeguard's chair, palm
 * trees, and Lulu the lifeguard (who'll take you up to the top). The tower, slide, pool and zipline are built in HubPark.
 */
final class WaterPark {
	private WaterPark() {
	}

	static final int CX = 44;
	static final int CZ = -44;

	/** Before the tower goes up: sand instead of grass, and the old tall slide cleared away. */
	static void ground(ServerLevel level) {
		HubPark.clear(level, CX - 11, FLOOR + 13, CZ - 11, CX + 11, FLOOR + 22, CZ + 11);
		for (int x = -11; x <= 11; x++) {
			for (int z = -11; z <= 11; z++) {
				BlockPos p = new BlockPos(CX + x, FLOOR, CZ + z);
				if (level.getBlockState(p).is(Blocks.GRASS_BLOCK)) {
					level.setBlock(p, Blocks.SAND.defaultBlockState(), 2);
				}
			}
		}
	}

	/** After: stripes on the tower, things round the pool, and the lifeguard. */
	static void dress(ServerLevel level) {
		for (int y = FLOOR + 1; y <= FLOOR + 15; y++) {
			for (int x = CX - 2; x <= CX + 2; x++) {
				for (int z = CZ - 2; z <= CZ + 2; z++) {
					boolean wall = x == CX - 2 || x == CX + 2 || z == CZ - 2 || z == CZ + 2;
					if (wall && !level.getBlockState(new BlockPos(x, y, z)).isAir() && !level.getBlockState(new BlockPos(x, y, z)).is(Blocks.LADDER)) {
						HubPark.set(level, x, y, z, ((y - FLOOR) / 2) % 2 == 0 ? Blocks.DYED_TERRACOTTA.pick(DyeColor.WHITE).defaultBlockState()
								: Blocks.DYED_TERRACOTTA.pick(DyeColor.LIGHT_BLUE).defaultBlockState());
					}
				}
			}
		}
		// The pool deck.
		for (int x = CX - 6; x <= CX + 5; x++) {
			for (int z = CZ - 11; z <= CZ - 3; z++) {
				boolean rim = x == CX - 6 || x == CX + 5 || z == CZ - 11 || z == CZ - 3;
				if (rim && level.getBlockState(new BlockPos(x, FLOOR, z)).is(Blocks.SAND)) {
					HubPark.set(level, x, FLOOR, z, Blocks.SMOOTH_QUARTZ.defaultBlockState());
				}
			}
		}
		umbrella(level, CX - 8, CZ + 4, DyeColor.RED);
		umbrella(level, CX + 7, CZ + 3, DyeColor.ORANGE);
		umbrella(level, CX + 7, CZ - 6, DyeColor.LIME);
		lounger(level, CX - 9, CZ + 6);
		lounger(level, CX - 7, CZ + 6);
		lounger(level, CX + 6, CZ + 5);
		palm(level, CX - 8, CZ - 4);
		palm(level, CX + 8, CZ + 7);
		// The lifeguard's chair by the pool.
		int lx = CX - 8;
		int lz = CZ - 8;
		for (int[] leg : new int[][] {{0, 0}, {1, 0}, {0, 1}, {1, 1}}) {
			HubPark.box(level, lx + leg[0], FLOOR + 1, lz + leg[1], lx + leg[0], FLOOR + 2, lz + leg[1], Blocks.SPRUCE_FENCE.defaultBlockState());
		}
		HubPark.box(level, lx, FLOOR + 3, lz, lx + 1, FLOOR + 3, lz + 1, Blocks.SPRUCE_SLAB.defaultBlockState());
		HubPark.set(level, lx, FLOOR + 4, lz, Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState());
		HubPark.label(level, CX + 0.5, FLOOR + 21, CZ + 0.5, "SPLASH TOWER", ChatFormatting.AQUA, 2.6F);
		HubHosts.add(level, new Vec3(CX + 1.5, FLOOR + 1, CZ + 4.5), 0, VillagerProfession.NITWIT, VillagerType.DESERT,
				new HubHosts.Host("tower", "Lulu", "Splash Tower", 0x40C8FF, "minecraft:water_bucket",
						"Up at the top: step on the BLUE pad to whoosh down the tube slide, the GOLD pad to zip line back to the plaza, "
								+ "or jump off the diving board into the pool!",
						"Splash Tower: right-click Lulu to go up!", HubHosts.play("Take me to the top!"), (p, key) -> {
							p.teleportTo(CX + 0.5, FLOOR + 17, CZ + 1.5);
							p.resetFallDistance();
							p.sendOverlayMessage(Component.literal("Blue pad: slide.  Gold pad: zip line.  Or the diving board!").withStyle(ChatFormatting.AQUA));
						}));
	}

	private static void umbrella(ServerLevel level, int x, int z, DyeColor color) {
		HubPark.box(level, x, FLOOR + 1, z, x, FLOOR + 3, z, Blocks.BIRCH_FENCE.defaultBlockState());
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				HubPark.set(level, x + dx, FLOOR + 4, z + dz, Blocks.WOOL.pick((dx + dz) % 2 == 0 ? color : DyeColor.WHITE).defaultBlockState());
			}
		}
		HubPark.set(level, x, FLOOR + 5, z, Blocks.WOOL.pick(color).defaultBlockState());
	}

	private static void lounger(ServerLevel level, int x, int z) {
		HubPark.set(level, x, FLOOR + 1, z, Blocks.SMOOTH_QUARTZ_SLAB.defaultBlockState());
		HubPark.set(level, x, FLOOR + 1, z - 1, Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, net.minecraft.core.Direction.NORTH));
	}

	private static void palm(ServerLevel level, int x, int z) {
		BlockState trunk = Blocks.JUNGLE_LOG.defaultBlockState();
		for (int y = 1; y <= 6; y++) {
			HubPark.set(level, x + (y > 4 ? 1 : 0), FLOOR + y, z, trunk);
		}
		BlockState leaves = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
		int tx = x + 1;
		int ty = FLOOR + 7;
		HubPark.set(level, tx, ty, z, leaves);
		for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
			for (int i = 1; i <= 2; i++) {
				HubPark.set(level, tx + d[0] * i, ty - (i == 2 ? 1 : 0), z + d[1] * i, leaves);
			}
		}
	}
}
