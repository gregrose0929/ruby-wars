package dev.rubymod.client;

import java.util.List;

import dev.rubymod.game.Phase;

/**
 * The step-by-step guide to a Ruby Wars season: what to do next, in order. Each step is
 * done when its goal is (the goals the server already tracks, see Goals), so the guide
 * needs nothing new from the server. Shown as the NEXT STEP panel on the HUD and the
 * GUIDE tab of the J screen.
 */
public final class Guide {
	/** {@code goal}: the goal that finishes it ("#research": any upgrade bought). Optional steps are listed but never block the next one. */
	public record Step(String title, String hint, String goal, boolean optional) {
	}

	public static final List<Step> STEPS = List.of(
			new Step("Place your Ruby Heart", "Pick a spot for your base (K opens the map) and place it. A starter base appears around it: "
					+ "an Armory, and a Ruby Drill that digs ore into a chest (sell it at the Trade Depot).", "heart", false),
			new Step("Buy something at your Armory", "Right-click the Armory by your Heart. It spends the rubies you carry, then your bank.", "armory", false),
			new Step("Upgrade your Heart", "At the Armory, the first tab (Your Heart): \"Upgrade Ruby Heart\", then Buy. "
					+ "20 rubies for tier 2 (a bigger claim, more health, more drills), then 60 for tier 3.", "heart2", false),
			new Step("Place a base defense", "Sentry Turrets, walls and mines from the Armory: everyone comes for your Heart on Battle Day.",
					"defense", false),
			new Step("Spend stars on an upgrade", "Goals give stars. Press J, open UPGRADES and pick one: it unlocks tanks, jets and more "
					+ "in the Armory.", "#research", false),
			new Step("Get to a Supply Cache", "World events are told to everyone two minutes ahead and marked on your map (K). Get there first, and watch out for the others.",
					"cache", false));

	private Guide() {
	}

	/** Is this step done? */
	public static boolean done(Step step) {
		var r = ClientState.research;
		if (r == null) {
			return false;
		}
		if (step.goal().equals("#research")) {
			return !r.unlocked().isEmpty();
		}
		String[] f = goal(step);
		return f != null && Boolean.parseBoolean(f[5]);
	}

	/** {progress, target}, or null. */
	public static int[] progress(Step step) {
		String[] f = goal(step);
		if (f == null) {
			return null;
		}
		return new int[] {Integer.parseInt(f[2]), Integer.parseInt(f[3])};
	}

	private static String[] goal(Step step) {
		var r = ClientState.research;
		if (r == null) {
			return null;
		}
		for (String line : r.goals()) {
			String[] f = line.split("\\|");
			if (f.length >= 6 && f[0].equals(step.goal())) {
				return f;
			}
		}
		return null;
	}

	/** The first step still to do (optional ones skipped), or -1 when they're all done. */
	public static int current() {
		for (int i = 0; i < STEPS.size(); i++) {
			if (!STEPS.get(i).optional() && !done(STEPS.get(i))) {
				return i;
			}
		}
		return -1;
	}

	/** Where the season is, in a line. */
	public static String season() {
		Phase phase = ClientState.phase();
		if (phase == Phase.LOBBY) {
			return "The season hasn't started yet (an admin starts it with /rubywars start). Build away!";
		}
		if (phase == Phase.BUILD) {
			int day = ClientState.hud == null ? 0 : ClientState.hud.day();
			return "Build day " + day + ". Watch for world events (Supply Caches, Ruby Shrines, meteors). Battle Day comes when the admin calls it (or on the date they set): last player standing wins.";
		}
		if (phase == Phase.ENDED) {
			return "The season is over. A new one starts soon.";
		}
		return "It's Battle Day! Defend your Heart and take out the others.";
	}
}
