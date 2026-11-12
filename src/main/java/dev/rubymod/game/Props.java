package dev.rubymod.game;

import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.mixin.ItemDisplayAccessor;
import dev.rubymod.mixin.TextDisplayAccessor;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Things to look at that aren't blocks: a floating item, a floating line of text, a block of any size. Every game
 * that puts a pick-up, a marker or a trophy in the world makes it here, tagged so its game can clear it away.
 */
public final class Props {
	private Props() {
	}

	/** A floating item that always faces you. */
	public static Display.ItemDisplay item(ServerLevel level, Vec3 at, ItemStack stack, float scale, String tag) {
		Display.ItemDisplay d = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d == null) {
			return null;
		}
		d.snapTo(at.x, at.y, at.z, 0, 0);
		((ItemDisplayAccessor) d).rubymod$setItemStack(stack);
		((DisplayAccessor) d).rubymod$setBillboard(Display.BillboardConstraints.VERTICAL);
		((DisplayAccessor) d).rubymod$setViewRange(4.0F);
		((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
		d.addTag(tag);
		level.addFreshEntity(d);
		return d;
	}

	/** A floating line of text that always faces you. */
	public static Display.TextDisplay text(ServerLevel level, Vec3 at, Component text, float scale, String tag) {
		Display.TextDisplay d = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d == null) {
			return null;
		}
		d.snapTo(at.x, at.y, at.z, 0, 0);
		((TextDisplayAccessor) d).rubymod$setText(text);
		((TextDisplayAccessor) d).rubymod$setBackground(0x70000000);
		((DisplayAccessor) d).rubymod$setBillboard(Display.BillboardConstraints.CENTER);
		((DisplayAccessor) d).rubymod$setViewRange(4.0F);
		((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
		d.addTag(tag);
		level.addFreshEntity(d);
		return d;
	}

	/** A block drawn at any size, centred on {@code at} (it's only a picture: nothing bumps into it). */
	public static Display.BlockDisplay block(ServerLevel level, Vec3 at, BlockState state, float sx, float sy, float sz, String tag) {
		Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d == null) {
			return null;
		}
		d.snapTo(at.x, at.y, at.z, 0, 0);
		((BlockDisplayAccessor) d).rubymod$setBlockState(state);
		((DisplayAccessor) d).rubymod$setViewRange(8.0F);
		((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(-sx / 2, -sy / 2, -sz / 2), new Quaternionf(), new Vector3f(sx, sy, sz),
				new Quaternionf()));
		d.addTag(tag);
		level.addFreshEntity(d);
		return d;
	}
}
