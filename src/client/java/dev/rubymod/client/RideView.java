package dev.rubymod.client;

import dev.rubymod.mixin.ItemDisplayAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

/**
 * Which things not to draw for you while you ride something in first person: the park ride vehicle you're in (and the
 * boat or raft under a bumper car or kart). Everyone else still sees you in it; press F5 and you see it too.
 */
public final class RideView {
	private RideView() {
	}

	public static boolean hide(Entity e) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !mc.options.getCameraType().isFirstPerson()) {
			return false;
		}
		// Morphed (the Morph Wand): your own animal isn't drawn while you look out through its eyes.
		if (e instanceof net.minecraft.world.entity.Mob m && m.isNoAi() && !m.isCustomNameVisible() && m.getCustomName() != null
				&& m.getCustomName().getString().equals(mc.player.getName().getString())) {
			return true;
		}
		Entity seat = mc.player.getVehicle();
		if (seat == null) {
			return false;
		}
		if (e == seat && e instanceof AbstractBoat) {
			return true;
		}
		if (!(e instanceof Display.ItemDisplay d) || !parkModel(d)) {
			return false;
		}
		// Riding with you (the same seat or boat, or the seat you're on rides it)...
		Entity v = e.getVehicle();
		if (v != null && (v == seat || v == seat.getVehicle() || seat.getVehicle() == e)) {
			return true;
		}
		// ...or part of the same car or train (the next seat along, the car in front), or the chair or ring round your seat.
		if (v != null) {
			double reach = v instanceof Display && seat instanceof Display ? 3.6 : 2.0;
			return v.position().distanceTo(seat.position()) < reach;
		}
		return e.position().distanceTo(seat.position()) < 2.6;
	}

	private static boolean parkModel(Display.ItemDisplay d) {
		Identifier model = ((ItemDisplayAccessor) d).rubymod$getItemStack().get(DataComponents.ITEM_MODEL);
		return model != null && model.getNamespace().equals("rubymod") && model.getPath().startsWith("park_");
	}
}
