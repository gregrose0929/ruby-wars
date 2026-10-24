package dev.rubymod.game.party;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.entity.SoldierEntity;
import dev.rubymod.game.Duels;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Gun Game: every kill swaps your weapon for the next one up the ladder (pistol,
 * shotgun, rifle, sniper, Ruby Blaster, rockets, and finally the sword). The first to
 * get a kill with the sword wins. Get knifed by a sword and you drop back a level.
 * With fewer than three people, AI soldiers join as extra targets.
 */
public class GunGame extends PartyGame {
	private static final Item[] LADDER = {ModItems.SERVICE_PISTOL, ModItems.COMBAT_SHOTGUN, ModItems.ARR_RIFLE, ModItems.LONGSHOT_SNIPER,
			ModItems.RUBY_BLASTER, ModItems.ROCKET_LAUNCHER, ModItems.RUBY_SWORD};
	private static final int ROUND_TICKS = 6 * 60 * 20;
	/** Bots coming back: {tick, index, level}. */
	private final List<int[]> botRespawns = new ArrayList<>();
	/** Each bot's ladder level and letter. */
	private final Map<UUID, int[]> bots = new java.util.HashMap<>();

	public GunGame() {
		super("gungame", "Gun Game", "Every kill gives you the next weapon. First to win with the sword!", "rubymod:arr_rifle",
				1, true, true, false);
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(-40000, GROUND + 1, 0);
	}

	@Override
	protected void build(ServerLevel level) {
		later(() -> Duels.ArenaBuilder.build(level, Duels.Mode.GUNS, center()));
	}

	private static String weaponName(int level) {
		return new ItemStack(LADDER[Math.min(level, LADDER.length - 1)]).getHoverName().getString();
	}


	@Override
	protected float barProgress() {
		int top = players.values().stream().mapToInt(p -> p.level).max().orElse(0);
		return top / (float) (LADDER.length - 1);
	}

	@Override
	protected void startRound() {
		botRespawns.clear();
		bots.clear();
		for (ServerPlayer p : online()) {
			spawn(p, players.get(p.getUUID()));
		}
		int wanted = Math.max(0, 3 - activeCount());
		for (int i = 0; i < wanted; i++) {
			spawnGunBot(i, 0);
		}
		titleAll("GUN GAME", "Every kill: a new weapon. Win with the sword!", ChatFormatting.GOLD);
	}

	private void spawnGunBot(int i, int level) {
		List<ServerPlayer> humans = online();
		ServerPlayer prey = humans.isEmpty() ? null : humans.get(i % humans.size());
		SoldierEntity bot = spawnBot(randomSpawn(level().getRandom()), prey, "Bot " + (char) ('A' + i));
		if (bot != null) {
			bot.setWeapon(LADDER[level]);
			bots.put(bot.getUUID(), new int[] {level, i});
		}
	}

	/** A random open spot in the arena. */
	private BlockPos randomSpawn(RandomSource random) {
		BlockPos c = center();
		ServerLevel level = level();
		for (int tries = 0; tries < 30; tries++) {
			BlockPos p = c.offset(random.nextInt(45) - 22, 0, random.nextInt(45) - 22);
			if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir() && !level.getBlockState(p.below()).isAir()) {
				return p;
			}
		}
		return c.offset(0, 0, random.nextBoolean() ? 26 : -26);
	}

	@Override
	protected void spawn(ServerPlayer player, Part part) {
		BlockPos at = randomSpawn(player.getRandom());
		teleport(player, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, player.getRandom().nextFloat() * 360);
		ready(player);
		kit(player, part);
	}

	private void kit(ServerPlayer player, Part part) {
		clearPlayer(player);
		Item weapon = LADDER[part.level];
		ItemStack main = weapon instanceof dev.rubymod.gun.GunItem ? gun(weapon) : new ItemStack(weapon);
		give(player, main);
		ItemStack ammo = ammoFor(weapon);
		if (!ammo.isEmpty()) {
			give(player, ammo);
		}
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
		player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
		player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
		player.getInventory().setSelectedSlot(0);
		player.inventoryMenu.broadcastChanges();
	}

	private static ItemStack ammoFor(Item weapon) {
		if (weapon == ModItems.SERVICE_PISTOL) {
			return new ItemStack(ModItems.LIGHT_AMMO, 64);
		} else if (weapon == ModItems.COMBAT_SHOTGUN) {
			return new ItemStack(ModItems.SHOTGUN_SHELL, 40);
		} else if (weapon == ModItems.ARR_RIFLE) {
			return new ItemStack(ModItems.HEAVY_AMMO, 64);
		} else if (weapon == ModItems.LONGSHOT_SNIPER) {
			return new ItemStack(ModItems.SNIPER_ROUND, 24);
		} else if (weapon == ModItems.RUBY_BLASTER) {
			return new ItemStack(ModItems.RUBY_CELL, 64);
		} else if (weapon == ModItems.ROCKET_LAUNCHER) {
			return new ItemStack(ModItems.ROCKET, 12);
		}
		return ItemStack.EMPTY;
	}

	private void advance(ServerPlayer killer) {
		Part part = players.get(killer.getUUID());
		if (part == null || phase != Phase.PLAYING) {
			return;
		}
		if (part.level >= LADDER.length - 1) {
			endRound(part.name + " WINS!", part.name + " got a kill with the sword. Kills: " + kills(), ChatFormatting.GOLD, one(killer.getUUID()));
			return;
		}
		part.level++;
		kit(killer, part);
		killer.sendOverlayMessage(Component.literal("Level " + (part.level + 1) + "/" + LADDER.length + ": " + weaponName(part.level))
				.withStyle(ChatFormatting.GREEN));
		killer.level().playSound(null, killer.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5F, 1.4F);
		if (part.level == LADDER.length - 1) {
			tell(Component.literal(part.name + " has the SWORD! One more kill and they win!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		}
	}

	@Override
	protected String roundStatus() {
		var top = players.values().stream().max(Comparator.comparingInt(p -> p.level)).orElse(null);
		int botTop = bots.values().stream().mapToInt(b -> b[0]).max().orElse(-1);
		if (top == null) {
			return activeCount() + " playing";
		}
		if (botTop > top.level) {
			return "Leader: a bot (" + weaponName(botTop) + ", " + (botTop + 1) + "/" + LADDER.length + ")";
		}
		return "Leader: " + top.name + " (" + weaponName(top.level) + ", " + (top.level + 1) + "/" + LADDER.length + ")";
	}

	private String kills() {
		StringBuilder b = new StringBuilder();
		players.values().stream().sorted(Comparator.comparingInt((Part p) -> p.kills).reversed())
				.forEach(p -> b.append(b.isEmpty() ? "" : ", ").append(p.name).append(" ").append(p.kills));
		return b.toString();
	}

	@Override
	protected void onDowned(ServerPlayer victim, Part part, Entity killer) {
		part.respawnIn = 50;
		int[] bot = killer == null ? null : bots.get(killer.getUUID());
		if (bot != null && killer instanceof SoldierEntity soldier) {
			// Bots climb the ladder too, and can win.
			if (bot[0] >= LADDER.length - 1) {
				endRound("BOT " + (char) ('A' + bot[1]) + " WINS!", "A bot got the sword kill. Try again! Kills: " + kills(), ChatFormatting.RED);
				return;
			}
			bot[0]++;
			soldier.setWeapon(LADDER[bot[0]]);
			if (bot[0] == LADDER.length - 1) {
				tell(Component.literal("Bot " + (char) ('A' + bot[1]) + " has the SWORD! Don't let it get you!").withStyle(ChatFormatting.RED));
			}
			return;
		}
		if (killer instanceof ServerPlayer k && k != victim && players.containsKey(k.getUUID())) {
			if (k.getMainHandItem().is(ModItems.RUBY_SWORD) && part.level > 0) {
				part.level--;
				victim.sendSystemMessage(Component.literal("Knifed! You drop back to " + weaponName(part.level) + ".").withStyle(ChatFormatting.RED));
			}
			advance(k);
		}
	}

	@Override
	protected void onEntityDied(LivingEntity entity, DamageSource source) {
		if (source.getEntity() instanceof ServerPlayer k && players.containsKey(k.getUUID())) {
			players.get(k.getUUID()).kills++;
			advance(k);
		}
		int[] bot = bots.remove(entity.getUUID());
		if (phase == Phase.PLAYING && bot != null) {
			botRespawns.add(new int[] {roundTicks + 60, bot[1], bot[0]});
		}
	}

	@Override
	protected void tickRound() {
		for (var it = botRespawns.iterator(); it.hasNext();) {
			int[] r = it.next();
			if (roundTicks >= r[0]) {
				it.remove();
				spawnGunBot(r[1], r[2]);
			}
		}
		// Bots go after whoever's closest.
		if (roundTicks % 100 == 0) {
			List<ServerPlayer> humans = online();
			for (UUID id : spawned) {
				if (level().getEntity(id) instanceof SoldierEntity bot) {
					humans.stream().filter(h -> !h.isSpectator()).min(Comparator.comparingDouble(h -> h.distanceToSqr(bot)))
							.ifPresent(h -> bot.setPrey(h.getUUID()));
				}
			}
		}
		if (roundTicks >= ROUND_TICKS) {
			Map.Entry<UUID, Part> top = players.entrySet().stream().max(Comparator.comparingInt(e -> e.getValue().level)).orElse(null);
			endRound(top == null ? "TIME'S UP" : top.getValue().name + " WINS!", "Time's up: furthest up the ladder wins. Kills: " + kills(),
					ChatFormatting.GOLD, top == null ? List.of() : one(top.getKey()));
		}
	}
}
