package dev.rubymod.game.party;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.entity.SoldierEntity;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * King of the Hill: hold the glowing zone. Every second you're the only one in it, you
 * score a point; first to 75 wins. The zone moves every minute. With fewer than three
 * people, AI soldiers fight for the zone too (they score as "Bots").
 */
public class KingOfTheHill extends PartyGame {
	private static final int HALF = 34;
	private static final int WIN = 75;
	private static final int ROUND_TICKS = 6 * 60 * 20;
	private static final int[][] ZONES = {{0, 0}, {-20, -20}, {20, 20}, {20, -20}, {-20, 20}};
	private int zone;
	private int botScore;

	public KingOfTheHill() {
		super("koth", "King of the Hill", "Stand in the glowing zone to score. It moves every minute! First to 75.", "minecraft:gold_block",
				1, true, true, false);
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(-55000, GROUND + 1, 0);
	}

	private int zoneY(int i) {
		return i == 0 ? 3 : 0;
	}

	@Override
	protected void build(ServerLevel level) {
		flatArena(level, HALF, HALF, 25, Blocks.GRASS_BLOCK.defaultBlockState(), true);
		// The hill in the middle: three steps up to a flat top.
		for (int step = 0; step < 3; step++) {
			int r = 9 - step * 2;
			box(level, -r, 1 + step, -r, r, 1 + step, r, step == 2 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.MOSSY_COBBLESTONE.defaultBlockState());
		}
		BlockState bricks = Blocks.STONE_BRICKS.defaultBlockState();
		BlockState crate = Blocks.BARREL.defaultBlockState();
		// Cover around the field (the same in every quarter).
		for (int k = 0; k < 4; k++) {
			int[][] pieces = {{12, 4, 12, 8}, {26, -6, 26, 6}, {8, 26, 14, 26}};
			for (int[] p : pieces) {
				int[] a = rot(p[0], p[1], k);
				int[] b = rot(p[2], p[3], k);
				box(level, a[0], 1, a[1], b[0], 2, b[1], bricks);
			}
			int[] c = rot(16, 12, k);
			set(level, c[0], 1, c[1], crate);
			int[] c2 = rot(30, 30, k);
			box(level, c2[0], 1, c2[1], c2[0], 3, c2[1], Blocks.OAK_LOG.defaultBlockState());
		}
		for (int i = 0; i < ZONES.length; i++) {
			paintZone(level, i, false);
		}
	}

	private static int[] rot(int x, int z, int k) {
		int[] p = {x, z};
		for (int i = 0; i < k; i++) {
			p = new int[] {-p[1], p[0]};
		}
		return p;
	}

	private void paintZone(ServerLevel level, int i, boolean active) {
		int[] z = ZONES[i];
		BlockState floor = active ? Blocks.GOLD_BLOCK.defaultBlockState() : i == 0 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState();
		box(level, z[0] - 3, zoneY(i), z[1] - 3, z[0] + 3, zoneY(i), z[1] + 3, floor);
	}

	private boolean inZone(Entity e) {
		int[] z = ZONES[zone];
		BlockPos c = center();
		return Math.abs(e.getX() - (c.getX() + z[0] + 0.5)) <= 3.6 && Math.abs(e.getZ() - (c.getZ() + z[1] + 0.5)) <= 3.6
				&& e.getY() >= GROUND + zoneY(zone) && e.getY() < GROUND + zoneY(zone) + 4;
	}

	@Override
	protected String roundStatus() {
		Part top = players.values().stream().max(java.util.Comparator.comparingInt(p -> p.score)).orElse(null);
		String lead = top == null ? "" : "Leader: " + (botScore > top.score ? "Bots " + botScore : top.name + " " + top.score) + "/" + WIN;
		int moveIn = 60 - (roundTicks / 20) % 60;
		return lead + "  •  zone moves in " + moveIn + "s";
	}

	@Override
	protected float barProgress() {
		int top = Math.max(botScore, players.values().stream().mapToInt(p -> p.score).max().orElse(0));
		return top / (float) WIN;
	}

	@Override
	protected void startRound() {
		zone = 0;
		botScore = 0;
		paintZone(level(), 0, true);
		for (ServerPlayer p : online()) {
			spawn(p, players.get(p.getUUID()));
		}
		int wanted = Math.max(0, 3 - activeCount());
		for (int i = 0; i < wanted; i++) {
			spawnZoneBot();
		}
		titleAll("KING OF THE HILL", "Stand in the gold zone to score", ChatFormatting.GOLD);
	}

	private void spawnZoneBot() {
		BlockPos at = edge();
		SoldierEntity bot = SoldierEntity.horde(level(), zoneCenter(), SoldierEntity.RIFLEMAN);
		if (bot == null) {
			return;
		}
		bot.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
		bot.setCustomName(Component.literal("Bot").withStyle(ChatFormatting.RED));
		bot.addTag(tag());
		level().addFreshEntity(bot);
		spawned.add(bot.getUUID());
	}

	private BlockPos zoneCenter() {
		int[] z = ZONES[zone];
		return center().offset(z[0], zoneY(zone), z[1]);
	}

	private BlockPos edge() {
		var random = level().getRandom();
		int a = random.nextInt(4);
		int along = random.nextInt(2 * HALF - 6) - HALF + 3;
		int[] p = rot(HALF - 2, along, a);
		return center().offset(p[0], 0, p[1]);
	}

	@Override
	protected void spawn(ServerPlayer player, Part part) {
		BlockPos at = edge();
		teleport(player, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, player.getRandom().nextFloat() * 360);
		ready(player);
		clearPlayer(player);
		give(player, gun(ModItems.ARR_RIFLE), gun(ModItems.COMBAT_SHOTGUN), gun(ModItems.SERVICE_PISTOL), new ItemStack(ModItems.FRAG_GRENADE, 2),
				new ItemStack(ModItems.HEAVY_AMMO, 64), new ItemStack(ModItems.HEAVY_AMMO, 64), new ItemStack(ModItems.SHOTGUN_SHELL, 32),
				new ItemStack(ModItems.LIGHT_AMMO, 32));
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
		player.getInventory().setSelectedSlot(0);
	}

	@Override
	protected void onEntityDied(LivingEntity entity, DamageSource source) {
		if (phase == Phase.PLAYING) {
			spawnLater = roundTicks + 100;
		}
	}

	private int spawnLater = -1;

	@Override
	protected void tickRound() {
		ServerLevel level = level();
		if (spawnLater > 0 && roundTicks >= spawnLater) {
			spawnLater = -1;
			long bots = spawned.stream().filter(id -> level.getEntity(id) instanceof SoldierEntity s && s.isAlive()).count();
			for (long i = bots; i < Math.max(0, 3 - activeCount()); i++) {
				spawnZoneBot();
			}
		}
		if (roundTicks > 0 && roundTicks % 1200 == 0) {
			paintZone(level, zone, false);
			zone = (zone + 1) % ZONES.length;
			paintZone(level, zone, true);
			titleAll("THE ZONE MOVED", "Find the gold!", ChatFormatting.YELLOW);
		}
		if (roundTicks % 10 == 0) {
			BlockPos z = zoneCenter();
			level.sendParticles(new DustParticleOptions(0xFFD700, 2.0F), z.getX() + 0.5, z.getY() + 2, z.getZ() + 0.5, 12, 2.5, 1.5, 2.5, 0);
		}
		if (roundTicks % 20 != 0) {
			return;
		}
		for (UUID id : spawned) {
			if (level.getEntity(id) instanceof SoldierEntity bot) {
				bot.setObjective(zoneCenter());
			}
		}
		// Score: only one side in the zone.
		Set<String> inside = new HashSet<>();
		ServerPlayer king = null;
		for (ServerPlayer p : online()) {
			Part part = players.get(p.getUUID());
			if (!part.watching && !p.isSpectator() && inZone(p)) {
				inside.add(p.getUUID().toString());
				king = p;
			}
		}
		for (UUID id : spawned) {
			if (level.getEntity(id) instanceof SoldierEntity bot && bot.isAlive() && inZone(bot)) {
				inside.add("bots");
			}
		}
		if (inside.size() == 1) {
			if (inside.contains("bots")) {
				botScore++;
			} else if (king != null) {
				Part part = players.get(king.getUUID());
				part.score++;
				king.sendOverlayMessage(Component.literal("Holding the hill: " + part.score + "/" + WIN).withStyle(ChatFormatting.GOLD));
				if (part.score % 10 == 0) {
					level.playSound(null, king.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.8F, 1.6F);
				}
			}
		} else if (inside.size() > 1) {
			for (ServerPlayer p : online()) {
				if (inZone(p)) {
					p.sendOverlayMessage(Component.literal("Contested! Clear the zone to score").withStyle(ChatFormatting.RED));
				}
			}
		}
		Part top = players.values().stream().max(java.util.Comparator.comparingInt(p -> p.score)).orElse(null);
		if (top != null && top.score >= WIN) {
			endRound(top.name + " WINS!", top.name + " held the hill for " + WIN + " seconds.", ChatFormatting.GOLD);
		} else if (botScore >= WIN) {
			endRound("THE BOTS WIN", "The bots held the hill. Try again!", ChatFormatting.RED);
		} else if (roundTicks >= ROUND_TICKS) {
			String name = top == null || botScore > top.score ? "The bots" : top.name;
			endRound(name.toUpperCase() + " WINS!", "Time's up: most time on the hill wins.", ChatFormatting.GOLD);
		}
	}

	@Override
	public boolean allowsBreak(ServerPlayer player, BlockPos pos) {
		return false;
	}

	@Override
	public boolean onUseBlock(ServerPlayer player, BlockPos pos, ItemStack held, BlockPos placeAt) {
		return phase == Phase.PLAYING && held.getItem() instanceof net.minecraft.world.item.BlockItem;
	}

	@Override
	protected void onDowned(ServerPlayer victim, Part part, Entity killer) {
		part.respawnIn = 60;
	}

	List<String> debug() {
		return List.of("zone " + zone + " bots " + botScore);
	}
}
