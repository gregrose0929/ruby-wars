package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.RandomSource;

/**
 * One act of the Rift as a branching map, like a tree of doors: a few rooms per floor,
 * each linked to one or two rooms on the next floor, all paths ending at the boss. The
 * team votes which room to go into next; each room shows its type (and look) ahead of
 * time, so you plan your route: an elite for a better relic, the shop, a rest...
 */
public final class RiftMap {
	public enum Type {
		FIGHT("Fight", "minecraft:iron_sword"), ELITE("Elite", "minecraft:wither_skeleton_skull"), TREASURE("Treasure", "minecraft:chest"),
		SHOP("Merchant", "minecraft:emerald"), EVENT("Mystery", "minecraft:enchanted_book"), REST("Arsenal", "rubymod:arr_rifle"),
		TANK("Tank Assault", "rubymod:tank"), BOSS("Boss", "minecraft:nether_star"), DEFEND("Defend the Core", "minecraft:beacon"),
		HOLD("Hold the Zone", "minecraft:target"), SPRING("Ruby Spring", "minecraft:heart_of_the_sea");

		public final String title;
		public final String icon;

		Type(String title, String icon) {
			this.title = title;
			this.icon = icon;
		}
	}

	public static final class Node {
		public final int id;
		public final int floor;
		public final int col;
		public final int cols;
		public final Type type;
		public final RiftRoom.Theme theme;
		public final List<Integer> next = new ArrayList<>();
		/** A Burning Elite: twice as tough, and a Legendary relic for beating it. */
		public boolean burning;

		public Node(int id, int floor, int col, int cols, Type type, RiftRoom.Theme theme) {
			this.id = id;
			this.floor = floor;
			this.col = col;
			this.cols = cols;
			this.type = type;
			this.theme = theme;
		}

		/** Across the floor, 0..1 (so floors with 2 or 4 rooms line up). */
		double across() {
			return cols == 1 ? 0.5 : col / (double) (cols - 1);
		}
	}

	public static final int FLOORS = 7;
	public final List<Node> nodes = new ArrayList<>();
	public final List<Integer> visited = new ArrayList<>();

	public Node node(int id) {
		return id >= 0 && id < nodes.size() ? nodes.get(id) : null;
	}

	public List<Node> floor(int f) {
		return nodes.stream().filter(n -> n.floor == f).toList();
	}

	/** Where the team can go from where it is (the first floor before anything's been visited). */
	public List<Node> choices() {
		if (visited.isEmpty()) {
			return floor(0);
		}
		Node at = node(visited.get(visited.size() - 1));
		return at.next.stream().map(this::node).toList();
	}

	/** Which act this map is (1-3: each ends with a different boss). */
	public int act = 1;

	/** The three lanes: left plays it safe, the middle is a mix (and the treasure), the right is where the danger and the best loot are. */
	public static final int LANES = 3;

	/** Which boss waits at the top of this act (shown on the map from the start). */
	public RiftBosses.Boss boss = RiftBosses.Boss.JUGGERNAUT;

	public static RiftMap generate(RandomSource random, int act) {
		RiftMap map = new RiftMap();
		map.act = act;
		map.boss = RiftBosses.pick(act, random);
		RiftRoom.Theme[] themes = RiftRoom.Theme.normal();
		for (int f = 0; f < FLOORS; f++) {
			for (int c = 0; c < LANES; c++) {
				Type type = pick(random, f + (act - 1) * 2, c);
				// Fixed stops: the treasure halfway up the middle; the last floor before the boss is a Spring, the Arsenal and the Merchant.
				if (f == 3 && c == 1) {
					type = Type.TREASURE;
				}
				if (f == FLOORS - 1) {
					type = c == 0 ? Type.SPRING : c == 1 ? Type.REST : Type.SHOP;
				}
				map.nodes.add(new Node(map.nodes.size(), f, c, LANES, type, themes[random.nextInt(themes.length)]));
			}
		}
		// One Burning Elite an act: an elite on the right lane if there is one (else any elite, else a fight turned elite).
		List<Node> elites = map.nodes.stream().filter(n -> n.type == Type.ELITE && n.floor >= 2 && n.floor <= 5).toList();
		List<Node> right = elites.stream().filter(n -> n.col == LANES - 1).toList();
		Node burning = !right.isEmpty() ? right.get(random.nextInt(right.size())) : !elites.isEmpty() ? elites.get(random.nextInt(elites.size())) : null;
		if (burning == null) {
			Node old = map.floor(2 + random.nextInt(3)).get(LANES - 1);
			replace(map, old, Type.ELITE);
			burning = map.node(old.id);
		}
		burning.burning = true;
		map.nodes.add(new Node(map.nodes.size(), FLOORS, 1, LANES, Type.BOSS, map.boss.theme));
		// Paths: up each lane, plus a few diagonals into the next lane (never two that cross), so you can change lanes on the way.
		for (int f = 0; f < FLOORS; f++) {
			List<Node> from = map.floor(f);
			List<Node> to = map.floor(f + 1);
			if (to.size() == 1) {
				for (Node a : from) {
					a.next.add(to.get(0).id);
				}
				continue;
			}
			for (int c = 0; c < LANES; c++) {
				from.get(c).next.add(to.get(c).id);
			}
			for (int c = 0; c < LANES - 1; c++) {
				float roll = random.nextFloat();
				if (roll < 0.35F) {
					from.get(c).next.add(to.get(c + 1).id);
				} else if (roll < 0.7F) {
					from.get(c + 1).next.add(to.get(c).id);
				}
			}
		}
		return map;
	}

	private static void replace(RiftMap map, Node old, Type type) {
		Node n = new Node(old.id, old.floor, old.col, old.cols, type, old.theme);
		n.burning = old.burning;
		map.nodes.set(old.id, n);
	}

	/** A room for this floor and lane. */
	private static Type pick(RandomSource random, int floor, int lane) {
		if (floor == 0) {
			return Type.FIGHT;
		}
		int r = random.nextInt(100);
		if (lane == 0) {
			// Safe: mostly plain fights, with the Merchant, Mysteries and Springs along the way.
			return r < 45 ? Type.FIGHT : r < 52 ? Type.HOLD : r < 70 ? Type.EVENT : r < 84 && floor >= 2 ? Type.SHOP : r < 96 && floor >= 2 ? Type.SPRING
					: Type.FIGHT;
		} else if (lane == 1) {
			// The middle: a bit of everything.
			if (r < 8 && floor >= 2) {
				return Type.TANK;
			}
			return r < 36 ? Type.FIGHT : r < 44 ? Type.DEFEND : r < 52 ? Type.HOLD : r < 64 && floor >= 2 ? Type.ELITE : r < 78 ? Type.EVENT
					: r < 86 && floor >= 2 ? Type.SHOP : r < 92 && floor >= 3 ? Type.REST : Type.FIGHT;
		}
		// Danger: elites and tank assaults (better relics, more gold).
		if (floor < 2) {
			return r < 60 ? Type.FIGHT : Type.DEFEND;
		}
		return r < 40 ? Type.ELITE : r < 58 ? Type.TANK : r < 72 ? Type.DEFEND : r < 82 ? Type.FIGHT : r < 92 ? Type.EVENT : Type.TREASURE;
	}

	/** "boss|ID|name|about", then "id|floor|col|cols|TYPE|THEME|next,ids|state|flags" per room (state: done, here, open, locked; flags: burning). */
	public List<String> lines() {
		List<Integer> open = choices().stream().map(n -> n.id).toList();
		int here = visited.isEmpty() ? -1 : visited.get(visited.size() - 1);
		List<String> out = new ArrayList<>();
		out.add("boss|" + boss.name() + "|" + boss.title + "|" + boss.about);
		for (Node n : nodes) {
			String state = n.id == here ? "here" : visited.contains(n.id) ? "done" : open.contains(n.id) ? "open" : "locked";
			out.add(n.id + "|" + n.floor + "|" + n.col + "|" + n.cols + "|" + n.type.name() + "|" + n.theme.name() + "|"
					+ String.join(",", n.next.stream().map(String::valueOf).toList()) + "|" + state + "|" + (n.burning ? "burning" : ""));
		}
		return out;
	}
}
