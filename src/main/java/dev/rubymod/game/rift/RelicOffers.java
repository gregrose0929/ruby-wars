package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.RandomSource;

/**
 * Which three relics you're offered: better tiers from elites, a Legendary in every
 * treasure room, nothing you already have, and a lean toward the elements you're already
 * building around (two Shock relics make a third more likely).
 */
final class RelicOffers {
	private RelicOffers() {
	}

	/** Bond relics the team could take right now: two elements on two teammates (or on you, playing alone). */
	static List<Relic> bonds(Rift.Runner r) {
		List<Relic> out = new ArrayList<>();
		java.util.Collection<Rift.Runner> team = Rift.RUNNERS.values();
		if (pair(team, Relic.Element.SHOCK, Relic.Element.FIRE)) {
			out.add(Relic.PLASMA_STORM);
		}
		if (pair(team, Relic.Element.FROST, Relic.Element.EXPLOSIVE)) {
			out.add(Relic.SHATTERBOMB);
		}
		if (pair(team, Relic.Element.SHOCK, Relic.Element.RUBY)) {
			out.add(Relic.RUBY_CONDUIT);
		}
		if (pair(team, Relic.Element.FROST, Relic.Element.FIRE)) {
			out.add(Relic.FROSTFIRE);
		}
		if (team.stream().filter(x -> x.has(Relic.POCKET_TANK)).count() >= 2) {
			out.add(Relic.TANK_PLATOON);
		}
		out.removeIf(r::has);
		return out;
	}

	private static boolean pair(java.util.Collection<Rift.Runner> team, Relic.Element a, Relic.Element b) {
		for (Rift.Runner x : team) {
			for (Rift.Runner y : team) {
				if ((x != y || team.size() == 1) && x.relics.stream().anyMatch(rl -> rl.element == a && rl.tier != Relic.Tier.BOND)
						&& y.relics.stream().anyMatch(rl -> rl.element == b && rl.tier != Relic.Tier.BOND)) {
					return true;
				}
			}
		}
		return false;
	}

	static List<Relic> offer(Rift.Runner r, RiftMap.Type room, RandomSource random) {
		List<Relic> out = offerPlain(r, room, random);
		List<Relic> bonds = bonds(r);
		if (!bonds.isEmpty() && out.size() == 3 && random.nextFloat() < 0.45F) {
			out.set(2, bonds.get(random.nextInt(bonds.size())));
		}
		return out;
	}

	private static List<Relic> offerPlain(Rift.Runner r, RiftMap.Type room, RandomSource random) {
		List<Relic> out = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			Relic.Tier tier = switch (room) {
				case TREASURE -> i == 0 ? Relic.Tier.LEGENDARY : Relic.Tier.RARE;
				case BOSS -> Relic.Tier.LEGENDARY;
				case ELITE -> roll(random, 0, 55, 22, 23);
				default -> roll(random, 38, 52, 0, 10);
			};
			java.util.function.Predicate<Relic> owned = x -> r != null && r.has(x);
			Relic pick = pick(r, tier, out, random, owned);
			if (pick == null) {
				pick = pick(r, Relic.Tier.RARE, out, random, owned);
			}
			if (pick == null) {
				pick = pick(r, Relic.Tier.COMMON, out, random, owned);
			}
			if (pick != null) {
				out.add(pick);
			}
		}
		return out;
	}

	private static Relic.Tier roll(RandomSource random, int common, int rare, int legendary, int cursed) {
		int v = random.nextInt(common + rare + legendary + cursed);
		if (v < common) {
			return Relic.Tier.COMMON;
		} else if (v < common + rare) {
			return Relic.Tier.RARE;
		} else if (v < common + rare + legendary) {
			return Relic.Tier.LEGENDARY;
		}
		return Relic.Tier.CURSED;
	}

	private static Relic pick(Rift.Runner r, Relic.Tier tier, List<Relic> already, RandomSource random, java.util.function.Predicate<Relic> owned) {
		List<Relic> pool = new ArrayList<>();
		for (Relic relic : Relic.values()) {
			if (relic.tier != tier || owned.test(relic) || already.contains(relic)) {
				continue;
			}
			if (relic.locked && (r == null || !Rift.unlocked(r.id, relic.id()))) {
				continue;
			}
			int weight = 1;
			if (r != null && relic.element != Relic.Element.NONE) {
				long same = r.relics.stream().filter(x -> x.element == relic.element).count();
				weight += (int) same * 2;
			}
			for (int k = 0; k < weight; k++) {
				pool.add(relic);
			}
		}
		return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
	}

	/** The merchant's stock: two commons, a rare or two, and sometimes a legendary; never one the whole team already has. */
	static List<Relic> shopStock(RandomSource random, java.util.Collection<Rift.Runner> team) {
		List<Relic> out = new ArrayList<>();
		java.util.function.Predicate<Relic> owned = x -> !team.isEmpty() && team.stream().allMatch(m -> m.has(x));
		for (Relic.Tier t : new Relic.Tier[] {Relic.Tier.COMMON, Relic.Tier.COMMON, Relic.Tier.RARE, Relic.Tier.RARE,
				random.nextFloat() < 0.4F ? Relic.Tier.LEGENDARY : Relic.Tier.CURSED}) {
			Relic r = pick(null, t, out, random, owned);
			if (r != null) {
				out.add(r);
			}
		}
		return out;
	}

	static int price(Relic r) {
		return switch (r.tier) {
			case COMMON -> 60;
			case RARE -> 100;
			case LEGENDARY -> 220;
			case CURSED -> 80;
			case BOND -> 160;
		};
	}
}
