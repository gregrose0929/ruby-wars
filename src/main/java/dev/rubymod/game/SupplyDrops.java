package dev.rubymod.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Battle Day supply drops, worth chasing: told to everyone 30 seconds before they land, with a beacon's beam over the
 * spot from that moment and a chest on the map. The chest lands with a bang, and stays (marked) until it's emptied
 * or ten minutes go by; then the ground goes back as it was. Never inside anyone's base. Written down as they're
 * built, so a crash can't leave one behind.
 */
public final class SupplyDrops {
	private static final long WARNING = 30_000L;
	private static final long LASTS = 10 * 60_000L;
	/** Once emptied it goes this soon after. */
	private static final long EMPTIED = 30_000L;
	/** Its marker on the map: a supply drop (drawn as a chest). */
	private static final int MARKER = 3;

	private static final class Drop {
		final BlockPos at;
		long landsAt;
		long until;
		boolean landed;
		long emptiedAt = -1;
		final Map<BlockPos, BlockState> restore = new LinkedHashMap<>();
		final Map<BlockPos, BlockState> placed = new HashMap<>();

		Drop(BlockPos at, long landsAt) {
			this.at = at;
			this.landsAt = landsAt;
			this.until = landsAt + LASTS;
		}
	}

	private static final List<Drop> DROPS = new ArrayList<>();
	private static long skew;

	private SupplyDrops() {
	}

	private static long now() {
		return System.currentTimeMillis() + skew;
	}

	/** Time for a drop: pick a spot and tell everyone it's coming. */
	public static BlockPos incoming(ServerLevel level) {
		BlockPos spot = pickSpot(level);
		if (spot != null) {
			incomingAt(level, spot, WARNING);
		}
		return spot;
	}

	/** A drop coming down at {@code spot} in {@code warning} ms (tests use their own). */
	public static void incomingAt(ServerLevel level, BlockPos spot, long warning) {
		Drop drop = new Drop(spot.immutable(), now() + warning);
		DROPS.add(drop);
		// The beam: a beacon in the ground on a little iron pyramid, under a pane of cyan glass (the chest lands there).
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				put(level, drop, spot.offset(dx, -2, dz), Blocks.IRON_BLOCK.defaultBlockState());
			}
		}
		put(level, drop, spot.below(), Blocks.BEACON.defaultBlockState());
		put(level, drop, spot, Blocks.STAINED_GLASS.pick(DyeColor.CYAN).defaultBlockState());
		for (int y = 1; y < 40; y++) {
			BlockState above = level.getBlockState(spot.above(y));
			if (!above.isAir() && (above.canOcclude() || above.is(net.minecraft.tags.BlockTags.LEAVES))) {
				put(level, drop, spot.above(y), Blocks.AIR.defaultBlockState());
			}
		}
		Waypoints.global("Supply drop", spot, MARKER, (int) ((warning + LASTS) / 1000));
		int seconds = (int) Math.round(warning / 1000.0);
		for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
			if (Hub.inRubyWarsWorld(p)) {
				Alerts.title(p, "SUPPLY DROP", "Landing in " + seconds + "s at " + spot.getX() + ", " + spot.getZ() + " (the beam, and your map)", ChatFormatting.AQUA);
				p.level().playSound(null, p.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 0.8F, 1.2F);
			}
		}
		Alerts.broadcast(level.getServer(), Component.literal("★ Supply drop in " + seconds + "s at " + spot.getX() + ", " + spot.getZ()
				+ ": follow the beam").withStyle(ChatFormatting.AQUA));
		save(level.getServer());
	}

	/** Out in the open: on dry land inside the border, and well clear of every base. */
	static BlockPos pickSpot(ServerLevel level) {
		RandomSource random = level.getRandom();
		WorldBorder border = level.getWorldBorder();
		double half = border.getSize() * 0.4;
		SeasonData data = SeasonData.get();
		for (int tries = 0; tries < 60; tries++) {
			int x = (int) (border.getCenterX() + (random.nextDouble() * 2 - 1) * half);
			int z = (int) (border.getCenterZ() + (random.nextDouble() * 2 - 1) * half);
			boolean free = true;
			for (SeasonData.Claim claim : data.claims.values()) {
				if (Math.abs(claim.x - x) < claim.radius() + 16 && Math.abs(claim.z - z) < claim.radius() + 16) {
					free = false;
					break;
				}
			}
			if (!free) {
				continue;
			}
			level.getChunk(x >> 4, z >> 4);
			BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
			if (top.getY() > level.getMinY() + 3 && !level.getBlockState(top.below()).isAir() && level.getFluidState(top.below()).isEmpty()
					&& level.getFluidState(top).isEmpty()) {
				return top;
			}
		}
		return null;
	}

	private static void put(ServerLevel level, Drop drop, BlockPos pos, BlockState state) {
		drop.restore.putIfAbsent(pos.immutable(), level.getBlockState(pos));
		level.setBlockAndUpdate(pos, state);
		if (state.isAir()) {
			drop.placed.remove(pos.immutable());
		} else {
			drop.placed.put(pos.immutable(), level.getBlockState(pos));
		}
	}

	/** Once a second (Battle Day): land them, keep their markers up to date, and take them away when they're done. */
	public static void tick(MinecraftServer server) {
		if (DROPS.isEmpty()) {
			return;
		}
		ServerLevel level = server.overworld();
		if (!Rules.phase().isBattle()) {
			clear(server);
			return;
		}
		if (SeasonData.get().pausedRemaining >= 0) {
			// Paused: they wait too (nothing lands or goes while everyone's away).
			for (Drop drop : DROPS) {
				drop.landsAt += 1000;
				drop.until += 1000;
				if (drop.emptiedAt >= 0) {
					drop.emptiedAt += 1000;
				}
			}
			return;
		}
		long t = now();
		boolean changed = false;
		for (var it = DROPS.iterator(); it.hasNext();) {
			Drop drop = it.next();
			if (!drop.landed && t >= drop.landsAt) {
				land(level, drop);
				changed = true;
			}
			if (drop.landed) {
				boolean empty = !(level.getBlockEntity(drop.at) instanceof ChestBlockEntity chest) || chest.isEmpty();
				if (empty && drop.emptiedAt < 0) {
					drop.emptiedAt = t;
				}
				if (empty && t - drop.emptiedAt >= EMPTIED || t >= drop.until) {
					remove(level, drop);
					it.remove();
					changed = true;
					continue;
				}
			}
			long left = drop.landed ? drop.until - t : drop.landsAt - t;
			String name = drop.landed ? "Supply drop" : "Supply drop " + clock(left);
			Waypoints.global(name, drop.at, MARKER, (int) (left / 1000) + 30);
			if (!drop.landed && (drop.landsAt - t) / 1000 % 2 == 0) {
				level.sendParticles(ParticleTypes.END_ROD, true, false, drop.at.getX() + 0.5, drop.at.getY() + 20, drop.at.getZ() + 0.5, 6, 0.3, 8, 0.3, 0);
			}
		}
		if (changed) {
			save(server);
		}
	}

	private static void land(ServerLevel level, Drop drop) {
		drop.landed = true;
		put(level, drop, drop.at, Blocks.CHEST.defaultBlockState());
		if (level.getBlockEntity(drop.at) instanceof ChestBlockEntity chest) {
			RandomSource random = level.getRandom();
			for (ItemStack stack : BattleDirector.supplyLoot(random)) {
				int slot = random.nextInt(chest.getContainerSize());
				for (int k = 0; k < chest.getContainerSize() && !chest.getItem(slot).isEmpty(); k++) {
					slot = (slot + 1) % chest.getContainerSize();
				}
				chest.setItem(slot, stack);
			}
		}
		double x = drop.at.getX() + 0.5;
		double y = drop.at.getY() + 0.5;
		double z = drop.at.getZ() + 0.5;
		level.sendParticles(ParticleTypes.EXPLOSION, x, y + 0.5, z, 2, 0.5, 0.2, 0.5, 0);
		level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, false, x, y + 1, z, 20, 0.4, 1.5, 0.4, 0.02);
		level.sendParticles(ParticleTypes.FIREWORK, true, false, x, y + 2, z, 40, 0.6, 0.6, 0.6, 0.15);
		level.playSound(null, drop.at, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 2.0F, 1.4F);
		level.playSound(null, drop.at, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST_FAR, SoundSource.BLOCKS, 6.0F, 0.8F);
		Alerts.broadcast(level.getServer(), Component.literal("\u2605 Supply drop down at " + drop.at.getX() + ", " + drop.at.getZ()).withStyle(ChatFormatting.AQUA));
	}

	/** Done with: what's left spills out, and the ground goes back. */
	private static void remove(ServerLevel level, Drop drop) {
		if (level.getBlockEntity(drop.at) instanceof ChestBlockEntity chest) {
			net.minecraft.world.Containers.dropContents(level, drop.at, chest);
			chest.clearContent();
		}
		List<Map.Entry<BlockPos, BlockState>> list = new ArrayList<>(drop.restore.entrySet());
		java.util.Collections.reverse(list);
		drop.placed.clear();
		for (var e : list) {
			level.setBlockAndUpdate(e.getKey(), e.getValue());
		}
		Waypoints.clearGlobal(drop.at);
	}

	/** Part of a drop's beacon (not its chest): nobody can break it, by hand or with explosives. */
	public static boolean protects(net.minecraft.world.level.Level level, BlockPos pos) {
		if (DROPS.isEmpty() || !(level instanceof ServerLevel server) || server != server.getServer().overworld()) {
			return false;
		}
		for (Drop drop : DROPS) {
			BlockState placed = drop.placed.get(pos);
			if (placed != null && !pos.equals(drop.at) && placed == level.getBlockState(pos)) {
				return true;
			}
		}
		return false;
	}

	/** Every drop away (Battle Day over, or the server stopping). */
	public static void clear(MinecraftServer server) {
		ServerLevel level = server.overworld();
		for (Drop drop : DROPS) {
			remove(level, drop);
		}
		DROPS.clear();
		forget(server);
	}

	private static String clock(long ms) {
		long s = Math.max(0, (ms + 999) / 1000);
		return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
	}

	// ----- Written down, for a crash -----

	private static java.nio.file.Path file(MinecraftServer server) {
		return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("rubywars").resolve("supply_drops.txt");
	}

	private static void save(MinecraftServer server) {
		if (DROPS.isEmpty()) {
			forget(server);
			return;
		}
		List<String> lines = new ArrayList<>();
		for (Drop drop : DROPS) {
			lines.add("chest " + drop.at.getX() + " " + drop.at.getY() + " " + drop.at.getZ());
			for (var e : drop.restore.entrySet()) {
				BlockPos p = e.getKey();
				lines.add("block " + p.getX() + " " + p.getY() + " " + p.getZ() + " "
						+ net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(e.getValue()));
			}
		}
		try {
			java.nio.file.Files.createDirectories(file(server).getParent());
			java.nio.file.Files.write(file(server), lines);
		} catch (java.io.IOException ex) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't write down the supply drops", ex);
		}
	}

	private static void forget(MinecraftServer server) {
		try {
			java.nio.file.Files.deleteIfExists(file(server));
		} catch (java.io.IOException ex) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't clear the supply drop file", ex);
		}
	}

	/** On start: drops the server went down with (a crash) are taken away, the ground put back. */
	public static void recover(MinecraftServer server) {
		DROPS.clear();
		java.nio.file.Path f = file(server);
		if (!java.nio.file.Files.exists(f)) {
			return;
		}
		ServerLevel level = server.overworld();
		try {
			List<String> lines = java.nio.file.Files.readAllLines(f);
			var lookup = level.holderLookup(net.minecraft.core.registries.Registries.BLOCK);
			for (String line : lines) {
				String[] w = line.split(" ", 5);
				if (w[0].equals("chest") && level.getBlockEntity(new BlockPos(Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3])))
						instanceof ChestBlockEntity chest) {
					chest.clearContent();
				}
			}
			java.util.Collections.reverse(lines);
			for (String line : lines) {
				String[] w = line.split(" ", 5);
				if (!w[0].equals("block")) {
					continue;
				}
				try {
					BlockState state = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(lookup, w[4], false).blockState();
					level.setBlockAndUpdate(new BlockPos(Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3])), state);
				} catch (Exception ex) {
					dev.rubymod.RubyMod.LOGGER.warn("Supply drop tidy-up: skipped '{}'", line);
				}
			}
		} catch (java.io.IOException ex) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't read the supply drop file", ex);
		}
		forget(server);
		dev.rubymod.RubyMod.LOGGER.info("Tidied away supply drops left from before the restart");
	}

	// ----- For tests -----

	public static int count() {
		return DROPS.size();
	}

	public static boolean landed(BlockPos at) {
		for (Drop drop : DROPS) {
			if (drop.at.equals(at)) {
				return drop.landed;
			}
		}
		return false;
	}

	public static void advance(long ms) {
		skew += ms;
	}

	public static void reset() {
		DROPS.clear();
		skew = 0;
	}
}
