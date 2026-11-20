package dev.rubymod.industry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Conveyor Belt: carries items the way it faces and feeds them into whatever container or machine is at its end.
 * Machines drop their output onto a belt leading away from them.
 *
 * <ul>
 * <li>Curves: a plain belt fed from one side (and not from behind) bends into a curve by itself, and items follow it
 * round. A belt joining the side of a line that already runs on stays straight (a merge).</li>
 * <li>People and mobs get a gentle push while they stand on it: they can walk, jump and step off any time (crouch to
 * stand still). Vehicles aren't carried.</li>
 * <li>Items riding a belt never vanish and can't be picked up by someone walking past; they're the belt's.</li>
 * <li>Redstone (a lever, a button, any signal) stops it; so does a full chest at the end, back up the line to the
 * machine or Belt Loader feeding it, which waits.</li>
 * </ul>
 */
public class ConveyorBlock extends Block {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
	/** Stopped by redstone. */
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

	/** A plain belt's curve: LEFT when the belt feeding it is on its left (things turn left round it). */
	public enum Turn implements StringRepresentable {
		NONE, LEFT, RIGHT;

		@Override
		public String getSerializedName() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	public static final EnumProperty<Turn> TURN = EnumProperty.create("turn", Turn.class);
	private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 4, 16);
	/** Items: blocks per tick (two blocks a second). */
	static final double SPEED = 0.1;
	/** People and mobs: a nudge each tick (about a slow walk if you stand still), on top of their own walking. */
	private static final double PUSH = 0.03;
	/** More items than this crowded on one belt, and the extras are left to vanish like items on the ground. */
	private static final int CROWD = 16;

	public ConveyorBlock(Properties properties) {
		super(properties);
		BlockState state = stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWERED, false);
		if (state.hasProperty(TURN)) {
			state = state.setValue(TURN, Turn.NONE);
		}
		registerDefaultState(state);
	}

	/** Only the plain belt curves (ramps, sorters and splitters keep their shape). */
	private boolean curves() {
		return getClass() == ConveyorBlock.class;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, POWERED);
		if (curves()) {
			builder.add(TURN);
		}
	}

	/**
	 * Belts run away from you as you place them, except that a belt never points straight back into the belt
	 * feeding its spot (a dead end): it carries that line on instead.
	 */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		Direction facing = context.getHorizontalDirection();
		if (feeds(level, pos.relative(facing), facing.getOpposite())) {
			facing = facing.getOpposite();
		}
		BlockState state = defaultBlockState().setValue(FACING, facing).setValue(POWERED, level.hasNeighborSignal(pos));
		return shaped(state, level, pos);
	}

	/** The curve this belt should have, given the belts around it. */
	public static BlockState shaped(BlockState state, BlockGetter level, BlockPos pos) {
		if (!state.hasProperty(TURN)) {
			return state;
		}
		return state.setValue(TURN, turnFor(level, pos, state.getValue(FACING)));
	}

	/** Straight if something feeds it from behind (or from both sides); otherwise it bends toward the side that feeds it. */
	public static Turn turnFor(BlockGetter level, BlockPos pos, Direction facing) {
		if (feeds(level, pos.relative(facing.getOpposite()), facing)) {
			return Turn.NONE;
		}
		boolean left = feeds(level, pos.relative(facing.getCounterClockWise()), facing.getClockWise());
		boolean right = feeds(level, pos.relative(facing.getClockWise()), facing.getCounterClockWise());
		return left == right ? Turn.NONE : left ? Turn.LEFT : Turn.RIGHT;
	}

	/** Does the belt piece (or Belt Loader, or tunnel exit) at {@code from} send things {@code toward} its neighbour that way? */
	public static boolean feeds(BlockGetter level, BlockPos from, Direction toward) {
		BlockState s = level.getBlockState(from);
		if (s.getBlock() instanceof BeltRampBlock) {
			// An up ramp lets things off a level higher; a down ramp at this level.
			return s.getValue(BeltRampBlock.DOWN) && s.getValue(FACING) == toward;
		}
		if (s.getBlock() instanceof BeltSorterBlock) {
			return s.getValue(FACING) == toward || BeltSorterBlock.side(s) == toward;
		}
		if (s.getBlock() instanceof BeltSplitterBlock) {
			return s.getValue(FACING).getClockWise() == toward || s.getValue(FACING).getCounterClockWise() == toward;
		}
		if (s.getBlock() instanceof ConveyorBlock) {
			return s.getValue(FACING) == toward;
		}
		if (s.getBlock() instanceof BeltLoaderBlock) {
			return s.getValue(BeltLoaderBlock.FACING) == toward;
		}
		if (s.getBlock() instanceof BeltTunnelBlock) {
			return s.getValue(BeltTunnelBlock.EXIT) && s.getValue(BeltTunnelBlock.FACING) == toward;
		}
		return false;
	}

	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction directionToNeighbour,
			BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
		return shaped(state, level, pos);
	}

	/** Placed, turned (the wrench), or built by a Constructor: settle its curve and whether it's powered. */
	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		BlockState want = shaped(state, level, pos).setValue(POWERED, level.hasNeighborSignal(pos));
		if (want != state) {
			level.setBlock(pos, want, Block.UPDATE_CLIENTS);
		}
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, Orientation orientation, boolean movedByPiston) {
		boolean powered = level.hasNeighborSignal(pos);
		if (powered != state.getValue(POWERED)) {
			level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
		}
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		BlockState s = state.rotate(mirror.getRotation(state.getValue(FACING)));
		if (mirror != Mirror.NONE && s.hasProperty(TURN) && s.getValue(TURN) != Turn.NONE) {
			s = s.setValue(TURN, s.getValue(TURN) == Turn.LEFT ? Turn.RIGHT : Turn.LEFT);
		}
		return s;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	/** Standing on it: people and mobs get their nudge; items ride. */
	@Override
	public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
		if (entity instanceof ItemEntity item) {
			carryItem(level, pos, state, item);
		} else {
			push(pos, state, entity);
		}
		super.stepOn(level, pos, state, entity);
	}

	/** Only items are carried from inside the belt's space (people mid-jump over it are left alone). */
	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effectApplier, boolean isPrecise) {
		if (entity instanceof ItemEntity item && item.getY() < pos.getY() + 0.6) {
			carryItem(level, pos, state, item);
		}
	}

	/** How fast this belt lifts what's on it (a Belt Ramp carries things up a level). */
	protected double lift(BlockPos pos, Entity entity) {
		return Double.NEGATIVE_INFINITY;
	}

	/** Which way this belt sends this item (a Belt Sorter or Splitter turns some off to the side). */
	protected Direction route(Level level, BlockPos pos, BlockState state, Entity entity) {
		return state.getValue(FACING);
	}

	private static Vec3 unit(Direction d) {
		return Vec3.atLowerCornerOf(d.getUnitVec3i());
	}

	/** People and mobs: added to their own movement, so they can always walk or jump off. */
	private void push(BlockPos pos, BlockState state, Entity entity) {
		if (!(entity instanceof LivingEntity) || entity instanceof dev.rubymod.entity.VehicleEntity || entity instanceof dev.rubymod.entity.AircraftEntity
				|| entity.isShiftKeyDown() || state.getValue(POWERED) || !entity.onGround() || entity.getY() > pos.getY() + 0.5) {
			return;
		}
		Vec3 dir = unit(state.getValue(FACING));
		Vec3 v = entity.getDeltaMovement();
		if (v.dot(dir) < 0.12) {
			entity.setDeltaMovement(v.add(dir.scale(PUSH)));
		}
	}

	private void carryItem(Level level, BlockPos pos, BlockState state, ItemEntity item) {
		Vec3 v = item.getDeltaMovement();
		if (!level.isClientSide()) {
			keep(level, pos, item);
		}
		if (state.getValue(POWERED)) {
			// Stopped: things stay where they are.
			item.setDeltaMovement(0, Math.min(v.y, 0), 0);
			return;
		}
		Direction facing = route(level, pos, state, item);
		Vec3 dir = unit(facing);
		Vec3 move;
		Turn turn = state.hasProperty(TURN) ? state.getValue(TURN) : Turn.NONE;
		if (turn != Turn.NONE) {
			move = curve(pos, facing, turn, item);
		} else {
			// Along the belt at belt speed; across it, drift to the middle so items line up.
			double across = facing.getAxis() == Direction.Axis.Z ? pos.getX() + 0.5 - item.getX() : pos.getZ() + 0.5 - item.getZ();
			double ax = facing.getAxis() == Direction.Axis.Z ? across * 0.2 : 0;
			double az = facing.getAxis() == Direction.Axis.X ? across * 0.2 : 0;
			move = new Vec3(dir.x * SPEED + ax, 0, dir.z * SPEED + az);
		}
		item.setDeltaMovement(move.x, Math.max(v.y, lift(pos, item)), move.z);
		if (level.isClientSide()) {
			return;
		}
		// At the end of the belt: into a container or machine, if there's one.
		Vec3 center = Vec3.atBottomCenterOf(pos);
		double along = item.position().subtract(center).dot(dir);
		if (along < 0.3) {
			return;
		}
		if (Belts.containerAt(level, pos.relative(facing)) != null) {
			// Furnaces get things to cook in the top and fuel underneath, whichever side the belt comes in.
			ItemStack rest = Belts.insert(level, pos.relative(facing), facing.getOpposite(), item.getItem());
			if (rest.isEmpty()) {
				item.discard();
			} else {
				item.setItem(rest);
			}
		}
	}

	/**
	 * Round a curve: a quarter circle about the corner between the side it comes in and the end it leaves by, from
	 * the middle of the entry edge to the middle of the exit edge. Items drift onto the arc if they're off it.
	 */
	private static Vec3 curve(BlockPos pos, Direction facing, Turn turn, ItemEntity item) {
		Vec3 f = unit(facing);
		Vec3 e = unit(turn == Turn.LEFT ? facing.getCounterClockWise() : facing.getClockWise());
		Vec3 corner = Vec3.atBottomCenterOf(pos).add(f.scale(0.5)).add(e.scale(0.5));
		Vec3 rel = new Vec3(item.getX() - corner.x, 0, item.getZ() - corner.z);
		double r = rel.length();
		if (r < 0.05) {
			return f.scale(SPEED);
		}
		// 0 where it comes in, a right angle where it leaves.
		double theta = Math.atan2(-rel.dot(e), -rel.dot(f));
		theta = Math.max(0, Math.min(Math.PI / 2, theta));
		Vec3 along = f.scale(Math.sin(theta)).subtract(e.scale(Math.cos(theta)));
		Vec3 inward = rel.scale(-(r - 0.5) * 0.3 / r);
		return along.scale(SPEED).add(inward);
	}

	/** Items on a belt are the belt's: nobody walking past picks them up, and they don't vanish (unless a belt's swamped). */
	private static void keep(Level level, BlockPos pos, ItemEntity item) {
		item.setPickUpDelay(10);
		if (item.tickCount % 40 == 0 && level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(0.05)).size() <= CROWD) {
			((dev.rubymod.mixin.ItemEntityAccessor) item).rubymod$setAge(0);
		}
	}

	/** Machines put output on a belt next to them if it leads away. */
	public static boolean leadsAway(BlockState belt, Direction fromMachine) {
		return belt.getBlock() instanceof ConveyorBlock && belt.getValue(FACING) != fromMachine.getOpposite();
	}

	/**
	 * Is this belt too busy to put more on (something still sitting where new things land, or it's stopped)? Machines
	 * and Belt Loaders wait, so a full chest at the end backs the line up instead of piling items everywhere.
	 */
	public static boolean busy(Level level, BlockPos belt) {
		BlockState state = level.getBlockState(belt);
		if (state.hasProperty(POWERED) && state.getValue(POWERED)) {
			return true;
		}
		return !level.getEntitiesOfClass(ItemEntity.class, new AABB(belt).inflate(-0.2, 0, -0.2)).isEmpty();
	}

	/** For the look-at panel and tests: the server level's view of a belt's curve. */
	public static Turn turnOf(BlockState state) {
		return state.hasProperty(TURN) ? state.getValue(TURN) : Turn.NONE;
	}
}
