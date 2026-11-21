package dev.rubymod.client;

import dev.rubymod.client.ui.Ui;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Ruby Wars' toasts: goals done, things unlocked, your base being ready... a little card in the top corner with an
 * icon, a title and a line under it, in the game's own dark-and-ruby style. They stack and slide away by themselves.
 */
public class RubyToast implements Toast {
	/** The toast manager stacks toasts in 32-pixel slots: a toast is one slot, or two when its message wraps. */
	private static final int SLOT = 32;
	private static final int MIN_WIDTH = 176;
	private static final int MAX_WIDTH = 250;
	private static final long SHOW_MS = 5000;

	private final ItemStack icon;
	private final String title;
	private final String body;
	private final int color;
	private Visibility visibility = Visibility.SHOW;
	/** Sized to what it says: wider for a longer line, then a second line for the message (and a second slot). */
	private final int width;
	private final java.util.List<net.minecraft.util.FormattedCharSequence> lines;
	private final int cardHeight;

	public RubyToast(String icon, String title, String body, int color) {
		ItemStack stack;
		try {
			stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(icon)));
		} catch (RuntimeException e) {
			stack = ItemStack.EMPTY;
		}
		this.icon = stack;
		this.title = title;
		this.body = body;
		this.color = 0xFF000000 | color;
		Font font = Minecraft.getInstance().font;
		int tx = stack.isEmpty() ? 10 : 30;
		int need = tx + Math.max(font.width(title), font.width(body)) + 8;
		this.width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, need));
		int room = width - tx - 8;
		var split = body.isEmpty() ? java.util.List.<net.minecraft.util.FormattedCharSequence>of()
				: font.split(net.minecraft.network.chat.Component.literal(body), room);
		this.lines = split.size() > 2 ? split.subList(0, 2) : split;
		this.cardHeight = lines.size() > 1 ? 43 : SLOT;
	}

	/** Notes that came in together (shown as one where they can be), and held while a menu is open. */
	private static final java.util.List<MorePayloads.Note> WAITING = new java.util.ArrayList<>();
	private static long lastIn;
	/** How long to wait for more to come with the first (goals finished by the same thing arrive together). */
	private static final long GATHER_MS = 600;

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.Note.TYPE, (p, c) -> {
			Minecraft mc = Minecraft.getInstance();
			if (ClientSettings.toasts) {
				WAITING.add(p);
				lastIn = System.currentTimeMillis();
			} else if (mc.player != null) {
				// Toasts off: a quiet line over the hotbar instead.
				mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(p.title() + (p.body().isEmpty() ? "" : ": " + p.body())));
			}
		});
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(RubyToast::tick);
	}

	/** Show what's waiting once nothing more has come for a moment, and nothing's open on screen (chat aside). */
	private static void tick(Minecraft mc) {
		if (WAITING.isEmpty() || System.currentTimeMillis() - lastIn < GATHER_MS || GameHud.menuOpen()) {
			return;
		}
		java.util.List<MorePayloads.Note> goals = new java.util.ArrayList<>();
		for (MorePayloads.Note n : WAITING) {
			if (n.title().startsWith("Goal done!")) {
				goals.add(n);
			}
		}
		if (goals.size() >= 2) {
			// "4 goals done! +5 stars (J)": the goals by name underneath.
			int stars = 0;
			java.util.List<String> names = new java.util.ArrayList<>();
			for (MorePayloads.Note n : goals) {
				stars += (int) n.title().chars().filter(ch -> ch == '\u2605').count();
				names.add(n.body());
			}
			mc.gui.toastManager().addToast(new RubyToast("minecraft:nether_star", goals.size() + " goals done! +" + stars + (stars == 1 ? " star" : " stars") + " (J)",
					String.join(", ", names), goals.get(0).color()));
		}
		for (MorePayloads.Note n : WAITING) {
			if (goals.size() < 2 || !goals.contains(n)) {
				mc.gui.toastManager().addToast(new RubyToast(n.icon(), n.title(), n.body(), n.color()));
			}
		}
		WAITING.clear();
	}

	@Override
	public Visibility getWantedVisibility() {
		return visibility;
	}

	/** Time spent out of sight behind an open menu (it doesn't count toward its five seconds). */
	private long hidden;
	private long hiddenFrom = -1;

	@Override
	public void update(ToastManager manager, long visibleTime) {
		if (GameHud.menuOpen()) {
			if (hiddenFrom < 0) {
				hiddenFrom = visibleTime;
			}
		} else if (hiddenFrom >= 0) {
			hidden += visibleTime - hiddenFrom;
			hiddenFrom = -1;
		}
		long shown = visibleTime - hidden - (hiddenFrom >= 0 ? visibleTime - hiddenFrom : 0);
		visibility = shown >= SHOW_MS * manager.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
	}

	/** Below the minimap (when it's showing), not on top of it. */
	@Override
	public float yPos(int slot) {
		boolean map = dev.rubymod.client.map.Minimap.enabled && ClientState.inRubyWarsWorld();
		return HudScale.toGui(slot * SLOT + (map ? dev.rubymod.client.map.Minimap.SIZE + 20 : 0));
	}

	@Override
	public int width() {
		return HudScale.toGui(width);
	}

	@Override
	public int height() {
		return cardHeight > SLOT ? SLOT * 2 : SLOT;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, Font font, long visibleTime) {
		if (GameHud.menuOpen()) {
			return; // out of sight while a menu's open; it carries on when it closes
		}
		HudScale.push(g);
		try {
			drawCard(g, font);
		} finally {
			HudScale.pop(g);
		}
	}

	private void drawCard(GuiGraphicsExtractor g, Font font) {
		int h = cardHeight;
		Ui.panel(g, 0, 0, width, h);
		g.fill(2, 2, 4, h - 2, color);
		if (!icon.isEmpty()) {
			g.item(icon, 9, (h - 16) / 2);
		}
		int tx = icon.isEmpty() ? 10 : 30;
		int room = width - tx - 8;
		g.text(font, Ui.fit(title, room), tx, lines.isEmpty() ? 12 : 7, color, false);
		int ly = 18;
		for (var line : lines) {
			g.text(font, line, tx, ly, Ui.DIM, false);
			ly += 10;
		}
	}
}
