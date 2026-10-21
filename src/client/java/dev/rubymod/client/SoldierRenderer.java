package dev.rubymod.client;

import dev.rubymod.RubyMod;
import dev.rubymod.entity.SoldierEntity;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;

/** Guards (woodland camo, ruby armband) and raiders (desert kit, red bandana), holding their weapon up. */
public class SoldierRenderer extends HumanoidMobRenderer<SoldierEntity, SoldierRenderer.State, HumanoidModel<SoldierRenderer.State>> {
	private static final Identifier GUARD = RubyMod.id("textures/entity/soldier/guard.png");
	private static final Identifier RAIDER = RubyMod.id("textures/entity/soldier/raider.png");

	public static class State extends HumanoidRenderState {
		public boolean raider;
	}

	public SoldierRenderer(EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(SoldierEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.raider = entity.isRaider();
	}

	@Override
	protected HumanoidModel.ArmPose getArmPose(SoldierEntity entity, HumanoidArm arm) {
		return HumanoidModel.ArmPose.CROSSBOW_HOLD;
	}

	@Override
	public Identifier getTextureLocation(State state) {
		return state.raider ? RAIDER : GUARD;
	}
}
