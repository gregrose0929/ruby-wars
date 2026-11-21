package dev.rubymod.item;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.registry.ModBlocks;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Prospector's Pick: right-click and it chimes (higher when closer) and points a trail of red sparkles toward the
 * nearest Ruby Ore within 16 blocks, saying roughly where it is and whether you're warmer or colder than last time.
 */
public class ProspectorPickItem extends Item {
	public static final int RANGE = 16;
	/** How far the ore was the last time each player tried (for warmer and colder). */
	private static final Map<UUID, Double> LAST = new HashMap<>();

	public ProspectorPickItem(Properties properties) {
		super(properties);
	}

	/** The nearest Ruby Ore to {@code from} within {@link #RANGE} blocks, or null. */
	public static BlockPos nearest(Level level, BlockPos from) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(from.offset(-RANGE, -RANGE, -RANGE), from.offset(RANGE, RANGE, RANGE))) {
			if (p.getY() < level.getMinY() || p.getY() > level.getMaxY()) {
				continue;
			}
			var state = level.getBlockState(p);
			if (state.is(ModBlocks.RUBY_ORE) || state.is(ModBlocks.DEEPSLATE_RUBY_ORE)) {
				double d = p.distSqr(from);
				if (d < bestDist && d <= RANGE * RANGE) {
					bestDist = d;
					best = p.immutable();
				}
			}
		}
		return best;
	}

	/** "below you, ahead and to the left" from where you're facing. */
	public static String direction(Player player, BlockPos ore) {
		Vec3 eye = player.getEyePosition();
		Vec3 to = Vec3.atCenterOf(ore).subtract(eye);
		double flat = Math.sqrt(to.x * to.x + to.z * to.z);
		StringBuilder out = new StringBuilder();
		if (to.y < -1.5 && Math.abs(to.y) > flat * 0.5) {
			out.append("below you");
		} else if (to.y > 1.5 && Math.abs(to.y) > flat * 0.5) {
			out.append("above you");
		}
		if (flat >= 1.5) {
			Vec3 look = Vec3.directionFromRotation(0, player.getYRot());
			double ahead = (to.x * look.x + to.z * look.z) / flat;
			double side = (look.x * to.z - look.z * to.x) / flat;
			String fb = ahead > 0.4 ? "ahead" : ahead < -0.4 ? "behind you" : "";
			String lr = side > 0.4 ? "to the right" : side < -0.4 ? "to the left" : "";
			String flatWords = fb.isEmpty() ? lr : lr.isEmpty() ? fb : fb + " and " + lr;
			if (!flatWords.isEmpty()) {
				out.append(out.length() > 0 ? ", " : "").append(flatWords);
			}
		}
		return out.length() == 0 ? "right here" : out.toString();
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer sp)) {
			return InteractionResult.SUCCESS;
		}
		var stack = player.getItemInHand(hand);
		player.getCooldowns().addCooldown(stack, 15);
		BlockPos ore = nearest(level, player.blockPosition());
		if (ore == null) {
			LAST.remove(player.getUUID());
			server.playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.5F, 0.6F);
			sp.sendOverlayMessage(Component.literal("No Ruby Ore within " + RANGE + " blocks. "
					+ (player.getY() < 0 ? "Try somewhere else down here." : "It's found below Y 0: try deeper."))
					.withStyle(ChatFormatting.GRAY));
			return InteractionResult.SUCCESS;
		}
		double dist = Math.sqrt(ore.distSqr(player.blockPosition()));
		Double last = LAST.put(player.getUUID(), dist);
		String trend = last == null || Math.abs(last - dist) < 0.75 ? "" : dist < last ? "Warmer! " : "Colder. ";
		float closeness = (float) (1.0 - Math.min(1.0, dist / RANGE));
		server.playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.6F, 0.6F + 1.3F * closeness);
		// A short trail of sparkles from your eyes toward the ore (only you see it).
		Vec3 eye = player.getEyePosition().add(0, -0.3, 0);
		Vec3 dir = Vec3.atCenterOf(ore).subtract(eye).normalize();
		for (int i = 1; i <= 8; i++) {
			Vec3 at = eye.add(dir.scale(0.5 + i * 0.35));
			server.sendParticles(sp, new DustParticleOptions(0xFF2050, 0.9F), true, false, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		}
		int blocks = Math.max(1, (int) Math.round(dist));
		sp.sendOverlayMessage(Component.literal(trend + "Ruby Ore about " + blocks + (blocks == 1 ? " block" : " blocks") + " away: " + direction(player, ore))
				.withStyle(trend.startsWith("Warmer") ? ChatFormatting.GREEN : trend.startsWith("Colder") ? ChatFormatting.AQUA : ChatFormatting.LIGHT_PURPLE));
		return InteractionResult.SUCCESS;
	}
}
