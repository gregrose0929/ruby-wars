package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.mixin.DisplayAccessor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Little bits of magic: fireflies drifting over the grass at night, a rainbow over the biggest waterfall on sunny days,
 * and Rubi, the park's ruby dragon mascot, wandering round the plaza, waving, and giving hugs (right-click her).
 */
final class Wonder {
	private Wonder() {
	}

	private static UUID rainbow;
	private static Vec3 rainbowAt;
	private static UUID rubi;
	private static UUID rubiHug;
	private static double rubiAngle;
	private static int waveUntil;
	private static boolean wavingModel;
	private static final Map<UUID, Long> GREETED = new HashMap<>();
	private static int tick;

	static void reset() {
		rainbow = null;
		rubi = null;
		rubiHug = null;
		GREETED.clear();
	}

	static void build(ServerLevel level, Vec3 fall) {
		// The rainbow: always turned to face you (so it never looks like a thin line).
		if (fall != null) {
			rainbowAt = fall.add(0, -4, 0);
			Display.ItemDisplay d = HubPark.model(level, rainbowAt, 0, "rainbow", 18.0F, 0);
			if (d != null) {
				((DisplayAccessor) d).rubymod$setBillboard(Display.BillboardConstraints.VERTICAL);
				((DisplayAccessor) d).rubymod$setBrightness(new net.minecraft.util.Brightness(15, 15));
				((DisplayAccessor) d).rubymod$setViewRange(6.0F);
				rainbow = d.getUUID();
			}
		}
		// Rubi.
		rubiAngle = 0;
		Vec3 at = rubiSpot(rubiAngle);
		Display.ItemDisplay m = HubPark.model(level, at, 0, "mascot", 2.4F, 1.2F);
		if (m != null) {
			((DisplayAccessor) m).rubymod$setPosRotInterpolationDuration(6);
			rubi = m.getUUID();
		}
		Interaction hug = EntityTypes.INTERACTION.create(level, EntitySpawnReason.EVENT);
		if (hug != null) {
			((dev.rubymod.mixin.InteractionAccessor) hug).rubymod$setWidth(1.3F);
			((dev.rubymod.mixin.InteractionAccessor) hug).rubymod$setHeight(2.6F);
			hug.snapTo(at.x, at.y, at.z, 0, 0);
			level.addFreshEntity(hug);
			HubPark.keep(hug);
			rubiHug = hug.getUUID();
		}
	}

	/** Where Rubi is on her stroll round the plaza (a loop round the fountain, out past the benches). */
	private static Vec3 rubiSpot(double a) {
		double r = 11 + Math.sin(a * 3) * 1.5;
		return new Vec3(0.5 + Math.cos(a) * r, FLOOR + 1, 0.5 + Math.sin(a) * r);
	}

	/** Right-clicked something: Rubi? A hug. True if it was her. */
	static boolean clicked(ServerPlayer p, Entity e) {
		if (rubiHug == null || !rubiHug.equals(e.getUUID())) {
			return false;
		}
		ServerLevel level = (ServerLevel) p.level();
		level.sendParticles(ParticleTypes.HEART, e.getX(), e.getY() + 2.2, e.getZ(), 8, 0.6, 0.4, 0.6, 0);
		level.playSound(null, e.blockPosition(), SoundEvents.ALLAY_AMBIENT_WITH_ITEM, SoundSource.NEUTRAL, 1.0F, 1.2F);
		String[] says = {"Rubi gives you a big squishy hug!", "Rubi waves her little wings at you!", "Rubi does a happy dance!",
			"Rubi says: \"Have you tried the Park Train?\"", "Rubi says: \"I love ice cream! Strawberry's my favourite.\"",
			"Rubi says: \"Have you found any golden eggs?\""};
		p.sendOverlayMessage(Component.literal(says[level.getRandom().nextInt(says.length)]).withStyle(ChatFormatting.LIGHT_PURPLE));
		waveUntil = tick + 60;
		return true;
	}

	static void tick(ServerLevel level) {
		tick++;
		long time = level.getOverworldClockTime() % 24000;
		boolean night = time > 13000 && time < 23000;
		var random = level.getRandom();
		// Fireflies round everyone, over the grass, at night.
		if (night && tick % 10 == 0) {
			for (ServerPlayer p : level.players()) {
				for (int i = 0; i < 5; i++) {
					double x = p.getX() + random.nextGaussian() * 9;
					double z = p.getZ() + random.nextGaussian() * 9;
					BlockPos g = BlockPos.containing(x, FLOOR, z);
					if (level.getBlockState(g).is(Blocks.GRASS_BLOCK) && level.getBlockState(g.above()).canBeReplaced()) {
						level.sendParticles(ParticleTypes.FIREFLY, x, FLOOR + 1.3 + random.nextDouble() * 1.8, z, 1, 0.2, 0.2, 0.2, 0);
					}
				}
			}
		}
		// The rainbow's out in the day, gone at night.
		if (rainbow != null && tick % 100 == 0) {
			Entity e = level.getEntity(rainbow);
			if (e instanceof Display d) {
				((DisplayAccessor) d).rubymod$setViewRange(night ? 0.0F : 6.0F);
			}
		}
		rubi(level);
	}

	private static void rubi(ServerLevel level) {
		Entity m = rubi == null ? null : level.getEntity(rubi);
		if (m == null || tick % 3 != 0) {
			return;
		}
		// Someone close: she stops, turns to them and waves. Otherwise she strolls on.
		ServerPlayer near = null;
		for (ServerPlayer p : level.players()) {
			if (!p.isSpectator() && p.position().distanceTo(m.position()) < 4.5 && (near == null || p.distanceTo(m) < near.distanceTo(m))) {
				near = p;
			}
		}
		Vec3 at = m.position();
		if (near == null) {
			rubiAngle += 0.004;
			Vec3 next = rubiSpot(rubiAngle);
			Vec3 dir = next.subtract(at);
			m.teleportTo(next.x, next.y + Math.abs(Math.sin(tick * 0.25)) * 0.08, next.z);
			m.setYRot((float) Math.toDegrees(Math.atan2(-dir.x, dir.z)));
		} else {
			Vec3 to = near.position().subtract(at);
			m.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
			Long last = GREETED.get(near.getUUID());
			if (last == null || level.getGameTime() - last > 1200) {
				GREETED.put(near.getUUID(), level.getGameTime());
				near.sendOverlayMessage(Component.literal("Rubi the Ruby Dragon waves hello! (Right-click her for a hug.)").withStyle(ChatFormatting.LIGHT_PURPLE));
				waveUntil = tick + 60;
			}
		}
		// Waving: her arm goes up and down.
		boolean wave = tick < waveUntil && (tick / 6) % 2 == 0;
		if (wave != wavingModel && m instanceof Display.ItemDisplay d) {
			wavingModel = wave;
			net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK);
			stack.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, dev.rubymod.RubyMod.id(wave ? "park_mascot_wave" : "park_mascot"));
			((dev.rubymod.mixin.ItemDisplayAccessor) d).rubymod$setItemStack(stack);
		}
		Entity hug = rubiHug == null ? null : level.getEntity(rubiHug);
		if (hug != null) {
			hug.teleportTo(m.getX(), m.getY(), m.getZ());
		}
	}
}
