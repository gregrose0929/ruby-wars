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
		FINISH("Finish the Circuit", Items.COMPASS, ChatFormatting.GOLD);

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
	private static final String BACK = "<< Back";
	private static final String PAINT = "Track Colour";
	private static final Piece[] PAGE1 = {Piece.STRAIGHT, Piece.LEFT, Piece.RIGHT, Piece.UP, Piece.DOWN, Piece.LOOP};
	private static final Piece[] PAGE2 = {Piece.LAUNCH, Piece.BIG_DROP, Piece.HELIX, Piece.TUNNEL, Piece.FINISH};
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

	/** A car going round: who, on whose coaster, where. */
	private static final class Ride {
		final UUID rider;
		final Coaster coaster;
		final UUID seat;
		final UUID car;
		double d;
		double v = 0.2;

		Ride(UUID rider, Coaster coaster, UUID seat, UUID car) {
			this.rider = rider;
			this.coaster = coaster;
			this.seat = seat;
			this.car = car;
		}
	}

	private final Map<UUID, Ride> rides = new HashMap<>();
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
		for (Piece piece : page == 1 ? PAGE1 : PAGE2) {
			give(player, tool(piece.icon, piece.label, piece.color));
		}
		if (page == 2) {
			give(player, tool(Items.DYE.pick(DyeColor.MAGENTA), PAINT, ChatFormatting.LIGHT_PURPLE));
		}
		give(player, tool(Items.BARRIER, UNDO, ChatFormatting.GRAY), tool(Items.PAPER, page == 1 ? MORE : BACK, ChatFormatting.AQUA),
				tool(Items.MINECART, RIDE, ChatFormatting.GOLD));
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
		if (MORE.equals(name) || BACK.equals(name)) {
			tools(player, MORE.equals(name) ? 2 : 1);
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
				int before = c.path.size();
				c.pieces.add(piece);
				lay(c);
				String why = !fits(c) ? "That would go off the edge of your plot! Try turning." : crashes(c, before, piece == Piece.FINISH)
						? "That would crash into your track! Try another way." : null;
				if (why != null) {
					c.pieces.remove(c.pieces.size() - 1);
					lay(c);
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
					// A full circle to the right, going down (or up, if it's low already).
					double r = 5;
					double drop = at.y - (s.getY() + 0.6) > 5 ? -5 : 5;
					Vec3 centre = at.add(side(yaw).scale(r));
					for (int i = 0; i < 24; i++) {
						double a = Math.toRadians(yaw + i * 15);
						Vec3 q = centre.subtract(side(Math.toDegrees(a)).scale(r));
						add(pts, up, boost, new Vec3(q.x, at.y + drop * i / 24.0, q.z), new Vec3(0, 1, 0), false);
					}
					at = at.add(0, drop, 0).add(dir(yaw).scale(0.5));
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
			Vec3 mid = a.lerp(b, 0.5).add(fa[1].scale(-0.15));
			beam(level, c, mid.add(fa[2].scale(-0.5)), mid.add(fa[2].scale(0.5)), 0.1F, tie);
			// Supports under raised track (not in loops).
			if (i % (seg * 3) == 0 && fa[1].y > 0.9 && a.y > station(c.plot).getY() + 1.4) {
				beam(level, c, a.add(0, -0.5, 0), new Vec3(a.x, station(c.plot).getY(), a.z), 0.3F, steel);
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
		Display.BlockDisplay seat = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		Display.ItemDisplay car = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (seat == null || car == null) {
			return;
		}
		Vec3 at = c.path.get(0);
		seat.snapTo(at.x, at.y + 0.55, at.z, 0, 0);
		((BlockDisplayAccessor) seat).rubymod$setBlockState(Blocks.AIR.defaultBlockState());
		((DisplayAccessor) seat).rubymod$setPosRotInterpolationDuration(2);
		seat.addTag(tag());
		spawned.add(seat.getUUID());
		level.addFreshEntity(seat);
		car.snapTo(at.x, at.y + 0.55, at.z, 0, 0);
		ItemStack look = new ItemStack(Items.STICK);
		look.set(DataComponents.ITEM_MODEL, dev.rubymod.RubyMod.id("park_coaster_solo_" + new String[] {"red", "blue", "green", "yellow", "red", "blue"}[c.plot]));
		((ItemDisplayAccessor) car).rubymod$setItemStack(look);
		car.addTag(tag());
		spawned.add(car.getUUID());
		level.addFreshEntity(car);
		car.startRiding(seat, true, true);
		p.stopRiding();
		p.startRiding(seat, true, true);
		Ride r = new Ride(p.getUUID(), c, seat.getUUID(), car.getUUID());
		rides.put(p.getUUID(), r);
		place(level, r);
		p.sendOverlayMessage(Component.literal("Hold on! (crouch to get off)").withStyle(ChatFormatting.GOLD));
		level.playSound(null, p.blockPosition(), SoundEvents.IRON_DOOR_CLOSE, SoundSource.PLAYERS, 0.8F, 1.2F);
	}

	@Override
	protected void tickRound() {
		ServerLevel level = level();
		if (level == null) {
			return;
		}
		for (var it = rides.entrySet().iterator(); it.hasNext();) {
			Ride r = it.next().getValue();
			ServerPlayer p = PartyGames.player(server, r.rider);
			Entity seat = level.getEntity(r.seat);
			Coaster c = r.coaster;
			int n = c.path.size();
			if (p == null || seat == null || p.getVehicle() != seat || r.d / STEP >= n - 1) {
				boolean done = r.d / STEP >= n - 1;
				end(level, r);
				it.remove();
				if (p != null && p.level() == level) {
					BlockPos s = station(c.plot);
					teleport(p, s.getX() + 3.5, s.getY(), s.getZ() + 2.5, 270);
					p.removeEffect(MobEffects.SPEED);
					if (done) {
						Alerts.title(p, c.circuit ? "WHAT A RIDE!" : "END OF THE LINE!", c.circuit ? "" : "Tip: bring the track back round to the station",
								ChatFormatting.GOLD);
					}
				}
				continue;
			}
			int i = (int) Math.min(n - 1, r.d / STEP);
			int j = (int) Math.min(n - 1, i + 4);
			double dy = c.path.get(j).y - c.path.get(i).y;
			if (c.launch[i]) {
				r.v = Math.min(1.5, r.v + 0.06);
			} else {
				double v2 = r.v * r.v - 2 * 0.03 * dy * r.v - 0.0004;
				r.v = Math.sqrt(Math.max(0, v2));
			}
			// A chain lift takes you up anything you're too slow for.
			r.v = Math.max(0.2, Math.min(1.6, r.v));
			double left = (n - 1 - i) * STEP;
			if (!c.circuit && left < 6) {
				r.v = Math.min(r.v, 0.12 + left * 0.05);
			}
			if (r.v > 1.0 && roundTicks % 6 == 0) {
				p.addEffect(new MobEffectInstance(MobEffects.SPEED, 14, 3, false, false));
			}
			if (r.v > 0.9 && roundTicks % 10 == 0) {
				level.playSound(null, p.blockPosition(), SoundEvents.MINECART_RIDING, SoundSource.PLAYERS, 0.6F, 1.4F);
			}
			if (c.launch[i] && roundTicks % 3 == 0) {
				level.sendParticles(ParticleTypes.FIREWORK, p.getX(), p.getY(), p.getZ(), 2, 0.3, 0.1, 0.3, 0.02);
			}
			r.d += r.v;
			place(level, r);
		}
	}

	/** The seat to the track, and the car turned to it (tipped only a little: it rides round you). */
	private void place(ServerLevel level, Ride r) {
		Coaster c = r.coaster;
		int n = c.path.size();
		double f = Math.min(n - 1.001, r.d / STEP);
		int i = (int) Math.floor(f);
		Vec3 p = c.path.get(i).lerp(c.path.get(i + 1), f - i);
		Vec3[] fr = frame(c, i);
		Vec3 sp = p.add(fr[1].scale(0.55));
		Entity seat = level.getEntity(r.seat);
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
		if (level.getEntity(r.car) instanceof Display d) {
			double pitch = Math.max(-0.35, Math.min(0.35, Math.asin(Math.max(-1, Math.min(1, fr[0].y)))));
			Quaternionf q = new Quaternionf().rotateY((float) Math.toRadians(-yaw)).rotateX((float) -pitch);
			Vector3f carUp = q.transform(new Vector3f(0, 1, 0));
			Vector3f off = new Vector3f(carUp).mul(0.9F).sub(new Vector3f((float) fr[1].x, (float) fr[1].y, (float) fr[1].z).mul(0.55F));
			((DisplayAccessor) d).rubymod$setTransformationInterpolationDelay(0);
			((DisplayAccessor) d).rubymod$setTransformationInterpolationDuration(2);
			((DisplayAccessor) d).rubymod$setTransformation(new Transformation(off, new Quaternionf(q).rotateY((float) Math.PI), new Vector3f(1.8F, 1.8F, 1.8F),
					new Quaternionf()));
		}
	}

	private void end(ServerLevel level, Ride r) {
		for (UUID id : new UUID[] {r.car, r.seat}) {
			Entity e = level.getEntity(id);
			if (e != null) {
				e.discard();
			}
			spawned.remove(id);
		}
	}

	@Override
	protected void onLeft(UUID id, Part part) {
		Ride r = rides.remove(id);
		if (r != null && level() != null) {
			end(level(), r);
		}
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
