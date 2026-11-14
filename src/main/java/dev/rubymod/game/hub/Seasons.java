package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.time.LocalDate;
import java.time.MonthDay;

import dev.rubymod.mixin.DisplayAccessor;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Decorations for the time of year, put up on their own: pumpkins, ghosts and bats for Halloween (October), a
 * Christmas tree, presents, a snowman and falling snow for the holidays (December to early January), and blossom and
 * pastel lights in spring. The rest of the year the plaza is as it always is.
 */
final class Seasons {
	private Seasons() {
	}

	enum Season {
		NONE, HALLOWEEN, WINTER, SPRING
	}

	/** For trying them out: -Drubywars.season=winter (or a test can set it). */
	static Season now() {
		String forced = System.getProperty("rubywars.season");
		if (forced != null) {
			try {
				return Season.valueOf(forced.toUpperCase());
			} catch (IllegalArgumentException e) {
				return Season.NONE;
			}
		}
		MonthDay d = MonthDay.from(LocalDate.now());
		if (!d.isBefore(MonthDay.of(10, 1)) && !d.isAfter(MonthDay.of(11, 2))) {
			return Season.HALLOWEEN;
		}
		if (!d.isBefore(MonthDay.of(12, 1)) || !d.isAfter(MonthDay.of(1, 6))) {
			return Season.WINTER;
		}
		if (!d.isBefore(MonthDay.of(3, 20)) && !d.isAfter(MonthDay.of(5, 31))) {
			return Season.SPRING;
		}
		return Season.NONE;
	}

	/** The plaza's string lights, in the season's colours. */
	static DyeColor[] lights() {
		return switch (now()) {
			case HALLOWEEN -> new DyeColor[] {DyeColor.ORANGE, DyeColor.PURPLE, DyeColor.LIME, DyeColor.ORANGE};
			case WINTER -> new DyeColor[] {DyeColor.RED, DyeColor.LIME, DyeColor.WHITE, DyeColor.YELLOW};
			case SPRING -> new DyeColor[] {DyeColor.PINK, DyeColor.LIGHT_BLUE, DyeColor.YELLOW, DyeColor.LIME, DyeColor.MAGENTA};
			default -> new DyeColor[] {DyeColor.RED, DyeColor.YELLOW, DyeColor.LIME, DyeColor.LIGHT_BLUE, DyeColor.MAGENTA, DyeColor.ORANGE};
		};
	}

	static void build(ServerLevel level) {
		switch (now()) {
			case HALLOWEEN -> halloween(level);
			case WINTER -> winter(level);
			case SPRING -> HubPark.label(level, 0.5, FLOOR + 12, -1.5, "✿ HAPPY SPRING! ✿", ChatFormatting.LIGHT_PURPLE, 2.0F);
			default -> {
			}
		}
	}

	private static Display.BlockDisplay deco(ServerLevel level, Vec3 at, BlockState state, float sx, float sy, float sz) {
		Display.BlockDisplay d = HubPark.piece(level, at, state, new Vector3f(sx, sy, sz), new Quaternionf());
		return d;
	}

	private static void glow(Display.BlockDisplay d) {
		if (d != null) {
			((DisplayAccessor) d).rubymod$setBrightness(new net.minecraft.util.Brightness(15, 15));
		}
	}

	private static void halloween(ServerLevel level) {
		// Jack o'lanterns round the plaza, ghosts over the fountain, bats under the trees.
		for (int i = 0; i < 14; i++) {
			double a = i * Math.PI * 2 / 14 + 0.2;
			double r = 19.5;
			glow(deco(level, new Vec3(Math.cos(a) * r + 0.5, FLOOR + 1.45, Math.sin(a) * r + 0.5), Blocks.JACK_O_LANTERN.defaultBlockState(), 0.9F, 0.9F, 0.9F));
		}
		for (int i = 0; i < 3; i++) {
			double a = i * Math.PI * 2 / 3;
			HubPark.model(level, new Vec3(Math.cos(a) * 5 + 0.5, FLOOR + 6.5, Math.sin(a) * 5 + 0.5), (float) Math.toDegrees(a), "ghost", 1.4F, 0);
		}
		for (int[] t : new int[][] {{15, -6}, {-15, -6}, {14, 8}, {-14, 8}, {7, 16}, {-7, 16}}) {
			HubPark.model(level, new Vec3(t[0] + 0.5, FLOOR + 3.4, t[1] + 1.5), 0, "bat", 0.8F, 0);
		}
		HubPark.label(level, 0.5, FLOOR + 12, -1.5, "☠ HAPPY HALLOWEEN! ☠", ChatFormatting.GOLD, 2.0F);
	}

	private static void winter(ServerLevel level) {
		// A Christmas tree: stacked boughs, a trunk, a star, baubles; presents underneath.
		Vec3 base = new Vec3(-9.5, FLOOR + 1, 0.5);
		deco(level, base.add(0, 0.6, 0), Blocks.SPRUCE_LOG.defaultBlockState(), 0.6F, 1.2F, 0.6F);
		BlockState bough = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
		for (int i = 0; i < 5; i++) {
			float w = 4.4F - i * 0.85F;
			deco(level, base.add(0, 1.6 + i * 1.05, 0), bough, w, 1.2F, w);
		}
		glow(deco(level, base.add(0, 7.3, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 0.6F, 0.6F, 0.6F));
		DyeColor[] baubles = {DyeColor.RED, DyeColor.YELLOW, DyeColor.LIGHT_BLUE, DyeColor.MAGENTA, DyeColor.WHITE};
		var random = new java.util.Random(12);
		for (int i = 0; i < 18; i++) {
			int layer = random.nextInt(5);
			float w = (4.4F - layer * 0.85F) / 2;
			double a = random.nextDouble() * Math.PI * 2;
			glow(deco(level, base.add(Math.cos(a) * w, 1.2 + layer * 1.05, Math.sin(a) * w), Blocks.STAINED_GLASS.pick(baubles[i % baubles.length]).defaultBlockState(),
					0.28F, 0.28F, 0.28F));
		}
		DyeColor[] wrap = {DyeColor.RED, DyeColor.GREEN, DyeColor.BLUE, DyeColor.PURPLE};
		for (int i = 0; i < 4; i++) {
			double a = i * Math.PI / 2 + 0.4;
			Vec3 at = base.add(Math.cos(a) * 2.6, 0.35, Math.sin(a) * 2.6);
			deco(level, at, Blocks.WOOL.pick(wrap[i]).defaultBlockState(), 0.7F, 0.7F, 0.7F);
			deco(level, at, Blocks.WOOL.pick(DyeColor.YELLOW).defaultBlockState(), 0.74F, 0.72F, 0.14F);
			deco(level, at, Blocks.WOOL.pick(DyeColor.YELLOW).defaultBlockState(), 0.14F, 0.72F, 0.74F);
		}
		// A snowman.
		Vec3 sm = new Vec3(9.5, FLOOR + 1, 0.5);
		deco(level, sm.add(0, 0.5, 0), Blocks.SNOW_BLOCK.defaultBlockState(), 1.0F, 1.0F, 1.0F);
		deco(level, sm.add(0, 1.35, 0), Blocks.SNOW_BLOCK.defaultBlockState(), 0.75F, 0.75F, 0.75F);
		deco(level, sm.add(0, 2.0, 0), Blocks.CARVED_PUMPKIN.defaultBlockState(), 0.6F, 0.6F, 0.6F);
		deco(level, sm.add(0, 2.4, 0), Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(), 0.65F, 0.08F, 0.65F);
		deco(level, sm.add(0, 2.62, 0), Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(), 0.4F, 0.4F, 0.4F);
		deco(level, sm.add(0, 1.72, 0), Blocks.WOOL.pick(DyeColor.RED).defaultBlockState(), 0.8F, 0.15F, 0.8F);
		HubPark.label(level, 0.5, FLOOR + 12, -1.5, "❄ HAPPY HOLIDAYS! ❄", ChatFormatting.AQUA, 2.0F);
	}

	static void tick(ServerLevel level, int tick) {
		if (tick % 4 != 0) {
			return;
		}
		var random = level.getRandom();
		switch (now()) {
			case WINTER -> level.sendParticles(ParticleTypes.SNOWFLAKE, (random.nextDouble() - 0.5) * 40, FLOOR + 12, (random.nextDouble() - 0.5) * 40, 12, 8,
					1, 8, 0.02);
			case SPRING -> level.sendParticles(ParticleTypes.CHERRY_LEAVES, (random.nextDouble() - 0.5) * 36, FLOOR + 9, (random.nextDouble() - 0.5) * 36, 3, 6,
					1, 6, 0.0);
			case HALLOWEEN -> {
				if (tick % 40 == 0) {
					level.sendParticles(ParticleTypes.SOUL, (random.nextDouble() - 0.5) * 30, FLOOR + 2, (random.nextDouble() - 0.5) * 30, 2, 0.3, 0.3, 0.3,
							0.01);
				}
			}
			default -> {
			}
		}
	}
}
