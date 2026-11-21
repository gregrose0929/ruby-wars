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

/** A Geode, from drills and mining now and then: right-click to crack it open for a surprise. */
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
		ItemStack inside = crack(server.getRandom());
		geode.consume(1, player);
		String what = inside.getCount() + " " + inside.getHoverName().getString();
		if (!player.getInventory().add(inside)) {
			player.drop(inside, false, net.minecraft.util.Prediction.SERVER_ONLY);
		}
		server.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 0.9F, 1.1F);
		// A puff of crystal chips at your feet (not in front of your eyes).
		var at = player.position().add(player.getLookAngle().multiply(1, 0, 1).normalize().scale(0.8));
		server.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, Items.AMETHYST_SHARD), at.x, at.y + 0.4, at.z, 8, 0.2, 0.1, 0.2, 0.05);
		sp.sendOverlayMessage(Component.literal("You cracked the Geode: " + what + "!").withStyle(ChatFormatting.LIGHT_PURPLE));
		return InteractionResult.SUCCESS;
	}
}
