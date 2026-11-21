package dev.rubymod.item;

import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/** A Geode, from drills and mining now and then: right-click to crack it open for a surprise (crouch: the whole stack). */
public class GeodeItem extends Item {
	public GeodeItem(Properties properties) {
		super(properties);
	}

	/** What's inside one. */
	public static ItemStack crack(RandomSource random) {
		float roll = random.nextFloat();
		if (roll < 0.45F) {
			return new ItemStack(Items.AMETHYST_SHARD, 3 + random.nextInt(4));
		}
		if (roll < 0.65F) {
			return new ItemStack(ModItems.RUBY, 1 + random.nextInt(3));
		}
		if (roll < 0.80F) {
			return new ItemStack(Items.QUARTZ, 2 + random.nextInt(3));
		}
		if (roll < 0.90F) {
			return new ItemStack(Items.LAPIS_LAZULI, 2 + random.nextInt(4));
		}
		if (roll < 0.97F) {
			return new ItemStack(Items.EMERALD);
		}
		return new ItemStack(Items.DIAMOND);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer sp)) {
			return InteractionResult.SUCCESS;
		}
		ItemStack geode = player.getItemInHand(hand);
		// Crouching: the whole stack at once.
		int n = player.isSecondaryUseActive() ? geode.getCount() : 1;
		java.util.Map<Item, Integer> got = new java.util.LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			ItemStack inside = crack(server.getRandom());
			got.merge(inside.getItem(), inside.getCount(), Integer::sum);
			if (!player.getInventory().add(inside)) {
				player.drop(inside, false, net.minecraft.util.Prediction.SERVER_ONLY);
			}
		}
		geode.consume(n, player);
		server.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 0.9F, 1.1F);
		// A puff of crystal chips at your feet (not in front of your eyes).
		var at = player.position().add(player.getLookAngle().multiply(1, 0, 1).normalize().scale(0.8));
		server.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, Items.AMETHYST_SHARD), at.x, at.y + 0.4, at.z, Math.min(30, 8 * n), 0.2, 0.1, 0.2, 0.05);
		sp.sendOverlayMessage(Component.literal((n == 1 ? "You cracked the Geode: " : "You cracked " + n + " Geodes: ") + summary(got) + "!")
				.withStyle(ChatFormatting.LIGHT_PURPLE));
		return InteractionResult.SUCCESS;
	}

	/** "14 Amethyst Shards, 2 Rubies and 1 Diamond", most first. */
	public static String summary(java.util.Map<Item, Integer> got) {
		var parts = got.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
				.map(e -> e.getValue() + " " + plural(new ItemStack(e.getKey()).getHoverName().getString(), e.getValue()))
				.toList();
		if (parts.size() == 1) {
			return parts.get(0);
		}
		return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
	}

	/** "Ruby" to "Rubies", "Diamond" to "Diamonds" (Quartz and Lapis Lazuli stay as they are). */
	public static String plural(String name, int n) {
		if (n == 1 || name.endsWith("Quartz") || name.endsWith("Lazuli") || name.endsWith("s")) {
			return name;
		}
		return name.endsWith("y") ? name.substring(0, name.length() - 1) + "ies" : name + "s";
	}
}
