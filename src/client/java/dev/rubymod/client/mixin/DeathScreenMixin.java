package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.rubymod.client.ClientState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.DeathScreen;

/** The death screen tells you who got you, with what, and whether you'll be back (Heart respawn) or are out. */
@Mixin(DeathScreen.class)
public abstract class DeathScreenMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void rubymod$killInfo(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		var font = mc.font;
		int w = g.guiWidth();
		int y = 20;
		var info = ClientState.deathInfo;
		var kill = ClientState.lastDeath;
		if (info != null && System.currentTimeMillis() - ClientState.deathInfoAt < 15000) {
			String line = dev.rubymod.client.GameHud.deathLine(info);
			int tw = font.width(line) + 16;
			g.fill(w / 2 - tw / 2, y - 3, w / 2 + tw / 2, y + 10, 0xA0000000);
			g.centeredText(font, line, w / 2, y, 0xFFFF7A7A);
			y += 13;
		} else if (kill != null && System.currentTimeMillis() - ClientState.lastDeathAt < 15000) {
			String line = "Killed by " + kill.killer() + " with " + kill.weapon() + (kill.headshot() ? " (headshot)" : "");
			int tw = font.width(line) + 16;
			g.fill(w / 2 - tw / 2, y - 3, w / 2 + tw / 2, y + 10, 0xA0000000);
			g.centeredText(font, line, w / 2, y, 0xFFFF7A7A);
			y += 13;
		}
		if (ClientState.hud != null && ClientState.phase().isBattle() && ClientState.inRubyWarsWorld()) {
			// The same answer as the line under the timer (from just before you fell).
			var hud = ClientState.hud;
			boolean mate = hud.respawn() == dev.rubymod.net.Payloads.HudSync.RESPAWN_MATE;
			boolean out = hud.eliminated() || hud.respawn() == dev.rubymod.net.Payloads.HudSync.LAST_LIFE
					|| hud.respawn() == dev.rubymod.net.Payloads.HudSync.NO_RESPAWNS;
			String status = out ? "KNOCKED OUT - you can watch the rest of the battle (or press H for party games)"
					: mate ? "You'll respawn at a squadmate's Heart" : "You'll respawn at your Heart";
			g.centeredText(font, status, w / 2, y, out ? 0xFFFF4040 : 0xFF5BE37D);
			y += 12;
		}
		if (ClientState.map != null && ClientState.map.markers().stream().anyMatch(m -> m.kind() == 1)) {
			g.centeredText(font, "Your gear is in a War Grave where you fell (it's on your map)", w / 2, y, 0xFFFFD040);
		}
	}
}
