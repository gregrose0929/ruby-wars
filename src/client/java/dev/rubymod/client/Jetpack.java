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
 * jets (WASD steers), watch the fuel bar; it refuels on the ground. The player moves
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

	/** Every client tick. */
	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		boolean firing = false;
		if (wearing(p) && p.getVehicle() == null && !p.isSpectator() && !p.getAbilities().flying && !p.isFallFlying()) {
			boolean held = mc.gui.screen() == null && mc.options.keyJump.isDown();
			if (held && !p.onGround() && !p.isInWater() && fuel > 0) {
				firing = true;
				fuel = Math.max(0, fuel - 1.0F);
				Vec3 v = p.getDeltaMovement();
				double vy = Math.min(MAX_CLIMB, v.y + LIFT);
				// WASD pushes you along where you're looking.
				Vec3 forward = Vec3.directionFromRotation(0, p.getYRot());
				Vec3 right = new Vec3(-forward.z, 0, forward.x);
				Vec3 push = forward.scale(p.zza).add(right.scale(-p.xxa));
				Vec3 h = new Vec3(v.x, 0, v.z).add(push.lengthSqr() > 0 ? push.normalize().scale(PUSH) : Vec3.ZERO);
				if (h.length() > MAX_SPEED) {
					h = h.normalize().scale(MAX_SPEED);
				}
				p.setDeltaMovement(h.x, vy, h.z);
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
			int color = fuel < 25 ? 0xFFFF5050 : thrusting ? 0xFFFFB040 : 0xFF50C8FF;
			g.fill(x + 1, y + h - filled, x + 6, y + h, color);
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
