package dev.rubymod.game;

import java.util.HashMap;
import java.util.Map;

import dev.rubymod.entity.CruiseMissileEntity;
import dev.rubymod.entity.MortarShellEntity;
import dev.rubymod.item.RangefinderItem;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Firing the Mortar and launching from the Missile Silo. */
public final class HeavyWeapons {
	private static final int MORTAR_MIN = 40;
	private static final int MORTAR_MAX = 250;
	private static final int MORTAR_RELOAD_TICKS = 100;
	private static final int VOLLEY = 5;
	private static final Map<Long, Long> MORTAR_READY_AT = new HashMap<>();

	private HeavyWeapons() {
	}

	public static void describe(ServerPlayer player, BlockPos pos, boolean mortar) {
		BlockPos target = RangefinderItem.target(player);
		String targetText = target == null ? "No target marked. Use a Rangefinder first."
				: "Target: " + target.getX() + ", " + target.getY() + ", " + target.getZ() + " (" + (int) Math.sqrt(target.distSqr(pos)) + " blocks)";
		if (mortar) {
			player.sendSystemMessage(Component.literal("Mortar: right-click with Mortar Shells to fire a 5-shell volley. Range "
					+ MORTAR_MIN + "-" + MORTAR_MAX + ". " + targetText));
		} else {
			SeasonData.PlayerRecord record = SeasonData.get().player(player.getUUID(), player.getName().getString());
			int left = GameConfig.get().maxMissilesPerPlayer - record.missilesUsed;
			player.sendSystemMessage(Component.literal("Missile Silo: right-click with a Cruise Missile to launch. Launches left this Battle Day: "
					+ left + ". " + targetText));
		}
	}

	private static boolean checkWar(ServerPlayer player) {
		if (Horde.playing(player)) {
			return true; // Co-op Horde: fire away
		}
		if (!Rules.phase().raidWeaponsActive()) {
			player.sendSystemMessage(Component.literal("Heavy weapons only fire during Open War on Battle Day.").withStyle(ChatFormatting.YELLOW), true);
			return false;
		}
		return true;
	}

	public static void fireMortar(ServerLevel level, ServerPlayer player, BlockPos mortar, ItemStack held) {
		if (!held.is(ModItems.MORTAR_SHELL)) {
			describe(player, mortar, true);
			return;
		}
		if (!checkWar(player)) {
			return;
		}
		BlockPos target = RangefinderItem.target(player);
		if (target == null) {
			player.sendSystemMessage(Component.literal("Mark a target with a Rangefinder first."), true);
			return;
		}
		double dx = target.getX() - mortar.getX();
		double dz = target.getZ() - mortar.getZ();
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (distance < MORTAR_MIN || distance > MORTAR_MAX) {
			player.sendSystemMessage(Component.literal("Target is " + (int) distance + " blocks away. Mortar range is " + MORTAR_MIN + "-" + MORTAR_MAX + ".")
					.withStyle(ChatFormatting.YELLOW), true);
			return;
		}
		long now = level.getGameTime();
		if (now < MORTAR_READY_AT.getOrDefault(mortar.asLong(), 0L)) {
			player.sendSystemMessage(Component.literal("Mortar is reloading..."), true);
			return;
		}
		MORTAR_READY_AT.put(mortar.asLong(), now + MORTAR_RELOAD_TICKS);

		int shells = player.isCreative() ? VOLLEY : Math.min(VOLLEY, held.getCount());
		Vec3 from = Vec3.atCenterOf(mortar).add(0, 1.0, 0);
		for (int i = 0; i < shells; i++) {
			Vec3 aim = Vec3.atCenterOf(target).add(level.getRandom().triangle(0, 4), 0, level.getRandom().triangle(0, 4));
			MortarShellEntity shell = MortarShellEntity.launch(level, player, from.add(0, i * 0.6, 0), aim);
			level.addFreshEntity(shell);
		}
		if (!player.isCreative()) {
			held.shrink(shells);
		}
		level.playSound(null, mortar, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 2.0F, 1.8F);
		player.sendSystemMessage(Component.literal("Shells away! " + shells + " rounds on target.").withStyle(ChatFormatting.GOLD), true);
	}

	public static void launchMissile(ServerLevel level, ServerPlayer player, BlockPos silo, ItemStack held) {
		if (!held.is(ModItems.CRUISE_MISSILE)) {
			describe(player, silo, false);
			return;
		}
		if (!checkWar(player)) {
			return;
		}
		BlockPos target = RangefinderItem.target(player);
		if (target == null) {
			player.sendSystemMessage(Component.literal("Mark a target with a Rangefinder first."), true);
			return;
		}
		SeasonData.PlayerRecord record = SeasonData.get().player(player.getUUID(), player.getName().getString());
		if (record.missilesUsed >= GameConfig.get().maxMissilesPerPlayer && !player.isCreative()) {
			player.sendSystemMessage(Component.literal("You've used all your cruise missiles this Battle Day.").withStyle(ChatFormatting.RED), true);
			return;
		}
		record.missilesUsed++;
		SeasonData.markDirty();
		if (!player.isCreative()) {
			held.shrink(1);
		}
		CruiseMissileEntity missile = CruiseMissileEntity.launch(level, player, Vec3.atCenterOf(silo).add(0, 1.5, 0), Vec3.atCenterOf(target));
		level.addFreshEntity(missile);
		level.playSound(null, silo, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 4.0F, 0.6F);
		Alerts.broadcast(level.getServer(), Component.literal("A cruise missile has been launched!").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
	}
}
