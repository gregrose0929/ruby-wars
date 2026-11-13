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
					"minecraft:zombie_head", 1, true, true, false);
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(-50000, GROUND + 1, 0);
	}

	@Override
	protected void build(ServerLevel level) {
		Town.build(this, level);
	}

	/** The first zombie: it can roar (crouch) to slow and reveal survivors nearby. */
	private UUID alpha;
	private int roarAt;
	/** The last survivor has had their hero's weapon. */
	private boolean lastStand;
	private String hero;

	private void roar(ServerPlayer zombie) {
		roarAt = roundTicks + 20 * 20;
		ServerLevel level = level();
		level.playSound(null, zombie.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, 2.5F, 0.8F);
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.SONIC_BOOM, zombie.getX(), zombie.getY() + 1.2, zombie.getZ(), 1, 0, 0, 0, 0);
		dev.rubymod.game.rift.RiftFx.ring(level, zombie.position().add(0, 0.3, 0), 12, net.minecraft.core.particles.ParticleTypes.SCULK_SOUL, 48);
		int caught = 0;
		for (ServerPlayer p : online()) {
			Part part = players.get(p.getUUID());
			if (part != null && part.team == SURVIVOR && !part.watching && !p.isSpectator() && p.distanceTo(zombie) <= 12) {
				p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1, false, true));
				p.addEffect(new MobEffectInstance(MobEffects.GLOWING, 120, 0, false, false));
				dev.rubymod.game.Alerts.title(p, "", "The Alpha roared: you're slowed. RUN!", ChatFormatting.DARK_GREEN);
				caught++;
			}
		}
		zombie.sendOverlayMessage(Component.literal(caught == 0 ? "ROAR! Nobody was close enough." : "ROAR! " + caught + " slowed and lit up.")
				.withStyle(ChatFormatting.DARK_GREEN));
	}

	/** One survivor left against a crowd: they get the big gun, a glow, and the music. */
	private void heroMoment(ServerPlayer survivor, Part part) {
		lastStand = true;
		hero = part.name;
		give(survivor, gun(ModItems.RUBY_MINIGUN), new ItemStack(ModItems.HEAVY_AMMO, 64), new ItemStack(ModItems.HEAVY_AMMO, 64), new ItemStack(Items.GOLDEN_APPLE, 2));
		survivor.addEffect(new MobEffectInstance(MobEffects.SPEED, -1, 0, false, false));
		survivor.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, -1, 0, false, false));
		survivor.setGlowingTag(true);
		dev.rubymod.game.Alerts.title(survivor, "LAST ONE STANDING", "Here's a Ruby Minigun. Hold out!", ChatFormatting.GOLD);
		for (ServerPlayer p : online()) {
			if (p != survivor) {
				dev.rubymod.game.Alerts.title(p, "LAST SURVIVOR", part.name + " has a minigun. Get them!", ChatFormatting.RED);
			}
			dev.rubymod.game.Alerts.jingle(p, dev.rubymod.registry.ModSounds.GOLDEN, 0.9F);
			p.level().playSound(null, p.blockPosition(), SoundEvents.MUSIC_DISC_PIGSTEP.value(), SoundSource.RECORDS, 1.0F, 1.0F);
		}
		tell(Component.literal(part.name + " is the LAST SURVIVOR!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
	}

	private void stopMusic() {
		for (ServerPlayer p : online()) {
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundStopSoundPacket(null, SoundSource.RECORDS));
		}
	}

	@Override
	protected List<Component> extraAwards() {
		stopMusic();
		List<Component> lines = new ArrayList<>();
		if (hero != null) {
			lines.add(award("Last one standing", hero, ChatFormatting.GOLD));
		}
		Part biter = players.values().stream().filter(p -> p.score > 0).max(Comparator.comparingInt(p -> p.score)).orElse(null);
		if (biter != null) {
			lines.add(award("Hungriest zombie", biter.name + " (" + biter.score + (biter.score == 1 ? " bite)" : " bites)"), ChatFormatting.DARK_GREEN));
		}
		return lines;
	}

	/** One player: a survivor against the wild zombies. */
	private boolean solo;

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
		// On your own: nobody starts as the zombie. The wild ones come thicker and faster instead.
		solo = ids.size() == 1;
		int zombies = solo ? 0 : Math.max(1, ids.size() / 5);
		for (int i = 0; i < ids.size(); i++) {
			players.get(ids.get(i)).team = i < zombies ? ZOMBIE : SURVIVOR;
		}
		alpha = ids.isEmpty() || solo ? null : ids.get(0);
		roarAt = 15 * 20;
		lastStand = false;
		hero = null;
		for (ServerPlayer p : online()) {
			Part part = players.get(p.getUUID());
			spawn(p, part);
			boolean isAlpha = p.getUUID().equals(alpha);
			dev.rubymod.game.Alerts.title(p, isAlpha ? "YOU'RE THE ALPHA ZOMBIE" : part.team == ZOMBIE ? "YOU'RE A ZOMBIE" : "SURVIVE!",
					isAlpha ? "Crouch to ROAR: it slows survivors near you" : part.team == ZOMBIE ? "Catch them all!" : "Don't let the zombies get you",
					part.team == ZOMBIE ? ChatFormatting.DARK_GREEN : ChatFormatting.GREEN);
			if (isAlpha) {
				p.sendSystemMessage(Component.literal("You're the Alpha: crouch to ROAR (every 20 seconds). Survivors within 12 blocks are slowed and light up.")
						.withStyle(ChatFormatting.DARK_GREEN));
			}
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
		// Somewhere you fit, away from the other side (zombies come in at the edge of town).
		boolean zombie = part.team == ZOMBIE;
		BlockPos at = farthest(() -> spot(zombie), player, o -> players.get(o.getUUID()) != null && players.get(o.getUUID()).team != part.team);
		if (at == null) {
			at = spot(zombie);
		}
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
			player.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, -1, player.getUUID().equals(alpha) ? 1 : 0, false, false));
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
			if (count(SURVIVOR) == 0 && solo) {
				int lasted = roundTicks / 20;
				endRound("THE ZOMBIES GOT YOU", "You lasted " + lasted / 60 + ":" + String.format("%02d", lasted % 60) + " of 4:00. Try again!",
						ChatFormatting.DARK_GREEN);
			} else if (count(SURVIVOR) == 0) {
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
		if (id.equals(alpha)) {
			alpha = null;
		}
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
		ServerPlayer first = alpha == null ? null : PartyGames.player(server, alpha);
		if (first != null && roundTicks >= roarAt && first.isShiftKeyDown() && !first.isSpectator() && players.get(alpha) != null
				&& players.get(alpha).respawnIn <= 0) {
			roar(first);
		}
		if (!lastStand && roundTicks % 20 == 0 && count(SURVIVOR) == 1 && count(ZOMBIE) >= 2) {
			for (ServerPlayer p : online()) {
				Part part = players.get(p.getUUID());
				if (part.team == SURVIVOR && !part.watching && !p.isSpectator()) {
					heroMoment(p, part);
				}
			}
		}
		// Wild zombies wander in to keep survivors moving.
		if (roundTicks % (solo ? 120 : 300) == (solo ? 60 : 150) && level.getDifficulty() != Difficulty.PEACEFUL) {
			long wild = spawned.stream().filter(id -> level.getEntity(id) instanceof Mob m && m.isAlive()).count();
			// On your own they keep coming, more of them as the clock runs down.
			int add = solo ? (int) Math.min(2 + roundTicks / 1200, 12 - wild) : (int) Math.min(count(SURVIVOR), 8 - wild);
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
