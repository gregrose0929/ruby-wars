package dev.rubymod.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.gun.GunItem;
import dev.rubymod.industry.Grid;
import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Build-phase world events: something worth going for, out where nobody's base is, so players meet out in the open
 * (and fight: PvP is on outside bases in the build phase, and what you carry waits in your War Grave if you fall).
 * Each is told to everyone in Ruby Wars two minutes ahead, with a marker on the map and a beam of light over the
 * spot, and it's put somewhere between everyone's bases so nobody starts much closer than the rest.
 * <ul>
 * <li>Supply Cache: a ring of chests round a big one in the middle, full of Armory gear and rubies. First there takes
 * it. It crumbles a few minutes after it opens, with whatever's left.</li>
 * <li>Ruby Shrine: stand in its ring with nobody else in it and rubies go into your bank every few seconds (everyone in
 * it glows). Two or more in it and it's contested: nobody gets anything. Whoever held it longest gets a bonus.</li>
 * <li>Ruby Meteor: comes down on the spot, full of ruby ore and blocks.</li>
 * </ul>
 * Events only come while at least {@code worldEventMinPlayers} are in Ruby Wars (one alone has nobody to meet), about
 * every {@code worldEventMinutes} of that time. Missing one costs nothing. Not saved: a restart ends it (a cache
 * left standing stays as a few free chests).
 */
public final class WorldEvents {
	public enum Kind {
		CACHE("Supply Cache", ChatFormatting.AQUA, 0xFF5FC8E8),
		SHRINE("Ruby Shrine", ChatFormatting.LIGHT_PURPLE, 0xFFE05AF0),
		METEOR("Ruby Meteor", ChatFormatting.RED, 0xFFFF5050);

		public final String title;
		final ChatFormatting chat;
		final int color;

		Kind(String title, ChatFormatting chat, int color) {
			this.title = title;
			this.chat = chat;
			this.color = color;
		}
	}

	/** How long before it opens it's told (ms). */
	private static final long WARNING = 2 * 60_000L;
	private static final long CACHE_OPEN = 8 * 60_000L;
	private static final long SHRINE_OPEN = 4 * 60_000L;
	private static final int SHRINE_RADIUS = 6;
	/** A rubies to the bank this often while you hold the shrine alone (seconds). */
	private static final int SHRINE_EVERY = 5;
	private static final int SHRINE_BONUS = 15;
	private static final int CACHE_RADIUS = 3;

	private static Kind kind;
	private static BlockPos at;
	private static long opensAt;
	private static long endsAt;
	private static boolean open;
	private static boolean warnedEnd;
	/** Time with enough players on since the last event (ms): the first comes ten minutes in. */
	private static long played;
	private static int rotation;
	/** For tests: added to the clock. */
	private static long skew;
	/** What the event built over, to put back after. */
	private static final Map<BlockPos, BlockState> RESTORE = new LinkedHashMap<>();
	private static final List<BlockPos> CHESTS = new ArrayList<>();
	private static final Set<UUID> CAME = new HashSet<>();
	/** Shrine: seconds each player has held it alone. */
	private static final Map<UUID, Integer> HELD = new HashMap<>();
	private static UUID holder;
	private static boolean contested;

	private WorldEvents() {
	}

	private static long now() {
		return System.currentTimeMillis() + skew;
	}

	public static Kind kind() {
		return kind;
	}

	public static BlockPos at() {
		return at;
	}

	public static boolean isOpen() {
		return open;
	}

	/** Everyone in Ruby Wars right now (not in the hub, a duel, Horde or the other games). */
	public static List<ServerPlayer> inRubyWars(MinecraftServer server) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (Hub.where(p) == Hub.Where.RUBY_WARS && !p.isSpectator()) {
				out.add(p);
			}
		}
		return out;
	}

	/** Every tick: once a second, run things. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 == 0) {
			tickSecond(server, inRubyWars(server));
		}
	}

	/** Once a second, with these players in Ruby Wars (tests pass their own). */
	public static void tickSecond(MinecraftServer server, List<ServerPlayer> players) {
		SeasonData data = SeasonData.get();
		boolean building = data.phase == Phase.BUILD && data.pausedRemaining < 0;
		if (kind != null && !building) {
			finish(server, players, "");
			return;
		}
		if (kind == null) {
			if (!building || players.size() < Math.max(1, GameConfig.get().worldEventMinPlayers) || GameConfig.get().worldEventMinutes <= 0) {
				return;
			}
			played += 1000;
			long every = GameConfig.get().worldEventMinutes * 60_000L;
			if (played >= Math.min(every, 10 * 60_000L) && (played >= every || rotation == 0)) {
				Kind next = Kind.values()[rotation % Kind.values().length];
				if (begin(server, players, next, null, WARNING) == null) {
					played = 0;
					rotation++;
				} else {
					played -= 60_000L; // nowhere free just now: try again in a minute
				}
			}
			return;
		}
		ServerLevel level = server.overworld();
		long t = now();
		beam(level);
		if (!open && t >= opensAt) {
			openUp(server, players);
		}
		if (open) {
			switch (kind) {
				case CACHE -> tickCache(server, players, t);
				case SHRINE -> tickShrine(server, players);
				case METEOR -> {
				}
			}
			if (kind != null && !warnedEnd && endsAt - t <= 60_000L && kind == Kind.CACHE) {
				warnedEnd = true;
				tell(players, Component.literal("The Supply Cache crumbles in a minute, with whatever's still in it.").withStyle(ChatFormatting.GRAY));
			}
			if (kind != null && t >= endsAt) {
				finish(server, players, kind == Kind.SHRINE ? shrineResult(server, players) : "The Supply Cache crumbled away.");
				return;
			}
		}
		status(players, t);
	}

	/**
	 * Start one: told now, opening in {@code warning} ms. {@code where} null: pick a fair spot. Returns why not, or null.
	 */
	public static String begin(MinecraftServer server, List<ServerPlayer> players, Kind what, BlockPos where, long warning) {
		if (kind != null) {
			return "A " + kind.title + " is already on.";
		}
		ServerLevel level = server.overworld();
		BlockPos spot = where != null ? where : pickSpot(level, players);
		if (spot == null) {
			return "Couldn't find a free spot away from everyone's bases.";
		}
		kind = what;
		at = spot.immutable();
		opensAt = now() + warning;
		endsAt = Long.MAX_VALUE;
		open = false;
		warnedEnd = false;
		CAME.clear();
		HELD.clear();
		holder = null;
		contested = false;
		int seconds = (int) (warning / 1000);
		Waypoints.global(what.title, at, 3, seconds + 10 * 60);
		String when = seconds >= 60 ? "in " + (seconds + 30) / 60 + " minute" + ((seconds + 30) / 60 == 1 ? "" : "s") : "now";
		String what2 = switch (what) {
			case CACHE -> "Chests full of Armory gear and rubies. First there takes them.";
			case SHRINE -> "Stand in it alone and rubies go into your bank. Anyone else in it and it's contested.";
			case METEOR -> "It'll be full of rubies, and it's on nobody's land.";
		};
		for (ServerPlayer p : players) {
			Alerts.title(p, what.title.toUpperCase(java.util.Locale.ROOT), when + " at " + at.getX() + ", " + at.getZ() + " (on your map: K)", what.chat);
			p.sendSystemMessage(Component.literal("★ A " + what.title + " " + (seconds >= 60 ? "opens " + when : "is here") + " at " + at.getX() + ", "
					+ at.getZ() + ". " + what2 + " Watch out: you can fight out there.").withStyle(what.chat, ChatFormatting.BOLD));
			p.level().playSound(null, p.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 1.0F, 0.8F);
		}
		dev.rubymod.RubyMod.LOGGER.info("World event: {} at {} (opens in {}s)", what.title, at.toShortString(), seconds);
		return null;
	}

	/** Somewhere between everyone's bases (their Hearts, or where they are), on dry land, clear of every claim. */
	public static BlockPos pickSpot(ServerLevel level, List<ServerPlayer> players) {
		SeasonData data = SeasonData.get();
		double cx = 0;
		double cz = 0;
		int n = 0;
		for (ServerPlayer p : players) {
			SeasonData.Claim claim = data.claimOf(p.getUUID());
			if (claim != null && !claim.destroyed) {
				cx += claim.x;
				cz += claim.z;
			} else {
				cx += p.getX();
				cz += p.getZ();
			}
			n++;
		}
		WorldBorder border = level.getWorldBorder();
		if (n == 0) {
			cx = border.getCenterX();
			cz = border.getCenterZ();
		} else {
			cx /= n;
			cz /= n;
		}
		RandomSource random = level.getRandom();
		BlockPos spawn = level.getRespawnData().pos();
		for (int tries = 0; tries < 60; tries++) {
			double r = 30 + random.nextDouble() * 70 + tries * 4;
			double a = random.nextDouble() * Math.PI * 2;
			int x = (int) Math.round(cx + Math.cos(a) * r);
			int z = (int) Math.round(cz + Math.sin(a) * r);
			if (Math.abs(x - border.getCenterX()) > border.getSize() / 2 - 40 || Math.abs(z - border.getCenterZ()) > border.getSize() / 2 - 40) {
				continue;
			}
			if (Math.abs(x - spawn.getX()) < 48 && Math.abs(z - spawn.getZ()) < 48) {
				continue;
			}
			boolean free = true;
			for (SeasonData.Claim claim : data.claims.values()) {
				if (!claim.destroyed && Math.abs(claim.x - x) < claim.radius() + 30 && Math.abs(claim.z - z) < claim.radius() + 30) {
					free = false;
					break;
				}
			}
			if (!free) {
				continue;
			}
			level.getChunk(x >> 4, z >> 4);
			BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
			if (level.getFluidState(top.below()).isEmpty() && level.getFluidState(top).isEmpty() && top.getY() > level.getMinY() + 5) {
				return top;
			}
		}
		return null;
	}

	/** A beam of light over the spot (seen from far off), and the shrine's ring. */
	private static void beam(ServerLevel level) {
		for (int y = 2; y < 70; y += 3) {
			level.sendParticles(ParticleTypes.END_ROD, true, true, at.getX() + 0.5, at.getY() + y, at.getZ() + 0.5, 1, 0.05, 0.4, 0.05, 0.0);
		}
		if (kind == Kind.SHRINE) {
			int dots = 36;
			for (int i = 0; i < dots; i++) {
				double a = i * Math.PI * 2 / dots;
				level.sendParticles(open ? (contested ? ParticleTypes.ANGRY_VILLAGER : ParticleTypes.WITCH) : ParticleTypes.END_ROD, true, false,
						at.getX() + 0.5 + Math.cos(a) * SHRINE_RADIUS, at.getY() + 0.3, at.getZ() + 0.5 + Math.sin(a) * SHRINE_RADIUS, 1, 0, 0.05, 0, 0);
			}
		}
	}

	private static void openUp(MinecraftServer server, List<ServerPlayer> players) {
		ServerLevel level = server.overworld();
		open = true;
		dev.rubymod.RubyMod.LOGGER.info("World event open: {} at {}", kind.title, at.toShortString());
		switch (kind) {
			case CACHE -> {
				buildCache(level);
				endsAt = now() + CACHE_OPEN;
				tell(players, Component.literal("The Supply Cache is open at " + at.getX() + ", " + at.getZ() + "!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
				level.playSound(null, at, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 4.0F, 0.6F);
			}
			case SHRINE -> {
				buildShrine(level);
				endsAt = now() + SHRINE_OPEN;
				tell(players, Component.literal("The Ruby Shrine is lit at " + at.getX() + ", " + at.getZ() + ". Hold it alone!")
						.withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
			}
			case METEOR -> {
				BlockPos c = BattleDirector.meteorAt(level, at);
				Waypoints.clearGlobal(at);
				kind = null; // the meteor says the rest itself, and its crater stays
				at = null;
				open = false;
				if (c == null) {
					dev.rubymod.RubyMod.LOGGER.warn("World event: the meteor couldn't come down");
				}
				return;
			}
		}
		for (ServerPlayer p : players) {
			p.level().playSound(null, p.blockPosition(), SoundEvents.FIREWORK_ROCKET_LARGE_BLAST_FAR, SoundSource.PLAYERS, 0.8F, 0.8F);
		}
	}

	// ----- Building and tidying -----

	/** Set a block, remembering what was there (once). */
	private static void put(ServerLevel level, BlockPos pos, BlockState state) {
		RESTORE.putIfAbsent(pos.immutable(), level.getBlockState(pos));
		level.setBlockAndUpdate(pos, state);
	}

	private static void platform(ServerLevel level, int radius) {
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				BlockPos floor = at.offset(dx, -1, dz);
				boolean edge = Math.abs(dx) == radius || Math.abs(dz) == radius;
				put(level, floor, edge ? Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState() : Blocks.POLISHED_BLACKSTONE.defaultBlockState());
				for (int y = 0; y < 4; y++) {
					BlockPos above = at.offset(dx, y, dz);
					if (!level.getBlockState(above).isAir()) {
						put(level, above, Blocks.AIR.defaultBlockState());
					}
				}
			}
		}
	}

	private static void buildCache(ServerLevel level) {
		RandomSource random = level.getRandom();
		platform(level, CACHE_RADIUS);
		int[][] ring = {{3, 0}, {-3, 0}, {0, 3}, {0, -3}, {2, 2}, {-2, -2}, {2, -2}, {-2, 2}};
		for (int[] c : new int[][] {{3, 3}, {-3, 3}, {3, -3}, {-3, -3}}) {
			put(level, at.offset(c[0], 0, c[1]), ModBlocks.RUBY_BLOCK.defaultBlockState());
			put(level, at.offset(c[0], 1, c[1]), Blocks.LANTERN.defaultBlockState());
		}
		CHESTS.clear();
		chest(level, at, BattleDirector.supplyLoot(random));
		for (int i = 0; i < 6; i++) {
			int[] c = ring[i];
			chest(level, at.offset(c[0], 0, c[1]), ringLoot(random));
		}
	}

	private static void chest(ServerLevel level, BlockPos pos, List<ItemStack> loot) {
		put(level, pos, Blocks.CHEST.defaultBlockState());
		if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
			RandomSource random = level.getRandom();
			for (ItemStack stack : loot) {
				int slot = random.nextInt(chest.getContainerSize());
				for (int k = 0; k < chest.getContainerSize() && !chest.getItem(slot).isEmpty(); k++) {
					slot = (slot + 1) % chest.getContainerSize();
				}
				chest.setItem(slot, stack);
			}
			CHESTS.add(pos.immutable());
		}
	}

	/** A ring chest: some rubies, and a weapon or what it shoots, or grenades, or a golden apple. */
	static List<ItemStack> ringLoot(RandomSource random) {
		List<ItemStack> loot = new ArrayList<>();
		loot.add(new ItemStack(ModItems.RUBY, 3 + random.nextInt(6)));
		Item[][] picks = {
				{ModItems.ARR_RIFLE, ModItems.HEAVY_AMMO},
				{ModItems.LONGSHOT_SNIPER, ModItems.SNIPER_ROUND},
				{ModItems.RUBY_BLASTER, ModItems.RUBY_CELL},
				{ModItems.ROCKET_LAUNCHER, ModItems.ROCKET}};
		switch (random.nextInt(4)) {
			case 0 -> {
				Item[] pick = picks[random.nextInt(picks.length)];
				ItemStack gun = new ItemStack(pick[0]);
				if (pick[0] instanceof GunItem g) {
					gun.set(ModComponents.AMMO, g.stats().magazine());
				}
				loot.add(gun);
				loot.add(new ItemStack(pick[1], Math.min(pick[1].getDefaultMaxStackSize(), 6 + random.nextInt(10))));
			}
			case 1 -> {
				Item[] pick = picks[random.nextInt(picks.length)];
				loot.add(new ItemStack(pick[1], Math.min(pick[1].getDefaultMaxStackSize(), 12 + random.nextInt(16))));
			}
			case 2 -> loot.add(new ItemStack(random.nextBoolean() ? ModItems.FRAG_GRENADE : ModItems.SMOKE_GRENADE, 2 + random.nextInt(3)));
			default -> loot.add(new ItemStack(Items.GOLDEN_APPLE, 1 + random.nextInt(2)));
		}
		return loot;
	}

	private static void buildShrine(ServerLevel level) {
		platform(level, 2);
		put(level, at.below(), ModBlocks.RUBY_BLOCK.defaultBlockState());
		put(level, at.offset(2, 0, 2), Blocks.AMETHYST_CLUSTER.defaultBlockState());
		put(level, at.offset(-2, 0, 2), Blocks.AMETHYST_CLUSTER.defaultBlockState());
		put(level, at.offset(2, 0, -2), Blocks.AMETHYST_CLUSTER.defaultBlockState());
		put(level, at.offset(-2, 0, -2), Blocks.AMETHYST_CLUSTER.defaultBlockState());
	}

	/** Over: put the ground back as it was (what's left in the cache goes with it). */
	private static void finish(MinecraftServer server, List<ServerPlayer> players, String message) {
		ServerLevel level = server.overworld();
		dev.rubymod.RubyMod.LOGGER.info("World event over: {} ({})", kind == null ? "-" : kind.title, message.isEmpty() ? "ended" : message);
		for (BlockPos pos : CHESTS) {
			if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
				chest.clearContent();
			}
		}
		List<Map.Entry<BlockPos, BlockState>> undo = new ArrayList<>(RESTORE.entrySet());
		java.util.Collections.reverse(undo);
		for (var e : undo) {
			level.setBlockAndUpdate(e.getKey(), e.getValue());
		}
		if (!RESTORE.isEmpty() && at != null) {
			level.sendParticles(ParticleTypes.POOF, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 40, 2, 0.5, 2, 0.02);
		}
		RESTORE.clear();
		CHESTS.clear();
		if (at != null) {
			Waypoints.clearGlobal(at);
		}
		if (!message.isEmpty()) {
			tell(players, Component.literal(message).withStyle(ChatFormatting.GRAY));
		}
		for (ServerPlayer p : players) {
			dev.rubymod.net.Net.send(p, new dev.rubymod.net.MorePayloads.EventStatus("", 0));
		}
		kind = null;
		at = null;
		open = false;
	}

	// ----- While it's on -----

	private static boolean near(ServerPlayer p, int radius) {
		return p.level() == p.level().getServer().overworld() && Math.abs(p.getX() - (at.getX() + 0.5)) <= radius + 0.5
				&& Math.abs(p.getZ() - (at.getZ() + 0.5)) <= radius + 0.5 && Math.abs(p.getY() - at.getY()) < 4;
	}

	private static void tickCache(MinecraftServer server, List<ServerPlayer> players, long t) {
		for (ServerPlayer p : players) {
			if (near(p, CACHE_RADIUS + 2) && CAME.add(p.getUUID())) {
				Goals.add(server, p.getUUID(), "cache", 1);
			}
		}
		if (chestsLeft(server.overworld()) == 0 && endsAt - t > 30_000L) {
			endsAt = t + 30_000L; // all taken: it goes soon after
			warnedEnd = true;
		}
	}

	private static int chestsLeft(ServerLevel level) {
		int left = 0;
		for (BlockPos pos : CHESTS) {
			if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest && !chest.isEmpty()) {
				left++;
			}
		}
		return left;
	}

	private static void tickShrine(MinecraftServer server, List<ServerPlayer> players) {
		List<ServerPlayer> in = new ArrayList<>();
		for (ServerPlayer p : players) {
			if (near(p, SHRINE_RADIUS) && p.isAlive()) {
				in.add(p);
				p.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false));
			}
		}
		contested = in.size() > 1;
		UUID was = holder;
		holder = in.size() == 1 ? in.get(0).getUUID() : null;
		if (holder != null) {
			ServerPlayer p = in.get(0);
			int held = HELD.merge(holder, 1, Integer::sum);
			if (held % SHRINE_EVERY == 0) {
				Grid.deposit(holder, 1000L);
				p.sendOverlayMessage(Component.literal("+1 ruby: you hold the Ruby Shrine").withStyle(ChatFormatting.LIGHT_PURPLE));
				p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 1.4F);
			}
			if (!holder.equals(was)) {
				tell(players, Component.literal(p.getName().getString() + " holds the Ruby Shrine!").withStyle(ChatFormatting.LIGHT_PURPLE));
			}
		}
	}

	/** The shrine's over: whoever held it longest gets the bonus. */
	private static String shrineResult(MinecraftServer server, List<ServerPlayer> players) {
		UUID best = null;
		int most = 0;
		for (var e : HELD.entrySet()) {
			if (e.getValue() > most) {
				best = e.getKey();
				most = e.getValue();
			}
		}
		if (best == null) {
			return "The Ruby Shrine went dark. Nobody held it.";
		}
		Grid.deposit(best, SHRINE_BONUS * 1000L);
		Goals.add(server, best, "shrine", 1);
		var record = SeasonData.get().players.get(best.toString());
		String name = record == null || record.name.isEmpty() ? "Someone" : record.name;
		ServerPlayer winner = server.getPlayerList().getPlayer(best);
		if (winner != null) {
			winner.level().playSound(null, winner.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8F, 1.0F);
		}
		return "The Ruby Shrine went dark. " + name + " held it longest (" + most + "s) and gets " + SHRINE_BONUS + " bonus rubies!";
	}

	/** The banner along the top: what, where, how far, and how long. */
	private static void status(List<ServerPlayer> players, long t) {
		if (kind == null) {
			return;
		}
		for (ServerPlayer p : players) {
			String where;
			if (p.level() == p.level().getServer().overworld()) {
				double dx = at.getX() + 0.5 - p.getX();
				double dz = at.getZ() + 0.5 - p.getZ();
				int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
				where = dist < 12 ? "here" : dist + " m " + compass(dx, dz);
			} else {
				where = at.getX() + ", " + at.getZ();
			}
			String text;
			if (!open) {
				text = kind.title.toUpperCase(java.util.Locale.ROOT) + " in " + clock(opensAt - t) + "   " + where;
			} else if (kind == Kind.CACHE) {
				int left = chestsLeft(p.level().getServer().overworld());
				text = "SUPPLY CACHE   " + left + (left == 1 ? " chest" : " chests") + " left   " + where + "   " + clock(endsAt - t);
			} else {
				String who = contested ? "CONTESTED" : holder == null ? "nobody holds it" : holder.equals(p.getUUID()) ? "you hold it"
						: "held by " + name(p.level().getServer(), holder);
				text = "RUBY SHRINE   " + who + "   " + where + "   " + clock(endsAt - t);
			}
			dev.rubymod.net.Net.send(p, new dev.rubymod.net.MorePayloads.EventStatus(text, kind.color));
		}
	}

	private static String name(MinecraftServer server, UUID id) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		return p == null ? "someone" : p.getName().getString();
	}

	private static String clock(long ms) {
		long s = Math.max(0, (ms + 999) / 1000);
		return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
	}

	private static String compass(double dx, double dz) {
		double angle = Math.toDegrees(Math.atan2(dz, dx));
		String[] names = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
		return names[Math.floorMod(Math.round((float) angle / 45.0F), 8)];
	}

	private static void tell(List<ServerPlayer> players, Component message) {
		for (ServerPlayer p : players) {
			p.sendSystemMessage(message);
		}
	}

	// ----- For tests and admins -----

	/** End whatever's on now (ground put back). */
	public static void stop(MinecraftServer server) {
		if (kind != null) {
			finish(server, inRubyWars(server), "");
		}
	}

	/** Move the clock on (tests). */
	public static void advance(long ms) {
		skew += ms;
	}

	public static int chestsLeftForTest(ServerLevel level) {
		return chestsLeft(level);
	}

	public static void reset() {
		RESTORE.clear();
		CHESTS.clear();
		CAME.clear();
		HELD.clear();
		kind = null;
		at = null;
		open = false;
		played = 0;
		rotation = 0;
		skew = 0;
	}
}
