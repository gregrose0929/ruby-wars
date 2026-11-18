package dev.rubymod.game.party;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.game.Alerts;
import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.mixin.ItemDisplayAccessor;
import dev.rubymod.mixin.TextDisplayAccessor;

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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/**
 * Coaster Workshop: build your own roller coaster, piece by piece, then ride it. Everyone gets a plot with a station;
 * the nine tools in your hotbar add track (straight, left, right, up, down, a loop, a launch), take the last piece back,
 * or send you round. Stand at someone else's station and press Ride to ride theirs. Coasters are kept (saved with the
 * world), so they're still there next time. No rounds, no score: just build and ride.
 */
public class CoasterWorkshop extends PartyGame {
	private static final int PLOTS = 6;
	/** Each plot: from {@link #BEHIND} behind the station to {@link #AHEAD} in front, {@link #SIDE} either side, {@link #HIGH} up. */
	private static final int BEHIND = 35;
	private static final int AHEAD = 100;
	private static final int SIDE = 50;
	private static final int HIGH = 70;
	private static final int SPACING = SIDE * 2 + 10;
	private static final int MAX_PIECES = 100;
	/** The whole ground (half its size each way), plots and all. */
	private static final int HALF_X = 72;
	private static final int HALF_Z = PLOTS / 2 * SPACING + SIDE + 10;
	private static final double STEP = 0.25;

	private enum Piece {
		STRAIGHT("Straight", Items.RAIL, ChatFormatting.WHITE), LEFT("Turn Left", Items.SPECTRAL_ARROW, ChatFormatting.YELLOW),
		RIGHT("Turn Right", Items.ARROW, ChatFormatting.YELLOW), UP("Hill Up", Items.FEATHER, ChatFormatting.AQUA),
		DOWN("Drop Down", Items.ANVIL, ChatFormatting.AQUA), LOOP("Loop!", Items.SLIME_BALL, ChatFormatting.GREEN),
		LAUNCH("Launch", Items.FIREWORK_ROCKET, ChatFormatting.RED), BIG_DROP("Big Drop", Items.POINTED_DRIPSTONE, ChatFormatting.AQUA),
		HELIX("Helix", Items.NAUTILUS_SHELL, ChatFormatting.GREEN), TUNNEL("Tunnel", Items.COBBLESTONE, ChatFormatting.GRAY),
		FINISH("Finish the Circuit", Items.COMPASS, ChatFormatting.GOLD),
		BANK_LEFT("Banked Left", Items.PRISMARINE_SHARD, ChatFormatting.YELLOW), BANK_RIGHT("Banked Right", Items.AMETHYST_SHARD, ChatFormatting.YELLOW),
		HOPS("Bunny Hops", Items.RABBIT_FOOT, ChatFormatting.AQUA), CORKSCREW("Corkscrew!", Items.BREEZE_ROD, ChatFormatting.GREEN);

		final String label;
		final Item icon;
		final ChatFormatting color;

		Piece(String label, Item icon, ChatFormatting color) {
			this.label = label;
			this.icon = icon;
			this.color = color;
		}
	}

	private static final String UNDO = "Undo (crouch: start again)";
	private static final String RIDE = "Ride!";
	private static final String MORE = "More pieces >>";
	private static final String MORE2 = "Even more pieces >>";
	private static final String BACK = "<< Back";
	private static final String PAINT = "Track Colour";
	private static final Piece[] PAGE1 = {Piece.STRAIGHT, Piece.LEFT, Piece.RIGHT, Piece.UP, Piece.DOWN, Piece.LOOP};
	private static final Piece[] PAGE2 = {Piece.LAUNCH, Piece.BIG_DROP, Piece.HELIX, Piece.TUNNEL, Piece.FINISH};
	private static final Piece[] PAGE3 = {Piece.BANK_LEFT, Piece.BANK_RIGHT, Piece.HOPS, Piece.CORKSCREW};
	private static final DyeColor[][] COLOURS = {{DyeColor.RED, DyeColor.YELLOW}, {DyeColor.BLUE, DyeColor.WHITE}, {DyeColor.LIME, DyeColor.GREEN},
		{DyeColor.PURPLE, DyeColor.PINK}, {DyeColor.ORANGE, DyeColor.BLACK}, {null, DyeColor.WHITE}};
	private static final String[] COLOUR_NAMES = {"Red", "Blue", "Green", "Purple", "Orange", "Rainbow"};

	/** Everyone's coasters by name: plot number and pieces. */
	private static final class Coaster {
		int plot;
		int colour;
		boolean[] tunnel = new boolean[0];
		final List<Piece> pieces = new ArrayList<>();
		final List<UUID> shown = new ArrayList<>();
		UUID sign;
		/** The ride, worked out from the pieces: points STEP apart, which way is up, launch boosts. */
		List<Vec3> path = List.of();
		List<Vec3> ups = List.of();
		boolean[] launch = new boolean[0];
		boolean circuit;
	}

	private final Map<String, Coaster> coasters = new LinkedHashMap<>();

	/** A train going round: three cars on whose coaster, who's in each, where the front is. */
	private static final class Ride {
		final Coaster coaster;
		final UUID[] riders = new UUID[CARS];
		final UUID[] seats = new UUID[CARS];
		final UUID[] cars = new UUID[CARS];
		/** How far along the front car is (the others follow GAP behind each other). */
		double d = (CARS - 1) * GAP;
		double v = 0.2;
		/** Ticks left waiting in the station for friends to hop on. */
		int boarding = BOARDING;
		boolean launched;
		boolean braked;
		int windCool;
		int wheeCool;

		Ride(Coaster coaster) {
			this.coaster = coaster;
		}

		int free() {
			for (int k = 0; k < CARS; k++) {
				if (riders[k] == null) {
					return k;
				}
			}
			return -1;
		}
	}

	private static final int CARS = 3;
	private static final double GAP = 2.0;
	private static final int BOARDING = 50;

	/** Rider to their train. */
	private final Map<UUID, Ride> rides = new HashMap<>();
	private final List<Ride> trains = new ArrayList<>();
	private boolean builtThisRun;
	private boolean loaded;

	public CoasterWorkshop() {
		super("coasters", "Coaster Workshop", "Build your own roller coaster, piece by piece: hills, drops, loops and launches. Then ride it! "
				+ "Your coaster's kept for next time, and you can ride everyone else's too.", "minecraft:minecart", 1, false, true, false);
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(-110000, GROUND + 1, 0);
	}

	@Override
	protected int reach() {
		return HALF_Z + 10;
	}

	@Override
	protected String arenaKey() {
		// v2: the bigger plots.
		return id + "-v2";
	}

	@Override
	protected String roundStatus() {
		int n = activeCount();
		return n + (n == 1 ? " builder" : " builders");
	}

	private BlockPos station(int plot) {
		BlockPos c = center();
		return new BlockPos(c.getX() - (AHEAD - BEHIND) / 2, c.getY(), c.getZ() + (plot - PLOTS / 2) * SPACING);
	}

	// ----- Building the ground -----

	@Override
	protected void build(ServerLevel level) {
		flatArena(level, HALF_X, HALF_Z, 8, Blocks.GRASS_BLOCK.defaultBlockState(), true);
		// The old (smaller) ground's invisible walls stood 40 high, now inside the new ground: gone.
		BlockPos mid = center();
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int x = -72; x <= 72; x++) {
			for (int z = -164; z <= 164; z++) {
				if (Math.abs(x) <= 70 && Math.abs(z) <= 162) {
					continue;
				}
				int fx = x;
				int fz = z;
				later(() -> {
					for (int y = 9; y <= 42; y++) {
						BlockPos p = new BlockPos(mid.getX() + fx, GROUND + y, mid.getZ() + fz);
						if (level.getBlockState(p).is(Blocks.BARRIER)) {
							setNow(level, p, air);
						}
					}
				});
			}
		}
		later(() -> {
			for (int i = 0; i < PLOTS; i++) {
				stationBlocks(level, i);
			}
			load();
			for (Coaster c : coasters.values()) {
				redraw(level, c);
			}
		});
	}

	private void stationBlocks(ServerLevel level, int plot) {
		BlockPos s = station(plot);
		DyeColor[] colors = {DyeColor.RED, DyeColor.BLUE, DyeColor.LIME, DyeColor.YELLOW, DyeColor.PURPLE, DyeColor.ORANGE};
		// A plot border on the grass, and the station: a platform, posts and a striped roof.
		for (int x = -BEHIND - 1; x <= AHEAD + 1; x++) {
			for (int z = -SIDE; z <= SIDE; z++) {
				if (x == -BEHIND - 1 || x == AHEAD + 1 || Math.abs(z) == SIDE) {
					setNow(level, s.offset(x, -1, z), Blocks.WOOL.pick(colors[plot]).defaultBlockState());
				}
			}
		}
		for (int x = -2; x <= 8; x++) {
			for (int z = -3; z <= 3; z++) {
				setNow(level, s.offset(x, -1, z), Math.abs(z) <= 1 ? Blocks.POLISHED_BLACKSTONE.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState());
				setNow(level, s.offset(x, 5, z), Blocks.WOOL.pick((x & 1) == 0 ? colors[plot] : DyeColor.WHITE).defaultBlockState());
			}
		}
		for (int x : new int[] {-2, 8}) {
			for (int z : new int[] {-3, 3}) {
				for (int y = 0; y <= 4; y++) {
					setNow(level, s.offset(x, y, z), Blocks.QUARTZ_PILLAR.defaultBlockState());
				}
			}
		}
	}

	// ----- Players -----

	@Override
	protected void startRound() {
		for (ServerPlayer p : online()) {
			spawn(p, players.get(p.getUUID()));
		}
	}

	@Override
	protected void spawn(ServerPlayer player, Part part) {
		Coaster c = mine(player);
		ServerLevel level = level();
		if (c.shown.isEmpty() && level != null) {
			redraw(level, c);
		}
		BlockPos s = station(c.plot);
		player.stopRiding();
		teleport(player, s.getX() + 3.5, s.getY(), s.getZ() + 2.5, 270);
		ready(player);
		player.setGameMode(GameType.ADVENTURE);
		clearPlayer(player);
		player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, -1, 0, false, false));
		tools(player, 1);
		Alerts.title(player, "COASTER WORKSHOP", "Right-click the tools in your hotbar to build. Then Ride!", ChatFormatting.GOLD);
	}

	/** The hotbar: page 1 (the basics) or page 2 (the big stuff). */
	private static void tools(ServerPlayer player, int page) {
		player.getInventory().clearContent();
		for (Piece piece : page == 1 ? PAGE1 : page == 2 ? PAGE2 : PAGE3) {
			give(player, tool(piece.icon, piece.label, piece.color));
		}
		if (page == 3) {
			give(player, tool(Items.DYE.pick(DyeColor.MAGENTA), PAINT, ChatFormatting.LIGHT_PURPLE));
		}
		give(player, tool(Items.BARRIER, UNDO, ChatFormatting.GRAY));
		if (page < 3) {
			give(player, tool(Items.PAPER, page == 1 ? MORE : MORE2, ChatFormatting.AQUA));
		}
		if (page > 1) {
			give(player, tool(Items.MAP, BACK, ChatFormatting.AQUA));
		}
		give(player, tool(Items.MINECART, RIDE, ChatFormatting.GOLD));
		player.getInventory().setSelectedSlot(0);
		player.inventoryMenu.broadcastChanges();
	}

	private static ItemStack tool(Item item, String name, ChatFormatting color) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(color));
		return s;
	}

	/** This player's coaster (a plot of their own the first time). */
	private Coaster mine(ServerPlayer p) {
		load();
		String name = p.getName().getString();
		Coaster c = coasters.get(name);
		if (c == null) {
			c = new Coaster();
			boolean[] used = new boolean[PLOTS];
			for (Coaster o : coasters.values()) {
				used[o.plot] = true;
			}
			int plot = 0;
			while (plot < PLOTS - 1 && used[plot]) {
				plot++;
			}
			if (used[plot]) {
				// All taken: the longest-left plot goes to the newcomer.
				String oldest = coasters.keySet().iterator().next();
				Coaster gone = coasters.remove(oldest);
				clearShown(level(), gone);
				plot = gone.plot;
			}
			c.plot = plot;
			coasters.put(name, c);
			lay(c);
			save();
		}
		return c;
	}

	@Override
	public boolean allowsItemUse(ServerPlayer player, ItemStack stack) {
		String name = stack.getHoverName().getString();
		ServerLevel level = level();
		if (level == null || rides.containsKey(player.getUUID())) {
			return false;
		}
		if (player.getCooldowns().isOnCooldown(stack)) {
			return false;
		}
		player.getCooldowns().addCooldown(stack, 6);
		Coaster c = mine(player);
		if (MORE.equals(name) || MORE2.equals(name) || BACK.equals(name)) {
			tools(player, MORE.equals(name) ? 2 : MORE2.equals(name) ? 3 : 1);
			return false;
		}
		if (PAINT.equals(name)) {
			c.colour = (c.colour + 1) % COLOURS.length;
			redraw(level, c);
			save();
			player.sendOverlayMessage(Component.literal("Track colour: " + COLOUR_NAMES[c.colour]).withStyle(ChatFormatting.LIGHT_PURPLE));
			return false;
		}
		for (Piece piece : Piece.values()) {
			if (piece.label.equals(name)) {
				if (c.pieces.size() >= MAX_PIECES) {
					player.sendOverlayMessage(Component.literal("That's as long as a coaster can be! Undo some, or Ride!").withStyle(ChatFormatting.YELLOW));
					return false;
				}
				if (!c.pieces.isEmpty() && c.pieces.get(c.pieces.size() - 1) == Piece.FINISH) {
					player.sendOverlayMessage(Component.literal("It's finished! Ride it, or Undo to keep building.").withStyle(ChatFormatting.YELLOW));
					return false;
				}
				String why = tryAdd(c, piece);
				if (why != null) {
					player.sendOverlayMessage(Component.literal(why).withStyle(ChatFormatting.YELLOW));
					return false;
				}
				redraw(level, c);
				save();
				level.playSound(null, player.blockPosition(), SoundEvents.ANVIL_PLACE, SoundSource.PLAYERS, 0.4F, 1.6F);
				player.sendOverlayMessage(Component.literal(piece.label.replace("!", "") + " added (" + c.pieces.size() + " pieces)"
						+ (c.circuit ? "  It's a full circuit!" : "")).withStyle(ChatFormatting.GREEN));
				return false;
			}
		}
		if (UNDO.equals(name)) {
			if (player.isShiftKeyDown()) {
				c.pieces.clear();
				player.sendOverlayMessage(Component.literal("Cleared: start again!").withStyle(ChatFormatting.YELLOW));
			} else if (!c.pieces.isEmpty()) {
				c.pieces.remove(c.pieces.size() - 1);
				player.sendOverlayMessage(Component.literal("Took the last piece back.").withStyle(ChatFormatting.YELLOW));
			}
			lay(c);
			redraw(level, c);
			save();
			return false;
		}
		if (RIDE.equals(name)) {
			// At someone else's station: ride theirs.
			Coaster which = c;
			for (Coaster o : coasters.values()) {
				BlockPos s = station(o.plot);
				if (player.position().distanceTo(Vec3.atCenterOf(s)) < 8) {
					which = o;
				}
			}
			ride(player, which);
			return false;
		}
		return true;
	}

	/** Add a piece if it fits and doesn't crash into the track; otherwise why not. */
	private String tryAdd(Coaster c, Piece piece) {
		int before = c.path.size();
		c.pieces.add(piece);
		lay(c);
		String why = !fits(c) ? "That would go off the edge of your plot! Try turning." : crashes(c, before, piece == Piece.FINISH)
				? "That would crash into your track! Try another way." : null;
		if (why != null) {
			c.pieces.remove(c.pieces.size() - 1);
			lay(c);
		}
		return why;
	}

	/** For autotests: start this player's coaster again from these pieces (by name); what happened to each. */
	public String testBuild(ServerPlayer p, String... names) {
		Coaster c = mine(p);
		c.pieces.clear();
		lay(c);
		StringBuilder b = new StringBuilder();
		for (String name : names) {
			Piece piece = Piece.valueOf(name);
			String why = tryAdd(c, piece);
			b.append(name).append(why == null ? " ok" : " NO: " + why).append("; ");
		}
		if (level() != null) {
			redraw(level(), c);
		}
		save();
		return b + testInfo(p);
	}

	/** For autotests: where this player's station is. */
	public BlockPos testStation(ServerPlayer p) {
		return station(mine(p).plot);
	}

	@Override
	public boolean onUseBlock(ServerPlayer player, BlockPos pos, ItemStack held, BlockPos placeAt) {
		return !allowsItemUse(player, held);
	}

	@Override
	public boolean allowsBreak(ServerPlayer player, BlockPos pos) {
		return false;
	}

	// ----- The track -----

	/** Work the pieces out into a path. */
	private void lay(Coaster c) {
		BlockPos s = station(c.plot);
		List<Vec3> pts = new ArrayList<>();
		List<Vec3> up = new ArrayList<>();
		List<Boolean> boost = new ArrayList<>();
		java.util.Set<Integer> tunnelAt = new java.util.HashSet<>();
		Vec3 at = new Vec3(s.getX() - 1.5, s.getY() + 0.6, s.getZ() + 0.5);
		double yaw = 0; // heading +x
		// The station: straight along the platform.
		for (int i = 0; i < 9; i++) {
			add(pts, up, boost, at, new Vec3(0, 1, 0), false);
			at = at.add(dir(yaw));
		}
		for (Piece p : c.pieces) {
			switch (p) {
				case STRAIGHT, LAUNCH -> {
					int n = p == Piece.LAUNCH ? 8 : 5;
					for (int i = 0; i < n; i++) {
						add(pts, up, boost, at, new Vec3(0, 1, 0), p == Piece.LAUNCH);
						at = at.add(dir(yaw));
					}
				}
				case LEFT, RIGHT -> {
					double r = 7;
					double sign = p == Piece.LEFT ? -1 : 1;
					Vec3 centre = at.add(side(yaw).scale(r * sign));
					for (int i = 0; i < 9; i++) {
						double a = Math.toRadians(yaw + sign * i * 90.0 / 9);
						Vec3 q = centre.subtract(side(Math.toDegrees(a)).scale(r * sign));
						add(pts, up, boost, new Vec3(q.x, at.y, q.z), new Vec3(0, 1, 0), false);
					}
					yaw += sign * 90;
					Vec3 centre2 = centre.subtract(side(yaw).scale(r * sign));
					at = new Vec3(centre2.x, at.y, centre2.z);
				}
				case UP, DOWN -> {
					double rise = p == Piece.UP ? 4 : -Math.min(5, at.y - (s.getY() + 0.6));
					for (int i = 0; i < 7; i++) {
						double t = i / 7.0;
						double h = rise * (0.5 - 0.5 * Math.cos(Math.PI * t));
						add(pts, up, boost, at.add(dir(yaw).scale(i)).add(0, h, 0), new Vec3(0, 1, 0), false);
					}
					at = at.add(dir(yaw).scale(7)).add(0, rise, 0);
				}
				case BIG_DROP -> {
					double fall = -Math.min(12, at.y - (s.getY() + 0.6));
					double climb = fall > -3 ? 8 : 0;
					double rise = fall > -3 ? climb : fall;
					for (int i = 0; i < 11; i++) {
						double t = i / 11.0;
						double h = rise * (0.5 - 0.5 * Math.cos(Math.PI * t));
						add(pts, up, boost, at.add(dir(yaw).scale(i * 1.1)).add(0, h, 0), new Vec3(0, 1, 0), false);
					}
					at = at.add(dir(yaw).scale(12.1)).add(0, rise, 0);
				}
				case HELIX -> {
					// A full circle to the right, going down (or up, if it's low already), leaning in.
					double r = 5;
					double drop = at.y - (s.getY() + 0.6) > 5 ? -5 : 5;
					Vec3 centre = at.add(side(yaw).scale(r));
					for (int i = 0; i < 24; i++) {
						double a = Math.toRadians(yaw + i * 15);
						Vec3 q = centre.subtract(side(Math.toDegrees(a)).scale(r));
						q = new Vec3(q.x, at.y + drop * i / 24.0, q.z);
						add(pts, up, boost, q, banked(q, centre, 30, i / 24.0), false);
					}
					at = at.add(0, drop, 0).add(dir(yaw).scale(0.5));
				}
				case BANK_LEFT, BANK_RIGHT -> {
					// A tighter turn, tipped right over toward the middle.
					double r = 6;
					double sign = p == Piece.BANK_LEFT ? -1 : 1;
					Vec3 centre = at.add(side(yaw).scale(r * sign));
					int n = 12;
					for (int i = 0; i < n; i++) {
						double a = Math.toRadians(yaw + sign * i * 90.0 / n);
						Vec3 q = centre.subtract(side(Math.toDegrees(a)).scale(r * sign));
						q = new Vec3(q.x, at.y, q.z);
						add(pts, up, boost, q, banked(q, centre, 45, i / (double) n), false);
					}
					yaw += sign * 90;
					Vec3 centre2 = centre.subtract(side(yaw).scale(r * sign));
					at = new Vec3(centre2.x, at.y, centre2.z);
				}
				case HOPS -> {
					// Three quick little hills: up out of your seat over each one.
					for (int i = 0; i < 18; i++) {
						double h = 2.2 * Math.pow(Math.sin(Math.PI * i / 6.0), 2);
						add(pts, up, boost, at.add(dir(yaw).scale(i)).add(0, h, 0), new Vec3(0, 1, 0), false);
					}
					at = at.add(dir(yaw).scale(18));
				}
				case CORKSCREW -> {
					// Once right round, upside down in the middle, while going forward.
					double r = 1.8;
					double len = 14;
					Vec3 f = dir(yaw);
					Vec3 sd = side(yaw);
					int n = 28;
					for (int i = 0; i < n; i++) {
						double t = i / (double) n;
						double th = 2 * Math.PI * t * t * (3 - 2 * t);
						Vec3 axis = at.add(f.scale(len * t)).add(0, r, 0);
						Vec3 q = axis.add(0, -r * Math.cos(th), 0).add(sd.scale(r * Math.sin(th)));
						add(pts, up, boost, q, axis.subtract(q).normalize(), false);
					}
					at = at.add(f.scale(len));
				}
				case TUNNEL -> {
					for (int i = 0; i < 8; i++) {
						add(pts, up, boost, at, new Vec3(0, 1, 0), false);
						tunnelAt.add(pts.size() - 1);
						at = at.add(dir(yaw));
					}
				}
				case FINISH -> {
					// A smooth curve back round to the start of the station.
					Vec3 end = pts.get(0);
					double len = Math.min(10, Math.max(5, at.distanceTo(end) * 0.45));
					Vec3 p1 = at.add(dir(yaw).scale(len));
					Vec3 p2 = end.add(-len, 0, 0);
					// Keep the curve's pulls inside the plot.
					BlockPos st = station(c.plot);
					p1 = new Vec3(Math.max(st.getX() - BEHIND + 2, Math.min(st.getX() + AHEAD - 2, p1.x)), p1.y,
						Math.max(st.getZ() - SIDE + 3, Math.min(st.getZ() + SIDE - 2, p1.z)));
					for (int i = 0; i < 30; i++) {
						double t = i / 30.0;
						double u = 1 - t;
						Vec3 q = at.scale(u * u * u).add(p1.scale(3 * u * u * t)).add(p2.scale(3 * u * t * t)).add(end.scale(t * t * t));
						add(pts, up, boost, q, new Vec3(0, 1, 0), false);
					}
					at = end;
				}
				case LOOP -> {
					double r = 4.5;
					Vec3 centre = at.add(0, r, 0);
					Vec3 f = dir(yaw);
					Vec3 sd = side(yaw);
					int n = 32;
					for (int i = 0; i < n; i++) {
						double t = i * Math.PI * 2 / n;
						Vec3 q = centre.add(f.scale(r * Math.sin(t))).add(0, -r * Math.cos(t), 0).add(sd.scale(1.8 * t / (Math.PI * 2)));
						Vec3 u = centre.add(sd.scale(1.8 * t / (Math.PI * 2))).subtract(q).normalize();
						add(pts, up, boost, q, u, false);
					}
					at = at.add(sd.scale(1.8));
				}
				default -> {
				}
			}
		}
		add(pts, up, boost, at, new Vec3(0, 1, 0), false);
		// Back at the start? Then it's a full circuit.
		Vec3 start = pts.get(0);
		c.circuit = c.pieces.size() > 3 && at.distanceTo(start) < 3.5 || !c.pieces.isEmpty() && c.pieces.get(c.pieces.size() - 1) == Piece.FINISH;
		// Points STEP apart (straight lines between: the pieces are already finely laid out).
		List<Vec3> path = new ArrayList<>();
		List<Vec3> ups = new ArrayList<>();
		List<Boolean> la = new ArrayList<>();
		List<Boolean> tl = new ArrayList<>();
		for (int i = 0; i + 1 < pts.size(); i++) {
			Vec3 a = pts.get(i);
			Vec3 b = pts.get(i + 1);
			int k = Math.max(1, (int) Math.round(a.distanceTo(b) / STEP));
			for (int j = 0; j < k; j++) {
				path.add(a.lerp(b, j / (double) k));
				ups.add(up.get(i).lerp(up.get(i + 1), j / (double) k).normalize());
				la.add(boost.get(i));
				tl.add(tunnelAt.contains(i));
			}
		}
		path.add(pts.get(pts.size() - 1));
		ups.add(up.get(up.size() - 1));
		la.add(false);
		tl.add(false);
		// Round off the corners where the pieces meet (and between their points): a smoother ride.
		path = smooth(path, 6, false);
		ups = smooth(ups, 6, true);
		c.tunnel = new boolean[tl.size()];
		for (int i = 0; i < tl.size(); i++) {
			c.tunnel[i] = tl.get(i);
		}
		c.path = path;
		c.ups = ups;
		c.launch = new boolean[la.size()];
		for (int i = 0; i < la.size(); i++) {
			c.launch[i] = la.get(i);
		}
	}

	/** Which way is up on a banked turn: leaning toward {@code centre}, easing in and out over the turn (t 0 to 1). */
	private static Vec3 banked(Vec3 q, Vec3 centre, double degrees, double t) {
		double ease = Math.min(1, Math.min(t, 1 - t) * 4);
		double b = Math.toRadians(degrees) * ease * ease * (3 - 2 * ease);
		Vec3 in = new Vec3(centre.x - q.x, 0, centre.z - q.z);
		in = in.lengthSqr() < 1e-6 ? Vec3.ZERO : in.normalize();
		return new Vec3(0, Math.cos(b), 0).add(in.scale(Math.sin(b)));
	}

	/** A running average (twice) over {@code w} points each way; the ends stay put. */
	private static List<Vec3> smooth(List<Vec3> in, int w, boolean unit) {
		List<Vec3> out = new ArrayList<>(in);
		for (int pass = 0; pass < 2; pass++) {
			List<Vec3> src = new ArrayList<>(out);
			for (int i = 1; i < src.size() - 1; i++) {
				int h = Math.min(w, Math.min(i, src.size() - 1 - i));
				double x = 0;
				double y = 0;
				double z = 0;
				for (int k = i - h; k <= i + h; k++) {
					Vec3 v = src.get(k);
					x += v.x;
					y += v.y;
					z += v.z;
				}
				Vec3 m = new Vec3(x, y, z).scale(1.0 / (2 * h + 1));
				out.set(i, unit && m.lengthSqr() > 1e-6 ? m.normalize() : m);
			}
		}
		return out;
	}

	private static void add(List<Vec3> pts, List<Vec3> up, List<Boolean> boost, Vec3 p, Vec3 u, boolean b) {
		pts.add(p);
		up.add(u);
		boost.add(b);
	}

	private static Vec3 dir(double yaw) {
		double r = Math.toRadians(yaw);
		return new Vec3(Math.cos(r), 0, Math.sin(r));
	}

	/** To the right of heading {@code yaw}. */
	private static Vec3 side(double yaw) {
		double r = Math.toRadians(yaw);
		return new Vec3(-Math.sin(r), 0, Math.cos(r));
	}

	/**
	 * Would the newest piece run into the track already there? (Its own points, and the bit just before it, don't count;
	 * finishing the circuit may come back alongside the station.)
	 */
	private boolean crashes(Coaster c, int before, boolean finish) {
		int keep = Math.max(0, before - 16);
		for (int i = before; i < c.path.size(); i++) {
			Vec3 q = c.path.get(i);
			for (int j = finish ? 60 : 0; j < keep; j++) {
				Vec3 o = c.path.get(j);
				if (Math.hypot(q.x - o.x, q.z - o.z) < 2.2 && Math.abs(q.y - o.y) < 3.2) {
					if (finish && i > c.path.size() - 40) {
						continue;
					}
					return true;
				}
			}
		}
		return false;
	}

	/** Does the coaster stay on its plot (and below the sky limit)? */
	private boolean fits(Coaster c) {
		BlockPos s = station(c.plot);
		for (Vec3 p : c.path) {
			if (p.x < s.getX() - BEHIND || p.x > s.getX() + AHEAD || Math.abs(p.z - s.getZ() - 0.5) > SIDE - 1 || p.y > s.getY() + HIGH) {
				return false;
			}
		}
		return true;
	}

	private Vec3[] frame(Coaster c, int i) {
		int n = c.path.size();
		Vec3 fwd = c.path.get(Math.min(n - 1, i + 1)).subtract(c.path.get(Math.max(0, i - 1)));
		fwd = fwd.lengthSqr() < 1e-9 ? new Vec3(1, 0, 0) : fwd.normalize();
		Vec3 up = c.ups.get(Math.min(n - 1, i));
		up = up.subtract(fwd.scale(fwd.dot(up)));
		up = up.lengthSqr() < 1e-4 ? new Vec3(0, 1, 0) : up.normalize();
		return new Vec3[] {fwd, up, up.cross(fwd).normalize()};
	}

	private void clearShown(ServerLevel level, Coaster c) {
		if (level == null || c == null) {
			return;
		}
		for (UUID id : c.shown) {
			Entity e = level.getEntity(id);
			if (e != null) {
				e.discard();
			}
			spawned.remove(id);
		}
		c.shown.clear();
	}

	/** Draw the coaster: red rails on a yellow spine, black ties, white supports, a sign with the builder's name. */
	private void redraw(ServerLevel level, Coaster c) {
		clearShown(level, c);
		if (c.path.isEmpty()) {
			lay(c);
		}
		DyeColor[] rainbow = {DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.LIME, DyeColor.LIGHT_BLUE, DyeColor.PURPLE};
		DyeColor[] look = COLOURS[Math.floorMod(c.colour, COLOURS.length)];
		BlockState rail = Blocks.CONCRETE.pick(look[0] == null ? DyeColor.RED : look[0]).defaultBlockState();
		BlockState spine = Blocks.CONCRETE.pick(look[1]).defaultBlockState();
		BlockState rock = Blocks.STONE_BRICKS.defaultBlockState();
		BlockState tie = Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState();
		BlockState steel = Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState();
		BlockState chain = Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState();
		BlockState magnet = Blocks.GOLD_BLOCK.defaultBlockState();
		BlockState footing = Blocks.POLISHED_ANDESITE.defaultBlockState();
		int seg = (int) Math.round(1.5 / STEP);
		for (int i = 0; i + seg < c.path.size(); i += seg) {
			Vec3 a = c.path.get(i);
			Vec3 b = c.path.get(i + seg);
			Vec3[] fa = frame(c, i);
			Vec3[] fb = frame(c, i + seg);
			if (look[0] == null) {
				rail = Blocks.CONCRETE.pick(rainbow[(i / seg) % rainbow.length]).defaultBlockState();
			}
			for (int s : new int[] {-1, 1}) {
				beam(level, c, a.add(fa[2].scale(0.45 * s)), b.add(fb[2].scale(0.45 * s)), 0.15F, rail);
			}
			// A tunnel: stone walls and a roof (high above your head).
			if (i < c.tunnel.length && c.tunnel[i]) {
				Vec3 sd = new Vec3(fa[2].x, 0, fa[2].z).normalize().scale(2.4);
				for (int k : new int[] {-1, 1}) {
					beam(level, c, a.add(sd.scale(k)).add(0, -0.4, 0), b.add(sd.scale(k)).add(0, -0.4, 0), 0.0F, rock);
					beam(level, c, a.add(sd.scale(k)).add(0, 1.6, 0), b.add(sd.scale(k)).add(0, 1.6, 0), 0.0F, rock);
				}
				beam(level, c, a.add(0, 4.2, 0), b.add(0, 4.2, 0), 0.0F, rock);
			}
			beam(level, c, a.add(fa[1].scale(-0.35)), b.add(fb[1].scale(-0.35)), 0.3F, spine);
			boolean boosting = i < c.launch.length && c.launch[i];
			// Climbing: the chain lift, a dark strip up the middle.
			if (!boosting && b.y - a.y > 0.25 && fa[1].y > 0.6) {
				beam(level, c, a.add(fa[1].scale(-0.17)), b.add(fb[1].scale(-0.17)), 0.12F, chain);
			}
			Vec3 mid = a.lerp(b, 0.5).add(fa[1].scale(-0.15));
			// Ties (gold launch magnets on a launch).
			beam(level, c, mid.add(fa[2].scale(-0.5)), mid.add(fa[2].scale(0.5)), boosting ? 0.16F : 0.1F, boosting ? magnet : tie);
			// Supports under raised track (not in loops), on a stone footing.
			int ground = station(c.plot).getY();
			if (i % (seg * 3) == 0 && fa[1].y > 0.6 && a.y > ground + 1.4) {
				beam(level, c, a.add(fa[1].scale(-0.5)), new Vec3(a.x, ground, a.z), 0.3F, steel);
				beam(level, c, new Vec3(a.x, ground - 0.05, a.z), new Vec3(a.x, ground + 0.35, a.z), 0.8F, footing);
			}
		}
		// The end of the track: a glowing marker where the next piece goes.
		Vec3 end = c.path.get(c.path.size() - 1);
		beam(level, c, end.add(0, -0.3, 0), end.add(0, 0.6, 0), 0.5F, c.circuit ? Blocks.EMERALD_BLOCK.defaultBlockState()
				: Blocks.REDSTONE_BLOCK.defaultBlockState());
		// The sign.
		String owner = "";
		for (var e : coasters.entrySet()) {
			if (e.getValue() == c) {
				owner = e.getKey();
			}
		}
		BlockPos s = station(c.plot);
		Display.TextDisplay t = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (t != null) {
			t.snapTo(s.getX() + 3.5, s.getY() + 6.6, s.getZ() + 0.5, 0, 0);
			((TextDisplayAccessor) t).rubymod$setText(Component.literal(owner + "'s Coaster").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
					.append(Component.literal("\n" + c.pieces.size() + " pieces" + (c.circuit ? ", full circuit!" : "") + "\nStand here and press Ride to ride it")
							.withStyle(ChatFormatting.WHITE)));
			((DisplayAccessor) t).rubymod$setBillboard(Display.BillboardConstraints.CENTER);
			add(level, c, t);
		}
	}

	private void add(ServerLevel level, Coaster c, Entity e) {
		e.addTag(tag());
		spawned.add(e.getUUID());
		level.addFreshEntity(e);
		c.shown.add(e.getUUID());
	}

	private void beam(ServerLevel level, Coaster c, Vec3 a, Vec3 b, float w0, BlockState state) {
		float w = w0 == 0 ? 1.0F : w0;
		Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d == null) {
			return;
		}
		Vec3 dv = b.subtract(a);
		float len = (float) dv.length();
		Quaternionf rot = new Quaternionf().rotateY((float) Math.atan2(dv.x, dv.z)).rotateX((float) -Math.asin(Math.max(-1, Math.min(1, dv.y / Math.max(1e-6, len)))));
		Vector3f off = rot.transform(new Vector3f(-w / 2, -w / 2, 0));
		d.snapTo(a.x, a.y, a.z, 0, 0);
		((BlockDisplayAccessor) d).rubymod$setBlockState(state);
		((DisplayAccessor) d).rubymod$setTransformation(new Transformation(off, rot, new Vector3f(w, w, len), new Quaternionf()));
		((DisplayAccessor) d).rubymod$setViewRange(4.0F);
		add(level, c, d);
	}

	// ----- Riding -----

	private void ride(ServerPlayer p, Coaster c) {
		ServerLevel level = level();
		if (c.pieces.isEmpty()) {
			p.sendOverlayMessage(Component.literal("Build some track first! Right-click the pieces in your hotbar.").withStyle(ChatFormatting.YELLOW));
			return;
		}
		// A train still waiting in this station? Hop on with your friends.
		Ride r = null;
		for (Ride t : trains) {
			if (t.coaster == c && t.boarding > 0 && t.free() >= 0) {
				r = t;
			}
		}
		if (r == null) {
			r = new Ride(c);
			if (!makeTrain(level, r)) {
				return;
			}
			trains.add(r);
		}
		int k = r.free();
		Entity seat = level.getEntity(r.seats[k]);
		if (seat == null) {
			return;
		}
		p.stopRiding();
		p.startRiding(seat, true, true);
		r.riders[k] = p.getUUID();
		rides.put(p.getUUID(), r);
		p.sendOverlayMessage(Component.literal(k == 0 ? "Front seat! Hold on (crouch to get off)" : "All aboard! (crouch to get off)").withStyle(ChatFormatting.GOLD));
		level.playSound(null, p.blockPosition(), SoundEvents.IRON_DOOR_CLOSE, SoundSource.PLAYERS, 0.8F, 1.2F);
	}

	/** A train of three cars in the station, the front one at the head of the platform. */
	private boolean makeTrain(ServerLevel level, Ride r) {
		String colour = new String[] {"red", "blue", "green", "yellow", "red", "blue"}[r.coaster.plot];
		for (int k = 0; k < CARS; k++) {
			Display.BlockDisplay seat = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			Display.ItemDisplay car = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (seat == null || car == null) {
				end(level, r);
				return false;
			}
			Vec3 at = r.coaster.path.get((int) Math.min(r.coaster.path.size() - 1, Math.max(0, r.d - k * GAP) / STEP));
			seat.snapTo(at.x, at.y + 0.55, at.z, 0, 0);
			((BlockDisplayAccessor) seat).rubymod$setBlockState(Blocks.AIR.defaultBlockState());
			((DisplayAccessor) seat).rubymod$setPosRotInterpolationDuration(2);
			seat.addTag(tag());
			spawned.add(seat.getUUID());
			level.addFreshEntity(seat);
			car.snapTo(at.x, at.y + 0.55, at.z, 0, 0);
			ItemStack look = new ItemStack(Items.STICK);
			look.set(DataComponents.ITEM_MODEL, dev.rubymod.RubyMod.id("park_coaster_solo_" + colour));
			((ItemDisplayAccessor) car).rubymod$setItemStack(look);
			car.addTag(tag());
			spawned.add(car.getUUID());
			level.addFreshEntity(car);
			car.startRiding(seat, true, true);
			r.seats[k] = seat.getUUID();
			r.cars[k] = car.getUUID();
		}
		place(level, r);
		level.playSound(null, station(r.coaster.plot), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 0.6F, 1.6F);
		return true;
	}

	private void tell(Ride r, Component text) {
		for (UUID id : r.riders) {
			ServerPlayer p = id == null ? null : PartyGames.player(server, id);
			if (p != null) {
				p.sendOverlayMessage(text);
			}
		}
	}

	@Override
	protected void tickRound() {
		ServerLevel level = level();
		if (level == null) {
			return;
		}
		for (var it = trains.iterator(); it.hasNext();) {
			Ride r = it.next();
			Coaster c = r.coaster;
			int n = c.path.size();
			boolean done = r.d / STEP >= n - 1;
			// Who's still aboard? (Crouching gets you off: back to the station.)
			ServerPlayer front = null;
			List<ServerPlayer> aboard = new ArrayList<>();
			for (int k = 0; k < CARS; k++) {
				if (r.riders[k] == null) {
					continue;
				}
				ServerPlayer p = PartyGames.player(server, r.riders[k]);
				Entity seat = level.getEntity(r.seats[k]);
				if (p == null || seat == null || p.getVehicle() != seat) {
					if (rides.get(r.riders[k]) == r) {
						rides.remove(r.riders[k]);
					}
					r.riders[k] = null;
					if (p != null && p.level() == level) {
						offTrain(p, c, k, false);
					}
					continue;
				}
				aboard.add(p);
				if (front == null) {
					front = p;
				}
			}
			if (front == null || done) {
				end(level, r);
				it.remove();
				for (int k = 0; k < CARS; k++) {
					ServerPlayer p = r.riders[k] == null ? null : PartyGames.player(server, r.riders[k]);
					if (p != null) {
						rides.remove(p.getUUID());
						if (p.level() == level) {
							offTrain(p, c, k, true);
						}
					}
				}
				continue;
			}
			// Waiting in the station for friends.
			if (r.boarding > 0) {
				r.boarding--;
				if (r.boarding % 20 == 0 && r.boarding > 0) {
					tell(r, Component.literal("Leaving in " + (r.boarding / 20) + "...  (friends: press Ride! here to hop on)").withStyle(ChatFormatting.GOLD));
				}
				if (r.boarding == 0) {
					tell(r, Component.literal("Here we go!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
					level.playSound(null, station(c.plot), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 0.8F, 1.2F);
				}
				continue;
			}
			// Speed: the middle of the train goes up and down; the front sets off launches and the brakes.
			int lead = (int) Math.min(n - 1, r.d / STEP);
			int i = (int) Math.min(n - 1, Math.max(0, r.d - GAP) / STEP);
			int j = Math.min(n - 1, i + 4);
			double dy = c.path.get(j).y - c.path.get(i).y;
			boolean lift = false;
			if (c.launch[lead]) {
				if (!r.launched) {
					r.launched = true;
					level.playSound(null, front, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.0F, 0.6F);
					level.playSound(null, front, SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 0.7F, 0.8F);
				}
				r.v = Math.min(1.5, r.v + 0.06);
			} else {
				r.launched = false;
				double v2 = r.v * r.v - 2 * 0.03 * dy * r.v - 0.0004;
				r.v = Math.sqrt(Math.max(0, v2));
				// A chain lift takes you up anything you're too slow for.
				lift = r.v < 0.2 && dy > 0.05;
			}
			r.v = Math.max(0.2, Math.min(1.6, r.v));
			// The brakes, coming into the station (or the end of the line).
			double left = (n - 1 - lead) * STEP;
			if (left < 8 && r.v > 0.12 + left * 0.05) {
				r.v = 0.12 + left * 0.05;
				if (!r.braked) {
					r.braked = true;
					level.playSound(null, front, SoundEvents.PISTON_CONTRACT, SoundSource.PLAYERS, 0.7F, 0.6F);
					level.playSound(null, front, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.4F, 1.6F);
				}
			}
			// Sounds: the chain lift's clack, rattling along, the wind at speed.
			if (lift && roundTicks % 4 == 0) {
				level.playSound(null, front, SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 0.35F, 1.9F);
				level.playSound(null, front, SoundEvents.CHAIN_STEP, SoundSource.PLAYERS, 0.4F, 0.8F);
			}
			if (r.v > 0.6 && roundTicks % 10 == 0) {
				level.playSound(null, front, SoundEvents.MINECART_RIDING, SoundSource.PLAYERS, (float) (0.2 + 0.3 * r.v), (float) (0.9 + 0.4 * r.v));
			}
			if (--r.windCool <= 0 && r.v > 1.1) {
				r.windCool = 70;
				level.playSound(null, front, SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, 0.35F, 1.3F);
			}
			if (--r.wheeCool <= 0 && r.v > 1.0 && dy < -0.5) {
				r.wheeCool = 120;
				String[] shout = {"WHEEEE!", "AAAAH!", "WOOHOO!", "YEEHAW!"};
				tell(r, Component.literal(shout[level.getRandom().nextInt(shout.length)]).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
			}
			for (ServerPlayer p : aboard) {
				if (r.v > 1.0 && roundTicks % 6 == 0) {
					p.addEffect(new MobEffectInstance(MobEffects.SPEED, 14, 3, false, false));
				}
				if (c.launch[lead] && roundTicks % 3 == 0) {
					level.sendParticles(ParticleTypes.FIREWORK, p.getX(), p.getY(), p.getZ(), 2, 0.3, 0.1, 0.3, 0.02);
				}
			}
			r.d += r.v;
			place(level, r);
		}
	}

	/** Back on the station platform (side by side, if you rode together). */
	private void offTrain(ServerPlayer p, Coaster c, int k, boolean done) {
		BlockPos s = station(c.plot);
		teleport(p, s.getX() + 3.5 - k, s.getY(), s.getZ() + 2.5, 270);
		p.removeEffect(MobEffects.SPEED);
		if (done) {
			Alerts.title(p, c.circuit ? "WHAT A RIDE!" : "END OF THE LINE!", c.circuit ? "" : "Tip: bring the track back round to the station",
					ChatFormatting.GOLD);
		}
	}

	private void place(ServerLevel level, Ride r) {
		for (int k = 0; k < CARS; k++) {
			placeCar(level, r.coaster, r.seats[k], r.cars[k], Math.max(0, r.d - k * GAP));
		}
	}

	/**
	 * A seat to the track, and its car turned to it: tipped up and down only a little, and leaning into banked turns
	 * (it rides round you: you stay upright).
	 */
	private void placeCar(ServerLevel level, Coaster c, UUID seatId, UUID carId, double d) {
		int n = c.path.size();
		double f = Math.min(n - 1.001, d / STEP);
		int i = (int) Math.floor(f);
		Vec3 p = c.path.get(i).lerp(c.path.get(i + 1), f - i);
		Vec3[] fr = frame(c, i);
		Vec3 sp = p.add(fr[1].scale(0.55));
		Entity seat = seatId == null ? null : level.getEntity(seatId);
		if (seat == null) {
			return;
		}
		seat.teleportTo(sp.x, sp.y, sp.z);
		Vec3 h = new Vec3(fr[0].x, 0, fr[0].z).subtract(new Vec3(fr[1].x, 0, fr[1].z).scale(fr[0].y));
		float yaw = h.lengthSqr() < 1e-6 ? seat.getYRot() : (float) Math.toDegrees(Math.atan2(-h.x, h.z));
		seat.setYRot(yaw);
		for (Entity rider : seat.getPassengers()) {
			rider.resetFallDistance();
		}
		if (level.getEntity(carId) instanceof Display disp) {
			double yr = Math.toRadians(yaw);
			Vec3 hd = new Vec3(-Math.sin(yr), 0, Math.cos(yr));
			double pitch = Math.max(-0.35, Math.min(0.35, Math.asin(Math.max(-1, Math.min(1, fr[0].y)))));
			Vec3 fc = hd.scale(Math.cos(pitch)).add(0, Math.sin(pitch), 0);
			Vec3 b0 = new Vec3(0, 1, 0).subtract(fc.scale(fc.y)).normalize();
			Vec3 r0 = b0.cross(fc);
			Vec3 uu = fr[1].subtract(fc.scale(fc.dot(fr[1])));
			double roll = Math.atan2(uu.dot(r0), uu.dot(b0));
			// Lean with the track up to 40 degrees, and back upright when it goes upside down.
			double lim = 0.7;
			double rc = Math.abs(roll) <= lim ? roll
					: Math.copySign(lim, roll) * Math.cos((Math.abs(roll) - lim) * Math.PI / (2 * (Math.PI - lim)));
			Vec3 carUp = b0.scale(Math.cos(rc)).add(r0.scale(Math.sin(rc)));
			Vec3 right = carUp.cross(fc);
			Quaternionf q = new Quaternionf().setFromNormalized(new org.joml.Matrix3f((float) right.x, (float) right.y, (float) right.z,
					(float) carUp.x, (float) carUp.y, (float) carUp.z, (float) fc.x, (float) fc.y, (float) fc.z));
			Vector3f off = new Vector3f((float) carUp.x, (float) carUp.y, (float) carUp.z).mul(0.9F)
					.sub(new Vector3f((float) fr[1].x, (float) fr[1].y, (float) fr[1].z).mul(0.55F));
			((DisplayAccessor) disp).rubymod$setTransformationInterpolationDelay(0);
			((DisplayAccessor) disp).rubymod$setTransformationInterpolationDuration(2);
			((DisplayAccessor) disp).rubymod$setTransformation(new Transformation(off, new Quaternionf(q).rotateY((float) Math.PI),
					new Vector3f(1.8F, 1.8F, 1.8F), new Quaternionf()));
		}
	}

	private void end(ServerLevel level, Ride r) {
		for (UUID[] ids : new UUID[][] {r.cars, r.seats}) {
			for (UUID id : ids) {
				Entity e = id == null ? null : level.getEntity(id);
				if (e != null) {
					e.discard();
				}
				spawned.remove(id);
			}
		}
	}

	@Override
	protected void onLeft(UUID id, Part part) {
		// Their car carries on empty (the train's tick notices they're gone).
		rides.remove(id);
	}

	// ----- Keeping the coasters -----

	private Path file() {
		return server.getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("coasters.txt");
	}

	private void load() {
		if (loaded || server == null) {
			return;
		}
		loaded = true;
		try {
			if (!Files.exists(file())) {
				return;
			}
			for (String line : Files.readAllLines(file())) {
				String[] f = line.split("\\|", -1);
				if (f.length < 3) {
					continue;
				}
				Coaster c = new Coaster();
				c.plot = Math.max(0, Math.min(PLOTS - 1, Integer.parseInt(f[1])));
				if (f.length > 3 && !f[3].isEmpty()) {
					c.colour = Integer.parseInt(f[3].trim());
				}
				for (String p : f[2].split(",")) {
					if (!p.isEmpty()) {
						c.pieces.add(Piece.valueOf(p));
					}
				}
				lay(c);
				coasters.put(f[0], c);
			}
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't read the saved coasters", e);
		}
	}

	private void save() {
		StringBuilder b = new StringBuilder();
		for (var e : coasters.entrySet()) {
			b.append(e.getKey()).append('|').append(e.getValue().plot).append('|');
			for (Piece p : e.getValue().pieces) {
				b.append(p.name()).append(',');
			}
			b.append('|').append(e.getValue().colour);
			b.append('\n');
		}
		try {
			Files.createDirectories(file().getParent());
			dev.rubymod.game.SafeFiles.write(file(), b.toString());
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't save the coasters", e);
		}
	}

	// ----- Built once a run, kept between visits -----

	@Override
	public void queueBuild() {
		if (builtThisRun) {
			return;
		}
		builtThisRun = true;
		super.queueBuild();
	}

	@Override
	protected void clearArea() {
		if (builtThisRun && !coasters.isEmpty() && level() != null) {
			return;
		}
		super.clearArea();
	}

	@Override
	public void tick() {
		if (server != null && phase == Phase.LOBBY && !building() && timer > 1) {
			timer = 1;
		}
		super.tick();
	}

	@Override
	public void reset() {
		super.reset();
		builtThisRun = false;
		loaded = false;
		coasters.clear();
		rides.clear();
		trains.clear();
	}

	/** For autotests: this player's coaster: pieces, and how far its track reaches from the station (ahead, side, up). */
	public String testInfo(ServerPlayer p) {
		Coaster c = mine(p);
		BlockPos s = station(c.plot);
		double ahead = 0;
		double side = 0;
		double up = 0;
		for (Vec3 v : c.path) {
			ahead = Math.max(ahead, v.x - s.getX());
			side = Math.max(side, Math.abs(v.z - s.getZ() - 0.5));
			up = Math.max(up, v.y - s.getY());
		}
		return c.pieces.size() + " pieces, reaches " + Math.round(ahead) + " ahead, " + Math.round(side) + " to the side, " + Math.round(up) + " up";
	}
}
