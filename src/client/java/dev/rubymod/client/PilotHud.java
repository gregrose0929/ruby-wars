package dev.rubymod.client;

import org.joml.Vector3fc;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.AircraftType;
import dev.rubymod.entity.AircraftType.Weapon;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The head-up display while flying: speed and altitude tapes, heading, pitch
 * ladder, flight path marker, throttle, weapons, the missile seeker and lock,
 * the guided-missile impact point, the bomb impact pipper (CCIP), and warnings.
 */
public class PilotHud implements HudElement {
	private static final int GREEN = 0xFF50FF70;
	private static final int DIM = 0xA050FF70;
	private static final int AMBER = 0xFFFFC040;
	private static final int RED = 0xFFFF4040;
	private static final int PANEL = 0x50000000;
	/** Half-height of the speed and altitude tapes. */
	private static final int TAPE = 40;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		AircraftEntity aircraft = Targeting.piloting(mc);
		if (aircraft == null) {
			return;
		}
		Font font = mc.font;
		int w = g.guiWidth();
		int h = g.guiHeight();
		AircraftType kind = aircraft.kind();
		Camera camera = mc.gameRenderer.mainCamera();

		if (Designation.weaponCam) {
			drawWeaponCam(g, font, mc, w, h);
			return;
		}
		drawPitchLadder(g, font, camera, aircraft, w, h);
		drawFlightPath(g, camera, aircraft, w, h);
		drawSpeedTape(g, font, aircraft, w, h);
		drawAltitudeTape(g, font, mc, aircraft, w, h);
		drawHeading(g, font, aircraft, w);
		drawStatus(g, font, aircraft, kind, w, h);
		drawWeapons(g, font, camera, aircraft, w, h);
		drawContacts(g, font, camera, mc, w, h);
		drawLandingAid(g, font, camera, mc, aircraft, kind, w, h);
		drawDesignation(g, font, camera, aircraft, w, h);
		drawWarnings(g, font, mc, aircraft, kind, w, h);
		drawAutopilot(g, font, aircraft, w, h);
	}

	/** "AUTOPILOT" banner with the held heading and altitude, and how to get out of it. */
	private void drawAutopilot(GuiGraphicsExtractor g, Font font, AircraftEntity aircraft, int w, int h) {
		String key = RubyModClient.autopilotKey == null ? "Z" : RubyModClient.autopilotKey.getTranslatedKeyMessage().getString();
		if (!Vehicles.autopilot) {
			g.text(font, key + ": autopilot", w - 96, h - 58, DIM, false);
			return;
		}
		int heading = Math.floorMod(Math.round(Vehicles.autopilotHeading + 180.0F), 360);
		String text = "AUTOPILOT  HDG " + String.format("%03d", heading) + "  ALT " + Math.round(Vehicles.autopilotAltitude);
		int tw = font.width(text) + 12;
		g.fill(w / 2 - tw / 2, 58, w / 2 + tw / 2, 71, 0xA0000000);
		g.outline(w / 2 - tw / 2, 58, tw, 13, GREEN);
		center(g, font, text, w / 2, 61, GREEN);
		center(g, font, "look anywhere to aim  -  " + key + " to take the stick", w / 2, 74, DIM);
	}

	// ----- Projection -----

	/** Screen position of a world point, or null if it's behind the camera. */
	private static float[] project(Camera camera, Vec3 world, int w, int h) {
		Vec3 rel = world.subtract(camera.position());
		Vector3fc f = camera.forwardVector();
		Vector3fc u = camera.upVector();
		Vector3fc l = camera.leftVector();
		double z = rel.x * f.x() + rel.y * f.y() + rel.z * f.z();
		if (z < 0.1) {
			return null;
		}
		double x = rel.x * l.x() + rel.y * l.y() + rel.z * l.z();
		double y = rel.x * u.x() + rel.y * u.y() + rel.z * u.z();
		double scale = (h / 2.0) / Math.tan(Math.toRadians(camera.getFov() / 2.0));
		return new float[] {(float) (w / 2.0 - x / z * scale), (float) (h / 2.0 - y / z * scale)};
	}

	private static float[] projectDirection(Camera camera, Vec3 direction, int w, int h) {
		return project(camera, camera.position().add(direction.scale(1000)), w, h);
	}

	// ----- Flight instruments -----

	private void drawPitchLadder(GuiGraphicsExtractor g, Font font, Camera camera, AircraftEntity aircraft, int w, int h) {
		float yaw = aircraft.getYRot();
		for (int pitch = -60; pitch <= 60; pitch += 10) {
			float[] p = projectDirection(camera, Vec3.directionFromRotation(-pitch, yaw), w, h);
			if (p == null || p[1] < 52 || p[1] > h - 30) {
				continue;
			}
			int x = (int) p[0];
			int y = (int) p[1];
			if (pitch == 0) {
				// Horizon line.
				g.horizontalLine(x - 70, x - 12, y, DIM);
				g.horizontalLine(x + 12, x + 70, y, DIM);
				continue;
			}
			int col = DIM;
			if (pitch > 0) {
				g.horizontalLine(x - 40, x - 14, y, col);
				g.horizontalLine(x + 14, x + 40, y, col);
			} else {
				// Below the horizon: dashed.
				for (int dx = 14; dx < 40; dx += 6) {
					g.horizontalLine(x - dx - 3, x - dx, y, col);
					g.horizontalLine(x + dx, x + dx + 3, y, col);
				}
			}
			String label = Integer.toString(pitch);
			g.text(font, label, x + 43, y - 4, col, false);
			g.text(font, label, x - 43 - font.width(label), y - 4, col, false);
		}
	}

	private void drawFlightPath(GuiGraphicsExtractor g, Camera camera, AircraftEntity aircraft, int w, int h) {
		// Boresight: where the nose points.
		float[] nose = projectDirection(camera, aircraft.forward(), w, h);
		if (nose != null) {
			int x = (int) nose[0];
			int y = (int) nose[1];
			g.horizontalLine(x - 8, x - 3, y, GREEN);
			g.horizontalLine(x + 3, x + 8, y, GREEN);
			g.verticalLine(x, y - 5, y - 1, GREEN);
		}
		// Flight path marker: where the aircraft is actually going.
		Vec3 velocity = aircraft.getDeltaMovement();
		if (velocity.lengthSqr() > 0.01) {
			float[] fpm = projectDirection(camera, velocity.normalize(), w, h);
			if (fpm != null) {
				int x = (int) fpm[0];
				int y = (int) fpm[1];
				circle(g, x, y, 4, GREEN);
				g.horizontalLine(x - 11, x - 5, y, GREEN);
				g.horizontalLine(x + 5, x + 11, y, GREEN);
				g.verticalLine(x, y - 9, y - 4, GREEN);
			}
		}
	}

	private void drawSpeedTape(GuiGraphicsExtractor g, Font font, AircraftEntity aircraft, int w, int h) {
		int x = w / 2 - 95;
		int cy = h / 2;
		int kmh = (int) Math.round(aircraft.speed() * 72.0);
		g.fill(x - 30, cy - TAPE, x + 2, cy + TAPE, PANEL);
		for (int v = (kmh / 20 - 3) * 20; v <= kmh + 60; v += 20) {
			int y = cy - (v - kmh) * TAPE / 60;
			if (v < 0 || y < cy - TAPE + 2 || y > cy + TAPE - 2) {
				continue;
			}
			g.horizontalLine(x - 4, x, y, DIM);
			if (v % 40 == 0 && Math.abs(y - cy) > 6) {
				String s = Integer.toString(v);
				g.text(font, s, x - 6 - font.width(s), y - 4, DIM, false);
			}
		}
		box(g, font, Integer.toString(kmh), x - 30, cy - 6, 30, GREEN);
		g.text(font, "KM/H", x - 28, cy + TAPE + 2, DIM, false);
	}

	private void drawAltitudeTape(GuiGraphicsExtractor g, Font font, Minecraft mc, AircraftEntity aircraft, int w, int h) {
		int x = w / 2 + 95;
		int cy = h / 2;
		int alt = (int) Math.round(aircraft.getY());
		g.fill(x - 2, cy - TAPE, x + 32, cy + TAPE, PANEL);
		for (int v = (alt / 10 - 6) * 10; v <= alt + 60; v += 10) {
			int y = cy - (v - alt) * TAPE / 60;
			if (y < cy - TAPE + 2 || y > cy + TAPE - 2) {
				continue;
			}
			g.horizontalLine(x, x + 4, y, DIM);
			if (v % 20 == 0 && Math.abs(y - cy) > 6) {
				g.text(font, Integer.toString(v), x + 7, y - 4, DIM, false);
			}
		}
		box(g, font, Integer.toString(alt), x, cy - 6, 32, GREEN);
		g.text(font, "ALT", x + 4, cy + TAPE + 2, DIM, false);
		// Height above ground (radar altimeter) and vertical speed.
		int agl = heightAboveGround(mc, aircraft);
		g.text(font, agl < 0 ? "R ---" : "R " + agl, x + 2, cy + TAPE + 22, agl >= 0 && agl < 15 ? AMBER : GREEN, false);
		double climb = aircraft.getDeltaMovement().y * 20.0;
		String vs = (climb >= 0 ? "+" : "") + Math.round(climb) + " m/s";
		g.text(font, vs, x + 2, cy + TAPE + 12, GREEN, false);
	}

	private static int heightAboveGround(Minecraft mc, AircraftEntity aircraft) {
		Vec3 from = aircraft.position();
		BlockHitResult hit = mc.level.clip(new ClipContext(from, from.add(0, -300, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, aircraft));
		return hit.getType() == HitResult.Type.BLOCK ? (int) Math.round(from.y - hit.getLocation().y) : -1;
	}

	private void drawHeading(GuiGraphicsExtractor g, Font font, AircraftEntity aircraft, int w) {
		int cx = w / 2;
		int y = 22;
		float heading = Mth.positiveModulo(aircraft.getYRot() + 180.0F, 360.0F); // 0 = north, clockwise
		g.fill(cx - 90, y - 2, cx + 90, y + 16, PANEL);
		for (int deg = 0; deg < 360; deg += 10) {
			float d = Mth.wrapDegrees(deg - heading);
			if (Math.abs(d) > 44) {
				continue;
			}
			int x = cx + (int) (d * 2);
			g.verticalLine(x, y - 1, deg % 30 == 0 ? y + 5 : y + 3, DIM);
			if (deg % 30 == 0) {
				String s = switch (deg) {
					case 0 -> "N";
					case 90 -> "E";
					case 180 -> "S";
					case 270 -> "W";
					default -> Integer.toString(deg / 10);
				};
				g.text(font, s, x - font.width(s) / 2, y + 6, DIM, false);
			}
		}
		String s = String.format("%03d", Math.round(heading) % 360);
		box(g, font, s, cx - 14, y + 17, 28, GREEN);
	}

	private void drawStatus(GuiGraphicsExtractor g, Font font, AircraftEntity aircraft, AircraftType kind, int w, int h) {
		int x = 6;
		int y = 34;
		// Throttle bar.
		g.fill(x, y, x + 6, y + 40, PANEL);
		int filled = Math.round(aircraft.throttle() * 40);
		g.fill(x + 1, y + 40 - filled, x + 5, y + 40, aircraft.throttle() >= 0.99F ? AMBER : GREEN);
		g.text(font, (kind.rotorcraft() ? "COLL " : "THR ") + Math.round(aircraft.throttle() * 100) + "%", x + 10, y + 32, GREEN, false);
		g.text(font, kind.name().toUpperCase(), x + 10, y, DIM, false);
		int hp = Math.round(aircraft.getHealth() / aircraft.getMaxHealth() * 100);
		g.text(font, "HULL " + hp + "%", x + 10, y + 11, hp < 30 ? RED : hp < 60 ? AMBER : GREEN, false);
		boolean gear = aircraft.gearDown();
		if (kind.rotorcraft()) {
			g.text(font, "WASD fly  Space/Ctrl up/down", x + 10, y + 42, DIM, false);
			return;
		}
		g.text(font, gear ? "GEAR DOWN [G]" : "GEAR UP [G]", x + 10, y + 42, gear ? GREEN : AMBER, false);
		if (aircraft.onGround()) {
			int kmh = (int) Math.round(aircraft.speed() * 72.0);
			int vr = (int) Math.round(kind.rotateSpeed() * 72.0);
			String takeoff = kmh >= vr ? "ROTATE - pull up" : "TAKEOFF ROLL  Vr " + vr;
			g.text(font, takeoff, x + 10, y + 21, kmh >= vr ? AMBER : DIM, false);
		}
	}

	// ----- Weapons -----

	private void drawWeapons(GuiGraphicsExtractor g, Font font, Camera camera, AircraftEntity aircraft, int w, int h) {
		Weapon weapon = aircraft.selectedWeapon();
		int x = w - 112;
		int y = 34;
		if (weapon == null) {
			g.text(font, "NO WEAPONS", x, y, DIM, false);
		} else {
			int ammo = aircraft.ammo(weapon);
			g.text(font, weapon.title.toUpperCase(), x, y, GREEN, false);
			g.text(font, (weapon == Weapon.CANNON ? "RNDS " : "x") + ammo, x, y + 10, ammo == 0 ? RED : GREEN, false);
			if (weapon == Weapon.ROCKETS) {
				// Rockets fly straight to whatever is under the crosshair.
				g.outline(w / 2 - 8, h / 2 - 8, 16, 16, GREEN);
			}
			g.text(font, "R: next weapon", x, y + 31, DIM, false);
		}
		g.text(font, "FLARES " + aircraft.flares() + (aircraft.kind().rotorcraft() ? "  [X]" : "  [Space/X]"), x, y + 20,
				aircraft.flares() == 0 ? RED : GREEN, false);
		if (weapon == null) {
			return;
		}

		switch (weapon) {
			case CANNON -> {
				// Gun pipper (the nose for jets; the crosshair for the helicopter's swiveling chin gun),
				// and the lead circle: put the pipper on it and fire.
				float[] p = aircraft.kind().rotorcraft() ? new float[] {w / 2.0F, h / 2.0F} : projectDirection(camera, aircraft.forward(), w, h);
				if (p != null) {
					circle(g, (int) p[0], (int) p[1], 10, GREEN);
					g.fill((int) p[0], (int) p[1], (int) p[0] + 1, (int) p[1] + 1, GREEN);
				}
				if (Designation.leadDirection != null) {
					float[] lead = projectDirection(camera, Designation.leadDirection, w, h);
					if (lead != null) {
						int lx = (int) lead[0];
						int ly = (int) lead[1];
						boolean onTarget = p != null && Math.hypot(lx - p[0], ly - p[1]) < 6;
						circle(g, lx, ly, 5, onTarget ? RED : AMBER);
						diamond(g, lx, ly, 2, onTarget ? RED : AMBER);
						int d = (int) Math.round(Designation.leadTarget.distanceTo(aircraft));
						g.text(font, (onTarget ? "SHOOT " : "LEAD ") + d + "m", lx + 8, ly - 4, onTarget ? RED : AMBER, false);
					}
				}
			}
			case AAM, AGM -> drawSeeker(g, font, camera, weapon, w, h);
			case BOMBS -> drawBombTarget(g, font, camera, aircraft, w, h);
		}
	}

	private void drawSeeker(GuiGraphicsExtractor g, Font font, Camera camera, Weapon weapon, int w, int h) {
		// Seeker cone.
		circle(g, w / 2, h / 2, (int) (h / 2.0 / Math.tan(Math.toRadians(camera.getFov() / 2.0)) * Math.tan(Math.toRadians(12))), DIM);
		Contacts.Target target = Targeting.candidate;
		boolean locked = Targeting.locked(weapon);
		if (target != null) {
			float[] p = project(camera, target.pos(), w, h);
			if (p != null) {
				int x = (int) p[0];
				int y = (int) p[1];
				// The box closes in as the lock builds.
				float progress = Math.min(1.0F, Targeting.lockTicks / (float) Targeting.lockTime(weapon));
				int size = locked ? 9 : (int) (22 - 13 * progress);
				int col = locked ? RED : AMBER;
				g.outline(x - size, y - size, size * 2, size * 2, col);
				if (locked) {
					diamond(g, x, y, 4, RED);
					String d = Math.round(target.pos().distanceTo(camera.position())) + "m";
					g.text(font, "LOCK " + target.name() + " " + d, x + size + 3, y - 4, RED, false);
				}
			}
		}
		if (!locked && weapon == Weapon.AGM && Targeting.aimPoint != null) {
			// No lock: the missile flies to the point under the cross.
			float[] p = project(camera, Targeting.aimPoint, w, h);
			if (p != null) {
				diamond(g, (int) p[0], (int) p[1], 6, AMBER);
				String d = Math.round(Targeting.aimPoint.distanceTo(camera.position())) + "m";
				g.text(font, "IMPACT " + d, (int) p[0] + 9, (int) p[1] - 4, AMBER, false);
			}
		}
	}

	/**
	 * Bomb targeting: put the crosshair on what you want to hit. The diamond marks that spot; the bombs
	 * glide to it if it's within their reach from the current release point (green IN REACH), otherwise
	 * it says which way to fly. The CCIP pipper still shows where an unguided bomb would land.
	 */
	private void drawBombTarget(GuiGraphicsExtractor g, Font font, Camera camera, AircraftEntity aircraft, int w, int h) {
		Vec3 target = Designation.point != null ? Designation.point : Targeting.aimPoint;
		Vec3 impact = Targeting.bombImpact;
		if (target == null) {
			center(g, font, "BOMBS: look at a spot on the ground  (" + (RubyModClient.autopilotKey == null ? "Z"
					: RubyModClient.autopilotKey.getTranslatedKeyMessage().getString()) + ": autopilot, then look down)", w / 2, h / 2 + 30, AMBER);
			return;
		}
		double alt = Math.max(1.0, aircraft.getY() - target.y);
		double fallTicks = Math.sqrt(2.0 * alt / 0.08);
		double reach = 0.5 * dev.rubymod.entity.BombEntity.MAX_STEER * fallTicks * fallTicks * 0.55 + 4.0;
		double miss = impact == null ? Double.MAX_VALUE : Math.hypot(impact.x - target.x, impact.z - target.z);
		boolean inReach = miss <= reach;
		int col = inReach ? GREEN : AMBER;
		float[] p = project(camera, target, w, h);
		if (p != null) {
			int x = (int) p[0];
			int y = (int) p[1];
			for (int i = 0; i <= 6; i++) {
				g.fill(x - i, y - 6 + i, x - i + 1, y - 5 + i, col);
				g.fill(x + i, y - 6 + i, x + i + 1, y - 5 + i, col);
				g.fill(x - i, y + 6 - i, x - i + 1, y + 7 - i, col);
				g.fill(x + i, y + 6 - i, x + i + 1, y + 7 - i, col);
			}
			g.text(font, "TGT " + Math.round(target.distanceTo(aircraft.position())) + "m", x + 10, y - 10, col, false);
		}
		String status;
		if (inReach) {
			status = "IN REACH - RELEASE  (" + Math.round(fallTicks / 20.0) + "s fall)";
		} else {
			// Is the target ahead of where the bombs would land, or behind / off to the side?
			Vec3 vel = aircraft.getDeltaMovement();
			Vec3 toTarget = impact == null ? Vec3.ZERO : new Vec3(target.x - impact.x, 0, target.z - impact.z);
			double along = vel.horizontalDistanceSqr() < 1.0E-4 ? 0 : toTarget.dot(new Vec3(vel.x, 0, vel.z).normalize());
			status = along > 0 ? "HOLD - target ahead, " + Math.round(miss - reach) + "m to go" : "OUT OF REACH - turn back and line up";
		}
		center(g, font, status, w / 2, h / 2 + 42, col);
		drawCcip(g, font, camera, aircraft, w, h);
	}

	private void drawCcip(GuiGraphicsExtractor g, Font font, Camera camera, AircraftEntity aircraft, int w, int h) {
		Vec3 impact = Targeting.bombImpact;
		Vec3 velocity = aircraft.getDeltaMovement();
		float[] fpm = velocity.lengthSqr() > 0.01 ? projectDirection(camera, velocity.normalize(), w, h) : null;
		if (impact == null) {
			g.text(font, "CCIP: no solution", w / 2 - 40, h / 2 + 40, AMBER, false);
			return;
		}
		float[] p = project(camera, impact, w, h);
		if (p == null) {
			g.text(font, "CCIP: impact below view - dive", w / 2 - 70, h / 2 + 40, AMBER, false);
			return;
		}
		int x = (int) p[0];
		int y = (int) p[1];
		if (y > h - 34) {
			// Impact point is below the screen: show where it is along the bottom edge.
			int ex = Mth.clamp(x, 20, w - 20);
			center(g, font, "v CCIP " + Math.round(impact.distanceTo(aircraft.position())) + "m v", ex, h - 44, RED);
			return;
		}
		circle(g, x, y, 7, RED);
		g.fill(x, y, x + 1, y + 1, RED);
		// Bomb fall line from the flight path marker to the pipper.
		if (fpm != null) {
			int steps = 12;
			for (int i = 1; i < steps; i += 2) {
				int lx = (int) Mth.lerp(i / (float) steps, fpm[0], p[0]);
				int ly = (int) Mth.lerp(i / (float) steps, fpm[1], p[1]);
				g.fill(lx, ly, lx + 1, ly + 2, DIM);
			}
		}
		g.text(font, "BOMB " + Math.round(impact.distanceTo(aircraft.position())) + "m", x + 10, y - 4, RED, false);
	}

	// ----- Landing aid -----

	/**
	 * Gear down, low and descending: where the flight path meets the ground (the touchdown point),
	 * and a glide-slope cue: aim for a 3 degree descent and a sink rate under 6 m/s.
	 */
	private void drawLandingAid(GuiGraphicsExtractor g, Font font, Camera camera, Minecraft mc, AircraftEntity aircraft, AircraftType kind, int w, int h) {
		if (kind.rotorcraft() || !aircraft.gearDown() || aircraft.onGround()) {
			return;
		}
		Vec3 v = aircraft.getDeltaMovement();
		int agl = heightAboveGround(mc, aircraft);
		if (v.y >= -0.02 || agl < 0 || agl > 60 || v.lengthSqr() < 0.01) {
			return;
		}
		Vec3 from = aircraft.position();
		var hit = mc.level.clip(new ClipContext(from, from.add(v.normalize().scale(400)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, aircraft));
		if (hit.getType() == HitResult.Type.BLOCK) {
			float[] p = project(camera, hit.getLocation(), w, h);
			if (p != null) {
				int x = (int) p[0];
				int y = (int) p[1];
				g.horizontalLine(x - 10, x + 10, y, 0xFF40C0FF);
				g.verticalLine(x, y - 4, y + 4, 0xFF40C0FF);
				g.text(font, "TOUCHDOWN", x + 12, y - 4, 0xFF40C0FF, false);
			}
		}
		double sink = -v.y * 20.0;
		double angle = Math.toDegrees(Math.atan2(-v.y, Math.hypot(v.x, v.z)));
		String cue = angle > 4.5 ? "TOO STEEP - pull up" : angle < 1.5 ? "SHALLOW - descend" : "ON GLIDE SLOPE";
		int col = sink > 6 ? RED : angle > 4.5 || angle < 1.5 ? AMBER : GREEN;
		center(g, font, String.format(java.util.Locale.ROOT, "%s  %.1f deg  sink %.1f m/s", cue, angle, sink), w / 2, h - 46, col);
	}

	// ----- Long-range contacts -----

	/** Aircraft and vehicles beyond render distance: a small diamond, type and range. */
	private void drawContacts(GuiGraphicsExtractor g, Font font, Camera camera, Minecraft mc, int w, int h) {
		for (Contacts.Target t : Contacts.remote(mc)) {
			float[] p = project(camera, t.pos(), w, h);
			if (p == null || p[0] < 0 || p[0] > w || p[1] < 0 || p[1] > h) {
				continue;
			}
			int col = t.airborne() ? 0xFFFF9050 : 0xFFD0D060;
			diamond(g, (int) p[0], (int) p[1], 3, col);
			String s = t.name() + " " + Math.round(t.pos().distanceTo(camera.position())) + "m";
			g.text(font, s, (int) p[0] + 6, (int) p[1] - 4, col, false);
		}
	}

	// ----- Designation and weapon camera -----

	/** The designated target: a square on it, or an arrow at the screen edge pointing to it. */
	private void drawDesignation(GuiGraphicsExtractor g, Font font, Camera camera, AircraftEntity aircraft, int w, int h) {
		Vec3 target = Designation.target();
		if (target == null) {
			g.text(font, "T: designate target", 6, h / 2 + 70, DIM, false);
			return;
		}
		int d = (int) Math.round(target.distanceTo(aircraft.position()));
		String label = (Designation.entity != null ? "TGT " + Designation.entity.name() : "TGT POINT") + " " + d + "m";
		float[] p = project(camera, target, w, h);
		if (p != null && p[0] > 10 && p[0] < w - 10 && p[1] > 10 && p[1] < h - 10) {
			int x = (int) p[0];
			int y = (int) p[1];
			g.outline(x - 7, y - 7, 14, 14, 0xFF40C0FF);
			g.fill(x, y, x + 1, y + 1, 0xFF40C0FF);
			g.text(font, label, x + 10, y + 4, 0xFF40C0FF, false);
			return;
		}
		// Off screen: arrow on an ellipse around the center, toward the target.
		Vec3 rel = target.subtract(camera.position());
		var l = camera.leftVector();
		var u = camera.upVector();
		double sx = -(rel.x * l.x() + rel.y * l.y() + rel.z * l.z());
		double sy = -(rel.x * u.x() + rel.y * u.y() + rel.z * u.z());
		double a = Math.atan2(sy, sx);
		int ax = w / 2 + (int) (Math.cos(a) * (w / 2.0 - 40));
		int ay = h / 2 + (int) (Math.sin(a) * (h / 2.0 - 30));
		for (int i = 0; i < 6; i++) {
			int px = ax - (int) Math.round(Math.cos(a) * i);
			int py = ay - (int) Math.round(Math.sin(a) * i);
			g.fill(px - (5 - i) / 2, py - (5 - i) / 2, px + (5 - i) / 2 + 1, py + (5 - i) / 2 + 1, 0xFF40C0FF);
		}
		center(g, font, label, Mth.clamp(ax, 60, w - 60), Mth.clamp(ay + 8, 20, h - 30), 0xFF40C0FF);
	}

	private void drawWeaponCam(GuiGraphicsExtractor g, Font font, Minecraft mc, int w, int h) {
		center(g, font, "WEAPON CAM  [C] back to cockpit", w / 2, 22, GREEN);
		var munition = Designation.lastMunition;
		Vec3 target = Designation.target();
		if (munition != null && munition.isAlive()) {
			String speed = Math.round(munition.getDeltaMovement().length() * 72) + " km/h";
			String range = target != null ? "  " + Math.round(munition.position().distanceTo(target)) + "m to target" : "";
			center(g, font, speed + range, w / 2, h - 30, GREEN);
		} else {
			center(g, font, "IMPACT", w / 2, h - 30, RED);
		}
		g.outline(w / 2 - 20, h / 2 - 20, 40, 40, DIM);
	}

	// ----- Warnings -----

	private void drawWarnings(GuiGraphicsExtractor g, Font font, Minecraft mc, AircraftEntity aircraft, AircraftType kind, int w, int h) {
		boolean blink = (mc.level.getGameTime() / 5) % 2 == 0;
		int y = h / 2 + 22;
		if (Targeting.missileIncoming && blink) {
			warning(g, font, "!! MISSILE - FLARES !!", w / 2, y, RED);
			y += 12;
		}
		if (!aircraft.onGround()) {
			if (!kind.rotorcraft() && aircraft.speed() < kind.stallSpeed() * 1.1) {
				warning(g, font, "STALL", w / 2, y, blink ? RED : AMBER);
				y += 12;
			}
			double sink = -aircraft.getDeltaMovement().y;
			int agl = heightAboveGround(mc, aircraft);
			if (sink > 0.3 && agl >= 0 && agl / sink < 50) {
				warning(g, font, "PULL UP", w / 2, y, blink ? RED : AMBER);
			}
		}
	}

	// ----- Drawing helpers -----

	private static void box(GuiGraphicsExtractor g, Font font, String text, int x, int y, int width, int col) {
		g.fill(x, y, x + width, y + 12, 0xC0000000);
		g.outline(x, y, width, 12, col);
		g.text(font, text, x + (width - font.width(text)) / 2, y + 2, col, false);
	}

	private static void warning(GuiGraphicsExtractor g, Font font, String text, int x, int y, int col) {
		int half = font.width(text) / 2 + 4;
		g.fill(x - half, y - 2, x + half, y + 10, 0xC0000000);
		g.outline(x - half, y - 2, half * 2, 12, col);
		center(g, font, text, x, y, col);
	}

	private static void center(GuiGraphicsExtractor g, Font font, String text, int x, int y, int col) {
		g.text(font, text, x - font.width(text) / 2, y, col, false);
	}

	private static void circle(GuiGraphicsExtractor g, int cx, int cy, int r, int col) {
		int points = Math.max(12, r * 3);
		for (int i = 0; i < points; i++) {
			double a = i * Math.PI * 2 / points;
			int x = cx + (int) Math.round(Math.cos(a) * r);
			int y = cy + (int) Math.round(Math.sin(a) * r);
			g.fill(x, y, x + 1, y + 1, col);
		}
	}

	private static void diamond(GuiGraphicsExtractor g, int cx, int cy, int r, int col) {
		for (int i = 0; i <= r; i++) {
			g.fill(cx - r + i, cy - i, cx - r + i + 1, cy - i + 1, col);
			g.fill(cx - r + i, cy + i, cx - r + i + 1, cy + i + 1, col);
			g.fill(cx + r - i, cy - i, cx + r - i + 1, cy - i + 1, col);
			g.fill(cx + r - i, cy + i, cx + r - i + 1, cy + i + 1, col);
		}
	}
}
