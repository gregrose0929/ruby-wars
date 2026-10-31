package dev.rubymod.client.rift;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.rubymod.client.ui.Ui;

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
	/** Rift Keys found this run. */
	private List<String> keys = List.of();
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
			if (f[0].equals("keys")) {
				keys = f.length > 1 && !f[1].isEmpty() ? List.of(f[1].split(",")) : List.of();
				continue;
			}
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
			case "GAUNTLET" -> "minecraft:netherite_sword";
			case "HEIST" -> "minecraft:gold_block";
			case "RESCUE" -> "minecraft:iron_bars";
			case "DARK" -> "minecraft:black_candle";
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
			case "GAUNTLET" -> "Gauntlet";
			case "HEIST" -> "Vault Heist";
			case "RESCUE" -> "Rescue";
			case "DARK" -> "Lights Out";
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
			case "GAUNTLET" -> "Survive 60 seconds of nonstop monsters. 2 gold a kill, and 25 kills earns a better relic.";
			case "HEIST" -> "Rubies all over a vault: grab them before it seals in 45 seconds, while guards pour in.";
			case "RESCUE" -> "A Guardian in a cage. Stand by it (with no monsters near) to free it: it fights beside you for the rest of the act.";
			case "DARK" -> "The lights are out and only the monsters glow. Clear it for a better relic.";
			default -> "Monsters. Clear the room for gold and a relic.";
		};
	}

	private int[] pos(Node n, int floors) {
		int top = 34;
		int bottom = height - 50;
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
		float in = Ui.ease(opened, 260);
		Ui.dim(g, width, height, in);
		// The map sits on one big panel.
		int span = Math.min(width - 160, 300);
		int mapX = (width - span) / 2 - 34;
		Ui.panel(g, mapX, 26, span + 68, height - 26 - 30);
		// Header: the emblem, the act's boss, the Rift Keys.
		Ui.emblem(g, "rift", 6, 5, 16);
		Ui.heading(g, "The Rift", 26, 9, Ui.TEXT, 1.0F, false);
		if (!bossName.isEmpty()) {
			Ui.icon(g, Ui.Icon.SKULL, 26 + font.width("The Rift") + 10, 8);
			Ui.text(g, "Boss: " + bossName, 26 + font.width("The Rift") + 22, 9, Ui.RED);
		}
		{
			String[] names = {"flame", "gold", "life"};
			int[] colors = {0xFFFF7A2E, 0xFFFFC02E, 0xFF6EE38A};
			int kx = width - 62;
			Ui.text(g, "Keys", kx - 26, 9, Ui.MUTED);
			for (int i = 0; i < 3; i++) {
				boolean got = keys.contains(names[i]);
				Ui.inset(g, kx + i * 18, 4, 16, 16);
				if (got) {
					g.fill(kx + i * 18 + 2, 6, kx + i * 18 + 14, 18, colors[i]);
					Ui.icon(g, Ui.Icon.STAR, kx + i * 18 + 3, 7);
				}
			}
			if (Ui.inside(mouseX, mouseY, kx - 30, 4, 84, 18)) {
				Ui.tooltip(g, List.of("Rift Keys: " + keys.size() + " of 3", "Flame: beat a Burning Elite.", "Gold: steal 100+ gold in a Vault Heist.",
						"Life: take it at a Ruby Spring (instead of a gift).", "All three open a secret fourth act."), mouseX, mouseY, width, height);
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
				boolean onward = a.state().equals("here") && b.state().equals("open");
				int color = taken ? Ui.GOLD : onward ? Ui.mix(Ui.RIFT, Ui.TEXT, Ui.pulse(900)) : 0x50808090;
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
			int r = boss ? 15 : 11;
			boolean open = n.state().equals("open");
			boolean here = n.state().equals("here");
			boolean done = n.state().equals("done");
			boolean over = Math.abs(mouseX - p[0]) <= r && Math.abs(mouseY - p[1]) <= r;
			if (over) {
				hovered = n;
			}
			int theme = themeColor(n.theme());
			if (open) {
				int ring = r + 2 + Math.round(Ui.pulse(1100) * 2);
				g.outline(p[0] - ring, p[1] - ring, ring * 2, ring * 2, Ui.alpha(theme, 0.5F + 0.5F * Ui.pulse(1100)));
			}
			if (n.burning() && !done) {
				int flick = (int) ((now / 90 + n.id()) % 3);
				int fire = flick == 0 ? 0xFFFF7A2E : flick == 1 ? 0xFFFFC02E : 0xFFFF3020;
				g.outline(p[0] - r - 3, p[1] - r - 3, r * 2 + 6, r * 2 + 6, fire);
				for (int k = 0; k < 5; k++) {
					double rise = ((now / 30.0 + k * 37 + n.id() * 11) % 28);
					int fx = p[0] - r + (int) ((k * 53 + n.id() * 7) % (r * 2));
					int fy = p[1] - r - (int) rise;
					g.fill(fx, fy, fx + 2, fy + 2, (int) (255 * (1 - rise / 28)) << 24 | (k % 2 == 0 ? 0xFF7A2E : 0xFFC02E));
				}
			}
			float hv = Ui.hover("room:" + n.id(), over && open);
			if (open || here) {
				Ui.card(g, p[0] - r, p[1] - r, r * 2, r * 2, here ? Ui.GOLD : theme, hv);
			} else {
				Ui.inset(g, p[0] - r, p[1] - r, r * 2, r * 2);
			}
			int lift = Math.round(hv * 2);
			ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(icon(n.type()))));
			if (boss) {
				g.pose().pushMatrix();
				g.pose().translate(p[0] - 12, p[1] - 11 - lift);
				g.pose().scale(1.5F, 1.5F);
				g.item(stack, 0, 0);
				g.pose().popMatrix();
			} else {
				g.item(stack, p[0] - 8, p[1] - 7 - lift);
			}
			if (done) {
				g.fill(p[0] - r + 1, p[1] - r + 1, p[0] + r - 1, p[1] + r - 1, 0x90101216);
				Ui.icon(g, Ui.Icon.CHECK, p[0] - 4, p[1] - 4);
			}
			int v = votes.getOrDefault(n.id(), 0);
			if (v > 0) {
				String vs = v + (v == 1 ? " vote" : " votes");
				Ui.tag(g, vs, p[0] - (font.width(vs) + 6) / 2, p[1] - r - 14, Ui.RIFT);
			}
			if (myVote == n.id()) {
				g.outline(p[0] - r - 1, p[1] - r - 1, r * 2 + 2, r * 2 + 2, Ui.TEXT);
			}
			if (open) {
				boxes.put(n.id(), new int[] {p[0] - r - 2, p[1] - r - 2, r * 2 + 4, r * 2 + 4});
			}
		}
		g.centeredText(font, "Click a glowing room to vote for it. Rooms in flames hold Burning Elites.", width / 2, height - 20, Ui.DIM);
		if (hovered != null) {
			List<String> tip = new ArrayList<>();
			tip.add(hovered.type().equals("BOSS") && !bossName.isEmpty() ? "Boss: " + bossName : hovered.burning() ? "Burning Elite" : title(hovered.type()));
			if (!themeName(hovered.theme()).isEmpty()) {
				tip.add(themeName(hovered.theme()));
			}
			tip.add(hovered.type().equals("BOSS") && !bossAbout.isEmpty() ? bossAbout
					: hovered.burning() ? "Elites wreathed in fire: tougher, and there's one more of them. Beat them for a Legendary relic and 100 gold."
					: about(hovered.type()));
			if (hovered.state().equals("open")) {
				tip.add("Click to vote.");
			}
			Ui.tooltip(g, tip, mouseX, mouseY, width, height);
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
