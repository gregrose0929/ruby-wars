package dev.rubymod.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.mojang.math.Transformation;

import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Buildings that come down: after a Breaching Charge, cruise missile or mortar shell blows a hole, anything left
 * hanging (no path through other blocks down to the ground) falls, and big blasts throw chunks that land and break.
 * Ground is natural rock and earth (and anything a weapon isn't allowed to break, so a protected claim stays put).
 */
public final class Collapse {
	private Collapse() {
	}

	/** How far round a blast it looks, and the most blocks it'll follow before deciding "that's held up by plenty". */
	private static final int REACH = 18;
	private static final int MAX_STRUCTURE = 1500;

	/** What the blast is about to destroy (some of it comes back as flying chunks). Call before the explosion. */
	public static List<BlockState> sample(ServerLevel level, Vec3 at, float power) {
		List<BlockState> out = new ArrayList<>();
		BlockPos c = BlockPos.containing(at);
		int r = (int) Math.ceil(power * 0.8);
		RandomSource random = level.getRandom();
		for (int i = 0; i < 40 && out.size() < 12; i++) {
			BlockPos p = c.offset(random.nextInt(2 * r + 1) - r, random.nextInt(2 * r + 1) - r, random.nextInt(2 * r + 1) - r);
			BlockState s = level.getBlockState(p);
			if (!s.isAir() && s.getFluidState().isEmpty() && Rules.canWeaponBreak(level, p, s) && !natural(s)) {
				out.add(s);
			}
		}
		return out;
	}

	/** After the blast: chunks fly, and whatever's left hanging comes down. Returns how many blocks fell. */
	public static int after(ServerLevel level, Vec3 at, float power, List<BlockState> blasted) {
		if (power >= 3.0F) {
			throwChunks(level, at, power, blasted);
		}
		return dropHanging(level, BlockPos.containing(at), (int) Math.ceil(power * 1.5) + 2);
	}

	/** Natural ground: rock, earth, sand, snow and ice (a building made of these just counts as ground). */
	static boolean natural(BlockState s) {
		return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.BEDROCK)
				|| s.is(Blocks.GRAVEL) || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.PACKED_ICE)
				|| s.is(Blocks.ICE) || s.is(Blocks.BLUE_ICE) || s.is(Blocks.CLAY) || s.is(Blocks.MUD) || s.is(BlockTags.BASE_STONE_NETHER) || s.is(Blocks.END_STONE);
	}

	/** Part of a building (something that can hang or fall), as opposed to air, water, plants. */
	private static boolean solid(ServerLevel level, BlockPos p, BlockState s) {
		return !s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced() && !s.getCollisionShape(level, p).isEmpty();
	}

	/** Holds things up by itself: the ground, a protected block, a Ruby Heart, anything with contents (chests, machines). */
	private static boolean anchor(ServerLevel level, BlockPos p, BlockState s) {
		return natural(s) || s.is(dev.rubymod.registry.ModBlocks.RUBY_HEART) || s.hasBlockEntity() || !Rules.canWeaponBreak(level, p, s)
				|| p.getY() <= level.getMinY() + 1;
	}

	/** Every block near the blast that no longer has a path down to the ground falls. */
	static int dropHanging(ServerLevel level, BlockPos center, int r) {
		Set<BlockPos> settled = new HashSet<>();
		int fell = 0;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					BlockPos p = center.offset(dx, dy, dz);
					if (settled.contains(p)) {
						continue;
					}
					BlockState s = level.getBlockState(p);
					if (!solid(level, p, s) || anchor(level, p, s)) {
						continue;
					}
					List<BlockPos> piece = new ArrayList<>();
					boolean held = follow(level, p, center, piece);
					settled.addAll(piece);
					if (!held) {
						fell += fall(level, piece);
					}
				}
			}
		}
		return fell;
	}

	/** Follow everything joined to start: true if it reaches something holding it up (or there's too much to tell). */
	private static boolean follow(ServerLevel level, BlockPos start, BlockPos center, List<BlockPos> piece) {
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		seen.add(start);
		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			piece.add(p);
			if (piece.size() > MAX_STRUCTURE) {
				return true;
			}
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (seen.contains(n)) {
					continue;
				}
				seen.add(n);
				if (Math.abs(n.getX() - center.getX()) > REACH || Math.abs(n.getY() - center.getY()) > REACH || Math.abs(n.getZ() - center.getZ()) > REACH) {
					// Joined to something further off than we look: say it's held up.
					if (solid(level, n, level.getBlockState(n))) {
						return true;
					}
					continue;
				}
				BlockState s = level.getBlockState(n);
				if (!solid(level, n, s)) {
					continue;
				}
				if (anchor(level, n, s)) {
					return true;
				}
				queue.add(n);
			}
		}
		return false;
	}

	/** Bring a hanging piece down, bottom first, as falling blocks. */
	private static int fall(ServerLevel level, List<BlockPos> piece) {
		piece.sort(Comparator.comparingInt(BlockPos::getY));
		int n = 0;
		for (BlockPos p : piece) {
			BlockState s = level.getBlockState(p);
			if (s.isAir()) {
				continue;
			}
			FallingBlockEntity.fall(level, p, s);
			n++;
		}
		if (n > 0) {
			BlockPos mid = piece.get(piece.size() / 2);
			level.playSound(null, mid, net.minecraft.sounds.SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 2.0F, 0.6F);
		}
		return n;
	}

	// ----- Flying chunks -----

	private static final class Chunk {
		final Display.BlockDisplay show;
		final BlockState state;
		Vec3 pos;
		Vec3 velocity;
		float spin;
		final Vector3f axis;
		int age;

		Chunk(Display.BlockDisplay show, BlockState state, Vec3 pos, Vec3 velocity, Vector3f axis) {
			this.show = show;
			this.state = state;
			this.pos = pos;
			this.velocity = velocity;
			this.axis = axis;
		}
	}

	private static final List<Chunk> CHUNKS = new ArrayList<>();

	private static void throwChunks(ServerLevel level, Vec3 at, float power, List<BlockState> blasted) {
		RandomSource random = level.getRandom();
		int n = Math.min(blasted.size(), (int) (power * 2));
		for (int i = 0; i < n; i++) {
			BlockState state = blasted.get(i);
			Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (d == null) {
				continue;
			}
			double a = random.nextDouble() * Math.PI * 2;
			double out = 0.3 + random.nextDouble() * 0.25 * power / 4;
			Vec3 v = new Vec3(Math.cos(a) * out, 0.45 + random.nextDouble() * 0.5, Math.sin(a) * out);
			Vec3 start = at.add(0, 0.6, 0);
			d.snapTo(start.x, start.y, start.z, 0, 0);
			((BlockDisplayAccessor) d).rubymod$setBlockState(state);
			((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(2);
			((DisplayAccessor) d).rubymod$setTransformationInterpolationDuration(2);
			Vector3f axis = new Vector3f((float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian()).normalize();
			Chunk c = new Chunk(d, state, start, v, axis);
			show(c);
			level.addFreshEntity(d);
			CHUNKS.add(c);
		}
	}

	private static void show(Chunk c) {
		float size = 0.55F;
		Quaternionf rot = new Quaternionf().rotateAxis(c.spin, c.axis);
		Vector3f half = rot.transform(new Vector3f(size / 2, size / 2, size / 2));
		((DisplayAccessor) c.show).rubymod$setTransformation(new Transformation(new Vector3f().sub(half), rot, new Vector3f(size, size, size), new Quaternionf()));
		((DisplayAccessor) c.show).rubymod$setTransformationInterpolationDelay(0);
	}

	/** Every tick: chunks fly, spin, and break where they land. */
	public static void tick(MinecraftServer server) {
		for (Iterator<Chunk> it = CHUNKS.iterator(); it.hasNext();) {
			Chunk c = it.next();
			if (c.show.isRemoved() || !(c.show.level() instanceof ServerLevel level)) {
				it.remove();
				continue;
			}
			c.age++;
			Vec3 next = c.pos.add(c.velocity);
			BlockPos into = BlockPos.containing(next);
			BlockState there = level.getBlockState(into);
			boolean hit = !there.getCollisionShape(level, into).isEmpty() || !there.getFluidState().isEmpty();
			if (hit || c.age > 120) {
				Vec3 at = hit ? c.pos : next;
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, c.state), at.x, at.y + 0.2, at.z, 14, 0.25, 0.2, 0.25, 0.15);
				level.playSound(null, at.x, at.y, at.z, c.state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
				c.show.discard();
				it.remove();
				continue;
			}
			c.pos = next;
			c.velocity = c.velocity.scale(0.98).add(0, -0.06, 0);
			c.spin += 0.35F;
			c.show.teleportTo(next.x, next.y, next.z);
			show(c);
			if (c.age % 3 == 0) {
				level.sendParticles(ParticleTypes.SMOKE, next.x, next.y, next.z, 1, 0.05, 0.05, 0.05, 0.0);
			}
		}
	}

	/** The server's stopping: no chunks left in the air. */
	public static void clear() {
		for (Chunk c : CHUNKS) {
			c.show.discard();
		}
		CHUNKS.clear();
	}
}
