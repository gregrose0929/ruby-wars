package dev.rubymod.client.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.rubymod.RubyMod;

import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * One gun's 3D model (geometry from {@link GunModels}) with the parts the game
 * moves: magazine, bolt, slide, pump, trigger, hammer, loaded rocket, attachments,
 * muzzle flash and the spent casing. Drawn in gun pixels (x left, y up, z forward,
 * origin at the top of the grip), 1/16 of a block per pixel before any scaling.
 *
 * Rendering is deferred, so every draw carries its own {@link Pose}; setupAnim
 * applies it when the frame is actually drawn.
 */
public final class GunModel extends Model<GunModel.Pose> {
	private static final Map<String, Supplier<LayerDefinition>> DEFINITIONS = Map.of(
			"arr_rifle", GunModels::arrRifle, "service_pistol", GunModels::servicePistol, "combat_shotgun", GunModels::combatShotgun,
			"longshot_sniper", GunModels::longshotSniper, "ruby_blaster", GunModels::rubyBlaster, "rocket_launcher", GunModels::rocketLauncher,
			"skyhammer", GunModels::skyhammer, "lancer", GunModels::lancer);
	private static final Map<String, ModelLayerLocation> LAYERS = new HashMap<>();
	private static final Map<String, GunModel> BAKED = new HashMap<>();
	private static final float U = GunModels.UNITS;

	/** Everything about how the gun looks in one draw. Distances are in gun pixels, angles in degrees. */
	public static class Pose {
		/** Ruby Rift reskin. */
		public boolean rift;
		public List<String> attachments = List.of();
		public boolean loaded = true;
		public boolean casingOnly;
		public float flash;            // 0 = no flash; otherwise the flash's roll angle + 1
		public float magDrop;          // magazine pulled down (pixels)
		public float magTilt;
		public boolean magHidden;
		public float boltBack;
		public float boltLift;         // bolt-action handle lift
		public float slideBack;
		public float pumpBack;
		public float trigger;          // 0..1 pulled
		public float rocketBack;       // a rocket being pushed into the tube (pixels short of seated)
		public boolean bipodDown;

		public Pose copy() {
			Pose p = new Pose();
			p.attachments = attachments;
			p.loaded = loaded;
			p.casingOnly = casingOnly;
			p.flash = flash;
			p.magDrop = magDrop;
			p.magTilt = magTilt;
			p.magHidden = magHidden;
			p.boltBack = boltBack;
			p.boltLift = boltLift;
			p.slideBack = slideBack;
			p.pumpBack = pumpBack;
			p.trigger = trigger;
			p.rocketBack = rocketBack;
			p.bipodDown = bipodDown;
			return p;
		}
	}

	public final String name;
	public final Identifier texture;
	public final Identifier glow;
	/** Ruby Rift's glowing reskin. */
	public final Identifier riftTexture;
	public final Identifier riftGlow;

	/** A Ruby Rift weapon (tagged with the "rift" custom model data). */
	public static boolean isRift(net.minecraft.world.item.ItemStack stack) {
		var cmd = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA);
		return cmd != null && cmd.strings().contains("rift");
	}
	public final Map<String, float[]> anchors;
	private final ModelPart mag;
	private final ModelPart magStd;
	private final ModelPart magExt;
	private final ModelPart bolt;
	private final ModelPart slide;
	private final ModelPart pump;
	private final ModelPart trigger;
	private final ModelPart hammer;
	private final ModelPart rocket;
	private final ModelPart irons;
	private final ModelPart muzzleDevice;
	private final ModelPart redDot;
	private final ModelPart suppressor;
	private final ModelPart grip;
	private final ModelPart flash;
	private final ModelPart casing;
	private final ModelPart bipod;

	private GunModel(String name, ModelPart root) {
		super(root, RenderTypes::entityCutout);
		this.name = name;
		this.texture = RubyMod.id("textures/entity/gun/" + name + ".png");
		this.glow = RubyMod.id("textures/entity/gun/" + name + "_glow.png");
		this.riftTexture = RubyMod.id("textures/entity/gun/" + name + "_rift.png");
		this.riftGlow = RubyMod.id("textures/entity/gun/" + name + "_rift_glow.png");
		this.anchors = GunModels.ANCHORS.get(name);
		this.mag = child(root, "mag");
		this.magStd = child(mag, "mag_std");
		this.magExt = child(mag, "mag_ext");
		this.bolt = child(root, "bolt");
		this.slide = child(root, "slide");
		this.pump = child(root, "pump");
		this.trigger = child(root, "trigger");
		this.hammer = child(root, "hammer");
		this.rocket = child(root, "rocket");
		this.irons = child(root, "irons");
		this.muzzleDevice = child(root, "muzzle_device");
		this.redDot = child(root, "att_red_dot");
		this.suppressor = child(root, "att_suppressor");
		this.grip = child(root, "att_grip");
		this.flash = child(root, "flash");
		this.casing = child(root, "casing");
		this.bipod = child(root, "bipod");
	}

	private static ModelPart child(ModelPart parent, String name) {
		return parent != null && parent.hasChild(name) ? parent.getChild(name) : null;
	}

	public static void registerLayers() {
		DEFINITIONS.forEach((name, definition) -> {
			ModelLayerLocation layer = new ModelLayerLocation(RubyMod.id("gun/" + name), "main");
			LAYERS.put(name, layer);
			ModelLayerRegistry.registerModelLayer(layer, definition::get);
		});
	}

	/** The model for a gun item, or null if the item isn't one of ours. */
	public static GunModel of(Item item) {
		return get(BuiltInRegistries.ITEM.getKey(item).getPath());
	}

	public static GunModel get(String name) {
		if (!LAYERS.containsKey(name)) {
			return null;
		}
		return BAKED.computeIfAbsent(name, n -> new GunModel(n, Minecraft.getInstance().getEntityModels().bakeLayer(LAYERS.get(n))));
	}

	/** (Re)bakes a gun from a fresh entity model set (item models are rebuilt on every resource reload). */
	public static void bake(String name, net.minecraft.client.model.geom.EntityModelSet models) {
		if (LAYERS.containsKey(name)) {
			BAKED.put(name, new GunModel(name, models.bakeLayer(LAYERS.get(name))));
		}
	}

	public float[] anchor(String key) {
		float[] a = anchors.get(key);
		return a != null ? a : anchors.get("hand_r");
	}

	@Override
	public void setupAnim(Pose pose) {
		super.setupAnim(pose);
		for (ModelPart part : allParts()) {
			part.visible = true;
		}
		if (pose.casingOnly) {
			for (ModelPart part : allParts()) {
				part.visible = false;
			}
			root.visible = true;
			if (casing != null) {
				casing.visible = true;
				casing.getAllParts().forEach(p -> p.visible = true);
			}
			return;
		}
		List<String> att = pose.attachments;
		boolean dot = att.contains("RED_DOT");
		boolean quiet = att.contains("SUPPRESSOR");
		boolean ext = att.contains("EXTENDED_MAG");
		show(redDot, dot);
		show(irons, !dot);
		show(suppressor, quiet);
		show(muzzleDevice, !quiet);
		show(grip, att.contains("GRIP"));
		show(magExt, ext);
		show(magStd, !ext || magExt == null);
		show(rocket, pose.loaded);
		show(casing, false);
		show(flash, pose.flash != 0 && !quiet);
		if (flash != null && pose.flash != 0) {
			flash.zRot = (float) Math.toRadians(pose.flash - 1);
		}
		if (mag != null) {
			mag.visible = !pose.magHidden;
			mag.y += pose.magDrop * U;
			mag.xRot += (float) Math.toRadians(pose.magTilt);
		}
		if (bolt != null) {
			bolt.z -= pose.boltBack * U;
			bolt.zRot += (float) Math.toRadians(pose.boltLift);
		}
		if (slide != null) {
			slide.z -= pose.slideBack * U;
		}
		if (pump != null) {
			pump.z -= pose.pumpBack * U;
		}
		if (trigger != null) {
			trigger.xRot += pose.trigger * 0.5F;
		}
		if (hammer != null) {
			hammer.xRot += pose.slideBack > 0.1F ? -0.9F : 0.0F;
		}
		if (rocket != null) {
			rocket.z += pose.rocketBack * U;
		}
		if (bipod != null && pose.bipodDown) {
			bipod.xRot = 0;
		}
	}

	private static void show(ModelPart part, boolean visible) {
		if (part != null) {
			part.visible = visible;
		}
	}

	/** Draws the gun at the current PoseStack (origin = the grip), 1/16 block per gun pixel, then its glow pass. */
	public void submit(Pose state, PoseStack pose, SubmitNodeCollector collector, int light, int overlay, int outline) {
		pose.pushPose();
		float s = 1.0F / U;
		pose.scale(-s, -s, s);
		collector.submitModel(this, state, pose, RenderTypes.entityCutout(state.rift ? riftTexture : texture), light, overlay, outline);
		collector.submitModel(this, state, pose, RenderTypes.eyes(state.rift ? riftGlow : glow), 0xF000F0, OverlayTexture.NO_OVERLAY, 0);
		pose.popPose();
	}
}
