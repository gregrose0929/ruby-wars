package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.game.Alerts;
import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.mixin.InteractionAccessor;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Mini golf: four themed holes (the Windmill, the Castle, the Volcano, Pirate Cove). Gus hands you a putter: right-click
 * once to start the power bar swinging, right-click again to hit (the ball goes where you're looking). Lava and water
 * send the ball back to the tee. Your scorecard comes up at the end.
 */
final class MiniGolf {
	private MiniGolf() {
	}

	private static final String PUTTER = "Putter";
	private static final String BALL_TAG = "rubymod_golf_ball";
	private static final int MAX_STROKES = 10;
	private static final int[] PAR = {2, 3, 4, 3};
	private static final String[] NAMES = {"The Windmill", "The Castle", "The Volcano", "Pirate Cove"};

	/** Each hole: its lane (x1, z1, x2, z2: the playing surface), tee and cup. */
	private record Hole(int x1, int z1, int x2, int z2, BlockPos tee, BlockPos cup) {
	}

	private static final List<Hole> HOLES = new ArrayList<>();

	private static final class Round {
		int hole;
		final int[] strokes = new int[4];
		HubGames.Ball ball;
		int charging = -1;
		int next = -1;
	}

	private static final Map<UUID, Round> ROUNDS = new HashMap<>();
	private static int tick;

	// ----- Building -----

	static void build(ServerLevel level) {
		ROUNDS.clear();
		HOLES.clear();
		int cx = -44;
		int cz = 44;
		HubPark.island(level, cx, cz, 16, FLOOR);
		HubPark.label(level, cx + 0.5, FLOOR + 10, cz + 0.5, "MINI GOLF", ChatFormatting.GREEN, 2.6F);
		lane(level, -54, 33, -35, 35, Blocks.MOSS_BLOCK.defaultBlockState());
		lane(level, -57, 38, -31, 40, Blocks.MOSS_BLOCK.defaultBlockState());
		lane(level, -58, 42, -30, 48, Blocks.MOSS_BLOCK.defaultBlockState());
		lane(level, -55, 50, -33, 53, Blocks.MOSS_BLOCK.defaultBlockState());
		windmill(level);
		castle(level);
		volcano(level);
		cove(level);
		for (int h = 0; h < HOLES.size(); h++) {
			Hole hole = HOLES.get(h);
			HubPark.set(level, hole.tee().getX(), FLOOR, hole.tee().getZ(), Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState());
			HubPark.set(level, hole.cup().getX(), FLOOR, hole.cup().getZ(), Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState());
			HubPark.model(level, Vec3.atBottomCenterOf(hole.cup().above()), 0, "golf_flag", 1.0F, 0.0F);
			HubPark.label(level, hole.tee().getX() + 0.5, FLOOR + 2.4, hole.tee().getZ() + 0.5, "HOLE " + (h + 1) + ": " + NAMES[h].toUpperCase() + "\npar " + PAR[h],
					ChatFormatting.GREEN, 0.7F);
		}
		HubPark.travelPad(level, -36, FLOOR, 56, "golf");
		HubHosts.add(level, new Vec3(-43.5, FLOOR + 1, 57.5), 180, VillagerProfession.LIBRARIAN, VillagerType.JUNGLE,
				new HubHosts.Host("golf", "Gus", "Mini Golf", 0x60FF80, "minecraft:slime_ball",
						"Four holes: the Windmill, the Castle, the Volcano and Pirate Cove. Right-click with your putter to start the power bar, "
								+ "then right-click again to hit. The ball goes where you look. Watch out for lava and water!",
						"Mini Golf: right-click Gus for a putter!", HubHosts.play("Play a round (4 holes)"), (p, key) -> start(p)));
		HubPark.lamp(level, -58, FLOOR + 1, 36);
		HubPark.lamp(level, -30, FLOOR + 1, 37);
	}

	private static void lane(ServerLevel level, int x1, int z1, int x2, int z2, BlockState green) {
		for (int x = x1 - 1; x <= x2 + 1; x++) {
			for (int z = z1 - 1; z <= z2 + 1; z++) {
				boolean edge = x == x1 - 1 || x == x2 + 1 || z == z1 - 1 || z == z2 + 1;
				HubPark.set(level, x, FLOOR, z, edge ? Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState() : green);
				HubPark.set(level, x, FLOOR + 1, z, edge ? Blocks.SPRUCE_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
			}
		}
		int mid = (z1 + z2) / 2;
		HOLES.add(new Hole(x1, z1, x2, z2, new BlockPos(x1, FLOOR, mid), new BlockPos(x2 - 1, FLOOR, mid)));
	}

	// The Windmill: a white mill over the lane; its sails sweep across the tunnel and block it now and then.
	private static final Vec3 SAILS = new Vec3(-46.75, FLOOR + 4.0, 34.5);
	private static final List<UUID> SAIL = new ArrayList<>();
	private static double sailAngle;

	private static void windmill(ServerLevel level) {
		SAIL.clear();
		for (int x = -46; x <= -44; x++) {
			for (int z = 32; z <= 36; z++) {
				for (int y = FLOOR + 1; y <= FLOOR + 5; y++) {
					boolean tunnel = y == FLOOR + 1 && z >= 33 && z <= 35;
					HubPark.set(level, x, y, z, tunnel ? Blocks.AIR.defaultBlockState() : Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState());
				}
			}
		}
		for (int i = 0; i < 3; i++) {
			HubPark.box(level, -46 + (i == 0 ? 0 : 0), FLOOR + 6 + i, 32 + i, -44, FLOOR + 6 + i, 36 - i, Blocks.WOOL.pick(DyeColor.RED).defaultBlockState());
		}
		HubPark.set(level, -47, FLOOR + 3, 34, Blocks.CONCRETE.pick(DyeColor.BLUE).defaultBlockState());
		for (int i = 0; i < 4; i++) {
			Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (d == null) {
				continue;
			}
			d.snapTo(SAILS.x, SAILS.y, SAILS.z, 0, 0);
			((BlockDisplayAccessor) d).rubymod$setBlockState(Blocks.SPRUCE_PLANKS.defaultBlockState());
			((DisplayAccessor) d).rubymod$setViewRange(5.0F);
			level.addFreshEntity(d);
			HubPark.keep(d);
			SAIL.add(d.getUUID());
		}
		sails(level, 0);
	}

	private static void sails(ServerLevel level, int duration) {
		for (int i = 0; i < SAIL.size(); i++) {
			if (level.getEntity(SAIL.get(i)) instanceof Display d) {
				Quaternionf q = new Quaternionf().rotateX((float) (sailAngle + i * Math.PI / 2));
				((DisplayAccessor) d).rubymod$setTransformationInterpolationDelay(0);
				((DisplayAccessor) d).rubymod$setTransformationInterpolationDuration(duration);
				((DisplayAccessor) d).rubymod$setTransformation(new Transformation(q.transform(new Vector3f(-0.06F, 0.2F, -0.45F)), q, new Vector3f(0.12F, 2.8F, 0.9F),
						new Quaternionf()));
			}
		}
	}

	private static boolean sailDown() {
		for (int i = 0; i < 4; i++) {
			if (Math.cos(sailAngle + i * Math.PI / 2) < -0.96) {
				return true;
			}
		}
		return false;
	}

	// The Castle: a gatehouse across the lane with a portcullis that drops every few seconds.
	private static void castle(ServerLevel level) {
		BlockState stone = Blocks.STONE_BRICKS.defaultBlockState();
		for (int z = 37; z <= 41; z++) {
			boolean lane = z >= 38 && z <= 40;
			for (int y = FLOOR + 1; y <= FLOOR + 5; y++) {
				if (lane && y <= FLOOR + 1) {
					continue;
				}
				HubPark.box(level, -45, y, z, -43, y, z, stone);
			}
			if (z % 2 == 0) {
				HubPark.set(level, -45, FLOOR + 6, z, stone);
				HubPark.set(level, -43, FLOOR + 6, z, stone);
			}
		}
		for (int z : new int[] {37, 41}) {
			HubPark.box(level, -45, FLOOR + 6, z, -43, FLOOR + 7, z, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
			HubPark.set(level, -44, FLOOR + 8, z, Blocks.OAK_FENCE.defaultBlockState());
			HubPark.piece(level, new Vec3(-43.65, FLOOR + 8.7, z + 0.5), Blocks.WOOL.pick(DyeColor.BLUE).defaultBlockState(), new Vector3f(0.7F, 0.45F, 0.05F),
					new Quaternionf());
		}
	}

	private static boolean portcullisDown() {
		return tick % 80 >= 50;
	}

	// The Volcano: a smoking cone in the middle of the lane, and lava pools to roll round.
	private static void volcano(ServerLevel level) {
		int vx = -44;
		int vz = 45;
		// Two blocks clear each side of it (z 42-43 and 47-48) to putt round.
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				int r = Math.abs(dx) + Math.abs(dz);
				int top = FLOOR + 4 - r;
				if (r == 0) {
					top = FLOOR + 3;
				}
				HubPark.box(level, vx + dx, FLOOR + 1, vz + dz, vx + dx, top, vz + dz, (dx + dz) % 2 == 0 ? Blocks.BLACKSTONE.defaultBlockState()
						: Blocks.BASALT.defaultBlockState());
			}
		}
		HubPark.set(level, vx, FLOOR + 3, vz, Blocks.MAGMA_BLOCK.defaultBlockState());
		HubPark.set(level, vx, FLOOR + 4, vz, Blocks.AIR.defaultBlockState());
		for (int[] l : new int[][] {{-51, 43}, {-51, 47}, {-38, 45}, {-37, 44}, {-37, 46}}) {
			HubPark.set(level, l[0], FLOOR, l[1], Blocks.MAGMA_BLOCK.defaultBlockState());
		}
	}

	// Pirate Cove: water to roll round, barrels in the way, treasure by the hole.
	private static void cove(ServerLevel level) {
		HubPark.box(level, -49, FLOOR, 51, -47, FLOOR, 52, Blocks.WATER.defaultBlockState());
		HubPark.box(level, -49, FLOOR - 1, 51, -47, FLOOR - 1, 52, Blocks.SAND.defaultBlockState());
		HubPark.box(level, -40, FLOOR, 52, -39, FLOOR, 53, Blocks.WATER.defaultBlockState());
		HubPark.box(level, -40, FLOOR - 1, 52, -39, FLOOR - 1, 53, Blocks.SAND.defaultBlockState());
		HubPark.set(level, -44, FLOOR + 1, 50, Blocks.BARREL.defaultBlockState());
		HubPark.set(level, -44, FLOOR + 1, 53, Blocks.BARREL.defaultBlockState());
		HubPark.set(level, -33, FLOOR + 1, 54, Blocks.CHEST.defaultBlockState());
		HubPark.set(level, -55, FLOOR + 2, 54, Blocks.OAK_FENCE.defaultBlockState());
		HubPark.piece(level, new Vec3(-54.65, FLOOR + 2.9, 54.5), Blocks.WOOL.pick(DyeColor.BLACK).defaultBlockState(), new Vector3f(0.7F, 0.5F, 0.05F),
				new Quaternionf());
	}

	// ----- Playing -----

	private static void start(ServerPlayer p) {
		Round old = ROUNDS.remove(p.getUUID());
		if (old != null && old.ball != null) {
			old.ball.remove((ServerLevel) p.level());
		}
		Round r = new Round();
		ROUNDS.put(p.getUUID(), r);
		ItemStack putter = HubGames.toy(Items.STICK, 1, PUTTER, ChatFormatting.GREEN);
		putter.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, dev.rubymod.RubyMod.id("park_putter"));
		HubGames.give(p, putter);
		HubGames.hold(p, PUTTER);
		toHole(p, r, 0);
	}

	private static void toHole(ServerPlayer p, Round r, int h) {
		ServerLevel level = (ServerLevel) p.level();
		r.hole = h;
		r.charging = -1;
		if (r.ball != null) {
			r.ball.remove(level);
		}
		Hole hole = HOLES.get(h);
		r.ball = spawnBall(level, ballHome(hole));
		p.teleportTo(hole.tee().getX() - 0.2, FLOOR + 1, hole.tee().getZ() + 0.5);
		p.setYRot(-90);
		p.setXRot(30);
		Alerts.title(p, "Hole " + (h + 1), NAMES[h] + "  •  par " + PAR[h], ChatFormatting.GREEN);
		p.sendOverlayMessage(Component.literal("Look where you want it to go, right-click to start the power bar, right-click again to hit!")
				.withStyle(ChatFormatting.GREEN));
	}

	private static Vec3 ballHome(Hole hole) {
		return Vec3.atBottomCenterOf(hole.tee().above()).add(1.3, 0.02, 0);
	}

	private static HubGames.Ball spawnBall(ServerLevel level, Vec3 at) {
		HubGames.Ball b = new HubGames.Ball();
		Display.ItemDisplay d = HubPark.tempModel(level, at, 0, "golf_ball", 0.42F, 0.21F);
		if (d != null) {
			((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(1);
			b.display = d.getUUID();
		}
		Interaction hit = EntityTypes.INTERACTION.create(level, EntitySpawnReason.EVENT);
		if (hit != null) {
			((InteractionAccessor) hit).rubymod$setWidth(0.6F);
			((InteractionAccessor) hit).rubymod$setHeight(0.5F);
			hit.snapTo(at.x, at.y, at.z, 0, 0);
			level.addFreshEntity(hit);
			hit.addTag(HubRides.TEMP);
			hit.addTag(BALL_TAG);
			HubPark.live(hit);
			b.hitbox = hit.getUUID();
		}
		b.pos = at;
		return b;
	}

	/** Right-click with the putter (or punch your ball): start the power bar, or hit. */
	static boolean putt(ServerPlayer p) {
		Round r = ROUNDS.get(p.getUUID());
		if (r == null || r.ball == null) {
			p.sendOverlayMessage(Component.literal("Talk to Gus at Mini Golf to start a round!").withStyle(ChatFormatting.GREEN));
			return true;
		}
		if (r.next >= 0) {
			return true;
		}
		if (r.ball.vel.lengthSqr() > 0.0004) {
			return true;
		}
		if (p.position().distanceTo(r.ball.pos) > 4.5) {
			p.sendOverlayMessage(Component.literal("Walk up to your ball first!").withStyle(ChatFormatting.YELLOW));
			return true;
		}
		if (r.charging < 0) {
			r.charging = tick;
			p.level().playSound(null, p.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.5F, 1.4F);
			return true;
		}
		double power = power(tick - r.charging);
		r.charging = -1;
		Vec3 dir = new Vec3(p.getLookAngle().x, 0, p.getLookAngle().z);
		if (dir.lengthSqr() < 1e-4) {
			dir = new Vec3(1, 0, 0);
		}
		r.ball.vel = dir.normalize().scale(0.12 + power * 0.9);
		r.strokes[r.hole]++;
		p.level().playSound(null, p.blockPosition(), SoundEvents.ARROW_HIT, SoundSource.PLAYERS, 0.9F, 1.4F + (float) power * 0.4F);
		p.sendOverlayMessage(Component.literal("Hole " + (r.hole + 1) + "  •  stroke " + r.strokes[r.hole]).withStyle(ChatFormatting.GREEN));
		return true;
	}

	/** The bar swings up and down: 0..1 and back over two seconds. */
	private static double power(int t) {
		double f = (t % 40) / 20.0;
		return f <= 1 ? f : 2 - f;
	}

	static boolean isPutter(ItemStack stack) {
		return HubGames.isToy(stack) && stack.getHoverName().getString().equals(PUTTER);
	}

	/** Somebody punched a golf ball. */
	static boolean punched(ServerPlayer p, Entity e) {
		if (!e.entityTags().contains(BALL_TAG)) {
			return false;
		}
		Round r = ROUNDS.get(p.getUUID());
		if (r != null && r.ball != null && e.getUUID().equals(r.ball.hitbox)) {
			putt(p);
		} else {
			p.sendOverlayMessage(Component.literal("That's someone else's ball!").withStyle(ChatFormatting.YELLOW));
		}
		return true;
	}

	static void tick(ServerLevel level) {
		tick++;
		// The windmill's sails and the castle's portcullis.
		sailAngle += 0.06;
		if (tick % 4 == 0) {
			sails(level, 4);
		}
		BlockState barrier = sailDown() ? Blocks.BARRIER.defaultBlockState() : Blocks.AIR.defaultBlockState();
		for (int z = 33; z <= 35; z++) {
			BlockPos pos = new BlockPos(-47, FLOOR + 1, z);
			if (level.getBlockState(pos) != barrier) {
				level.setBlock(pos, barrier, 2);
			}
		}
		BlockState bars = portcullisDown() ? Blocks.IRON_BARS.defaultBlockState() : Blocks.AIR.defaultBlockState();
		for (int z = 38; z <= 40; z++) {
			BlockPos pos = new BlockPos(-44, FLOOR + 1, z);
			if (level.getBlockState(pos).getBlock() != bars.getBlock()) {
				level.setBlock(pos, bars, 3);
				if (z == 39) {
					level.playSound(null, pos, portcullisDown() ? SoundEvents.CHAIN_PLACE : SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 0.6F, 0.7F);
				}
			}
		}
		if (tick % 6 == 0) {
			level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, -43.5, FLOOR + 4.2, 45.5, 1, 0.1, 0.1, 0.1, 0.01);
			if (tick % 30 == 0) {
				level.sendParticles(ParticleTypes.LAVA, -43.5, FLOOR + 4, 45.5, 3, 0.2, 0.1, 0.2, 0);
			}
		}
		for (Iterator<Map.Entry<UUID, Round>> it = ROUNDS.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			Round r = e.getValue();
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
			if (p == null || p.level() != level || r.ball == null || p.position().distanceTo(r.ball.pos) > 45) {
				if (r.ball != null) {
					r.ball.remove(level);
				}
				it.remove();
				continue;
			}
			if (r.next >= 0) {
				if (--r.next < 0) {
					if (r.hole + 1 < HOLES.size()) {
						toHole(p, r, r.hole + 1);
					} else {
						finish(p, r);
						it.remove();
					}
				}
				continue;
			}
			if (r.charging >= 0) {
				if (!isPutter(p.getMainHandItem())) {
					r.charging = -1;
				} else if (tick % 2 == 0) {
					meter(level, p, r);
				}
			}
			roll(level, p, r);
		}
	}

	private static void meter(ServerLevel level, ServerPlayer p, Round r) {
		double power = power(tick - r.charging);
		int filled = (int) Math.round(power * 12);
		StringBuilder bar = new StringBuilder();
		for (int i = 0; i < 12; i++) {
			bar.append(i < filled ? "■" : "□");
		}
		ChatFormatting color = power < 0.4 ? ChatFormatting.GREEN : power < 0.75 ? ChatFormatting.YELLOW : ChatFormatting.RED;
		p.sendOverlayMessage(Component.literal("Power  ").withStyle(ChatFormatting.WHITE).append(Component.literal(bar.toString()).withStyle(color))
				.append(Component.literal("  right-click to hit!").withStyle(ChatFormatting.WHITE)));
		Vec3 dir = new Vec3(p.getLookAngle().x, 0, p.getLookAngle().z);
		if (dir.lengthSqr() > 1e-4) {
			dir = dir.normalize();
			for (int i = 1; i <= 6; i++) {
				Vec3 dot = r.ball.pos.add(dir.scale(i * (0.35 + power * 0.9))).add(0, 0.15, 0);
				p.level().sendParticles(p, new DustParticleOptions(0xFFFFFFFF, 0.7F), true, false, dot.x, dot.y, dot.z, 1, 0, 0, 0, 0);
			}
		}
	}

	private static void roll(ServerLevel level, ServerPlayer p, Round r) {
		HubGames.Ball b = r.ball;
		Hole hole = HOLES.get(r.hole);
		if (b.vel.lengthSqr() > 0) {
			b.step(level);
		}
		// Lava and water.
		BlockPos under = BlockPos.containing(b.pos.x, FLOOR, b.pos.z);
		BlockState floor = level.getBlockState(under);
		String oops = floor.is(Blocks.MAGMA_BLOCK) ? "Hot lava! Back to the tee (+1 stroke)"
				: !level.getFluidState(under).isEmpty() ? "Splash! Back to the tee (+1 stroke)" : null;
		boolean out = b.pos.x < hole.x1() - 0.1 || b.pos.x > hole.x2() + 1.1 || b.pos.z < hole.z1() - 0.1 || b.pos.z > hole.z2() + 1.1;
		if (oops != null || out) {
			if (oops != null) {
				level.playSound(null, under, floor.is(Blocks.MAGMA_BLOCK) ? SoundEvents.LAVA_EXTINGUISH : SoundEvents.PLAYER_SPLASH, SoundSource.BLOCKS, 0.8F, 1.2F);
			}
			b.pos = ballHome(hole);
			b.vel = Vec3.ZERO;
			r.strokes[r.hole]++;
			p.sendOverlayMessage(Component.literal(oops != null ? oops : "Out of bounds! Back to the tee (+1 stroke)").withStyle(ChatFormatting.RED));
			b.step(level);
		}
		Vec3 cup = Vec3.atBottomCenterOf(hole.cup().above());
		double d = Math.hypot(b.pos.x - cup.x, b.pos.z - cup.z);
		if (d < 0.45 && b.vel.length() < 0.6) {
			int s = r.strokes[r.hole];
			int diff = s - PAR[r.hole];
			String how = s == 1 ? "HOLE IN ONE!" : diff <= -2 ? "Eagle!" : diff == -1 ? "Birdie!" : diff == 0 ? "Par!" : "In the hole!";
			Alerts.title(p, how, NAMES[r.hole] + " in " + s + (s == 1 ? " stroke" : " strokes"), ChatFormatting.GREEN);
			level.playSound(null, hole.cup(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.6F);
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, cup.x, cup.y + 0.5, cup.z, 20, 0.4, 0.4, 0.4, 0);
			if (diff < 0) {
				HubPark.burst(level, cup.add(0, 4, 0));
			}
			b.remove(level);
			r.ball = spawnBall(level, cup.add(0, -5, 0));
			r.next = 50;
			return;
		}
		if (r.strokes[r.hole] >= MAX_STROKES && b.vel.lengthSqr() == 0) {
			p.sendOverlayMessage(Component.literal("That one's tricky! On to the next hole.").withStyle(ChatFormatting.YELLOW));
			r.next = 30;
		}
	}

	private static void finish(ServerPlayer p, Round r) {
		if (r.ball != null) {
			r.ball.remove((ServerLevel) p.level());
		}
		int total = 0;
		int par = 0;
		StringBuilder card = new StringBuilder();
		for (int h = 0; h < 4; h++) {
			total += r.strokes[h];
			par += PAR[h];
			card.append(h == 0 ? "" : "   ").append(h + 1).append(": ").append(r.strokes[h]);
		}
		Alerts.title(p, "Round done!", total + " strokes (par " + par + ")", ChatFormatting.GOLD);
		p.sendSystemMessage(Component.literal("⛳ Mini golf scorecard:  " + card + "   Total: " + total + " (par " + par + ")").withStyle(ChatFormatting.GREEN));
		p.sendSystemMessage(Component.literal("Talk to Gus to play again!").withStyle(ChatFormatting.GRAY));
		p.teleportTo(-43.5, FLOOR + 1, 56.0);
		if (total <= par) {
			HubPark.burst((ServerLevel) p.level(), p.position().add(0, 4, 0));
		}
	}

	static void reset() {
		ROUNDS.clear();
	}
}
