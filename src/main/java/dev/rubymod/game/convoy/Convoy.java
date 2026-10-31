package dev.rubymod.game.convoy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.VehicleEntity;
import dev.rubymod.game.Alerts;
import dev.rubymod.game.Duels;
import dev.rubymod.game.Hub;
import dev.rubymod.game.SpawnShield;
import dev.rubymod.game.Stats;
import dev.rubymod.game.rift.RiftFx;
import dev.rubymod.gun.GunItem;
import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Ruby Convoy: a co-op escort for 1-4 players. The Ruby Truck sets off from the Ruby Mine
 * with a load of rubies, bound for the vault behind the Iron Fortress, through five stages
 * of hostile country (see ConvoyRoute). Keep it moving and keep it alive:
 *
 * <ul>
 * <li>It only drives while someone is close to it, and stops while enemies crowd it.
 * It never rolls back.</li>
 * <li>Crouch beside it to repair it (Mechanics repair three times as fast). Two seats on
 * top: the roof gun (hit harder, take less) and the cargo bed.</li>
 * <li>At each depot: repairs, new vehicles (jeeps, tanks, a helicopter), and everyone picks
 * an upgrade for the convoy.</li>
 * <li>Go down and you're back on the truck eight seconds later. If the truck is wrecked,
 * a spare one starts the stage again from its depot; run out of spares and the job's
 * failed.</li>
 * </ul>
 *
 * Your Ruby Wars things are safe in the hub's save the whole time; nothing comes back.
 */
public final class Convoy {
	public static final String TAG = "rubymod_convoy";
	static final String SEAT_TAG = TAG + "_seat";
	static final String BOSS_TAG = TAG + "_boss";
	static final String VEHICLE_TAG = TAG + "_vehicle";
	private static final int STAGING_TICKS = 60 * 20;
	private static final int DEPOT_TICKS = 45 * 20;
	private static final int BACK_TICKS = 8 * 20;
	private static final int SPARES = 3;
	/** Where the truck parks in the Ruby Mine and in each depot. */
	private static final int PARK = 12;

	enum State { IDLE, STAGING, ROLLING, DEPOT, WRECKED, OVER }

	static final class Crew {
		final UUID id;
		final String name;
		ConvoyUpgrade.Role role;
		boolean alive = true;
		long backAt;
		int kills;
		float repaired;
		List<ConvoyUpgrade> offer = List.of();
		boolean picked;

		Crew(UUID id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	static final Map<UUID, Crew> CREW = new LinkedHashMap<>();
	static final Set<UUID> MOBS = new HashSet<>();
	static final ArrayDeque<Runnable> WORK = new ArrayDeque<>();
	private static final Map<ConvoyUpgrade, Integer> UPGRADES = new EnumMap<>(ConvoyUpgrade.class);
	private static final List<UUID> VEHICLES = new ArrayList<>();
	private static final List<long[]> HELD = new ArrayList<>();
	private static State state = State.IDLE;
	private static int timer;
	static ConvoyTruck truck;
	static int stage;
	/** Depots reached this run (the truck stops at each once). */
	private static final boolean[] VISITED = new boolean[ConvoyRoute.DEPOT.length];
	private static int spares;
	static boolean bossBeaten;
	private static Dreadnought boss;
	private static long seed;
	private static long ticks;
	/** The first stretch is built: the run can start. */
	private static boolean ready;
	private static boolean won;
	private static long runStart;
	private static int wrecks;
	/** What the truck's doing (for the HUD). */
	private static int status;
	private static boolean hudSoon;

	private Convoy() {
	}

	// ----- Who's in -----

	public static boolean playing(Player player) {
		return CREW.containsKey(player.getUUID());
	}

	public static boolean isEnemy(Entity e) {
		return e != null && MOBS.contains(e.getUUID());
	}

	static boolean isBoss(Entity e) {
		return e != null && e.entityTags().contains(BOSS_TAG);
	}

	/** The truck's blocks and seats (bullets go straight through them). */
	public static boolean isTruckPart(Entity e) {
		return ConvoyTruck.isPart(e);
	}

	/** The crew never hurt each other (null: not a Convoy matter). */
	public static Boolean canHurt(Player a, Player b) {
		return playing(a) || playing(b) ? Boolean.FALSE : null;
	}

	/** Nobody can break the road. */
	public static boolean protects(Level level, BlockPos pos) {
		return level.dimension() == Duels.ARENA && Math.abs(pos.getX() - ConvoyRoute.X0) <= 200;
	}

	/** The crew can't hurt their own vehicles (or each other's). */
	public static boolean allowDamage(LivingEntity entity, DamageSource source) {
		if (entity.entityTags().contains(VEHICLE_TAG) && source.getEntity() instanceof ServerPlayer p && playing(p)) {
			return false;
		}
		return true;
	}

	static List<ServerPlayer> players(MinecraftServer server) {
		List<ServerPlayer> out = new ArrayList<>();
		for (UUID id : CREW.keySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) {
				out.add(p);
			}
		}
		return out;
	}

	/** The crew who are up and about (the ones enemies go for). */
	private static List<ServerPlayer> active(MinecraftServer server) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : players(server)) {
			Crew c = CREW.get(p.getUUID());
			if (c != null && c.alive && !p.isSpectator()) {
				out.add(p);
			}
		}
		return out;
	}

	static ServerLevel level(MinecraftServer server) {
		return server.getLevel(Duels.ARENA);
	}

	static int level(ConvoyUpgrade u) {
		return UPGRADES.getOrDefault(u, 0);
	}

	/** Bigger teams: the truck takes a little less from each hit (it's got more defenders, and more coming at it). */
	static float damageScale() {
		return 1.0F;
	}

	public static String status() {
		return switch (state) {
			case IDLE -> "Nobody on the road";
			case STAGING -> CREW.size() + " loading the truck";
			case OVER -> won ? "Rubies delivered!" : "Job over";
			default -> CREW.size() + " on the road  •  " + ConvoyRoute.NAMES[stage];
		};
	}

	public static boolean running() {
		return state != State.IDLE && state != State.STAGING;
	}

	/** For the hub's sign. */
	public static String hubStatus() {
		return switch (state) {
			case IDLE -> "Nobody on the road";
			case STAGING -> CREW.size() + " getting ready: join in!";
			default -> CREW.size() + " playing  •  " + ConvoyRoute.NAMES[stage];
		};
	}

	public static String canJoin(ServerPlayer player) {
		if (CREW.size() >= 4) {
			return "The convoy is full (4 players).";
		}
		return null;
	}

	public static void join(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Crew c = new Crew(player.getUUID(), player.getName().getString());
		CREW.put(player.getUUID(), c);
		player.getInventory().clearContent();
		player.removeAllEffects();
		if (state == State.IDLE) {
			startStaging(server);
		}
		if (ready && truck != null) {
			place(player);
		} else {
			hold(player);
		}
		offerRoles(player);
		tell(server, Component.literal(player.getName().getString() + " joined the Ruby Convoy.").withStyle(ChatFormatting.GOLD));
		player.sendSystemMessage(Component.literal("RUBY CONVOY: get the Ruby Truck to the vault! It only drives while you stay close to it, and stops "
				+ "while enemies crowd it. Crouch next to it to repair it. Right-click the top of it to ride. H or /hub to leave.")
				.withStyle(ChatFormatting.GOLD));
	}

	public static void leave(ServerPlayer player) {
		Crew c = CREW.remove(player.getUUID());
		if (c == null) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		player.stopRiding();
		player.getInventory().clearContent();
		player.removeAllEffects();
		Net.send(player, new MorePayloads.ConvoyHud("", List.of(), "", List.of()));
		Net.send(player, new MorePayloads.ConvoyCards("close", "", List.of()));
		if (CREW.isEmpty()) {
			stop(server);
		}
	}

	public static void onDisconnect(ServerPlayer player) {
		if (playing(player)) {
			leave(player);
		}
	}

	private static void tell(MinecraftServer server, Component message) {
		for (ServerPlayer p : players(server)) {
			p.sendSystemMessage(message);
		}
	}

	private static void titleAll(MinecraftServer server, String title, String subtitle, ChatFormatting color) {
		for (ServerPlayer p : players(server)) {
			Alerts.title(p, title, subtitle, color);
		}
	}

	/** Everyone out: tidy up. */
	private static void stop(MinecraftServer server) {
		ServerLevel level = level(server);
		clearWorld(level);
		release(level);
		WORK.clear();
		state = State.IDLE;
		ready = false;
	}

	private static void clearWorld(ServerLevel level) {
		for (UUID id : new ArrayList<>(MOBS)) {
			Entity e = level.getEntity(id);
			if (e != null) {
				e.discard();
			}
		}
		MOBS.clear();
		ConvoyEnemies.clear();
		ConvoyEvents.clear(level);
		if (boss != null) {
			boss.remove(level);
			boss = null;
		}
		if (truck != null) {
			truck.remove(level);
			truck = null;
		}
		clearVehicles(level, true);
		// Anything of ours left lying around (after a crash, say).
		for (Entity e : level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(ConvoyRoute.X0 - 80, 0, ConvoyRoute.BUILD_FROM - 40,
				ConvoyRoute.X0 + 80, 200, ConvoyRoute.BUILD_TO + 40), e -> e.entityTags().contains(TAG) && !(e instanceof Player))) {
			e.discard();
		}
	}

	/**
	 * After a restart: nobody's on the road, so whatever of the convoy was saved with the world (the truck, monsters)
	 * goes as it loads, and the road's chunks are let go.
	 */
	public static boolean stale(Entity e) {
		return state == State.IDLE && e.entityTags().contains(TAG);
	}

	public static void cleanup(MinecraftServer server) {
		ServerLevel level = level(server);
		if (level == null) {
			return;
		}
		int cx = ConvoyRoute.X0 >> 4;
		for (int x = cx - 4; x <= cx + 4; x++) {
			for (int z = (ConvoyRoute.BUILD_FROM >> 4) - 1; z <= (ConvoyRoute.BUILD_TO >> 4) + 1; z++) {
				level.setChunkForced(x, z, false);
			}
		}
	}

	public static void reset() {
		CREW.clear();
		MOBS.clear();
		WORK.clear();
		HELD.clear();
		VEHICLES.clear();
		UPGRADES.clear();
		state = State.IDLE;
		truck = null;
		boss = null;
		ready = false;
		ConvoyEnemies.clear();
	}

	// ----- Keeping the road loaded where the truck is -----

	private static void hold(ServerLevel level) {
		if (truck == null) {
			return;
		}
		Set<Long> want = new HashSet<>();
		int cx = ConvoyRoute.X0 >> 4;
		for (int x = cx - 3; x <= cx + 3; x++) {
			for (int z = ((int) truck.z - 24) >> 4; z <= ((int) truck.z + 56) >> 4; z++) {
				want.add(((long) x << 32) | (z & 0xFFFFFFFFL));
			}
		}
		for (var it = HELD.iterator(); it.hasNext();) {
			long[] c = it.next();
			long key = (c[0] << 32) | (c[1] & 0xFFFFFFFFL);
			if (!want.remove(key)) {
				level.setChunkForced((int) c[0], (int) c[1], false);
				it.remove();
			}
		}
		for (long key : want) {
			int x = (int) (key >> 32);
			int z = (int) key;
			level.setChunkForced(x, z, true);
			HELD.add(new long[] {x, z});
		}
	}

	private static void release(ServerLevel level) {
		for (long[] c : HELD) {
			level.setChunkForced((int) c[0], (int) c[1], false);
		}
		HELD.clear();
	}

	// ----- Placing people -----

	/** Out of the way while the road is being built. */
	private static void hold(ServerPlayer player) {
		ServerLevel level = level(player.level().getServer());
		player.teleportTo(level, ConvoyRoute.X0 + 0.5, ConvoyRoute.Y + 30, 10.5, Set.<Relative>of(), 0, 30, true);
		player.setGameMode(GameType.SPECTATOR);
	}

	/** Beside the truck, on your feet. */
	static void place(ServerPlayer player) {
		ServerLevel level = level(player.level().getServer());
		Crew c = CREW.get(player.getUUID());
		var random = player.getRandom();
		Vec3 at = truck == null ? new Vec3(ConvoyRoute.X0 + 0.5, ConvoyRoute.Y + 1, PARK) : truck.local((random.nextBoolean() ? 1 : -1) * (2.6 + random.nextDouble()),
				0, -1 + random.nextInt(4));
		player.stopRiding();
		player.teleportTo(level, at.x, Hub.safeY(level, at.x, at.y, at.z), at.z, Set.<Relative>of(), truck == null ? 0 : truck.forward().z > 0 ? 0 : 180, 0,
				true);
		player.setGameMode(GameType.ADVENTURE);
		player.clearFire();
		player.resetFallDistance();
		player.setHealth(player.getMaxHealth());
		SpawnShield.grant(player);
		if (c != null) {
			c.alive = true;
		}
	}

	private static ItemStack gun(Item item) {
		ItemStack s = new ItemStack(item);
		if (item instanceof GunItem g) {
			s.set(ModComponents.AMMO, g.stats().magazine());
		}
		return s;
	}

	/** Your kit for your job (ammo never runs out on the convoy). */
	private static void kit(ServerPlayer player, ConvoyUpgrade.Role role) {
		var inv = player.getInventory();
		inv.clearContent();
		switch (role) {
			case GUNNER -> {
				inv.add(gun(ModItems.ARR_RIFLE));
				inv.add(gun(ModItems.COMBAT_SHOTGUN));
			}
			case DEMOLITION -> {
				inv.add(new ItemStack(ModItems.ROCKET_LAUNCHER));
				inv.add(gun(ModItems.SERVICE_PISTOL));
			}
			case MARKSMAN -> {
				inv.add(gun(ModItems.LONGSHOT_SNIPER));
				inv.add(gun(ModItems.SIX_SHOOTER));
			}
			case MECHANIC -> {
				inv.add(gun(ModItems.RUBY_BLASTER));
				inv.add(gun(ModItems.COMBAT_SHOTGUN));
			}
		}
		inv.add(new ItemStack(ModItems.RUBY_SWORD));
		inv.add(new ItemStack(ModItems.FRAG_GRENADE, role == ConvoyUpgrade.Role.DEMOLITION ? 10 : 4));
		inv.add(new ItemStack(ModItems.SMOKE_GRENADE, 2));
		if (level(ConvoyUpgrade.HEAVY) > 0 && CREW.get(player.getUUID()) != null) {
			inv.add(new ItemStack(ModItems.FRAG_GRENADE, 4));
		}
		if (level(ConvoyUpgrade.JETPACKS) > 0) {
			player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.JETPACK));
		} else {
			player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
		}
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
		player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
		inv.setSelectedSlot(0);
		player.inventoryMenu.broadcastChanges();
	}

	/** Topped up at a depot: grenades back, flares if there's air support. */
	private static void resupply(ServerPlayer player) {
		Crew c = CREW.get(player.getUUID());
		int frags = c != null && c.role == ConvoyUpgrade.Role.DEMOLITION ? 10 : 4;
		frags += level(ConvoyUpgrade.HEAVY) * 3;
		int have = player.getInventory().countItem(ModItems.FRAG_GRENADE);
		if (have < frags) {
			player.getInventory().add(new ItemStack(ModItems.FRAG_GRENADE, frags - have));
		}
		if (player.getInventory().countItem(ModItems.SMOKE_GRENADE) < 2) {
			player.getInventory().add(new ItemStack(ModItems.SMOKE_GRENADE, 2 - player.getInventory().countItem(ModItems.SMOKE_GRENADE)));
		}
		if (level(ConvoyUpgrade.AIRSTRIKE) > 0) {
			player.getInventory().add(new ItemStack(ModItems.AIR_STRIKE_FLARE));
		}
		player.setHealth(player.getMaxHealth());
	}

	// ----- Picking cards -----

	private static void offerRoles(ServerPlayer player) {
		List<String> cards = new ArrayList<>();
		for (ConvoyUpgrade.Role r : ConvoyUpgrade.Role.values()) {
			cards.add(r.card());
		}
		Net.send(player, new MorePayloads.ConvoyCards("role", "PICK YOUR JOB", cards));
	}

	private static void offerUpgrades(ServerPlayer player, Crew c, Random random) {
		List<ConvoyUpgrade> pool = new ArrayList<>();
		for (ConvoyUpgrade u : ConvoyUpgrade.values()) {
			if (level(u) < u.max) {
				pool.add(u);
			}
		}
		java.util.Collections.shuffle(pool, random);
		c.offer = new ArrayList<>(pool.subList(0, Math.min(3, pool.size())));
		c.picked = c.offer.isEmpty();
		List<String> cards = new ArrayList<>();
		for (ConvoyUpgrade u : c.offer) {
			cards.add(u.card(level(u)));
		}
		Net.send(player, new MorePayloads.ConvoyCards("upgrade", "DEPOT " + stage + ": UPGRADE THE CONVOY", cards));
	}

	private static int stageNumber() {
		return stage + 1;
	}

	/** A card was clicked. */
	public static void pick(ServerPlayer player, String kind, String id) {
		Crew c = CREW.get(player.getUUID());
		if (c == null) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		if (kind.equals("role")) {
			ConvoyUpgrade.Role role = ConvoyUpgrade.Role.byId(id);
			if (role == null) {
				return;
			}
			c.role = role;
			kit(player, role);
			Net.send(player, new MorePayloads.ConvoyCards("close", "", List.of()));
			player.sendSystemMessage(Component.literal("You're the convoy's " + role.title + ". " + role.about).withStyle(ChatFormatting.GOLD));
			if (state == State.STAGING && CREW.values().stream().allMatch(x -> x.role != null) && ready) {
				timer = Math.min(timer, 6 * 20);
			}
			hudSoon = true;
			return;
		}
		if (kind.equals("upgrade") && !c.picked) {
			ConvoyUpgrade u = ConvoyUpgrade.byId(id);
			if (u == null || !c.offer.contains(u)) {
				return;
			}
			c.picked = true;
			c.offer = List.of();
			apply(server, player, u);
			Net.send(player, new MorePayloads.ConvoyCards("close", "", List.of()));
			tell(server, Component.literal(player.getName().getString() + " fitted " + u.title + ".").withStyle(ChatFormatting.GOLD));
			if (state == State.DEPOT && CREW.values().stream().allMatch(x -> x.picked)) {
				timer = Math.min(timer, 5 * 20);
			}
			hudSoon = true;
		}
	}

	private static void apply(MinecraftServer server, ServerPlayer picker, ConvoyUpgrade u) {
		UPGRADES.merge(u, 1, Integer::sum);
		switch (u) {
			case PLATING -> {
				if (truck != null) {
					truck.maxHp *= 1.25F;
					truck.hp = truck.maxHp;
				}
			}
			case SPARE -> spares++;
			case AIRSTRIKE -> {
				for (ServerPlayer p : players(server)) {
					p.getInventory().add(new ItemStack(ModItems.AIR_STRIKE_FLARE));
				}
			}
			case JETPACKS -> {
				for (ServerPlayer p : players(server)) {
					p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.JETPACK));
				}
			}
			case HEAVY -> {
				picker.getInventory().add(gun(ModItems.RUBY_MINIGUN));
				for (ServerPlayer p : players(server)) {
					p.getInventory().add(new ItemStack(ModItems.FRAG_GRENADE, 3));
				}
			}
			default -> {
			}
		}
	}

	/** A supply cache: something good for the team. Returns what it was. */
	static String openCache(ServerLevel level, ServerPlayer opener) {
		MinecraftServer server = level.getServer();
		int roll = level.getRandom().nextInt(4);
		switch (roll) {
			case 0 -> {
				truck.repair(truck.maxHp * 0.35F);
				return "repair kits (the truck is patched up)";
			}
			case 1 -> {
				for (ServerPlayer p : players(server)) {
					p.getInventory().add(new ItemStack(ModItems.AIR_STRIKE_FLARE));
				}
				return "air strike flares for everyone";
			}
			case 2 -> {
				for (ServerPlayer p : players(server)) {
					p.getInventory().add(new ItemStack(ModItems.FRAG_GRENADE, 4));
					p.getInventory().add(new ItemStack(Items.GOLDEN_APPLE, 2));
				}
				return "grenades and golden apples";
			}
			default -> {
				opener.getInventory().add(gun(ModItems.RUBY_MINIGUN));
				return "a Ruby Minigun";
			}
		}
	}

	// ----- The run -----

	private static void startStaging(MinecraftServer server) {
		ServerLevel level = level(server);
		clearWorld(level);
		state = State.STAGING;
		timer = STAGING_TICKS;
		ready = false;
		won = false;
		bossBeaten = false;
		stage = 0;
		spares = SPARES;
		wrecks = 0;
		UPGRADES.clear();
		java.util.Arrays.fill(VISITED, false);
		seed = level.getRandom().nextLong();
		ConvoyRoute.reroll(new Random(seed));
		// The mine and the first stage first (the run can start then); the rest keeps building behind.
		ConvoyRoute.build(WORK, level, ConvoyRoute.BUILD_FROM, ConvoyRoute.DEPOT[1], seed);
		WORK.add(() -> firstStretchBuilt(server));
		for (int i = 1; i < ConvoyRoute.DEPOT.length; i++) {
			ConvoyRoute.build(WORK, level, ConvoyRoute.DEPOT[i], i + 1 < ConvoyRoute.DEPOT.length ? ConvoyRoute.DEPOT[i + 1] : ConvoyRoute.BUILD_TO, seed);
		}
		for (Crew c : CREW.values()) {
			c.role = null;
			c.picked = false;
			c.kills = 0;
			c.repaired = 0;
		}
	}

	private static void firstStretchBuilt(MinecraftServer server) {
		ServerLevel level = level(server);
		ready = true;
		truck = ConvoyTruck.spawn(level, PARK, 300 * (1 + 0.3F * Math.max(0, CREW.size() - 1)));
		hold(level);
		for (ServerPlayer p : players(server)) {
			place(p);
			Alerts.title(p, "RUBY CONVOY", "Pick your job, then get the rubies to the vault!", ChatFormatting.GOLD);
			if (CREW.get(p.getUUID()).role == null) {
				offerRoles(p);
			}
		}
		spawnVehicles(level, 0);
		if (CREW.values().stream().allMatch(x -> x.role != null)) {
			timer = Math.min(timer, 6 * 20);
		}
	}

	public static void tick(MinecraftServer server) {
		ticks++;
		if (!WORK.isEmpty()) {
			long end = System.nanoTime() + (state == State.STAGING && !ready ? 12_000_000L : 4_000_000L);
			while (!WORK.isEmpty() && System.nanoTime() < end) {
				WORK.poll().run();
			}
		}
		if (state == State.IDLE) {
			return;
		}
		ServerLevel level = level(server);
		List<ServerPlayer> crew = players(server);
		tickBack(server, level);
		if (ticks % 20 == 0) {
			for (ServerPlayer p : crew) {
				Crew c = CREW.get(p.getUUID());
				if (c != null && c.role == ConvoyUpgrade.Role.GUNNER) {
					p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 40, 0, false, false));
				}
				if (truck != null && truck.onRoof(p)) {
					ConvoyTruck.gunnerBuff(p);
				}
				p.getFoodData().setFoodLevel(20);
			}
		}
		switch (state) {
			case STAGING -> {
				if (ready) {
					timer--;
					status = 5;
					if (timer <= 0) {
						depart(server, level);
					}
				}
			}
			case ROLLING -> tickRolling(server, level);
			case DEPOT -> {
				status = 4;
				timer--;
				truck.tick(level, false, active(server));
				tickRepairs(level, active(server));
				if (timer <= 0) {
					leaveDepot(server, level);
				}
			}
			case WRECKED -> {
				status = 6;
				if (--timer <= 0) {
					retry(server, level);
				}
			}
			case OVER -> {
				if (won && timer % 10 == 0) {
					fireworks(level);
				}
				if (--timer <= 0) {
					startStaging(server);
					for (ServerPlayer p : players(server)) {
						hold(p);
						Net.send(p, new MorePayloads.ConvoyCards("close", "", List.of()));
					}
				}
			}
			default -> {
			}
		}
		if (state != State.IDLE && ticks % 10 == 0) {
			keepInBounds(server, level);
		}
		if (ticks % 40 == 0) {
			hold(level);
		}
		if (ticks % 5 == 0 || hudSoon) {
			hudSoon = false;
			syncHud(server, level);
		}
	}

	private static void depart(MinecraftServer server, ServerLevel level) {
		for (ServerPlayer p : players(server)) {
			Crew c = CREW.get(p.getUUID());
			if (c.role == null) {
				c.role = ConvoyUpgrade.Role.GUNNER;
				kit(p, c.role);
				Net.send(p, new MorePayloads.ConvoyCards("close", "", List.of()));
			}
			if (p.isSpectator() && c.alive) {
				place(p);
			}
		}
		VISITED[0] = true;
		stage = 0;
		runStart = ticks;
		state = State.ROLLING;
		ConvoyEvents.startStage(level, 0, truck.z, level.getRandom());
		titleAll(server, "ROLL OUT!", "Stage 1: " + ConvoyRoute.NAMES[0], ChatFormatting.GOLD);
		tell(server, Component.literal("Stage 1: " + ConvoyRoute.NAMES[0] + ". " + ConvoyRoute.BLURBS[0]).withStyle(ChatFormatting.GOLD));
		level.playSound(null, BlockPos.containing(truck.position()), SoundEvents.RAID_HORN.value(), SoundSource.NEUTRAL, 2.0F, 1.4F);
	}

	private static void tickRolling(MinecraftServer server, ServerLevel level) {
		List<ServerPlayer> targets = active(server);
		// Who's close, and is anything crowding it?
		int near = 0;
		for (ServerPlayer p : targets) {
			if (truck.distanceTo(p.position()) < 9 || truck.riding(p)) {
				near++;
			}
		}
		boolean contested = false;
		boolean ram = level(ConvoyUpgrade.RAM) > 0;
		for (UUID id : MOBS) {
			if (level.getEntity(id) instanceof Mob m && m.isAlive() && !isBoss(m) && !(m instanceof AircraftEntity) && truck.distanceTo(m.position()) < 6) {
				ConvoyEnemies.Job job = ConvoyEnemies.job(m);
				if (!ram || job == ConvoyEnemies.Job.GUNMAN || job == ConvoyEnemies.Job.ROCKETEER || job == ConvoyEnemies.Job.BRUTE) {
					contested = true;
					break;
				}
			}
		}
		double limit = ConvoyEvents.stopAt(level, stage);
		int next = stage + 1;
		if (next < ConvoyRoute.DEPOT.length && !VISITED[next]) {
			limit = Math.min(limit, ConvoyRoute.DEPOT[next] + PARK);
		}
		if (stage == 4 && bossBeaten) {
			limit = ConvoyRoute.FINISH;
		}
		boolean blocked = truck.z >= limit - 0.05;
		boolean moving = near > 0 && !contested && !blocked;
		if (moving) {
			double speed = 0.07 * (1 + 0.12 * (near - 1)) * (1 + 0.25 * level(ConvoyUpgrade.ENGINE));
			truck.z = Math.min(limit, truck.z + speed);
			truck.place(level);
			// Nudge anyone standing where it's going.
			for (ServerPlayer p : targets) {
				if (!truck.riding(p) && truck.touches(p.position(), 0.2)) {
					Vec3 side = p.position().subtract(truck.position()).multiply(1, 0, 1);
					Vec3 push = new Vec3(side.x, 0, 0).normalize().scale(0.3);
					p.push(push.x, 0.05, 0.12);
					p.syncVelocity = true;
				}
			}
		}
		status = moving ? 0 : blocked && limit < ConvoyRoute.FINISH ? 3 : contested ? 2 : near == 0 ? 1 : 3;
		truck.tick(level, moving, targets);
		tickRepairs(level, targets);
		ConvoyEnemies.tick(level, truck, targets, ticks);
		ConvoyEvents.tick(level, stage, truck, targets, near > 0, ticks);
		// The fortress: the Dreadnought waits in the courtyard.
		if (stage == 4 && !bossBeaten && boss == null && truck.z >= ConvoyRoute.COURTYARD - 14) {
			boss = Dreadnought.spawn(level, Math.max(1, targets.size()));
			titleAll(server, "THE IRON DREADNOUGHT", "Bring it down to open the vault!", ChatFormatting.DARK_RED);
			level.playSound(null, BlockPos.containing(truck.position()), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1.5F, 0.6F);
		}
		if (boss != null) {
			boss.tick(level, truck, targets);
			if (boss.dead()) {
				boss = null;
				bossBeaten = true;
				ConvoyRoute.openGate(level);
				Vec3 gate = new Vec3(ConvoyRoute.cx(ConvoyRoute.GATE), ConvoyRoute.Y + 3, ConvoyRoute.GATE);
				level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, gate.x, gate.y, gate.z, 3, 2, 1, 1, 0);
				level.playSound(null, BlockPos.containing(gate), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 3.0F, 0.5F);
				for (UUID id : new ArrayList<>(MOBS)) {
					if (level.getEntity(id) instanceof LivingEntity left) {
						RiftFx.burst(level, left.getBoundingBox().getCenter(), 0xFFFFC02E, 12, 0.4);
						left.discard();
					}
				}
				MOBS.clear();
				titleAll(server, "THE GATE IS OPEN!", "Roll the rubies into the vault!", ChatFormatting.GOLD);
			}
		}
		if (next < ConvoyRoute.DEPOT.length && !VISITED[next] && truck.z >= ConvoyRoute.DEPOT[next] + PARK - 0.05) {
			enterDepot(server, level, next);
			return;
		}
		if (stage == 4 && bossBeaten && truck.z >= ConvoyRoute.FINISH - 0.1) {
			victory(server, level);
			return;
		}
		if (truck.hp <= 0) {
			wreck(server, level);
		}
	}

	/** Crouching beside the truck mends it; Mechanics mend vehicles too. */
	private static void tickRepairs(ServerLevel level, List<ServerPlayer> targets) {
		for (ServerPlayer p : targets) {
			if (!p.isShiftKeyDown() || p.getVehicle() != null) {
				continue;
			}
			Crew c = CREW.get(p.getUUID());
			boolean mechanic = c != null && c.role == ConvoyUpgrade.Role.MECHANIC;
			if (truck.distanceTo(p.position()) < 2.5 && truck.hp < truck.maxHp) {
				float amount = mechanic ? 0.3F : 0.1F;
				truck.repair(amount);
				if (c != null) {
					c.repaired += amount;
				}
				if (ticks % 5 == 0) {
					Vec3 at = p.position().add(p.getLookAngle().scale(1.2)).add(0, 1.2, 0);
					level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 5, 0.2, 0.2, 0.2, 0.1);
					level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y, at.z, 1, 0.3, 0.3, 0.3, 0);
				}
				if (ticks % 16 == 0) {
					level.playSound(null, p.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.25F, 1.6F);
				}
				if (ticks % 20 == 0) {
					p.sendOverlayMessage(Component.literal("Repairing the truck: " + (int) truck.hp + " / " + (int) truck.maxHp).withStyle(ChatFormatting.GREEN));
				}
			} else if (mechanic) {
				for (UUID id : VEHICLES) {
					if (level.getEntity(id) instanceof LivingEntity v && v.isAlive() && v.distanceTo(p) < 4 && v.getHealth() < v.getMaxHealth()) {
						v.heal(0.3F);
						if (ticks % 5 == 0) {
							level.sendParticles(ParticleTypes.ELECTRIC_SPARK, v.getX(), v.getY() + 1, v.getZ(), 5, 0.5, 0.4, 0.5, 0.1);
						}
						if (ticks % 20 == 0) {
							p.sendOverlayMessage(Component.literal("Repairing: " + (int) v.getHealth() + " / " + (int) v.getMaxHealth()).withStyle(ChatFormatting.GREEN));
						}
						break;
					}
				}
			}
		}
	}

	private static void enterDepot(MinecraftServer server, ServerLevel level, int depot) {
		VISITED[depot] = true;
		state = State.DEPOT;
		timer = DEPOT_TICKS;
		stage = depot;
		truck.z = ConvoyRoute.DEPOT[depot] + PARK;
		truck.place(level);
		truck.repair(truck.maxHp * 0.35F);
		ConvoyEvents.clear(level);
		spawnVehicles(level, depot);
		Random random = new Random(seed ^ depot * 977L);
		for (ServerPlayer p : players(server)) {
			Crew c = CREW.get(p.getUUID());
			if (!c.alive) {
				place(p);
			}
			resupply(p);
			offerUpgrades(p, c, new Random(random.nextLong()));
			Alerts.title(p, "DEPOT " + depot, "Repairs, new vehicles, and an upgrade each", ChatFormatting.GREEN);
		}
		level.playSound(null, BlockPos.containing(truck.position()), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 1.0F, 0.8F);
	}

	private static void leaveDepot(MinecraftServer server, ServerLevel level) {
		for (ServerPlayer p : players(server)) {
			Crew c = CREW.get(p.getUUID());
			if (!c.picked && !c.offer.isEmpty()) {
				// Out of time: take the first card.
				pick(p, "upgrade", c.offer.get(0).name());
			}
			Net.send(p, new MorePayloads.ConvoyCards("close", "", List.of()));
		}
		state = State.ROLLING;
		ConvoyEvents.startStage(level, stage, truck.z, level.getRandom());
		titleAll(server, "STAGE " + stageNumber(), ConvoyRoute.NAMES[stage], ChatFormatting.GOLD);
		tell(server, Component.literal("Stage " + stageNumber() + ": " + ConvoyRoute.NAMES[stage] + ". " + ConvoyRoute.BLURBS[stage]).withStyle(ChatFormatting.GOLD));
		level.playSound(null, BlockPos.containing(truck.position()), SoundEvents.RAID_HORN.value(), SoundSource.NEUTRAL, 2.0F, 1.4F);
	}

	// ----- Vehicles -----

	private static void clearVehicles(ServerLevel level, boolean all) {
		for (var it = VEHICLES.iterator(); it.hasNext();) {
			Entity e = level.getEntity(it.next());
			if (e == null || !e.isAlive()) {
				it.remove();
				continue;
			}
			if (all || !e.isVehicle()) {
				e.ejectPassengers();
				e.discard();
				it.remove();
			}
		}
	}

	/** Fresh vehicles on the depot's pads: jeeps at the mine, tanks for the town, a helicopter at the canyon and before the fortress. */
	private static void spawnVehicles(ServerLevel level, int depot) {
		clearVehicles(level, false);
		int[][] pads = ConvoyEvents.pads(depot);
		int players = Math.max(1, CREW.size());
		for (int i = 0; i < pads.length; i++) {
			int[] pad = pads[i];
			net.minecraft.world.entity.EntityType<? extends Mob> type;
			if (pad[2] == 1) {
				type = ModEntities.ATTACK_HELI;
			} else if (depot == 0) {
				type = i < Math.min(2, (players + 1) / 2 + 1) ? ModEntities.JEEP : null;
			} else if (i == 0 || i == 1 && players >= 3 && depot != 2) {
				type = ModEntities.TANK;
			} else {
				type = ModEntities.JEEP;
			}
			if (type == null) {
				continue;
			}
			Mob v = type.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
			if (v == null) {
				continue;
			}
			double x = ConvoyRoute.X0 + pad[0] + 0.5;
			double z = ConvoyRoute.DEPOT[depot] + pad[1] + 0.5;
			v.snapTo(x, ConvoyRoute.Y + 1, z, 0, 0);
			v.yBodyRot = 0;
			v.addTag(TAG);
			v.addTag(VEHICLE_TAG);
			level.addFreshEntity(v);
			VEHICLES.add(v.getUUID());
			level.sendParticles(ParticleTypes.CLOUD, x, ConvoyRoute.Y + 1.5, z, 12, 1, 0.5, 1, 0.02);
		}
	}

	// ----- Wrecks, wins and losses -----

	private static void wreck(MinecraftServer server, ServerLevel level) {
		state = State.WRECKED;
		timer = 120;
		wrecks++;
		truck.explode(level);
		for (UUID id : new ArrayList<>(MOBS)) {
			Entity e = level.getEntity(id);
			if (e != null) {
				e.discard();
			}
		}
		MOBS.clear();
		ConvoyEnemies.clear();
		ConvoyEvents.clear(level);
		if (boss != null) {
			boss.remove(level);
			boss = null;
		}
		if (spares <= 0) {
			titleAll(server, "TRUCK WRECKED!", "No spare trucks left...", ChatFormatting.RED);
		} else {
			titleAll(server, "TRUCK WRECKED!", "A spare truck is on its way (" + spares + " left)", ChatFormatting.RED);
		}
	}

	private static void retry(MinecraftServer server, ServerLevel level) {
		if (spares <= 0) {
			fail(server);
			return;
		}
		spares--;
		float max = truck.maxHp;
		truck.remove(level);
		double z = stage == 0 ? PARK : ConvoyRoute.DEPOT[stage] + PARK;
		truck = ConvoyTruck.spawn(level, z, max);
		hold(level);
		state = State.DEPOT;
		timer = 12 * 20;
		spawnVehicles(level, stage);
		for (ServerPlayer p : players(server)) {
			place(p);
			resupply(p);
			Alerts.title(p, "SPARE TRUCK", "Back to the start of " + ConvoyRoute.NAMES[stage] + ": rolling in 12 seconds", ChatFormatting.GOLD);
		}
		for (Crew c : CREW.values()) {
			c.picked = true;
			c.offer = List.of();
		}
	}

	private static void fail(MinecraftServer server) {
		state = State.OVER;
		timer = 15 * 20;
		won = false;
		status = 8;
		record(server, false);
		titleAll(server, "THE CONVOY IS LOST", "You made it to " + ConvoyRoute.NAMES[stage] + ". Try again!", ChatFormatting.RED);
		summary(server, false);
	}

	private static void victory(MinecraftServer server, ServerLevel level) {
		state = State.OVER;
		timer = 20 * 20;
		won = true;
		status = 7;
		record(server, true);
		titleAll(server, "RUBIES DELIVERED!", "The convoy made it to the vault!", ChatFormatting.GOLD);
		level.playSound(null, BlockPos.containing(truck.position()), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.NEUTRAL, 2.0F, 1.0F);
		summary(server, true);
	}

	private static void record(MinecraftServer server, boolean delivered) {
		for (ServerPlayer p : players(server)) {
			Crew c = CREW.get(p.getUUID());
			Stats.convoy(p.getUUID(), p.getName().getString(), stage + 1, delivered, c == null ? 0 : c.kills);
		}
	}

	private static void summary(MinecraftServer server, boolean delivered) {
		long secs = (ticks - runStart) / 20;
		tell(server, Component.literal("══ RUBY CONVOY " + (delivered ? "DELIVERED" : "LOST") + " ══").withStyle(ChatFormatting.GOLD,
				ChatFormatting.BOLD));
		tell(server, Component.literal("Reached: " + ConvoyRoute.NAMES[stage] + "  •  time " + secs / 60 + ":" + String.format("%02d", secs % 60)
				+ "  •  trucks wrecked: " + wrecks).withStyle(ChatFormatting.WHITE));
		for (Crew c : CREW.values()) {
			tell(server, Component.literal("  " + c.name + (c.role == null ? "" : " (" + c.role.title + ")") + ": " + c.kills + " kills, "
					+ (int) c.repaired + " repaired").withStyle(ChatFormatting.GRAY));
		}
		tell(server, Component.literal("A new convoy loads up in a few seconds. H to leave.").withStyle(ChatFormatting.GRAY));
	}

	private static void fireworks(ServerLevel level) {
		if (truck == null) {
			return;
		}
		var random = level.getRandom();
		Vec3 at = truck.position().add((random.nextDouble() - 0.5) * 20, 8 + random.nextDouble() * 6, (random.nextDouble() - 0.5) * 12);
		RiftFx.burst(level, at, random.nextBoolean() ? 0xFFFF3050 : 0xFFFFD040, 40, 1.4);
		level.sendParticles(ParticleTypes.FIREWORK, at.x, at.y, at.z, 30, 0.4, 0.4, 0.4, 0.2);
		level.playSound(null, BlockPos.containing(at), SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.NEUTRAL, 2.0F, 1.0F);
	}

	// ----- Going down -----

	/** A crew member would die: down instead, back on the truck in eight seconds. */
	public static boolean onDeath(ServerPlayer victim, DamageSource source) {
		Crew c = CREW.get(victim.getUUID());
		if (c == null) {
			return false;
		}
		victim.setHealth(victim.getMaxHealth());
		victim.removeAllEffects();
		victim.clearFire();
		victim.stopRiding();
		ServerLevel level = level(victim.level().getServer());
		level.sendParticles(ParticleTypes.POOF, victim.getX(), victim.getY() + 1, victim.getZ(), 20, 0.4, 0.6, 0.4, 0.05);
		if (state == State.STAGING || state == State.OVER) {
			place(victim);
			return true;
		}
		c.alive = false;
		c.backAt = ticks + BACK_TICKS;
		victim.setGameMode(GameType.SPECTATOR);
		victim.teleportTo(level, victim.getX(), Math.max(victim.getY(), ConvoyRoute.Y + 1) + 3, victim.getZ(), Set.<Relative>of(), victim.getYRot(), 40, true);
		Alerts.title(victim, "YOU'RE DOWN!", "Back on the truck in " + BACK_TICKS / 20 + " seconds", ChatFormatting.RED);
		tell(victim.level().getServer(), Component.literal(victim.getName().getString() + " is down (back in " + BACK_TICKS / 20 + "s).")
				.withStyle(ChatFormatting.RED));
		return true;
	}

	private static void tickBack(MinecraftServer server, ServerLevel level) {
		for (ServerPlayer p : players(server)) {
			Crew c = CREW.get(p.getUUID());
			if (c == null || c.alive || !ready) {
				continue;
			}
			if (ticks >= c.backAt && truck != null) {
				place(p);
				p.sendOverlayMessage(Component.literal("Back on the road!").withStyle(ChatFormatting.GREEN));
			} else if ((c.backAt - ticks) % 20 == 0) {
				p.sendOverlayMessage(Component.literal("Back in " + (c.backAt - ticks) / 20 + "...").withStyle(ChatFormatting.YELLOW));
			}
		}
	}

	/** Nobody wanders off the map or falls into the gorge for good. */
	private static void keepInBounds(MinecraftServer server, ServerLevel level) {
		if (truck == null || !ready) {
			return;
		}
		for (ServerPlayer p : active(server)) {
			Entity body = p.getVehicle() != null && !truck.riding(p) ? p.getVehicle() : p;
			double off = Math.abs(body.getX() - ConvoyRoute.cx(body.getZ()));
			boolean fell = body.getY() < ConvoyRoute.Y - 7;
			boolean away = off > ConvoyRoute.HALF + 4 || body.getZ() < truck.z - 70 || body.getZ() > truck.z + 110 || body.getY() > ConvoyRoute.Y + 60
					|| body.getZ() > ConvoyRoute.BUILD_TO - 6 || body.getZ() < ConvoyRoute.BUILD_FROM + 4;
			if (!fell && !away) {
				continue;
			}
			if (body != p) {
				Vec3 at = truck.local(5, 0, -6);
				body.teleportTo(at.x, ConvoyRoute.Y + (body instanceof AircraftEntity ? 6 : 1.5), at.z);
				body.setDeltaMovement(Vec3.ZERO);
			} else {
				place(p);
			}
			p.sendOverlayMessage(Component.literal(fell ? "Up you come! Back to the truck." : "Stay with the convoy!").withStyle(ChatFormatting.YELLOW));
		}
	}

	// ----- Hooks from the rest of the game -----

	public static void onMobDeath(LivingEntity entity, DamageSource source) {
		if (!MOBS.remove(entity.getUUID())) {
			return;
		}
		if (source.getEntity() instanceof ServerPlayer killer) {
			Crew c = CREW.get(killer.getUUID());
			if (c != null) {
				c.kills++;
			}
		}
	}

	/** A bullet hit a block: the barricades take it. */
	public static void onImpact(ServerLevel level, BlockPos pos) {
		if (state == State.ROLLING && protects(level, pos)) {
			ConvoyEvents.onImpact(level, pos);
		}
	}

	/** An explosion: the barricades take it. */
	public static void onBlast(ServerLevel level, double x, double y, double z, float power) {
		if (state == State.ROLLING && protects(level, BlockPos.containing(x, y, z))) {
			ConvoyEvents.onBlast(level, new Vec3(x, y, z), power);
		}
	}

	public static void register() {
		// Right-click a seat on top of the truck to ride it.
		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (!entity.entityTags().contains(SEAT_TAG)) {
				return InteractionResult.PASS;
			}
			if (!world.isClientSide() && player instanceof ServerPlayer p && playing(p) && !entity.isVehicle() && p.getVehicle() == null) {
				p.startRiding(entity, true, true);
				p.sendOverlayMessage(Component.literal(truck != null && truck.onRoof(p) ? "On the roof gun: you hit harder and take less. Crouch to hop off."
						: "Riding the cargo bed. Crouch to hop off.").withStyle(ChatFormatting.GOLD));
			}
			return InteractionResult.SUCCESS;
		});
		ServerPlayNetworking.registerGlobalReceiver(MorePayloads.ConvoyPick.TYPE, (payload, context) -> pick(context.player(), payload.kind(), payload.id()));
	}

	// ----- The HUD -----

	private static void syncHud(MinecraftServer server, ServerLevel level) {
		List<String> team = new ArrayList<>();
		for (Crew c : CREW.values()) {
			ServerPlayer p = server.getPlayerList().getPlayer(c.id);
			if (p != null) {
				team.add(c.name + "|" + (int) Math.ceil(p.getHealth()) + "|" + (int) p.getMaxHealth() + "|" + (c.alive ? 0 : 1) + "|"
						+ (c.role == null ? "" : c.role.title));
			}
		}
		String header;
		String objective = "";
		float bar = -1;
		int seconds = -1;
		switch (state) {
			case STAGING -> {
				header = "RUBY CONVOY  •  LOADING UP";
				objective = ready ? "Pick your job. The truck rolls out in " + Math.max(0, timer / 20) + "s." : "Building the road...";
				seconds = ready ? timer / 20 : -1;
			}
			case DEPOT -> {
				header = "DEPOT " + stage + "  •  NEXT: " + ConvoyRoute.NAMES[stage].toUpperCase();
				objective = "Repairs and new vehicles. Pick an upgrade! Rolling out in " + Math.max(0, timer / 20) + "s.";
				seconds = timer / 20;
			}
			case WRECKED -> {
				header = "STAGE " + stageNumber() + "  •  " + ConvoyRoute.NAMES[stage].toUpperCase();
				objective = spares > 0 ? "The truck is wrecked! A spare is coming..." : "The truck is wrecked!";
			}
			case OVER -> {
				header = won ? "RUBIES DELIVERED!" : "THE CONVOY IS LOST";
				objective = "A new convoy loads up in " + Math.max(0, timer / 20) + "s.";
			}
			default -> {
				header = "STAGE " + stageNumber() + "  •  " + ConvoyRoute.NAMES[stage].toUpperCase();
				objective = truck == null ? "" : ConvoyEvents.objective(level, stage, truck.z);
				bar = truck == null ? -1 : ConvoyEvents.objectiveBar(level, stage, truck.z);
			}
		}
		Vec3 t = truck == null ? Vec3.ZERO : truck.position();
		List<Float> nums = List.of(truck == null ? 0F : (float) Math.max(0, Math.min(1, truck.z / ConvoyRoute.FINISH)), truck == null ? 0F : truck.hp,
				truck == null ? 1F : truck.maxHp, truck == null ? 0F : truck.shield, (float) status, bar, (float) t.x, (float) t.y, (float) t.z,
				(float) spares, (float) seconds, (float) stage);
		MorePayloads.ConvoyHud hud = new MorePayloads.ConvoyHud(header, nums, objective, team);
		for (ServerPlayer p : players(server)) {
			Net.send(p, hud);
		}
	}

	// ----- For autotests -----

	public static String debugState() {
		return state + " stage " + stage + (truck == null ? "" : String.format(" z %.1f hp %.0f/%.0f", truck.z, truck.hp, truck.maxHp)) + " status " + status
				+ " mobs " + MOBS.size() + " work " + WORK.size() + " spares " + spares + (boss != null ? " boss " + boss.debug() : "")
				+ (bossBeaten ? " BEATEN" : "") + " upgrades " + UPGRADES;
	}

	public static boolean ready() {
		return ready && WORK.isEmpty();
	}

	public static Vec3 truckPos() {
		return truck == null ? null : truck.position();
	}

	/** Skip ahead: "start", "goto:Z", "pick" (everyone takes their first card), "wreck", "killboss", "tanks", "logs", "bridge", "hp:N", "upgrade:ID". */
	public static void testSkip(MinecraftServer server, String what) {
		ServerLevel level = level(server);
		if (what.equals("start") && state == State.STAGING && ready) {
			timer = 1;
		} else if (what.startsWith("goto:") && truck != null) {
			double z = Double.parseDouble(what.substring(5));
			int s = Math.max(0, ConvoyRoute.stageAt(z));
			for (int i = 0; i <= s; i++) {
				VISITED[i] = true;
			}
			if (s != stage || state != State.ROLLING) {
				stage = s;
				state = State.ROLLING;
				ConvoyEvents.startStage(level, stage, z, level.getRandom());
			}
			ConvoyEvents.skipAmbushes(stage, z);
			truck.z = z;
			truck.place(level);
			hold(level);
			for (ServerPlayer p : active(server)) {
				place(p);
			}
		} else if (what.equals("pick")) {
			for (ServerPlayer p : players(server)) {
				Crew c = CREW.get(p.getUUID());
				if (c.role == null) {
					pick(p, "role", "GUNNER");
				} else if (!c.offer.isEmpty() && !c.picked) {
					pick(p, "upgrade", c.offer.get(0).name());
				}
			}
		} else if (what.equals("ride") && truck != null) {
			List<ServerPlayer> ps = active(server);
			if (!ps.isEmpty()) {
				ServerPlayer p = ps.get(0);
				p.setNoGravity(false);
				Entity seat = level.getEntity(truck.roofSeat);
				if (seat != null && p.getVehicle() == null) {
					p.teleportTo(seat.getX(), seat.getY() + 0.5, seat.getZ());
					p.startRiding(seat, true, true);
				}
			}
		} else if (what.equals("wreck") && truck != null) {
			truck.hp = 0;
		} else if (what.startsWith("hp:") && truck != null) {
			truck.hp = Float.parseFloat(what.substring(3));
		} else if (what.equals("killboss") && boss != null && boss.body(level) != null) {
			boss.body(level).hurtServer(level, level.damageSources().generic(), 1.0E6F);
		} else if (what.equals("logs")) {
			ConvoyEvents.onBlast(level, Vec3.atCenterOf(BlockPos.containing(ConvoyRoute.cx(ConvoyEvents.LOGS), ConvoyRoute.Y + 1, ConvoyEvents.LOGS + 1)), 20);
			for (BlockPos p : new ArrayList<>(ConvoyEvents.BARRICADE.keySet())) {
				ConvoyEvents.onBlast(level, Vec3.atCenterOf(p), 3);
			}
		} else if (what.equals("tanks")) {
			for (UUID id : new ArrayList<>(ConvoyEnemies.TANKS.keySet())) {
				if (level.getEntity(id) instanceof LivingEntity e) {
					e.hurtServer(level, level.damageSources().generic(), 1.0E6F);
				}
			}
		} else if (what.equals("bridge")) {
			ConvoyEvents.bridge = ConvoyEvents.BRIDGE_TICKS - 1;
		} else if (what.equals("clear")) {
			for (UUID id : new ArrayList<>(MOBS)) {
				Entity e = level.getEntity(id);
				if (e != null && !isBoss(e)) {
					e.discard();
				}
			}
		} else if (what.startsWith("upgrade:")) {
			ConvoyUpgrade u = ConvoyUpgrade.byId(what.substring(8));
			List<ServerPlayer> ps = players(server);
			if (u != null && !ps.isEmpty()) {
				apply(server, ps.get(0), u);
			}
		}
	}
}
