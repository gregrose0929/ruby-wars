package dev.rubymod.item;

import java.util.function.Consumer;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.VehicleEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * Paint Kit: right-click a vehicle or aircraft to respray it in the next scheme
 * (woodland, desert, arctic, urban, night, ruby). Reusable.
 */
public class PaintKitItem extends Item {
	/** Texture suffixes; index 0 is the factory paint (no suffix). */
	public static final String[] SCHEMES = {"", "desert", "arctic", "urban", "night", "ruby"};
	private static final String[] NAMES = {"Woodland", "Desert", "Arctic", "Urban", "Night", "Ruby"};
	private static final int[] COLORS = {0x5A6640, 0xBAA070, 0xD6DADE, 0x707278, 0x2C2E32, 0x96182C};

	public PaintKitItem(Properties properties) {
		super(properties);
	}

	public static String scheme(int paint) {
		return paint > 0 && paint < SCHEMES.length ? SCHEMES[paint] : "";
	}

	@Override
	public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
		if (!(target instanceof VehicleEntity) && !(target instanceof AircraftEntity)) {
			return InteractionResult.PASS;
		}
		if (player instanceof ServerPlayer sp && player.level() instanceof ServerLevel level) {
			int next = ((target instanceof VehicleEntity v ? v.paint() : ((AircraftEntity) target).paint()) + 1) % SCHEMES.length;
			if (target instanceof VehicleEntity v) {
				v.setPaint(next);
			} else {
				((AircraftEntity) target).setPaint(next);
			}
			level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.BRUSH_GENERIC, SoundSource.PLAYERS, 1.0F, 0.8F);
			level.sendParticles(new DustParticleOptions(COLORS[next], 1.5F), target.getX(), target.getY() + 1, target.getZ(), 40,
					target.getBbWidth() * 0.4, 0.6, target.getBbWidth() * 0.4, 0.0);
			sp.sendOverlayMessage(Component.literal("Paint: " + NAMES[next]).withStyle(ChatFormatting.AQUA));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.literal("Right-click a vehicle or aircraft to repaint it").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal("Woodland, Desert, Arctic, Urban, Night, Ruby").withStyle(ChatFormatting.DARK_GRAY));
	}
}
