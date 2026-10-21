package dev.rubymod.game;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.rubymod.block.DefenseBlockEntity;
import dev.rubymod.block.GraveBlockEntity;
import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.HeavyAAGunEntity;
import dev.rubymod.entity.VehicleEntity;
import dev.rubymod.industry.Grid;
import dev.rubymod.industry.MachineBlockEntity;
import dev.rubymod.industry.MachineKind;
import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;
import dev.rubymod.registry.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Answers "what am I looking at?" for the look-at panel: owner, tier, status,
 * stored charge, ammo, armor, and the claim it's in. Only things worth knowing
 * about (our machines, defenses, Hearts, graves, vehicles) get an answer; the
 * client shows nothing otherwise.
 */
public final class LookInfo {
	private static final int GREEN = 0x55FF77;
	private static final int RED = 0xFF5555;
	private static final int AMBER = 0xFFB040;
	private static final int BLUE = 0x55AAFF;

	private LookInfo() {
	}

	public static void answer(ServerPlayer player, MorePayloads.LookQuery query) {
		List<String> lines = new ArrayList<>();
		float bar = -1;
		int color = BLUE;
		if (query.entityId() >= 0) {
			Entity e = player.level().getEntity(query.entityId());
			if (e == null || e.distanceTo(player) > 64) {
				return;
			}
			if (e instanceof VehicleEntity || e instanceof AircraftEntity || e instanceof HeavyAAGunEntity) {
				LivingEntity living = (LivingEntity) e;
				lines.add(e.getName().getString());
				bar = living.getHealth() / living.getMaxHealth();
				lines.add("Armor " + Math.round(bar * 100) + "%");
				Entity driver = e.getFirstPassenger();
				lines.add(driver != null ? "Crew: " + driver.getName().getString() : "Empty: right-click to get in");
				if (e instanceof VehicleEntity v && v.isTank()) {
					lines.add(v.reloadLeft() > 0 ? "Main gun reloading" : "Main gun ready");
				}
				color = bar > 0.5F ? GREEN : bar > 0.25F ? AMBER : RED;
			} else if (e instanceof dev.rubymod.entity.SoldierEntity s) {
				lines.add(s.getName().getString());
				bar = s.getHealth() / s.getMaxHealth();
				lines.add("Health " + Math.round(bar * 100) + "%");
				if (s.isRaider()) {
					lines.add("Raider: heading for a Heart");
					color = RED;
				} else {
					lines.add(s.following() ? "Following " + name(s.owner()) : "Holding a post");
					color = Squads.same(player.getUUID(), s.owner()) ? GREEN : RED;
				}
			} else if (e instanceof net.minecraft.world.entity.player.Player other) {
				Squads.Squad squad = Squads.of(other.getUUID());
				if (squad == null || !Squads.same(player.getUUID(), other.getUUID())) {
					return;
				}
				lines.add(other.getName().getString());
				bar = other.getHealth() / other.getMaxHealth();
				lines.add("Squadmate (" + squad.name + ")");
				color = GREEN;
			} else {
				return;
			}
		} else {
			BlockPos pos = query.pos();
			if (pos.distSqr(player.blockPosition()) > 64 * 64 || !player.level().isLoaded(pos)) {
				return;
			}
			BlockState state = player.level().getBlockState(pos);
			var be = player.level().getBlockEntity(pos);
			if (be instanceof MachineBlockEntity m) {
				MachineKind kind = m.kind();
				lines.add(kind.title + " " + MachineKind.TIER_NAMES[m.tier() - 1]);
				lines.add("Owner: " + name(m.owner()));
				lines.add(statusText(m.status()));
				if (kind == MachineKind.BATTERY || kind.output(m.tier()) > 0) {
					UUID owner = m.owner();
					long cap = Grid.capacity(owner);
					bar = cap > 0 ? (float) Grid.charge(owner) / cap : -1;
					lines.add("Grid: " + Grid.charge(owner) + " / " + cap + " charge");
				}
				color = m.status() == MachineBlockEntity.WORKING ? GREEN : m.status() == MachineBlockEntity.IDLE ? BLUE : AMBER;
			} else if (be instanceof DefenseBlockEntity d) {
				lines.add(state.getBlock().getName().getString());
				if (state.is(ModBlocks.SENTRY_TURRET) || state.is(ModBlocks.FLAK_CANNON)) {
					lines.add("Ammo: " + d.ammo + " / " + DefenseBlockEntity.MAX_TURRET_AMMO);
					bar = d.ammo / (float) DefenseBlockEntity.MAX_TURRET_AMMO;
					color = d.ammo > 0 ? GREEN : RED;
				} else if (state.is(ModBlocks.MISSILE_INTERCEPTOR) || state.is(ModBlocks.SAM_SITE)) {
					lines.add("Charges: " + d.charges + " / " + DefenseBlockEntity.MAX_CHARGES);
					bar = d.charges / (float) DefenseBlockEntity.MAX_CHARGES;
					color = d.charges > 0 ? GREEN : RED;
				}
				lines.add(SeasonData.get().phase.isBattle() ? "Armed" : "Switches on at Lockdown");
			} else if (be instanceof GraveBlockEntity g) {
				lines.add("War Grave: " + g.ownerName());
				int s = g.lockedSeconds();
				lines.add(s > 0 ? "Locked to " + g.ownerName() + " for " + s / 60 + ":" + String.format("%02d", s % 60) : "Unlocked: anyone can loot it");
				color = s > 0 ? AMBER : GREEN;
			} else if (state.is(ModBlocks.RUBY_HEART)) {
				SeasonData.Claim claim = claimAt(pos);
				if (claim == null) {
					return;
				}
				lines.add(claim.ownerName + "'s Ruby Heart");
				lines.add("Tier " + claim.tier + ", claim radius " + claim.radius());
				bar = claim.hp / (float) Math.max(1, claim.maxHp());
				lines.add("Integrity " + claim.hp + " / " + claim.maxHp());
				color = bar > 0.5F ? GREEN : bar > 0.25F ? AMBER : RED;
			} else {
				return;
			}
			SeasonData.Claim claim = SeasonData.get().claimAt(Rules.dim(player.level()), pos.getX(), pos.getZ());
			if (claim != null && !(state.is(ModBlocks.RUBY_HEART))) {
				lines.add("In " + claim.ownerName + "'s claim");
			}
		}
		Net.send(player, new MorePayloads.LookInfo(query.pos(), query.entityId(), lines, bar, color));
	}

	private static SeasonData.Claim claimAt(BlockPos pos) {
		for (SeasonData.Claim c : SeasonData.get().claims.values()) {
			if (!c.destroyed && c.pos().equals(pos)) {
				return c;
			}
		}
		return null;
	}

	private static String name(UUID id) {
		if (id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0) {
			return "shared";
		}
		SeasonData.PlayerRecord r = SeasonData.get().players.get(id.toString());
		return r != null ? r.name : "unknown";
	}

	private static String statusText(int status) {
		return switch (status) {
			case MachineBlockEntity.WORKING -> "Working";
			case MachineBlockEntity.NO_POWER -> "No power";
			case MachineBlockEntity.OUTPUT_FULL -> "Output full";
			case MachineBlockEntity.NO_INPUT -> "Waiting for input";
			case MachineBlockEntity.NIGHT -> "Night: no sun";
			default -> "Idle";
		};
	}
}
