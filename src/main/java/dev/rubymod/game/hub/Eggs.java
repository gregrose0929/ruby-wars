package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The golden egg hunt: twenty little golden eggs hidden round the park (somewhere new each time the park opens). Walk
 * into one to find it: a treat every time, and a golden crown for finding them all. Henrietta on the plaza gives hints.
 * No timer, nothing to keep up: just something to look for.
 */
final class Eggs {
	private Eggs() {
	}

	private static final int COUNT = 20;

	private record Egg(int n, UUID display, Vec3 at) {
	}

	private static final List<Egg> EGGS = new ArrayList<>();
	/** Which eggs each player's found (by egg number), since the park opened. */
	private static final Map<UUID, Set<Integer>> FOUND = new HashMap<>();
	private static int tick;

	static void reset() {
		EGGS.clear();
		FOUND.clear();
	}

	static void build(ServerLevel level) {
		EGGS.clear();
		FOUND.clear();
		var random = new java.util.Random(level.getGameTime() ^ 0x5eed);
		List<Vec3> spots = new ArrayList<>();
		int tries = 0;
		while (spots.size() < COUNT && tries++ < 20000) {
			int x = random.nextInt(320) - 160;
			int z = random.nextInt(320) - 160;
			Vec3 at = spot(level, x, z);
			if (at == null) {
				continue;
			}
			boolean far = true;
			for (Vec3 s : spots) {
				far &= s.distanceTo(at) > 22;
			}
			if (far) {
				spots.add(at);
			}
		}
		for (int i = 0; i < spots.size(); i++) {
			Vec3 at = spots.get(i);
			Display.ItemDisplay d = HubPark.model(level, at.add(0, 0.35, 0), random.nextFloat() * 360, "golden_egg", 0.7F, 0);
			if (d != null) {
				((DisplayAccessor) d).rubymod$setBrightness(new Brightness(15, 15));
				((DisplayAccessor) d).rubymod$setViewRange(0.6F);
				((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(10);
				EGGS.add(new Egg(i, d.getUUID(), at));
			}
		}
		dev.rubymod.RubyMod.LOGGER.info("Eggs: {} golden eggs hidden", EGGS.size());
		HubHosts.add(level, new Vec3(12.5, FLOOR + 1, 11.5), 180, VillagerProfession.FARMER, VillagerType.SAVANNA,
				new HubHosts.Host("eggs", "Henrietta", "Golden Egg Hunt", 0xFFD040, "minecraft:egg",
						"Twenty golden eggs are hidden round the park: in gardens, by the rides, on the islands. Walk into one to find it, and "
								+ "there's a treat inside! Find all twenty for a golden crown. Stuck? Ask me for a hint.",
						"Egg hunt: right-click Henrietta for a hint!", p -> List.of("hint|Give me a hint!", "count|How many have I found?"), (p, key) -> {
							Set<Integer> mine = FOUND.getOrDefault(p.getUUID(), Set.of());
							if (key.equals("count")) {
								p.sendSystemMessage(Component.literal("You've found " + mine.size() + " of " + EGGS.size() + " golden eggs.").withStyle(ChatFormatting.GOLD));
								return;
							}
							Egg near = null;
							for (Egg e : EGGS) {
								if (!mine.contains(e.n()) && (near == null || e.at().distanceTo(p.position()) < near.at().distanceTo(p.position()))) {
									near = e;
								}
							}
							if (near == null) {
								p.sendSystemMessage(Component.literal("You've found them all! Clever you!").withStyle(ChatFormatting.GOLD));
								return;
							}
							p.sendSystemMessage(Component.literal("Hmm... the nearest one you haven't found is " + where(p.position(), near.at()) + ".")
									.withStyle(ChatFormatting.GOLD));
						}));
		HubPark.label(level, 12.5, FLOOR + 3.9, 11.5, "GOLDEN EGG HUNT\n20 eggs hidden round the park!", ChatFormatting.GOLD, 0.7F);
	}

	/** A hiding place near x, z: on grass or a path, open above, nothing built right there. */
	private static Vec3 spot(ServerLevel level, int x, int z) {
		BlockPos g = new BlockPos(x, FLOOR, z);
		BlockState ground = level.getBlockState(g);
		if (ground.isAir() || !ground.isSolidRender()) {
			return null;
		}
		for (int y = 1; y <= 2; y++) {
			BlockState s = level.getBlockState(g.above(y));
			if (!s.isAir() && !s.canBeReplaced()) {
				return null;
			}
		}
		// Out under the sky, not indoors.
		for (int y = 3; y <= 16; y++) {
			if (!level.getBlockState(g.above(y)).isAir()) {
				return null;
			}
		}
		if (!level.getEntities((Entity) null, new AABB(x - 1.5, FLOOR, z - 1.5, x + 2.5, FLOOR + 3, z + 2.5), e -> true).isEmpty()) {
			return null;
		}
		return new Vec3(x + 0.5, FLOOR + 1, z + 0.5);
	}

	/** Roughly which way, and how far: "close by, to the north-east". */
	private static String where(Vec3 from, Vec3 to) {
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		double d = Math.hypot(dx, dz);
		String ns = dz < -d * 0.38 ? "north" : dz > d * 0.38 ? "south" : "";
		String ew = dx > d * 0.38 ? "east" : dx < -d * 0.38 ? "west" : "";
		String dir = ns + (ns.isEmpty() || ew.isEmpty() ? "" : "-") + ew;
		String far = d < 25 ? "really close, to the " : d < 70 ? "not far away, to the " : "a long way off, to the ";
		return far + (dir.isEmpty() ? "right here" : dir);
	}

	static void tick(ServerLevel level) {
		tick++;
		if (tick % 5 != 0 || EGGS.isEmpty()) {
			return;
		}
		for (Egg e : EGGS) {
			Entity d = level.getEntity(e.display());
			if (d == null) {
				continue;
			}
			// They bob and turn, and sparkle now and then.
			if (tick % 20 == 0) {
				d.teleportTo(e.at().x, e.at().y + 0.35 + Math.sin(tick * 0.05 + e.n()) * 0.12, e.at().z);
				d.setYRot(d.getYRot() + 25);
				level.sendParticles(ParticleTypes.WAX_ON, e.at().x, e.at().y + 0.6, e.at().z, 1, 0.25, 0.25, 0.25, 0);
			}
			for (ServerPlayer p : level.players()) {
				if (p.isSpectator() || p.isPassenger() || p.position().distanceTo(e.at()) > 1.4) {
					continue;
				}
				Set<Integer> mine = FOUND.computeIfAbsent(p.getUUID(), k -> new HashSet<>());
				if (mine.add(e.n())) {
					found(level, p, mine.size());
				}
			}
		}
	}

	private static void found(ServerLevel level, ServerPlayer p, int count) {
		var random = level.getRandom();
		level.playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2F, 1.4F);
		level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.6F);
		level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1, p.getZ(), 30, 0.4, 0.6, 0.4, 0.3);
		if (count >= EGGS.size()) {
			dev.rubymod.game.Alerts.title(p, "EGG-CELLENT!", "You found every golden egg! Here's your golden crown.", ChatFormatting.GOLD);
			MainStreet.hold(p, MainStreet.hat(1));
			HubPark.burst(level, p.position().add(0, 4, 0));
			return;
		}
		// A treat inside: a chocolate egg, or a plushie.
		if (random.nextInt(3) == 0) {
			Prizes.plushie(level, p);
		} else {
			MainStreet.hold(p, MainStreet.snack(Items.COCOA_BEANS, null, "Chocolate Egg", 2));
		}
		p.sendSystemMessage(Component.literal("You found a golden egg! (" + count + " of " + EGGS.size() + ")").withStyle(ChatFormatting.GOLD));
		dev.rubymod.game.Alerts.title(p, "", "Golden egg! " + count + " of " + EGGS.size(), ChatFormatting.GOLD);
	}
}
