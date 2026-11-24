package dev.rubymod.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.block.DefenseBlock;
import dev.rubymod.block.DefenseBlockEntity;
import dev.rubymod.industry.MachineBlockEntity;
import dev.rubymod.industry.MachineKind;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * "What does each defense cover?": hold a defense (a turret, Tesla Coil, air defense, sensor or Shield Generator) in
 * your own base, and every defense there shows its reach as a coloured outline only you can see. Red on the ground:
 * what turrets and Tesla Coils hit. Blue up in the air: what shoots down aircraft and missiles. Yellow: what sensors
 * notice. Pink: the Shield's dome. Your own base only, never anyone else's.
 */
public final class DefenseCoverage {
	private DefenseCoverage() {
	}

	public static final int GROUND = 0xFF4040;
	public static final int AIR = 0x40A0FF;
	public static final int SENSOR = 0xFFD040;
	public static final int SHIELD = 0xFF4D88;
	/** Rings bigger than this aren't drawn (they cover the whole base anyway): said in words instead. */
	private static final double MAX_RING = 64;

	/** One defense's reach: where, how far, and which kind of outline. */
	public record Reach(Vec3 at, double range, int color, String name) {
	}

	private static final Set<UUID> TOLD = new HashSet<>();
	private static final Map<UUID, List<Reach>> CACHE = new HashMap<>();
	private static final Map<UUID, Long> CACHED_AT = new HashMap<>();

	/** Is this a defense you'd want to see the reach of? */
	public static boolean isDefense(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem bi)) {
			return false;
		}
		if (bi.getBlock() instanceof DefenseBlock db) {
			return DefenseBlockEntity.reach(db.kind()) > 0;
		}
		if (bi.getBlock() instanceof dev.rubymod.industry.MachineBlock mb) {
			return mb.kind() == MachineKind.SHIELD_GENERATOR || mb.kind() == MachineKind.POINT_DEFENSE || mb.kind() == MachineKind.TESLA_COIL;
		}
		return false;
	}

	/** Every defense in this base, and its reach. */
	public static List<Reach> scan(ServerLevel level, SeasonData.Claim claim) {
		List<Reach> out = new ArrayList<>();
		BlockPos heart = claim.pos();
		int r = claim.radius();
		for (int cx = (heart.getX() - r) >> 4; cx <= (heart.getX() + r) >> 4; cx++) {
			for (int cz = (heart.getZ() - r) >> 4; cz <= (heart.getZ() + r) >> 4; cz++) {
				if (!level.hasChunk(cx, cz)) {
					continue;
				}
				for (BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values()) {
					BlockPos pos = be.getBlockPos();
					if (SeasonData.get().claimAt(Rules.dim(level), pos.getX(), pos.getZ()) != claim) {
						continue;
					}
					Vec3 at = Vec3.atCenterOf(pos);
					if (be instanceof DefenseBlockEntity && be.getBlockState().getBlock() instanceof DefenseBlock db) {
						double range = DefenseBlockEntity.reach(db.kind());
						if (range > 0) {
							int color = switch (db.kind()) {
								case INTERCEPTOR, FLAK, SAM -> AIR;
								case RADAR, SEISMIC, ALARM -> SENSOR;
								default -> GROUND;
							};
							out.add(new Reach(at, range, color, be.getBlockState().getBlock().getName().getString()));
						}
					} else if (be instanceof MachineBlockEntity m) {
						MachineKind k = m.kind();
						int color = k == MachineKind.SHIELD_GENERATOR ? SHIELD : k == MachineKind.POINT_DEFENSE ? AIR : k == MachineKind.TESLA_COIL ? GROUND : 0;
						if (color != 0) {
							out.add(new Reach(at, k.radius(m.tier()), color, be.getBlockState().getBlock().getName().getString()));
						}
					}
				}
			}
		}
		return out;
	}

	/** Twice a second: draw the outlines for anyone holding a defense in their own base (the words once a second). */
	public static void tick(MinecraftServer server, boolean second) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!isDefense(p.getMainHandItem()) || p.isSpectator() || !(p.level() instanceof ServerLevel level)) {
				continue;
			}
			SeasonData.Claim claim = Rules.baseAt(level, p.blockPosition());
			if (claim == null || claim.destroyed || !claim.canUse(p.getUUID())) {
				continue;
			}
			long now = server.getTickCount();
			List<Reach> reach = CACHE.get(p.getUUID());
			if (reach == null || now - CACHED_AT.getOrDefault(p.getUUID(), 0L) > 100) {
				reach = scan(level, claim);
				CACHE.put(p.getUUID(), reach);
				CACHED_AT.put(p.getUUID(), now);
			}
			List<String> big = new ArrayList<>();
			for (Reach r : reach) {
				if (r.range() > MAX_RING) {
					if (!big.contains(r.name())) {
						big.add(r.name());
					}
					continue;
				}
				draw(level, p, r);
			}
			String legend = reach.isEmpty() ? "No defenses in this base yet: place one and its reach shows here."
					: "Red: ground  |  Blue: air  |  Yellow: sensors  |  Pink: Shield" + (big.isEmpty() ? "" : "  |  " + String.join(", ", big) + ": your whole base");
			if (second) {
				p.sendSystemMessage(Component.literal(legend).withStyle(ChatFormatting.GRAY), true);
			}
			if (TOLD.add(p.getUUID())) {
				p.sendSystemMessage(Component.literal("Holding a defense shows what your defenses cover (only you see it). Gaps are where attackers get in!")
						.withStyle(ChatFormatting.AQUA));
			}
			if (!reach.isEmpty()) {
				Goals.add(server, p.getUUID(), "coverage", 1);
			}
		}
	}

	private static void draw(ServerLevel level, ServerPlayer p, Reach r) {
		DustParticleOptions dust = new DustParticleOptions(r.color(), 2.0F);
		int points = (int) Math.max(24, Math.min(140, r.range() * 2.4));
		if (r.color() == SHIELD) {
			// The dome: three rings up its side.
			for (double lat : new double[] {0.0, 0.5, 0.85}) {
				double ring = r.range() * Math.cos(lat);
				double y = r.at().y + r.range() * Math.sin(lat);
				circle(level, p, dust, r.at().x, y, r.at().z, ring, (int) Math.max(12, points * Math.cos(lat)), false);
			}
			return;
		}
		boolean air = r.color() == AIR;
		circle(level, p, dust, r.at().x, air ? r.at().y + 5 : r.at().y, r.at().z, r.range(), points, !air);
	}

	/** A ring of dust: on the ground (following it up and down), or flat in the air. */
	private static void circle(ServerLevel level, ServerPlayer p, DustParticleOptions dust, double x, double y, double z, double radius, int points,
			boolean ground) {
		for (int i = 0; i < points; i++) {
			double a = i * Math.PI * 2 / points;
			double px = x + Math.cos(a) * radius;
			double pz = z + Math.sin(a) * radius;
			double py = y;
			if (ground) {
				py = surface(level, px, y, pz);
			}
			level.sendParticles(p, dust, true, false, px, py, pz, 1, 0, 0, 0, 0);
		}
	}

	/** The top of the ground near this height (so the ring hugs hills and dips). */
	private static double surface(ServerLevel level, double x, double y, double z) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int bx = net.minecraft.util.Mth.floor(x);
		int bz = net.minecraft.util.Mth.floor(z);
		for (int dy = 4; dy >= -8; dy--) {
			m.set(bx, (int) y + dy, bz);
			if (level.getBlockState(m).isAir() && !level.getBlockState(m.below()).isAir()) {
				return m.getY() + 0.2;
			}
		}
		return y;
	}

	public static void forget(UUID player) {
		CACHE.remove(player);
		CACHED_AT.remove(player);
	}
}
