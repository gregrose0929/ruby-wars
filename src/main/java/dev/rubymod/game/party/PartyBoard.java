package dev.rubymod.game.party;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.game.Alerts;
import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Party Board: a board game for up to four (computer players fill the empty seats). Take turns rolling the dice and
 * hopping round the board: blue squares give coins, red ones take a few, green ones are lucky, purple ones swap you with
 * someone. Pass the star with 20 coins and it's yours. After everyone's had a go, a quick minigame for coins. Most stars
 * after five rounds wins.
 */
public class PartyBoard extends PartyGame {
	private static final int ROUNDS = 5;
	private static final int STAR_COST = 20;
	private static final String DICE = "Roll the Dice!";
	private static final String[] BOT_NAMES = {"Dicey Dan", "Lucky Lou", "Starry Sue"};

	private enum Square {
		BLUE, RED, LUCKY, SWAP
	}

	/** Everyone at the table: a person (id) or a computer player (token is its villager). */
	private static final class Seat {
		final String name;
		final UUID player;
		UUID token;
		int square;
		int coins = 10;
		int stars;

		Seat(String name, UUID player) {
			this.name = name;
			this.player = player;
		}
	}

	private enum Step {
		TURN, ROLLING, MOVING, LANDED, GAME, DONE
	}

	private final List<BlockPos> squares = new ArrayList<>();
	private final List<Square> kinds = new ArrayList<>();
	private final List<Seat> seats = new ArrayList<>();
	private int star;
	private UUID starMark;
	private Step step = Step.DONE;
	private int turn;
	private int round;
	private int wait;
	private int roll;
	private int left;
	// The minigame.
	private boolean anvils;
	private int gameT;
	private final Set<UUID> out = new HashSet<>();
	private final List<UUID> falling = new ArrayList<>();
	private final Map<UUID, BlockPos> fallingAt = new HashMap<>();
	private DyeColor safe;

	public PartyBoard() {
		super("board", "Party Board", "A board game! Roll the dice, hop round the board collecting coins, buy stars, and play a quick minigame "
				+ "after every round. Most stars after five rounds wins. Computer players fill any empty seats.", "minecraft:gold_nugget", 1, false, false, false);
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(-115000, GROUND + 1, 0);
	}

	@Override
	protected int reach() {
		return 90;
	}

	@Override
	protected String roundStatus() {
		if (step == Step.GAME) {
			return "Minigame: " + (anvils ? "Anvil Rain" : "Colour Floor");
		}
		if (seats.isEmpty() || step == Step.DONE) {
			return "Getting ready";
		}
		return "Round " + Math.min(ROUNDS, round + 1) + "/" + ROUNDS + "   " + seats.get(turn).name + "'s turn";
	}

	private BlockPos game() {
		return center().offset(0, 0, 40);
	}

	// ----- The board -----

	@Override
	protected void build(ServerLevel level) {
		flatArena(level, 45, 62, 25, Blocks.GRASS_BLOCK.defaultBlockState(), true);
		later(() -> {
			squares.clear();
			kinds.clear();
			BlockPos c = center();
			// A rounded loop of 28 squares.
			int n = 28;
			for (int i = 0; i < n; i++) {
				double a = i * Math.PI * 2 / n;
				double r = 17 + 3 * Math.cos(a * 2);
				squares.add(c.offset((int) Math.round(Math.cos(a) * r), 0, (int) Math.round(Math.sin(a) * r)));
				Square k = i % 7 == 3 ? Square.LUCKY : i % 9 == 5 ? Square.SWAP : i % 4 == 2 ? Square.RED : Square.BLUE;
				kinds.add(i == 0 ? Square.BLUE : k);
			}
			for (int i = 0; i < n; i++) {
				paint(level, i);
			}
			// The start square, and a path of gravel between the squares.
			for (int i = 0; i < n; i++) {
				BlockPos a = squares.get(i);
				BlockPos b = squares.get((i + 1) % n);
				for (double t = 0.2; t < 0.8; t += 0.1) {
					BlockPos q = BlockPos.containing(a.getX() + (b.getX() - a.getX()) * t + 0.5, a.getY() - 1, a.getZ() + (b.getZ() - a.getZ()) * t + 0.5);
					if (level.getBlockState(q).is(Blocks.GRASS_BLOCK)) {
						setNow(level, q, Blocks.SMOOTH_SANDSTONE.defaultBlockState());
					}
				}
			}
			// The minigame stage: a pit with water at the bottom, the floor laid fresh for each game.
			BlockPos g = game();
			for (int x = -9; x <= 9; x++) {
				for (int z = -9; z <= 9; z++) {
					boolean rim = Math.abs(x) == 9 || Math.abs(z) == 9;
					for (int y = -5; y <= -1; y++) {
						setNow(level, g.offset(x, y, z), rim ? Blocks.QUARTZ_BRICKS.defaultBlockState() : y == -5 ? Blocks.SAND.defaultBlockState()
								: y <= -3 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
					}
				}
			}
			floor(level, true);
		});
	}

	private void paint(ServerLevel level, int i) {
		BlockPos s = squares.get(i);
		DyeColor color = switch (kinds.get(i)) {
			case BLUE -> DyeColor.BLUE;
			case RED -> DyeColor.RED;
			case LUCKY -> DyeColor.LIME;
			case SWAP -> DyeColor.PURPLE;
		};
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				setNow(level, s.offset(dx, -1, dz), dx == 0 && dz == 0 && i == 0 ? Blocks.GOLD_BLOCK.defaultBlockState()
						: Blocks.CONCRETE.pick(color).defaultBlockState());
			}
		}
	}

	/** The minigame floor: all white (between games), or patches of four colours (Colour Floor). */
	private void floor(ServerLevel level, boolean plain) {
		BlockPos g = game();
		DyeColor[] colors = {DyeColor.RED, DyeColor.YELLOW, DyeColor.LIME, DyeColor.LIGHT_BLUE};
		var random = level.getRandom();
		DyeColor[][] patch = new DyeColor[6][6];
		for (DyeColor[] row : patch) {
			for (int k = 0; k < row.length; k++) {
				row[k] = colors[random.nextInt(colors.length)];
			}
		}
		for (int x = -8; x <= 8; x++) {
			for (int z = -8; z <= 8; z++) {
				DyeColor c = plain ? DyeColor.WHITE : patch[(x + 8) / 3][(z + 8) / 3];
				level.setBlock(g.offset(x, -1, z), Blocks.WOOL.pick(c).defaultBlockState(), 2);
			}
		}
	}

	// ----- Starting -----

	@Override
	protected void startRound() {
		ServerLevel level = level();
		seats.clear();
		out.clear();
		for (ServerPlayer p : online()) {
			seats.add(new Seat(players.get(p.getUUID()).name, p.getUUID()));
		}
		Collections.shuffle(seats);
		for (int i = 0; seats.size() < 4; i++) {
			Seat bot = new Seat(BOT_NAMES[i], null);
			Villager v = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT);
			if (v != null) {
				v.setNoAi(true);
				v.setPermanentlyInvulnerable(true);
				v.setCustomName(Component.literal(bot.name).withStyle(ChatFormatting.AQUA));
				v.setCustomNameVisible(true);
				v.addTag(tag());
				spawned.add(v.getUUID());
				level.addFreshEntity(v);
				bot.token = v.getUUID();
			}
			seats.add(bot);
		}
		for (Seat s : seats) {
			s.square = 0;
			place(s);
			ServerPlayer p = s.player == null ? null : PartyGames.player(server, s.player);
			if (p != null) {
				ready(p);
				p.setGameMode(GameType.ADVENTURE);
				clearPlayer(p);
				ItemStack dice = new ItemStack(Items.SLIME_BALL);
				dice.set(DataComponents.CUSTOM_NAME, Component.literal(DICE).withStyle(ChatFormatting.GOLD));
				give(p, dice);
				Alerts.title(p, "PARTY BOARD", "Most stars after " + ROUNDS + " rounds wins!", ChatFormatting.GOLD);
			}
		}
		moveStar(level);
		round = 0;
		turn = 0;
		step = Step.TURN;
		wait = 40;
	}

	@Override
	protected void spawn(ServerPlayer player, Part part) {
		for (Seat s : seats) {
			if (player.getUUID().equals(s.player)) {
				place(s);
				return;
			}
		}
		toLobby(player);
	}

	/** Put someone on their square (side by side if several share it). */
	private void place(Seat s) {
		if (squares.isEmpty()) {
			return;
		}
		BlockPos at = squares.get(s.square);
		int k = seats.indexOf(s);
		double ox = (k % 2) * 0.9 - 0.45;
		double oz = (k / 2) * 0.9 - 0.45;
		if (s.player != null) {
			ServerPlayer p = PartyGames.player(server, s.player);
			if (p != null && step != Step.GAME) {
				teleport(p, at.getX() + 0.5 + ox, at.getY(), at.getZ() + 0.5 + oz, p.getYRot());
			}
		} else {
			Entity e = s.token == null ? null : level().getEntity(s.token);
			if (e != null) {
				e.teleportTo(at.getX() + 0.5 + ox, at.getY(), at.getZ() + 0.5 + oz);
			}
		}
	}

	private void moveStar(ServerLevel level) {
		var random = level.getRandom();
		int was = star;
		do {
			star = 4 + random.nextInt(squares.size() - 4);
		} while (star == was);
		if (starMark != null) {
			Entity e = level.getEntity(starMark);
			if (e != null) {
				e.discard();
			}
		}
		BlockPos s = squares.get(star);
		Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d != null) {
			d.snapTo(s.getX() + 0.5, s.getY() + 2.6, s.getZ() + 0.5, 0, 0);
			((BlockDisplayAccessor) d).rubymod$setBlockState(Blocks.GOLD_BLOCK.defaultBlockState());
			((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(-0.4F, -0.4F, -0.4F), new Quaternionf().rotateXYZ(0.6F, 0.7F, 0),
					new Vector3f(0.8F, 0.8F, 0.8F), new Quaternionf()));
			((DisplayAccessor) d).rubymod$setBrightness(new net.minecraft.util.Brightness(15, 15));
			d.addTag(tag());
			spawned.add(d.getUUID());
			level.addFreshEntity(d);
			starMark = d.getUUID();
		}
		tell(Component.literal("★ The star has moved! ★").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
	}

	// ----- Turns -----

	@Override
	public boolean allowsItemUse(ServerPlayer player, ItemStack stack) {
		if (!stack.getHoverName().getString().equals(DICE)) {
			return true;
		}
		if (step == Step.TURN && !seats.isEmpty() && player.getUUID().equals(seats.get(turn).player)) {
			roll(level());
		} else {
			player.sendOverlayMessage(Component.literal("It's not your turn yet!").withStyle(ChatFormatting.GRAY));
		}
		return false;
	}

	@Override
	public boolean onUseBlock(ServerPlayer player, BlockPos pos, ItemStack held, BlockPos placeAt) {
		return !allowsItemUse(player, held);
	}

	@Override
	public boolean allowsBreak(ServerPlayer player, BlockPos pos) {
		return false;
	}

	private void roll(ServerLevel level) {
		roll = 1 + level.getRandom().nextInt(6);
		step = Step.ROLLING;
		wait = 30;
	}

	@Override
	protected void tickRound() {
		ServerLevel level = level();
		if (level == null || seats.isEmpty()) {
			return;
		}
		Seat s = seats.get(turn);
		ServerPlayer p = s.player == null ? null : PartyGames.player(server, s.player);
		if (roundTicks % 10 == 0) {
			for (Seat o : seats) {
				ServerPlayer q = o.player == null ? null : PartyGames.player(server, o.player);
				if (q != null) {
					q.sendOverlayMessage(Component.literal("★ " + o.stars + "   ● " + o.coins + " coins   " + (step == Step.GAME ? "" : s.name + "'s turn"))
							.withStyle(ChatFormatting.YELLOW));
				}
			}
		}
		switch (step) {
			case TURN -> {
				if (wait == 40) {
					for (ServerPlayer q : online()) {
						Alerts.title(q, s.name + "'s turn!", s.player != null && q.getUUID().equals(s.player) ? "Right-click the dice!" : "", ChatFormatting.AQUA);
					}
				}
				// Computer players roll straight away; people get a while, then it rolls for them.
				if (--wait <= (s.player == null ? 10 : -15 * 20)) {
					roll(level);
				}
			}
			case ROLLING -> {
				int shown = wait > 6 ? 1 + level.getRandom().nextInt(6) : roll;
				if (wait % 3 == 0) {
					for (ServerPlayer q : online()) {
						Alerts.title(q, String.valueOf(shown), s.name + " rolls...", ChatFormatting.GOLD);
					}
					level.playSound(null, squares.get(s.square), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 1.0F, 1.5F);
				}
				if (--wait <= 0) {
					left = roll;
					step = Step.MOVING;
					wait = 8;
				}
			}
			case MOVING -> {
				if (--wait > 0) {
					return;
				}
				wait = 8;
				s.square = (s.square + 1) % squares.size();
				left--;
				place(s);
				level.playSound(null, squares.get(s.square), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0F, 0.8F + (roll - left) * 0.1F);
				if (s.square == star) {
					if (s.coins >= STAR_COST) {
						s.coins -= STAR_COST;
						s.stars++;
						for (ServerPlayer q : online()) {
							Alerts.title(q, "★ STAR! ★", s.name + " got a star!", ChatFormatting.GOLD);
						}
						BlockPos at = squares.get(star);
						level.sendParticles(ParticleTypes.FIREWORK, at.getX() + 0.5, at.getY() + 2, at.getZ() + 0.5, 40, 0.5, 0.5, 0.5, 0.2);
						level.playSound(null, at, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.2F, 1.2F);
						moveStar(level);
					} else if (p != null) {
						p.sendOverlayMessage(Component.literal("A star costs " + STAR_COST + " coins: you've got " + s.coins + ".").withStyle(ChatFormatting.GRAY));
					}
				}
				if (left <= 0) {
					land(level, s, p);
					step = Step.LANDED;
					wait = 40;
				}
			}
			case LANDED -> {
				if (--wait <= 0) {
					turn++;
					if (turn >= seats.size()) {
						turn = 0;
						startGame(level);
					} else {
						step = Step.TURN;
						wait = 40;
					}
				}
			}
			case GAME -> tickGame(level);
			default -> {
			}
		}
		// People stay on their square between turns.
		if (step != Step.GAME && roundTicks % 20 == 0) {
			for (Seat o : seats) {
				ServerPlayer q = o.player == null ? null : PartyGames.player(server, o.player);
				if (q != null && q.position().distanceTo(Vec3.atBottomCenterOf(squares.get(o.square))) > 3) {
					place(o);
				}
			}
		}
	}

	private void land(ServerLevel level, Seat s, ServerPlayer p) {
		var random = level.getRandom();
		String what;
		switch (kinds.get(s.square)) {
			case BLUE -> {
				s.coins += 3;
				what = "+3 coins";
			}
			case RED -> {
				s.coins = Math.max(0, s.coins - 3);
				what = "-3 coins";
			}
			case LUCKY -> {
				int pick = random.nextInt(3);
				if (pick == 0) {
					s.coins += 10;
					what = "Lucky! +10 coins";
				} else if (pick == 1) {
					Seat richest = Collections.max(seats, java.util.Comparator.comparingInt(o -> o.coins));
					int take = Math.min(5, richest.coins);
					if (richest != s) {
						richest.coins -= take;
						s.coins += take;
					}
					what = "Lucky! " + take + " coins from " + (richest == s ? "the bank" : richest.name);
				} else {
					s.square = star;
					place(s);
					what = "Lucky! Whoosh, to the star!";
					if (s.coins >= STAR_COST) {
						s.coins -= STAR_COST;
						s.stars++;
						what += " ★ and you got it!";
						moveStar(level);
					}
				}
			}
			default -> {
				Seat other = seats.get(random.nextInt(seats.size()));
				if (other != s) {
					int a = s.square;
					s.square = other.square;
					other.square = a;
					place(s);
					place(other);
					what = "Swap! " + s.name + " and " + other.name + " swap places";
				} else {
					what = "Swap! ...with yourself. Nothing happens.";
				}
			}
		}
		for (ServerPlayer q : online()) {
			Alerts.title(q, "", s.name + ": " + what, ChatFormatting.YELLOW);
		}
		level.playSound(null, squares.get(s.square), what.startsWith("-") ? SoundEvents.NOTE_BLOCK_BASS.value() : SoundEvents.EXPERIENCE_ORB_PICKUP,
				SoundSource.PLAYERS, 1.0F, 1.0F);
	}

	// ----- Minigames -----

	private void startGame(ServerLevel level) {
		step = Step.GAME;
		anvils = round % 2 == 0;
		gameT = -60;
		out.clear();
		falling.clear();
		fallingAt.clear();
		floor(level, anvils);
		BlockPos g = game();
		int k = 0;
		for (Seat s : seats) {
			ServerPlayer p = s.player == null ? null : PartyGames.player(server, s.player);
			if (p != null) {
				double a = k++ * Math.PI / 2;
				teleport(p, g.getX() + 0.5 + Math.cos(a) * 3, g.getY(), g.getZ() + 0.5 + Math.sin(a) * 3, 0);
				Alerts.title(p, anvils ? "ANVIL RAIN" : "COLOUR FLOOR", anvils ? "Dodge the falling anvils (watch for shadows)!"
						: "Stand on the colour I call before the rest vanish!", ChatFormatting.LIGHT_PURPLE);
			}
		}
	}

	private void tickGame(ServerLevel level) {
		gameT++;
		BlockPos g = game();
		var random = level.getRandom();
		if (gameT < 0) {
			return;
		}
		// Anyone who falls (or is squashed) is out.
		for (Seat s : seats) {
			ServerPlayer p = s.player == null ? null : PartyGames.player(server, s.player);
			if (p != null && !out.contains(p.getUUID()) && p.getY() < g.getY() - 1.5) {
				out.add(p.getUUID());
				Alerts.title(p, "OUT!", "Watch the others...", ChatFormatting.RED);
				teleport(p, g.getX() + 0.5, g.getY(), g.getZ() + 11.5, 180);
			}
		}
		if (anvils) {
			// Shadows warn where each anvil will land; then it drops.
			if (gameT % Math.max(4, 12 - gameT / 60) == 0) {
				BlockPos at = g.offset(random.nextInt(17) - 8, -1, random.nextInt(17) - 8);
				Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
				if (d != null) {
					d.snapTo(at.getX(), at.getY() + 14, at.getZ(), 0, 0);
					((BlockDisplayAccessor) d).rubymod$setBlockState(Blocks.ANVIL.defaultBlockState());
					((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(2);
					d.addTag(tag());
					spawned.add(d.getUUID());
					level.addFreshEntity(d);
					falling.add(d.getUUID());
					fallingAt.put(d.getUUID(), at);
					level.setBlock(at, Blocks.WOOL.pick(DyeColor.GRAY).defaultBlockState(), 2);
				}
			}
			for (var it = falling.iterator(); it.hasNext();) {
				UUID id = it.next();
				Entity d = level.getEntity(id);
				BlockPos at = fallingAt.get(id);
				if (d == null) {
					it.remove();
					continue;
				}
				double y = d.getY() - 0.8;
				if (y <= at.getY() + 1) {
					level.playSound(null, at, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.7F, 1.0F);
					level.sendParticles(ParticleTypes.CLOUD, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 6, 0.3, 0.1, 0.3, 0.02);
					for (ServerPlayer p : online()) {
						if (!out.contains(p.getUUID()) && Math.abs(p.getX() - at.getX() - 0.5) < 0.9 && Math.abs(p.getZ() - at.getZ() - 0.5) < 0.9
								&& Math.abs(p.getY() - at.getY() - 1) < 1.5) {
							out.add(p.getUUID());
							Alerts.title(p, "BONK! OUT!", "Watch the others...", ChatFormatting.RED);
							teleport(p, g.getX() + 0.5, g.getY(), g.getZ() + 11.5, 180);
						}
					}
					level.setBlock(at, Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState(), 2);
					d.discard();
					spawned.remove(id);
					it.remove();
				} else {
					d.teleportTo(d.getX(), y, d.getZ());
				}
			}
		} else {
			// Every few seconds: a colour is called; then everything else vanishes for a moment.
			int cycle = Math.max(60, 110 - gameT / 6);
			int t = gameT % cycle;
			DyeColor[] colors = {DyeColor.RED, DyeColor.YELLOW, DyeColor.LIME, DyeColor.LIGHT_BLUE};
			String[] names = {"RED", "YELLOW", "GREEN", "BLUE"};
			ChatFormatting[] fmt = {ChatFormatting.RED, ChatFormatting.YELLOW, ChatFormatting.GREEN, ChatFormatting.AQUA};
			if (t == 0) {
				int pick = random.nextInt(4);
				safe = colors[pick];
				floor(level, false);
				for (ServerPlayer p : online()) {
					Alerts.title(p, names[pick] + "!", "Stand on " + names[pick].toLowerCase(), fmt[pick]);
				}
				level.playSound(null, g, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.5F, 1.0F);
			}
			if (t == cycle - 25 && safe != null) {
				for (int x = -8; x <= 8; x++) {
					for (int z = -8; z <= 8; z++) {
						BlockPos b = g.offset(x, -1, z);
						if (!level.getBlockState(b).is(Blocks.WOOL.pick(safe))) {
							level.setBlock(b, Blocks.AIR.defaultBlockState(), 2);
						}
					}
				}
			}
		}
		// Thirty seconds, or everyone's out: whoever's left wins 10 coins (computer players: about half the time).
		boolean anyoneIn = false;
		for (Seat s : seats) {
			if (s.player != null && !out.contains(s.player)) {
				anyoneIn = true;
			}
		}
		boolean people = seats.stream().anyMatch(s -> s.player != null);
		if (gameT >= 30 * 20 || people && !anyoneIn) {
			List<String> winners = new ArrayList<>();
			for (Seat s : seats) {
				boolean won = s.player != null ? !out.contains(s.player) : random.nextBoolean();
				if (won) {
					s.coins += 10;
					winners.add(s.name);
				}
			}
			for (UUID id : falling) {
				Entity e = level.getEntity(id);
				if (e != null) {
					e.discard();
				}
			}
			falling.clear();
			floor(level, true);
			for (ServerPlayer p : online()) {
				Alerts.title(p, "+10 coins", winners.isEmpty() ? "Nobody made it!" : String.join(", ", winners), ChatFormatting.GOLD);
			}
			round++;
			for (Seat s : seats) {
				place(s);
			}
			if (round >= ROUNDS) {
				finish();
			} else {
				step = Step.TURN;
				wait = 40;
			}
		}
	}

	private void finish() {
		step = Step.DONE;
		List<Seat> order = new ArrayList<>(seats);
		order.sort((a, b) -> a.stars != b.stars ? b.stars - a.stars : b.coins - a.coins);
		Seat w = order.get(0);
		StringBuilder b = new StringBuilder();
		for (Seat s : order) {
			b.append(b.isEmpty() ? "" : "   ").append(s.name).append(" ★").append(s.stars).append(" ●").append(s.coins);
		}
		List<UUID> winners = w.player != null ? List.of(w.player) : List.of();
		endRound(w.name + " WINS!", b.toString(), ChatFormatting.GOLD, winners);
	}

	@Override
	protected void onLeft(UUID id, Part part) {
		seats.removeIf(s -> id.equals(s.player));
		if (turn >= seats.size()) {
			turn = 0;
		}
	}

	@Override
	public void reset() {
		super.reset();
		seats.clear();
		step = Step.DONE;
	}
}
