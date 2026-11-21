package dev.rubymod.item;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.industry.DrillSite;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Prospector's Pick: right-click and it chimes (higher when closer) and points a trail of sparkles toward the nearest
 * ore it's looking for within 16 blocks, saying roughly where it is and whether you're warmer or colder than your
 * last try. Crouch and right-click to choose what it looks for: Ruby Ore, Diamonds or any ore.
 */
public class ProspectorPickItem extends Item {
	public static final int RANGE = 16;
	/** A try older than this, or from further away than this, doesn't count for warmer and colder. */
	private static final long FORGET_TICKS = 60 * 20;
	private static final double FORGET_MOVED = 12;

	/** What it looks for. */
	public enum Mode {
		RUBY("Ruby Ore", 0xFF2050), DIAMOND("Diamonds", 0x40E0FF), ANY("Any ore", 0xFFD040);

		public final String label;
		public final int color;

		Mode(String label, int color) {
			this.label = label;
			this.color = color;
		}

		boolean matches(BlockState state) {
			Item mineral = DrillSite.mineral(state);
			return switch (this) {
				case RUBY -> mineral == ModItems.RUBY;
				case DIAMOND -> mineral == Items.DIAMOND;
				case ANY -> mineral != null;
			};
		}
	}

	/** Each player's last try: how far, from where, when, and looking for what. */
	private record Try(double dist, Vec3 from, long time, Mode mode) {
	}

	private static final Map<UUID, Try> LAST = new HashMap<>();

	public ProspectorPickItem(Properties properties) {
		super(properties);
	}

	public static Mode mode(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		int i = data == null ? 0 : data.copyTag().getIntOr("find", 0);
		return Mode.values()[Math.floorMod(i, Mode.values().length)];
	}

	public static void setMode(ItemStack stack, Mode mode) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("find", mode.ordinal());
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(DataComponents.LORE, lore(mode));
	}

	/** The tooltip lines: what it's looking for. */
	public static ItemLore lore(Mode mode) {
		return new ItemLore(List.of(
				Component.literal("Looking for: " + mode.label).withStyle(st -> st.withItalic(false).withColor(ChatFormatting.GOLD)),
				Component.literal("Crouch and right-click to change").withStyle(st -> st.withItalic(false).withColor(ChatFormatting.GRAY))));
	}

	/** The nearest Ruby Ore to {@code from} within {@link #RANGE} blocks, or null. */
	public static BlockPos nearest(Level level, BlockPos from) {
		return nearest(level, from, Mode.RUBY);
	}

	/** The nearest ore of the kind {@code mode} looks for, within {@link #RANGE} blocks, or null. */
	public static BlockPos nearest(Level level, BlockPos from, Mode mode) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(from.offset(-RANGE, -RANGE, -RANGE), from.offset(RANGE, RANGE, RANGE))) {
			if (p.getY() < level.getMinY() || p.getY() > level.getMaxY()) {
				continue;
			}
			double d = p.distSqr(from);
			if (d >= bestDist || d > RANGE * RANGE) {
				continue;
			}
			if (mode.matches(level.getBlockState(p))) {
				bestDist = d;
				best = p.immutable();
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

	/** "Warmer! ", "Colder. " or "" against this player's last try (only a recent one, from nearby). */
	public static String trend(UUID player, double dist, Vec3 from, long now, Mode mode) {
		Try last = LAST.put(player, new Try(dist, from, now, mode));
		if (last == null || last.mode != mode || now - last.time > FORGET_TICKS || now < last.time
				|| last.from.distanceTo(from) > FORGET_MOVED || Math.abs(last.dist - dist) < 0.75) {
			return "";
		}
		return dist < last.dist ? "Warmer! " : "Colder. ";
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer sp)) {
			return InteractionResult.SUCCESS;
		}
		var stack = player.getItemInHand(hand);
		Mode mode = mode(stack);
		if (player.isSecondaryUseActive()) {
			// Crouching: look for something else.
			mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
			setMode(stack, mode);
			LAST.remove(player.getUUID());
			server.playSound(null, player.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.4F, 1.4F);
			sp.sendOverlayMessage(Component.literal("Prospector's Pick: now looking for " + mode.label).withStyle(ChatFormatting.GOLD));
			return InteractionResult.SUCCESS;
		}
		player.getCooldowns().addCooldown(stack, 15);
		BlockPos ore = nearest(level, player.blockPosition(), mode);
		if (ore == null) {
			LAST.remove(player.getUUID());
			server.playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.5F, 0.6F);
			String hint = switch (mode) {
				case RUBY -> player.getY() < 0 ? "Try somewhere else down here." : "It's found below Y 0: try deeper.";
				case DIAMOND -> player.getY() < 0 ? "Try somewhere else down here." : "They're found far below Y 0: try deeper.";
				case ANY -> "Try a cave, or dig down.";
			};
			sp.sendOverlayMessage(Component.literal("No " + (mode == Mode.ANY ? "ore" : mode.label) + " within " + RANGE + " blocks. " + hint)
					.withStyle(ChatFormatting.GRAY));
			return InteractionResult.SUCCESS;
		}
		double dist = Math.sqrt(ore.distSqr(player.blockPosition()));
		String trend = trend(player.getUUID(), dist, player.position(), server.getGameTime(), mode);
		float closeness = (float) (1.0 - Math.min(1.0, dist / RANGE));
		server.playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.6F, 0.6F + 1.3F * closeness);
		// A short trail of sparkles from your eyes toward the ore (only you see it).
		Vec3 eye = player.getEyePosition().add(0, -0.3, 0);
		Vec3 dir = Vec3.atCenterOf(ore).subtract(eye).normalize();
		for (int i = 1; i <= 8; i++) {
			Vec3 at = eye.add(dir.scale(0.5 + i * 0.35));
			server.sendParticles(sp, new DustParticleOptions(mode.color, 0.9F), true, false, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		}
		int blocks = Math.max(1, (int) Math.round(dist));
		String what = level.getBlockState(ore).getBlock().getName().getString();
		sp.sendOverlayMessage(Component.literal(trend + what + " about " + blocks + (blocks == 1 ? " block" : " blocks") + " away: " + direction(player, ore))
				.withStyle(trend.startsWith("Warmer") ? ChatFormatting.GREEN : trend.startsWith("Colder") ? ChatFormatting.AQUA : ChatFormatting.LIGHT_PURPLE));
		return InteractionResult.SUCCESS;
	}
}
