package dev.rubymod.world;

/**
 * The Ruby Wars map, as plain math: the same island every time (no world seed), so players
 * can learn it. Centred on 0,0:
 *
 * <pre>
 *   ocean ........................................ (beyond ~760 blocks)
 *     beach, lowlands
 *       four home corners (one per player or squad), each a flat meadow ~470 blocks out
 *       on a diagonal: Green Hills (NE), Pine Woods (NW), Red Desert (SW), Snow Plateau (SE)
 *         a ring of mountains ~280 out, with a pass from each home toward the middle
 *           the Ruby Crater in the middle: a rim and a bowl full of ruby ore (spawn)
 *   four rivers run out from the crater along the compass lines, between the corners
 * </pre>
 *
 * Every corner is the same land turned 90 degrees, so nobody gets a better start; only the
 * look differs. The rivers hide the seams where the turned copies meet.
 */
public final class WarzoneTerrain {
	public static final int SEA = 62;
	/** World border size (blocks across) for this map. */
	public static final int BORDER = 1700;
	public static final int THEME_GREEN = 0;
	public static final int THEME_PINE = 1;
	public static final int THEME_DESERT = 2;
	public static final int THEME_SNOW = 3;

	public enum Zone { OCEAN, BEACH, RIVER, CRATER, MOUNTAIN, HOME, LAND }

	/** What's at one column: surface height, zone and theme (the corner's look). */
	public record Column(int height, Zone zone, int theme) {
	}

	private static final long SEED = 0x52554259L; // "RUBY": fixed, so the map never changes
	private static final double HOME_R = 470;

	private WarzoneTerrain() {
	}

	/** Which corner (0..3, counter-clockwise from +x/+z) a point is in. */
	public static int quadrant(double x, double z) {
		double a = Math.atan2(z, x);
		if (a < 0) {
			a += Math.PI * 2;
		}
		return Math.min(3, (int) (a / (Math.PI / 2)));
	}

	public static Column column(int bx, int bz) {
		double x = bx + 0.5;
		double z = bz + 0.5;
		int k = quadrant(x, z);
		// Turn the point back into the first corner: every corner shares this land.
		double cx = x;
		double cz = z;
		for (int i = 0; i < k; i++) {
			double t = cx;
			cx = cz;
			cz = -t;
		}
		double r = Math.hypot(x, z);
		double axis = Math.min(Math.abs(x), Math.abs(z)); // distance to the nearest compass line

		// Rolling hills, from the turned point (fair) ...
		double hills = fbm(cx / 190.0, cz / 190.0, 4) * 13.0 + fbm(cx / 55.0, cz / 55.0, 2) * 3.5;
		// ... blended near the compass lines into a shape that only depends on distance (no seams).
		double radial = fbm(r / 190.0, 7.3, 3) * 10.0;
		double seam = smooth(16, 60, axis);
		double h = 67 + lerp(radial, hills, seam);

		// The mountain ring, with a pass along each diagonal (home to crater).
		double ang = Math.atan2(cz, cx);
		double pass = smooth(0.04, 0.42, Math.abs(ang - Math.PI / 4));
		double ridge = 1.0 - Math.abs(noise(cx / 90.0, cz / 90.0));
		double ringR = 285 + 30 * noise(cx / 140.0 + 40, cz / 140.0);
		double ring = Math.exp(-Math.pow((r - ringR) / 70.0, 2)) * (0.8 + 0.3 * fbm(cx / 80.0 - 20, cz / 80.0, 2));
		double mountains = Math.max(0, ring) * (24 + 58 * ridge * ridge) * pass * smooth(10, 90, axis);
		h += mountains;

		// Home meadows: flat ground to build on.
		double home = Math.hypot(cx - HOME_R / Math.sqrt(2), cz - HOME_R / Math.sqrt(2));
		double flat = smooth(135, 75, home);
		h = lerp(h, 69, flat * 0.9);

		// The Ruby Crater: a raised rim and a bowl.
		double rim = 16 * Math.exp(-Math.pow((r - 118) / 26.0, 2));
		double bowl = smooth(105, 35, r);
		h = lerp(h, 66, smooth(170, 120, r)) + rim;
		h = lerp(h, 64, bowl);

		// Rivers along the compass lines, from the crater rim to the sea (shallow enough to wade).
		double river = smooth(13, 4, riverDistance(x, z)) * smooth(130, 160, r);
		boolean ford = Math.abs(r - 300) < 18 || Math.abs(r - 520) < 18;
		h = lerp(h, ford ? 61 : 59.5, river);

		// The coast: the island ends in beaches, then ocean.
		double coast = 735 + 28 * fbm(cx / 120.0, cz / 120.0, 3);
		double land = smooth(coast + 45, coast - 25, r);
		double seaFloor = Math.max(38, 60 - (r - coast) * 0.12);
		h = lerp(seaFloor, h, land);

		int height = (int) Math.floor(h);
		Zone zone;
		if (land < 0.5 && height < SEA) {
			zone = Zone.OCEAN;
		} else if (height <= SEA + 2 && r > coast - 60) {
			zone = Zone.BEACH;
		} else if (river > 0.5) {
			zone = Zone.RIVER;
		} else if (r < 150) {
			zone = Zone.CRATER;
		} else if (mountains > 18 && height > 88) {
			zone = Zone.MOUNTAIN;
		} else if (flat > 0.5) {
			zone = Zone.HOME;
		} else {
			zone = Zone.LAND;
		}
		return new Column(height, zone, k);
	}

	/** How far a point is from the nearest river. The four rivers wind out along the compass lines, all the same shape. */
	private static double riverDistance(double x, double z) {
		double best = Double.MAX_VALUE;
		double u = x;
		double v = z;
		for (int i = 0; i < 4; i++) {
			if (u > 0) {
				double wind = 11 * noise(u / 85.0, 5.5) * smooth(130, 210, u);
				best = Math.min(best, Math.abs(v - wind));
			}
			double t = u; // turn 90 degrees to the next river
			u = v;
			v = -t;
		}
		return best;
	}

	/** True where ruby ore pokes up through the crater floor (rich veins close to the surface). */
	public static boolean craterRuby(int x, int y, int z, int surface) {
		double r = Math.hypot(x, z);
		if (r > 110 || y < surface - 14 || y > surface - 2) {
			return false;
		}
		return hash3(x, y, z) < 0.035 * smooth(110, 30, r) + 0.01;
	}

	// ----- noise -----

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	/** 0 at edge0, 1 at edge1 (either order), smooth in between. */
	private static double smooth(double edge0, double edge1, double v) {
		double t = Math.max(0, Math.min(1, (v - edge0) / (edge1 - edge0)));
		return t * t * (3 - 2 * t);
	}

	private static double hash(long x, long z) {
		long h = SEED ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
		h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 29)) * 0x94D049BB133111EBL;
		h ^= h >>> 32;
		return (h & 0xFFFFFFL) / (double) 0x1000000L;
	}

	public static double hash3(int x, int y, int z) {
		return hash(x * 73856093L ^ y * 19349663L, z * 83492791L + y);
	}

	/** Smooth value noise in -1..1. */
	private static double noise(double x, double z) {
		long x0 = (long) Math.floor(x);
		long z0 = (long) Math.floor(z);
		double fx = x - x0;
		double fz = z - z0;
		double ux = fx * fx * (3 - 2 * fx);
		double uz = fz * fz * (3 - 2 * fz);
		double a = hash(x0, z0);
		double b = hash(x0 + 1, z0);
		double c = hash(x0, z0 + 1);
		double d = hash(x0 + 1, z0 + 1);
		return (lerp(lerp(a, b, ux), lerp(c, d, ux), uz)) * 2 - 1;
	}

	private static double fbm(double x, double z, int octaves) {
		double sum = 0;
		double amp = 1;
		double norm = 0;
		for (int i = 0; i < octaves; i++) {
			sum += noise(x, z) * amp;
			norm += amp;
			amp *= 0.5;
			x = x * 2.03 + 17.1;
			z = z * 2.03 - 9.7;
		}
		return sum / norm;
	}
}
