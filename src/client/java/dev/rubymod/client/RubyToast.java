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
	private static final int WIDTH = 176;
	private static final int HEIGHT = 32;
	private static final long SHOW_MS = 5000;

	private final ItemStack icon;
	private final String title;
	private final String body;
	private final int color;
	private Visibility visibility = Visibility.SHOW;

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
		return slot * HEIGHT + (map ? dev.rubymod.client.map.Minimap.SIZE + 28 : 0);
	}

	@Override
	public int width() {
		return WIDTH;
	}

	@Override
	public int height() {
		return HEIGHT;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, Font font, long visibleTime) {
		Ui.panel(g, 0, 0, WIDTH, HEIGHT);
		g.fill(0, 2, 2, HEIGHT - 2, color);
		if (!icon.isEmpty()) {
			g.item(icon, 8, 8);
		}
		int tx = icon.isEmpty() ? 10 : 30;
		int room = WIDTH - tx - 6;
		g.text(font, Ui.fit(title, room), tx, body.isEmpty() ? 12 : 7, color, false);
		if (!body.isEmpty()) {
			g.text(font, Ui.fit(body, room), tx, 18, Ui.DIM, false);
		}
	}
}
