package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.rubymod.client.ui.Ui;
import dev.rubymod.industry.MachineBlockEntity;
import dev.rubymod.industry.MachineKind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * My Base (from the Ruby Wars menu): your base's power (how full, what makes and uses it, how long it lasts) and
 * every machine you own, the stopped ones first with what they need.
 */
public class BaseScreen extends Screen {
	private final long opened = System.currentTimeMillis();
	private int scroll;
	private int scrollMax;

	public BaseScreen() {
		super(Component.literal("My Base"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** Short status words for the list. */
	public static String status(MachineKind kind, int status) {
		return switch (status) {
			case MachineBlockEntity.WORKING -> "Working";
			case MachineBlockEntity.NO_POWER -> "No power";
			case MachineBlockEntity.OUTPUT_FULL -> kind.generator() ? "Grid full" : "Full: empty it, or add a belt";
			case MachineBlockEntity.NO_INPUT -> switch (kind) {
				case COMBUSTION_GENERATOR -> "Needs fuel";
				case CONSTRUCTOR -> "Needs a Blueprint or blocks";
				default -> "Waiting for something to work on";
			};
			case MachineBlockEntity.NIGHT -> "Resting (no sun)";
			default -> "Idle";
		};
	}

	private static int color(ClientState.BaseMachine m) {
		if (m.stopped()) {
			return m.status() == MachineBlockEntity.NO_POWER ? Ui.RED : Ui.AMBER;
		}
		return m.status() == MachineBlockEntity.WORKING ? Ui.GREEN : Ui.MUTED;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		Ui.dim(g, width, height, Ui.ease(opened, 180));
		var font = this.font;
		int w = Math.min(340, width - 16);
		int h = Math.min(250, height - 16);
		int x = (width - w) / 2;
		int y = (height - h) / 2;
		Ui.panel(g, x, y, w, h);
		g.text(font, "My Base", x + 10, y + 8, Ui.TEXT, true);
		var e = ClientState.economy;
		int ty = y + 22;

		// Power.
		if (e != null) {
			long cap = Math.max(1, e.capacity());
			String nums = String.format(Locale.ROOT, "%,d / %,d charge", e.charge(), cap);
			Ui.icon(g, Ui.Icon.BOLT, x + 10, ty - 1);
			int barW = w - 20 - 12 - font.width(nums) - 6;
			Ui.bar(g, x + 22, ty + 1, barW, 6, (float) Math.min(1.0, e.charge() / (double) cap), Ui.GOLD);
			g.text(font, nums, x + w - 10 - font.width(nums), ty, Ui.DIM, false);
			ty += 12;
			String rates = String.format(Locale.ROOT, "+%,d/s made   -%,d/s used", e.produced(), e.consumed());
			g.text(font, rates, x + 10, ty, Ui.MUTED, false);
			String fc = PowerText.forecast(e.charge(), cap, e.produced(), e.consumed());
			g.text(font, Ui.fit(fc, w - 30 - font.width(rates)), x + w - 10 - Math.min(font.width(fc), w - 30 - font.width(rates)), ty,
					e.produced() >= e.consumed() ? Ui.GREEN : Ui.AMBER, false);
			ty += 11;
			String night = PowerText.night(e.charge(), e.produced(), e.consumed());
			if (!night.isEmpty()) {
				g.text(font, Ui.fit(night, w - 20), x + 10, ty, Ui.MUTED, false);
				ty += 11;
			}
			List<String> parts = PowerText.breakdown();
			if (!parts.isEmpty()) {
				small(g, Ui.fit(String.join("   ", parts), (int) ((w - 20) / 0.75F)), x + 10, ty, Ui.MUTED);
				ty += 9;
			}
		}
		// What your machines have made, and what's in your silos.
		ty = strip(g, "Made this season", ClientState.made, x, ty, w, mouseX, mouseY);
		ty = strip(g, "In your silos", ClientState.silos, x, ty, w, mouseX, mouseY);
		ty += 3;
		g.fill(x + 8, ty, x + w - 8, ty + 1, 0xFF2E3440);
		ty += 5;

		// Machines: stopped ones first.
		List<ClientState.BaseMachine> list = new ArrayList<>(ClientState.base);
		list.sort((a, b) -> Boolean.compare(b.stopped(), a.stopped()));
		int top = ty;
		int bottom = y + h - 18;
		int rowH = 22;
		scrollMax = Math.max(0, list.size() * rowH - (bottom - top));
		scroll = Math.min(scroll, scrollMax);
		if (list.isEmpty()) {
			g.centeredText(font, "No machines yet", x + w / 2, top + 12, Ui.DIM);
			g.centeredText(font, Ui.fit("Buy a Ruby Drill and some power at your Armory", w - 20), x + w / 2, top + 26, Ui.MUTED);
		}
		var me = Minecraft.getInstance().player;
		String here = me == null ? "" : dev.rubymod.game.Rules.dim(me.level());
		g.enableScissor(x + 4, top, x + w - 4, bottom);
		int ry = top - scroll;
		for (ClientState.BaseMachine m : list) {
			if (ry + rowH > top && ry < bottom) {
				var item = BuiltInRegistries.ITEM.getValue(Identifier.parse("rubymod:" + m.kind().name().toLowerCase(Locale.ROOT)));
				g.item(new ItemStack(item), x + 10, ry + 2);
				String name = m.kind().title + " " + MachineKind.TIER_NAMES[Math.max(0, Math.min(4, m.tier() - 1))];
				g.text(font, name, x + 30, ry + 2, Ui.TEXT, false);
				String where;
				if (me != null && m.dim().equals(here)) {
					int dist = (int) Math.round(Math.sqrt(me.blockPosition().distSqr(m.pos())));
					where = m.pos().getX() + ", " + m.pos().getY() + ", " + m.pos().getZ() + "  (" + dist + "m)";
				} else {
					where = m.pos().getX() + ", " + m.pos().getY() + ", " + m.pos().getZ();
				}
				small(g, where, x + w - 10 - Math.round(font.width(where) * 0.75F), ry + 3, Ui.MUTED);
				String line = status(m.kind(), m.status());
				if (m.kind() == MachineKind.RUBY_DRILL && m.loads() > 0) {
					line += String.format(Locale.ROOT, "   dug up %,d loads, %,d %s", m.loads(), m.rubies(), m.rubies() == 1 ? "ruby" : "rubies");
				}
				if (!m.shared()) {
					line += "   (on its own power)";
				}
				if (me != null && !m.owner().equals(me.getName().getString())) {
					line += "   " + m.owner() + "'s";
				}
				small(g, Ui.fit(line, (int) ((w - 40) / 0.75F)), x + 30, ry + 12, color(m));
			}
			ry += rowH;
		}
		g.disableScissor();
		String foot = scrollMax > 0 ? (scroll < scrollMax ? "Scroll for more" : "Scroll back up")
				: "All your machines share your power";
		g.centeredText(font, Ui.fit(foot, w - 20), x + w / 2, y + h - 13, Ui.MUTED);
	}

	/** A row of item icons with counts (nothing at all when the list is empty). Returns the next y. */
	private int strip(GuiGraphicsExtractor g, String label, List<java.util.Map.Entry<String, Long>> list, int x, int ty, int w, int mouseX, int mouseY) {
		if (list.isEmpty()) {
			return ty;
		}
		ty += 2;
		small(g, label, x + 10, ty + 5, Ui.MUTED);
		int cx = x + 10 + Math.round(font.width(label) * 0.75F) + 8;
		for (var e : list) {
			String count = compact(e.getValue());
			int cellW = 18 + Math.round(font.width(count) * 0.75F) + 6;
			if (cx + cellW > x + w - 8) {
				break;
			}
			var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(e.getKey()));
			ItemStack stack = new ItemStack(item);
			g.item(stack, cx, ty);
			small(g, count, cx + 18, ty + 5, Ui.TEXT);
			if (Ui.inside(mouseX, mouseY, cx, ty, cellW, 16)) {
				g.setComponentTooltipForNextFrame(font, List.of(Component.literal(String.format(Locale.ROOT, "%s: %,d", stack.getHoverName().getString(),
						e.getValue()))), mouseX, mouseY);
			}
			cx += cellW;
		}
		return ty + 18;
	}

	/** 950, 12.4k, 3.1M. */
	private static String compact(long n) {
		if (n < 10_000) {
			return String.format(Locale.ROOT, "%,d", n);
		}
		if (n < 1_000_000) {
			return String.format(Locale.ROOT, "%.1fk", n / 1000.0);
		}
		return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
	}

	private void small(GuiGraphicsExtractor g, String s, int x, int y, int color) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(0.75F, 0.75F);
		g.text(font, s, 0, 0, color, false);
		g.pose().popMatrix();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollMax > 0) {
			scroll = Math.max(0, Math.min(scrollMax, scroll - (int) (scrollY * 22)));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}
}
