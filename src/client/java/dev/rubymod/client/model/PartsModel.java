package dev.rubymod.client.model;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/**
 * A generated vehicle model (tools/more_vehicles.py) with its moving parts: wheels that turn with the ground covered
 * ("wheel_*", radius given), a spinning drill ("drill"), handlebars that steer ("bars"), a turret ("turret") with its
 * gun ("gun"), and a bow ramp ("ramp").
 */
public class PartsModel extends EntityModel<PartsModel.State> {
	public static class State extends EntityRenderState {
		/** Pixels driven, signed. */
		public float distance;
		/** Radians. */
		public float steer;
		/** Degrees. */
		public float spin;
		public float turretYaw;
		public float gunPitch;
		/** 0 up, 1 down. */
		public float ramp;
		public float yaw;
		/** Degrees leaning over (positive: to its right), at the ground. */
		public float roll;
		/** Degrees nose up, about a point this far back from the middle (blocks). */
		public float pitch;
		public float pitchPivot;
		/** Blocks it's being shaken about this frame (a drill hard at work). */
		public float shakeX;
		public float shakeY;
		public float shakeZ;
	}

	private final List<ModelPart> wheels = new ArrayList<>();
	private final List<Float> radii = new ArrayList<>();
	private final ModelPart drill;
	private final ModelPart bars;
	private final ModelPart turret;
	private final ModelPart gun;
	private final ModelPart ramp;

	/** @param wheels "name=radius" for each wheel */
	public PartsModel(ModelPart root, String... wheels) {
		super(root);
		for (String w : wheels) {
			String[] f = w.split("=");
			ModelPart part = child(root, f[0]);
			if (part != null) {
				this.wheels.add(part);
				radii.add(Float.parseFloat(f[1]));
			}
		}
		drill = child(root, "drill");
		bars = child(root, "bars");
		turret = child(root, "turret");
		gun = turret == null ? null : child(turret, "gun");
		ramp = child(root, "ramp");
	}

	private static ModelPart child(ModelPart parent, String name) {
		return parent != null && parent.hasChild(name) ? parent.getChild(name) : null;
	}

	@Override
	public void setupAnim(State state) {
		super.setupAnim(state);
		for (int i = 0; i < wheels.size(); i++) {
			wheels.get(i).xRot = state.distance / radii.get(i);
		}
		if (drill != null) {
			drill.zRot = (float) Math.toRadians(state.spin);
		}
		if (bars != null) {
			bars.yRot = state.steer;
		}
		if (turret != null) {
			turret.yRot = (float) Math.toRadians(state.turretYaw);
		}
		if (gun != null) {
			gun.xRot = (float) Math.toRadians(-state.gunPitch);
		}
		if (ramp != null) {
			ramp.xRot = (float) Math.toRadians(-state.ramp * 100.0F);
		}
	}
}
