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
 * hanging falls (cut off from the ground, or reaching out further than its material can hold now its supports have
 * gone), with dust on the way down and a thump or a shatter when it lands; big blasts throw chunks that land and break.
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

	/**
	 * How far a block of this material can reach out from what holds it up: wood a few blocks, stone more, metal most.
	 * (Straight up a column costs nothing; every block out sideways, or hanging underneath, costs one.)
	 */
	static int strength(BlockState s) {
		var sound = s.getSoundType();
		if (sound == net.minecraft.world.level.block.SoundType.METAL || sound == net.minecraft.world.level.block.SoundType.NETHERITE_BLOCK || sound == net.minecraft.world.level.block.SoundType.COPPER || sound == net.minecraft.world.level.block.SoundType.HEAVY_CORE || sound == net.minecraft.world.level.block.SoundType.ANVIL
				|| sound == net.minecraft.world.level.block.SoundType.IRON || sound == net.minecraft.world.level.block.SoundType.COPPER_BULB || sound == net.minecraft.world.level.block.SoundType.COPPER_GRATE || sound == net.minecraft.world.level.block.SoundType.CHAIN || sound == net.minecraft.world.level.block.SoundType.LODESTONE) {
			return 10;
		}
		if (sound == net.minecraft.world.level.block.SoundType.STONE || sound == net.minecraft.world.level.block.SoundType.DEEPSLATE || sound == net.minecraft.world.level.block.SoundType.DEEPSLATE_BRICKS || sound == net.minecraft.world.level.block.SoundType.POLISHED_DEEPSLATE || sound == net.minecraft.world.level.block.SoundType.TUFF
				|| sound == net.minecraft.world.level.block.SoundType.TUFF_BRICKS || sound == net.minecraft.world.level.block.SoundType.MUD_BRICKS || sound == net.minecraft.world.level.block.SoundType.NETHER_BRICKS
				|| sound == net.minecraft.world.level.block.SoundType.DEEPSLATE_TILES || sound == net.minecraft.world.level.block.SoundType.POLISHED_TUFF || sound == net.minecraft.world.level.block.SoundType.RESIN_BRICKS || sound == net.minecraft.world.level.block.SoundType.CALCITE) {
			return 7;
		}
		if (sound == net.minecraft.world.level.block.SoundType.WOOD || sound == net.minecraft.world.level.block.SoundType.CHERRY_WOOD || sound == net.minecraft.world.level.block.SoundType.BAMBOO_WOOD || sound == net.minecraft.world.level.block.SoundType.NETHER_WOOD) {
			return 5;
		}
		if (sound == net.minecraft.world.level.block.SoundType.GLASS || sound == net.minecraft.world.level.block.SoundType.WOOL || sound == net.minecraft.world.level.block.SoundType.SAND || sound == net.minecraft.world.level.block.SoundType.GRAVEL) {
			return 2;
		}
		return 4;
	}

	/** Full support: the ground, protected blocks, and whatever's beyond where we look. */
	private static final int FIRM = 64;

	/**
	 * After a blast: how well held up is every block round it? Support flows out from the ground (and anything firm)
	 * through the building: straight up a column it stays as strong as the material allows, every block out sideways
	 * or hanging underneath loses one. Whatever ends up with none (cut off, or reaching out too far now that what held
	 * it has gone) comes down.
	 */
	static int dropHanging(ServerLevel level, BlockPos center, int r) {
		int box = r + 8;
		java.util.Map<BlockPos, Integer> support = new java.util.HashMap<>();
		java.util.PriorityQueue<long[]> queue = new java.util.PriorityQueue<>((a, b) -> Long.compare(b[1], a[1]));
		List<BlockPos> building = new ArrayList<>();
		for (int dx = -box; dx <= box; dx++) {
			for (int dy = -box; dy <= box; dy++) {
				for (int dz = -box; dz <= box; dz++) {
					BlockPos p = center.offset(dx, dy, dz);
					BlockState s = level.getBlockState(p);
					if (!solid(level, p, s)) {
						continue;
					}
					boolean edge = Math.abs(dx) == box || Math.abs(dy) == box || Math.abs(dz) == box;
					if (anchor(level, p, s) || edge) {
						support.put(p, FIRM);
						queue.add(new long[] {p.asLong(), FIRM});
					} else {
						building.add(p);
					}
				}
			}
		}
		if (building.isEmpty()) {
			return 0;
		}
		while (!queue.isEmpty()) {
			long[] top = queue.poll();
			BlockPos p = BlockPos.of(top[0]);
			int s = (int) top[1];
			if (support.getOrDefault(p, -1) > s) {
				continue;
			}
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (Math.abs(n.getX() - center.getX()) > box || Math.abs(n.getY() - center.getY()) > box || Math.abs(n.getZ() - center.getZ()) > box) {
					continue;
				}
				BlockState ns = level.getBlockState(n);
				if (!solid(level, n, ns) || anchor(level, n, ns)) {
					continue;
				}
				int carried = Math.min(d == Direction.UP ? s : s - 1, strength(ns));
				if (carried > support.getOrDefault(n, 0)) {
					support.put(n, carried);
					queue.add(new long[] {n.asLong(), carried});
				}
			}
		}
		List<BlockPos> falling = new ArrayList<>();
		for (BlockPos p : building) {
			if (support.getOrDefault(p, 0) <= 0) {
				falling.add(p);
			}
		}
		if (falling.size() > MAX_STRUCTURE) {
			falling.sort(Comparator.comparingInt(BlockPos::getY));
			falling = new ArrayList<>(falling.subList(0, MAX_STRUCTURE));
		}
		return fall(level, falling);
	}

	/** Bring them down, bottom first, as falling blocks; a big collapse rumbles and throws up dust. */
	private static int fall(ServerLevel level, List<BlockPos> piece) {
		piece.sort(Comparator.comparingInt(BlockPos::getY));
		int n = 0;
		for (BlockPos p : piece) {
			BlockState s = level.getBlockState(p);
			if (s.isAir()) {
				continue;
			}
			FallingBlockEntity f = FallingBlockEntity.fall(level, p, s);
			FALLING.add(new Faller(f, s));
			n++;
		}
		if (n > 0) {
			Vec3 mid = Vec3.atCenterOf(piece.get(piece.size() / 2));
			level.playSound(null, mid.x, mid.y, mid.z, net.minecraft.sounds.SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 2.0F, 0.6F);
			if (n >= 25) {
				// A building coming down: a deep rumble, the ground shakes, and a cloud of dust.
				level.playSound(null, mid.x, mid.y, mid.z, net.minecraft.sounds.SoundEvents.WARDEN_DIG, SoundSource.BLOCKS, 4.0F, 0.6F);
				level.playSound(null, mid.x, mid.y, mid.z, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 2.0F, 0.4F);
				Explosions.shake(level, mid, Math.min(6.0F, 1.5F + n / 40.0F), 30);
				level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, mid.x, mid.y, mid.z, Math.min(60, n), 3.0, 2.0, 3.0, 0.02);
			}
		}
		return n;
	}

	/** For the self-test: what's left hanging round this spot falls (as after a blast there). */
	public static int settle(ServerLevel level, BlockPos center, int r) {
		return dropHanging(level, center, r);
	}

	// ----- Falling blocks: dust on the way down, and a thump (or a shatter) when they land -----

	private record Faller(FallingBlockEntity entity, BlockState state) {
	}

	private static final List<Faller> FALLING = new ArrayList<>();

	/** Glass, ice and the like always break when they land; other blocks sometimes crack apart. */
	private static boolean shatters(BlockState s, RandomSource random) {
		return s.getSoundType() == net.minecraft.world.level.block.SoundType.GLASS || s.is(BlockTags.ICE) || random.nextInt(4) == 0;
	}

	private static void tickFalling() {
		int sounds = 0;
		for (Iterator<Faller> it = FALLING.iterator(); it.hasNext();) {
			Faller f = it.next();
			FallingBlockEntity e = f.entity();
			if (!(e.level() instanceof ServerLevel level)) {
				it.remove();
				continue;
			}
			if (!e.isRemoved()) {
				if (e.tickCount % 3 == 0) {
					level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, f.state()), e.getX(), e.getY() + 0.5, e.getZ(), 1, 0.3, 0.2, 0.3, 0);
				}
				continue;
			}
			it.remove();
			BlockPos at = e.blockPosition();
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, f.state()), at.getX() + 0.5, at.getY() + 0.3, at.getZ() + 0.5, 10, 0.4, 0.2, 0.4, 0.1);
			level.sendParticles(ParticleTypes.POOF, at.getX() + 0.5, at.getY() + 0.2, at.getZ() + 0.5, 3, 0.4, 0.1, 0.4, 0.02);
			boolean landedHere = level.getBlockState(at).is(f.state().getBlock());
			if (landedHere && shatters(f.state(), level.getRandom())) {
				level.destroyBlock(at, false);
				landedHere = false;
			}
			if (sounds++ < 6) {
				var sound = f.state().getSoundType();
				level.playSound(null, at, landedHere ? sound.getFallSound() : sound.getBreakSound(), SoundSource.BLOCKS, 1.2F, 0.7F);
			}
		}
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
		tickFalling();
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
		FALLING.clear();
		for (Chunk c : CHUNKS) {
			c.show.discard();
		}
		CHUNKS.clear();
	}
}
