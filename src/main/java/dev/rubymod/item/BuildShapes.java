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
		ROOM("Hollow Room"),
		TOWER("Round Tower"),
		DOME("Dome"),
		STAIRS("Staircase");

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
			case TOWER -> {
				// A round wall (an oval in a long box) on the corners' footprint, from one height to the other.
				for (int x = x0; x <= x1; x++) {
					for (int z = z0; z <= z1; z++) {
						if (rim(x, z, x0, x1, z0, z1)) {
							for (int y = y0; y <= y1; y++) {
								out.add(new BlockPos(x, y, z));
							}
						}
					}
				}
			}
			case DOME -> {
				// Half an egg on the footprint, as tall as the corners are apart (or as round as it is wide if they're level).
				double rx = (x1 - x0) / 2.0 + 0.5;
				double rz = (z1 - z0) / 2.0 + 0.5;
				double ry = y1 > y0 ? y1 - y0 + 0.5 : Math.min(rx, rz);
				double cx = (x0 + x1) / 2.0;
				double cz = (z0 + z1) / 2.0;
				for (int x = x0; x <= x1; x++) {
					for (int z = z0; z <= z1; z++) {
						for (int dy = 0; dy < ry; dy++) {
							if (inEgg(x, dy, z, cx, cz, rx, ry, rz) && (!inEgg(x + 1, dy, z, cx, cz, rx, ry, rz) || !inEgg(x - 1, dy, z, cx, cz, rx, ry, rz)
									|| !inEgg(x, dy + 1, z, cx, cz, rx, ry, rz) || !inEgg(x, dy, z + 1, cx, cz, rx, ry, rz)
									|| !inEgg(x, dy, z - 1, cx, cz, rx, ry, rz))) {
								out.add(new BlockPos(x, y0 + dy, z));
							}
						}
					}
				}
			}
			case STAIRS -> {
				// Steps up (or down) from A toward B along the longer way, as wide as the corners are apart the other way.
				boolean alongX = x1 - x0 >= z1 - z0;
				int length = alongX ? x1 - x0 : z1 - z0;
				int dir = alongX ? Integer.signum(b.getX() - a.getX()) : Integer.signum(b.getZ() - a.getZ());
				int up = b.getY() >= a.getY() ? 1 : -1;
				int rise = b.getY() == a.getY() ? length : Math.abs(b.getY() - a.getY());
				for (int i = 0; i <= length; i++) {
					int y = a.getY() + up * Math.min(i, rise);
					for (int w = alongX ? z0 : x0; w <= (alongX ? z1 : x1); w++) {
						int along = (alongX ? a.getX() : a.getZ()) + dir * i;
						out.add(alongX ? new BlockPos(along, y, w) : new BlockPos(w, y, along));
					}
				}
			}
		}
		List<BlockPos> list = new ArrayList<>(out);
		return list.size() > MAX_BLOCKS ? list.subList(0, MAX_BLOCKS) : list;
	}

	/** Inside the oval that fits the footprint. */
	private static boolean inOval(int x, int z, int x0, int x1, int z0, int z1) {
		double rx = (x1 - x0) / 2.0 + 0.5;
		double rz = (z1 - z0) / 2.0 + 0.5;
		double dx = (x - (x0 + x1) / 2.0) / rx;
		double dz = (z - (z0 + z1) / 2.0) / rz;
		return dx * dx + dz * dz <= 1.0;
	}

	/** On the oval's edge: inside it, with a side neighbour outside. */
	private static boolean rim(int x, int z, int x0, int x1, int z0, int z1) {
		return inOval(x, z, x0, x1, z0, z1) && (!inOval(x + 1, z, x0, x1, z0, z1) || !inOval(x - 1, z, x0, x1, z0, z1)
				|| !inOval(x, z + 1, x0, x1, z0, z1) || !inOval(x, z - 1, x0, x1, z0, z1));
	}

	private static boolean inEgg(int x, int dy, int z, double cx, double cz, double rx, double ry, double rz) {
		if (dy < 0) {
			return true; // the ground under it closes it off
		}
		double a = (x - cx) / rx;
		double b = dy / ry;
		double c = (z - cz) / rz;
		return a * a + b * b + c * c <= 1.0;
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
