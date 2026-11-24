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

	/** A stretch of the season: its steps, in order. */
	public record Chapter(String title, List<Step> steps) {
	}

	private static final List<Step> STARTED = List.of(
			new Step("Place your Ruby Heart", "Pick a spot for your base (K opens the map) and place it. A starter base appears around it: "
					+ "an Armory, and a Ruby Drill that digs ore into a chest (sell it at the Trade Depot).", "heart", false),
			new Step("Buy something at your Armory", "Right-click the Armory by your Heart. It spends the rubies you carry, then your bank.", "armory", false),
			new Step("Upgrade your Heart", "At the Armory, the first tab (Your Heart): \"Upgrade Ruby Heart\", then Buy. "
					+ "20 rubies for tier 2 (a bigger claim, more health, more drills), then 60 for tier 3.", "heart2", false),
			new Step("Place a base defense", "Sentry Turrets, walls and mines from the Armory: everyone comes for your Heart on Battle Day.",
					"defense", false),
			new Step("Spend stars on an upgrade", "Goals give stars. Press J, open UPGRADES and pick one: it unlocks tanks, jets and more "
					+ "in the Armory.", "#research", false),
			new Step("Get to a Supply Cache", "World events come while you play, even on your own: they're told two minutes ahead and marked on your map (K). Get there first!",
					"cache", true));

	private static final List<Step> GROW = List.of(
			new Step("Build a generator", "A Combustion Generator (Armory, Machines) burns coal, charcoal or wood day and night. Solar panels rest at night.", "power", false),
			new Step("Sell at a Trade Depot", "A Trade Depot buys anything. Put a belt from your drill's chest into it, or drop things in by hand.", "sell100", false),
			new Step("3 machines busy at once", "A drill, a Refinery and an Assembler working together. An Ore Silo next to a machine feeds it, no belts needed.", "busy3", false),
			new Step("Machine to Mk III", "Open a machine and press Upgrade (it spends rubies). Higher tiers work faster.", "mk3", false),
			new Step("Bank 100 rubies", "Rubies from the Trade Depot go straight into your bank. A Vault keeps them safer.", "bank500", false),
			new Step("Hold a Ruby Shrine longest", "Ruby Shrines come when two or more of you are on. Stand in its ring alone: rubies go into your bank, and the longest holder gets a bonus.",
					"shrine", true));

	private static final List<Step> READY = List.of(
			new Step("Heart to tier 3", "At the Armory, Your Heart tab: upgrade it again (60 rubies). A bigger claim, more health and more drills.", "heart3", false),
			new Step("Place 5 base defenses", "Turrets, walls, mines and Tesla Coils. Try them on a Practice Dummy (Armory, Defenses) to see what they hit.", "defense5", false),
			new Step("Build a Vault", "A Vault keeps your bank and pays a little interest each day. On Battle Day, guard it: a blown-open Vault spills rubies.", "vault", false),
			new Step("Bank 200 rubies", "Rubies to spend on Battle Day: ammo, vehicles, missiles.", "bank2000", false),
			new Step("Drive 300 blocks", "A Jeep, a Dirt Bike or a Quad from the Armory (Vehicles). Learn the roads before Battle Day.", "drive", false),
			new Step("Fly 500 blocks", "Jetpacks count. Aircraft unlock with upgrade stars (J, UPGRADES).", "fly", true));

	public static final List<Chapter> CHAPTERS = List.of(
			new Chapter("Getting started", STARTED),
			new Chapter("Grow your base", GROW),
			new Chapter("Ready for Battle Day", READY));

	/** The chapter you're on: the first with a step still to do (optional ones aside), or the last. */
	public static int chapter() {
		for (int c = 0; c < CHAPTERS.size(); c++) {
			for (Step s : CHAPTERS.get(c).steps()) {
				if (!s.optional() && !done(s)) {
					return c;
				}
			}
		}
		return CHAPTERS.size() - 1;
	}

	/** This chapter's steps. */
	public static List<Step> steps() {
		return CHAPTERS.get(chapter()).steps();
	}

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

	/** The first step still to do in this chapter (optional ones skipped), or -1 when every chapter is done. */
	public static int current() {
		List<Step> steps = steps();
		for (int i = 0; i < steps.size(); i++) {
			if (!steps.get(i).optional() && !done(steps.get(i))) {
				return i;
			}
		}
		return -1;
	}

	/** Where the season is, in a line. */
	public static String season() {
		Phase phase = ClientState.phase();
		String order = " Battle Day goes: Lockdown (defenses on) > Open War > Sudden Death > Final Circle. It waits whenever nobody's on.";
		if (phase == Phase.LOBBY) {
			return "The season hasn't started yet (an admin starts it with /rubywars start). Pick your Season Choices in the Ruby Wars menu: "
					+ "teams, and a vote on a Night Battle.";
		}
		if (phase == Phase.BUILD) {
			var hud = ClientState.hud;
			int day = hud == null ? 0 : hud.day();
			if (hud != null && hud.battleReady()) {
				return "Battle Day's date has come! It starts when the admin starts it (" + hud.readyHere() + " of " + hud.readyOf()
						+ " with a Heart are on)." + order;
			}
			long left = ClientState.secondsLeft();
			String when = left >= 0 ? "Battle Day's date is in " + GameHud.formatTime(left) + "; it starts when the admin starts it."
					: "Battle Day starts when the admin starts it.";
			return "Build day " + day + ". " + when + " Missing a build evening costs nothing." + order;
		}
		if (phase == Phase.ENDED) {
			return "The season is over. A new one starts soon.";
		}
		return "It's Battle Day! Defend your Heart and take out the others: the last one (or team) standing wins.";
	}
}
