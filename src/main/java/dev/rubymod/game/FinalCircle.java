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

	/** Sudden Death's border closes in this many announced steps: each one holds still for a while, then moves in. */
	public static final int STEPS = 3;
	/** The part of each step it holds still (the rest, it's moving). */
	private static final double HOLD = 0.4;
	/** The last step/stage announced (step * 2, +1 once it's moving), so each is said once. */
	private static int announced = -1;

	/** How far the border has closed (0 to 1) at time t (0 to 1) through the closing: in steps, holding then moving. */
	public static double stepped(double t) {
		if (t >= 1) {
			return 1;
		}
		int step = (int) (t * STEPS);
		double local = t * STEPS - step;
		double move = local < HOLD ? 0 : (local - HOLD) / (1 - HOLD);
		return (step + move) / STEPS;
	}

	/** Tell everyone: the border's holding (and when it moves), or moving now. Once a second. */
	private static void announce(MinecraftServer server, double t, long ms) {
		if (ms < 120_000L || t >= 1) {
			return; // a quick close (an admin's shortcut): no steps to tell
		}
		int step = (int) (t * STEPS);
		double local = t * STEPS - step;
		boolean moving = local >= HOLD;
		int stage = step * 2 + (moving ? 1 : 0);
		long stepMs = ms / STEPS;
		if (stage != announced) {
			announced = stage;
			String msg = moving ? "The border is closing in! (step " + (step + 1) + " of " + STEPS + ")"
					: "Step " + (step + 1) + " of " + STEPS + ": the border holds for " + BaseReport.length((long) (stepMs * HOLD)) + ", then closes in. Get inside!";
			Alerts.broadcast(server, net.minecraft.network.chat.Component.literal(msg).withStyle(net.minecraft.ChatFormatting.RED));
		}
		if (!moving) {
			long left = (long) ((HOLD - local) * stepMs / 1000);
			net.minecraft.network.chat.Component bar = net.minecraft.network.chat.Component.literal(
					"Border moves in " + left / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", left % 60) + "  (step " + (step + 1) + " of " + STEPS + ")")
					.withStyle(net.minecraft.ChatFormatting.RED);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				p.sendSystemMessage(bar, true);
			}
		}
	}

	private static void apply(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		double raw = Math.max(0, Math.min(1, (SeasonTicker.now() - data.closeStart) / (double) data.closeMs));
		announce(server, raw, data.closeMs);
		double t = data.closeMs >= 120_000L ? stepped(raw) : raw;
		WorldBorder border = server.overworld().getWorldBorder();
		double end = GameConfig.get().borderEndSize;
		border.setCenter(data.closeFromX + (data.finalX + 0.5 - data.closeFromX) * t, data.closeFromZ + (data.finalZ + 0.5 - data.closeFromZ) * t);
		border.setSize(data.closeFromSize + (end - data.closeFromSize) * t);
		if (t >= 1) {
			data.closeMs = 0;
			announced = -1;
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
