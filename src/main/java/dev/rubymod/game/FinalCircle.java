package dev.rubymod.game;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Where Battle Day ends up: the final circle is a different spot each season (somewhere on dry land inside the
 * border, not always the island's middle), picked when Battle Day starts and shown on everyone's map from Open War
 * on, so you can plan your way there. In Sudden Death the border closes on it smoothly, its middle sliding over as it
 * shrinks, so it never jumps past anyone. Kept in the season's save, so a restart carries on where it was.
 */
public final class FinalCircle {
	private FinalCircle() {
	}

	/** Pick this season's final circle (once). */
	public static void plan(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		if (data.finalSet) {
			return;
		}
		ServerLevel level = server.overworld();
		WorldBorder border = level.getWorldBorder();
		int end = GameConfig.get().borderEndSize;
		// Far enough inside that the whole circle fits in the border with room to spare.
		double reach = Math.max(0, (border.getSize() - end) / 2.0) * 0.6;
		RandomSource random = level.getRandom();
		int x = (int) Math.round(border.getCenterX());
		int z = (int) Math.round(border.getCenterZ());
		for (int tries = 0; tries < 40; tries++) {
			int tx = (int) Math.round(border.getCenterX() + (random.nextDouble() * 2 - 1) * reach);
			int tz = (int) Math.round(border.getCenterZ() + (random.nextDouble() * 2 - 1) * reach);
			level.getChunk(tx >> 4, tz >> 4);
			BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(tx, 0, tz));
			if (level.getFluidState(top.below()).isEmpty() && !level.getBlockState(top.below()).isAir()) {
				x = tx;
				z = tz;
				break;
			}
		}
		data.finalX = x;
		data.finalZ = z;
		data.finalSet = true;
		data.closeMs = 0;
		SeasonData.markDirty();
		dev.rubymod.RubyMod.LOGGER.info("Final circle this season: {}, {} ({} across)", x, z, end);
	}

	/** Start closing the border on the final circle, over {@code ms}. */
	public static void close(MinecraftServer server, long ms) {
		plan(server);
		SeasonData data = SeasonData.get();
		WorldBorder border = server.overworld().getWorldBorder();
		data.closeFromX = border.getCenterX();
		data.closeFromZ = border.getCenterZ();
		data.closeFromSize = border.getSize();
		data.closeStart = SeasonTicker.now();
		data.closeMs = Math.max(1000, ms);
		SeasonData.markDirty();
		apply(server);
	}

	/** Straight there (the border's already small, or nearly). */
	public static void snap(MinecraftServer server) {
		plan(server);
		SeasonData data = SeasonData.get();
		WorldBorder border = server.overworld().getWorldBorder();
		data.closeMs = 0;
		border.setCenter(data.finalX + 0.5, data.finalZ + 0.5);
		border.setSize(GameConfig.get().borderEndSize);
		SeasonData.markDirty();
	}

	/** Once a second: the border a step closer, and everyone's map told where it's going. */
	public static void tick(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		if (data.closeMs > 0) {
			if (data.pausedRemaining >= 0) {
				data.closeStart += 1000; // paused: it waits too
			}
			apply(server);
		}
		boolean battle = data.phase == Phase.WAR || data.phase == Phase.SUDDEN_DEATH || data.phase == Phase.FINAL_CIRCLE;
		if (battle && !data.finalSet) {
			plan(server); // an admin skipped straight past Lockdown
		}
		boolean show = data.finalSet && battle;
		var plan = new dev.rubymod.net.MorePayloads.BorderPlan(data.finalX, data.finalZ, GameConfig.get().borderEndSize, show);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (show || server.getTickCount() % 200 < 20) {
				dev.rubymod.net.Net.send(p, plan);
			}
		}
	}

	private static void apply(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		double t = Math.max(0, Math.min(1, (SeasonTicker.now() - data.closeStart) / (double) data.closeMs));
		WorldBorder border = server.overworld().getWorldBorder();
		double end = GameConfig.get().borderEndSize;
		border.setCenter(data.closeFromX + (data.finalX + 0.5 - data.closeFromX) * t, data.closeFromZ + (data.finalZ + 0.5 - data.closeFromZ) * t);
		border.setSize(data.closeFromSize + (end - data.closeFromSize) * t);
		if (t >= 1) {
			data.closeMs = 0;
			SeasonData.markDirty();
		}
	}

	/** A new season: a new spot. */
	public static void forget() {
		SeasonData data = SeasonData.get();
		data.finalSet = false;
		data.closeMs = 0;
		SeasonData.markDirty();
	}
}
