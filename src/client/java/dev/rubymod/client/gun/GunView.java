package dev.rubymod.client.gun;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.rubymod.client.ClientState;
import dev.rubymod.client.model.GunModel;
import dev.rubymod.gun.GunItem;
import dev.rubymod.gun.GunStats;
import dev.rubymod.item.LockOnLauncherItem;
import dev.rubymod.item.RocketLauncherItem;
import dev.rubymod.registry.ModComponents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * First-person guns: the 3D gun held in both hands, with aim-down-sights that
 * lines the real sight up with your eye, recoil kick, a slide/bolt/pump that
 * cycles, muzzle flash, spent casings that fly out and tumble, a full reload
 * (tilt, mag out, new mag in, charge), a sprint pose, a raise-on-equip, weapon
 * sway that lags behind your mouse, and walking bob.
 *
 * Space: the PoseStack we get is camera space (x right, y up, -z forward), in blocks.
 */
public final class GunView {
	/** Gun pixels to blocks in first person. */
	private static final float SCALE = 0.5F / 16.0F;
	private static final Vector3f R_SHOULDER = new Vector3f(0.42F, -0.95F, 0.05F);
	private static final Vector3f L_SHOULDER = new Vector3f(-0.42F, -0.95F, -0.05F);

	/** Test hook: show the sprint pose. */
	public static boolean debugSprint;
	private static long lastNanos;
	private static float lastReload = -1;
	private static float ads;
	private static float sprint;
	private static float equip = 1.0F;
	private static ItemStack lastStack = ItemStack.EMPTY;
	private static int lastSlot = -1;
	private static float recoil;
	private static float recoilRoll;
	private static long lastShotAt;
	private static long flashUntil;
	private static float flashRoll;
	private static float swayX;
	private static float swayY;
	private static float lastYaw;
	private static float lastPitch;
	private static float bobPhase;
	private static float bobAmount;
	private static float launcherCooldown;
	private static final List<Casing> CASINGS = new ArrayList<>();

	private static final class Casing {
		Vec3 pos;
		Vec3 vel;
		float spin;
		float spinVel;
		float age;
		boolean landed;
		final GunModel model;

		Casing(GunModel model, Vec3 pos, Vec3 vel) {
			this.model = model;
			this.pos = pos;
			this.vel = vel;
			this.spinVel = 900.0F + (float) Math.random() * 600.0F;
		}
	}

	private GunView() {
	}

	public static boolean handles(ItemStack stack) {
		return (stack.getItem() instanceof GunItem || stack.getItem() instanceof RocketLauncherItem || stack.getItem() instanceof LockOnLauncherItem)
				&& GunModel.of(stack.getItem()) != null;
	}

	/** A shot went off (the server's recoil packet): kick, flash, cycle the action, eject a casing. */
	public static void onShot() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		ItemStack stack = mc.player.getMainHandItem();
		GunModel model = GunModel.of(stack.getItem());
		if (model == null) {
			return;
		}
		boolean heavy = stack.getItem() instanceof GunItem gun && gun.stats(stack).recoil() >= 4.0F;
		recoil = Math.min(1.6F, recoil + (heavy ? 1.0F : 0.55F));
		recoilRoll = (float) (Math.random() - 0.5) * (heavy ? 6.0F : 3.0F);
		lastShotAt = System.currentTimeMillis();
		flashUntil = lastShotAt + 55;
		flashRoll = (float) Math.random() * 360.0F;
		pendingCasing = model.name.equals("arr_rifle") || model.name.equals("service_pistol");
	}

	private static boolean pendingCasing;
	private static long ejectedFor;

	private static boolean isLauncher(GunModel model) {
		return model.name.equals("rocket_launcher") || model.name.equals("skyhammer") || model.name.equals("lancer");
	}

	/** Called while rendering the main hand; returns true if we drew it (so vanilla shouldn't). */
	public static boolean render(PlayerRenderState playerState, ItemStack stack, PoseStack pose, SubmitNodeCollector collector, int light,
			HandRenderer hands) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		GunModel model = GunModel.of(stack.getItem());
		if (player == null || model == null) {
			return false;
		}
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016F : Math.min(0.1F, (now - lastNanos) / 1.0E9F);
		lastNanos = now;
		long ms = System.currentTimeMillis();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);

		// ----- state
		int slot = player.getInventory().getSelectedSlot();
		if (slot != lastSlot || !ItemStack.isSameItem(stack, lastStack)) {
			equip = 0.0F;
			lastSlot = slot;
		}
		lastStack = stack;
		equip = Math.min(1.0F, equip + dt * 3.2F);
		boolean aiming = player.isUsingItem() && player.getUseItem() == stack;
		ads = approach(ads, aiming && reloadProgress() < 0 ? 1.0F : 0.0F, dt * 7.0F);
		sprint = approach(sprint, (player.isSprinting() || debugSprint) && !aiming ? 1.0F : 0.0F, dt * 5.0F);
		recoil *= (float) Math.exp(-dt * 11.0F);
		float yaw = player.getViewYRot(partial);
		float pitch = player.getViewXRot(partial);
		float dYaw = Mth.wrapDegrees(yaw - lastYaw);
		float dPitch = pitch - lastPitch;
		lastYaw = yaw;
		lastPitch = pitch;
		float swayGain = 1.0F - 0.75F * ads;
		swayX = approach(swayX, Mth.clamp(dYaw * 0.6F, -6.0F, 6.0F) * swayGain, dt * 10.0F);
		swayY = approach(swayY, Mth.clamp(dPitch * 0.6F, -6.0F, 6.0F) * swayGain, dt * 10.0F);
		swayX = approach(swayX, 0.0F, dt * 6.0F);
		swayY = approach(swayY, 0.0F, dt * 6.0F);
		Vec3 v = player.getDeltaMovement();
		float speed = (float) Math.min(0.3, Math.sqrt(v.x * v.x + v.z * v.z));
		boolean grounded = player.onGround();
		bobAmount = approach(bobAmount, grounded ? speed / 0.22F : 0.0F, dt * 6.0F);
		bobPhase += dt * (6.0F + 6.0F * sprint) * (0.4F + bobAmount);
		if (!(stack.getItem() instanceof GunItem)) {
			float cd = player.getCooldowns().getCooldownPercent(stack, partial);
			if (cd > launcherCooldown + 0.5F) {
				recoil = 1.6F;
				recoilRoll = 4.0F;
				lastShotAt = ms;
				flashUntil = ms + 90;
				flashRoll = 0;
			}
			launcherCooldown = cd;
		}

		// ----- the gun's pose
		GunModel.Pose gp = new GunModel.Pose();
		gp.attachments = stack.getOrDefault(ModComponents.ATTACHMENTS, List.<String>of());
		gp.loaded = stack.getItem() instanceof GunItem || launcherCooldown <= 0.0F;
		float sinceShot = (ms - lastShotAt) / 1000.0F;
		float cycle = cycleTime(stack);
		if (sinceShot < cycle) {
			float t = sinceShot / cycle;
			switch (model.name) {
				case "service_pistol" -> gp.slideBack = 1.6F * pulse(t);
				case "arr_rifle", "ruby_blaster" -> gp.boltBack = 1.8F * pulse(t);
				case "combat_shotgun" -> {
					if (t > 0.3F) {
						gp.pumpBack = 2.4F * pulse((t - 0.3F) / 0.7F);
					}
				}
				case "longshot_sniper" -> {
					if (t > 0.25F) {
						float b = (t - 0.25F) / 0.75F;
						gp.boltLift = 65.0F * Mth.clamp(Math.min(b * 5.0F, (1.0F - b) * 5.0F), 0.0F, 1.0F);
						gp.boltBack = 2.6F * pulse(Mth.clamp((b - 0.15F) / 0.7F, 0.0F, 1.0F));
					}
				}
				default -> {
				}
			}
			gp.trigger = sinceShot < 0.08F ? 1.0F : 0.0F;
		}
		if (Trigger_isDown(mc)) {
			gp.trigger = 1.0F;
		}
		if (ms < flashUntil) {
			gp.flash = flashRoll + 1.0F;
		}

		// Reload choreography (with the sounds only you hear: the new mag seating, each shell going in).
		float r = reloadProgress();
		if (r >= 0 && lastReload >= 0) {
			if (model.name.equals("combat_shotgun")) {
				if ((int) ((r - 0.12F) / 0.19F) > (int) ((lastReload - 0.12F) / 0.19F) && r > 0.2F && r < 0.9F) {
					localSound(player, dev.rubymod.registry.ModSounds.SHELL_IN, 0.7F);
				}
			} else if (lastReload < 0.62F && r >= 0.62F) {
				localSound(player, dev.rubymod.registry.ModSounds.MAG_IN, 0.8F);
			}
		}
		lastReload = r;
		float reloadTilt = 0.0F;
		Vector3f leftHand = null;
		float[] magHand = model.anchor("mag_hand");
		boolean leftOnMag = false;
		if (r >= 0) {
			reloadTilt = smooth(Mth.clamp(r / 0.12F, 0, 1)) * smooth(Mth.clamp((1.0F - r) / 0.12F, 0, 1));
			if (isLauncher(model)) {
				gp.loaded = r > 0.45F;
				if (r > 0.45F && r < 0.75F) {
					gp.rocketBack = 10.0F * (1.0F - smooth((r - 0.45F) / 0.3F));
				}
			} else if (model.name.equals("combat_shotgun")) {
				// Shells go in one by one through the loading port.
				float shells = (r - 0.12F) / 0.76F * 4.0F;
				if (shells > 0 && shells < 4) {
					float f = shells - (int) shells;
					leftOnMag = true;
					magHand = new float[] {0.0F, 0.8F - 1.2F * pulse(f), 2.0F - 0.6F * pulse(f)};
				}
			} else {
				if (r > 0.15F && r < 0.38F) {
					float t = (r - 0.15F) / 0.23F;
					gp.magDrop = 10.0F * t * t;
					gp.magTilt = 15.0F * t;
					leftOnMag = t < 0.4F;
				} else if (r >= 0.38F && r < 0.55F) {
					gp.magHidden = true;
				} else if (r >= 0.55F && r < 0.78F) {
					float t = (r - 0.55F) / 0.23F;
					gp.magDrop = 6.0F * (1.0F - smooth(t));
					leftOnMag = true;
				}
				if (r >= 0.78F && r < 0.95F) {
					float t = (r - 0.78F) / 0.17F;
					switch (model.name) {
						case "service_pistol" -> gp.slideBack = 1.6F * pulse(t);
						case "longshot_sniper" -> {
							gp.boltLift = 65.0F * Mth.clamp(Math.min(t * 4.0F, (1.0F - t) * 4.0F), 0, 1);
							gp.boltBack = 2.6F * pulse(t);
						}
						default -> gp.boltBack = 1.8F * pulse(t);
					}
				}
			}
		}

		// ----- where the gun sits
		boolean dot = gp.attachments.contains("RED_DOT");
		float[] sight = model.anchor(dot ? "red_dot_sight" : "sight");
		View view = view(model.name);
		float eye = (dot ? view.dotEye : view.eye) * SCALE;
		Vector3f adsPos = new Vector3f(SCALE * sight[0], -SCALE * sight[1], -eye + SCALE * sight[2]);
		Vector3f pos = new Vector3f(view.hip).lerp(adsPos, smooth(ads));
		float bob = 1.0F - 0.8F * ads;
		pos.x += Mth.sin(bobPhase) * 0.012F * bobAmount * bob + swayX * -0.004F;
		pos.y += Math.abs(Mth.cos(bobPhase)) * -0.014F * bobAmount * bob + swayY * 0.003F + Mth.sin(ms / 900.0F) * 0.003F * (1 - ads);
		pos.z += recoil * (0.05F - 0.025F * ads);
		pos.add(-0.10F * sprint, -0.07F * sprint, 0.04F * sprint);
		pos.add(-0.12F * reloadTilt, 0.06F * reloadTilt, 0.04F * reloadTilt);
		pos.y -= (1.0F - smooth(equip)) * 0.45F;

		float gunPitch = recoil * (5.0F - 2.0F * ads) - 24.0F * sprint + 8.0F * reloadTilt - (1.0F - smooth(equip)) * 55.0F + swayY * 0.6F;
		float gunYaw = view.yaw * (1.0F - ads) + 30.0F * sprint + 16.0F * reloadTilt + swayX * 0.8F;
		float gunRoll = recoilRoll * recoil * 0.5F + 6.0F * sprint + 28.0F * reloadTilt - swayX * 0.6F;

		Matrix4f gun = new Matrix4f()
				.translate(pos)
				.rotateY((float) Math.toRadians(gunYaw))
				.rotateX((float) Math.toRadians(gunPitch))
				.rotateZ((float) Math.toRadians(-gunRoll))
				.rotateY((float) Math.PI)
				.scale(SCALE * 16.0F);

		// Casings leave the ejection port: right away for autoloaders, when the pump or bolt is worked otherwise.
		boolean delayed = model.name.equals("combat_shotgun") || model.name.equals("longshot_sniper");
		float ejectAt = model.name.equals("combat_shotgun") ? 0.5F * cycle : 0.65F * cycle;
		if (pendingCasing || (delayed && lastShotAt != ejectedFor && sinceShot >= ejectAt && sinceShot < cycle)) {
			pendingCasing = false;
			ejectedFor = lastShotAt;
			spawnCasing(model, player, gun);
		}

		// Gun
		pose.pushPose();
		pose.mulPose(gun);
		model.submit(gp, pose, collector, light, OverlayTexture.NO_OVERLAY, 0);
		pose.popPose();

		// Arms
		if (!player.isScoping()) {
			Vector3f handR = at(gun, model.anchor("hand_r"), 0);
			Vector3f handL;
			if (leftOnMag) {
				float[] m = magHand;
				handL = at(gun, new float[] {m[0], m[1] - gp.magDrop, m[2]}, 0);
			} else if (r >= 0.38F && r < 0.55F && !isLauncher(model) && !model.name.equals("combat_shotgun")) {
				float t = (r - 0.38F) / 0.17F;
				Vector3f below = new Vector3f(-0.15F, -0.85F, -0.35F);
				handL = at(gun, magHand, 0).lerp(below, Mth.sin(t * Mth.PI));
			} else {
				boolean grip = gp.attachments.contains("GRIP") && model.anchors.containsKey("grip_hand_l");
				handL = at(gun, model.anchor(grip ? "grip_hand_l" : "hand_l"), 0);
			}
			if (isLauncher(model) && r >= 0.3F && r < 0.75F) {
				float t = (r - 0.3F) / 0.45F;
				Vector3f front = at(gun, model.anchor("muzzle"), 0);
				handL = new Vector3f(handL).lerp(front.add(0.05F, -0.05F, 0.1F), Mth.sin(t * Mth.PI));
			}
			arm(pose, collector, light, playerState, hands, HumanoidArm.RIGHT, handR, R_SHOULDER);
			arm(pose, collector, light, playerState, hands, HumanoidArm.LEFT, handL, L_SHOULDER);
		}

		// Spent casings (in world space, so they fly off as you turn).
		renderCasings(pose, collector, light, player, partial, dt);
		return true;
	}

	/** How a gun sits: hip position (camera space), eye distance behind the sight in gun pixels (irons / red dot), hip yaw. */
	private record View(Vector3f hip, float eye, float dotEye, float yaw) {
	}

	private static View view(String gun) {
		return switch (gun) {
			case "service_pistol" -> new View(new Vector3f(0.20F, -0.22F, -0.50F), 11.0F, 11.0F, -3.0F);
			case "combat_shotgun" -> new View(new Vector3f(0.26F, -0.27F, -0.55F), 4.6F, 6.6F, -2.0F);
			case "longshot_sniper" -> new View(new Vector3f(0.26F, -0.27F, -0.52F), 3.0F, 3.0F, -2.0F);
			case "ruby_blaster" -> new View(new Vector3f(0.26F, -0.26F, -0.55F), 4.4F, 6.4F, -2.0F);
			case "rocket_launcher" -> new View(new Vector3f(0.30F, -0.21F, -0.34F), 5.0F, 5.0F, -1.0F);
			case "skyhammer" -> new View(new Vector3f(0.28F, -0.23F, -0.36F), 4.0F, 4.0F, -1.0F);
			case "lancer" -> new View(new Vector3f(0.32F, -0.26F, -0.32F), 1.6F, 1.6F, -1.0F);
			default -> new View(new Vector3f(0.26F, -0.27F, -0.55F), 4.2F, 6.8F, -2.0F);
		};
	}

	/** Seconds the action takes to cycle after a shot. */
	private static float cycleTime(ItemStack stack) {
		if (stack.getItem() instanceof GunItem gun) {
			GunStats s = gun.stats(stack);
			return Math.max(0.07F, Math.min(1.3F, s.fireInterval() / 20.0F * 0.9F));
		}
		return 0.3F;
	}

	private static boolean Trigger_isDown(Minecraft mc) {
		return mc.options.keyAttack.isDown() && mc.gui.screen() == null;
	}

	/** 0..1 through the current reload, or -1 when not reloading. */
	public static float reloadProgress() {
		if (ClientState.reloadTicks <= 0) {
			return -1.0F;
		}
		float t = (System.currentTimeMillis() - ClientState.reloadStartedAt) / (ClientState.reloadTicks * 50.0F);
		return t >= 1.0F ? -1.0F : Math.max(0.0F, t);
	}

	public static float adsProgress() {
		return ads;
	}

	private static Vector3f at(Matrix4f gun, float[] p, float unused) {
		return gun.transformPosition(new Vector3f(p[0] / 16.0F, p[1] / 16.0F, p[2] / 16.0F));
	}

	/**
	 * Draws one arm with its hand at {@code hand}, reaching back toward {@code shoulder}.
	 * The player arm model hangs from its shoulder pivot (-5, 2, 0) along +y, hand end at y = 12.
	 */
	private static void arm(PoseStack pose, SubmitNodeCollector collector, int light, PlayerRenderState state, HandRenderer hands,
			HumanoidArm side, Vector3f hand, Vector3f shoulder) {
		Vector3f dir = new Vector3f(hand).sub(shoulder).normalize();
		float sign = side == HumanoidArm.RIGHT ? 1.0F : -1.0F;
		Quaternionf q = new Quaternionf().rotationTo(0, 1, 0, dir.x, dir.y, dir.z);
		q.rotateY((float) Math.toRadians(side == HumanoidArm.RIGHT ? 70.0F : -70.0F));
		pose.pushPose();
		pose.translate(hand.x, hand.y, hand.z);
		pose.mulPose(new Matrix4f().rotation(q));
		pose.scale(0.46F, 1.9F, 0.46F);
		pose.translate(sign * 6.0F / 16.0F, -11.0F / 16.0F, 0.0F);
		hands.render(pose, collector, light, side, state);
		pose.popPose();
	}

	private static void spawnCasing(GunModel model, LocalPlayer player, Matrix4f gun) {
		var camera = Minecraft.getInstance().gameRenderer.mainCamera();
		Vector3f local = at(gun, model.anchor("eject"), 0);
		Vector3f world = toWorld(camera, local);
		// Out to the right, up, and a little back.
		Vector3f out = toWorld(camera, new Vector3f(1.6F + (float) Math.random() * 0.8F, 1.4F + (float) Math.random() * 0.8F, 0.4F));
		Vec3 cam = camera.position();
		Vec3 vel = player.getDeltaMovement().add(out.x * 0.07, out.y * 0.07, out.z * 0.07);
		CASINGS.add(new Casing(model, cam.add(world.x, world.y, world.z), vel));
		while (CASINGS.size() > 12) {
			CASINGS.remove(0);
		}
	}

	private static void renderCasings(PoseStack pose, SubmitNodeCollector collector, int light, LocalPlayer player, float partial, float dt) {
		if (CASINGS.isEmpty()) {
			return;
		}
		var camera = Minecraft.getInstance().gameRenderer.mainCamera();
		Vec3 cam = camera.position();
		Iterator<Casing> it = CASINGS.iterator();
		while (it.hasNext()) {
			Casing c = it.next();
			c.age += dt;
			if (c.age > 1.2F) {
				it.remove();
				continue;
			}
			float steps = dt * 20.0F;
			c.vel = c.vel.add(0, -0.04 * steps, 0).scale(Math.pow(0.98, steps));
			c.pos = c.pos.add(c.vel.scale(steps));
			double floor = player.getY() + 0.03;
			if (c.pos.y < floor && c.vel.y < 0) {
				// Bounce on the ground with a tinkle (first hit loudest).
				c.pos = new Vec3(c.pos.x, floor, c.pos.z);
				c.vel = new Vec3(c.vel.x * 0.4, -c.vel.y * 0.35, c.vel.z * 0.4);
				c.spinVel *= 0.5F;
				if (!c.landed) {
					c.landed = true;
					Minecraft.getInstance().level.playLocalSound(c.pos.x, c.pos.y, c.pos.z, dev.rubymod.registry.ModSounds.CASING,
							net.minecraft.sounds.SoundSource.PLAYERS, 0.35F, 0.9F + (float) Math.random() * 0.2F, false);
				}
			}
			c.spin += c.spinVel * dt;
			Vector3f local = toLocal(camera, c.pos.subtract(cam));
			if (local.z > -0.06F) {
				continue;
			}
			GunModel.Pose p = new GunModel.Pose();
			p.casingOnly = true;
			pose.pushPose();
			pose.translate(local.x, local.y, local.z);
			pose.rotateDegrees(Axis.YP, c.spin * 0.7F);
			pose.rotateDegrees(Axis.XP, c.spin);
			pose.scale(SCALE * 16.0F, SCALE * 16.0F, SCALE * 16.0F);
			c.model.submit(p, pose, collector, light, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
		}
	}

	/** Camera space (x right, y up, -z forward) to a world-space offset. */
	private static Vector3f toWorld(net.minecraft.client.Camera camera, Vector3f v) {
		org.joml.Vector3fc f = camera.forwardVector();
		org.joml.Vector3fc u = camera.upVector();
		org.joml.Vector3fc l = camera.leftVector();
		return new Vector3f(-l.x() * v.x + u.x() * v.y - f.x() * v.z, -l.y() * v.x + u.y() * v.y - f.y() * v.z,
				-l.z() * v.x + u.z() * v.y - f.z() * v.z);
	}

	private static Vector3f toLocal(net.minecraft.client.Camera camera, Vec3 rel) {
		org.joml.Vector3fc f = camera.forwardVector();
		org.joml.Vector3fc u = camera.upVector();
		org.joml.Vector3fc l = camera.leftVector();
		return new Vector3f((float) -(rel.x * l.x() + rel.y * l.y() + rel.z * l.z()), (float) (rel.x * u.x() + rel.y * u.y() + rel.z * u.z()),
				(float) -(rel.x * f.x() + rel.y * f.y() + rel.z * f.z()));
	}

	private static void localSound(LocalPlayer player, net.minecraft.sounds.SoundEvent sound, float volume) {
		player.level().playLocalSound(player.getX(), player.getY(), player.getZ(), sound, net.minecraft.sounds.SoundSource.PLAYERS, volume,
				0.95F + (float) Math.random() * 0.1F, false);
	}

	private static float approach(float value, float target, float step) {
		return value < target ? Math.min(target, value + step) : Math.max(target, value - step);
	}

	private static float smooth(float t) {
		t = Mth.clamp(t, 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	/** 0 -> 1 -> 0 with a fast attack and a slower return (a slide or bolt cycling). */
	private static float pulse(float t) {
		t = Mth.clamp(t, 0.0F, 1.0F);
		return t < 0.3F ? smooth(t / 0.3F) : 1.0F - smooth((t - 0.3F) / 0.7F);
	}

	/** Draws a player arm (the mixin passes vanilla's renderPlayerHand in). */
	@FunctionalInterface
	public interface HandRenderer {
		void render(PoseStack pose, SubmitNodeCollector collector, int light, HumanoidArm arm, PlayerRenderState state);
	}
}
