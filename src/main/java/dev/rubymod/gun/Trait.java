package dev.rubymod.gun;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModItems;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Weapon fusion (Ruby Rift's Arsenal): every weapon has one trait it can give to another.
 * Fuse a base with a donor and the base keeps its look and gains the donor's trait (up to
 * three); some pairs make a named legendary. Traits are kept on the weapon (TRAITS).
 */
public enum Trait {
	SPREAD("Scattering", "Spread", "every shot fires 4 extra pellets", Kind.STAT, 0xFFB8C4D0),
	PIERCE("Piercing", "Pierce", "shots go through up to 3 enemies", Kind.SHOT, 0xFFE8E8F0),
	BLAST("Explosive", "Blast", "hits explode", Kind.HIT, 0xFFFFD040),
	BOUNCE("Ricochet", "Bounce", "shots bounce off walls twice", Kind.SHOT, 0xFFFF3D6E),
	SPINUP("Spinning", "Spin-up", "fires faster and faster while you hold the trigger", Kind.SHOT, 0xFFFF7A2E),
	IGNITE("Blazing", "Ignite", "hits set enemies on fire", Kind.HIT, 0xFFFF7A2E),
	CHAIN("Arcing", "Chain", "hits arc into 2 more enemies", Kind.HIT, 0xFF66E0FF),
	HOMING("Seeking", "Homing", "shots curve toward enemies", Kind.SHOT, 0xFFFFC02E),
	RETURN("Rebounding", "Rebound", "hits ricochet into another enemy", Kind.HIT, 0xFFFF3D6E),
	SLAM("Crushing", "Slam", "every shot also smashes enemies right in front of you", Kind.SHOT, 0xFFFF3D6E),
	RAIL("Railed", "Rail", "every 5th shot is a beam through a whole line", Kind.SHOT, 0xFF66E0FF),
	VORTEX("Gravitic", "Vortex", "hits drag nearby enemies together", Kind.HIT, 0xFFC27BFF),
	QUICKDRAW("Quickdraw", "Quickdraw", "fires 40% faster and reloads in half the time", Kind.STAT, 0xFFFFFFFF),
	AUTO("Full-auto", "Full Auto", "hold the trigger to keep firing, 20% faster", Kind.STAT, 0xFFB8C4D0),
	GRENADE("Grenadier", "Grenadier", "every 4th shot also lobs a bouncing grenade", Kind.SHOT, 0xFFFF3D6E),
	FAN("Gunslinger", "Gunslinger", "headshots ricochet into the next head", Kind.HIT, 0xFFFFD040),
	SKYHUNTER("Skyhunter", "Skyhunter", "double damage to anything that flies", Kind.HIT, 0xFF66E0FF),
	ARMOR("Armor-piercing", "Breach", "+60% damage to elites and bosses", Kind.HIT, 0xFFB8C4D0);

	/** When it acts: on the gun's numbers, on each shot (bullet guns only), or on each hit (every weapon). */
	public enum Kind { STAT, SHOT, HIT }

	public final String adjective;
	public final String title;
	public final String about;
	public final Kind kind;
	public final int color;

	Trait(String adjective, String title, String about, Kind kind, int color) {
		this.adjective = adjective;
		this.title = title;
		this.about = about;
		this.kind = kind;
		this.color = color;
	}

	/** The trait a weapon gives when it's used up in a fusion (null: it has none). */
	public static Trait donor(Item item) {
		if (item == ModItems.COMBAT_SHOTGUN) {
			return SPREAD;
		} else if (item == ModItems.LONGSHOT_SNIPER) {
			return PIERCE;
		} else if (item == ModItems.ROCKET_LAUNCHER) {
			return BLAST;
		} else if (item == ModItems.RUBY_BLASTER) {
			return BOUNCE;
		} else if (item == ModItems.RUBY_MINIGUN) {
			return SPINUP;
		} else if (item == ModItems.DRAGONS_BREATH) {
			return IGNITE;
		} else if (item == ModItems.TESLA_COILGUN) {
			return CHAIN;
		} else if (item == ModItems.SWARM_LAUNCHER) {
			return HOMING;
		} else if (item == ModItems.RUBY_CHAKRAM) {
			return RETURN;
		} else if (item == ModItems.RUBY_WARHAMMER || item == ModItems.RUBY_SWORD) {
			return SLAM;
		} else if (item == ModItems.RAIL_LANCE) {
			return RAIL;
		} else if (item == ModItems.SINGULARITY_CANNON) {
			return VORTEX;
		} else if (item == ModItems.SERVICE_PISTOL) {
			return QUICKDRAW;
		} else if (item == ModItems.ARR_RIFLE) {
			return AUTO;
		} else if (item == ModItems.BOUNCER) {
			return GRENADE;
		} else if (item == ModItems.SIX_SHOOTER) {
			return FAN;
		} else if (item == ModItems.SKYHAMMER) {
			return SKYHUNTER;
		} else if (item == ModItems.LANCER) {
			return ARMOR;
		}
		return null;
	}

	/** A weapon's fused traits (in the order they were added). */
	public static List<Trait> of(ItemStack stack) {
		List<Trait> out = new ArrayList<>();
		for (String s : stack.getOrDefault(ModComponents.TRAITS, List.<String>of())) {
			for (Trait t : values()) {
				if (t.name().equals(s)) {
					out.add(t);
				}
			}
		}
		return out;
	}

	/** A named legendary fusion's name, or null. */
	public static String legendary(ItemStack stack) {
		for (String s : stack.getOrDefault(ModComponents.TRAITS, List.<String>of())) {
			if (s.startsWith("LEGEND:")) {
				return s.substring(7);
			}
		}
		return null;
	}

	/** The gun's numbers with its traits (Full Auto, Quickdraw, Spread) and a legendary's extra punch. */
	public static GunStats apply(GunStats s, ItemStack stack) {
		List<Trait> traits = of(stack);
		if (traits.isEmpty()) {
			return s;
		}
		float damage = s.damage();
		int interval = s.fireInterval();
		int reload = s.reloadTicks();
		int pellets = s.pellets();
		float hip = s.hipSpread();
		boolean auto = s.automatic();
		for (Trait t : traits) {
			switch (t) {
				case AUTO -> {
					auto = true;
					interval = Math.max(1, Math.round(interval * 0.8F));
				}
				case QUICKDRAW -> {
					interval = Math.max(1, Math.round(interval * 0.6F));
					reload = Math.max(4, reload / 2);
				}
				case SPREAD -> {
					if (pellets == 1) {
						damage *= 0.55F;
					}
					pellets += 4;
					hip = Math.max(hip, 4.0F);
				}
				default -> {
				}
			}
		}
		if (legendary(stack) != null) {
			damage *= 1.5F;
		}
		return new GunStats(damage, interval, s.magazine(), reload, hip, s.aimSpread(), s.range(), pellets, s.ammo(), auto, s.headshot(), s.moveSpeed(),
				s.recoil(), s.zoom(), s.special(), s.silenced());
	}
}
