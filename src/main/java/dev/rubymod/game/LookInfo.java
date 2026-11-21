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
				String holds = holds(m, kind.inputs, m.getContainerSize());
				if (kind.slots() > 0) {
					lines.add(holds.isEmpty() ? "Empty" : "Holds " + holds);
					if (!holds.isEmpty() && kind.slots() > kind.inputs) {
						lines.add("Crouch + empty hand: collect it all");
					}
				}
				if (kind == MachineKind.RUBY_DRILL) {
					lines.add("Finds: " + dev.rubymod.industry.Recipes.FOCUS_NAMES[m.focus()] + (m.leavesStone() ? ", leaves stone behind" : "")
							+ ". " + m.site().describe());
				}
				if (kind == MachineKind.BATTERY || kind.output(m.tier()) > 0 || kind.draw(m.tier()) > 0) {
					UUID owner = m.power();
					long cap = Grid.capacity(owner);
					bar = cap > 0 ? (float) Grid.charge(owner) / cap : -1;
					lines.add(String.format(java.util.Locale.ROOT, "Grid: %,d / %,d charge", Grid.charge(owner), cap));
				}
				color = m.status() == MachineBlockEntity.WORKING ? GREEN : m.status() == MachineBlockEntity.IDLE ? BLUE : AMBER;
			} else if (be instanceof dev.rubymod.industry.SiloBlockEntity silo) {
				lines.add(dev.rubymod.industry.OreSiloBlock.describe(silo));
				lines.add("Owner: " + (silo.owner() == null ? "nobody" : name(silo.owner())));
				lines.add(silo.total() > 0 ? "Empty hand: take a stack (crouch: one)" : "Right-click with something to store it");
				bar = silo.total() > 0 ? (float) silo.total() / silo.capacity() : 0;
				color = BLUE;
			} else if (state.getBlock() instanceof dev.rubymod.industry.BeltLoaderBlock) {
				net.minecraft.core.Direction facing = state.getValue(dev.rubymod.industry.BeltLoaderBlock.FACING);
				String what = be instanceof dev.rubymod.industry.FilterBlockEntity f ? f.filterName() : "anything";
				lines.add("Belt Loader: takes " + what);
				BlockState behind = player.level().getBlockState(pos.relative(facing.getOpposite()));
				BlockState ahead = player.level().getBlockState(pos.relative(facing));
				boolean from = dev.rubymod.industry.Belts.containerAt(player.level(), pos.relative(facing.getOpposite())) != null;
				boolean to = ahead.getBlock() instanceof dev.rubymod.industry.ConveyorBlock
						|| dev.rubymod.industry.Belts.containerAt(player.level(), pos.relative(facing)) != null;
				lines.add(from ? "From the " + behind.getBlock().getName().getString() : "Nothing behind it to take from");
				lines.add(to ? "Onto the " + ahead.getBlock().getName().getString() : "Put a belt (or a chest) in front of it");
				color = from && to ? GREEN : AMBER;
			} else if (state.getBlock() instanceof dev.rubymod.industry.BeltSorterBlock) {
				String what = be instanceof dev.rubymod.industry.FilterBlockEntity f && !f.filter().isEmpty() ? f.filterName() : null;
				lines.add("Belt Sorter, running " + state.getValue(dev.rubymod.industry.ConveyorBlock.FACING).getName());
				lines.add(what == null ? "Turns nothing yet: right-click it with an item"
						: what + " turns " + (state.getValue(dev.rubymod.industry.BeltSorterBlock.RIGHT) ? "right" : "left") + ", the rest goes straight on");
				color = what == null ? AMBER : GREEN;
			} else if (state.getBlock() instanceof dev.rubymod.industry.BeltTunnelBlock) {
				boolean exit = state.getValue(dev.rubymod.industry.BeltTunnelBlock.EXIT);
				net.minecraft.core.Direction facing = state.getValue(dev.rubymod.industry.BeltTunnelBlock.FACING);
				BlockPos other = exit ? dev.rubymod.industry.BeltTunnelBlock.entranceFor(player.level(), pos, state)
						: dev.rubymod.industry.BeltTunnelBlock.exitFor(player.level(), pos, state);
				lines.add(exit ? "Belt Tunnel exit" : "Belt Tunnel entrance");
				if (other == null) {
					lines.add(exit ? "No entrance: place one behind it, facing the same way (up to 7 blocks)"
							: "No exit: place one ahead of it, facing the same way (up to 7 blocks)");
				} else {
					lines.add((exit ? "From the entrance " : "To the exit ") + pos.distManhattan(other) + " blocks " + (exit ? "back" : "on"));
					if (!exit && be instanceof dev.rubymod.industry.BeltTunnelBlockEntity t && t.carrying() > 0) {
						lines.add("Carrying " + t.carrying());
					}
					if (exit) {
						BlockPos out = pos.relative(facing);
						boolean takes = player.level().getBlockState(out).getBlock() instanceof dev.rubymod.industry.ConveyorBlock
								|| dev.rubymod.industry.Belts.containerAt(player.level(), out) != null;
						if (!takes) {
							lines.add("Put a belt (or a chest) in front of it");
						}
					}
				}
				color = other == null ? AMBER : GREEN;
			} else if (state.getBlock() instanceof dev.rubymod.industry.ConveyorBlock) {
				net.minecraft.core.Direction facing = state.getValue(dev.rubymod.industry.ConveyorBlock.FACING);
				boolean stopped = state.getValue(dev.rubymod.industry.ConveyorBlock.POWERED);
				String name = state.getBlock().getName().getString();
				if (state.getBlock() instanceof dev.rubymod.industry.BeltRampBlock) {
					lines.add(name + ", carrying things " + (state.getValue(dev.rubymod.industry.BeltRampBlock.DOWN) ? "down" : "up") + " " + facing.getName());
				} else if (state.getBlock() instanceof dev.rubymod.industry.BeltSplitterBlock) {
					lines.add(name + ": shares things left and right");
				} else {
					var turn = dev.rubymod.industry.ConveyorBlock.turnOf(state);
					lines.add(name + (turn == dev.rubymod.industry.ConveyorBlock.Turn.NONE ? ", running " : ", curving " + turn.getSerializedName() + " to run ")
							+ facing.getName());
				}
				BlockPos ahead = state.getBlock() instanceof dev.rubymod.industry.BeltRampBlock && !state.getValue(dev.rubymod.industry.BeltRampBlock.DOWN)
						? pos.relative(facing).above() : pos.relative(facing);
				BlockState frontState = player.level().getBlockState(ahead);
				boolean feeds = dev.rubymod.industry.Belts.containerAt(player.level(), ahead) != null;
				boolean belt = frontState.getBlock() instanceof dev.rubymod.industry.ConveyorBlock;
				if (stopped) {
					lines.add("Stopped by redstone");
				} else if (!(state.getBlock() instanceof dev.rubymod.industry.BeltSplitterBlock)) {
					lines.add(feeds ? "Feeds the " + frontState.getBlock().getName().getString()
							: belt ? "Feeds the next belt"
									: frontState.isFaceSturdy(player.level(), ahead, facing.getOpposite()) ? "Nothing at the end: items stop there (anyone can take them)"
											: "Nothing at the end: items fall off");
				}
				// What's riding on it, by name (most first), and how much is on the rest of the line ahead.
				java.util.Map<String, Integer> riding = new java.util.LinkedHashMap<>();
				for (var item : player.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(0.1))) {
					riding.merge(item.getItem().getHoverName().getString(), item.getItem().getCount(), Integer::sum);
				}
				if (riding.isEmpty()) {
					lines.add("Nothing on it");
				} else {
					var sorted = riding.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).toList();
					StringBuilder sb = new StringBuilder("Carrying ");
					for (int i = 0; i < Math.min(3, sorted.size()); i++) {
						sb.append(i == 0 ? "" : ", ").append(sorted.get(i).getValue()).append(" ").append(sorted.get(i).getKey());
					}
					if (sorted.size() > 3) {
						sb.append(" and more");
					}
					lines.add(sb.toString());
				}
				int ahead2 = 0;
				BlockPos walk = pos;
				for (int i = 0; i < 32; i++) {
					BlockState ws = player.level().getBlockState(walk);
					if (!(ws.getBlock() instanceof dev.rubymod.industry.ConveyorBlock)) {
						break;
					}
					net.minecraft.core.Direction wf = ws.getValue(dev.rubymod.industry.ConveyorBlock.FACING);
					BlockPos next = ws.getBlock() instanceof dev.rubymod.industry.BeltRampBlock
							? (ws.getValue(dev.rubymod.industry.BeltRampBlock.DOWN) ? walk.relative(wf).below() : walk.relative(wf).above())
							: walk.relative(wf);
					if (!(player.level().getBlockState(next).getBlock() instanceof dev.rubymod.industry.ConveyorBlock)) {
						// maybe the ramp's top or bottom is level with the next belt
						next = walk.relative(wf);
						if (!(player.level().getBlockState(next).getBlock() instanceof dev.rubymod.industry.ConveyorBlock)) {
							break;
						}
					}
					walk = next;
					for (var item : player.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(walk).inflate(0.1))) {
						ahead2 += item.getItem().getCount();
					}
				}
				if (ahead2 > 0) {
					lines.add(ahead2 + " more on the belts ahead");
				}
				color = stopped ? AMBER : feeds || belt || state.getBlock() instanceof dev.rubymod.industry.BeltSplitterBlock ? GREEN : AMBER;
			} else if (be instanceof net.minecraft.world.Container chest && !(be instanceof DefenseBlockEntity) && !(be instanceof GraveBlockEntity)
					&& (state.is(net.minecraft.world.level.block.Blocks.CHEST) || state.is(net.minecraft.world.level.block.Blocks.BARREL)
							|| state.is(net.minecraft.world.level.block.Blocks.TRAPPED_CHEST) || state.is(net.minecraft.world.level.block.Blocks.HOPPER))) {
				lines.add(state.getBlock().getName().getString());
				String holds = holds(chest, 0, chest.getContainerSize());
				lines.add(holds.isEmpty() ? "Empty" : holds);
				int used = 0;
				for (int i = 0; i < chest.getContainerSize(); i++) {
					if (!chest.getItem(i).isEmpty()) {
						used++;
					}
				}
				bar = used / (float) Math.max(1, chest.getContainerSize());
				color = used >= chest.getContainerSize() ? AMBER : BLUE;
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
				lines.add(SeasonData.get().phase.pvp() ? "Armed: enemies, raiders and monsters" : "Armed for raiders and monsters (enemies from Open War)");
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

	/** "12 Cobblestone, 4 Raw Iron and 2 more" from slots {@code from} to {@code to}. */
	private static String holds(net.minecraft.world.Container c, int from, int to) {
		java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
		for (int i = from; i < to; i++) {
			var s = c.getItem(i);
			if (!s.isEmpty()) {
				counts.merge(s.getHoverName().getString(), s.getCount(), Integer::sum);
			}
		}
		StringBuilder b = new StringBuilder();
		int n = 0;
		for (var e : counts.entrySet()) {
			if (n == 2) {
				b.append(" and ").append(counts.size() - 2).append(" more");
				break;
			}
			b.append(n > 0 ? ", " : "").append(e.getValue()).append(' ').append(e.getKey());
			n++;
		}
		return b.toString();
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
			case MachineBlockEntity.NO_POWER -> "No power: add solar panels or a generator";
			case MachineBlockEntity.OUTPUT_FULL -> "Full: empty it, or add a conveyor belt";
			case MachineBlockEntity.NO_INPUT -> "Waiting for input";
			case MachineBlockEntity.NIGHT -> "Night: no sun";
			default -> "Idle";
		};
	}
}
