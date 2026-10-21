package dev.rubymod.client;

import java.util.List;

import dev.rubymod.item.ArchitectWandItem;
import dev.rubymod.item.BlueprintItem;
import dev.rubymod.item.BuildShapes;
import dev.rubymod.registry.ModComponents;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Live previews for the building tools, drawn with small glowing particles:
 * the Architect's Wand shows exactly which blocks it will fill, and a Blueprint
 * with one corner set shows the box it will copy.
 */
public final class BuildPreview {
	private static final DustParticleOptions WAND = new DustParticleOptions(0x40C8FF, 0.7F);
	private static final DustParticleOptions COPY = new DustParticleOptions(0xFFD040, 0.7F);

	private BuildPreview() {
	}

	public static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null || mc.player.tickCount % 3 != 0) {
			return;
		}
		ItemStack held = mc.player.getMainHandItem();
		boolean wand = held.getItem() instanceof ArchitectWandItem;
		boolean blueprint = held.getItem() instanceof BlueprintItem && !held.has(ModComponents.BLUEPRINT);
		if (!wand && !blueprint || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		BlockPos target = hit.getBlockPos();
		if (wand && !mc.level.getBlockState(target).canBeReplaced()) {
			target = target.relative(hit.getDirection());
		}
		BlockPos corner = held.get(ModComponents.CORNER);
		if (corner == null) {
			dot(mc, target, wand ? WAND : COPY);
			return;
		}
		if (blueprint) {
			box(mc, corner, target, COPY);
			return;
		}
		BuildShapes.Mode mode = ArchitectWandItem.mode(held);
		if (mode == BuildShapes.Mode.BOX || mode == BuildShapes.Mode.ROOM) {
			box(mc, corner, target, WAND);
			return;
		}
		List<BlockPos> blocks = BuildShapes.shape(mode, corner, target);
		int step = Math.max(1, blocks.size() / 200);
		for (int i = 0; i < blocks.size(); i += step) {
			dot(mc, blocks.get(i), WAND);
		}
	}

	private static void dot(Minecraft mc, BlockPos p, DustParticleOptions dust) {
		mc.level.addParticle(dust, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 0, 0, 0);
	}

	/** The edges of the box spanning two blocks. */
	private static void box(Minecraft mc, BlockPos a, BlockPos b, DustParticleOptions dust) {
		double x0 = Math.min(a.getX(), b.getX());
		double y0 = Math.min(a.getY(), b.getY());
		double z0 = Math.min(a.getZ(), b.getZ());
		double x1 = Math.max(a.getX(), b.getX()) + 1;
		double y1 = Math.max(a.getY(), b.getY()) + 1;
		double z1 = Math.max(a.getZ(), b.getZ()) + 1;
		double[][] edges = {
				{x0, y0, z0, x1, y0, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z0, x1, y1, z0}, {x0, y1, z1, x1, y1, z1},
				{x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1},
				{x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1}};
		for (double[] e : edges) {
			double len = Math.max(Math.abs(e[3] - e[0]), Math.max(Math.abs(e[4] - e[1]), Math.abs(e[5] - e[2])));
			double spacing = Math.max(0.5, len / 40);
			for (double t = 0; t <= len; t += spacing) {
				double f = len == 0 ? 0 : t / len;
				mc.level.addParticle(dust, e[0] + (e[3] - e[0]) * f, e[1] + (e[4] - e[1]) * f, e[2] + (e[5] - e[2]) * f, 0, 0, 0);
			}
		}
	}
}
