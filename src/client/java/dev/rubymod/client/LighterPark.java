package dev.rubymod.client;

import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;

/**
 * "Lighter park": a switch (on the game picker, H) for slower computers. With it on, the park's decorations (its 3D
 * models) more than 40 blocks away aren't drawn. Signs, people and everything close by are, as always. Remembered.
 */
public final class LighterPark {
	private LighterPark() {
	}

	private static final double FAR = 40 * 40;
	private static Boolean on;

	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("rubywars-lighter-park.txt");
	}

	public static boolean on() {
		if (on == null) {
			try {
				on = Files.exists(file()) && Files.readString(file()).trim().equals("on");
			} catch (Exception e) {
				on = false;
			}
		}
		return on;
	}

	public static void toggle() {
		on = !on();
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), on ? "on" : "off");
		} catch (Exception e) {
			// Not remembered next time; still switched for now.
		}
	}

	/** Skip drawing this? (A park model, in the hub, far away, with the switch on.) */
	public static boolean hide(Entity e) {
		if (!(e instanceof Display.BlockDisplay) && !(e instanceof Display.ItemDisplay) || !on()) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || !mc.level.dimension().identifier().getPath().equals("hub")) {
			return false;
		}
		return e.distanceToSqr(mc.player) > FAR;
	}
}
