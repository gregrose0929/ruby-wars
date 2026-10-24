package dev.rubymod.game.party;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;

/**
 * Infection: one player starts as a zombie (fast and strong, no weapons). Anyone a
 * zombie gets becomes a zombie too. Survivors have a pistol and a shotgun and must
 * hold out for 4 minutes. Wild zombies wander in from the edges to keep survivors
 * moving, even with just two players.
 */
public class Infection extends PartyGame {
	private static final int ROUND_TICKS = 4 * 60 * 20;
	private static final int SURVIVOR = 0;
	private static final int ZOMBIE = 1;

	public Infection() {
		super("infection", "Infection", "One zombie at the start. Get caught and you're a zombie too! Survive 4 minutes.",
				"minecraft:zombie_head", 2, false, true, false);
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(-50000, GROUND + 1, 0);
	}

	@Override
	protected void build(ServerLevel level) {
		Town.build(this, level);
	}

	private long count(int team) {
		return players.values().stream().filter(p -> !p.watching && p.team == team).count();
	}

	@Override
	protected String roundStatus() {
		int left = Math.max(0, ROUND_TICKS - roundTicks) / 20;
		return count(SURVIVOR) + " survivors, " + count(ZOMBIE) + " zombies  •  " + left / 60 + ":" + String.format("%02d", left % 60);
	}

	@Override
	protected float barProgress() {
		return 1 - roundTicks / (float) ROUND_TICKS;
	}

	@Override
	protected void startRound() {
		List<UUID> ids = new ArrayList<>(players.keySet());
		Collections.shuffle(ids);
		int zombies = Math.max(1, ids.size() / 5);
		for (int i = 0; i < ids.size(); i++) {
			players.get(ids.get(i)).team = i < zombies ? ZOMBIE : SURVIVOR;
		}
		for (ServerPlayer p : online()) {
			Part part = players.get(p.getUUID());
			spawn(p, part);
			dev.rubymod.game.Alerts.title(p, part.team == ZOMBIE ? "YOU'RE THE ZOMBIE" : "SURVIVE!",
					part.team == ZOMBIE ? "Catch them all!" : "Don't let the zombie get you", part.team == ZOMBIE ? ChatFormatting.DARK_GREEN : ChatFormatting.GREEN);
		}
	}

	/** A spot near the town's edge, away from survivors (for zombies) or anywhere open. */
	private BlockPos spot(boolean edge) {
		var random = level().getRandom();
		BlockPos c = center();
		for (int tries = 0; tries < 40; tries++) {
			int r = Town.HALF - 3;
			int x = edge ? (random.nextBoolean() ? r : -r) : random.nextInt(2 * r) - r;
			int z = random.nextInt(2 * r) - r;
			if (edge && random.nextBoolean()) {
				int t = x;
				x = z;
				z = t;
			}
			BlockPos p = c.offset(x, 0, z);
			if (level().getBlockState(p).isAir() && level().getBlockState(p.above()).isAir()) {
				return p;
			}
		}
		return c.offset(0, 0, 8);
	}

	@Override
	protected void spawn(ServerPlayer player, Part part) {
		BlockPos at = spot(part.team == ZOMBIE);
		teleport(player, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, player.getRandom().nextFloat() * 360);
		ready(player);
		clearPlayer(player);
		player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, -1, 0, false, false));
		if (part.team == ZOMBIE) {
			player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.ZOMBIE_HEAD));
			for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
				ItemStack piece = new ItemStack(slot == EquipmentSlot.CHEST ? Items.LEATHER_CHESTPLATE : slot == EquipmentSlot.LEGS ? Items.LEATHER_LEGGINGS
						: Items.LEATHER_BOOTS);
				piece.set(DataComponents.DYED_COLOR, new DyedItemColor(0x3C7A2E));
				player.setItemSlot(slot, piece);
			}
			player.addEffect(new MobEffectInstance(MobEffects.SPEED, -1, 1, false, false));
			player.addEffect(new MobEffectInstance(MobEffects.STRENGTH, -1, 1, false, false));
			player.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, -1, 0, false, false));
		} else {
			give(player, gun(ModItems.SERVICE_PISTOL), gun(ModItems.COMBAT_SHOTGUN), new ItemStack(ModItems.LIGHT_AMMO, 64),
					new ItemStack(ModItems.SHOTGUN_SHELL, 40), new ItemStack(Items.GOLDEN_APPLE, 2));
			player.getInventory().setSelectedSlot(1);
		}
	}

	@Override
	public Boolean canHurt(Player attacker, Player victim) {
		Part a = players.get(attacker.getUUID());
		Part v = players.get(victim.getUUID());
		if (a == null && v == null) {
			return null;
		}
		return a != null && v != null && phase == Phase.PLAYING && a.team != v.team && !a.watching && !v.watching;
	}

	@Override
	protected void onDowned(ServerPlayer victim, Part part, Entity killer) {
		if (part.team == SURVIVOR) {
			part.team = ZOMBIE;
			part.respawnIn = 60;
			if (killer instanceof ServerPlayer z && players.containsKey(z.getUUID())) {
				players.get(z.getUUID()).score++;
			}
			dev.rubymod.game.Alerts.title(victim, "INFECTED!", "You're a zombie now: go get them!", ChatFormatting.DARK_GREEN);
			tell(Component.literal(part.name + " was infected! " + count(SURVIVOR) + " survivors left.").withStyle(ChatFormatting.DARK_GREEN));
			level().playSound(null, victim.blockPosition(), SoundEvents.ZOMBIE_VILLAGER_CONVERTED, SoundSource.PLAYERS, 1.0F, 1.0F);
			if (count(SURVIVOR) == 0) {
				Part best = players.values().stream().max(Comparator.comparingInt(p -> p.score)).orElse(null);
				endRound("ZOMBIES WIN", "Everyone was infected!" + (best != null && best.score > 0 ? " Most bites: " + best.name + " (" + best.score + ")" : ""),
						ChatFormatting.DARK_GREEN, teamIds(ZOMBIE));
			}
		} else {
			part.respawnIn = 80;
		}
	}

	@Override
	protected void onLeft(UUID id, Part part) {
		if (count(ZOMBIE) == 0 && count(SURVIVOR) > 0) {
			UUID next = players.keySet().iterator().next();
			players.get(next).team = ZOMBIE;
			ServerPlayer p = PartyGames.player(server, next);
			if (p != null) {
				spawn(p, players.get(next));
				dev.rubymod.game.Alerts.title(p, "YOU'RE THE ZOMBIE", "The zombie left: you're it now!", ChatFormatting.DARK_GREEN);
			}
		} else if (count(SURVIVOR) == 0) {
			endRound("ZOMBIES WIN", "No survivors left.", ChatFormatting.DARK_GREEN, teamIds(ZOMBIE));
		}
	}

	@Override
	protected void tickRound() {
		ServerLevel level = level();
		// Wild zombies wander in to keep survivors moving.
		if (roundTicks % 300 == 150 && level.getDifficulty() != Difficulty.PEACEFUL) {
			long wild = spawned.stream().filter(id -> level.getEntity(id) instanceof Mob m && m.isAlive()).count();
			int add = (int) Math.min(count(SURVIVOR), 8 - wild);
			for (int i = 0; i < add; i++) {
				var zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.EVENT);
				if (zombie == null) {
					continue;
				}
				BlockPos at = spot(true);
				zombie.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
				ItemStack helmet = new ItemStack(Items.LEATHER_HELMET);
				helmet.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
				zombie.setItemSlot(EquipmentSlot.HEAD, helmet);
				zombie.setDropChance(EquipmentSlot.HEAD, 0);
				zombie.addTag(tag());
				zombie.addTag("rubymod_horde"); // allowed even with hostile mobs off
				zombie.setPersistenceRequired();
				level.addFreshEntity(zombie);
				spawned.add(zombie.getUUID());
			}
		}
		if (roundTicks % 20 == 0) {
			List<ServerPlayer> survivors = online().stream().filter(p -> {
				Part part = players.get(p.getUUID());
				return part.team == SURVIVOR && !part.watching && !p.isSpectator();
			}).toList();
			for (UUID id : spawned) {
				if (level.getEntity(id) instanceof Mob m && m.isAlive() && !survivors.isEmpty()
						&& !(m.getTarget() instanceof ServerPlayer t && survivors.contains(t))) {
					survivors.stream().min(Comparator.comparingDouble(s -> s.distanceToSqr(m))).ifPresent(m::setTarget);
				}
			}
		}
		if (roundTicks >= ROUND_TICKS) {
			StringBuilder names = new StringBuilder();
			players.values().stream().filter(p -> p.team == SURVIVOR && !p.watching)
					.forEach(p -> names.append(names.isEmpty() ? "" : ", ").append(p.name));
			endRound("SURVIVORS WIN", "Made it out: " + names, ChatFormatting.GREEN, teamIds(SURVIVOR));
		}
	}

	@Override
	public boolean allowsBreak(ServerPlayer player, BlockPos pos) {
		return false;
	}

	@Override
	public boolean onUseBlock(ServerPlayer player, BlockPos pos, ItemStack held, BlockPos placeAt) {
		return phase == Phase.PLAYING && !held.isEmpty() && held.getItem() instanceof net.minecraft.world.item.BlockItem;
	}
}
