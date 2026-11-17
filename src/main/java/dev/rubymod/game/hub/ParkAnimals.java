package dev.rubymod.game.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/** The park's animals you can pet (right-click), or give an Animal Snack. */
final class ParkAnimals {
	private ParkAnimals() {
	}

	private static final String SNACKS = "Animal Snacks";
	static final List<UUID> ANIMALS = new ArrayList<>();

	/** Right-clicking one of the animals: a pet (or a snack). True if it was one. */
	static boolean pet(ServerPlayer p, Entity e) {
		if (!ANIMALS.contains(e.getUUID())) {
			return false;
		}
		ServerLevel level = (ServerLevel) p.level();
		ItemStack held = p.getMainHandItem();
		boolean snack = HubGames.isToy(held) && held.getHoverName().getString().equals(SNACKS);
		level.sendParticles(snack ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.HEART, e.getX(), e.getY() + e.getBbHeight() + 0.3, e.getZ(), snack ? 8 : 3, 0.3, 0.2,
				0.3, 0.02);
		level.playSound(null, e.blockPosition(), snack ? SoundEvents.GENERIC_EAT.value() : SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 0.8F, 1.2F);
		String name = e.getType().getDescription().getString().toLowerCase();
		boolean little = e instanceof AgeableMob a && a.isBaby();
		p.sendOverlayMessage(Component.literal(snack ? "Munch munch! The " + (little ? "baby " : "") + name + " loves it!"
				: "You pet the " + (little ? "baby " : "") + name + ". It's very happy!").withStyle(ChatFormatting.GREEN));
		return true;
	}

	static void reset() {
		ANIMALS.clear();
	}
}
