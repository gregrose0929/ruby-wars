package dev.rubymod.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.rubymod.RubyMod;
import dev.rubymod.net.MorePayloads;
import dev.rubymod.registry.ModItems;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.Vec3;

/**
 * The jetpack (worn in the chest slot), client side: hold Space in the air to fire the
 * jets (WASD steers), crouch to float gently down, watch the fuel bar; it refuels on the ground. The last of the fuel
 * (below the line on the bar) is kept for landing: coming down fast near the ground, the jets fire on their own. The player moves
 * themselves, so the flying happens here; the server is told when the jets fire (no
 * fall damage then, and everyone sees the exhaust).
 */
public final class Jetpack {
	public static final float MAX_FUEL = 80.0F;
	private static final double LIFT = 0.11;
	private static final double MAX_CLIMB = 0.36;
	/** Set on each player's render state: are they wearing one? */
	public static final net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey<Boolean> WEARING =
			net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey.create(() -> "rubymod:jetpack");
	private static final double PUSH = 0.035;
	private static final double MAX_SPEED = 0.6;
	public static final ModelLayerLocation LAYER = new ModelLayerLocation(RubyMod.id("jetpack"), "main");
	private static final Identifier TEXTURE = RubyMod.id("textures/entity/jetpack.png");

	private static float fuel = MAX_FUEL;
	private static boolean thrusting;

	private Jetpack() {
	}

	public static boolean wearing(LocalPlayer player) {
		return player != null && player.getItemBySlot(EquipmentSlot.CHEST).is(ModItems.JETPACK);
	}

	/** Fuel kept back for landing: Space won't climb on it, but it always softens a fall. */
	public static final float RESERVE = 16.0F;

	/** Every client tick. */
	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		boolean firing = false;
		if (wearing(p) && p.getVehicle() == null && !p.isSpectator() && !p.getAbilities().flying && !p.isFallFlying()) {
			boolean free = mc.gui.screen() == null;
			boolean held = free && mc.options.keyJump.isDown();
			boolean easing = free && mc.options.keyShift.isDown();
			boolean air = !p.onGround() && !p.isInWater();
			Vec3 v = p.getDeltaMovement();
			if (air && held && fuel > RESERVE) {
				// Climbing.
				firing = true;
				fuel = Math.max(RESERVE, fuel - 1.0F);
				p.setDeltaMovement(steer(p, v, Math.min(MAX_CLIMB, v.y + LIFT)));
				p.resetFallDistance();
			} else if (air && easing && fuel > 0 && v.y < -0.1) {
				// Crouching: the jets let you float gently down (and you can still steer).
				firing = true;
				fuel = Math.max(0, fuel - 0.4F);
				p.setDeltaMovement(steer(p, v, Math.max(v.y, -0.15)));
				p.resetFallDistance();
			} else if (air && fuel > 0 && v.y < -0.45 && nearGround(p, 4 + (int) (-v.y * 8))) {
				// Coming down fast near the ground: the jets fire on their own to soften the landing.
				firing = true;
				fuel = Math.max(0, fuel - 0.6F);
				p.setDeltaMovement(v.x, Math.max(v.y + 0.18, -0.3), v.z);
				p.resetFallDistance();
			} else if (p.onGround()) {
				fuel = Math.min(MAX_FUEL, fuel + 1.6F);
			}
		} else if (p != null && p.onGround()) {
			fuel = Math.min(MAX_FUEL, fuel + 1.6F);
		}
		if (firing != thrusting && p != null) {
			thrusting = firing;
			ClientPlayNetworking.send(new MorePayloads.JetpackThrust(firing));
		}
	}

	/** WASD pushes you along where you're looking (up to a top speed), with this upward speed. */
	private static Vec3 steer(LocalPlayer p, Vec3 v, double vy) {
		Vec3 forward = Vec3.directionFromRotation(0, p.getYRot());
		Vec3 right = new Vec3(-forward.z, 0, forward.x);
		Vec3 push = forward.scale(p.zza).add(right.scale(-p.xxa));
		Vec3 h = new Vec3(v.x, 0, v.z).add(push.lengthSqr() > 0 ? push.normalize().scale(PUSH) : Vec3.ZERO);
		if (h.length() > MAX_SPEED) {
			h = h.normalize().scale(MAX_SPEED);
		}
		return new Vec3(h.x, vy, h.z);
	}

	/** Solid ground within this many blocks under their feet? */
	private static boolean nearGround(LocalPlayer p, int blocks) {
		net.minecraft.core.BlockPos at = p.blockPosition();
		for (int d = 0; d <= blocks; d++) {
			net.minecraft.core.BlockPos q = at.below(d);
			if (!p.level().getBlockState(q).getCollisionShape(p.level(), q).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** For the autotest: the fuel left. */
	public static float fuel() {
		return fuel;
	}

	/** The fuel bar, beside the hotbar while you wear one. */
	public static class Hud implements HudElement {
		@Override
		public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
			Minecraft mc = Minecraft.getInstance();
			if (!wearing(mc.player) || mc.player.getVehicle() != null) {
				return;
			}
			int x = g.guiWidth() / 2 + 96;
			int y = g.guiHeight() - 34;
			int h = 30;
			int filled = Math.round(h * fuel / MAX_FUEL);
			g.fill(x, y, x + 7, y + h, 0xA0000000);
			int color = fuel <= RESERVE ? 0xFFFF5050 : thrusting ? 0xFFFFB040 : 0xFF50C8FF;
			g.fill(x + 1, y + h - filled, x + 6, y + h, color);
			// The landing reserve: a line where climbing stops.
			int r = y + h - Math.round(h * RESERVE / MAX_FUEL);
			g.fill(x, r, x + 7, r + 1, 0xC0FFFFFF);
			g.text(mc.font, "JET", x - 1, y - 9, 0xFFDDE6EE, true);
		}
	}

	/** The jetpack model: a harness plate, two tanks and nozzles, worn on the back. */
	public static LayerDefinition layer() {
		MeshDefinition mesh = new MeshDefinition();
		mesh.getRoot().addOrReplaceChild("pack", CubeListBuilder.create()
				.texOffs(0, 0).addBox(-4.0F, 1.0F, 2.0F, 8.0F, 9.0F, 1.0F)          // harness plate
				.texOffs(0, 12).addBox(-4.0F, 0.0F, 3.0F, 3.0F, 9.0F, 3.0F)         // left tank
				.texOffs(14, 12).addBox(1.0F, 0.0F, 3.0F, 3.0F, 9.0F, 3.0F)         // right tank
				.texOffs(28, 12).addBox(-3.5F, -1.0F, 3.5F, 2.0F, 1.0F, 2.0F)       // valve caps
				.texOffs(28, 16).addBox(1.5F, -1.0F, 3.5F, 2.0F, 1.0F, 2.0F)
				.texOffs(40, 12).addBox(-3.5F, 9.0F, 3.5F, 2.0F, 2.0F, 2.0F)        // nozzles
				.texOffs(40, 17).addBox(1.5F, 9.0F, 3.5F, 2.0F, 2.0F, 2.0F)
				.texOffs(22, 0).addBox(-3.5F, 0.0F, -2.2F, 1.0F, 8.0F, 0.2F)        // shoulder straps
				.texOffs(26, 0).addBox(2.5F, 0.0F, -2.2F, 1.0F, 8.0F, 0.2F),
				PartPose.ZERO);
		return LayerDefinition.create(mesh, 64, 32);
	}

	/** Draws the jetpack on the back of any player wearing one. */
	public static class Layer extends RenderLayer<AvatarRenderState, PlayerModel> {
		private final ModelPart model;

		public Layer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, ModelPart model) {
			super(parent);
			this.model = model;
		}

		@Override
		public void submit(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
			if (state.isInvisible || !Boolean.TRUE.equals(state.getData(WEARING))) {
				return;
			}
			pose.pushPose();
			getParentModel().body.translateAndRotate(pose);
			collector.submitModelPart(model, pose, RenderTypes.entityCutout(TEXTURE), light, OverlayTexture.NO_OVERLAY, null);
			pose.popPose();
		}
	}
}
