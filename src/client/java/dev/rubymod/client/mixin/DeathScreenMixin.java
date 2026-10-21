package dev.rubymod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.rubymod.client.ClientState;
import dev.rubymod.game.Phase;

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
		int y = 34;
		var kill = ClientState.lastDeath;
		if (kill != null && System.currentTimeMillis() - ClientState.lastDeathAt < 15000) {
			String line = "Killed by " + kill.killer() + " with " + kill.weapon() + (kill.headshot() ? " (headshot)" : "");
			int tw = font.width(line) + 16;
			g.fill(w / 2 - tw / 2, y - 3, w / 2 + tw / 2, y + 10, 0xA0000000);
			g.centeredText(font, line, w / 2, y, 0xFFFF7A7A);
			y += 16;
		}
		if (ClientState.hud != null && ClientState.phase().isBattle()) {
			boolean out = ClientState.hud.eliminated() || ClientState.phase() == Phase.SUDDEN_DEATH || ClientState.phase() == Phase.FINAL_CIRCLE;
			String status = out ? "ELIMINATED - you can spectate the rest of the battle" : ClientState.hud.heartHp() >= 0
					? "You'll respawn at your Heart" : "Your Heart is gone - you'll respawn at a squad Heart if one stands";
			g.centeredText(font, status, w / 2, y, out ? 0xFFFF4040 : 0xFF5BE37D);
			y += 12;
		}
		if (ClientState.map != null && ClientState.map.markers().stream().anyMatch(m -> m.kind() == 1)) {
			g.centeredText(font, "Your gear is in a War Grave where you fell (it's on your map)", w / 2, y, 0xFFFFD040);
		}
	}
}
