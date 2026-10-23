package dev.rubymod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The shimmering curtain in a hub portal (one colour per game). Walk-through; the hub
 * notices you stepping in and sends you to that game.
 */
public class ModePortalBlock extends Block {
	private static final VoxelShape SHAPE = Block.box(0, 0, 6, 16, 16, 10);
	private final int color;

	public ModePortalBlock(int color, Properties properties) {
		super(properties);
		this.color = color;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected boolean skipRendering(BlockState state, BlockState neighbor, Direction direction) {
		return neighbor.is(this) || super.skipRendering(state, neighbor, direction);
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state) {
		return true;
	}

	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (random.nextInt(3) == 0) {
			level.addParticle(new DustParticleOptions(color, 1.2F), pos.getX() + random.nextDouble(), pos.getY() + random.nextDouble(),
					pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.6, 0, 0.02, 0);
		}
	}
}
