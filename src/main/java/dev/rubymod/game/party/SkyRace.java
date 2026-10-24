package dev.rubymod.game.party;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.RubyMod;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/**
 * Sky Race: jetpack through a loop of rings in the sky, twice around. Land on a glowing
 * pad (or the ground) to refuel. Fastest time wins; the best time ever is kept.
 */
public class SkyRace extends PartyGame {
	private static final int RINGS = 12;
	private static final int LAPS = 2;
	private static final double RADIUS = 4.0;
	private static final int[] HEIGHTS = {10, 15, 20, 14, 9, 16, 22, 17, 11, 18, 13, 8};
	private static final int START_TICKS = 4 * 20;
	private static final int ROUND_TICKS = 4 * 60 * 20;
	private final Map<UUID, Integer> next = new HashMap<>();
	private final Map<UUID, Vec3> lastPos = new HashMap<>();
	private final List<UUID> finished = new ArrayList<>();
	private int firstFinish = -1;
	private int bestTicks = -1;
	private String bestName = "";

	public SkyRace() {
		super("skyrace", "Sky Race", "Jetpack through the rings in the sky. Fastest time wins!", "rubymod:jetpack", 1, false, false, false);
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(-70000, GROUND + 1, 0);
	}

	private Vec3 ring(int i) {
		double a = Math.toRadians(15 + i * 360.0 / RINGS);
		BlockPos c = center();
		return new Vec3(c.getX() + 0.5 + Math.cos(a) * 50, GROUND + HEIGHTS[i], c.getZ() + 0.5 + Math.sin(a) * 34);
	}

	/** The way through a ring (toward the next one, around the loop). */
	private Vec3 through(int i) {
		double a = Math.toRadians(15 + i * 360.0 / RINGS);
		return new Vec3(-Math.sin(a) * 50, 0, Math.cos(a) * 34).normalize();
	}

	@Override
	protected void build(ServerLevel level) {
		flatArena(level, 66, 50, 40, Blocks.GRASS_BLOCK.defaultBlockState(), true);
		for (int i = 0; i < RINGS; i++) {
			Vec3 c = ring(i);
			Vec3 t = through(i);
			Vec3 side = new Vec3(-t.z, 0, t.x);
			BlockState a = Blocks.CONCRETE.pick(i % 2 == 0 ? DyeColor.ORANGE : DyeColor.WHITE).defaultBlockState();
			for (int deg = 0; deg < 360; deg += 5) {
				double r = Math.toRadians(deg);
				Vec3 p = c.add(side.scale(Math.cos(r) * RADIUS)).add(0, Math.sin(r) * RADIUS, 0);
				BlockPos pos = BlockPos.containing(p);
				if (level.getBlockState(pos).isAir()) {
					level.setBlock(pos, deg % 30 == 0 ? Blocks.SEA_LANTERN.defaultBlockState() : a, 3);
				}
			}
			// A refuel pad under every other ring.
			if (i % 2 == 1) {
				BlockPos pad = BlockPos.containing(c.x, c.y - 8, c.z);
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						level.setBlock(pad.offset(dx, 0, dz), Blocks.SEA_LANTERN.defaultBlockState(), 3);
					}
				}
			}
		}
		// The start platform.
		Vec3 s = startPos();
		BlockPos sp = BlockPos.containing(s.x, s.y - 1, s.z);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				level.setBlock(sp.offset(dx, 0, dz), (dx + dz) % 2 == 0 ? Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState()
						: Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(), 3);
			}
		}
	}

	private Vec3 startPos() {
		BlockPos c = center();
		return new Vec3(c.getX() + 50.5, GROUND + 6, c.getZ() + 0.5);
	}

	private void cage(boolean up) {
		Vec3 s = startPos();
		BlockPos sp = BlockPos.containing(s.x, s.y, s.z);
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				boolean edge = Math.abs(dx) == 4 || Math.abs(dz) == 4;
				for (int dy = 0; dy <= 4; dy++) {
					if (edge || dy == 4) {
						level().setBlock(sp.offset(dx, dy, dz), up ? Blocks.BARRIER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
		}
	}

	@Override
	protected void toLobby(ServerPlayer player) {
		Vec3 s = startPos();
		teleport(player, s.x + player.getRandom().nextInt(5) - 2, s.y, s.z + player.getRandom().nextInt(5) - 2, 0);
		player.setGameMode(net.minecraft.world.level.GameType.ADVENTURE);
	}

	private static String time(int ticks) {
		int tenths = ticks / 2;
		return tenths / 600 + ":" + String.format("%02d", (tenths / 10) % 60) + "." + tenths % 10;
	}

	@Override
	protected String roundStatus() {
		if (roundTicks < START_TICKS) {
			return "Get ready...";
		}
		return finished.size() + "/" + activeCount() + " finished  •  " + time(roundTicks - START_TICKS)
				+ (bestTicks > 0 ? "  •  Record " + time(bestTicks) + " (" + bestName + ")" : "");
	}

	@Override
	protected void startRound() {
		loadBest();
		next.clear();
		lastPos.clear();
		finished.clear();
		firstFinish = -1;
		cage(true);
		for (ServerPlayer p : online()) {
			spawn(p, players.get(p.getUUID()));
		}
	}

	@Override
	protected void spawn(ServerPlayer player, Part part) {
		Vec3 s = startPos();
		if (roundTicks < START_TICKS) {
			teleport(player, s.x + player.getRandom().nextInt(5) - 2, s.y, s.z + player.getRandom().nextInt(5) - 2, 0);
		} else {
			// Back at the last ring you passed.
			int n = next.getOrDefault(player.getUUID(), 0);
			Vec3 at = n == 0 ? s : ring((n - 1) % RINGS);
			teleport(player, at.x, at.y, at.z, 0);
		}
		ready(player);
		clearPlayer(player);
		player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.JETPACK));
		next.putIfAbsent(player.getUUID(), 0);
	}

	@Override
	public Boolean canHurt(Player attacker, Player victim) {
		return contains(attacker) || contains(victim) ? Boolean.FALSE : null;
	}

	@Override
	protected void onDowned(ServerPlayer victim, Part part, Entity killer) {
		part.respawnIn = 20;
	}

	@Override
	protected void tickRound() {
		if (roundTicks < START_TICKS) {
			if (roundTicks % 20 == 0) {
				titleAll(String.valueOf((START_TICKS - roundTicks) / 20), "Hold Space to fly. Through every ring, twice around!", ChatFormatting.YELLOW);
			}
			return;
		}
		if (roundTicks == START_TICKS) {
			cage(false);
			titleAll("GO!", "", ChatFormatting.GREEN);
		}
		ServerLevel level = level();
		int total = RINGS * LAPS;
		for (ServerPlayer p : online()) {
			Part part = players.get(p.getUUID());
			if (part.watching || finished.contains(p.getUUID())) {
				continue;
			}
			int n = next.getOrDefault(p.getUUID(), 0);
			Vec3 now = p.position().add(0, 0.9, 0);
			Vec3 prev = lastPos.put(p.getUUID(), now);
			int ri = n % RINGS;
			Vec3 c = ring(ri);
			Vec3 t = through(ri);
			if (prev != null) {
				double before = prev.subtract(c).dot(t);
				double after = now.subtract(c).dot(t);
				Vec3 offset = now.subtract(c);
				double lateral = offset.subtract(t.scale(offset.dot(t))).length();
				if (before < 0 && after >= 0 && lateral < RADIUS + 0.6) {
					n++;
					next.put(p.getUUID(), n);
					level.playSound(null, p.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.9F, 0.8F + (n % RINGS) * 0.06F);
					if (n >= total) {
						int ticks = roundTicks - START_TICKS;
						part.time = ticks;
						finished.add(p.getUUID());
						if (firstFinish < 0) {
							firstFinish = roundTicks;
						}
						tell(Component.literal(part.name + " finished " + ordinal(finished.size()) + " in " + time(ticks) + "!").withStyle(ChatFormatting.GOLD));
						dev.rubymod.game.Alerts.title(p, ordinal(finished.size()).toUpperCase(), time(ticks), ChatFormatting.GOLD);
						if (bestTicks < 0 || ticks < bestTicks) {
							bestTicks = ticks;
							bestName = part.name;
							saveBest();
							tell(Component.literal("NEW RECORD!").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
						}
						p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
						continue;
					}
				}
			}
			if (roundTicks % 4 == 0) {
				p.sendOverlayMessage(Component.literal("Lap " + Math.min(LAPS, n / RINGS + 1) + "/" + LAPS + "   Ring " + (n % RINGS + 1) + "/" + RINGS
						+ "   " + time(roundTicks - START_TICKS)).withStyle(ChatFormatting.AQUA));
				// Show this player where the next ring is.
				Vec3 r = ring(n % RINGS);
				level.sendParticles(p, new DustParticleOptions(0x40FF60, 2.5F), true, true, r.x, r.y, r.z, 16, RADIUS * 0.5, RADIUS * 0.5, RADIUS * 0.5, 0);
			}
		}
		boolean allDone = finished.size() >= activeCount();
		if (allDone || firstFinish > 0 && roundTicks - firstFinish > 60 * 20 || roundTicks >= ROUND_TICKS) {
			StringBuilder b = new StringBuilder();
			players.values().stream().filter(x -> x.time > 0).sorted(Comparator.comparingLong(x -> x.time))
					.forEach(x -> b.append(b.isEmpty() ? "" : ", ").append(x.name).append(" ").append(time((int) x.time)));
			Part winner = players.values().stream().filter(x -> x.time > 0).min(Comparator.comparingLong(x -> x.time)).orElse(null);
			endRound(winner == null ? "NOBODY FINISHED" : winner.name + " WINS!", b.isEmpty() ? "Try again!" : b.toString(), ChatFormatting.GOLD);
		}
	}

	private static String ordinal(int n) {
		return n + (n == 1 ? "st" : n == 2 ? "nd" : n == 3 ? "rd" : "th");
	}

	private Path bestFile() {
		return server.getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("skyrace_best.txt");
	}

	private void loadBest() {
		try {
			if (Files.exists(bestFile())) {
				List<String> lines = Files.readAllLines(bestFile());
				bestTicks = Integer.parseInt(lines.get(0).trim());
				bestName = lines.size() > 1 ? lines.get(1) : "";
			}
		} catch (IOException | RuntimeException e) {
			RubyMod.LOGGER.warn("Couldn't read the Sky Race record", e);
		}
	}

	private void saveBest() {
		try {
			Files.createDirectories(bestFile().getParent());
			Files.writeString(bestFile(), bestTicks + "\n" + bestName + "\n");
		} catch (IOException e) {
			RubyMod.LOGGER.warn("Couldn't save the Sky Race record", e);
		}
	}

	@Override
	public boolean allowsGuns(ServerPlayer player) {
		return false;
	}

	@Override
	public boolean allowsBreak(ServerPlayer player, BlockPos pos) {
		return false;
	}

	@Override
	public boolean onUseBlock(ServerPlayer player, BlockPos pos, ItemStack held, BlockPos placeAt) {
		return phase == Phase.PLAYING;
	}
}
