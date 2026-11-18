package dev.rubymod.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.math.Transformation;

import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.registry.ModBlocks;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Moving base parts (the Ruby Bearing and the Ruby Piston): the blocks stuck to the front of one lift out of the world
 * as block displays, swing round (or slide along), and go back in as real blocks where they end up. Drawbridges,
 * sliding gates, roofs that open.
 */
public final class Movers {
	private Movers() {
	}

	/** The most blocks one bearing or piston can move. */
	public static final int MAX_BLOCKS = 256;
	private static final int TICKS = 30;

	private record Part(BlockPos from, BlockState state, Display.BlockDisplay show) {
	}

	/** A move under way. */
	private static final class Move {
		ServerLevel level;
		BlockPos machine;
		List<Part> parts = new ArrayList<>();
		/** Rotation: the axis (through the machine's centre) and the angle in degrees; or a slide by this many blocks. */
		Direction.Axis axis;
		float degrees;
		Direction slide;
		int distance;
		Vec3 pivot;
		int age;
		Runnable done;
	}

	private static final List<Move> MOVING = new ArrayList<>();

	public static boolean busy(BlockPos machine) {
		for (Move m : MOVING) {
			if (m.machine.equals(machine)) {
				return true;
			}
		}
		return false;
	}

	/** The blocks stuck to the machine's front (null, with why in problem[0], if it can't move them). */
	public static List<BlockPos> attached(ServerLevel level, BlockPos machine, Direction front, String[] problem) {
		return attached(level, machine, machine.relative(front), problem);
	}

	/**
	 * The blocks joined to start, made of the same kind of material as it (wood, stone, metal, glass...), so a wooden
	 * drawbridge never takes the stone floor it rests on with it. (Not through the machine itself.)
	 */
	public static List<BlockPos> attached(ServerLevel level, BlockPos machine, BlockPos start, String[] problem) {
		BlockState first = level.getBlockState(start);
		net.minecraft.world.level.block.SoundType material = first.getSoundType();
		if (!movable(level, start, first)) {
			problem[0] = "Nothing stuck to its front to move.";
			return null;
		}
		List<BlockPos> out = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		seen.add(start);
		seen.add(machine);
		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			out.add(p);
			if (out.size() > MAX_BLOCKS) {
				problem[0] = "That's too much to move (" + MAX_BLOCKS + " blocks at most): it takes everything of the same material joined to its front.";
				return null;
			}
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				BlockState ns = level.getBlockState(n);
				if (seen.add(n) && ns.getSoundType() == material && movable(level, n, ns)) {
					queue.add(n);
				}
			}
		}
		return out;
	}

	/** Part of what moves: a built block (not the ground, a Heart, another machine, or anything with contents). */
	private static boolean movable(ServerLevel level, BlockPos p, BlockState s) {
		return !s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced() && !s.hasBlockEntity() && !Collapse.natural(s)
				&& !s.is(ModBlocks.RUBY_HEART) && !s.is(ModBlocks.RUBY_BEARING) && !s.is(ModBlocks.RUBY_PISTON) && s.getDestroySpeed(level, p) >= 0;
	}

	/** Where a block ends up. */
	private static BlockPos target(Move m, BlockPos p) {
		if (m.slide != null) {
			return p.relative(m.slide, m.distance);
		}
		Vec3 c = Vec3.atCenterOf(p).subtract(m.pivot);
		Vec3 r = rotate(c, m.axis, m.degrees);
		return BlockPos.containing(m.pivot.add(r));
	}

	private static Vec3 rotate(Vec3 v, Direction.Axis axis, float degrees) {
		double a = Math.toRadians(degrees);
		double cos = Math.cos(a);
		double sin = Math.sin(a);
		return switch (axis) {
			case X -> new Vec3(v.x, v.y * cos - v.z * sin, v.y * sin + v.z * cos);
			case Y -> new Vec3(v.x * cos + v.z * sin, v.y, -v.x * sin + v.z * cos);
			case Z -> new Vec3(v.x * cos - v.y * sin, v.x * sin + v.y * cos, v.z);
		};
	}

	private static Quaternionf turn(Direction.Axis axis, float degrees) {
		float a = (float) Math.toRadians(degrees);
		return switch (axis) {
			case X -> new Quaternionf().rotateX(a);
			case Y -> new Quaternionf().rotateY(a);
			case Z -> new Quaternionf().rotateZ(a);
		};
	}

	/** Can it go: are all the places it ends up empty (or part of what's moving), and inside the machine's own claim? */
	private static String check(ServerLevel level, Move m, List<BlockPos> blocks) {
		Set<BlockPos> moving = new HashSet<>(blocks);
		String dim = Rules.dim(level);
		SeasonData.Claim home = SeasonData.get().claimAt(dim, m.machine.getX(), m.machine.getZ());
		for (BlockPos p : blocks) {
			BlockPos to = target(m, p);
			for (BlockPos q : new BlockPos[] {p, to}) {
				if (SeasonData.get().claimAt(dim, q.getX(), q.getZ()) != home) {
					return "It can't move blocks into or out of a claim that isn't its own.";
				}
			}
			if (!moving.contains(to)) {
				BlockState there = level.getBlockState(to);
				if (!there.isAir() && !there.canBeReplaced()) {
					return "Something's in the way (at " + to.getX() + ", " + to.getY() + ", " + to.getZ() + ").";
				}
			}
			if (to.getY() < level.getMinY() || to.getY() > level.getMaxY()) {
				return "That would go out of the world.";
			}
		}
		return null;
	}

	/** Swing the blocks on the machine's front round its axis by this many degrees. Null if started, or why not. */
	public static String rotate(ServerLevel level, BlockPos machine, Direction front, float degrees, Runnable done) {
		Move m = new Move();
		m.level = level;
		m.machine = machine;
		m.axis = front.getAxis();
		m.degrees = degrees;
		m.pivot = Vec3.atCenterOf(machine);
		m.done = done;
		return start(level, m, front);
	}

	/** Slide the blocks on the machine's front along by this many blocks (negative: back). */
	public static String slide(ServerLevel level, BlockPos machine, Direction front, Direction way, int distance, Runnable done) {
		Move m = new Move();
		m.level = level;
		m.machine = machine;
		m.slide = way;
		m.distance = distance;
		m.pivot = Vec3.atCenterOf(machine);
		m.done = done;
		return start(level, m, front);
	}

	private static String start(ServerLevel level, Move m, Direction front) {
		if (busy(m.machine)) {
			return "It's still moving.";
		}
		String[] problem = new String[1];
		// (A pushed-out piston's blocks are out at the end of its push.)
		BlockPos first = m.slide != null && m.slide != front ? m.machine.relative(front, m.distance + 1) : m.machine.relative(front);
		List<BlockPos> blocks = attached(level, m.machine, first, problem);
		if (blocks == null) {
			return problem[0];
		}
		String why = check(level, m, blocks);
		if (why != null) {
			return why;
		}
		// Out of the world and into displays (all removed first, so the light's right for every one of them).
		Map<BlockPos, BlockState> states = new HashMap<>();
		Map<BlockPos, Brightness> lights = new HashMap<>();
		for (BlockPos p : blocks) {
			states.put(p, level.getBlockState(p));
			// Lit like the open air round it (its own spot is dark while it's a block).
			int sky = 0;
			int block = 0;
			for (Direction d : Direction.values()) {
				sky = Math.max(sky, level.getBrightness(LightLayer.SKY, p.relative(d)));
				block = Math.max(block, level.getBrightness(LightLayer.BLOCK, p.relative(d)));
			}
			lights.put(p, new Brightness(block, sky));
		}
		for (BlockPos p : blocks) {
			level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
		}
		for (BlockPos p : blocks) {
			level.updateNeighborsAt(p, Blocks.AIR, null);
		}
		for (BlockPos p : blocks) {
			Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (d == null) {
				continue;
			}
			Vec3 at = m.slide != null ? Vec3.atLowerCornerOf(p) : m.pivot;
			d.snapTo(at.x, at.y, at.z, 0, 0);
			((BlockDisplayAccessor) d).rubymod$setBlockState(states.get(p));
			((DisplayAccessor) d).rubymod$setTransformationInterpolationDuration(2);
			((DisplayAccessor) d).rubymod$setBrightness(lights.get(p));
			Part part = new Part(p, states.get(p), d);
			pose(m, part, 0);
			level.addFreshEntity(d);
			m.parts.add(part);
		}
		level.playSound(null, m.machine, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 1.0F, 0.6F);
		MOVING.add(m);
		return null;
	}

	/** Where a part's display shows it, t of the way there (0..1). */
	private static void pose(Move m, Part part, float t) {
		Transformation tf;
		if (m.slide != null) {
			Vector3f off = new Vector3f(m.slide.getStepX(), m.slide.getStepY(), m.slide.getStepZ()).mul(m.distance * t);
			tf = new Transformation(off, new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf());
		} else {
			float deg = m.degrees * t;
			Quaternionf q = turn(m.axis, deg);
			// The block's corner, relative to the pivot, turned round it.
			Vec3 corner = Vec3.atLowerCornerOf(part.from()).subtract(m.pivot);
			Vector3f c = q.transform(new Vector3f((float) corner.x, (float) corner.y, (float) corner.z));
			tf = new Transformation(c, q, new Vector3f(1, 1, 1), new Quaternionf());
		}
		((DisplayAccessor) part.show()).rubymod$setTransformation(tf);
		((DisplayAccessor) part.show()).rubymod$setTransformationInterpolationDelay(0);
	}

	/** Every tick: the moves go on, and finished ones go back into the world. */
	public static void tick() {
		for (Iterator<Move> it = MOVING.iterator(); it.hasNext();) {
			Move m = it.next();
			m.age++;
			if (m.age % 2 == 0 && m.age < TICKS) {
				float t = m.age / (float) TICKS;
				float eased = t * t * (3 - 2 * t);
				for (Part p : m.parts) {
					pose(m, p, eased);
				}
			}
			if (m.age >= TICKS + 2) {
				finish(m);
				it.remove();
			}
		}
	}

	/** Back into the world where they ended up (turned to match), and the displays go. */
	private static void finish(Move m) {
		for (Part p : m.parts) {
			pose(m, p, 1);
		}
		for (Part p : m.parts) {
			BlockPos to = target(m, p.from());
			BlockState s = m.slide != null ? p.state() : turned(p.state(), m.axis, m.degrees);
			BlockState there = m.level.getBlockState(to);
			if (there.isAir() || there.canBeReplaced()) {
				m.level.setBlock(to, s, 3);
			}
			p.show().discard();
		}
		m.level.playSound(null, m.machine, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 1.0F, 0.7F);
		if (m.done != null) {
			m.done.run();
		}
	}

	/** A block turned with the rest: round the vertical, it turns properly; logs and pillars lie the right way. */
	private static BlockState turned(BlockState s, Direction.Axis axis, float degrees) {
		int quarters = Math.floorMod(Math.round(degrees / 90), 4);
		if (axis == Direction.Axis.Y) {
			net.minecraft.world.level.block.Rotation r = switch (quarters) {
				case 1 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
				case 2 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
				case 3 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
				default -> net.minecraft.world.level.block.Rotation.NONE;
			};
			return s.rotate(r);
		}
		if (quarters % 2 == 1 && s.hasProperty(RotatedPillarBlock.AXIS)) {
			Direction.Axis a = s.getValue(RotatedPillarBlock.AXIS);
			if (a != axis) {
				Direction.Axis other = Direction.Axis.Y == a ? (axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X) : Direction.Axis.Y;
				return s.setValue(RotatedPillarBlock.AXIS, other);
			}
		}
		return s;
	}

	/** The server's stopping: everything moving goes straight to where it was going. */
	public static void finishAll() {
		for (Move m : MOVING) {
			finish(m);
		}
		MOVING.clear();
	}
}
