package dev.rubymod.client.model;

import dev.rubymod.client.AircraftRenderer;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/**
 * Animated aircraft model (geometry from {@link AircraftModels}): landing gear
 * that retracts, elevators and ailerons that follow the stick, a rudder that
 * follows turns, and spinning helicopter rotors. Parts a design doesn't have
 * are simply skipped.
 */
public class AircraftModel extends EntityModel<AircraftRenderer.State> {
	private final ModelPart gear;
	private final ModelPart elevatorL;
	private final ModelPart elevatorR;
	private final ModelPart aileronL;
	private final ModelPart aileronR;
	private final ModelPart[] rudders;
	private final ModelPart rotor;
	private final ModelPart tailRotor;

	public AircraftModel(ModelPart root) {
		super(root, RenderTypes::entityCutout);
		this.gear = child(root, "gear");
		this.elevatorL = child(root, "elevator_l");
		this.elevatorR = child(root, "elevator_r");
		this.aileronL = child(child(root, "wing_l"), "aileron_l");
		this.aileronR = child(child(root, "wing_r"), "aileron_r");
		this.rudders = new ModelPart[] {child(child(root, "fin"), "rudder"), child(child(root, "fin_l"), "rudder_l"),
				child(child(root, "fin_r"), "rudder_r")};
		this.rotor = child(root, "rotor");
		this.tailRotor = child(root, "tail_rotor");
	}

	private static ModelPart child(ModelPart parent, String name) {
		return parent != null && parent.hasChild(name) ? parent.getChild(name) : null;
	}

	@Override
	public void setupAnim(AircraftRenderer.State state) {
		super.setupAnim(state);
		if (gear != null) {
			gear.visible = state.gearDown;
		}
		// Control surfaces (radians; model space flips the sign of x rotations).
		float pitchInput = Math.max(-1.0F, Math.min(1.0F, state.pitchRate / 3.0F));
		float rollInput = Math.max(-1.0F, Math.min(1.0F, state.roll / 45.0F));
		float yawInput = Math.max(-1.0F, Math.min(1.0F, state.yawRate / 4.0F));
		if (elevatorL != null) {
			elevatorL.xRot += pitchInput * 0.35F;
		}
		if (elevatorR != null) {
			elevatorR.xRot += pitchInput * 0.35F;
		}
		if (aileronL != null) {
			aileronL.xRot += rollInput * 0.4F;
		}
		if (aileronR != null) {
			aileronR.xRot -= rollInput * 0.4F;
		}
		for (ModelPart rudder : rudders) {
			if (rudder != null) {
				rudder.yRot += yawInput * 0.4F;
			}
		}
		if (rotor != null) {
			rotor.yRot = state.rotorAngle;
		}
		if (tailRotor != null) {
			tailRotor.xRot = state.rotorAngle * 1.7F;
		}
	}
}
