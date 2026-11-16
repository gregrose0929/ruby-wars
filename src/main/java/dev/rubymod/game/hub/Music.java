package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Music round the park: a bandstand where a little band plays old favourites when you come near (sit on the benches and
 * listen), and buskers on Main Street and in the Town Square, each with their own instrument.
 */
final class Music {
	private Music() {
	}

	/** Old tunes everyone knows (all very old, free to play): note-block notes (0 = F#3 .. 24 = F#5), -1 a rest. */
	private static final int[][] SONGS = {
		// Twinkle, Twinkle, Little Star
		{6, 6, 13, 13, 15, 15, 13, -1, 11, 11, 10, 10, 8, 8, 6, -1, 13, 13, 11, 11, 10, 10, 8, -1, 13, 13, 11, 11, 10, 10, 8, -1, 6, 6, 13, 13, 15, 15, 13, -1,
			11, 11, 10, 10, 8, 8, 6, -1, -1},
		// Ode to Joy
		{10, 10, 11, 13, 13, 11, 10, 8, 6, 6, 8, 10, 10, -1, 8, 8, -1, 10, 10, 11, 13, 13, 11, 10, 8, 6, 6, 8, 10, 8, -1, 6, 6, -1, -1},
		// Row, Row, Row Your Boat
		{6, -1, 6, -1, 6, 8, 10, -1, 10, 8, 10, 11, 13, -1, -1, -1, 18, 18, 18, 13, 13, 13, 10, 10, 10, 6, 6, 6, 13, 11, 10, 8, 6, -1, -1, -1},
		// London Bridge
		{13, 15, 13, 11, 10, 11, 13, -1, 8, 10, 11, -1, 10, 11, 13, -1, 13, 15, 13, 11, 10, 11, 13, -1, 8, -1, 13, -1, 10, 6, -1, -1}};

	/** A band (or one busker): who's playing, where, what instrument(s), and how far through the tune. */
	private static final class Group {
		Vec3 at;
		List<UUID> players = new ArrayList<>();
		Holder<SoundEvent> lead;
		boolean band;
		int song;
		int note;
		int beat = 6;
	}

	private static final List<Group> GROUPS = new ArrayList<>();
	private static int tick;

	static void reset() {
		GROUPS.clear();
	}

	static void build(ServerLevel level) {
		GROUPS.clear();
		bandstand(level);
		busker(level, new Vec3(-57.5, FLOOR + 1, -94.5), 90, SoundEvents.NOTE_BLOCK_BANJO, "Banjo Bill");
		busker(level, new Vec3(-66.5, FLOOR + 1, -86.5), -90, SoundEvents.NOTE_BLOCK_FLUTE, "Flute Flo");
		busker(level, new Vec3(-66.5, FLOOR + 1, -26.5), 180, SoundEvents.NOTE_BLOCK_GUITAR, "Guitar Gabe");
	}

	/** An open spot for the bandstand: open grass, nothing built or riding nearby, as near the plaza as can be. */
	private static Vec3 spot(ServerLevel level) {
		for (int r = 18; r <= 130; r += 2) {
			for (int k = 0; k < 32; k++) {
				double a = k * Math.PI * 2 / 32;
				int x = (int) Math.round(Math.cos(a) * r);
				int z = (int) Math.round(Math.sin(a) * r);
				if (open(level, x, z, 6)) {
					return new Vec3(x + 0.5, FLOOR + 1, z + 0.5);
				}
			}
		}
		return null;
	}

	private static boolean open(ServerLevel level, int x, int z, int r) {
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				BlockState g = level.getBlockState(new BlockPos(x + dx, FLOOR, z + dz));
				if (!g.is(Blocks.GRASS_BLOCK)) {
					return false;
				}
				for (int y = 1; y <= 7; y++) {
					BlockState s = level.getBlockState(new BlockPos(x + dx, FLOOR + y, z + dz));
					if (!s.isAir() && !s.canBeReplaced()) {
						return false;
					}
				}
			}
		}
		return level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(x - r, FLOOR - 1, z - r, x + r + 1, FLOOR + 12, z + r + 1), e -> true).isEmpty();
	}

	/** An eight-sided bandstand: a raised floor, white posts, a striped pointed roof, benches round it, and a band. */
	private static void bandstand(ServerLevel level) {
		Vec3 at = spot(level);
		if (at == null) {
			dev.rubymod.RubyMod.LOGGER.info("Music: no room for the bandstand");
			return;
		}
		int cx = (int) Math.floor(at.x);
		int cz = (int) Math.floor(at.z);
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				if (Math.abs(dx) + Math.abs(dz) > 6) {
					continue;
				}
				boolean edge = Math.abs(dx) == 4 || Math.abs(dz) == 4 || Math.abs(dx) + Math.abs(dz) == 6;
				HubPark.set(level, cx + dx, FLOOR + 1, cz + dz, edge ? Blocks.STONE_BRICK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE,
						net.minecraft.world.level.block.state.properties.SlabType.TOP) : Blocks.POLISHED_ANDESITE.defaultBlockState());
				for (int y = 2; y <= 9; y++) {
					HubPark.set(level, cx + dx, FLOOR + y, cz + dz, Blocks.AIR.defaultBlockState());
				}
			}
		}
		// Steps up on all four sides.
		for (int[] s : new int[][] {{0, -5}, {0, 5}, {-5, 0}, {5, 0}}) {
			for (int w = -1; w <= 1; w++) {
				int x = cx + s[0] + (s[0] == 0 ? w : 0);
				int z = cz + s[1] + (s[1] == 0 ? w : 0);
				HubPark.set(level, x, FLOOR + 1, z, Blocks.STONE_BRICK_SLAB.defaultBlockState());
			}
		}
		// Posts, a railing between them, and the roof.
		int[][] posts = {{-4, -2}, {-4, 2}, {4, -2}, {4, 2}, {-2, -4}, {2, -4}, {-2, 4}, {2, 4}};
		for (int[] p : posts) {
			HubPark.box(level, cx + p[0], FLOOR + 2, cz + p[1], cx + p[0], FLOOR + 5, cz + p[1], Blocks.BIRCH_FENCE.defaultBlockState());
		}
		for (int layer = 0; layer <= 4; layer++) {
			int r = 5 - layer;
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.abs(dx) + Math.abs(dz) > r + r / 2 || Math.max(Math.abs(dx), Math.abs(dz)) < r - 1 && layer < 4) {
						continue;
					}
					boolean stripe = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(dz, dx)) / 30), 2) == 0;
					HubPark.set(level, cx + dx, FLOOR + 6 + layer, cz + dz,
							Blocks.WOOL.pick(stripe ? net.minecraft.world.item.DyeColor.RED : net.minecraft.world.item.DyeColor.WHITE).defaultBlockState());
				}
			}
		}
		HubPark.box(level, cx, FLOOR + 11, cz, cx, FLOOR + 12, cz, Blocks.BIRCH_FENCE.defaultBlockState());
		HubPark.set(level, cx, FLOOR + 13, cz, Blocks.GOLD_BLOCK.defaultBlockState());
		HubPark.set(level, cx, FLOOR + 5, cz, Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
		// Benches facing the stage.
		Direction4[] sides = Direction4.values();
		for (Direction4 s : sides) {
			for (int w = -2; w <= 2; w++) {
				if (Math.abs(w) <= 1) {
					continue;
				}
				int x = cx + s.dx * 7 + (s.dx == 0 ? w : 0);
				int z = cz + s.dz * 7 + (s.dz == 0 ? w : 0);
				HubPark.set(level, x, FLOOR + 1, z, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, s.facing));
			}
		}
		HubPark.label(level, cx + 0.5, FLOOR + 14.2, cz + 0.5, "BANDSTAND", ChatFormatting.GOLD, 1.4F);
		HubPark.label(level, cx + 0.5, FLOOR + 13.6, cz + 0.5, "The band plays when you're near: sit and listen!", ChatFormatting.WHITE, 0.6F);
		// The band: four musicians.
		Group g = new Group();
		g.at = new Vec3(cx + 0.5, FLOOR + 2, cz + 0.5);
		g.band = true;
		g.lead = SoundEvents.NOTE_BLOCK_FLUTE;
		int[][] spots = {{-2, -1}, {2, -1}, {-1, 2}, {1, 2}};
		String[] names = {"Trumpet Tom", "Drummer Dee", "Harpist Hal", "Bass Betty"};
		for (int i = 0; i < 4; i++) {
			Villager v = musician(level, new Vec3(cx + spots[i][0] + 0.5, FLOOR + 2, cz + spots[i][1] + 0.5), 180, names[i]);
			if (v != null) {
				g.players.add(v.getUUID());
			}
		}
		GROUPS.add(g);
		dev.rubymod.RubyMod.LOGGER.info("Music: bandstand at {} {}", cx, cz);
	}

	private enum Direction4 {
		N(0, -1, net.minecraft.core.Direction.NORTH), S(0, 1, net.minecraft.core.Direction.SOUTH), W(-1, 0, net.minecraft.core.Direction.WEST),
		E(1, 0, net.minecraft.core.Direction.EAST);

		final int dx;
		final int dz;
		final net.minecraft.core.Direction facing;

		Direction4(int dx, int dz, net.minecraft.core.Direction facing) {
			this.dx = dx;
			this.dz = dz;
			this.facing = facing;
		}
	}

	private static Villager musician(ServerLevel level, Vec3 at, float yaw, String name) {
		Villager v = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT);
		if (v == null) {
			return null;
		}
		v.setVillagerData(v.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.NITWIT).withType(level.registryAccess(), VillagerType.PLAINS));
		v.setNoAi(true);
		v.setSilent(true);
		v.setPermanentlyInvulnerable(true);
		v.setPersistenceRequired();
		v.setCustomName(Component.literal(name).withStyle(ChatFormatting.LIGHT_PURPLE));
		v.snapTo(at.x, at.y, at.z, yaw, 0);
		v.setYHeadRot(yaw);
		level.addFreshEntity(v);
		HubPark.keep(v);
		return v;
	}

	private static void busker(ServerLevel level, Vec3 at, float yaw, Holder<SoundEvent> instrument, String name) {
		Villager v = musician(level, at, yaw, name);
		if (v == null) {
			return;
		}
		Group g = new Group();
		g.at = at;
		g.lead = instrument;
		g.players.add(v.getUUID());
		g.song = GROUPS.size() % SONGS.length;
		g.beat = 5;
		GROUPS.add(g);
	}

	static int[] song(int i) {
		return SONGS[Math.floorMod(i, SONGS.length)];
	}

	private static float pitch(int note) {
		return (float) Math.pow(2, (note - 12) / 12.0);
	}

	static void tick(ServerLevel level) {
		tick++;
		for (Group g : GROUPS) {
			if (tick % g.beat != 0) {
				continue;
			}
			ServerPlayer near = null;
			for (ServerPlayer p : level.players()) {
				if (p.position().distanceTo(g.at) < (g.band ? 22 : 12)) {
					near = p;
				}
			}
			if (near == null) {
				g.note = 0;
				continue;
			}
			int[] song = SONGS[g.song];
			int n = song[g.note];
			BlockPos pos = BlockPos.containing(g.at);
			if (n >= 0) {
				float vol = g.band ? 1.2F : 0.8F;
				level.playSound(null, pos, g.lead.value(), SoundSource.RECORDS, vol, pitch(n));
				if (g.band) {
					// The band: harp doubling the tune an octave down, bass on the beat, a drum, a chime now and then.
					level.playSound(null, pos, SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.RECORDS, 0.7F, pitch(Math.max(0, n - 12)));
					if (g.note % 2 == 0) {
						level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.RECORDS, 0.9F, pitch(n % 12));
					}
					level.playSound(null, pos, (g.note % 4 == 0 ? SoundEvents.NOTE_BLOCK_BASEDRUM : SoundEvents.NOTE_BLOCK_HAT).value(), SoundSource.RECORDS, 0.6F, 1.0F);
				}
			} else if (g.band) {
				level.playSound(null, pos, SoundEvents.NOTE_BLOCK_SNARE.value(), SoundSource.RECORDS, 0.5F, 1.0F);
			}
			// Notes float up from the players.
			for (UUID id : g.players) {
				Entity e = level.getEntity(id);
				if (e != null && n >= 0) {
					level.sendParticles(ParticleTypes.NOTE, e.getX(), e.getY() + 2.3, e.getZ(), 1, 0.2, 0.1, 0.2, n / 24.0);
					// A little bob in time.
					e.setYHeadRot(e.getYRot() + (g.note % 2 == 0 ? 12 : -12));
				}
			}
			g.note++;
			if (g.note >= song.length) {
				g.note = 0;
				g.song = (g.song + 1) % SONGS.length;
			}
		}
	}
}
