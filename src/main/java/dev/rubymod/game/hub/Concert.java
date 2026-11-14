package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Stage, as a concert: a lighting rig with spotlights that sweep the dance floor while music plays, speaker stacks,
 * and DJ Remy behind the decks, whose card lists every song so you can pick one.
 */
final class Concert {
	private Concert() {
	}

	private record Light(UUID id, double yaw, double phase) {
	}

	private static final List<Light> LIGHTS = new ArrayList<>();
	private static int tick;

	static void build(ServerLevel level) {
		LIGHTS.clear();
		// Truss towers at the stage's corners, and a frame across the top.
		BlockState truss = Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState();
		int[][] corners = {{-53, -43}, {-39, -43}, {-53, -37}, {-39, -37}};
		for (int[] c : corners) {
			HubPark.box(level, c[0], FLOOR + 1, c[1], c[0], FLOOR + 9, c[1], Blocks.IRON_BARS.defaultBlockState());
		}
		double top = FLOOR + 9.6;
		HubPark.beam(level, new Vec3(-52.5, top, -42.5), new Vec3(-38.5, top, -42.5), 0.35F, 0.35F, truss);
		HubPark.beam(level, new Vec3(-52.5, top, -36.5), new Vec3(-38.5, top, -36.5), 0.35F, 0.35F, truss);
		HubPark.beam(level, new Vec3(-52.5, top, -42.5), new Vec3(-52.5, top, -36.5), 0.35F, 0.35F, truss);
		HubPark.beam(level, new Vec3(-38.5, top, -42.5), new Vec3(-38.5, top, -36.5), 0.35F, 0.35F, truss);
		// Speaker stacks either side.
		for (int x : new int[] {-56, -37}) {
			for (int dx = 0; dx <= 1; dx++) {
				for (int y = FLOOR + 1; y <= FLOOR + 4; y++) {
					HubPark.set(level, x + dx, y, -41, Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState());
					HubPark.set(level, x + dx, y, -40, y % 2 == 0 ? Blocks.COAL_BLOCK.defaultBlockState() : Blocks.POLISHED_BLACKSTONE.defaultBlockState());
				}
			}
			HubPark.set(level, x, FLOOR + 5, -41, Blocks.REDSTONE_LAMP.defaultBlockState());
		}
		// The DJ's decks across the front of the stage (the jukebox in the middle still skips to the next song).
		BlockState[] desk = {Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(), Blocks.NOTE_BLOCK.defaultBlockState(), Blocks.JUKEBOX.defaultBlockState(),
			Blocks.NOTE_BLOCK.defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState()};
		for (int i = 0; i < desk.length; i++) {
			HubPark.set(level, -48 + i, FLOOR + 2, -40, desk[i]);
		}
		// Spotlights hanging from the front of the rig.
		DyeColor[] colors = {DyeColor.MAGENTA, DyeColor.CYAN, DyeColor.YELLOW, DyeColor.LIME};
		double[] xs = {-51.5, -48.0, -44.0, -40.5};
		for (int i = 0; i < xs.length; i++) {
			Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (d == null) {
				continue;
			}
			d.snapTo(xs[i], FLOOR + 9.2, -36.6, 0, 0);
			((BlockDisplayAccessor) d).rubymod$setBlockState(Blocks.STAINED_GLASS.pick(colors[i]).defaultBlockState());
			((DisplayAccessor) d).rubymod$setViewRange(6.0F);
			((DisplayAccessor) d).rubymod$setBrightness(net.minecraft.util.Brightness.FULL_BRIGHT);
			level.addFreshEntity(d);
			HubPark.keep(d);
			LIGHTS.add(new Light(d.getUUID(), (i - 1.5) * 0.25, i * 1.3));
			HubPark.set(level, (int) Math.floor(xs[i]), FLOOR + 9, -37, Blocks.SEA_LANTERN.defaultBlockState());
		}
		aim(level, 0);
		HubHosts.add(level, new Vec3(-45.5, FLOOR + 2, -41.5), 0, VillagerProfession.CLERIC, VillagerType.SWAMP,
				new HubHosts.Host("dj", "DJ Remy", "The Stage", 0xC060FF, "minecraft:jukebox",
						"Pick a song and I'll play it for the whole park! The dance floor and the lights go wild while it's on.",
						"The Stage: right-click DJ Remy to pick a song!", p -> {
							List<String> buttons = new ArrayList<>();
							for (int i = 0; i < HubGames.SONG_NAMES.length; i++) {
								buttons.add(i + "|" + (HubGames.song() == i ? "♪ " : "") + HubGames.SONG_NAMES[i]);
							}
							buttons.add("stop|Stop the music");
							return buttons;
						}, (p, key) -> {
							int i;
							try {
								i = Integer.parseInt(key);
							} catch (NumberFormatException e) {
								i = -1;
							}
							HubGames.play(p, i);
						}));
		HubPark.label(level, -46, FLOOR + 11, -40, "THE STAGE", ChatFormatting.LIGHT_PURPLE, 2.4F);
	}

	/** Point the spotlights: swinging over the dance floor while there's music, resting on it when not. */
	private static void aim(ServerLevel level, int duration) {
		boolean on = HubGames.stageBusy();
		for (Light l : LIGHTS) {
			if (!(level.getEntity(l.id()) instanceof Display d)) {
				continue;
			}
			double t = tick * 0.05 + l.phase();
			double yaw = l.yaw() + (on ? Math.sin(t) * 0.45 : 0);
			double pitch = 0.8 + (on ? Math.cos(t * 1.3) * 0.2 : 0);
			Quaternionf q = new Quaternionf().rotateY((float) yaw).rotateX((float) pitch);
			float len = on ? 11F : 0.6F;
			((DisplayAccessor) d).rubymod$setTransformationInterpolationDelay(0);
			((DisplayAccessor) d).rubymod$setTransformationInterpolationDuration(duration);
			((DisplayAccessor) d).rubymod$setTransformation(new Transformation(q.transform(new Vector3f(-0.25F, -0.25F, 0)), q, new Vector3f(0.5F, 0.5F, len),
					new Quaternionf()));
		}
	}

	static void tick(ServerLevel level) {
		tick++;
		if (tick % 8 == 0) {
			aim(level, 8);
		}
	}
}
