package dev.rubymod.game;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.registry.ModBlocks;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * Spare Hearts (an admin option, off unless the admin turns it on: /rubywars spareheart on). The first time your Heart
 * is broken in Open War you get one Spare Heart: put it down on open ground (not in anyone's base) and you come back
 * there while it stands. It has no land around it, so it's just a place to come back to, and anyone can break it. Lose it
 * too and your next knockdown puts you out, as usual. Until you've put it down you come back where your Heart was.
 * Sudden Death takes Spare Hearts with everything else. So a Heart lost early in Open War isn't the end of your day.
 */
public final class SpareHearts {
	private SpareHearts() {
	}

	public static final int NONE = 0;
	/** Given, not put down yet. */
	public static final int CARRIED = 1;
	public static final int PLACED = 2;
	public static final int LOST = 3;

	/** /rubywars spareheart on|off. Returns what went wrong, or null. */
	public static String set(MinecraftServer server, boolean on) {
		SeasonData data = SeasonData.get();
		if (data.phase == Phase.SUDDEN_DEATH || data.phase == Phase.FINAL_CIRCLE || data.phase == Phase.ENDED) {
			return "Too late for Spare Hearts this season.";
		}
		data.spareHeartsAllowed = on;
		SeasonData.markDirty();
		Alerts.broadcast(server, Component.literal(on ? "Spare Hearts are on: the first time your Heart is broken in Open War, you get a Spare Heart to come back to."
				: "Spare Hearts are off: when your Heart is broken, your next knockdown puts you out.").withStyle(ChatFormatting.AQUA));
		return null;
	}

	/** A Heart was just broken: hand its owner their spare (once, in Open War, if the admin allows them). */
	public static void heartBroken(MinecraftServer server, SeasonData.Claim claim) {
		SeasonData data = SeasonData.get();
		SeasonData.PlayerRecord r = data.players.get(claim.owner);
		if (!data.spareHeartsAllowed || data.phase != Phase.WAR || r == null || r.spareState != NONE || r.eliminated) {
			return;
		}
		r.spareState = CARRIED;
		SeasonData.markDirty();
		ServerPlayer owner = server.getPlayerList().getPlayer(UUID.fromString(claim.owner));
		if (owner != null) {
			give(owner);
			owner.sendSystemMessage(Component.literal("You got a Spare Heart! Put it down on open ground (not in a base): you'll come back there while it stands.")
					.withStyle(ChatFormatting.GOLD));
		}
	}

	/** Make sure they're carrying it (it may have been left behind when they were knocked down). */
	public static void give(ServerPlayer player) {
		if (player.getInventory().contains(new ItemStack(ModBlocks.SPARE_HEART.asItem()))) {
			return;
		}
		ItemStack spare = new ItemStack(ModBlocks.SPARE_HEART.asItem());
		if (!player.getInventory().add(spare)) {
			player.drop(spare, false, net.minecraft.util.Prediction.SERVER_ONLY);
		}
	}

	/** Can this player put their Spare Heart down here? Null if yes, or why not. */
	public static String placeProblem(ServerLevel level, ServerPlayer player, BlockPos pos) {
		SeasonData.PlayerRecord r = SeasonData.get().player(player.getUUID());
		if (SeasonData.get().phase != Phase.WAR) {
			return "Spare Hearts only go down in Open War.";
		}
		if (r == null || r.spareState != CARRIED) {
			return "This isn't your Spare Heart to put down.";
		}
		SeasonData.Claim claim = Rules.baseAt(level, pos);
		if (claim != null && !claim.destroyed) {
			return "Put it on open ground, not in a base.";
		}
		return null;
	}

	/** Put down: they come back here now. */
	public static void placed(ServerPlayer player, BlockPos pos) {
		SeasonData.PlayerRecord r = SeasonData.get().player(player.getUUID());
		if (r == null) {
			return;
		}
		r.spareState = PLACED;
		r.spareAt = pos.asLong();
		SeasonData.markDirty();
		player.sendSystemMessage(Component.literal("Spare Heart down: you'll come back here while it stands. Keep it safe!").withStyle(ChatFormatting.GREEN));
	}

	private static boolean stands(MinecraftServer server, SeasonData.PlayerRecord r) {
		return r.spareState == PLACED && server.overworld().getBlockState(BlockPos.of(r.spareAt)).is(ModBlocks.SPARE_HEART);
	}

	/** Does this player still have somewhere to come back to, thanks to their spare? */
	public static boolean canRespawn(MinecraftServer server, UUID player) {
		SeasonData data = SeasonData.get();
		SeasonData.PlayerRecord r = data.player(player);
		return r != null && data.phase == Phase.WAR && (r.spareState == CARRIED || stands(server, r));
	}

	/** Back from a knockdown with no Heart: at the spare, or (not down yet) where their Heart was. Returns true if handled. */
	public static boolean respawn(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		SeasonData data = SeasonData.get();
		SeasonData.PlayerRecord r = data.player(player.getUUID());
		if (r == null || !canRespawn(server, player.getUUID())) {
			return false;
		}
		ServerLevel level = server.overworld();
		BlockPos at;
		if (r.spareState == PLACED) {
			at = BlockPos.of(r.spareAt);
		} else {
			SeasonData.Claim old = data.claimOf(player.getUUID());
			if (old == null) {
				return false;
			}
			at = old.pos();
			give(player);
			player.sendSystemMessage(Component.literal("Put your Spare Heart down soon: until you do, you come back here.").withStyle(ChatFormatting.GOLD));
		}
		BlockPos spot = BattleDirector.safeSpotNear(level, at);
		player.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, Set.<Relative>of(), player.getYRot(), player.getXRot(), true);
		SpawnShield.grant(player);
		return true;
	}

	/** Every second: a Spare Heart that's been broken is lost. */
	public static void tickSecond(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		if (!data.phase.isBattle()) {
			return;
		}
		for (Map.Entry<String, SeasonData.PlayerRecord> e : data.players.entrySet()) {
			SeasonData.PlayerRecord r = e.getValue();
			if (r.spareState == PLACED && !stands(server, r)) {
				r.spareState = LOST;
				SeasonData.markDirty();
				ServerPlayer owner = server.getPlayerList().getPlayer(UUID.fromString(e.getKey()));
				if (owner != null) {
					Alerts.title(owner, "SPARE HEART LOST", "Your next knockdown puts you out", ChatFormatting.DARK_RED);
				}
				Alerts.broadcast(server, Component.literal(r.name + "'s Spare Heart was broken.").withStyle(ChatFormatting.RED));
			}
		}
	}

	/** Sudden Death: every Spare Heart goes too. */
	public static void suddenDeath(MinecraftServer server) {
		for (SeasonData.PlayerRecord r : SeasonData.get().players.values()) {
			if (r.spareState == PLACED) {
				BlockPos pos = BlockPos.of(r.spareAt);
				if (server.overworld().getBlockState(pos).is(ModBlocks.SPARE_HEART)) {
					server.overworld().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
				}
			}
			if (r.spareState == PLACED || r.spareState == CARRIED) {
				r.spareState = LOST;
			}
		}
		SeasonData.markDirty();
	}
}
