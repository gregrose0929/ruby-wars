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
		SHOP("Merchant", "minecraft:emerald"), EVENT("Mystery", "minecraft:enchanted_book"), REST("Campfire", "minecraft:campfire"),
		BOSS("Boss", "minecraft:nether_star");

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

		Node(int id, int floor, int col, int cols, Type type, RiftRoom.Theme theme) {
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

	public static RiftMap generate(RandomSource random) {
		RiftMap map = new RiftMap();
		RiftRoom.Theme[] themes = RiftRoom.Theme.normal();
		for (int f = 0; f < FLOORS; f++) {
			int cols = f == 0 ? 2 : 2 + random.nextInt(3);
			for (int c = 0; c < cols; c++) {
				Type type = pick(random, f);
				map.nodes.add(new Node(map.nodes.size(), f, c, cols, type, themes[random.nextInt(themes.length)]));
			}
			// Some floors always have something: treasure halfway, a campfire before the boss.
			List<Node> here = map.floor(f);
			if (f == 3 && here.stream().noneMatch(n -> n.type == Type.TREASURE)) {
				replace(map, here.get(random.nextInt(here.size())), Type.TREASURE);
			}
			if (f == FLOORS - 1 && map.floor(f).stream().noneMatch(n -> n.type == Type.REST)) {
				replace(map, map.floor(f).get(random.nextInt(map.floor(f).size())), Type.REST);
			}
		}
		map.nodes.add(new Node(map.nodes.size(), FLOORS, 0, 1, Type.BOSS, RiftRoom.Theme.FOUNDRY));
		// Links: each room to the nearest one or two on the next floor; every room reachable.
		for (int f = 0; f < FLOORS; f++) {
			List<Node> from = map.floor(f);
			List<Node> to = map.floor(f + 1);
			for (Node a : from) {
				Node best = null;
				for (Node b : to) {
					if (best == null || Math.abs(b.across() - a.across()) < Math.abs(best.across() - a.across())) {
						best = b;
					}
				}
				a.next.add(best.id);
				for (Node b : to) {
					if (b != best && Math.abs(b.across() - a.across()) < 0.45 && random.nextFloat() < 0.6 && a.next.size() < 2) {
						a.next.add(b.id);
					}
				}
			}
			for (Node b : to) {
				if (from.stream().noneMatch(a -> a.next.contains(b.id))) {
					Node nearest = null;
					for (Node a : from) {
						if (nearest == null || Math.abs(b.across() - a.across()) < Math.abs(b.across() - nearest.across())) {
							nearest = a;
						}
					}
					nearest.next.add(b.id);
				}
			}
		}
		return map;
	}

	private static void replace(RiftMap map, Node old, Type type) {
		Node n = new Node(old.id, old.floor, old.col, old.cols, type, old.theme);
		map.nodes.set(old.id, n);
	}

	private static Type pick(RandomSource random, int floor) {
		if (floor == 0) {
			return Type.FIGHT;
		}
		int r = random.nextInt(100);
		if (r < 44) {
			return Type.FIGHT;
		} else if (r < 60 && floor >= 2) {
			return Type.ELITE;
		} else if (r < 72) {
			return Type.EVENT;
		} else if (r < 83 && floor >= 2) {
			return Type.SHOP;
		} else if (r < 91 && floor >= 3) {
			return Type.REST;
		} else if (r < 95) {
			return Type.TREASURE;
		}
		return Type.FIGHT;
	}

	/** "id|floor|col|cols|TYPE|THEME|next,ids|state" per room (state: done, here, open, locked). */
	public List<String> lines() {
		List<Integer> open = choices().stream().map(n -> n.id).toList();
		int here = visited.isEmpty() ? -1 : visited.get(visited.size() - 1);
		List<String> out = new ArrayList<>();
		for (Node n : nodes) {
			String state = n.id == here ? "here" : visited.contains(n.id) ? "done" : open.contains(n.id) ? "open" : "locked";
			out.add(n.id + "|" + n.floor + "|" + n.col + "|" + n.cols + "|" + n.type.name() + "|" + n.theme.name() + "|"
					+ String.join(",", n.next.stream().map(String::valueOf).toList()) + "|" + state);
		}
		return out;
	}
}
