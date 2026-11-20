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

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(MorePayloads.Note.TYPE, (p, c) -> {
			Minecraft mc = Minecraft.getInstance();
			if (ClientSettings.toasts) {
				mc.gui.toastManager().addToast(new RubyToast(p.icon(), p.title(), p.body(), p.color()));
			} else if (mc.player != null) {
				// Toasts off: a quiet line over the hotbar instead.
				mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(p.title() + (p.body().isEmpty() ? "" : ": " + p.body())));
			}
		});
	}

	@Override
	public Visibility getWantedVisibility() {
		return visibility;
	}

	@Override
	public void update(ToastManager manager, long visibleTime) {
		visibility = visibleTime >= SHOW_MS * manager.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
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
