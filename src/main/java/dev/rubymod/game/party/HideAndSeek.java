package dev.rubymod.game.party;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Holo Hide & Seek: hiders turn into blocks and hide around the town; seekers get 40
 * seconds with their eyes shut, then 3 and a half minutes to find them. Right-click
 * any block to look like it; stand still for 2 seconds and you lock into place like a
 * real block (moving makes you wobble). One hit from a seeker finds you, and then you
 * seek too. Every 30 seconds hiders let out a little chirp.
 */
public class HideAndSeek extends PartyGame {
	private static final int HIDE_TICKS = 40 * 20;
	private static final int SEEK_TICKS = 210 * 20;
	private static final int HIDER = 0;
	private static final int SEEKER = 1;
	private static final BlockState[] STARTERS = {Blocks.BARREL.defaultBlockState(), Blocks.HAY_BLOCK.defaultBlockState(),
			Blocks.PUMPKIN.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState()};

	/** A hider's disguise: the block display, what it shows, and whether it's locked in place. */
	private static final class Disguise {
		UUID display;
		BlockState state;
		boolean locked;
		int still;
		Vec3 lastPos;
	}

	private final Map<UUID, Disguise> disguises = new HashMap<>();

	public HideAndSeek() {
		super("hideseek", "Holo Hide & Seek", "Turn into a block and hide! Seekers have to find you.", "rubymod:holo_camo",
				2, false, false, false);
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(-45000, GROUND + 1, 0);
	}

	@Override
	protected void build(ServerLevel level) {
		Town.build(this, level);
		// The seekers' waiting room, high above the square.
		box(level, -3, 24, -3, 3, 28, 3, Blocks.TINTED_GLASS.defaultBlockState());
		box(level, -2, 25, -2, 2, 27, 2, Blocks.AIR.defaultBlockState());
	}

	@Override
	protected boolean hideNames() {
		return true;
	}

	private boolean hiding() {
		return roundTicks < HIDE_TICKS;
	}

	@Override
	protected String roundStatus() {
		long hiders = players.values().stream().filter(p -> !p.watching && p.team == HIDER).count();
		int left = hiding() ? (HIDE_TICKS - roundTicks) / 20 : (HIDE_TICKS + SEEK_TICKS - roundTicks) / 20;
		return (hiding() ? "Hide! Seekers come out in " + left + "s" : hiders + " hiding  •  " + left / 60 + ":" + String.format("%02d", left % 60));
	}

	@Override
	protected float barProgress() {
		return hiding() ? 1 - roundTicks / (float) HIDE_TICKS : 1 - (roundTicks - HIDE_TICKS) / (float) SEEK_TICKS;
	}

	@Override
	protected void startRound() {
		disguises.clear();
		List<UUID> ids = new ArrayList<>(players.keySet());
		Collections.shuffle(ids);
		int seekers = Math.max(1, ids.size() / 4);
		for (int i = 0; i < ids.size(); i++) {
			players.get(ids.get(i)).team = i < seekers ? SEEKER : HIDER;
		}
		for (ServerPlayer p : online()) {
			spawn(p, players.get(p.getUUID()));
			Part part = players.get(p.getUUID());
			if (part.team == SEEKER) {
				p.sendSystemMessage(Component.literal("You're a SEEKER. Wait 40 seconds, then hit blocks that look wrong: one hit finds a hider.")
						.withStyle(ChatFormatting.RED));
				dev.rubymod.game.Alerts.title(p, "SEEKER", "Eyes shut for 40 seconds...", ChatFormatting.RED);
			} else {
				p.sendSystemMessage(Component.literal("You're a HIDER. Right-click any block to look like it, then hide! "
						+ "Stand still for 2 seconds and you lock in place like a real block.").withStyle(ChatFormatting.GREEN));
				dev.rubymod.game.Alerts.title(p, "HIDE!", "Right-click a block to turn into it", ChatFormatting.GREEN);
			}
		}
	}

	@Override
	protected void spawn(ServerPlayer player, Part part) {
		ready(player);
		BlockPos c = center();
		if (part.team == SEEKER && hiding()) {
			teleport(player, c.getX() + 0.5, GROUND + 25, c.getZ() + 0.5, 0);
			player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, HIDE_TICKS - roundTicks + 20, 0, false, false));
			player.setGameMode(net.minecraft.world.level.GameType.ADVENTURE);
		} else {
			double angle = player.getRandom().nextDouble() * Math.PI * 2;
			teleport(player, c.getX() + 0.5 + Math.cos(angle) * 8, c.getY(), c.getZ() + 0.5 + Math.sin(angle) * 8, player.getRandom().nextFloat() * 360);
			player.setGameMode(net.minecraft.world.level.GameType.ADVENTURE);
		}
		clearPlayer(player);
		player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, -1, 0, false, false));
		if (part.team == SEEKER) {
			give(player, new ItemStack(Items.IRON_SWORD));
			player.addEffect(new MobEffectInstance(MobEffects.SPEED, -1, 0, false, false));
		} else {
			player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false));
			Disguise d = disguises.get(player.getUUID());
			disguise(player, d != null ? d.state : STARTERS[player.getRandom().nextInt(STARTERS.length)]);
		}
	}

	private void disguise(ServerPlayer player, BlockState state) {
		Disguise d = disguises.computeIfAbsent(player.getUUID(), k -> new Disguise());
		d.state = state;
		d.locked = false;
		d.still = 0;
		ServerLevel level = level();
		Display.BlockDisplay display = d.display == null ? null : level.getEntity(d.display) instanceof Display.BlockDisplay b ? b : null;
		if (display == null) {
			display = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (display == null) {
				return;
			}
			display.snapTo(player.getX() - 0.5, player.getY(), player.getZ() - 0.5, 0, 0);
			((DisplayAccessor) display).rubymod$setPosRotInterpolationDuration(2);
			display.addTag(tag());
			level.addFreshEntity(display);
			spawned.add(display.getUUID());
			d.display = display.getUUID();
		}
		((BlockDisplayAccessor) display).rubymod$setBlockState(state);
	}

	private void undisguise(UUID id) {
		Disguise d = disguises.remove(id);
		if (d != null && d.display != null) {
			Entity e = level().getEntity(d.display);
			if (e != null) {
				e.discard();
			}
		}
	}

	/** Hiders right-click a block to look like it. */
	@Override
	public boolean onUseBlock(ServerPlayer player, BlockPos pos, ItemStack held, BlockPos placeAt) {
		Part part = players.get(player.getUUID());
		if (phase != Phase.PLAYING || part == null || part.watching) {
			return phase == Phase.PLAYING;
		}
		if (part.team == HIDER) {
			BlockState state = level().getBlockState(pos);
			if (!state.isAir() && state.isCollisionShapeFullBlock(level(), pos) && !state.is(Blocks.BARRIER)) {
				disguise(player, state);
				player.sendOverlayMessage(Component.literal("You look like: " + state.getBlock().getName().getString()).withStyle(ChatFormatting.GREEN));
				player.level().playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.4F, 1.8F);
			} else {
				player.sendOverlayMessage(Component.literal("Pick a full block (not glass panes, fences or flowers).").withStyle(ChatFormatting.GRAY));
			}
		}
		return true; // no opening barrels or furnaces
	}

	@Override
	public boolean allowsBreak(ServerPlayer player, BlockPos pos) {
		return false;
	}

	/** A seeker's hit finds a hider (no damage); everything else between players does nothing. */
	@Override
	public Boolean canHurt(Player attacker, Player victim) {
		Part a = players.get(attacker.getUUID());
		Part v = players.get(victim.getUUID());
		if (a == null && v == null) {
			return null;
		}
		if (a != null && v != null && phase == Phase.PLAYING && !hiding() && a.team == SEEKER && v.team == HIDER && !v.watching
				&& victim instanceof ServerPlayer found && attacker instanceof ServerPlayer seeker) {
			found(found, v, seeker);
		}
		return false;
	}

	private void found(ServerPlayer hider, Part part, ServerPlayer seeker) {
		part.team = SEEKER;
		players.get(seeker.getUUID()).score++;
		undisguise(hider.getUUID());
		hider.removeEffect(MobEffects.INVISIBILITY);
		give(hider, new ItemStack(Items.IRON_SWORD));
		hider.addEffect(new MobEffectInstance(MobEffects.SPEED, -1, 0, false, false));
		level().sendParticles(ParticleTypes.FIREWORK, hider.getX(), hider.getY() + 1, hider.getZ(), 30, 0.4, 0.6, 0.4, 0.1);
		level().playSound(null, hider.blockPosition(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1.0F, 1.0F);
		dev.rubymod.game.Alerts.title(hider, "FOUND!", "by " + seeker.getName().getString() + ". Now you seek too!", ChatFormatting.RED);
		tell(Component.literal(seeker.getName().getString() + " found " + part.name + "!").withStyle(ChatFormatting.YELLOW));
		checkEnd();
	}

	private void checkEnd() {
		long hiders = players.values().stream().filter(p -> !p.watching && p.team == HIDER).count();
		if (hiders == 0) {
			Part best = players.values().stream().max(java.util.Comparator.comparingInt(p -> p.score)).orElse(null);
			endRound("SEEKERS WIN", "Everyone was found!" + (best != null && best.score > 0 ? " Best seeker: " + best.name + " (" + best.score + ")" : ""),
					ChatFormatting.RED);
			disguises.clear();
		}
	}

	@Override
	protected void onLeft(UUID id, Part part) {
		undisguise(id);
		long seekers = players.values().stream().filter(p -> !p.watching && p.team == SEEKER).count();
		if (seekers == 0 && !players.isEmpty()) {
			// The seeker left: someone else takes over.
			UUID next = players.keySet().iterator().next();
			players.get(next).team = SEEKER;
			ServerPlayer p = PartyGames.player(server, next);
			if (p != null) {
				undisguise(next);
				p.removeEffect(MobEffects.INVISIBILITY);
				give(p, new ItemStack(Items.IRON_SWORD));
				dev.rubymod.game.Alerts.title(p, "SEEKER", "The seeker left: you're it now!", ChatFormatting.RED);
			}
		}
		checkEnd();
	}

	@Override
	protected void onDowned(ServerPlayer victim, Part part, Entity killer) {
		part.respawnIn = 40;
	}

	@Override
	protected void tickRound() {
		ServerLevel level = level();
		if (roundTicks == HIDE_TICKS) {
			for (ServerPlayer p : online()) {
				Part part = players.get(p.getUUID());
				if (part.team == SEEKER && !part.watching) {
					spawn(p, part);
					dev.rubymod.game.Alerts.title(p, "GO!", "Find the hiders: hit blocks that look wrong", ChatFormatting.RED);
				} else {
					dev.rubymod.game.Alerts.title(p, "HERE THEY COME", "Stay still!", ChatFormatting.YELLOW);
				}
			}
		}
		// Disguises follow their hiders; standing still locks them to the block grid.
		for (ServerPlayer p : online()) {
			Disguise d = disguises.get(p.getUUID());
			Part part = players.get(p.getUUID());
			if (d == null || part == null || part.team != HIDER) {
				continue;
			}
			if (!(level.getEntity(d.display) instanceof Display.BlockDisplay display)) {
				continue;
			}
			Vec3 pos = p.position();
			boolean moved = d.lastPos != null && d.lastPos.distanceToSqr(pos) > 0.0004;
			d.lastPos = pos;
			if (moved) {
				d.still = 0;
				d.locked = false;
				double wobble = Math.sin(roundTicks * 0.8) * 0.06;
				display.teleportTo(pos.x - 0.5, pos.y + wobble, pos.z - 0.5);
			} else if (!d.locked && ++d.still >= 40) {
				d.locked = true;
				BlockPos at = BlockPos.containing(pos);
				p.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
				d.lastPos = p.position();
				display.teleportTo(at.getX(), at.getY(), at.getZ());
				p.sendOverlayMessage(Component.literal("Locked in place: you look like a real block. Don't move!").withStyle(ChatFormatting.GREEN));
			} else if (!d.locked) {
				display.teleportTo(pos.x - 0.5, pos.y, pos.z - 0.5);
			}
		}
		// Every 30 seconds of seeking, hiders chirp.
		if (!hiding() && (roundTicks - HIDE_TICKS) % 600 == 300) {
			for (ServerPlayer p : online()) {
				Part part = players.get(p.getUUID());
				if (part.team == HIDER && !part.watching) {
					level.playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.2F, 1.6F);
					level.sendParticles(ParticleTypes.NOTE, p.getX(), p.getY() + 1.4, p.getZ(), 2, 0.2, 0.2, 0.2, 0);
				}
			}
			tell(Component.literal("The hiders chirped! Listen...").withStyle(ChatFormatting.AQUA));
		}
		if (roundTicks >= HIDE_TICKS + SEEK_TICKS) {
			StringBuilder names = new StringBuilder();
			players.values().stream().filter(p -> p.team == HIDER && !p.watching)
					.forEach(p -> names.append(names.isEmpty() ? "" : ", ").append(p.name));
			endRound("HIDERS WIN", "Still hidden: " + names, ChatFormatting.GREEN);
			for (UUID id : new ArrayList<>(disguises.keySet())) {
				undisguise(id);
			}
		}
	}

	@Override
	public boolean allowsGuns(ServerPlayer player) {
		return false;
	}
}
