package dev.rubymod.client.model;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.client.VehicleRenderer;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/**
 * Animated tank and jeep (geometry from {@link VehicleModels}): the turret
 * traverses, the gun elevates and recoils with a muzzle flash, road wheels and
 * sprockets turn with the distance driven, track links slide along the runs,
 * and the jeep's front wheels and steering wheel turn into corners.
 */
public class VehicleModel extends EntityModel<VehicleRenderer.State> {
	private final ModelPart turret;
	private final ModelPart gun;
	private final ModelPart barrel;
	private final ModelPart flash;
	private final ModelPart steering;
	private final ModelPart gunner;
	private final List<ModelPart> wheels = new ArrayList<>();
	private final List<Float> radii = new ArrayList<>();
	private final ModelPart frontLeft;
	private final ModelPart frontRight;
	private final ModelPart[] cleatsTop;
	private final ModelPart[] cleatsBottom;

	public VehicleModel(ModelPart root) {
		super(root, RenderTypes::entityCutout);
		this.turret = child(root, "turret");
		this.gun = child(turret, "gun");
		this.barrel = child(gun, "barrel");
		this.flash = child(barrel, "flash");
		this.steering = child(root, "steering");
		this.gunner = child(root, "gunner");
		for (String side : new String[] {"l", "r"}) {
			for (int i = 0; i < 7; i++) {
				add(root, "wheel_" + side + i, 4.0F);
			}
			add(root, "wheel_" + side + "s", 4.5F);
			add(root, "wheel_" + side + "i", 3.8F);
		}
		for (String name : new String[] {"wheel_fl", "wheel_fr", "wheel_rl", "wheel_rr"}) {
			add(root, name, 6.5F);
		}
		this.frontLeft = child(root, "wheel_fl");
		this.frontRight = child(root, "wheel_fr");
		this.cleatsTop = new ModelPart[] {child(root, "cleats_top_l"), child(root, "cleats_top_r")};
		this.cleatsBottom = new ModelPart[] {child(root, "cleats_bottom_l"), child(root, "cleats_bottom_r")};
	}

	private void add(ModelPart root, String name, float radius) {
		ModelPart part = child(root, name);
		if (part != null) {
			wheels.add(part);
			radii.add(radius);
		}
	}

	private static ModelPart child(ModelPart parent, String name) {
		return parent != null && parent.hasChild(name) ? parent.getChild(name) : null;
	}

	@Override
	public void setupAnim(VehicleRenderer.State state) {
		super.setupAnim(state);
		if (turret != null) {
			turret.yRot = (float) Math.toRadians(state.turretYaw);
		}
		if (gun != null) {
			gun.xRot = (float) Math.toRadians(-state.barrelPitch); // Minecraft pitch is negative upward
		}
		if (barrel != null) {
			barrel.z -= state.recoil * 7.0F;
		}
		if (flash != null) {
			flash.visible = state.flash;
			flash.zRot = state.flashRoll;
		}
		// Distance is in pixels driven; a wheel turns distance / radius radians.
		for (int i = 0; i < wheels.size(); i++) {
			wheels.get(i).xRot = state.distance / radii.get(i);
		}
		if (frontLeft != null && frontRight != null) {
			frontLeft.yRot = state.steer;
			frontRight.yRot = state.steer;
		}
		if (steering != null) {
			steering.zRot = state.steer * 4.0F;
		}
		// Track links sit 3 px apart; the bottom run moves back while the top run moves forward.
		float shift = state.distance % 3.0F;
		for (ModelPart part : cleatsTop) {
			if (part != null) {
				part.z += shift;
			}
		}
		for (ModelPart part : cleatsBottom) {
			if (part != null) {
				part.z -= shift;
			}
		}
	}
}
