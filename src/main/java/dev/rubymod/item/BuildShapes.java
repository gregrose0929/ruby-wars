package dev.rubymod.item;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

/** The shapes the Architect's Wand draws between two corners (shared by the server and the client preview). */
public final class BuildShapes {
	public enum Mode {
		LINE("Line"),
		WALL("Wall"),
		FLOOR("Floor"),
		BOX("Solid Box"),
		ROOM("Hollow Room");

		public final String title;

		Mode(String title) {
			this.title = title;
		}
	}

	public static final int MAX_BLOCKS = 1024;

	private BuildShapes() {
	}

	public static List<BlockPos> shape(Mode mode, BlockPos a, BlockPos b) {
		Set<BlockPos> out = new LinkedHashSet<>();
		int x0 = Math.min(a.getX(), b.getX());
		int x1 = Math.max(a.getX(), b.getX());
		int y0 = Math.min(a.getY(), b.getY());
		int y1 = Math.max(a.getY(), b.getY());
		int z0 = Math.min(a.getZ(), b.getZ());
		int z1 = Math.max(a.getZ(), b.getZ());
		switch (mode) {
			case LINE -> line(a, b, out);
			case WALL -> {
				// A straight wall from A to B (in plan), from A's height to B's.
				Set<BlockPos> path = new LinkedHashSet<>();
				line(new BlockPos(a.getX(), 0, a.getZ()), new BlockPos(b.getX(), 0, b.getZ()), path);
				for (BlockPos p : path) {
					for (int y = y0; y <= y1; y++) {
						out.add(new BlockPos(p.getX(), y, p.getZ()));
					}
				}
			}
			case FLOOR -> {
				for (int x = x0; x <= x1; x++) {
					for (int z = z0; z <= z1; z++) {
						out.add(new BlockPos(x, a.getY(), z));
					}
				}
			}
			case BOX, ROOM -> {
				for (int x = x0; x <= x1; x++) {
					for (int y = y0; y <= y1; y++) {
						for (int z = z0; z <= z1; z++) {
							boolean shell = x == x0 || x == x1 || y == y0 || y == y1 || z == z0 || z == z1;
							if (mode == Mode.BOX || shell) {
								out.add(new BlockPos(x, y, z));
							}
						}
					}
				}
			}
		}
		List<BlockPos> list = new ArrayList<>(out);
		return list.size() > MAX_BLOCKS ? list.subList(0, MAX_BLOCKS) : list;
	}

	/** A 3D line through block centers (each block once). */
	private static void line(BlockPos a, BlockPos b, Set<BlockPos> out) {
		int steps = Math.max(Math.abs(b.getX() - a.getX()), Math.max(Math.abs(b.getY() - a.getY()), Math.abs(b.getZ() - a.getZ())));
		for (int i = 0; i <= steps; i++) {
			double t = steps == 0 ? 0 : i / (double) steps;
			out.add(BlockPos.containing(a.getX() + 0.5 + (b.getX() - a.getX()) * t, a.getY() + 0.5 + (b.getY() - a.getY()) * t,
					a.getZ() + 0.5 + (b.getZ() - a.getZ()) * t));
		}
	}
}
