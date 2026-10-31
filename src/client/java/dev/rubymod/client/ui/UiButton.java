package dev.rubymod.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** A regular button widget in the mod's style (for screens that use Minecraft's widgets). */
public class UiButton extends Button {
	private final int color;

	public UiButton(int x, int y, int w, int h, String label, int color, OnPress press) {
		super(x, y, w, h, Component.literal(label), press, DEFAULT_NARRATION);
		this.color = color;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		Ui.button(g, getMessage().getString(), getX(), getY(), getWidth(), getHeight(), mouseX, mouseY, color, active);
	}
}
