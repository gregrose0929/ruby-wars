package dev.rubymod.game.party;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import dev.rubymod.RubyMod;
import dev.rubymod.entity.SoldierEntity;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Ruby Rush: a quick battle royale on Rush Island (a quarter-size copy of the Ruby Wars
 * Map in its own world). Everyone floats down from the sky with nothing, loots crates
 * for guns, armor and supplies, and the safe zone shrinks until one is left. No
 * respawns. With fewer than four people, AI soldiers drop in too.
 */
public class RubyRush extends PartyGame {
	public static final ResourceKey<Level> RUSH = ResourceKey.create(Registries.DIMENSION, RubyMod.id("rush"));
	private static final int CRATES = 45;
	private static final int ISLAND = 170;
	private static final int GRACE_TICKS = 60 * 20;
	private static final int SHRINK_TICKS = 7 * 60 * 20;
	private static final int ROUND_TICKS = 10 * 60 * 20;
	private final List<BlockPos> crates = new ArrayList<>();
	private double zoneX;
	private double zoneZ;
	private int botsLeft;

	public RubyRush() {
		super("rush", "Ruby Rush", "A quick battle royale: drop in, loot crates, last one standing wins!", "rubymod:ruby", 1, true, false, false);
	}

	@Override
	protected ServerLevel level() {
		return server.getLevel(RUSH);
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(0, 64, 0);
	}

	@Override
	protected int reach() {
		return ISLAND + 40;
	}

	private BlockPos lobbySpot() {
		return new BlockPos(0, 160, 0);
	}

	@Override
	protected void build(ServerLevel level) {
		// A glass platform in the sky to wait on.
		BlockPos l = lobbySpot().below();
		for (int dx = -5; dx <= 5; dx++) {
			for (int dz = -5; dz <= 5; dz++) {
				level.setBlock(l.offset(dx, 0, dz), Blocks.GLASS.defaultBlockState(), 3);
				if (Math.abs(dx) == 5 || Math.abs(dz) == 5) {
					level.setBlock(l.offset(dx, 1, dz), Blocks.BARRIER.defaultBlockState(), 3);
					level.setBlock(l.offset(dx, 2, dz), Blocks.BARRIER.defaultBlockState(), 3);
				}
			}
		}
	}

	@Override
	protected void toLobby(ServerPlayer player) {
		BlockPos l = lobbySpot();
		teleport(player, l.getX() + 0.5 + player.getRandom().nextInt(7) - 3, l.getY(), l.getZ() + 0.5 + player.getRandom().nextInt(7) - 3, 0);
		player.setGameMode(GameType.ADVENTURE);
	}

	private double zoneRadius() {
		if (roundTicks < GRACE_TICKS) {
			return ISLAND + 30;
		}
		double t = Math.min(1.0, (roundTicks - GRACE_TICKS) / (double) SHRINK_TICKS);
		return (ISLAND + 30) + (12 - (ISLAND + 30)) * t;
	}

	private long alive() {
		long bots = spawned.stream().filter(id -> level().getEntity(id) instanceof SoldierEntity s && s.isAlive()).count();
		return players.values().stream().filter(p -> !p.watching).count() + bots;
	}

	@Override
	protected String roundStatus() {
		String zone = roundTicks < GRACE_TICKS ? "zone closes in " + (GRACE_TICKS - roundTicks) / 20 + "s" : "zone " + (int) zoneRadius() + "m";
		return alive() + " left  •  " + zone;
	}

	@Override
	protected float barProgress() {
		return (float) (zoneRadius() / (ISLAND + 30));
	}

	/** A random dry spot on the island (standing height), or null. */
	private BlockPos landSpot(RandomSource random, double minR, double maxR) {
		ServerLevel level = level();
		for (int tries = 0; tries < 40; tries++) {
			double a = random.nextDouble() * Math.PI * 2;
			double r = minR + random.nextDouble() * (maxR - minR);
			int x = (int) Math.round(Math.cos(a) * r);
			int z = (int) Math.round(Math.sin(a) * r);
			level.getChunk(x >> 4, z >> 4);
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos p = new BlockPos(x, y, z);
			if (y > 63 && level.getFluidState(p.below()).isEmpty() && level.getBlockState(p).isAir()) {
				return p;
			}
		}
		return null;
	}

	@Override
	protected void startRound() {
		ServerLevel level = level();
		RandomSource random = level.getRandom();
		// Fresh crates.
		for (BlockPos old : crates) {
			if (level.getBlockState(old).is(Blocks.CHEST)) {
				if (level.getBlockEntity(old) instanceof ChestBlockEntity chest) {
					chest.clearContent();
				}
				level.setBlock(old, Blocks.AIR.defaultBlockState(), 3);
			}
		}
		crates.clear();
		for (int i = 0; i < CRATES; i++) {
			BlockPos p = landSpot(random, 0, ISLAND - 10);
			if (p == null) {
				continue;
			}
			level.setBlock(p, Blocks.CHEST.defaultBlockState(), 3);
			if (level.getBlockEntity(p) instanceof ChestBlockEntity chest) {
				fill(chest, random);
			}
			crates.add(p);
		}
		zoneX = random.nextInt(61) - 30;
		zoneZ = random.nextInt(61) - 30;
		// Remove the waiting platform and drop everyone in.
		BlockPos l = lobbySpot().below();
		for (int dx = -5; dx <= 5; dx++) {
			for (int dz = -5; dz <= 5; dz++) {
				for (int dy = 0; dy <= 2; dy++) {
					level.setBlock(l.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
				}
			}
		}
		for (ServerPlayer p : online()) {
			spawn(p, players.get(p.getUUID()));
		}
		// AI rushers (to make four) land 15 seconds in, once everyone's on the ground.
		botsLeft = Math.max(0, 4 - activeCount());
		titleAll("RUBY RUSH", "Loot the chests. Stay in the zone. Be the last one!", ChatFormatting.RED);
	}

	private static void fill(ChestBlockEntity chest, RandomSource random) {
		List<ItemStack> loot = new ArrayList<>();
		int roll = random.nextInt(100);
		Item gun = roll < 25 ? ModItems.SERVICE_PISTOL : roll < 45 ? ModItems.COMBAT_SHOTGUN : roll < 67 ? ModItems.ARR_RIFLE
				: roll < 80 ? ModItems.LONGSHOT_SNIPER : roll < 92 ? ModItems.RUBY_BLASTER : ModItems.ROCKET_LAUNCHER;
		loot.add(gun(gun));
		loot.add(gun == ModItems.SERVICE_PISTOL ? new ItemStack(ModItems.LIGHT_AMMO, 32) : gun == ModItems.COMBAT_SHOTGUN ? new ItemStack(ModItems.SHOTGUN_SHELL, 20)
				: gun == ModItems.ARR_RIFLE ? new ItemStack(ModItems.HEAVY_AMMO, 48) : gun == ModItems.LONGSHOT_SNIPER ? new ItemStack(ModItems.SNIPER_ROUND, 12)
						: gun == ModItems.RUBY_BLASTER ? new ItemStack(ModItems.RUBY_CELL, 40) : new ItemStack(ModItems.ROCKET, 5));
		Item[] extras = {Items.GOLDEN_APPLE, ModItems.FRAG_GRENADE, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
				ModItems.LIGHT_AMMO, ModItems.HEAVY_AMMO, ModItems.SHOTGUN_SHELL, ModItems.RUBY_CHESTPLATE, ModItems.SMOKE_GRENADE, Items.COOKED_BEEF};
		int count = 1 + random.nextInt(3);
		for (int i = 0; i < count; i++) {
			Item extra = extras[random.nextInt(extras.length)];
			loot.add(new ItemStack(extra, extra == ModItems.LIGHT_AMMO || extra == ModItems.HEAVY_AMMO ? 32 : extra == ModItems.SHOTGUN_SHELL ? 16
					: extra == Items.COOKED_BEEF ? 4 : 1));
		}
		for (int i = 0; i < loot.size(); i++) {
			chest.setItem(random.nextInt(27), loot.get(i));
		}
	}

	@Override
	protected void spawn(ServerPlayer player, Part part) {
		RandomSource random = player.getRandom();
		double a = random.nextDouble() * Math.PI * 2;
		double r = 40 + random.nextDouble() * (ISLAND - 60);
		ready(player);
		clearPlayer(player);
		player.setGameMode(GameType.ADVENTURE);
		int x = (int) Math.round(Math.cos(a) * r);
		int z = (int) Math.round(Math.sin(a) * r);
		level().getChunk(x >> 4, z >> 4);
		int ground = level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		teleport(player, x + 0.5, Math.max(ground, 62) + 32, z + 0.5, random.nextFloat() * 360);
		player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 20, 0, false, false));
		player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, -1, 0, false, false));
		player.sendSystemMessage(Component.literal("Float down, open chests for gear, and stay inside the zone (the red wall). No respawns!")
				.withStyle(ChatFormatting.GOLD));
	}

	@Override
	protected void onDowned(ServerPlayer victim, Part part, Entity killer) {
		part.watching = true;
		// Your things fall where you did.
		for (int i = 0; i < victim.getInventory().getContainerSize(); i++) {
			ItemStack stack = victim.getInventory().getItem(i);
			if (!stack.isEmpty()) {
				ItemEntity drop = new ItemEntity(level(), victim.getX(), victim.getY() + 0.5, victim.getZ(), stack.copy());
				level().addFreshEntity(drop);
			}
		}
		clearPlayer(victim);
		victim.teleportTo(victim.getX(), Math.max(victim.getY(), 100), victim.getZ());
		long left = alive();
		tell(Component.literal(part.name + " is out! " + left + " left.").withStyle(ChatFormatting.RED));
		dev.rubymod.game.Alerts.title(victim, "ELIMINATED", "You placed #" + (left + 1) + ". Watch the rest!", ChatFormatting.RED);
		checkWinner();
	}

	@Override
	protected void onEntityDied(LivingEntity entity, DamageSource source) {
		if (source.getEntity() instanceof ServerPlayer k && players.containsKey(k.getUUID())) {
			players.get(k.getUUID()).kills++;
		}
		tell(Component.literal(entity.getName().getString() + " is out! " + alive() + " left.").withStyle(ChatFormatting.RED));
		checkWinner();
	}

	@Override
	protected void onLeft(UUID id, Part part) {
		checkWinner();
	}

	private void checkWinner() {
		if (phase != Phase.PLAYING) {
			return;
		}
		long humans = players.values().stream().filter(p -> !p.watching).count();
		long left = alive();
		if (left > 1 && humans > 0) {
			return;
		}
		if (humans == 1) {
			Part winner = players.values().stream().filter(p -> !p.watching).findFirst().get();
			endRound(winner.name.toUpperCase() + " WINS!", "Last one standing with " + winner.kills + (winner.kills == 1 ? " kill" : " kills") + ".",
					ChatFormatting.GOLD, one(idOf(winner)));
		} else {
			endRound("THE BOTS WIN", "A bot was the last one standing. Try again!", ChatFormatting.RED);
		}
	}

	@Override
	protected void tickRound() {
		ServerLevel level = level();
		if (roundTicks == 15 * 20 && botsLeft > 0) {
			List<ServerPlayer> humans = online();
			for (int i = 0; i < botsLeft; i++) {
				BlockPos at = landSpot(level.getRandom(), 30, ISLAND - 20);
				if (at != null) {
					SoldierEntity bot = spawnBot(at, humans.isEmpty() ? null : humans.get(i % humans.size()), "Rusher " + (char) ('A' + i));
					if (bot != null) {
						net.minecraft.world.item.Item[] guns = {ModItems.ARR_RIFLE, ModItems.COMBAT_SHOTGUN, ModItems.SERVICE_PISTOL, ModItems.LONGSHOT_SNIPER};
						bot.setWeapon(guns[level.getRandom().nextInt(guns.length)]);
					}
				}
			}
			tell(Component.literal(botsLeft + " AI rushers have landed!").withStyle(ChatFormatting.YELLOW));
		}
		if (roundTicks == GRACE_TICKS) {
			titleAll("THE ZONE IS CLOSING", "Stay inside the red wall", ChatFormatting.RED);
		}
		double radius = zoneRadius();
		if (roundTicks % 20 == 0) {
			float damage = roundTicks > GRACE_TICKS + SHRINK_TICKS / 2 ? 2.0F : 1.0F;
			List<LivingEntity> fighters = new ArrayList<>();
			for (ServerPlayer p : online()) {
				if (!players.get(p.getUUID()).watching && !p.isSpectator()) {
					fighters.add(p);
				}
			}
			for (UUID id : spawned) {
				if (level.getEntity(id) instanceof SoldierEntity bot && bot.isAlive()) {
					fighters.add(bot);
				}
			}
			for (LivingEntity e : fighters) {
				double d = Math.hypot(e.getX() - zoneX, e.getZ() - zoneZ);
				if (d > radius) {
					e.hurtServer(level, level.damageSources().outOfBorder(), damage);
					if (e instanceof ServerPlayer p) {
						p.sendOverlayMessage(Component.literal("⚠ Outside the zone! Get back in!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
					}
				} else if (e instanceof SoldierEntity bot && d > radius - 15) {
					bot.setObjective(BlockPos.containing(zoneX, e.getY(), zoneZ));
				}
			}
			// Bots go after whoever's closest.
			if (roundTicks % 100 == 0) {
				List<ServerPlayer> humans = online().stream().filter(p -> !players.get(p.getUUID()).watching && !p.isSpectator()).toList();
				for (UUID id : spawned) {
					if (level.getEntity(id) instanceof SoldierEntity bot) {
						humans.stream().min(Comparator.comparingDouble(h -> h.distanceToSqr(bot))).ifPresent(h -> bot.setPrey(h.getUUID()));
					}
				}
			}
		}
		// The zone wall, drawn near each player.
		if (roundTicks % 5 == 0 && roundTicks >= GRACE_TICKS - 200) {
			for (ServerPlayer p : online()) {
				double dx = p.getX() - zoneX;
				double dz = p.getZ() - zoneZ;
				double angle = Math.atan2(dz, dx);
				if (Math.abs(Math.hypot(dx, dz) - radius) > 40) {
					continue;
				}
				for (int i = -12; i <= 12; i++) {
					double a = angle + i * 1.5 / Math.max(10, radius);
					double x = zoneX + Math.cos(a) * radius;
					double z = zoneZ + Math.sin(a) * radius;
					for (int h = -2; h <= 6; h += 2) {
						level.sendParticles(p, new DustParticleOptions(0xFF2020, 2.0F), true, false, x, p.getY() + h, z, 1, 0.1, 0.4, 0.1, 0);
					}
				}
			}
		}
		if (roundTicks >= ROUND_TICKS) {
			Part best = players.values().stream().filter(p -> !p.watching).max(Comparator.comparingInt(p -> p.kills)).orElse(null);
			endRound(best == null ? "TIME'S UP" : best.name.toUpperCase() + " WINS!", "Time's up: the survivor with the most kills wins.", ChatFormatting.GOLD,
					best == null ? List.of() : one(idOf(best)));
		}
	}

	@Override
	public boolean allowsBreak(ServerPlayer player, BlockPos pos) {
		return false;
	}

	@Override
	public void join(ServerPlayer player) {
		super.join(player);
		if (phase == Phase.LOBBY) {
			player.sendSystemMessage(Component.literal("You're on the waiting platform above Rush Island. The round starts soon!").withStyle(ChatFormatting.GRAY));
		}
	}
}
