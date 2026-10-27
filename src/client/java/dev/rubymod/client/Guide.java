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
			new Step("Place your Ruby Heart", "Pick a spot for your base (K opens the map) and place the Heart: nobody else can build there.",
					"heart", false),
			new Step("Mine 10 Ruby Ore", "Dig in the Ruby Crater in the middle of the map (K), or deep underground.", "ruby10", false),
			new Step("Buy something at an Armory", "Craft an Armory (7 iron, 1 ruby, a crafting table in the middle), place it at your base "
					+ "and right-click it. It sells everything.", "armory", false),
			new Step("Build a generator", "Buy a Combustion Generator or Solar Panels at the Armory: they power your machines.", "power", false),
			new Step("Build a Ruby Drill", "Buy one at the Armory and place it near your generator: it digs ruby ore for you.", "drill", false),
			new Step("Build a Trade Depot", "It sells ore into your bank, and the Armory spends from your bank. Add a Vault for interest.", "depot",
					false),
			new Step("Spend stars on an upgrade", "Goals give stars. Press J, open UPGRADES and pick one: it unlocks new gear in the Armory.",
					"#research", false),
			new Step("Place a base defense", "Sentry Turrets, walls and mines from the Armory: raiders attack your Heart on days 3, 6, 9 and 12.",
					"defense", false),
			new Step("Join a squad", "Press O to team up: you share a base, respawns and upgrades.", "squad", true),
			new Step("Upgrade your Heart", "Buy \"Upgrade Ruby Heart\" at the Armory: a bigger base and more drills.", "heart2", false),
			new Step("Drive 300 blocks", "Research Motor Pool (J), buy a Jeep or a Tank at the Armory and go for a drive.", "drive", false),
			new Step("Repel a raid", "Beat the raiders on a raid day, or call a practice raid any time with /rubywars raid.", "raid", false),
			new Step("Get a machine to Mk III", "Right-click a machine to upgrade it with rubies.", "mk3", false),
			new Step("Bank 2,000 rubies", "More drills, a Refinery and a Vault. You'll want rubies for Battle Day gear.", "bank2000", false),
			new Step("Fly 1,000 blocks", "Research Aviation (J) for jets and helicopters, and an Airfield Pad to rearm them.", "fly", false));

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
			return "Build day " + day + " of 14. Raids on days 3, 6, 9 and 12. Then Battle Day: last squad standing wins.";
		}
		if (phase == Phase.ENDED) {
			return "The season is over. A new one starts soon.";
		}
		return "It's Battle Day! Defend your Heart and take out the others.";
	}
}
