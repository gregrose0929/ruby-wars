package dev.rubymod.client;

import com.mojang.blaze3d.platform.InputConstants;

import dev.rubymod.RubyMod;
import dev.rubymod.entity.VehicleEntity;
import dev.rubymod.net.Payloads;
import dev.rubymod.registry.ModEntities;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

public class RubyModClient implements ClientModInitializer {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(RubyMod.id("ruby_wars"));
	public static KeyMapping reloadKey;

	@Override
	public void onInitializeClient() {
		reloadKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rubymod.reload", InputConstants.KEY_R, CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (reloadKey.consumeClick()) {
				if (client.player != null) {
					ClientPlayNetworking.send(new Payloads.Reload());
				}
			}
			Trigger.tick(client);
		});

		ClientPlayNetworking.registerGlobalReceiver(Payloads.Recoil.TYPE, (payload, context) -> {
			var player = context.player();
			player.setXRot(Math.max(-90, player.getXRot() - payload.pitch()));
			player.setYRot(player.getYRot() + payload.yaw());
		});
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
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientState.reset());

		HudElementRegistry.addLast(RubyMod.id("hud"), new GameHud());
		AutoTest.register();

		EntityRendererRegistry.register(ModEntities.FRAG_GRENADE, ThrownItemRenderer::new);
		EntityRendererRegistry.register(ModEntities.SMOKE_GRENADE, ThrownItemRenderer::new);
		EntityRendererRegistry.register(ModEntities.ROCKET, context -> new ThrownItemRenderer<>(context, 1.5F, true));
		EntityRendererRegistry.register(ModEntities.MORTAR_SHELL, context -> new ThrownItemRenderer<>(context, 1.5F, false));
		EntityRendererRegistry.register(ModEntities.CRUISE_MISSILE, context -> new ThrownItemRenderer<>(context, 3.0F, true));
		EntityRendererRegistry.register(ModEntities.BOMB, context -> new ThrownItemRenderer<>(context, 2.0F, false));
		EntityRendererRegistry.register(ModEntities.AIR_STRIKE_FLARE, context -> new ThrownItemRenderer<>(context, 1.0F, true));
		EntityRendererRegistry.register(ModEntities.BREACHING_CHARGE, ThrownItemRenderer::new);
		EntityRendererRegistry.register(ModEntities.JEEP, VehicleRenderer::new);
		EntityRendererRegistry.register(ModEntities.TANK, VehicleRenderer::new);
	}
}
