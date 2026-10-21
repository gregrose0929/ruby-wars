package dev.rubymod.client.map;

import dev.rubymod.industry.Blueprints;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModItems;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Holding a filled Blueprint shows a cyan ghost of the structure in front of the
 * block you're looking at: what a Constructor on that block, facing away from
 * you, would build. The outline is the merged shape, so you see the building,
 * not a pile of cubes; the Constructor's spot is outlined in gold.
 */
public final class BlueprintGhost {
	private static Blueprints.Data cachedData;
	private static Direction cachedFacing;
	private static VoxelShape cachedShape;

	private BlueprintGhost() {
	}

	public static void render(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		ItemStack held = mc.player.getMainHandItem();
		if (!held.is(ModItems.BLUEPRINT)) {
			held = mc.player.getOffhandItem();
		}
		Blueprints.Data data = held.is(ModItems.BLUEPRINT) ? held.get(ModComponents.BLUEPRINT) : null;
		if (data == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		BlockPos constructor = hit.getBlockPos().relative(hit.getDirection());
		Direction facing = mc.player.getDirection();
		if (data != cachedData || facing != cachedFacing) {
			cachedData = data;
			cachedFacing = facing;
			cachedShape = shape(data, facing);
		}
		if (cachedShape == null || cachedShape.isEmpty()) {
			return;
		}
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		var pose = context.poseStack();
		pose.pushPose();
		pose.translate(constructor.getX() - cam.x, constructor.getY() - cam.y, constructor.getZ() - cam.z);
		context.submitNodeCollector().submitShapeOutline(pose, cachedShape, RenderTypes.linesTranslucent(), 0xE040E0FF, 2.5F, false);
		context.submitNodeCollector().submitShapeOutline(pose, Shapes.block(), RenderTypes.linesTranslucent(), 0xE0FFC040, 2.5F, false);
		pose.popPose();
	}

	/** The structure's merged shape, relative to the Constructor. */
	private static VoxelShape shape(Blueprints.Data data, Direction facing) {
		VoxelShape shape = Shapes.empty();
		int count = 0;
		for (int i = 0; i < data.cells().size() && count < 1500; i++) {
			if (data.at(i) == null) {
				continue;
			}
			BlockPos p = Blueprints.target(BlockPos.ZERO, facing, data.local(i));
			shape = Shapes.joinUnoptimized(shape, Shapes.box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1), BooleanOp.OR);
			count++;
		}
		return shape.optimize();
	}
}
