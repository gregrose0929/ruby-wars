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
	protected boolean hasBots() {
		return true;
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(-55000, GROUND + 1, 0);
	}

	private int zoneY(int i) {
		return i == 0 ? 3 : 0;
	}

	/** Which map this round is on: they take turns (the hill and its zones are the same on all three; the ground and cover aren't). */
	private int map;
	private int built;
	private static final String[] MAPS = {"Green Hill", "Sandstone Ruins", "Frost Fort"};

	@Override
	protected void build(ServerLevel level) {
		map = built++ % MAPS.length;
		BlockState floor = map == 1 ? Blocks.SAND.defaultBlockState() : map == 2 ? Blocks.SNOW_BLOCK.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
		BlockState slope = map == 1 ? Blocks.SANDSTONE.defaultBlockState() : map == 2 ? Blocks.PACKED_ICE.defaultBlockState() : Blocks.MOSSY_COBBLESTONE.defaultBlockState();
		BlockState bricks = map == 1 ? Blocks.CUT_SANDSTONE.defaultBlockState() : map == 2 ? Blocks.SPRUCE_PLANKS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
		BlockState post = map == 1 ? Blocks.CHISELED_SANDSTONE.defaultBlockState() : map == 2 ? Blocks.SPRUCE_LOG.defaultBlockState() : Blocks.OAK_LOG.defaultBlockState();
		flatArena(level, HALF, HALF, 25, floor, true);
		if (map == 1) {
			// Sand needs something under it.
			box(level, -HALF - 2, -1, -HALF - 2, HALF + 2, -1, HALF + 2, Blocks.SANDSTONE.defaultBlockState());
		}
		// The hill in the middle: three steps up to a flat top.
		for (int step = 0; step < 3; step++) {
			int r = 9 - step * 2;
			box(level, -r, 1 + step, -r, r, 1 + step, r, step == 2 ? Blocks.STONE_BRICKS.defaultBlockState() : slope);
		}
		BlockState crate = Blocks.BARREL.defaultBlockState();
		// Cover around the field (the same in every quarter): walls two high, {x1, z1, x2, z2}.
		int[][] pieces = map == 1 ? new int[][] {{10, 12, 14, 12}, {14, 12, 14, 15}, {26, -4, 26, 3}, {4, 27, 9, 27}, {29, 14, 31, 14}}
				: map == 2 ? new int[][] {{13, -3, 13, 3}, {25, 12, 29, 12}, {5, 15, 5, 19}, {12, 29, 15, 29}}
				: new int[][] {{12, 4, 12, 8}, {26, -6, 26, 6}, {8, 26, 14, 26}};
		for (int k = 0; k < 4; k++) {
			for (int[] p : pieces) {
				int[] a = rot(p[0], p[1], k);
				int[] b = rot(p[2], p[3], k);
				box(level, a[0], 1, a[1], b[0], 2, b[1], bricks);
			}
			int[] c = rot(16, 12, k);
			set(level, c[0], 1, c[1], crate);
			int[] c2 = rot(30, 30, k);
			box(level, c2[0], 1, c2[1], c2[0], 3, c2[1], post);
			if (map == 1) {
				// Broken columns.
				int[] c3 = rot(13, 26, k);
				box(level, c3[0], 1, c3[1], c3[0], 4, c3[1], post);
				int[] c4 = rot(27, 9, k);
				box(level, c4[0], 1, c4[1], c4[0], 2, c4[1], post);
			} else if (map == 2) {
				// A lookout in each corner: a platform three up, on four posts, with a ladder.
				int[] t = rot(27, 27, k);
				for (int dx = -1; dx <= 1; dx += 2) {
					for (int dz = -1; dz <= 1; dz += 2) {
						box(level, t[0] + dx, 1, t[1] + dz, t[0] + dx, 3, t[1] + dz, post);
					}
				}
				box(level, t[0] - 1, 4, t[1] - 1, t[0] + 1, 4, t[1] + 1, bricks);
				box(level, t[0], 1, t[1], t[0], 4, t[1], Blocks.SCAFFOLDING.defaultBlockState());
			}
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

	/** How far the zone reaches from its middle: 7 across at first, 5 for its last 20 seconds, 3 for its last 10. */
	private int zoneR() {
		int t = (roundTicks / 20) % 60;
		return t < 40 ? 3 : t < 50 ? 2 : 1;
	}

	private void paintZone(ServerLevel level, int i, boolean active) {
		int[] z = ZONES[i];
		BlockState plain = i == 0 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState();
		box(level, z[0] - 3, zoneY(i), z[1] - 3, z[0] + 3, zoneY(i), z[1] + 3, plain);
		if (active) {
			int r = zoneR();
			box(level, z[0] - r, zoneY(i), z[1] - r, z[0] + r, zoneY(i), z[1] + r, Blocks.GOLD_BLOCK.defaultBlockState());
		}
	}

	/** When the next bolt lands on the zone (round ticks; -1: none on its way). */
	private int strikeAt = -1;

	private void lightning(ServerLevel level) {
		BlockPos z = zoneCenter();
		var bolt = net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
		if (bolt != null) {
			bolt.snapTo(z.getX() + 0.5, z.getY() + 1, z.getZ() + 0.5, 0, 0);
			bolt.setVisualOnly(true);
			level.addFreshEntity(bolt);
		}
		for (ServerPlayer p : online()) {
			if (!p.isSpectator() && inZone(p)) {
				p.hurtServer(level, level.damageSources().lightningBolt(), 9.0F);
				p.setDeltaMovement(p.getDeltaMovement().add((p.getX() - z.getX() - 0.5) * 0.3, 0.7, (p.getZ() - z.getZ() - 0.5) * 0.3));
				p.syncVelocity = true;
				p.sendOverlayMessage(Component.literal("⚡ Struck by lightning!").withStyle(ChatFormatting.YELLOW));
			}
		}
		for (UUID id : spawned) {
			if (level.getEntity(id) instanceof SoldierEntity bot && bot.isAlive() && inZone(bot)) {
				bot.hurtServer(level, level.damageSources().lightningBolt(), 9.0F);
			}
		}
	}

	private boolean inZone(Entity e) {
		int[] z = ZONES[zone];
		BlockPos c = center();
		double r = zoneR() + 0.6;
		return Math.abs(e.getX() - (c.getX() + z[0] + 0.5)) <= r && Math.abs(e.getZ() - (c.getZ() + z[1] + 0.5)) <= r
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
		strikeAt = -1;
		paintZone(level(), 0, true);
		for (ServerPlayer p : online()) {
			spawn(p, players.get(p.getUUID()));
		}
		int wanted = useBots() ? Math.max(0, 3 - activeCount()) : 0;
		for (int i = 0; i < wanted; i++) {
			spawnZoneBot();
		}
		titleAll("KING OF THE HILL", "Stand in the gold zone to score", ChatFormatting.GOLD);
		tell(Component.literal("Map: " + MAPS[map]).withStyle(ChatFormatting.AQUA));
	}

	private void spawnZoneBot() {
		BlockPos at = edge();
		SoldierEntity bot = SoldierEntity.horde(level(), zoneCenter(), SoldierEntity.RIFLEMAN);
		if (bot == null) {
			return;
		}
		bot.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
		bot.setCustomName(Component.literal("Bot").withStyle(ChatFormatting.RED));
		bot.setSmart();
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
		// On the edge, at the spot farthest from everyone else.
		BlockPos at = farthest(this::edge, player, o -> true);
		if (at == null) {
			at = edge();
		}
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
			for (long i = bots; useBots() && i < Math.max(0, 3 - activeCount()); i++) {
				spawnZoneBot();
			}
		}
		if (roundTicks > 0 && roundTicks % 1200 == 0) {
			paintZone(level, zone, false);
			zone = (zone + 1) % ZONES.length;
			paintZone(level, zone, true);
			titleAll("THE ZONE MOVED", "Find the gold!", ChatFormatting.YELLOW);
		}
		// The zone shrinks before it moves: 20 seconds to go, then 10.
		if (roundTicks % 1200 == 800 || roundTicks % 1200 == 1000) {
			paintZone(level, zone, true);
			for (ServerPlayer p : online()) {
				p.sendOverlayMessage(Component.literal("The hill is shrinking!").withStyle(ChatFormatting.YELLOW));
			}
			level.playSound(null, zoneCenter(), SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 2.0F, 0.6F);
		}
		// Lightning: every 25 seconds a bolt hits the zone, two and a half seconds after the warning.
		if (roundTicks % 500 == 400) {
			strikeAt = roundTicks + 50;
			BlockPos z = zoneCenter();
			level.playSound(null, z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 1.5F, 1.6F);
			for (ServerPlayer p : online()) {
				if (inZone(p)) {
					dev.rubymod.game.Alerts.title(p, "", "⚡ Lightning is about to hit the hill: step off!", ChatFormatting.YELLOW);
				}
			}
		}
		if (strikeAt > 0 && roundTicks < strikeAt && roundTicks % 4 == 0) {
			BlockPos z = zoneCenter();
			level.sendParticles(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, z.getX() + 0.5, z.getY() + 4, z.getZ() + 0.5, 30, 2, 3, 2, 0.1);
		}
		if (strikeAt > 0 && roundTicks >= strikeAt) {
			strikeAt = -1;
			lightning(level);
		}
		if (roundTicks % 10 == 0) {
			BlockPos z = zoneCenter();
			double spread = zoneR() * 0.8;
			level.sendParticles(new DustParticleOptions(0xFFD700, 2.0F), z.getX() + 0.5, z.getY() + 2, z.getZ() + 0.5, 12, spread, 1.5, spread, 0);
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
			endRound(top.name + " WINS!", top.name + " held the hill for " + WIN + " seconds.", ChatFormatting.GOLD, one(idOf(top)));
		} else if (botScore >= WIN) {
			endRound("THE BOTS WIN", "The bots held the hill. Try again!", ChatFormatting.RED);
		} else if (roundTicks >= ROUND_TICKS) {
			String name = top == null || botScore > top.score ? "The bots" : top.name;
			endRound(name.toUpperCase() + " WINS!", "Time's up: most time on the hill wins.", ChatFormatting.GOLD,
					top == null || botScore > top.score ? List.of() : one(idOf(top)));
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
