package dev.rubymod.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.math.Transformation;

import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.registry.ModItems;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Brightness;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Night Battle (an option: /rubywars nightbattle on): Battle Day is fought at night. Searchlights sweep the sky from
 * every base's Heart, illumination flares hang over the field now and then, and night-vision goggles are worth having.
 * (The goggles work any night: night vision, and a green screen.)
 */
public final class NightBattle {
	private NightBattle() {
	}

	/** Searchlight beams standing now, by base owner. */
	private static final Map<String, List<Display.BlockDisplay>> BEAMS = new HashMap<>();
	public static final String TAG = "rubymod_searchlight";
	private static int tick;
	/** For autotests: night battle whatever the day. */
	public static boolean forced;

	public static boolean active() {
		return forced || GameConfig.get().nightBattle && Rules.phase().isBattle();
	}

	public static void tick(MinecraftServer server) {
		tick++;
		ServerLevel world = server.overworld();
		// Goggles: night vision while they're on.
		if (tick % 40 == 0) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (p.getItemBySlot(EquipmentSlot.HEAD).is(ModItems.NIGHT_GOGGLES)) {
					p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 300, 0, true, false, true));
				}
			}
		}
		if (!active()) {
			if (!BEAMS.isEmpty()) {
				clear();
			}
			return;
		}
		// Keep it night.
		if (tick % 100 == 0) {
			long day = world.getOverworldClockTime() % 24000;
			if (day < 13500 || day > 22500) {
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set 14000");
			}
		}
		// Flares now and then over somebody (up to three at once round the field).
		if (tick % 300 == 0) {
			List<ServerPlayer> out = new ArrayList<>(world.players());
			java.util.Collections.shuffle(out);
			for (ServerPlayer p : out.subList(0, Math.min(3, out.size()))) {
				if (p.isSpectator()) {
					continue;
				}
				var random = world.getRandom();
				double x = p.getX() + random.nextGaussian() * 25;
				double z = p.getZ() + random.nextGaussian() * 25;
				int ground = world.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) x, (int) z);
				dev.rubymod.entity.LightEntity.flare(world, new Vec3(x, ground + 11, z));
			}
		}
		if (tick % 40 == 0) {
			stand(world);
		}
		if (tick % 2 == 0) {
			sweep();
		}
	}

	/** Two beams on every base's Heart (and none on bases that have gone). */
	private static void stand(ServerLevel world) {
		String dim = Rules.dim(world);
		Map<String, SeasonData.Claim> bases = new HashMap<>();
		for (SeasonData.Claim c : SeasonData.get().claims.values()) {
			if (!c.destroyed && dim.equals(c.dimension)) {
				bases.put(c.owner, c);
			}
		}
		for (var it = BEAMS.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			if (!bases.containsKey(e.getKey())) {
				e.getValue().forEach(Entity::discard);
				it.remove();
			}
		}
		for (SeasonData.Claim c : bases.values()) {
			if (BEAMS.containsKey(c.owner) || !world.isLoaded(c.pos())) {
				continue;
			}
			List<Display.BlockDisplay> beams = new ArrayList<>();
			// On the list before they're in the world (or they'd look like leftovers, and be cleared away).
			BEAMS.put(c.owner, beams);
			for (int i = 0; i < 2; i++) {
				Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(world, EntitySpawnReason.EVENT);
				if (d == null) {
					continue;
				}
				BlockPos top = c.pos().above(2);
				d.snapTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5, 0, 0);
				((BlockDisplayAccessor) d).rubymod$setBlockState(Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState());
				((DisplayAccessor) d).rubymod$setBrightness(new Brightness(15, 15));
				((DisplayAccessor) d).rubymod$setViewRange(8.0F);
				((DisplayAccessor) d).rubymod$setTransformationInterpolationDuration(2);
				d.addTag(TAG);
				beams.add(d);
				world.addFreshEntity(d);
			}
		}
	}

	/** The beams turn slowly round, each its own way, leaning out over the field. */
	private static void sweep() {
		float t = tick / 20.0F;
		for (var e : BEAMS.entrySet()) {
			List<Display.BlockDisplay> beams = e.getValue();
			float seed = (e.getKey().hashCode() & 0xFFFF) / 1000.0F;
			for (int i = 0; i < beams.size(); i++) {
				float yaw = seed + t * (i == 0 ? 0.35F : -0.27F) + i * 2.2F;
				float lean = 0.45F + 0.15F * (float) Math.sin(t * 0.5 + i);
				Quaternionf q = new Quaternionf().rotateY(yaw).rotateX(lean);
				float w = 0.45F;
				float len = 70;
				Vector3f corner = q.transform(new Vector3f(-w / 2, 0, -w / 2));
				((DisplayAccessor) beams.get(i)).rubymod$setTransformation(new Transformation(corner, q, new Vector3f(w, len, w), new Quaternionf()));
				((DisplayAccessor) beams.get(i)).rubymod$setTransformationInterpolationDelay(0);
			}
		}
	}

	/** Night Battle's over (or the server's stopping): the beams come down. */
	public static void clear() {
		for (List<Display.BlockDisplay> beams : BEAMS.values()) {
			beams.forEach(Entity::discard);
		}
		BEAMS.clear();
	}

	/** A searchlight left over from before a restart. */
	public static boolean stale(Entity e) {
		if (!e.entityTags().contains(TAG)) {
			return false;
		}
		for (List<Display.BlockDisplay> beams : BEAMS.values()) {
			if (beams.contains(e)) {
				return false;
			}
		}
		return true;
	}
}
