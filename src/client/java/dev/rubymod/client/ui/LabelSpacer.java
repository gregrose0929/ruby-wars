package dev.rubymod.client.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps small labels in a sight or a HUD from landing on each other ("128m143m132m" when targets bunch up): each one
 * goes where it was meant to, or a line up or down if that's taken, or is left off if there's nowhere near.
 */
public final class LabelSpacer {
	private final List<int[]> taken = new ArrayList<>();

	/** A new frame: nothing placed yet. */
	public void clear() {
		taken.clear();
	}

	/**
	 * Where to draw a {@code w}-wide label meant for ({@code x}, {@code y}): the y to use, or {@link Integer#MIN_VALUE}
	 * if every spot near it is taken.
	 */
	public int place(int x, int y, int w) {
		int[] nudges = {0, -10, 10, -20, 20};
		for (int dy : nudges) {
			int[] r = {x - 1, y + dy - 1, x + w + 1, y + dy + 9};
			boolean clear = true;
			for (int[] t : taken) {
				if (r[0] < t[2] && t[0] < r[2] && r[1] < t[3] && t[1] < r[3]) {
					clear = false;
					break;
				}
			}
			if (clear) {
				taken.add(r);
				return y + dy;
			}
		}
		return Integer.MIN_VALUE;
	}
}
