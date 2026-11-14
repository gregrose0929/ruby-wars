package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashMap;
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
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Thrill Zone's carnival booths: the High Striker (swing the mallet, ring the bell), Hoop Shot (basketballs),
 * Ring Toss (rings onto bottles) and Whack-a-Mole. Do well and you win a teddy bear.
 */
final class Booths {
	private Booths() {
	}

	private static int tick;

	/** A 30-second round at a booth: when it ends, and everyone's score. */
	private static final class Round {
		int ends = -1;
		final Map<UUID, Integer> scores = new HashMap<>();

		boolean on() {
			return ends >= 0;
		}
	}

	static void build(ServerLevel level) {
		HOOPS.ends = -1;
		RINGS.ends = -1;
		MOLES.ends = -1;
		striker(level);
		hoops(level);
		ringToss(level);
		moles(level);
	}

	/** A little open-fronted booth (open to the north), striped, with a sign. */
	private static void booth(ServerLevel level, int x1, int x2, DyeColor a, DyeColor b, String name, boolean roof) {
		for (int x = x1; x <= x2; x++) {
			for (int z = 52; z <= 55; z++) {
				boolean wall = x == x1 || x == x2 || z == 55;
				HubPark.set(level, x, FLOOR, z, Blocks.SMOOTH_SANDSTONE.defaultBlockState());
				for (int y = FLOOR + 1; y <= FLOOR + 3; y++) {
					HubPark.set(level, x, y, z, wall ? Blocks.WOOL.pick((x + y) % 2 == 0 ? a : b).defaultBlockState() : Blocks.AIR.defaultBlockState());
				}
				if (roof) {
					HubPark.set(level, x, FLOOR + 4, z, Blocks.WOOL.pick((x + z) % 2 == 0 ? a : b).defaultBlockState());
				}
			}
			if (roof) {
				HubPark.set(level, x, FLOOR + 4, 51, Blocks.WOOL.pick(x % 2 == 0 ? a : b).defaultBlockState());
			}
		}
		HubPark.label(level, (x1 + x2 + 1) / 2.0, FLOOR + (roof ? 5.6 : 4.4), 53.0, name, ChatFormatting.YELLOW, 1.0F);
	}

	private static void prize(ServerLevel level, ServerPlayer p, String why) {
		ItemStack teddy = HubGames.toy(Items.STICK, 1, "Teddy Bear (prize!)", ChatFormatting.GOLD);
		teddy.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, dev.rubymod.RubyMod.id("park_teddy"));
		HubGames.give(p, teddy);
		Alerts.title(p, "YOU WIN!", why + " Here's a teddy bear", ChatFormatting.GOLD);
		HubPark.burst(level, p.position().add(0, 3, 0));
	}

	// ----- The High Striker -----

	private static final BlockPos PAD = new BlockPos(82, FLOOR, 53);
	private static UUID puck;
	private static final Map<UUID, Integer> CHARGING = new HashMap<>();
	private static double puckY = FLOOR + 1.2;
	private static double puckTarget;
	private static int puckHold;
	private static String MALLET = "Mallet";

	private static void striker(ServerLevel level) {
		CHARGING.clear();
		booth(level, 81, 84, DyeColor.RED, DyeColor.WHITE, "HIGH STRIKER", false);
		for (int y = FLOOR + 1; y <= FLOOR + 9; y++) {
			HubPark.set(level, 82, y, 54, Blocks.CONCRETE.pick(y >= FLOOR + 8 ? DyeColor.RED : y >= FLOOR + 6 ? DyeColor.ORANGE : y >= FLOOR + 4 ? DyeColor.YELLOW
					: DyeColor.LIME).defaultBlockState());
		}
		HubPark.set(level, 82, FLOOR + 10, 54, Blocks.GOLD_BLOCK.defaultBlockState());
		HubPark.set(level, 82, FLOOR + 11, 54, Blocks.BELL.defaultBlockState());
		HubPark.set(level, PAD.getX(), PAD.getY(), PAD.getZ(), Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState());
		Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d != null) {
			d.snapTo(82.5, puckY, 53.85, 0, 0);
			((BlockDisplayAccessor) d).rubymod$setBlockState(Blocks.GOLD_BLOCK.defaultBlockState());
			((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(-0.3F, 0, -0.1F), new Quaternionf(), new Vector3f(0.6F, 0.35F, 0.2F),
					new Quaternionf()));
			((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(2);
			level.addFreshEntity(d);
			HubPark.keep(d);
			puck = d.getUUID();
		}
		HubHosts.add(level, new Vec3(80.5, FLOOR + 1, 51.5), 180, VillagerProfession.MASON, VillagerType.SAVANNA,
				new HubHosts.Host("striker", "Hank", "High Striker", 0xFF5050, "minecraft:bell",
						"Grab the mallet and test your strength! Stand by the red pad: right-click once to start the power bar, "
								+ "right-click again to swing. Ring the bell to win a teddy bear!",
						"High Striker: right-click Hank for the mallet!", HubHosts.play("Give me the mallet!"), (p, key) -> {
							ItemStack mallet = HubGames.toy(Items.STICK, 1, MALLET, ChatFormatting.RED);
							mallet.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, dev.rubymod.RubyMod.id("park_mallet"));
							HubGames.give(p, mallet);
							HubGames.hold(p, MALLET);
							p.sendOverlayMessage(Component.literal("Stand by the red pad and right-click to swing!").withStyle(ChatFormatting.RED));
						}));
	}

	static boolean isMallet(ItemStack stack) {
		return HubGames.isToy(stack) && stack.getHoverName().getString().equals(MALLET);
	}

	/** Right-click with the mallet: start the power bar, then swing. */
	static boolean swing(ServerPlayer p) {
		ServerLevel level = (ServerLevel) p.level();
		if (p.position().distanceTo(Vec3.atCenterOf(PAD)) > 3.5) {
			p.sendOverlayMessage(Component.literal("Stand by the red pad at the High Striker!").withStyle(ChatFormatting.YELLOW));
			return true;
		}
		if (puckHold > 0 || Math.abs(puckY - puckTarget) > 0.05 && puckTarget > FLOOR + 1.3) {
			return true;
		}
		Integer start = CHARGING.remove(p.getUUID());
		if (start == null) {
			CHARGING.put(p.getUUID(), tick);
			return true;
		}
		double power = power(tick - start);
		double h = Math.pow(power, 1.3) * 8.6;
		puckTarget = FLOOR + 1.2 + h;
		level.playSound(null, PAD, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.6F, 1.4F);
		if (power > 0.93) {
			puckTarget = FLOOR + 9.9;
			p.sendOverlayMessage(Component.literal("DING DING DING!").withStyle(ChatFormatting.GOLD));
			prize(level, p, "You rang the bell!");
			level.playSound(null, new BlockPos(82, FLOOR + 11, 54), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 2.0F, 1.0F);
		} else {
			p.sendOverlayMessage(Component.literal(power < 0.35 ? "A little tap! Try again." : power < 0.7 ? "Not bad! Harder!" : "SO close!")
					.withStyle(ChatFormatting.YELLOW));
		}
		return true;
	}

	private static double power(int t) {
		double f = (t % 36) / 18.0;
		return f <= 1 ? f : 2 - f;
	}

	private static void tickStriker(ServerLevel level) {
		// The power bar for whoever's winding up.
		for (var e : new ArrayList<>(CHARGING.entrySet())) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
			if (p == null || !isMallet(p.getMainHandItem()) || p.position().distanceTo(Vec3.atCenterOf(PAD)) > 4) {
				CHARGING.remove(e.getKey());
				continue;
			}
			if (tick % 2 == 0) {
				double pw = power(tick - e.getValue());
				int filled = (int) Math.round(pw * 12);
				ChatFormatting c = pw < 0.5 ? ChatFormatting.GREEN : pw < 0.9 ? ChatFormatting.YELLOW : ChatFormatting.RED;
				p.sendOverlayMessage(Component.literal("Strength  ").withStyle(ChatFormatting.WHITE)
						.append(Component.literal("■".repeat(filled) + "□".repeat(12 - filled)).withStyle(c))
						.append(Component.literal("  right-click to SWING!").withStyle(ChatFormatting.WHITE)));
			}
		}
		// The puck flies up, hangs a moment, slides back down.
		if (puckTarget > puckY) {
			puckY = Math.min(puckTarget, puckY + 0.7);
			if (puckY >= puckTarget) {
				puckHold = 20;
			}
		} else if (puckHold > 0) {
			puckHold--;
			if (puckHold == 0) {
				puckTarget = FLOOR + 1.2;
			}
		} else if (puckY > FLOOR + 1.2) {
			puckY = Math.max(FLOOR + 1.2, puckY - 0.25);
		}
		Entity d = puck == null ? null : level.getEntity(puck);
		if (d != null && Math.abs(d.getY() - puckY) > 0.01) {
			d.teleportTo(82.5, puckY, 53.85);
		}
	}

	// ----- Hoop Shot -----

	private static final Round HOOPS = new Round();
	private static final AABB HOOP = new AABB(87.15, FLOOR + 3.2, 53.95, 88.85, FLOOR + 3.85, 54.9);

	private static void hoops(ServerLevel level) {
		booth(level, 86, 89, DyeColor.ORANGE, DyeColor.WHITE, "HOOP SHOT", false);
		HubPark.box(level, 86, FLOOR + 4, 55, 89, FLOOR + 5, 55, Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState());
		HubPark.box(level, 87, FLOOR + 3, 55, 88, FLOOR + 3, 55, Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState());
		BlockState rim = Blocks.CONCRETE.pick(DyeColor.ORANGE).defaultBlockState();
		double y = FLOOR + 3.75;
		HubPark.beam(level, new Vec3(87.1, y, 54.0), new Vec3(88.9, y, 54.0), 0.08F, 0.08F, rim);
		HubPark.beam(level, new Vec3(87.1, y, 54.0), new Vec3(87.1, y, 55.0), 0.08F, 0.08F, rim);
		HubPark.beam(level, new Vec3(88.9, y, 54.0), new Vec3(88.9, y, 55.0), 0.08F, 0.08F, rim);
		for (double x = 87.3; x < 88.9; x += 0.4) {
			HubPark.beam(level, new Vec3(x, y, 54.1), new Vec3(x + 0.1, y - 0.6, 54.4), 0.03F, 0.03F, Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState());
		}
		HubHosts.add(level, new Vec3(85.5, FLOOR + 1, 51.5), 180, VillagerProfession.FLETCHER, VillagerType.JUNGLE,
				new HubHosts.Host("hoops", "Bo", "Hoop Shot", 0xFF8A30, "minecraft:snowball",
						"Throw basketballs (right-click) through the hoop! 30 seconds, 12 balls. Get 4 in to win a teddy bear.",
						"Hoop Shot: right-click Bo to play!", HubHosts.play("Play! (30 seconds)"), (p, key) -> {
							start(level, HOOPS, p, "Basketball", "park_basketball", 12, "Throw them through the hoop!");
						}));
	}

	// ----- Ring Toss -----

	private static final Round RINGS = new Round();
	private static final List<Vec3> BOTTLES = new ArrayList<>();
	private static final List<UUID> LANDED = new ArrayList<>();

	private static void ringToss(ServerLevel level) {
		BOTTLES.clear();
		LANDED.clear();
		booth(level, 91, 94, DyeColor.LIME, DyeColor.WHITE, "RING TOSS", true);
		HubPark.box(level, 92, FLOOR + 1, 53, 93, FLOOR + 1, 54, Blocks.SPRUCE_PLANKS.defaultBlockState());
		for (double x : new double[] {92.3, 93.0, 93.7}) {
			for (double z : new double[] {53.4, 54.0, 54.6}) {
				Vec3 top = new Vec3(x, FLOOR + 2.65, z);
				BOTTLES.add(top);
				HubPark.piece(level, new Vec3(x, FLOOR + 2.25, z), Blocks.STAINED_GLASS.pick(DyeColor.GREEN).defaultBlockState(), new Vector3f(0.22F, 0.5F, 0.22F),
						new Quaternionf());
				HubPark.piece(level, new Vec3(x, FLOOR + 2.6, z), Blocks.STAINED_GLASS.pick(DyeColor.GREEN).defaultBlockState(), new Vector3f(0.1F, 0.2F, 0.1F),
						new Quaternionf());
			}
		}
		HubHosts.add(level, new Vec3(90.5, FLOOR + 1, 51.5), 180, VillagerProfession.LIBRARIAN, VillagerType.SWAMP,
				new HubHosts.Host("rings", "Rosie", "Ring Toss", 0x60FF80, "minecraft:lead",
						"Toss the rings (right-click) onto the bottles! 30 seconds, 12 rings. Land 3 to win a teddy bear. Aim high and let them drop!",
						"Ring Toss: right-click Rosie to play!", HubHosts.play("Play! (30 seconds)"), (p, key) -> {
							start(level, RINGS, p, "Toss Ring", "park_toss_ring", 12, "Toss them onto the bottles!");
						}));
	}

	// ----- Whack-a-Mole -----

	private static final Round MOLES = new Round();
	private static final List<Vec3> HOLES = new ArrayList<>();
	private static final List<UUID> MOLE = new ArrayList<>();
	private static final List<UUID> MOLE_HIT = new ArrayList<>();
	private static final int[] MOLE_UP = new int[6];
	private static final String MOLE_TAG = "rubymod_mole";

	private static void moles(ServerLevel level) {
		HOLES.clear();
		MOLE.clear();
		MOLE_HIT.clear();
		booth(level, 95, 99, DyeColor.BROWN, DyeColor.YELLOW, "WHACK-A-MOLE", true);
		HubPark.box(level, 96, FLOOR + 1, 53, 98, FLOOR + 1, 54, Blocks.CONCRETE.pick(DyeColor.BROWN).defaultBlockState());
		for (int x = 96; x <= 98; x++) {
			for (int z = 53; z <= 54; z++) {
				Vec3 hole = new Vec3(x + 0.5, FLOOR + 2, z + 0.5);
				HOLES.add(hole);
				HubPark.piece(level, hole.add(0, 0.01, 0), Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(), new Vector3f(0.7F, 0.02F, 0.7F),
						new Quaternionf());
				Display.ItemDisplay m = HubPark.model(level, hole.add(0, -0.9, 0), 180, "mole", 0.85F, 0.42F);
				MOLE.add(m == null ? null : m.getUUID());
				Interaction hit = EntityTypes.INTERACTION.create(level, EntitySpawnReason.EVENT);
				if (hit != null) {
					((InteractionAccessor) hit).rubymod$setWidth(0.8F);
					((InteractionAccessor) hit).rubymod$setHeight(0.8F);
					hit.snapTo(hole.x, hole.y, hole.z, 0, 0);
					hit.addTag(MOLE_TAG);
					HubPark.keep(hit);
					level.addFreshEntity(hit);
					MOLE_HIT.add(hit.getUUID());
				} else {
					MOLE_HIT.add(null);
				}
			}
		}
		HubHosts.add(level, new Vec3(95.5, FLOOR + 1, 51.5), 180, VillagerProfession.FARMER, VillagerType.PLAINS,
				new HubHosts.Host("moles", "Milo", "Whack-a-Mole", 0xC08040, "minecraft:carrot",
						"The moles pop up out of their holes: punch them (left-click) before they hide! 30 seconds. Whack 10 to win a teddy bear.",
						"Whack-a-Mole: right-click Milo to play!", HubHosts.play("Play! (30 seconds)"), (p, key) -> {
							start(level, MOLES, p, null, null, 0, "Punch the moles (left-click)!");
							p.teleportTo(97.5, FLOOR + 1, 51.8);
							p.setYRot(0);
							p.setXRot(40);
						}));
	}

	/** Punched something: a mole? */
	static boolean punched(ServerPlayer p, Entity e) {
		if (!e.entityTags().contains(MOLE_TAG)) {
			return false;
		}
		int i = MOLE_HIT.indexOf(e.getUUID());
		ServerLevel level = (ServerLevel) p.level();
		if (i >= 0 && MOLE_UP[i] > 0 && MOLES.on() && MOLES.scores.containsKey(p.getUUID())) {
			MOLE_UP[i] = 0;
			int score = MOLES.scores.merge(p.getUUID(), 1, Integer::sum);
			p.sendOverlayMessage(Component.literal("WHACK! " + score).withStyle(ChatFormatting.GOLD));
			level.playSound(null, e.blockPosition(), SoundEvents.NOTE_BLOCK_BASEDRUM.value(), SoundSource.PLAYERS, 1.0F, 1.4F);
			level.playSound(null, e.blockPosition(), SoundEvents.RABBIT_HURT, SoundSource.PLAYERS, 0.8F, 1.6F);
			level.sendParticles(ParticleTypes.CRIT, e.getX(), e.getY() + 0.4, e.getZ(), 8, 0.2, 0.2, 0.2, 0.2);
		}
		return true;
	}

	// ----- Rounds -----

	private static void start(ServerLevel level, Round r, ServerPlayer p, String ball, String model, int count, String how) {
		if (!r.on()) {
			r.ends = tick + 30 * 20;
			r.scores.clear();
		}
		r.scores.putIfAbsent(p.getUUID(), 0);
		if (ball != null) {
			ItemStack balls = HubGames.toy(Items.SNOWBALL, count, ball, ChatFormatting.GOLD);
			balls.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, dev.rubymod.RubyMod.id(model));
			HubGames.give(p, balls);
			HubGames.hold(p, ball);
		}
		Alerts.title(p, "GO!", how + " 30 seconds", ChatFormatting.GOLD);
	}

	private static void tickRound(ServerLevel level, Round r, String what, int toWin, String record) {
		if (!r.on()) {
			return;
		}
		int left = r.ends - tick;
		if (left > 0 && left % 200 == 0) {
			for (UUID id : r.scores.keySet()) {
				ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
				if (p != null) {
					p.sendOverlayMessage(Component.literal(left / 20 + " seconds left!").withStyle(ChatFormatting.GOLD));
				}
			}
		}
		if (left > 0) {
			return;
		}
		r.ends = -1;
		for (var e : r.scores.entrySet()) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
			if (p == null) {
				continue;
			}
			HubGames.takeAll(p, "Basketball");
			HubGames.takeAll(p, "Toss Ring");
			int s = e.getValue();
			Records.submit(level, record, p.getName().getString(), s, false, String::valueOf);
			if (s >= toWin) {
				prize(level, p, s + " " + what + "!");
			} else {
				Alerts.title(p, "TIME!", s + " " + what + ". So close! (" + toWin + " wins a prize)", ChatFormatting.GOLD);
			}
		}
		r.scores.clear();
	}

	private static final Vec3 HOOP_AIM = new Vec3(88.0, FLOOR + 3.75, 54.45);
	private static final Vec3 BOTTLE_AIM = new Vec3(93.0, FLOOR + 2.65, 54.0);
	private static final String LOBBED = "rubymod_lobbed";

	/**
	 * A basketball or ring just thrown becomes a proper lob: it arcs up and drops onto wherever you were aiming (at the
	 * hoop's or the bottles' distance). Aim well and it goes in; aim off and it misses by as much.
	 */
	private static void lob(Snowball ball, Vec3 target) {
		if (!(ball.getOwner() instanceof ServerPlayer p)) {
			return;
		}
		ball.addTag(LOBBED);
		Vec3 eye = p.getEyePosition();
		Vec3 aim = eye.add(p.getLookAngle().scale(eye.distanceTo(target)));
		Vec3 from = ball.position();
		double t = 16;
		double g = 0.03;
		Vec3 flat = aim.subtract(from);
		double drag = 1.08;
		ball.setDeltaMovement(flat.x / t * drag, (flat.y + 0.5 * g * t * t) / t, flat.z / t * drag);
	}

	private static void tickThrows(ServerLevel level) {
		for (Snowball ball : level.getEntities(EntityTypes.SNOWBALL, new AABB(80, FLOOR, 45, 100, FLOOR + 14, 60), s -> s.isAlive())) {
			String name = ball.getItem().getHoverName().getString();
			if (!ball.entityTags().contains(LOBBED)) {
				if (name.equals("Basketball")) {
					lob(ball, HOOP_AIM);
				} else if (name.equals("Toss Ring")) {
					lob(ball, BOTTLE_AIM);
				}
			}
			ServerPlayer owner = ball.getOwner() instanceof ServerPlayer sp ? sp : null;
			Vec3 a = ball.position();
			Vec3 b = a.add(ball.getDeltaMovement());
			if (name.equals("Basketball") && ball.getDeltaMovement().y < 0 && (HOOP.contains(a) || HOOP.clip(a, b).isPresent())) {
				ball.discard();
				level.playSound(null, BlockPos.containing(a), SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 1.2F, 1.2F);
				level.playSound(null, BlockPos.containing(a), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 1.0F, 1.6F);
				level.sendParticles(ParticleTypes.HAPPY_VILLAGER, a.x, a.y, a.z, 10, 0.3, 0.3, 0.3, 0);
				if (owner != null && HOOPS.on() && HOOPS.scores.containsKey(owner.getUUID())) {
					int s = HOOPS.scores.merge(owner.getUUID(), 1, Integer::sum);
					owner.sendOverlayMessage(Component.literal("SWISH! " + s).withStyle(ChatFormatting.GOLD));
				}
			}
			if (name.equals("Toss Ring") && ball.getDeltaMovement().y < 0) {
				for (Vec3 top : BOTTLES) {
					if (Math.hypot(a.x - top.x, a.z - top.z) < 0.4 && a.y < top.y + 0.4 && a.y > top.y - 0.6) {
						ball.discard();
						Display.ItemDisplay ring = HubPark.tempModel(level, top.add(0, -0.45, 0), 0, "toss_ring", 0.55F, 0);
						if (ring != null) {
							LANDED.add(ring.getUUID());
						}
						level.playSound(null, BlockPos.containing(top), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1.0F, 1.8F);
						level.sendParticles(ParticleTypes.HAPPY_VILLAGER, top.x, top.y, top.z, 8, 0.2, 0.2, 0.2, 0);
						if (owner != null && RINGS.on() && RINGS.scores.containsKey(owner.getUUID())) {
							int s = RINGS.scores.merge(owner.getUUID(), 1, Integer::sum);
							owner.sendOverlayMessage(Component.literal("RINGER! " + s).withStyle(ChatFormatting.GOLD));
						}
						break;
					}
				}
			}
		}
	}

	private static void tickMoles(ServerLevel level) {
		for (int i = 0; i < MOLE.size(); i++) {
			if (MOLE_UP[i] > 0) {
				MOLE_UP[i]--;
			}
		}
		if (MOLES.on() && tick % 11 == 0) {
			var random = level.getRandom();
			int i = random.nextInt(MOLE.size());
			if (MOLE_UP[i] == 0) {
				MOLE_UP[i] = 16 + random.nextInt(14);
				level.playSound(null, BlockPos.containing(HOLES.get(i)), SoundEvents.ROOTED_DIRT_BREAK, SoundSource.PLAYERS, 0.8F, 1.4F);
			}
		}
		for (int i = 0; i < MOLE.size(); i++) {
			Entity m = MOLE.get(i) == null ? null : level.getEntity(MOLE.get(i));
			if (m != null) {
				double y = MOLE_UP[i] > 0 ? HOLES.get(i).y - 0.05 : HOLES.get(i).y - 0.9;
				if (Math.abs(m.getY() - y) > 0.01) {
					((DisplayAccessor) m).rubymod$setPosRotInterpolationDuration(2);
					m.teleportTo(m.getX(), y, m.getZ());
				}
			}
		}
	}

	static void tick(ServerLevel level) {
		tick++;
		tickStriker(level);
		tickThrows(level);
		tickMoles(level);
		tickRound(level, HOOPS, "baskets", 4, "Most hoops (30s)");
		if (RINGS.on() && RINGS.ends - tick <= 0) {
			for (UUID id : LANDED) {
				HubPark.discard(level, id);
			}
			LANDED.clear();
		}
		tickRound(level, RINGS, "ringers", 3, "Most ringers (30s)");
		tickRound(level, MOLES, "moles", 10, "Most moles whacked (30s)");
	}
}
