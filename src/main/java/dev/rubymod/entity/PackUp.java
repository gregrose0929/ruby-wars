package dev.rubymod.entity;

import java.util.UUID;

import dev.rubymod.game.Hub;
import dev.rubymod.game.Rules;
import dev.rubymod.game.SeasonData;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * Packing a vehicle back up: crouch and right-click your own (empty) jeep, bike, boat, borer or aircraft and it goes
 * back in your inventory, with its paint and the damage it's taken (so it's no free repair). Your squad can pack yours
 * too. Vehicles from before this existed have no owner: those pack up when they're parked in a base you can use. Only
 * in the Ruby Wars world (the party games' and the Rift's vehicles stay put).
 *
 * Parked at home (in a base its owner can use), empty and left alone for a while, a damaged vehicle mends itself.
 */
public final class PackUp {
	private PackUp() {
	}

	private static final String OWNER = "rubymod_owner:";

	/** Mark who put this vehicle down. */
	public static void own(Mob vehicle, Player player) {
		vehicle.addTag(OWNER + player.getUUID());
	}

	/** Who put it down (null: nobody known). */
	public static UUID owner(Mob vehicle) {
		for (String t : vehicle.entityTags()) {
			if (t.startsWith(OWNER)) {
				try {
					return UUID.fromString(t.substring(OWNER.length()));
				} catch (IllegalArgumentException e) {
					return null;
				}
			}
		}
		return null;
	}

	/** Can this player pack it up? (Why not, or null if they can.) */
	public static String refusal(Mob vehicle, Player player) {
		if (!Hub.inRubyWarsWorld(player)) {
			return "Vehicles here belong to the game: they can't be packed up.";
		}
		if (vehicle.isVehicle()) {
			return "Everyone has to get out first.";
		}
		UUID owner = owner(vehicle);
		if (owner != null) {
			return owner.equals(player.getUUID()) || dev.rubymod.game.Squads.same(owner, player.getUUID()) ? null : "That's not yours to pack up.";
		}
		SeasonData.Claim base = Rules.baseAt(vehicle.level(), vehicle.blockPosition());
		return base != null && base.canUse(player.getUUID()) ? null : "Only its owner can pack this up (or anyone, parked in their own base).";
	}

	/** Crouch-right-click: pack it up if it's theirs. Whether that's what happened (so the click's used up). */
	public static boolean tryPackUp(Mob vehicle, Player player, int paint) {
		if (!player.isSecondaryUseActive() || !(player instanceof ServerPlayer sp) || !(vehicle.level() instanceof ServerLevel level)) {
			return player.isSecondaryUseActive() && !vehicle.isVehicle();
		}
		String no = refusal(vehicle, player);
		if (no != null) {
			sp.sendOverlayMessage(Component.literal(no).withStyle(ChatFormatting.YELLOW));
			return true;
		}
		ItemStack item = item(vehicle.getType());
		if (item.isEmpty()) {
			return false;
		}
		CompoundTag tag = new CompoundTag();
		tag.putInt("paint", paint);
		if (vehicle.getHealth() < vehicle.getMaxHealth()) {
			tag.putFloat("health", vehicle.getHealth());
		}
		item.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		vehicle.ejectPassengers();
		vehicle.discard();
		if (!player.getInventory().add(item)) {
			player.drop(item, false, net.minecraft.util.Prediction.SERVER_ONLY);
		}
		level.playSound(null, vehicle.getX(), vehicle.getY(), vehicle.getZ(), SoundEvents.IRON_GOLEM_REPAIR, SoundSource.PLAYERS, 1.0F, 1.3F);
		sp.sendOverlayMessage(Component.literal("Packed up: it's in your inventory.").withStyle(ChatFormatting.GREEN));
		return true;
	}

	/** The item that puts this kind of vehicle down. */
	public static ItemStack item(EntityType<?> type) {
		Item item = BuiltInRegistries.ITEM.getValue(EntityType.getKey(type));
		return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
	}

	/** A vehicle just put down from this item: its owner, and the paint and damage it was packed up with. */
	public static void placed(Mob vehicle, Player player, ItemStack from) {
		own(vehicle, player);
		CustomData data = from.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return;
		}
		CompoundTag tag = data.copyTag();
		int paint = tag.getIntOr("paint", 0);
		if (vehicle instanceof VehicleEntity v) {
			v.setPaint(paint);
		} else if (vehicle instanceof AircraftEntity a) {
			a.setPaint(paint);
		}
		float health = tag.getFloatOr("health", -1);
		if (health > 0) {
			vehicle.setHealth(Math.min(health, vehicle.getMaxHealth()));
		}
	}

	// ----- Repairs at home -----

	/** When each vehicle was last hit (game time): it only mends once it's been left alone a while. */
	private static final java.util.Map<UUID, Long> LAST_HIT = new java.util.HashMap<>();

	/** Seconds after the last hit before repairs start. */
	public static final int MEND_AFTER = 10;

	public static void hit(Mob vehicle) {
		LAST_HIT.put(vehicle.getUUID(), vehicle.level().getGameTime());
	}

	/**
	 * Once a second, on the server: a damaged vehicle parked in a base (its owner's, or one its owner can use; one with no
	 * owner, any base), with nobody in it and not hit for a while, mends a little (empty to full in under a minute).
	 * Whether it did.
	 */
	public static boolean mend(Mob vehicle) {
		if (!(vehicle.level() instanceof ServerLevel level) || vehicle.isVehicle() || vehicle.getHealth() >= vehicle.getMaxHealth() || !vehicle.isAlive()) {
			return false;
		}
		if (level.getGameTime() - LAST_HIT.getOrDefault(vehicle.getUUID(), -100000L) < MEND_AFTER * 20L) {
			return false;
		}
		SeasonData.Claim base = Rules.baseAt(level, vehicle.blockPosition());
		UUID owner = owner(vehicle);
		if (base == null || base.destroyed || owner != null && !base.canUse(owner)) {
			return false;
		}
		vehicle.setHealth(Math.min(vehicle.getMaxHealth(), vehicle.getHealth() + vehicle.getMaxHealth() * 0.02F));
		if (level.getGameTime() % 40 < 20) {
			level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, vehicle.getX(), vehicle.getY() + vehicle.getBbHeight() * 0.7,
					vehicle.getZ(), 3, vehicle.getBbWidth() * 0.4, 0.3, vehicle.getBbWidth() * 0.4, 0);
		}
		return true;
	}

	/** For the item's tooltip: how it was left (if it was packed up damaged), or null. */
	public static Component condition(ItemStack stack, float maxHealth) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		float health = data.copyTag().getFloatOr("health", -1);
		return health > 0 ? Component.literal("Armor: " + Math.round(health) + " / " + Math.round(maxHealth) + " (packed up damaged)").withStyle(ChatFormatting.GOLD)
				: null;
	}
}
