package dev.rubymod.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.block.DefenseBlock;
import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

/**
 * A base's security: Security Cameras you watch from a Monitor, Keycards for Keycard Doors, and Laser Tripwires that
 * set off the base's alarms and turrets. (Where each device is: they say so themselves as they tick.)
 */
public final class BaseSecurity {
	private BaseSecurity() {
	}

	/** Every defense device that's ticking, by kind (dimension|x|y|z), so a base can find its own. */
	private static final Map<DefenseBlock.Kind, Set<String>> DEVICES = new HashMap<>();

	public static void here(ServerLevel level, BlockPos pos, DefenseBlock.Kind kind) {
		DEVICES.computeIfAbsent(kind, k -> new HashSet<>()).add(Rules.dim(level) + "|" + pos.asLong());
	}

	/** This claim's devices of a kind (still there), nearest the given spot first. */
	public static List<BlockPos> inClaim(ServerLevel level, SeasonData.Claim claim, DefenseBlock.Kind kind, BlockPos near) {
		List<BlockPos> out = new ArrayList<>();
		String dim = Rules.dim(level);
		Set<String> all = DEVICES.getOrDefault(kind, Set.of());
		for (var it = all.iterator(); it.hasNext();) {
			String[] f = it.next().split("\\|");
			if (!f[0].equals(dim)) {
				continue;
			}
			BlockPos p = BlockPos.of(Long.parseLong(f[1]));
			if (!level.isLoaded(p)) {
				continue;
			}
			if (!(level.getBlockState(p).getBlock() instanceof DefenseBlock b) || b.kind() != kind) {
				it.remove();
				continue;
			}
			if (SeasonData.get().claimAt(dim, p.getX(), p.getZ()) == claim) {
				out.add(p);
			}
		}
		out.sort(Comparator.comparingDouble(p -> p.distSqr(near)));
		return out;
	}

	// ----- Cameras -----

	/** Someone watching the cameras: which camera, and the invisible stand-in the view is from. */
	private static final class Watch {
		List<BlockPos> cameras;
		int index;
		Display.ItemDisplay eye;
	}

	private static final Map<UUID, Watch> WATCHING = new HashMap<>();

	/** Right-click a Monitor: look through this base's cameras (squad and trusted only). */
	public static void watch(ServerPlayer p, ServerLevel level, BlockPos monitor) {
		SeasonData.Claim claim = SeasonData.get().claimAt(Rules.dim(level), monitor.getX(), monitor.getZ());
		if (claim == null) {
			p.sendOverlayMessage(Component.literal("The Monitor only works inside a claim.").withStyle(ChatFormatting.YELLOW));
			return;
		}
		if (!claim.canUse(p.getUUID())) {
			p.sendOverlayMessage(Component.literal("This Monitor only works for its base's owner.").withStyle(ChatFormatting.RED));
			return;
		}
		List<BlockPos> cameras = inClaim(level, claim, DefenseBlock.Kind.CAMERA, monitor);
		if (cameras.isEmpty()) {
			p.sendOverlayMessage(Component.literal("No Security Cameras in this base yet. Put some up!").withStyle(ChatFormatting.YELLOW));
			return;
		}
		stop(p);
		Watch w = new Watch();
		w.cameras = cameras;
		Display.ItemDisplay eye = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (eye == null) {
			return;
		}
		w.eye = eye;
		place(w);
		level.addFreshEntity(eye);
		WATCHING.put(p.getUUID(), w);
		Net.send(p, new MorePayloads.DroneView(eye.getId(), true));
		tell(p, w);
	}

	private static void place(Watch w) {
		BlockPos c = w.cameras.get(w.index);
		w.eye.snapTo(c.getX() + 0.5, c.getY() + 0.2, c.getZ() + 0.5, 0, 0);
		w.eye.teleportTo(c.getX() + 0.5, c.getY() + 0.2, c.getZ() + 0.5);
	}

	private static void tell(ServerPlayer p, Watch w) {
		BlockPos c = w.cameras.get(w.index);
		p.sendOverlayMessage(Component.literal("Camera " + (w.index + 1) + " of " + w.cameras.size() + "  •  " + c.getX() + ", " + c.getY() + ", " + c.getZ()
				+ "  •  right-click: next camera, crouch: step away").withStyle(ChatFormatting.AQUA));
	}

	/** The watcher's buttons: next camera, or step away. */
	public static void input(ServerPlayer p, boolean next, boolean exit) {
		Watch w = WATCHING.get(p.getUUID());
		if (w == null) {
			return;
		}
		if (exit) {
			stop(p);
			return;
		}
		if (next) {
			w.index = (w.index + 1) % w.cameras.size();
			place(w);
			tell(p, w);
		}
	}

	public static boolean watching(ServerPlayer p) {
		return WATCHING.containsKey(p.getUUID());
	}

	/** Back to your own eyes (stepping away, getting hurt, leaving). */
	public static void stop(ServerPlayer p) {
		Watch w = WATCHING.remove(p.getUUID());
		if (w != null) {
			if (w.eye != null) {
				w.eye.discard();
			}
			Net.send(p, new MorePayloads.DroneView(-1, true));
		}
	}

	// ----- Keycards -----

	private static final String BASE = "keycard_base";

	/** Right-click a Keycard inside your own base: it's that base's card now. */
	public static void bind(ServerPlayer p, ItemStack card) {
		SeasonData.Claim claim = SeasonData.get().claimAt(Rules.dim(p.level()), p.getBlockX(), p.getBlockZ());
		if (claim == null || !claim.canUse(p.getUUID())) {
			p.sendOverlayMessage(Component.literal("Stand in your own base and right-click to make this its Keycard.").withStyle(ChatFormatting.YELLOW));
			return;
		}
		CompoundTag tag = new CompoundTag();
		tag.putString(BASE, claim.owner);
		card.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		card.set(DataComponents.CUSTOM_NAME, Component.literal("Keycard: " + claim.ownerName + "'s base").withStyle(ChatFormatting.AQUA));
		p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.6F);
		p.sendOverlayMessage(Component.literal("Keycard set for " + claim.ownerName + "'s base: it opens its Keycard Doors.").withStyle(ChatFormatting.AQUA));
	}

	/** Does this player carry a Keycard for the base that owns this claim (in either hand)? */
	public static boolean hasCard(Player p, SeasonData.Claim claim) {
		for (ItemStack s : new ItemStack[] {p.getMainHandItem(), p.getOffhandItem()}) {
			if (s.is(ModItems.KEYCARD)) {
				CustomData data = s.get(DataComponents.CUSTOM_DATA);
				if (data != null && claim.owner.equals(data.copyTag().getStringOr(BASE, ""))) {
					return true;
				}
			}
		}
		return false;
	}

	// ----- Laser Tripwires -----

	/** Someone tripped a laser: the base's alarms ring, its turrets wake up, and the intruder glows for the squad. */
	public static void tripped(ServerLevel level, SeasonData.Claim claim, Player intruder, BlockPos laser) {
		Alerts.intruder(level, claim, intruder, laser);
		for (BlockPos alarm : inClaim(level, claim, DefenseBlock.Kind.ALARM, laser)) {
			level.playSound(null, alarm, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2.5F, 1.2F);
			level.sendParticles(ParticleTypes.ANGRY_VILLAGER, alarm.getX() + 0.5, alarm.getY() + 1.2, alarm.getZ() + 0.5, 3, 0.3, 0.2, 0.3, 0);
		}
		long until = level.getGameTime() + 200;
		for (BlockPos turret : inClaim(level, claim, DefenseBlock.Kind.SENTRY_TURRET, laser)) {
			if (level.getBlockEntity(turret) instanceof dev.rubymod.block.DefenseBlockEntity be) {
				be.alert(until);
			}
		}
		UUID owner = UUID.fromString(claim.owner);
		List<UUID> side = new ArrayList<>(Squads.mates(owner));
		side.add(owner);
		MorePayloads.DroneGlow glow = new MorePayloads.DroneGlow(List.of(intruder.getId()), 10);
		for (UUID id : side) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
			if (p != null) {
				Net.send(p, glow);
			}
		}
	}
}
