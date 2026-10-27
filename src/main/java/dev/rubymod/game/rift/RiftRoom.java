package dev.rubymod.game.rift;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import dev.rubymod.registry.ModBlocks;
import dev.rubymod.world.WarzoneTerrain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A Rift room, built fresh each time from pieces: an enclosed hall (lit by glowing blocks,
 * not the sun) with pillars banded in light, cover to fight from, rings of glowing tiles
 * in the floor, and features for its look: crystal spires, lava glowing under iron
 * grates, ice spires, mossy water tanks. A raised dais in the middle is where the way
 * onward opens. Built a few milliseconds a tick (no freezes) while the team travels.
 */
public final class RiftRoom {
	public enum Theme {
		CRYSTAL("Crystal Caverns", 0xFFC27BFF), FORGE("Molten Forge", 0xFFFF7A2E), FROST("Frozen Vault", 0xFF8FE3FF), LAB("Overgrown Lab", 0xFF6EE38A),
		FOUNDRY("Mech Foundry", 0xFFFF4040), SKYDOCK("The Sky Dock", 0xFF66E0FF), HEART("The Ruby Heart", 0xFFFF3D6E),
		THRESHOLD("The Threshold", 0xFFFF3D6E);

		public final String title;
		public final int color;

		Theme(String title, int color) {
			this.title = title;
			this.color = color;
		}

		static Theme[] normal() {
			return new Theme[] {CRYSTAL, FORGE, FROST, LAB};
		}
	}

	public static final int HEIGHT = 17;
	public final BlockPos center;
	public final Theme theme;
	public final int half;
	public final List<BlockPos> spawns = new ArrayList<>();
	public final Vec3 entry;
	public final Vec3 portal;

	RiftRoom(BlockPos center, Theme theme, int half) {
		this.center = center;
		this.theme = theme;
		this.half = half;
		for (int i = 0; i < 10; i++) {
			double a = Math.PI * 2 * i / 10 + 0.3;
			spawns.add(center.offset((int) Math.round(Math.cos(a) * (half - 4)), 1, (int) Math.round(Math.sin(a) * (half - 4))));
		}
		this.entry = new Vec3(center.getX() + 0.5, center.getY() + 1, center.getZ() + half - 3.5);
		this.portal = new Vec3(center.getX() + 0.5, center.getY() + 2, center.getZ() + 0.5);
	}

	public boolean inside(Vec3 p) {
		return Math.abs(p.x - center.getX()) <= half + 2 && Math.abs(p.z - center.getZ()) <= half + 2 && p.y > center.getY() - 6
				&& p.y < center.getY() + HEIGHT + 4;
	}

	// ----- Palettes -----

	private record Palette(BlockState[] floor, BlockState floorGlow, BlockState wall, BlockState wallAccent, BlockState wallGlow, BlockState ceiling,
			BlockState lamp, BlockState pillar, BlockState pillarGlow, BlockState cover) {
	}

	private static BlockState b(Block block) {
		return block.defaultBlockState();
	}

	private static BlockState copper(WeatheringCopper.WeatherState age) {
		return Blocks.CUT_COPPER.waxed().pick(age).defaultBlockState();
	}

	private static Palette palette(Theme theme) {
		return switch (theme) {
			case SKYDOCK -> new Palette(new BlockState[] {b(Blocks.SMOOTH_STONE), b(Blocks.POLISHED_ANDESITE), b(Blocks.SMOOTH_STONE),
					Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState()},
					b(Blocks.SEA_LANTERN), b(Blocks.POLISHED_ANDESITE), Blocks.CONCRETE.pick(DyeColor.LIGHT_BLUE).defaultBlockState(), b(Blocks.SEA_LANTERN),
					b(Blocks.AIR), b(Blocks.AIR), b(Blocks.POLISHED_ANDESITE), b(Blocks.SEA_LANTERN), b(Blocks.ANDESITE_WALL));
			case HEART -> new Palette(new BlockState[] {b(Blocks.POLISHED_BLACKSTONE), b(ModBlocks.RIFT_STONE), b(Blocks.POLISHED_BLACKSTONE),
					b(ModBlocks.RUBY_BLOCK), b(ModBlocks.RIFT_STONE)}, b(Blocks.PEARLESCENT_FROGLIGHT), b(Blocks.OBSIDIAN), b(ModBlocks.RIFT_STONE),
					b(Blocks.PEARLESCENT_FROGLIGHT), b(Blocks.OBSIDIAN), b(Blocks.PEARLESCENT_FROGLIGHT), b(ModBlocks.RIFT_STONE), b(ModBlocks.RUBY_BLOCK),
					b(Blocks.BLACKSTONE_WALL));
			case CRYSTAL, THRESHOLD -> new Palette(new BlockState[] {b(Blocks.DEEPSLATE_TILES), b(Blocks.POLISHED_DEEPSLATE), b(Blocks.DEEPSLATE_TILES),
					b(ModBlocks.RIFT_STONE), b(Blocks.AMETHYST_BLOCK)}, b(Blocks.PEARLESCENT_FROGLIGHT), b(Blocks.DEEPSLATE_BRICKS), b(ModBlocks.RIFT_STONE),
					b(Blocks.PEARLESCENT_FROGLIGHT), b(Blocks.DEEPSLATE_TILES), b(Blocks.PEARLESCENT_FROGLIGHT), b(ModBlocks.RIFT_STONE),
					b(Blocks.PEARLESCENT_FROGLIGHT), b(Blocks.DEEPSLATE_BRICK_WALL));
			case FORGE, FOUNDRY -> new Palette(new BlockState[] {b(Blocks.POLISHED_BLACKSTONE_BRICKS), b(Blocks.BLACKSTONE), b(Blocks.POLISHED_BLACKSTONE),
					b(ModBlocks.RIFT_MAGMA), b(Blocks.GILDED_BLACKSTONE)}, b(Blocks.OCHRE_FROGLIGHT), b(Blocks.NETHER_BRICKS),
					b(ModBlocks.RIFT_MAGMA), b(Blocks.SHROOMLIGHT), b(Blocks.BLACKSTONE), b(Blocks.SHROOMLIGHT), b(ModBlocks.RIFT_MAGMA),
					b(Blocks.OCHRE_FROGLIGHT), b(Blocks.POLISHED_BLACKSTONE_BRICK_WALL));
			case FROST -> new Palette(new BlockState[] {b(Blocks.PACKED_ICE), b(Blocks.SNOW_BLOCK), b(ModBlocks.RIFT_FROST), b(Blocks.PACKED_ICE),
					b(Blocks.BLUE_ICE)}, b(Blocks.SEA_LANTERN), b(Blocks.PACKED_ICE), b(ModBlocks.RIFT_FROST), b(Blocks.SEA_LANTERN), b(Blocks.PACKED_ICE),
					b(Blocks.SEA_LANTERN), b(ModBlocks.RIFT_FROST), b(Blocks.SEA_LANTERN), b(Blocks.DIORITE_WALL));
			case LAB -> new Palette(new BlockState[] {copper(WeatheringCopper.WeatherState.UNAFFECTED), b(ModBlocks.RIFT_VERDANT),
					b(Blocks.MOSS_BLOCK), b(Blocks.MOSSY_STONE_BRICKS), copper(WeatheringCopper.WeatherState.WEATHERED)}, b(Blocks.VERDANT_FROGLIGHT),
					copper(WeatheringCopper.WeatherState.OXIDIZED), b(ModBlocks.RIFT_VERDANT), b(Blocks.VERDANT_FROGLIGHT),
					copper(WeatheringCopper.WeatherState.WEATHERED), b(Blocks.VERDANT_FROGLIGHT), b(ModBlocks.RIFT_VERDANT),
					b(Blocks.VERDANT_FROGLIGHT), b(Blocks.MOSSY_STONE_BRICK_WALL));
		};
	}

	// ----- Building -----

	/** Queue up building a room at {@code center} (the old one there is replaced). */
	static RiftRoom build(ArrayDeque<Runnable> work, ServerLevel level, BlockPos center, Theme theme, RiftMap.Type type, long seed) {
		int half = type == RiftMap.Type.BOSS || type == RiftMap.Type.TANK ? 30 : theme == Theme.THRESHOLD ? 14
				: type == RiftMap.Type.FIGHT || type == RiftMap.Type.ELITE ? 22 : 15;
		boolean open = theme == Theme.SKYDOCK;
		RiftRoom room = new RiftRoom(center, theme, half);
		Palette pal = palette(theme);
		RandomSource random = RandomSource.create(seed);
		int ox = center.getX();
		int oy = center.getY();
		int oz = center.getZ();
		int outer = half + 1;
		for (int cx = (ox - outer - 8) >> 4; cx <= (ox + outer + 8) >> 4; cx++) {
			for (int cz = (oz - outer - 8) >> 4; cz <= (oz + outer + 8) >> 4; cz++) {
				int fcx = cx;
				int fcz = cz;
				work.add(() -> level.getChunk(fcx, fcz));
			}
		}
		// The shell: floor (with glowing rings), walls (with a band of light), ceiling (with lamps).
		BlockState air = b(Blocks.AIR);
		BlockState under = b(Blocks.DEEPSLATE);
		for (int x = -outer - 2; x <= outer + 2; x++) {
			int fx = x;
			work.add(() -> {
				BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
				for (int z = -outer - 2; z <= outer + 2; z++) {
					boolean beyond = Math.abs(fx) > outer || Math.abs(z) > outer;
					boolean wall = !beyond && (Math.abs(fx) == outer || Math.abs(z) == outer);
					double r = Math.sqrt(fx * fx + z * z);
					for (int y = -3; y <= HEIGHT + 1; y++) {
						BlockState want;
						if (beyond) {
							want = y <= HEIGHT ? (open && y > 8 ? air : under) : air;
						} else if (y < 0) {
							want = under;
						} else if (wall) {
							boolean band = y == HEIGHT - 4 || y == 1 && (fx + z) % 4 == 0;
							want = open && y > 8 ? b(Blocks.BARRIER) : y > HEIGHT ? under : band ? pal.wallGlow() : (fx * 7 + y * 3 + z * 5) % 11 == 0 ? pal.wallAccent() : pal.wall();
						} else if (y == 0) {
							int ring = (int) Math.round(r);
							boolean glowRing = (ring == 6 || ring == 13 || ring == 20) && (Math.abs(fx) + Math.abs(z)) % 2 == 0;
							double v = WarzoneTerrain.hash3(fx, (int) seed, z);
							want = glowRing ? pal.floorGlow() : pal.floor()[(int) (v * pal.floor().length)];
						} else if (y == HEIGHT) {
							want = open ? air : (Math.floorMod(fx, 6) == 3 && Math.floorMod(z, 6) == 3) ? pal.lamp() : pal.ceiling();
						} else if (y > HEIGHT) {
							want = open ? air : under;
						} else {
							want = air;
						}
						m.set(ox + fx, oy + y, oz + z);
						if (level.getBlockState(m) != want) {
							level.setBlock(m, want, Block.UPDATE_CLIENTS);
						}
					}
				}
			});
		}
		if (type == RiftMap.Type.TANK) {
			cover(work, level, room, pal, random);
			features(work, level, room, random);
		} else if (type == RiftMap.Type.BOSS || type == RiftMap.Type.FIGHT || type == RiftMap.Type.ELITE) {
			pillars(work, level, room, pal, random);
			cover(work, level, room, pal, random);
			features(work, level, room, random);
		} else {
			// Smaller rooms: four glowing pillars at the corners.
			for (int[] c : new int[][] {{-half + 3, -half + 3}, {half - 3, -half + 3}, {-half + 3, half - 3}, {half - 3, half - 3}}) {
				pillar(work, level, room, pal, c[0], c[1], 1);
			}
		}
		// The dais in the middle, where the way onward opens.
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				set(work, level, ox + dx, oy + 1, oz + dz, edge ? pal.pillar() : dx == 0 && dz == 0 ? pal.floorGlow() : pal.floor()[0]);
			}
		}
		return room;
	}

	private static void set(ArrayDeque<Runnable> work, ServerLevel level, int x, int y, int z, BlockState state) {
		BlockPos pos = new BlockPos(x, y, z);
		work.add(() -> level.setBlock(pos, state, Block.UPDATE_CLIENTS));
	}

	/** A pillar from floor to ceiling, banded with light ({@code r} 1: 3x3, 0: 1x1). */
	private static void pillar(ArrayDeque<Runnable> work, ServerLevel level, RiftRoom room, Palette pal, int px, int pz, int r) {
		int ox = room.center.getX();
		int oy = room.center.getY();
		int oz = room.center.getZ();
		work.add(() -> {
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					for (int y = 1; y < HEIGHT; y++) {
						boolean band = y == 4 || y == HEIGHT - 5;
						level.setBlock(m.set(ox + px + dx, oy + y, oz + pz + dz), band ? pal.pillarGlow() : pal.pillar(), Block.UPDATE_CLIENTS);
					}
				}
			}
		});
	}

	private static void pillars(ArrayDeque<Runnable> work, ServerLevel level, RiftRoom room, Palette pal, RandomSource random) {
		int d = room.half / 2 + random.nextInt(3);
		// Mirrored, so the room looks designed.
		for (int[] s : new int[][] {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}}) {
			pillar(work, level, room, pal, s[0] * d, s[1] * d, 1);
			if (room.half > 20) {
				pillar(work, level, room, pal, s[0] * (room.half - 5), s[1] * 4, 0);
			}
		}
	}

	/** Low walls to fight from. */
	private static void cover(ArrayDeque<Runnable> work, ServerLevel level, RiftRoom room, Palette pal, RandomSource random) {
		int ox = room.center.getX();
		int oy = room.center.getY();
		int oz = room.center.getZ();
		int n = room.theme == Theme.FOUNDRY ? 4 : 7;
		for (int i = 0; i < n; i++) {
			int x = random.nextInt(room.half * 2 - 8) - room.half + 4;
			int z = random.nextInt(room.half * 2 - 10) - room.half + 4;
			if (Math.abs(x) < 5 && Math.abs(z) < 5 || z > room.half - 7) {
				continue;
			}
			boolean alongX = random.nextBoolean();
			int len = 3 + random.nextInt(4);
			for (int k = 0; k < len; k++) {
				int bx = ox + x + (alongX ? k : 0);
				int bz = oz + z + (alongX ? 0 : k);
				set(work, level, bx, oy + 1, bz, pal.wall());
				set(work, level, bx, oy + 2, bz, pal.cover());
			}
		}
	}

	/** Each look's own features. */
	private static void features(ArrayDeque<Runnable> work, ServerLevel level, RiftRoom room, RandomSource random) {
		int ox = room.center.getX();
		int oy = room.center.getY();
		int oz = room.center.getZ();
		int h = room.half;
		switch (room.theme) {
			case SKYDOCK -> {
				// Landing pads with glowing rings, and platforms for the AA guns.
				for (int[] c : new int[][] {{-12, -12}, {12, -12}, {-12, 12}, {12, 12}}) {
					work.add(() -> {
						for (int dx = -3; dx <= 3; dx++) {
							for (int dz = -3; dz <= 3; dz++) {
								boolean ring = Math.max(Math.abs(dx), Math.abs(dz)) == 3;
								level.setBlock(new BlockPos(ox + c[0] + dx, oy + 1, oz + c[1] + dz), ring ? b(Blocks.SEA_LANTERN)
										: Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState(), Block.UPDATE_CLIENTS);
							}
						}
					});
				}
			}
			case CRYSTAL, THRESHOLD, HEART -> {
				// Crystal spires from the floor and the ceiling: amethyst, ruby and purple glass.
				BlockState[] crystal = {b(Blocks.AMETHYST_BLOCK), b(ModBlocks.RUBY_BLOCK), Blocks.STAINED_GLASS.pick(DyeColor.PURPLE).defaultBlockState(),
						Blocks.STAINED_GLASS.pick(DyeColor.MAGENTA).defaultBlockState()};
				for (int i = 0; i < 14; i++) {
					int x = random.nextInt(h * 2 - 4) - h + 2;
					int z = random.nextInt(h * 2 - 4) - h + 2;
					if (Math.abs(x) < 5 && Math.abs(z) < 5 || z > h - 6) {
						continue;
					}
					boolean down = random.nextBoolean();
					int tall = 3 + random.nextInt(5);
					BlockState c = crystal[random.nextInt(crystal.length)];
					work.add(() -> {
						for (int y = 0; y < tall; y++) {
							int r = y < tall / 3 ? 1 : 0;
							for (int dx = -r; dx <= r; dx++) {
								for (int dz = -r; dz <= r; dz++) {
									if (r == 1 && Math.abs(dx) + Math.abs(dz) == 2) {
										continue;
									}
									int yy = down ? oy + RiftRoom.HEIGHT - 1 - y : oy + 1 + y;
									level.setBlock(new BlockPos(ox + x + dx, yy, oz + z + dz), c, Block.UPDATE_CLIENTS);
								}
							}
						}
						int tipY = down ? oy + RiftRoom.HEIGHT - 1 - tall : oy + 1 + tall;
						if (!down) {
							level.setBlock(new BlockPos(ox + x, tipY, oz + z), b(Blocks.AMETHYST_CLUSTER), Block.UPDATE_CLIENTS);
						}
					});
				}
			}
			case FORGE, FOUNDRY -> {
				// Channels of lava under iron grates, glowing through the floor.
				for (int i = -h + 2; i <= h - 2; i++) {
					for (int w = -1; w <= 1; w++) {
						if (Math.abs(i) < 4) {
							continue;
						}
						int fi = i;
						int fw = w;
						work.add(() -> {
							for (BlockPos p : new BlockPos[] {new BlockPos(ox + fi, oy, oz + fw), new BlockPos(ox + fw, oy, oz + fi)}) {
								level.setBlock(p.below(), b(Blocks.LAVA), Block.UPDATE_CLIENTS);
								level.setBlock(p, b(Blocks.IRON_BARS), Block.UPDATE_CLIENTS);
							}
						});
					}
				}
				// Chains hanging from the ceiling.
				for (int i = 0; i < 12; i++) {
					int x = random.nextInt(h * 2 - 4) - h + 2;
					int z = random.nextInt(h * 2 - 4) - h + 2;
					int len = 3 + random.nextInt(6);
					work.add(() -> {
						for (int y = 0; y < len; y++) {
							level.setBlock(new BlockPos(ox + x, oy + RiftRoom.HEIGHT - 1 - y, oz + z), b(Blocks.IRON_CHAIN), Block.UPDATE_CLIENTS);
						}
						level.setBlock(new BlockPos(ox + x, oy + RiftRoom.HEIGHT - 1 - len, oz + z), b(Blocks.LANTERN), Block.UPDATE_CLIENTS);
					});
				}
			}
			case FROST -> {
				// Ice spires and slick blue ice.
				for (int i = 0; i < 12; i++) {
					int x = random.nextInt(h * 2 - 4) - h + 2;
					int z = random.nextInt(h * 2 - 4) - h + 2;
					if (Math.abs(x) < 5 && Math.abs(z) < 5 || z > h - 6) {
						continue;
					}
					int tall = 4 + random.nextInt(8);
					work.add(() -> {
						for (int y = 0; y < tall; y++) {
							int r = y < tall / 3 ? 1 : 0;
							for (int dx = -r; dx <= r; dx++) {
								for (int dz = -r; dz <= r; dz++) {
									level.setBlock(new BlockPos(ox + x + dx, oy + 1 + y, oz + z + dz), y % 4 == 3 ? b(Blocks.BLUE_ICE) : b(Blocks.PACKED_ICE),
											Block.UPDATE_CLIENTS);
								}
							}
						}
					});
				}
			}
			case LAB -> {
				// Glass tanks of water with sea pickles glowing inside, and moss growing over it all.
				for (int i = 0; i < 5; i++) {
					int x = random.nextInt(h * 2 - 8) - h + 4;
					int z = random.nextInt(h * 2 - 10) - h + 4;
					if (Math.abs(x) < 6 && Math.abs(z) < 6) {
						continue;
					}
					work.add(() -> {
						for (int dx = -1; dx <= 1; dx++) {
							for (int dz = -1; dz <= 1; dz++) {
								for (int y = 1; y <= 4; y++) {
									boolean shell = Math.abs(dx) == 1 || Math.abs(dz) == 1 || y == 4;
									level.setBlock(new BlockPos(ox + x + dx, oy + y, oz + z + dz), shell ? b(Blocks.GLASS)
											: y == 1 ? b(Blocks.SEA_LANTERN) : b(Blocks.WATER), Block.UPDATE_CLIENTS);
								}
							}
						}
					});
				}
				for (int i = 0; i < 40; i++) {
					int x = random.nextInt(h * 2 - 2) - h + 1;
					int z = random.nextInt(h * 2 - 2) - h + 1;
					work.add(() -> {
						BlockPos p = new BlockPos(ox + x, oy + 1, oz + z);
						if (level.getBlockState(p).isAir()) {
							level.setBlock(p, random.nextFloat() < 0.15F ? b(Blocks.FLOWERING_AZALEA) : b(Blocks.MOSS_CARPET), Block.UPDATE_CLIENTS);
						}
					});
				}
			}
		}
	}

	// ----- Looks, while you're in it -----

	/** Drifting particles for the room's look (a few a tick to whoever's there). */
	void ambient(ServerLevel level, RandomSource random) {
		for (int i = 0; i < 4; i++) {
			double x = center.getX() + (random.nextDouble() * 2 - 1) * half;
			double z = center.getZ() + (random.nextDouble() * 2 - 1) * half;
			double y = center.getY() + 1 + random.nextDouble() * (HEIGHT - 2);
			switch (theme) {
				case SKYDOCK -> level.sendParticles(ParticleTypes.CLOUD, true, false, x, center.getY() + 12, z, 1, 1.0, 0.2, 1.0, 0.01);
				case CRYSTAL, THRESHOLD, HEART -> {
					level.sendParticles(ParticleTypes.END_ROD, true, false, x, y, z, 1, 0.1, 0.1, 0.1, 0.01);
					level.sendParticles(RiftFx.dust(random.nextBoolean() ? 0xC27BFF : 0xFF3D6E, 1.2F), true, false, x, y, z, 1, 0.3, 0.3, 0.3, 0);
				}
				case FORGE, FOUNDRY -> {
					level.sendParticles(ParticleTypes.LAVA, true, false, x, center.getY() + 1, z, 1, 0.2, 0, 0.2, 0);
					level.sendParticles(ParticleTypes.SMALL_FLAME, true, false, x, y, z, 1, 0.2, 0.4, 0.2, 0.01);
				}
				case FROST -> level.sendParticles(ParticleTypes.SNOWFLAKE, true, false, x, center.getY() + HEIGHT - 1, z, 2, 1.5, 0.2, 1.5, 0.02);
				case LAB -> {
					level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, true, false, x, y, z, 1, 0.3, 0.3, 0.3, 0);
					level.sendParticles(ParticleTypes.GLOW, true, false, x, y, z, 1, 0.3, 0.3, 0.3, 0);
				}
			}
		}
	}
}
