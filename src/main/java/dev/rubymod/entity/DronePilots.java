package dev.rubymod.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

/** Who's flying which Scout Drone (one each), and the pilot's controls coming in. */
public final class DronePilots {
	private DronePilots() {
	}

	private static final Map<UUID, ScoutDroneEntity> FLYING = new HashMap<>();

	public static boolean flying(ServerPlayer p) {
		ScoutDroneEntity d = FLYING.get(p.getUUID());
		return d != null && !d.isRemoved();
	}

	/** Up it goes, and the pilot's view goes with it. */
	public static boolean launch(ServerLevel level, ServerPlayer p) {
		if (flying(p)) {
			p.sendOverlayMessage(Component.literal("You're already flying a drone! Right-click to bring it home first.").withStyle(ChatFormatting.YELLOW));
			return false;
		}
		ScoutDroneEntity drone = ScoutDroneEntity.launch(level, p);
		if (drone == null || !level.addFreshEntity(drone)) {
			return false;
		}
		FLYING.put(p.getUUID(), drone);
		Net.send(p, new MorePayloads.DroneView(drone.getId()));
		level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEEHIVE_EXIT, SoundSource.PLAYERS, 1.0F, 1.8F);
		p.sendSystemMessage(Component.literal("Scout Drone up! W A S D to fly, Space up, Shift down. Enemies it sees glow for your squad. Right-click to bring it home.")
				.withStyle(ChatFormatting.AQUA));
		return true;
	}

	/** The pilot's controls (from their client, every tick while flying). */
	public static void input(ServerPlayer p, MorePayloads.DroneInput in) {
		ScoutDroneEntity d = FLYING.get(p.getUUID());
		if (d == null || d.isRemoved()) {
			Net.send(p, new MorePayloads.DroneView(-1));
			return;
		}
		if (in.recall()) {
			recall(p);
			return;
		}
		d.steer(in.forward(), in.strafe(), in.up(), in.yaw(), in.pitch());
	}

	/** Back to the pilot's pocket (not lost: it can fly again). */
	public static void recall(ServerPlayer p) {
		ScoutDroneEntity d = FLYING.remove(p.getUUID());
		Net.send(p, new MorePayloads.DroneView(-1));
		if (d == null || d.isRemoved() || d.downed()) {
			return;
		}
		d.discard();
		ItemStack back = new ItemStack(ModItems.SCOUT_DRONE);
		if (!p.getInventory().add(back)) {
			p.drop(back, false, net.minecraft.util.Prediction.SERVER_ONLY);
		}
		p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEEHIVE_ENTER, SoundSource.PLAYERS, 1.0F, 1.8F);
		p.sendOverlayMessage(Component.literal("Drone home.").withStyle(ChatFormatting.AQUA));
	}

	/** Shot down: the pilot's view comes back to them. */
	static void lost(ServerPlayer p, ScoutDroneEntity d) {
		if (FLYING.get(p.getUUID()) == d) {
			FLYING.remove(p.getUUID());
			Net.send(p, new MorePayloads.DroneView(-1));
			dev.rubymod.game.Alerts.title(p, "", "Your drone was shot down!", ChatFormatting.RED);
		}
	}

	/** It went (fell, or its pilot left): stop watching it. */
	static void gone(ServerPlayer p, ScoutDroneEntity d) {
		if (FLYING.get(p.getUUID()) == d) {
			FLYING.remove(p.getUUID());
			Net.send(p, new MorePayloads.DroneView(-1));
		}
	}

	/** The pilot got hurt, changed world or left: the drone comes home. */
	public static void interrupt(ServerPlayer p) {
		if (flying(p)) {
			recall(p);
		}
	}
}
