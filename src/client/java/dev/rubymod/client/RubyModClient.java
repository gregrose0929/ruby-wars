package dev.rubymod.client;

import com.mojang.blaze3d.platform.InputConstants;

import dev.rubymod.RubyMod;
import dev.rubymod.entity.VehicleEntity;
import dev.rubymod.net.Payloads;
import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModEntities;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

public class RubyModClient implements ClientModInitializer {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(RubyMod.id("ruby_wars"));
	public static KeyMapping reloadKey;

	/** T in the cockpit: designate a target (shares its default key with chat; see MinecraftMixin). */
	public static KeyMapping designateKey;

	@Override
	public void onInitializeClient() {
		dev.rubymod.client.model.GunModel.registerLayers();
		dev.rubymod.client.mixin.SpecialModelRenderersAccessor.rubymod$idMapper().put(RubyMod.id("gun"),
				dev.rubymod.client.gun.GunItemRenderer.Unbaked.MAP_CODEC);
		dev.rubymod.client.mixin.SpecialModelRenderersAccessor.rubymod$idMapper().put(RubyMod.id("model_icon"),
				dev.rubymod.client.gun.ModelIconRenderer.Unbaked.MAP_CODEC);
		reloadKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rubymod.reload", InputConstants.KEY_R, CATEGORY));
		designateKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rubymod.designate", InputConstants.KEY_T, CATEGORY));
		KeyMapping weaponCamKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rubymod.weapon_cam", InputConstants.KEY_V, CATEGORY));
		KeyMapping gearKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rubymod.landing_gear", InputConstants.KEY_G, CATEGORY));
		dev.rubymod.entity.AircraftEntity.crashReporter = severity -> ClientPlayNetworking.send(new Payloads.AircraftCrash((float) severity));
		KeyMapping pingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rubymod.ping", InputConstants.KEY_B, CATEGORY));
		KeyMapping mapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rubymod.map", InputConstants.KEY_M, CATEGORY));
		KeyMapping researchKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rubymod.research", InputConstants.KEY_J, CATEGORY));
		KeyMapping freeLookKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rubymod.free_look", InputConstants.KEY_LALT, CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (reloadKey.consumeClick()) {
				if (Targeting.piloting(client) != null) {
					ClientPlayNetworking.send(new Payloads.AircraftCycle());
				} else if (client.player != null) {
					ClientPlayNetworking.send(new Payloads.Reload());
				}
			}
			Vehicles.tick(client, freeLookKey.isDown());
			dev.rubymod.client.map.MapCache.tick(client);
			EngineSounds.tick(client);
			dev.rubymod.client.map.LookPanel.tick(client);
			while (pingKey.consumeClick()) {
				dev.rubymod.client.map.Pinger.ping(client);
			}
			while (researchKey.consumeClick()) {
				if (client.player != null && client.gui.screen() == null) {
					client.gui.setScreen(new ResearchScreen());
				}
			}
			while (mapKey.consumeClick()) {
				if (client.player != null && client.gui.screen() == null) {
					client.gui.setScreen(new dev.rubymod.client.map.MapScreen());
				}
			}
			if (Targeting.piloting(client) != null) {
				// In the cockpit T designates (instead of opening chat; Enter still opens chat).
				while (designateKey.consumeClick()) {
					Designation.designate(client);
				}
				while (weaponCamKey.consumeClick()) {
					Designation.toggleCam(client);
				}
			}
			Designation.tick(client);
			while (gearKey.consumeClick()) {
				if (Targeting.piloting(client) != null) {
					ClientPlayNetworking.send(new Payloads.AircraftGear());
				}
			}
			Targeting.tick(client);
			LauncherSeeker.tick(client);
			BuildPreview.tick(client);
			Trigger.tick(client);
			if (client.player != null && ClientState.shakeTicks > 0) {
				ClientState.shakeTicks--;
				float s = ClientState.shake * (ClientState.shakeTicks / 20.0F + 0.2F);
				var random = client.player.getRandom();
				client.player.setXRot(client.player.getXRot() + (random.nextFloat() - 0.5F) * s);
				client.player.setYRot(client.player.getYRot() + (random.nextFloat() - 0.5F) * s);
				if (ClientState.shakeTicks == 0) {
					ClientState.shake = 0;
				}
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(Payloads.Recoil.TYPE, (payload, context) -> {
			dev.rubymod.client.gun.GunView.onShot();
			var player = context.player();
			player.setXRot(Math.max(-90, player.getXRot() - payload.pitch()));
			player.setYRot(player.getYRot() + payload.yaw());
		});
		ClientPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.MapSync.TYPE, (payload, context) -> ClientState.map = payload);
		ClientPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.ResearchSync.TYPE, (payload, context) -> ClientState.research = payload);
		ClientPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.LookInfo.TYPE, (payload, context) -> {
			ClientState.look = payload;
			ClientState.lookAt = System.currentTimeMillis();
		});
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(dev.rubymod.client.map.BlueprintGhost::render);
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(dev.rubymod.client.gun.BulletHoles::render);
		ClientPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.BulletHole.TYPE, (payload, context) ->
				dev.rubymod.client.gun.BulletHoles.add(payload));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.ReloadState.TYPE, (payload, context) -> {
			ClientState.reloadTicks = payload.ticks();
			ClientState.reloadStartedAt = System.currentTimeMillis();
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.HitMarker.TYPE, (payload, context) -> {
			ClientState.hitMarkerAt = System.currentTimeMillis();
			ClientState.hitHeadshot = payload.headshot();
			ClientState.hitKill = payload.kill();
			float pitch = payload.kill() ? 0.6F : payload.headshot() ? 1.8F : 1.3F;
			context.client().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ARROW_HIT_PLAYER, pitch, 0.5F));
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.HudSync.TYPE, (payload, context) -> {
			ClientState.hud = payload;
			ClientState.hudReceivedAt = System.currentTimeMillis();
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Radar.TYPE, (payload, context) -> {
			ClientState.radar = payload;
			ClientState.radarReceivedAt = System.currentTimeMillis();
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.KillFeed.TYPE, (payload, context) -> {
			ClientState.KILLS.addFirst(new ClientState.Kill(payload, System.currentTimeMillis()));
			while (ClientState.KILLS.size() > 5) {
				ClientState.KILLS.removeLast();
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.DamageFrom.TYPE, (payload, context) -> {
			ClientState.damagedAt = System.currentTimeMillis();
			ClientState.damageX = payload.x();
			ClientState.damageZ = payload.z();
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Economy.TYPE, (payload, context) -> ClientState.economy = payload);
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Contacts.TYPE, (payload, context) -> Contacts.receive(payload));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.HitConfirm.TYPE, (payload, context) -> {
			ClientState.hitConfirm = payload;
			ClientState.hitConfirmAt = System.currentTimeMillis();
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Shake.TYPE, (payload, context) -> {
			ClientState.shake = Math.max(ClientState.shake, payload.strength());
			ClientState.shakeTicks = Math.max(ClientState.shakeTicks, payload.ticks());
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientState.reset());

		HudElementRegistry.addLast(RubyMod.id("markers"), new dev.rubymod.client.map.Markers());
		HudElementRegistry.addLast(RubyMod.id("hud"), new GameHud());
		HudElementRegistry.addLast(RubyMod.id("minimap"), new dev.rubymod.client.map.Minimap());
		HudElementRegistry.addLast(RubyMod.id("look"), new dev.rubymod.client.map.LookPanel());
		HudElementRegistry.addLast(RubyMod.id("pilot_hud"), new PilotHud());
		HudElementRegistry.addLast(RubyMod.id("vehicle_hud"), new VehicleHud());
		HudElementRegistry.addLast(RubyMod.id("launcher_seeker"), new LauncherSeeker());
		// In a vehicle the vehicle HUD replaces the hotbar, hearts, hunger, armor, air and XP.
		for (var element : java.util.List.of(VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR,
				VanillaHudElements.ARMOR_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.INFO_BAR,
				VanillaHudElements.HELD_ITEM_TOOLTIP)) {
			HudElementRegistry.replaceElement(element, vanilla -> (g, delta) -> {
				if (!Vehicles.inVehicle(Minecraft.getInstance())) {
					vanilla.extractRenderState(g, delta);
				}
			});
		}
		// The pilot HUD shows hull %, so hide the rows of vanilla mount hearts while flying.
		HudElementRegistry.replaceElement(VanillaHudElements.MOUNT_HEALTH,
				vanilla -> (g, delta) -> {
					if (!Vehicles.inVehicle(Minecraft.getInstance())) {
						vanilla.extractRenderState(g, delta);
					}
				});
		AutoTest.register();

		EntityRendererRegistry.register(ModEntities.FRAG_GRENADE, ThrownItemRenderer::new);
		EntityRendererRegistry.register(ModEntities.SMOKE_GRENADE, ThrownItemRenderer::new);
		EntityRendererRegistry.register(ModEntities.AIR_STRIKE_FLARE, context -> new ThrownItemRenderer<>(context, 1.0F, true));
		EntityRendererRegistry.register(ModEntities.BREACHING_CHARGE, ThrownItemRenderer::new);
		EntityRendererRegistry.register(ModEntities.JEEP, VehicleRenderer::new);
		EntityRendererRegistry.register(ModEntities.TANK, VehicleRenderer::new);
		net.minecraft.client.gui.screens.MenuScreens.register(dev.rubymod.registry.ModMenus.MACHINE, MachineScreen::new);
		net.minecraft.client.gui.screens.MenuScreens.register(dev.rubymod.registry.ModMenus.ARMORY, ArmoryScreen::new);
		AircraftRenderer.registerLayers();
		VehicleRenderer.registerLayers();
		DroneRenderer.registerLayer();
		EntityRendererRegistry.register(ModEntities.SOLDIER, SoldierRenderer::new);
		EntityRendererRegistry.register(ModEntities.RECON_DRONE, DroneRenderer::new);
		HeavyAAGunRenderer.registerLayer();
		EntityRendererRegistry.register(ModEntities.HEAVY_AA_GUN, HeavyAAGunRenderer::new);
		EntityRendererRegistry.register(ModEntities.RAILGUN, HeavyAAGunRenderer::railgun);
		EntityRendererRegistry.register(ModEntities.FIGHTER_JET, AircraftRenderer::new);
		EntityRendererRegistry.register(ModEntities.ATTACK_JET, AircraftRenderer::new);
		EntityRendererRegistry.register(ModEntities.BOMBER, AircraftRenderer::new);
		EntityRendererRegistry.register(ModEntities.ATTACK_HELI, AircraftRenderer::new);
		EntityRendererRegistry.register(ModEntities.STICKY_CHARGE, ThrownItemRenderer::new);
		EntityRendererRegistry.register(ModEntities.WRECK, net.minecraft.client.renderer.entity.NoopRenderer::new);
		EntityRendererRegistry.register(ModEntities.TARGET_DUMMY, net.minecraft.client.renderer.entity.ArmorStandRenderer::new);
		EntityRendererRegistry.register(ModEntities.ROCKET,
				context -> new ProjectileModelRenderer<>(context, r -> r.isShell() ? ModBlocks.TANK_SHELL_MODEL : ModBlocks.ROCKET_MODEL, 1.6F));
		EntityRendererRegistry.register(ModEntities.MORTAR_SHELL, context -> new ProjectileModelRenderer<>(context, e -> ModBlocks.MORTAR_SHELL_MODEL, 1.4F));
		EntityRendererRegistry.register(ModEntities.CRUISE_MISSILE, context -> new ProjectileModelRenderer<>(context, e -> ModBlocks.CRUISE_MISSILE_MODEL, 2.2F));
		EntityRendererRegistry.register(ModEntities.BOMB, context -> new ProjectileModelRenderer<>(context, e -> ModBlocks.BOMB_MODEL, 1.6F));
	}
}
