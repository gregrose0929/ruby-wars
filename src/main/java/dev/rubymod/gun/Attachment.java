package dev.rubymod.gun;

import java.util.List;

/** Gun upgrades. Each gun can carry one of each. */
public enum Attachment {
	EXTENDED_MAG("Extended Mag", "+50% magazine size"),
	RED_DOT("Red Dot Sight", "Tighter spread, more zoom"),
	GRIP("Vertical Grip", "-40% recoil"),
	SUPPRESSOR("Suppressor", "Quiet shots, no tracers, no sniper glint");

	public final String title;
	public final String effect;

	Attachment(String title, String effect) {
		this.title = title;
		this.effect = effect;
	}

	/** The stats a gun has with these attachments fitted. */
	public static GunStats apply(GunStats base, List<String> attachments) {
		if (attachments.isEmpty()) {
			return base;
		}
		int magazine = base.magazine();
		float hip = base.hipSpread();
		float aim = base.aimSpread();
		float recoil = base.recoil();
		float zoom = base.zoom();
		GunStats.Special special = base.special();
		boolean silenced = false;
		for (String name : attachments) {
			switch (Attachment.valueOf(name)) {
				case EXTENDED_MAG -> magazine = Math.round(magazine * 1.5F);
				case RED_DOT -> {
					hip *= 0.8F;
					aim *= 0.5F;
					zoom = Math.min(zoom, 0.7F);
				}
				case GRIP -> recoil *= 0.6F;
				case SUPPRESSOR -> {
					silenced = true;
					if (special == GunStats.Special.GLINT) {
						special = GunStats.Special.NONE;
					}
				}
			}
		}
		return new GunStats(base.damage(), base.fireInterval(), magazine, base.reloadTicks(), hip, aim, base.range(), base.pellets(),
				base.ammo(), base.automatic(), base.headshot(), base.moveSpeed(), recoil, zoom, special, silenced);
	}
}
