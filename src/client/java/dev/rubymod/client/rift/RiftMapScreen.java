package dev.rubymod.client.rift;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The Rift map: the floors from the bottom up to the boss at the top, joined by dotted
 * paths. The rooms you can go to next glow; click one to vote. Everyone's votes show on
 * the rooms, and the most votes wins.
 */
public class RiftMapScreen extends Screen {
	private record Node(int id, int floor, int col, int cols, String type, String theme, List<Integer> next, String state, boolean burning) {
	}

	/** The act's boss: id, name, what it is. */
	private String bossName = "";
	private String bossAbout = "";

	private List<Node> nodes = new ArrayList<>();
	private final Map<Integer, Integer> votes = new HashMap<>();
	private final Map<Integer, int[]> boxes = new HashMap<>();
	private int myVote = -1;
	private final long opened = System.currentTimeMillis();

	RiftMapScreen(List<String> lines) {
		super(Component.literal("The Rift"));
		update(lines);
	}

	void update(List<String> lines) {
		List<Node> list = new ArrayList<>();
		votes.clear();
		for (String line : lines) {
			String[] f = line.split("[|]", -1);
			if (f[0].equals("boss") && f.length >= 4) {
				bossName = f[2];
				bossAbout = f[3];
				continue;
			}
			if (f[0].equals("votes")) {
				if (f.length > 1 && !f[1].isEmpty()) {
					for (String v : f[1].split(",")) {
						String[] kv = v.split(":");
						votes.put(Integer.parseInt(kv[0]), Integer.parseInt(kv[1]));
					}
				}
				continue;
			}
			if (f.length < 8) {
				continue;
			}
			List<Integer> next = new ArrayList<>();
			if (!f[6].isEmpty()) {
				for (String n : f[6].split(",")) {
					next.add(Integer.parseInt(n));
				}
			}
			list.add(new Node(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), f[4], f[5], next, f[7],
					f.length > 8 && f[8].contains("burning")));
		}
		nodes = list;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static int themeColor(String theme) {
		return switch (theme) {
			case "CRYSTAL" -> 0xFFC27BFF;
			case "FORGE" -> 0xFFFF7A2E;
			case "FROST" -> 0xFF8FE3FF;
			case "LAB" -> 0xFF6EE38A;
			case "FOUNDRY" -> 0xFFFF4040;
			case "SKYDOCK" -> 0xFF66E0FF;
			case "HEART" -> 0xFFFF3D6E;
			default -> 0xFFFF3D6E;
		};
	}

	private static String themeName(String theme) {
		return switch (theme) {
			case "CRYSTAL" -> "Crystal Caverns";
			case "FORGE" -> "Molten Forge";
			case "FROST" -> "Frozen Vault";
			case "LAB" -> "Overgrown Lab";
			case "FOUNDRY" -> "Mech Foundry";
			case "SKYDOCK" -> "The Sky Dock";
			case "HEART" -> "The Ruby Heart";
			default -> "";
		};
	}

	private static String icon(String type) {
		return switch (type) {
			case "ELITE" -> "minecraft:wither_skeleton_skull";
			case "TREASURE" -> "minecraft:chest";
			case "SHOP" -> "minecraft:emerald";
			case "EVENT" -> "minecraft:enchanted_book";
			case "REST" -> "rubymod:arr_rifle";
			case "BOSS" -> "minecraft:nether_star";
			case "TANK" -> "rubymod:tank";
			case "DEFEND" -> "minecraft:beacon";
			case "HOLD" -> "minecraft:target";
			case "SPRING" -> "minecraft:heart_of_the_sea";
			default -> "minecraft:iron_sword";
		};
	}

	private static String title(String type) {
		return switch (type) {
			case "ELITE" -> "Elite";
			case "TREASURE" -> "Treasure";
			case "SHOP" -> "Merchant";
			case "EVENT" -> "Mystery";
			case "REST" -> "Arsenal";
			case "BOSS" -> "BOSS";
			case "TANK" -> "Tank Assault";
			case "DEFEND" -> "Defend the Core";
			case "HOLD" -> "Hold the Zone";
			case "SPRING" -> "Ruby Spring";
			default -> "Fight";
		};
	}

	private static String about(String type) {
		return switch (type) {
			case "ELITE" -> "Tougher monsters and a huge elite with a twist. Better relics.";
			case "TREASURE" -> "A free relic, and one of the three is Legendary.";
			case "SHOP" -> "Spend the team's gold on relics (or new stock).";
			case "EVENT" -> "Something strange. A choice with a catch.";
			case "REST" -> "The Arsenal: pick a new weapon, and downed teammates get back up.";
			case "BOSS" -> "The end of this act, and something huge waiting there.";
			case "TANK" -> "Everyone in a tank, against enemy Rift Tanks.";
			case "DEFEND" -> "Keep the monsters off the ruby core until the last wave. Keep it whole for a better relic.";
			case "HOLD" -> "Stand in the ring of light to fill it; monsters inside stop it. Full: the room's cleared. Better relic.";
			case "SPRING" -> "A rest stop. Take one gift: heal, give a relic another star, or temper a weapon with a new power.";
			default -> "Monsters. Clear the room for gold and a relic.";
		};
	}

	private int[] pos(Node n, int floors) {
		int top = 34;
		int bottom = height - 66;
		int span = Math.min(width - 160, 300);
		int left = (width - span) / 2;
		double across = n.cols() == 1 ? 0.5 : n.col() / (double) (n.cols() - 1);
		int x = left + (int) (across * span);
		int y = bottom - (int) ((bottom - top) * (n.floor() / (double) Math.max(1, floors)));
		return new int[] {x, y};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		long now = System.currentTimeMillis();
		g.fillGradient(0, 0, width, height, 0xE0140410, 0xF0060208);
		// Drifting motes of light.
		for (int i = 0; i < 40; i++) {
			double s = (now / 40.0 + i * 97) % height;
			int mx = (int) ((i * 131 + Math.sin(now / 900.0 + i) * 20) % width + width) % width;
			int my = height - (int) s;
			g.fill(mx, my, mx + 1, my + 1, (i % 3 == 0 ? 0x90FF3D6E : 0x70C27BFF));
		}
		g.centeredText(font, "◆ THE RIFT ◆" + (bossName.isEmpty() ? "" : "   Boss: " + bossName.toUpperCase()), width / 2, 8, 0xFFFF3D6E);
		g.centeredText(font, "Click a glowing room to vote for it. The most votes wins.", width / 2, 20, 0xFFB8A8C8);
		// The three lanes, named at the bottom.
		{
			int span = Math.min(width - 160, 300);
			int left = (width - span) / 2;
			String[] lane = {"SAFE", "MIDDLE", "DANGER"};
			int[] laneColor = {0xFF6EE38A, 0xFFFFC02E, 0xFFFF5050};
			for (int c = 0; c < 3; c++) {
				int lx = left + c * span / 2;
				g.centeredText(font, lane[c], lx, height - 46, laneColor[c]);
			}
		}
		int floors = nodes.stream().mapToInt(Node::floor).max().orElse(1);
		Map<Integer, Node> byId = new HashMap<>();
		for (Node n : nodes) {
			byId.put(n.id(), n);
		}
		// Paths first.
		for (Node a : nodes) {
			int[] pa = pos(a, floors);
			for (int nid : a.next()) {
				Node b = byId.get(nid);
				if (b == null) {
					continue;
				}
				int[] pb = pos(b, floors);
				boolean taken = (a.state().equals("done") || a.state().equals("here")) && (b.state().equals("done") || b.state().equals("here"));
				boolean onward = a.state().equals("here") && b.state().equals("open") || a.state().equals("open") && false;
				int color = taken ? 0xFFFFC02E : onward ? (((now / 250) % 2 == 0) ? 0xFFFF3D6E : 0xFFC27BFF) : 0x60808090;
				double len = Math.hypot(pb[0] - pa[0], pb[1] - pa[1]);
				for (double d = 12; d < len - 12; d += 5) {
					int x = (int) (pa[0] + (pb[0] - pa[0]) * d / len);
					int y = (int) (pa[1] + (pb[1] - pa[1]) * d / len);
					g.fill(x - 1, y - 1, x + 1, y + 1, color);
				}
			}
		}
		// Rooms.
		boxes.clear();
		Node hovered = null;
		for (Node n : nodes) {
			int[] p = pos(n, floors);
			boolean boss = n.type().equals("BOSS");
			int r = boss ? 16 : 11;
			boolean open = n.state().equals("open");
			boolean here = n.state().equals("here");
			boolean done = n.state().equals("done");
			boolean hover = open && Math.abs(mouseX - p[0]) <= r && Math.abs(mouseY - p[1]) <= r;
			if (hover || Math.abs(mouseX - p[0]) <= r && Math.abs(mouseY - p[1]) <= r) {
				hovered = n;
			}
			int theme = themeColor(n.theme());
			float pulse = (float) (0.5 + 0.5 * Math.sin(now / 220.0 + n.id()));
			if (open) {
				int ring = r + 3 + (int) (pulse * 3);
				g.outline(p[0] - ring, p[1] - ring, ring * 2, ring * 2, (int) (120 + 135 * pulse) << 24 | theme & 0xFFFFFF);
			}
			if (here) {
				int ring = r + 2 + (int) ((now / 60) % 8);
				g.outline(p[0] - ring, p[1] - ring, ring * 2, ring * 2, (int) (255 - (now / 60 % 8) * 30) << 24 | 0xFFC02E);
			}
			if (n.burning() && !done) {
				int flick = (int) ((now / 90 + n.id()) % 3);
				int fire = flick == 0 ? 0xFFFF7A2E : flick == 1 ? 0xFFFFC02E : 0xFFFF3020;
				g.outline(p[0] - r - 3, p[1] - r - 3, r * 2 + 6, r * 2 + 6, fire);
				g.outline(p[0] - r - 2, p[1] - r - 2, r * 2 + 4, r * 2 + 4, 0xA0000000 | fire & 0xFFFFFF);
				for (int k = 0; k < 5; k++) {
					double rise = ((now / 30.0 + k * 37 + n.id() * 11) % 28);
					int fx = p[0] - r + (int) ((k * 53 + n.id() * 7) % (r * 2));
					int fy = p[1] - r - (int) rise;
					g.fill(fx, fy, fx + 2, fy + 2, (int) (255 * (1 - rise / 28)) << 24 | (k % 2 == 0 ? 0xFF7A2E : 0xFFC02E));
				}
			}
			int bg = done ? 0xE0302830 : here ? 0xF0503010 : open ? (hover ? 0xF0402040 : 0xE0281428) : 0xC0181018;
			g.fill(p[0] - r, p[1] - r, p[0] + r, p[1] + r, bg);
			g.outline(p[0] - r, p[1] - r, r * 2, r * 2, done ? 0xFF706070 : here ? 0xFFFFC02E : open ? theme | 0xFF000000 : 0xFF403840);
			g.fill(p[0] - r + 1, p[1] + r - 3, p[0] + r - 1, p[1] + r - 1, (open || here ? 0xFF000000 : 0x80000000) | theme & 0xFFFFFF);
			ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(icon(n.type()))));
			if (boss) {
				g.pose().pushMatrix();
				g.pose().translate(p[0] - 12, p[1] - 12);
				g.pose().scale(1.5F, 1.5F);
				g.item(stack, 0, 0);
				g.pose().popMatrix();
			} else {
				g.item(stack, p[0] - 8, p[1] - 9);
			}
			if (done) {
				g.text(font, "✔", p[0] + r - 7, p[1] - r + 1, 0xFF5BE37D, true);
			}
			int v = votes.getOrDefault(n.id(), 0);
			if (v > 0) {
				String vs = v + (v == 1 ? " vote" : " votes");
				g.fill(p[0] - font.width(vs) / 2 - 2, p[1] - r - 12, p[0] + font.width(vs) / 2 + 2, p[1] - r - 2, 0xE0FF3D6E);
				g.centeredText(font, vs, p[0], p[1] - r - 11, 0xFFFFFFFF);
			}
			if (myVote == n.id()) {
				g.outline(p[0] - r - 1, p[1] - r - 1, r * 2 + 2, r * 2 + 2, 0xFFFFFFFF);
			}
			if (open) {
				boxes.put(n.id(), new int[] {p[0] - r - 2, p[1] - r - 2, r * 2 + 4, r * 2 + 4});
			}
		}
		// Legend.
		int ly = height - 22;
		g.centeredText(font, "⚔ Fight   ⛟ Tank   ☠ Elite (in flames: Burning Elite)   ◈ Defend   ◎ Hold", width / 2, ly, 0xFF8A7A9A);
		g.centeredText(font, "❖ Treasure   ◆ Merchant   ? Mystery   ⚒ Arsenal   ♥ Spring   ★ Boss", width / 2, ly + 10, 0xFF8A7A9A);
		if (hovered != null) {
			List<Component> tip = new ArrayList<>();
			tip.add(Component.literal(hovered.type().equals("BOSS") && !bossName.isEmpty() ? "BOSS: " + bossName
					: hovered.burning() ? "BURNING ELITE" : title(hovered.type())).withColor(hovered.burning() ? 0xFF7A2E : themeColor(hovered.theme()) & 0xFFFFFF));
			if (!themeName(hovered.theme()).isEmpty()) {
				tip.add(Component.literal(themeName(hovered.theme())).withColor(0xA090B0));
			}
			tip.add(Component.literal(hovered.type().equals("BOSS") && !bossAbout.isEmpty() ? bossAbout
					: hovered.burning() ? "Elites wreathed in fire: tougher, and there's one more of them. Beat them for a Legendary relic and 100 gold."
					: about(hovered.type())).withColor(0xE0E0E0));
			String laneName = hovered.type().equals("BOSS") ? "" : hovered.col() == 0 ? "Safe lane" : hovered.col() == 1 ? "Middle lane" : "Danger lane";
			if (!laneName.isEmpty()) {
				tip.add(Component.literal(laneName).withColor(hovered.col() == 0 ? 0x6EE38A : hovered.col() == 1 ? 0xFFC02E : 0xFF5050));
			}
			if (hovered.state().equals("open")) {
				tip.add(Component.literal("Click to vote").withColor(0xFFC02E));
			}
			g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (Map.Entry<Integer, int[]> e : boxes.entrySet()) {
			int[] b = e.getValue();
			if (event.x() >= b[0] && event.x() < b[0] + b[2] && event.y() >= b[1] && event.y() < b[1] + b[3]) {
				myVote = e.getKey();
				ClientPlayNetworking.send(new MorePayloads.RiftChoice("vote", String.valueOf(e.getKey())));
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}
}
