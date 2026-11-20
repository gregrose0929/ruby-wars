package dev.rubymod.industry;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.Vec3;

/**
 * Belt Splitter: a belt piece that shares what comes in between its left and its right. Things on a belt bunch up
 * into piles, so a pile reaching the middle is split in two, half each way; a single thing goes to whichever side is
 * next, turn about. If only one side has a belt (or a chest, or a machine) to take things, everything goes that way;
 * if neither does, things carry straight on.
 */
public class BeltSplitterBlock extends ConveyorBlock {
	/** Which side the next single thing goes. */
	public static final BooleanProperty NEXT_RIGHT = BooleanProperty.create("right");
	/** Where each item reaching the middle was sent (on the server), so it isn't sent twice. */
	private static final Map<ItemEntity, Direction> SENT = new WeakHashMap<>();

	public BeltSplitterBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(NEXT_RIGHT, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(NEXT_RIGHT);
	}

	/** Somewhere for things to go off that side: a belt not pointing back in, or a container. */
	private static boolean outlet(Level level, BlockPos pos, Direction side) {
		BlockPos at = pos.relative(side);
		BlockState s = level.getBlockState(at);
		if (s.getBlock() instanceof ConveyorBlock) {
			return s.getValue(FACING) != side.getOpposite();
		}
		return Belts.containerAt(level, at) != null;
	}

	private static Vec3 unit(Direction d) {
		return Vec3.atLowerCornerOf(d.getUnitVec3i());
	}

	@Override
	protected Direction route(Level level, BlockPos pos, BlockState state, Entity entity) {
		Direction facing = state.getValue(FACING);
		Direction left = facing.getCounterClockWise();
		Direction right = facing.getClockWise();
		boolean l = outlet(level, pos, left);
		boolean r = outlet(level, pos, right);
		if (!l || !r || !(entity instanceof ItemEntity item)) {
			return l ? left : r ? right : facing;
		}
		// Already on its way to one side (the server and every screen can see which, from where it is).
		Vec3 rel = item.position().subtract(Vec3.atBottomCenterOf(pos));
		double lateral = rel.dot(unit(left));
		if (lateral > 0.08) {
			return left;
		}
		if (lateral < -0.08) {
			return right;
		}
		if (rel.dot(unit(facing)) < -0.05) {
			return facing; // not at the middle yet
		}
		if (level.isClientSide()) {
			return state.getValue(NEXT_RIGHT) ? right : left;
		}
		Direction sent = SENT.get(item);
		if (sent != null) {
			return sent;
		}
		// At the middle: a pile splits in two, half each way; a single thing goes to the side that's next.
		boolean goRight = state.getValue(NEXT_RIGHT);
		Direction way = goRight ? right : left;
		Direction other = goRight ? left : right;
		ItemStack stack = item.getItem();
		if (stack.getCount() > 1) {
			ItemStack half = stack.split(stack.getCount() / 2);
			item.setItem(stack);
			Vec3 o = unit(other).scale(0.12);
			ItemEntity twin = new ItemEntity(level, item.getX() + o.x, item.getY(), item.getZ() + o.z, half);
			twin.setDeltaMovement(Vec3.ZERO);
			twin.setPickUpDelay(10);
			// Neither half joins back up with the other before they're apart (the belt lets them merge again later).
			((dev.rubymod.mixin.ItemEntityAccessor) twin).rubymod$setAge(-32768);
			((dev.rubymod.mixin.ItemEntityAccessor) item).rubymod$setAge(-32768);
			level.addFreshEntity(twin);
			SENT.put(twin, other);
		}
		SENT.put(item, way);
		level.setBlock(pos, state.setValue(NEXT_RIGHT, !goRight), Block.UPDATE_CLIENTS);
		return way;
	}
}
