package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.rubymod.RubyMod;
import dev.rubymod.game.Shop;
import dev.rubymod.net.Payloads;
import dev.rubymod.registry.ModComponents;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * Scripted visual checks for development. Set the RUBYMOD_AUTOTEST environment
 * variable and launch into a world with --quickPlaySingleplayer. The client
 * runs a list of steps, saves screenshots to screenshots/autotest-*.png, and
 * quits. Modes: "guns" (each gun held, aimed, and in third person), "vehicles",
 * anything else = the full tour. Does nothing in normal play.
 */
public final class AutoTest {
	private record Step(int tick, Consumer<Minecraft> action) {
	}

	private static final List<Step> STEPS = new ArrayList<>();
	private static int tick = -1;
	private static int waiting;
	private static int cursor;
	private static Vec3 start;

	private AutoTest() {
	}

	public static void register() {
		String mode = System.getenv("RUBYMOD_AUTOTEST");
		if (mode == null) {
			return;
		}
		RubyMod.LOGGER.info("[autotest] enabled, mode {}", mode);
		buildScript(mode);
		ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
	}

	// ----- Helpers -----

	private static int at;

	private static void then(int delay, Consumer<Minecraft> action) {
		at += delay;
		STEPS.add(new Step(at, action));
	}

	private static void command(Minecraft mc, String command) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
		}
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "autotest-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
				message -> RubyMod.LOGGER.info("[autotest] screenshot {}: {}", name, message.getString()));
	}

	private static void aimAtNearest(net.minecraft.server.MinecraftServer server) {
		var me = me(server);
		var level = (net.minecraft.server.level.ServerLevel) me.level();
		var near = level.getEntities((net.minecraft.world.entity.Entity) null, me.getBoundingBox().inflate(30),
				e -> e instanceof net.minecraft.world.entity.Mob m && m.isAlive() && dev.rubymod.game.rift.Rift.isEnemy(e));
		near.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(me)));
		if (!near.isEmpty()) {
			var to = near.get(0).getBoundingBox().getCenter().subtract(me.getEyePosition());
			float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
			me.setYRot(yaw);
			me.setYHeadRot(yaw);
			me.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))));
		}
	}

	/** Ruby Convoy tests: move the truck (and the player) to Z, then ride it again. */
	private static void convoyGoto(String z) {
		then(5, mc -> onServer(mc, server -> {
			var me = me(server);
			me.setNoGravity(false);
			me.stopRiding();
			dev.rubymod.game.convoy.Convoy.testSkip(server, "goto:" + z);
			dev.rubymod.game.convoy.Convoy.testSkip(server, "hp:5000");
		}));
		then(30, mc -> onServer(mc, server -> dev.rubymod.game.convoy.Convoy.testSkip(server, "ride")));
	}

	/** Ruby Convoy tests: hop off and look at the truck from (dx, dy, dz) off it. */
	private static void convoyView(double dx, double dy, double dz) {
		then(5, mc -> onServer(mc, server -> {
			var me = me(server);
			var t = dev.rubymod.game.convoy.Convoy.truckPos();
			if (t == null) {
				return;
			}
			me.stopRiding();
			me.setNoGravity(true);
			me.teleportTo(t.x + dx, t.y + dy, t.z + dz);
		}));
		then(8, mc -> {
			var h = dev.rubymod.client.convoy.ConvoyClient.hud;
			if (h == null || h.nums().size() < 12) {
				return;
			}
			var t = new net.minecraft.world.phys.Vec3(h.nums().get(6), h.nums().get(7) + 1, h.nums().get(8));
			var e = mc.player.getEyePosition();
			var to = t.subtract(e);
			mc.player.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
			mc.player.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))));
		});
	}

	private static void log(String message, Object... args) {
		RubyMod.LOGGER.info("[autotest] " + message, args);
	}

	private static void aim(Minecraft mc, boolean on) {
		mc.options.keyUse.setDown(on);
		if (on) {
			mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
		}
	}

	private static String inventorySummary(Minecraft mc) {
		int stacks = 0;
		int items = 0;
		var inv = mc.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (!inv.getItem(i).isEmpty()) {
				stacks++;
				items += inv.getItem(i).getCount();
			}
		}
		return stacks + " stacks / " + items + " items, at " + mc.player.blockPosition().toShortString() + " in "
				+ mc.player.level().dimension().identifier() + ", " + mc.gameMode.getPlayerMode();
	}

	private static void onServer(Minecraft mc, java.util.function.Consumer<net.minecraft.server.MinecraftServer> action) {
		var server = mc.getSingleplayerServer();
		server.execute(() -> action.accept(server));
	}

	private static net.fabricmc.fabric.api.entity.FakePlayer buddy;

	private static dev.rubymod.game.party.PartyGame pg(String id) {
		return dev.rubymod.game.party.PartyGames.byId(id);
	}

	private static net.minecraft.server.level.ServerPlayer me(net.minecraft.server.MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}

	private static net.fabricmc.fabric.api.entity.FakePlayer partyBuddy(net.minecraft.server.MinecraftServer server) {
		return net.fabricmc.fabric.api.entity.FakePlayer.get(server.getLevel(dev.rubymod.game.Duels.ARENA),
				new com.mojang.authlib.GameProfile(java.util.UUID.fromString("00000000-0000-0000-0000-0000000b0d1e"), "Buddy"));
	}

	/** Join a party game (with Buddy if it needs two), skip the lobby, run {@code mechanics}, look, leave. */
	private static void partyMode(String id, boolean withBuddy, Runnable mechanics) {
		then(5, mc -> {
			log("=== {} ===", id);
			command(mc, "execute as @p run play " + id);
		});
		if (withBuddy) {
			then(10, mc -> onServer(mc, server -> dev.rubymod.game.party.PartyGames.addTestPlayer(pg(id), partyBuddy(server))));
		}
		// A fresh test world builds every arena first: keep skipping the lobby until the round is on.
		for (int i = 0; i < 20; i++) {
			then(i == 0 ? 10 : 40, mc -> onServer(mc, server -> pg(id).skipLobby()));
		}
		then(60, mc -> {
			log("{} started: {}", id, pg(id).debugState());
			shot(mc, "party-" + id + "-start");
		});
		mechanics.run();
		then(5, mc -> {
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			mc.options.keyShift.setDown(false);
			mc.gui.setScreen(null);
			log("{} end: {}", id, pg(id).debugState());
			onServer(mc, server -> {
				if (pg(id).contains(partyBuddy(server))) {
					pg(id).leave(partyBuddy(server));
				}
			});
			command(mc, "execute as @p run hub");
		});
		then(40, mc -> log("{} left: player in {}", id, mc.player.level().dimension().identifier()));
	}

	private static net.minecraft.core.BlockPos tankMark;

	private static String freeRoamCounts(net.minecraft.server.MinecraftServer server) {
		var level = server.getLevel(dev.rubymod.game.Duels.ARENA);
		var box = new net.minecraft.world.phys.AABB(-100400, -64, -400, -99600, 400, 1100);
		var all = level.getEntities((net.minecraft.world.entity.Entity) null, box, e -> !(e instanceof net.minecraft.world.entity.player.Player));
		java.util.Map<String, Integer> n = new java.util.TreeMap<>();
		for (var e : all) {
			String k = net.minecraft.world.entity.EntityType.getKey(e.getType()).getPath();
			if (e instanceof net.minecraft.world.entity.item.ItemEntity item) {
				k += ":" + item.getItem().getItem() + "@" + e.blockPosition().toShortString();
			}
			n.merge(k, 1, Integer::sum);
		}
		return n.toString();
	}

	private static void freeRoamWhere(net.minecraft.server.MinecraftServer server, String at) {
		var me = me(server);
		var level = (net.minecraft.server.level.ServerLevel) me.level();
		var feet = me.blockPosition();
		log("{}: me at {} in {} ({}, hp {}, vehicle {}) | below {} | column {}", at, feet, level.dimension().identifier(), me.gameMode.getGameModeForPlayer(),
				me.getHealth(), me.getVehicle(), level.getBlockState(feet.below()), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, feet.getX(), feet.getZ()));
	}

	private static void freeRoamScript() {
		then(0, mc -> {
			command(mc, "time set noon");
			command(mc, "gamemode survival @a");
			command(mc, "execute as @p run play freeroam");
		});
		for (int i = 0; i < 30; i++) {
			then(40, mc -> log("free roam: {} | building {}", pg("freeroam").debugState(), mc.player.blockPosition()));
		}
		then(20, mc -> onServer(mc, server -> log("after build: {}", freeRoamCounts(server))));
		then(5, mc -> shot(mc, "fr-spawn"));
		then(5, mc -> command(mc, "execute in rubymod:arena run tp @p -99996.5 65 -1.5 0 20"));
		then(40, mc -> shot(mc, "fr-button"));
		then(1, mc -> onServer(mc, server -> freeRoamWhere(server, "fr-button")));
		then(5, mc -> command(mc, "execute in rubymod:arena run tp @p -100040 65 4 160 10"));
		then(60, mc -> shot(mc, "fr-chests"));
		then(1, mc -> onServer(mc, server -> freeRoamWhere(server, "fr-chests")));
		then(5, mc -> command(mc, "execute in rubymod:arena run tp @p -99963 67 -10 0 10"));
		then(60, mc -> shot(mc, "fr-yard"));
		then(1, mc -> onServer(mc, server -> freeRoamWhere(server, "fr-yard")));
		then(5, mc -> command(mc, "execute in rubymod:arena run tp @p -99966 70 92 0 10"));
		then(80, mc -> shot(mc, "fr-apron"));
		then(1, mc -> onServer(mc, server -> freeRoamWhere(server, "fr-apron")));
		then(5, mc -> command(mc, "execute in rubymod:arena run tp @p -100000 65 0 0 0"));
		// Ammo refills itself.
		then(40, mc -> onServer(mc, server -> {
			var me = me(server);
			for (int i = 0; i < me.getInventory().getContainerSize(); i++) {
				var st = me.getInventory().getItem(i);
				if (st.is(dev.rubymod.registry.ModItems.HEAVY_AMMO)) {
					st.setCount(3);
				}
			}
		}));
		then(60, mc -> onServer(mc, server -> log("heavy ammo after refill: {}", me(server).getInventory().countItem(dev.rubymod.registry.ModItems.HEAVY_AMMO))));
		// Wreck the place: tanks gone, a crater, blocks on the runway.
		then(5, mc -> onServer(mc, server -> {
			var level = server.getLevel(dev.rubymod.game.Duels.ARENA);
			for (var e : level.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(-100100, 0, -100, -99900, 200, 200),
					e -> e instanceof dev.rubymod.entity.VehicleEntity)) {
				e.discard();
			}
			for (int x = -3; x <= 3; x++) {
				for (int z = 8; z <= 14; z++) {
					level.setBlock(new net.minecraft.core.BlockPos(-100000 + x, 64, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
					level.setBlock(new net.minecraft.core.BlockPos(-100000 + x, 63, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
				}
			}
			var button = new net.minecraft.core.BlockPos(-99996, 66, 4);
			log("wrecked: {} | button breakable by weapons? {}", freeRoamCounts(server),
					dev.rubymod.game.Rules.canWeaponBreak(level, button, level.getBlockState(button)));
		}));
		then(5, mc -> command(mc, "execute in rubymod:arena run tp @p -100000 65 -6 0 30"));
		then(30, mc -> shot(mc, "fr-wrecked"));
		then(1, mc -> onServer(mc, server -> freeRoamWhere(server, "fr-wrecked")));
		then(5, mc -> onServer(mc, server -> log("press: handled {}", pg("freeroam").onUseBlock(me(server), new net.minecraft.core.BlockPos(-99996, 67, 4),
				net.minecraft.world.item.ItemStack.EMPTY, new net.minecraft.core.BlockPos(-99996, 67, 4)))));
		then(10, mc -> onServer(mc, server -> log("press again: handled {}", pg("freeroam").onUseBlock(me(server), new net.minecraft.core.BlockPos(-99996, 66, 4),
				net.minecraft.world.item.ItemStack.EMPTY, new net.minecraft.core.BlockPos(-99996, 66, 4)))));
		then(50, mc -> shot(mc, "fr-countdown"));
		for (int i = 0; i < 20; i++) {
			then(40, mc -> onServer(mc, server -> log("resetting: {} | slowest tick {} ms", pg("freeroam").debugState(), maxTickMs(server))));
		}
		then(40, mc -> onServer(mc, server -> {
			var level = server.getLevel(dev.rubymod.game.Duels.ARENA);
			log("after reset: {} | ground at crater {} | me at {}", freeRoamCounts(server),
					level.getBlockState(new net.minecraft.core.BlockPos(-100000, 64, 10)), me(server).blockPosition());
		}));
		then(5, mc -> command(mc, "execute in rubymod:arena run tp @p -99963 67 -10 0 10"));
		then(60, mc -> shot(mc, "fr-yard-after"));
		then(1, mc -> onServer(mc, server -> freeRoamWhere(server, "fr-yard-after")));
		then(5, mc -> command(mc, "execute as @p run hub"));
		then(60, mc -> onServer(mc, server -> log("left: {} | {}", pg("freeroam").debugState(), me(server).level().dimension().identifier())));
		// Back in: no rebuild, straight into play, everything where it was.
		then(5, mc -> command(mc, "execute as @p run play freeroam"));
		then(60, mc -> onServer(mc, server -> log("rejoined: {} | {}", pg("freeroam").debugState(), freeRoamCounts(server))));
		then(5, mc -> command(mc, "execute as @p run hub"));
	}

	private static String riftOpenNode() {
		var screen = dev.rubymod.client.rift.RiftClient.lastScreen;
		if (screen != null && screen.kind().equals("map")) {
			for (String line : screen.lines()) {
				String[] f = line.split("[|]", -1);
				if (f.length >= 8 && f[7].equals("open")) {
					return f[0];
				}
			}
		}
		return "0";
	}

	private static String riftFirstOffer() {
		var screen = dev.rubymod.client.rift.RiftClient.lastScreen;
		if (screen != null) {
			for (String line : screen.lines()) {
				String[] f = line.split("[|]", -1);
				if (f.length >= 7 && !f[0].equals("title") && !f[0].equals("gold")) {
					return f[0];
				}
			}
		}
		return "";
	}

	/** Shoot your rifle at the nearest Rift monster (server side, aimed for you). */
	private static void riftShoot(Minecraft mc) {
		onServer(mc, server -> {
			var me = me(server);
			var level = (net.minecraft.server.level.ServerLevel) me.level();
			var target = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, me.getBoundingBox().inflate(40),
					e -> dev.rubymod.game.rift.Rift.isEnemy(e) && e.isAlive()).stream()
					.min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(me))).orElse(null);
			if (target == null) {
				return;
			}
			var to = target.getBoundingBox().getCenter().subtract(me.getEyePosition());
			me.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
			me.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))));
			me.setYHeadRot(me.getYRot());
			me.getInventory().setSelectedSlot(0);
			var stack = me.getMainHandItem();
			if (stack.getItem() instanceof dev.rubymod.gun.GunItem gun) {
				dev.rubymod.gun.Gunfire.forget(me.getUUID());
				dev.rubymod.gun.Gunfire.tryFire(level, me, stack, gun);
			}
		});
	}

	private static void riftScript() {
		then(0, mc -> {
			command(mc, "time set noon");
			command(mc, "difficulty normal");
			command(mc, "gamemode survival @a");
		});
		then(20, mc -> command(mc, "execute as @p run play rift"));
		then(160, mc -> {
			command(mc, "effect give @a minecraft:resistance infinite 255 true");
			log("threshold: {}", dev.rubymod.game.rift.Rift.debugState());
			shot(mc, "rift-threshold");
		});
		// The Altar of Echoes: some shards, a loadout and a relic unlocked.
		then(5, mc -> onServer(mc, server -> {
			dev.rubymod.game.Stats.get(me(server)).riftShards = 120;
			dev.rubymod.game.rift.RiftChoices.handle(me(server), "loadout", "demolitionist");
			dev.rubymod.game.rift.RiftChoices.handle(me(server), "unlock", "thunderclap");
		}));
		then(20, mc -> dev.rubymod.client.rift.RiftClient.reopen());
		then(30, mc -> {
			log("altar: screen {}", mc.gui.screen());
			shot(mc, "rift-altar");
			mc.gui.setScreen(null);
		});
		then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
		then(30, mc -> {
			log("vote: {} | screen {} | inventory {}", dev.rubymod.game.rift.Rift.debugState(), mc.gui.screen(), inventorySummary(mc));
			shot(mc, "rift-map");
		});
		then(5, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
				new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode())));
		then(200, mc -> {
			log("room: {}", dev.rubymod.game.rift.Rift.debugState());
			shot(mc, "rift-room");
		});
		then(80, mc -> {
			log("fight: {}", dev.rubymod.game.rift.Rift.debugState());
			shot(mc, "rift-fight");
		});
		// Relics, with the flashy pickup.
		then(5, mc -> onServer(mc, server -> {
			dev.rubymod.game.rift.Rift.testSkip(server, "chain_lightning");
			dev.rubymod.game.rift.Rift.testSkip(server, "pocket_tank");
			dev.rubymod.game.rift.Rift.testSkip(server, "drop_turret");
		}));
		then(6, mc -> shot(mc, "rift-pickup"));
		then(10, mc -> onServer(mc, server -> {
			dev.rubymod.game.rift.Rift.testSkip(server, "orbital_strike");
			dev.rubymod.game.rift.Rift.testSkip(server, "cryo");
			dev.rubymod.game.rift.Rift.testSkip(server, "napalm");
		}));
		then(8, mc -> shot(mc, "rift-legendary"));
		for (int i = 0; i < 24; i++) {
			then(4, mc -> riftShoot(mc));
		}
		then(2, mc -> {
			log("after shooting: {}", dev.rubymod.game.rift.Rift.debugState());
			shot(mc, "rift-shooting");
		});
		then(60, mc -> shot(mc, "rift-orbital"));
		// Clear the room: the relic choice.
		then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "clear")));
		then(80, mc -> {
			log("cleared: {} | screen {}", dev.rubymod.game.rift.Rift.debugState(), mc.gui.screen());
			shot(mc, "rift-offer");
		});
		// Close the choice without picking and walk into the portal: the choice comes back.
		then(5, mc -> mc.gui.setScreen(null));
		then(5, mc -> onServer(mc, server -> {
			var at = dev.rubymod.game.rift.Rift.portalPos();
			var me = me(server);
			me.teleportTo((net.minecraft.server.level.ServerLevel) me.level(), at.x + 0.5, at.y, at.z + 0.5, java.util.Set.of(), 180, 20, true);
		}));
		then(40, mc -> {
			log("in the portal without picking: screen {} | {}", mc.gui.screen(), dev.rubymod.game.rift.Rift.debugState());
			shot(mc, "rift-portal-pickfirst");
		});
		then(5, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
				new dev.rubymod.net.MorePayloads.RiftChoice("pick", riftFirstOffer())));
		then(40, mc -> {
			log("picked, standing in the portal: screen {} | {}", mc.gui.screen(), dev.rubymod.game.rift.Rift.debugState());
			shot(mc, "rift-portal");
		});
		then(5, mc -> onServer(mc, server -> {
			var me = me(server);
			var b = dev.rubymod.game.rift.Rift.debugState();
			log("picked: {}", b);
		}));
		// A tank room.
		then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "tank")));
		then(220, mc -> {
			log("tank room: {} riding {}", dev.rubymod.game.rift.Rift.debugState(), mc.player.getVehicle());
			shot(mc, "rift-tank");
		});
		then(120, mc -> {
			log("tank fight: {}", dev.rubymod.game.rift.Rift.debugState());
			shot(mc, "rift-tank2");
		});
		then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "clear")));
		then(60, mc -> {
			log("tank cleared: {} riding {}", dev.rubymod.game.rift.Rift.debugState(), mc.player.getVehicle());
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.RiftChoice("pick", riftFirstOffer()));
		});
		// The boss.
		then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "boss")));
		then(200, mc -> onServer(mc, server -> {
			var me = me(server);
			var level = (net.minecraft.server.level.ServerLevel) me.level();
			me.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 255, false, false));
			level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, me.getBoundingBox().inflate(80),
					e -> dev.rubymod.game.rift.JuggernautMech.isBoss(e)).stream().findFirst().ifPresent(b ->
					me.teleportTo(level, b.getX() + 7, b.getY() + 2, b.getZ() + 9, java.util.Set.of(), 140, 5, true));
		}));
		then(30, mc -> {
			log("boss: {}", dev.rubymod.game.rift.Rift.debugState());
			shot(mc, "rift-boss");
		});
		then(120, mc -> {
			log("boss fight: {}", dev.rubymod.game.rift.Rift.debugState());
			shot(mc, "rift-boss2");
		});
		then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "clear")));
		then(30, mc -> shot(mc, "rift-boss-dying"));
		then(80, mc -> {
			log("after the boss: {} | screen {}", dev.rubymod.game.rift.Rift.debugState(), mc.gui.screen());
			shot(mc, "rift-actclear");
		});
		// Act 2: the Gunship.
		then(5, mc -> {
			mc.gui.setScreen(null);
			onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "act2"));
		});
		then(260, mc -> onServer(mc, server -> {
			var me = me(server);
			me.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 255, false, false));
			log("gunship: {}", dev.rubymod.game.rift.Rift.debugState());
		}));
		then(60, mc -> {
			var heli = mc.level.getEntitiesOfClass(dev.rubymod.entity.AircraftEntity.class, mc.player.getBoundingBox().inflate(80));
			if (!heli.isEmpty()) {
				var to = heli.get(0).position().subtract(mc.player.getEyePosition());
				mc.player.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
				mc.player.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))));
			}
		});
		then(5, mc -> shot(mc, "rift-gunship"));
		then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "clear")));
		then(80, mc -> log("gunship down: {}", dev.rubymod.game.rift.Rift.debugState()));
		// Act 3: the Ruby Titan.
		then(5, mc -> {
			mc.gui.setScreen(null);
			onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "act3"));
		});
		then(260, mc -> onServer(mc, server -> {
			var me = me(server);
			var level = (net.minecraft.server.level.ServerLevel) me.level();
			me.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 255, false, false));
			log("titan: {}", dev.rubymod.game.rift.Rift.debugState());
			level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, me.getBoundingBox().inflate(80),
					e -> dev.rubymod.game.rift.JuggernautMech.isBoss(e)).stream().findFirst().ifPresent(b ->
					me.teleportTo(level, b.getX() + 10, b.getY() + 3, b.getZ() + 14, java.util.Set.of(), 145, 10, true));
		}));
		then(40, mc -> shot(mc, "rift-titan"));
		then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "clear")));
		then(20, mc -> log("pylons broken: {}", dev.rubymod.game.rift.Rift.debugState()));
		then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "clear")));
		then(40, mc -> shot(mc, "rift-titan-dying"));
		then(80, mc -> {
			log("after the titan: {}", dev.rubymod.game.rift.Rift.debugState());
			shot(mc, "rift-victory");
		});
		then(20, mc -> {
			log("done");
			mc.stop();
		});
	}

	private static long maxTickMs(net.minecraft.server.MinecraftServer server) {
		long max = 0;
		for (long t : server.getTickTimesNanos()) {
			max = Math.max(max, t);
		}
		return max / 1_000_000;
	}

	/** The hub, profile and cosmetics, leaderboards, bots in CTF and Gun Game, crash safety, tick times. */
	private static void extrasScript() {
		then(0, mc -> {
			command(mc, "time set noon");
			command(mc, "difficulty normal");
			command(mc, "gamemode survival @a");
		});
		then(60, mc -> onServer(mc, server -> log("tick times after start (arenas building in the background): max {} ms, avg {} ms",
				maxTickMs(server), server.getAverageTickTimeNanos() / 1_000_000)));
		// First visit to the hub.
		then(5, mc -> command(mc, "execute as @p run hub"));
		then(80, mc -> shot(mc, "extras-hub"));
		// Some wins: cosmetics unlock.
		then(5, mc -> onServer(mc, server -> {
			var me = me(server);
			dev.rubymod.game.Stats.won(me.getUUID(), me.getName().getString(), "gungame");
			dev.rubymod.game.Stats.won(me.getUUID(), me.getName().getString(), "tanks");
			dev.rubymod.game.Stats.horde(me.getUUID(), me.getName().getString(), 6, 40, 2);
			dev.rubymod.game.Stats.skyRace(me.getUUID(), me.getName().getString(), 1300);
			log("unlocked: {}", dev.rubymod.game.Stats.get(me).unlocked);
		}));
		then(10, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.CosmeticSelect("hat_tank")));
		then(5, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.CosmeticSelect("trail_snow")));
		then(5, mc -> mc.gui.setScreen(new ProfileScreen()));
		then(30, mc -> shot(mc, "extras-profile"));
		then(5, mc -> {
			mc.gui.setScreen(null);
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
		});
		then(40, mc -> {
			shot(mc, "extras-hat");
			onServer(mc, server -> {
				var me = me(server);
				var hats = ((net.minecraft.server.level.ServerLevel) me.level()).getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class,
						me.getBoundingBox().inflate(5));
				log("hat check: wearing '{}', displays near: {}", dev.rubymod.game.Stats.get(me).hat, hats.stream().map(h -> h.position().toString()).toList());
			});
			log("client sees item displays: {}", mc.level.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class,
					mc.player.getBoundingBox().inflate(5)).size());
		});
		then(5, mc -> {
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			command(mc, "execute as @p at @s run tp @s 0.5 101 3.5 -90 5");
		});
		then(60, mc -> shot(mc, "extras-board-records"));
		then(5, mc -> command(mc, "execute as @p at @s run tp @s 0.5 101 3.5 90 5"));
		then(40, mc -> shot(mc, "extras-board-party"));
		// Capture the Flag, solo against bots.
		then(5, mc -> command(mc, "execute as @p run play ctf"));
		then(20, mc -> onServer(mc, server -> pg("ctf").skipLobby()));
		then(80, mc -> log("ctf with bots: {}", pg("ctf").debugState()));
		then(5, mc -> onServer(mc, server -> pg("ctf").fastForward(20 * 20 - 1)));
		then(5, mc -> command(mc, "effect give @a minecraft:resistance infinite 255 true"));
		for (int i = 0; i < 4; i++) {
			then(200, mc -> {
				log("ctf: {} | tick max {} ms", pg("ctf").debugState(), "?");
				var bots = mc.level.getEntitiesOfClass(dev.rubymod.entity.SoldierEntity.class, mc.player.getBoundingBox().inflate(120));
				StringBuilder where = new StringBuilder();
				for (var b : bots) {
					where.append(b.getName().getString()).append("@").append((int) b.getZ()).append(b.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty() ? "" : "(FLAG)").append(" ");
				}
				log("ctf bots: {}", where);
			});
		}
		then(5, mc -> shot(mc, "extras-ctf"));
		then(5, mc -> command(mc, "execute as @p run play gungame"));
		then(20, mc -> onServer(mc, server -> pg("gungame").skipLobby()));
		then(100, mc -> {
			var bots = mc.level.getEntitiesOfClass(dev.rubymod.entity.SoldierEntity.class, mc.player.getBoundingBox().inflate(80));
			StringBuilder where = new StringBuilder();
			for (var b : bots) {
				where.append(b.getName().getString()).append(" holds ").append(b.getMainHandItem().getHoverName().getString()).append("; ");
			}
			log("gungame bots: {}", where);
			shot(mc, "extras-gungame-bots");
		});
		then(200, mc -> log("gungame after 10s with bots: health {} {}", mc.player.getHealth(), pg("gungame").debugState()));
		// Crash safety: a game that throws sends its players to the hub.
		then(5, mc -> onServer(mc, server -> {
			dev.rubymod.game.party.PartyGames.crashed(pg("gungame"), new RuntimeException("test crash"));
			log("after the test crash: {} | player in {}", pg("gungame").debugState(), me(server).level().dimension().identifier());
		}));
		then(40, mc -> onServer(mc, server -> log("tick times overall: max {} ms, avg {} ms", maxTickMs(server), server.getAverageTickTimeNanos() / 1_000_000)));
	}

	/** Every party game in turn (or just one: party:<id>). */	/** Every party game in turn (or just one: party:<id>). */
	private static void partyScript(String only) {
		then(0, mc -> {
			command(mc, "time set noon");
			command(mc, "difficulty normal");
			command(mc, "gamemode survival @a");
		});
		then(20, mc -> {
			mc.gui.setScreen(new ModeScreen());
		});
		then(40, mc -> {
			shot(mc, "party-picker");
			mc.gui.setScreen(null);
		});
		if (only.isEmpty() || only.equals("gungame")) {
			partyMode("gungame", false, () -> {
				for (int i = 0; i < 2; i++) {
					then(20, mc -> onServer(mc, server -> {
						var player = me(server);
						var level = server.getLevel(dev.rubymod.game.Duels.ARENA);
						var bot = level.getEntitiesOfClass(dev.rubymod.entity.SoldierEntity.class, player.getBoundingBox().inflate(90)).stream().findFirst();
						bot.ifPresent(b -> b.hurtServer(level, player.damageSources().playerAttack(player), 1000));
						log("gungame after a bot kill: {}", pg("gungame").debugState());
					}));
				}
				then(30, mc -> shot(mc, "party-gungame-level"));
			});
		}
		if (only.isEmpty() || only.equals("hideseek")) {
			partyMode("hideseek", true, () -> {
				then(5, mc -> onServer(mc, server -> {
					pg("hideseek").forceTeam(me(server), 0);
					pg("hideseek").forceTeam(partyBuddy(server), 1);
					var pos = new net.minecraft.core.BlockPos(-45005, 65, -5);
					dev.rubymod.game.party.PartyGames.onUseBlock(me(server), pos, net.minecraft.world.item.ItemStack.EMPTY, pos);
					log("hider disguised: {}", pg("hideseek").debugState());
				}));
				then(70, mc -> {
					mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
				});
				then(20, mc -> shot(mc, "party-hideseek-disguise"));
				then(5, mc -> onServer(mc, server -> pg("hideseek").fastForward(40 * 20 - 1)));
				then(10, mc -> onServer(mc, server -> {
					dev.rubymod.game.Rules.canHurt(partyBuddy(server), me(server));
					log("after the seeker's hit: {}", pg("hideseek").debugState());
				}));
				then(20, mc -> shot(mc, "party-hideseek-found"));
			});
		}
		if (only.isEmpty() || only.equals("infection")) {
			partyMode("infection", true, () -> {
				then(5, mc -> onServer(mc, server -> {
					pg("infection").forceTeam(me(server), 0);
					pg("infection").forceTeam(partyBuddy(server), 1);
				}));
				then(30, mc -> shot(mc, "party-infection-survivor"));
				then(160, mc -> onServer(mc, server -> {
					var buddy = partyBuddy(server);
					me(server).hurtServer(me(server).level(), buddy.damageSources().playerAttack(buddy), 1000);
					log("after the bite: {}", pg("infection").debugState());
				}));
				then(30, mc -> shot(mc, "party-infection-end"));
			});
		}
		if (only.isEmpty() || only.equals("ctf")) {
			partyMode("ctf", true, () -> {
				then(5, mc -> onServer(mc, server -> {
					pg("ctf").forceTeam(me(server), 0);
					pg("ctf").forceTeam(partyBuddy(server), 1);
				}));
				then(20, mc -> shot(mc, "party-ctf-base"));
				then(5, mc -> onServer(mc, server -> pg("ctf").fastForward(20 * 20 - 1)));
				then(10, mc -> command(mc, "execute as @p at @s run tp @s -59999.5 67 43.5 180 20"));
				then(20, mc -> {
					log("at the blue flag: {} head {}", pg("ctf").debugState(), mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD));
					shot(mc, "party-ctf-carrying");
				});
				then(5, mc -> command(mc, "execute as @p at @s run tp @s -59999.5 67 -40.5 0 20"));
				then(20, mc -> log("back home: {}", pg("ctf").debugState()));
			});
		}
		if (only.isEmpty() || only.equals("koth")) {
			partyMode("koth", false, () -> {
				then(5, mc -> command(mc, "execute as @p at @s run tp @s -54999.5 68 0.5 0 10"));
				then(120, mc -> {
					log("koth on the hill: {}", pg("koth").debugState());
					shot(mc, "party-koth-hill");
				});
			});
		}
		if (only.isEmpty() || only.equals("tanks")) {
			partyMode("tanks", true, () -> {
				for (int i = 0; i < 8; i++) {
					then(100, mc -> onServer(mc, server -> {
						log("tanks building: {} | slowest tick {} ms", pg("tanks").debugState(), maxTickMs(server));
						pg("tanks").skipLobby();
					}));
				}
				then(160, mc -> {
					log("tanks: riding {} | {}", mc.player.getVehicle(), pg("tanks").debugState());
					shot(mc, "party-tanks-ride");
				});
				// Bullets can't reach someone inside a tank.
				then(5, mc -> onServer(mc, server -> {
					var me = me(server);
					var buddy = partyBuddy(server);
					float before = me.getHealth();
					me.hurtServer(me.level(), dev.rubymod.registry.ModDamage.source(me.level(), dev.rubymod.registry.ModDamage.BULLET, buddy, buddy), 6.0F);
					log("tank crew shot with a bullet: riding {} health {} -> {}", me.getVehicle() != null, before, me.getHealth());
				}));
				// A crater in the arena: the next round's rebuild should fill it, without freezing the server.
				then(5, mc -> onServer(mc, server -> {
					var level = server.getLevel(dev.rubymod.game.Duels.ARENA);
					var p = new net.minecraft.core.BlockPos(me(server).getBlockX() + 20, dev.rubymod.game.Duels.GROUND + 1, me(server).getBlockZ() + 20);
					level.setBlock(p.below(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
					level.setBlock(p.above(), net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(), 3);
					tankMark = p;
					maxTickMs(server);
					pg("tanks").queueBuild();
				}));
				then(200, mc -> onServer(mc, server -> {
					var level = server.getLevel(dev.rubymod.game.Duels.ARENA);
					log("tanks rebuilt: crater filled {}, gold gone {}, slowest tick {} ms",
							!level.getBlockState(tankMark.below()).isAir(), level.getBlockState(tankMark.above()).isAir(), maxTickMs(server));
				}));
			});
		}
		if (only.isEmpty() || only.equals("air")) {
			partyMode("air", true, () -> {
				then(100, mc -> {
					log("air: riding {} | {}", mc.player.getVehicle(), pg("air").debugState());
					shot(mc, "party-air-runway");
				});
				then(5, mc -> onServer(mc, server -> {
					var level = server.getLevel(dev.rubymod.game.Duels.ARENA);
					log("AA guns: {}", level.getEntitiesOfClass(dev.rubymod.entity.HeavyAAGunEntity.class, me(server).getBoundingBox().inflate(400)).size());
				}));
				// Fly off the edge: five seconds to turn back, then you're out.
				then(5, mc -> onServer(mc, server -> {
					var me = me(server);
					var ride = me.getVehicle() != null ? me.getVehicle() : me;
					ride.teleportTo(-75000 + 360, 140, 0);
				}));
				then(40, mc -> shot(mc, "party-air-edge"));
				then(90, mc -> log("after leaving the area: {} health {}", pg("air").debugState(), mc.player.getHealth()));
			});
		}
		if (only.isEmpty() || only.equals("skyrace")) {
			partyMode("skyrace", false, () -> {
				then(160, mc -> shot(mc, "party-skyrace-go"));
				for (int i = 0; i < 24; i++) {
					int ring = i % 12;
					int[] heights = {10, 15, 20, 14, 9, 16, 22, 17, 11, 18, 13, 8};
					double a = Math.toRadians(15 + ring * 30);
					double cx = -70000 + 0.5 + Math.cos(a) * 50;
					double cy = dev.rubymod.game.Duels.GROUND + heights[ring];
					double cz = 0.5 + Math.sin(a) * 34;
					double tx = -Math.sin(a) * 50;
					double tz = Math.cos(a) * 34;
					double len = Math.sqrt(tx * tx + tz * tz);
					double fx = tx / len;
					double fz = tz / len;
					then(2, mc -> command(mc, String.format(java.util.Locale.ROOT, "execute as @p at @s run tp @s %.2f %.2f %.2f", cx - fx * 1.5, cy - 0.9, cz - fz * 1.5)));
					then(2, mc -> command(mc, String.format(java.util.Locale.ROOT, "execute as @p at @s run tp @s %.2f %.2f %.2f", cx + fx * 1.5, cy - 0.9, cz + fz * 1.5)));
				}
				then(20, mc -> {
					log("skyrace after the course: {}", pg("skyrace").debugState());
					shot(mc, "party-skyrace-finish");
				});
				// The next round is an elytra race (run the clock out on this one).
				then(5, mc -> onServer(mc, server -> pg("skyrace").fastForward(3 * 60 * 20 - 1)));
				then(260, mc -> onServer(mc, server -> {
					log("skyrace before round 2: {}", pg("skyrace").debugState());
					pg("skyrace").skipLobby();
				}));
				then(60, mc -> {
					log("round 2: {} chest {}", pg("skyrace").debugState(), mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST));
					shot(mc, "party-skyrace-tower");
				});
				then(60, mc -> command(mc, "execute as @p at @s run tp @s ~ ~ ~-8 180 10"));
				then(25, mc -> {
					log("off the tower: fall flying {}, minimap race data {}", mc.player.isFallFlying(), ClientState.race != null);
					shot(mc, "party-skyrace-glide");
				});
				// Then a boat race on the ice track.
				then(5, mc -> onServer(mc, server -> pg("skyrace").fastForward(3 * 60 * 20 - 1)));
				then(260, mc -> onServer(mc, server -> pg("skyrace").skipLobby()));
				then(70, mc -> {
					log("round 3: {} riding {}", pg("skyrace").debugState(), mc.player.getVehicle());
					shot(mc, "party-skyrace-boats");
				});
				then(60, mc -> {
					log("go: at {}", mc.player.position());
					mc.options.keyUp.setDown(true);
				});
				then(100, mc -> {
					log("after 5s of W: at {} riding {} | {}", mc.player.position(), mc.player.getVehicle(), pg("skyrace").debugState());
					shot(mc, "party-skyrace-boating");
					mc.options.keyUp.setDown(false);
					mc.options.keyShift.setDown(true);
				});
				then(10, mc -> {
					mc.options.keyShift.setDown(false);
					log("climbed out: riding {}", mc.player.getVehicle());
				});
				then(60, mc -> log("2s later: riding {} at {}", mc.player.getVehicle(), mc.player.position()));
			});
		}
		if (only.isEmpty() || only.equals("build")) {
			partyMode("build", true, () -> {
				then(20, mc -> onServer(mc, server -> {
					var player = me(server);
					log("build: TNT allowed {}, oak planks allowed {}, rifle allowed {}",
							pg("build").allowsItemUse(player, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TNT)),
							pg("build").allowsItemUse(player, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS)),
							pg("build").allowsItemUse(player, new net.minecraft.world.item.ItemStack(dev.rubymod.registry.ModItems.ARR_RIFLE)));
					var level = server.getLevel(dev.rubymod.game.Duels.ARENA);
					for (int y = 0; y < 5; y++) {
						level.setBlock(player.blockPosition().offset(2, y, 2), net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(), 3);
					}
				}));
				then(20, mc -> shot(mc, "party-build-building"));
				then(5, mc -> onServer(mc, server -> pg("build").fastForward(5 * 60 * 20 - 1)));
				then(260, mc -> onServer(mc, server -> {
					var player = me(server);
					pg("build").allowsItemUse(player, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WOOL.pick(net.minecraft.world.item.DyeColor.GREEN)));
					log("voted on plot 2: {}", pg("build").debugState());
				}));
				then(10, mc -> shot(mc, "party-build-voting"));
				then(260, mc -> {
					log("build results: {}", pg("build").debugState());
					shot(mc, "party-build-results");
				});
			});
		}
		if (only.isEmpty() || only.equals("rush")) {
			partyMode("rush", false, () -> {
				then(20, mc -> shot(mc, "party-rush-drop"));
				then(260, mc -> {
					log("rush landed: {} at {}", pg("rush").debugState(), mc.player.blockPosition());
					shot(mc, "party-rush-landed");
				});
				then(5, mc -> onServer(mc, server -> pg("rush").fastForward(60 * 20 + 7 * 60 * 20 - 40)));
				then(80, mc -> {
					log("rush zone closed: health {} {}", mc.player.getHealth(), pg("rush").debugState());
					shot(mc, "party-rush-zone");
				});
				then(5, mc -> onServer(mc, server -> {
					me(server).hurtServer(me(server).level(), me(server).damageSources().generic(), 1000);
					log("rush after going down: {}", pg("rush").debugState());
				}));
				then(20, mc -> shot(mc, "party-rush-out"));
			});
		}
	}

	/** Co-op Horde with a stand-in teammate: scaling, revive, rewards, the cache, fog, air raid, Second Wind. */	/** Co-op Horde with a stand-in teammate: scaling, revive, rewards, the cache, fog, air raid, Second Wind. */
	private static void hordePlusScript() {
		then(0, mc -> {
			for (String line : dev.rubymod.game.Horde.scalingTable()) {
				log("scale {}", line);
			}
			command(mc, "time set noon");
			command(mc, "difficulty normal");
			command(mc, "gamemode survival @a");
		});
		then(20, mc -> command(mc, "execute as @p run play horde normal"));
		then(40, mc -> onServer(mc, server -> {
			var arena = server.getLevel(dev.rubymod.game.Duels.ARENA);
			buddy = net.fabricmc.fabric.api.entity.FakePlayer.get(arena,
					new com.mojang.authlib.GameProfile(java.util.UUID.fromString("00000000-0000-0000-0000-0000000b0d1e"), "Buddy"));
			dev.rubymod.game.Horde.addTestFighter(buddy);
			log("with buddy: {}", dev.rubymod.game.Horde.debugState());
		}));
		then(10, mc -> {
			command(mc, "effect give @a minecraft:resistance infinite 255 true");
			command(mc, "execute as @p at @s run tp @s -20003.5 66 4.5 -135 15");
			onServer(mc, server -> dev.rubymod.game.Horde.skipWait());
		});
		then(60, mc -> {
			log("wave 1: {}", dev.rubymod.game.Horde.debugState());
			shot(mc, "plus-cache");
		});
		// Buddy goes down; we crouch next to them to bring them back.
		then(10, mc -> onServer(mc, server -> {
			buddy.teleportTo(-20004.5, 66, 6.5);
			dev.rubymod.game.Horde.knockDown(buddy);
			log("buddy down: {}", dev.rubymod.game.Horde.debugState());
		}));
		then(5, mc -> command(mc, "execute as @p at @s run tp @s -20003.5 66 4.5 -60 30"));
		then(20, mc -> shot(mc, "plus-down"));
		then(5, mc -> mc.options.keyShift.setDown(true));
		then(30, mc -> shot(mc, "plus-reviving"));
		then(50, mc -> {
			mc.options.keyShift.setDown(false);
			log("after revive: {}", dev.rubymod.game.Horde.debugState());
		});
		// Let raiders reach the cache for a while (we stand back in a tower).
		then(5, mc -> command(mc, "execute as @p at @s run tp @s -19971.5 72 29.5 135 25"));
		then(500, mc -> {
			log("cache under attack: {}", dev.rubymod.game.Horde.debugState());
			shot(mc, "plus-raiders");
		});
		// Clear the wave: rubies for the team.
		then(5, mc -> command(mc, "kill @e[tag=rubymod_horde,type=!minecraft:text_display]"));
		then(30, mc -> command(mc, "kill @e[tag=rubymod_horde,type=!minecraft:text_display]"));
		then(30, mc -> command(mc, "kill @e[tag=rubymod_horde,type=!minecraft:text_display]"));
		then(30, mc -> command(mc, "kill @e[tag=rubymod_horde,type=!minecraft:text_display]"));
		then(40, mc -> {
			log("cleared: {} screen {}", dev.rubymod.game.Horde.debugState(), mc.gui.screen());
			shot(mc, "plus-cleared");
		});
		// Open the Armory and buy a turret and some walls.
		then(5, mc -> mc.player.connection.sendCommand("horde shop"));
		then(20, mc -> {
			log("shop open: {}", mc.gui.screen());
			shot(mc, "plus-shop");
		});
		then(5, mc -> {
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.HordeBuy("turret"));
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.HordeBuy("walls"));
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.HordeBuy("tank"));
		});
		then(20, mc -> {
			log("bought: {} | {}", dev.rubymod.game.Horde.debugState(), inventorySummary(mc));
			shot(mc, "plus-shop-after");
			mc.gui.setScreen(null);
		});
		// Fog wave.
		then(5, mc -> onServer(mc, server -> dev.rubymod.game.Horde.jumpTo(server, 4)));
		then(5, mc -> command(mc, "execute as @p at @s run tp @s -20000.5 66 -4.5 180 5"));
		then(260, mc -> {
			log("fog wave: {} darkness {}", dev.rubymod.game.Horde.debugState(), mc.player.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS));
			shot(mc, "plus-fog");
		});
		// Air raid.
		then(5, mc -> {
			mc.gui.setScreen(null);
			onServer(mc, server -> dev.rubymod.game.Horde.jumpTo(server, 7));
		});
		then(140, mc -> {
			var heli = mc.level.getEntitiesOfClass(dev.rubymod.entity.AircraftEntity.class, mc.player.getBoundingBox().inflate(80));
			log("air raid: {} gunships {} skyhammer {}", dev.rubymod.game.Horde.debugState(), heli.size(),
					mc.player.getInventory().contains(new net.minecraft.world.item.ItemStack(dev.rubymod.registry.ModItems.SKYHAMMER)));
			if (!heli.isEmpty()) {
				var h = heli.get(0);
				var to = h.position().subtract(mc.player.getEyePosition());
				mc.player.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
				mc.player.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))));
			}
		});
		then(10, mc -> shot(mc, "plus-airraid"));
		then(100, mc -> log("after 5s of air raid: health {} {}", mc.player.getHealth(), dev.rubymod.game.Horde.debugState()));
		// Brutes, with two players.
		then(5, mc -> onServer(mc, server -> dev.rubymod.game.Horde.jumpTo(server, 5)));
		then(200, mc -> {
			log("brute wave: {}", dev.rubymod.game.Horde.debugState());
			var brutes = mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.Ravager.class, mc.player.getBoundingBox().inflate(90));
			log("brutes {}", brutes.size());
			shot(mc, "plus-brute");
		});
		// Everyone down: one Second Wind, then game over.
		then(5, mc -> onServer(mc, server -> {
			dev.rubymod.game.Horde.knockDown(buddy);
			dev.rubymod.game.Horde.knockDown(server.getPlayerList().getPlayers().get(0));
			log("team down: {}", dev.rubymod.game.Horde.debugState());
		}));
		then(80, mc -> log("after second wind: {} mode {}", dev.rubymod.game.Horde.debugState(), mc.gameMode.getPlayerMode()));
		then(5, mc -> onServer(mc, server -> {
			dev.rubymod.game.Horde.knockDown(server.getPlayerList().getPlayers().get(0));
			log("down again: {}", dev.rubymod.game.Horde.debugState());
		}));
		then(30, mc -> shot(mc, "plus-gameover"));
		then(240, mc -> log("end: {} in {}", dev.rubymod.game.Horde.debugState(), mc.player.level().dimension().identifier()));
	}

	/** Stand in the Horde outpost (unkillable) and watch the enemies come: where they are, what they are. */
	private static void hordeFightScript() {
		then(0, mc -> {
			command(mc, "time set noon");
			command(mc, "difficulty normal");
			command(mc, "gamemode survival @a");
		});
		then(20, mc -> command(mc, "execute as @p run play horde"));
		then(40, mc -> {
			command(mc, "effect give @a minecraft:resistance infinite 255 true");
			command(mc, "effect give @a minecraft:regeneration infinite 5 true");
			command(mc, "execute as @p at @s run tp @s -20000.5 66 -4.5 180 5");
		});
		for (int i = 0; i < 8; i++) {
			int n = i;
			then(100, mc -> {
				var c = new net.minecraft.world.phys.Vec3(-20000.5, 65, 0.5);
				var mobs = mc.level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new net.minecraft.world.phys.AABB(c, c).inflate(70));
				java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
				double near = 999;
				for (var m : mobs) {
					kinds.merge(m.getType().getDescriptionId().replace("entity.minecraft.", "").replace("entity.rubymod.", ""), 1, Integer::sum);
					near = Math.min(near, m.position().distanceTo(mc.player.position()));
				}
				log("t={}s health {} mobs {} closest {}m", (n + 1) * 5, mc.player.getHealth(), kinds, Math.round(near));
				shot(mc, "fight-" + n);
			});
		}
	}

	/** The hub, the game picker, a few waves of Co-op Horde, going down, and back to Ruby Wars with your things. */
	private static void hubScript() {
		then(0, mc -> {
			command(mc, "time set noon");
			command(mc, "weather clear");
			command(mc, "give @p minecraft:diamond 5");
			log("difficulty {}", mc.level.getDifficulty());
		});
		then(20, mc -> log("before: {}", inventorySummary(mc)));
		then(5, mc -> command(mc, "execute as @p run hub"));
		then(80, mc -> {
			log("in hub: {}", inventorySummary(mc));
			shot(mc, "hub-arrive");
		});
		then(5, mc -> command(mc, "execute as @p at @s run tp @s 0.5 101 4.5 180 -8"));
		then(60, mc -> shot(mc, "hub-wide"));
		then(5, mc -> mc.gui.setScreen(new ModeScreen()));
		then(30, mc -> shot(mc, "hub-picker"));
		then(5, mc -> mc.gui.setScreen(null));
		// Walk into the Horde portal.
		then(5, mc -> command(mc, "execute as @p at @s run tp @s 7.5 101 -13.5 180 0"));
		then(60, mc -> {
			log("horde: {}", inventorySummary(mc));
			shot(mc, "horde-arrive");
		});
		then(5, mc -> command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 10"));
		then(200, mc -> shot(mc, "horde-wave1"));
		then(60, mc -> {
			log("wave mobs: {}", mc.level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, mc.player.getBoundingBox().inflate(80)).size());
			shot(mc, "horde-wave1b");
		});
		then(5, mc -> command(mc, "kill @e[tag=rubymod_horde]"));
		then(60, mc -> command(mc, "kill @e[tag=rubymod_horde]"));
		then(60, mc -> command(mc, "kill @e[tag=rubymod_horde]"));
		then(60, mc -> command(mc, "kill @e[tag=rubymod_horde]"));
		then(40, mc -> {
			log("after wave 1: {}", inventorySummary(mc));
			shot(mc, "horde-cleared");
		});
		then(5, mc -> command(mc, "execute as @p at @s run tp @s -20000.5 75 -20 0 30"));
		then(340, mc -> shot(mc, "horde-wave2"));
		then(5, mc -> command(mc, "execute as @p at @s run tp @s -20000.5 66 0 0 0"));
		then(5, mc -> command(mc, "kill @p"));
		then(40, mc -> {
			log("down: {}", inventorySummary(mc));
			shot(mc, "horde-gameover");
		});
		then(240, mc -> {
			log("after game over: {}", inventorySummary(mc));
			shot(mc, "hub-back");
		});
		then(5, mc -> command(mc, "execute as @p run play rubywars"));
		then(80, mc -> {
			log("back in Ruby Wars: {}", inventorySummary(mc));
			shot(mc, "rubywars-back");
		});
	}

	/** A camera tour of the Ruby Wars map (run on a world made with the Ruby Wars Map type). */
	private static void mapTourScript() {
		then(0, mc -> {
			command(mc, "time set noon");
			command(mc, "weather clear");
			command(mc, "gamemode spectator @a");
		});
		String[][] stops = {
				{"crater", "0 100 -175 0 25"},
				{"crater-floor", "20 70 20 135 5"},
				{"mountains", "160 105 160 -135 10"},
				{"pass", "300 90 300 -135 15"},
				{"home-green", "250 90 250 45 15"},
				{"home-pine", "-250 90 250 135 15"},
				{"home-desert", "-250 90 -250 -135 15"},
				{"home-snow", "250 90 -250 -45 15"},
				{"river", "420 75 -30 -90 15"},
				{"coast", "0 95 650 0 15"},
				{"high", "300 220 300 -135 40"},
		};
		for (String[] stop : stops) {
			then(10, mc -> command(mc, "tp @p " + stop[1]));
			then(160, mc -> {
				log("at {}: {}", stop[0], mc.level.getChunkSource().gatherStats());
				shot(mc, "map-" + stop[0]);
			});
		}
	}

	// ----- Scripts -----

	private static void buildScript(String mode) {
		at = 40;
		then(0, mc -> {
			command(mc, "gamerule send_command_feedback false");
			command(mc, "gamerule sendCommandFeedback false");
		});
		if (mode.equals("fixes")) {
			final double[] saved = new double[3];
			then(0, mc -> {
				command(mc, "gamemode survival @a");
				command(mc, "difficulty normal");
			});
			then(900, mc -> onServer(mc, server -> log("after first-time builds: tick max {} ms avg {} ms", maxTickMs(server),
					server.getAverageTickTimeNanos() / 1_000_000)));
			then(20, mc -> {
				saved[0] = mc.player.getX();
				saved[1] = mc.player.getY();
				saved[2] = mc.player.getZ();
				command(mc, "execute as @p run hub");
			});
			then(60, mc -> onServer(mc, server -> {
				// Someone builds right where we left off.
				var level = server.overworld();
				var at = net.minecraft.core.BlockPos.containing(saved[0], saved[1], saved[2]);
				for (int y = 0; y < 4; y++) {
					level.setBlock(at.above(y), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
				}
				log("built stone at {}", at.toShortString());
			}));
			then(10, mc -> command(mc, "execute as @p run play rubywars"));
			then(60, mc -> onServer(mc, server -> {
				var me = me(server);
				var feet = me.blockPosition();
				log("back in Ruby Wars at y {} (left at {}): feet {} head {}, in wall {}", me.getY(), saved[1],
						me.level().getBlockState(feet).getBlock(), me.level().getBlockState(feet.above()).getBlock(), me.isInWall());
			}));
			then(10, mc -> command(mc, "execute as @p run play horde"));
			then(60, mc -> log("horde countdown: {}", dev.rubymod.game.Horde.debugState()));
			then(5, mc -> mc.player.connection.sendCommand("horde ready"));
			for (int i = 0; i < 8; i++) {
				then(40, mc -> log("after ready: {}", dev.rubymod.game.Horde.debugState()));
			}
			then(5, mc -> command(mc, "kill @e[tag=rubymod_horde,type=!minecraft:text_display]"));
			then(30, mc -> command(mc, "kill @e[tag=rubymod_horde,type=!minecraft:text_display]"));
			then(30, mc -> command(mc, "kill @e[tag=rubymod_horde,type=!minecraft:text_display]"));
			then(30, mc -> command(mc, "kill @e[tag=rubymod_horde,type=!minecraft:text_display]"));
			then(30, mc -> {
				log("break: {} screen {}", dev.rubymod.game.Horde.debugState(), mc.gui.screen());
				shot(mc, "fixes-break");
			});
			then(200, mc -> log("still the break 10s later: {}", dev.rubymod.game.Horde.debugState()));
			then(5, mc -> mc.player.connection.sendCommand("horde ready"));
			then(140, mc -> log("after ready again (wave 2 should have started): {}", dev.rubymod.game.Horde.debugState()));
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("extras")) {
			extrasScript();
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.startsWith("party")) {
			partyScript(mode.length() > 5 ? mode.substring(6) : "");
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("rift")) {
			riftScript();
			return;
		}
		if (mode.equals("freeroam")) {
			freeRoamScript();
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("riftgunmen")) {
			// One gunman of each rank, 14 blocks away, 10 seconds each: how much damage do they do?
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "gamemode survival @a");
				command(mc, "difficulty normal");
				command(mc, "tp @p ~ ~ ~ 0 5");
			});
			then(300, mc -> log("world ready"));
			for (int act = 1; act <= 3; act++) {
				int a = act;
				float[] taken = new float[1];
				int[] hits = new int[1];
				net.minecraft.world.entity.Mob[] gunman = new net.minecraft.world.entity.Mob[1];
				then(5, mc -> onServer(mc, server -> {
					var me = me(server);
					me.setHealth(me.getMaxHealth());
					var level = (net.minecraft.server.level.ServerLevel) me.level();
					gunman[0] = dev.rubymod.game.rift.RiftEnemies.testGunman(level, me.blockPosition().south(14), a);
					level.addFreshEntity(gunman[0]);
					gunman[0].setTarget(me);
				}));
				for (int t = 0; t < 200; t++) {
					int tick = t;
					then(1, mc -> onServer(mc, server -> {
						var me = me(server);
						gunman[0].setTarget(me);
						float lost = me.getMaxHealth() - me.getHealth();
						if (lost > 0) {
							taken[0] += lost;
							hits[0]++;
							me.setHealth(me.getMaxHealth());
						}
					}));
					if (a == 1 && t == 15) {
						then(0, mc -> shot(mc, "gunman-laser"));
					}
				}
				then(1, mc -> onServer(mc, server -> {
					log("act {}: {} ({} hp) did {} damage ({} hits) in 10 s", a, gunman[0].getCustomName().getString(), gunman[0].getMaxHealth(),
							taken[0], hits[0]);
					gunman[0].discard();
				}));
			}
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("riftfixes")) {
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(160, mc -> command(mc, "effect give @a minecraft:resistance infinite 255 true"));
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			then(30, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode())));
			then(200, mc -> onServer(mc, server -> {
				var level = (net.minecraft.server.level.ServerLevel) me(server).level();
				long glowing = level.getEntities((net.minecraft.world.entity.Entity) null, me(server).getBoundingBox().inflate(80),
						e -> e.entityTags().contains(dev.rubymod.game.rift.Rift.TAG) && e instanceof net.minecraft.world.entity.LivingEntity l
								&& l.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING)).size();
				log("fight start: {} | glowing {}", dev.rubymod.game.rift.Rift.debugState(), glowing);
			}));
			// Free ammo: empty the gun, no ammo in the bag, reload.
			then(5, mc -> onServer(mc, server -> {
				var me = me(server);
				for (int i = 0; i < me.getInventory().getContainerSize(); i++) {
					var st = me.getInventory().getItem(i);
					if (st.getItem() instanceof dev.rubymod.gun.GunItem && me.getInventory().getSelectedSlot() != i) {
						continue;
					}
					if (st.getItem() instanceof dev.rubymod.gun.GunItem) {
						st.set(dev.rubymod.registry.ModComponents.AMMO, 0);
					}
				}
				for (int k = 0; k < me.getInventory().getContainerSize(); k++) {
					var st = me.getInventory().getItem(k);
					if (st.is(dev.rubymod.registry.ModItems.LIGHT_AMMO) || st.is(dev.rubymod.registry.ModItems.HEAVY_AMMO)) {
						st.setCount(0);
					}
				}
				log("held {} ammo {} | reserve heavy {} light {}", me.getMainHandItem(), dev.rubymod.gun.GunItem.ammo(me.getMainHandItem()),
						me.getInventory().countItem(dev.rubymod.registry.ModItems.HEAVY_AMMO), me.getInventory().countItem(dev.rubymod.registry.ModItems.LIGHT_AMMO));
				dev.rubymod.gun.Gunfire.startReload(me);
				try {
					var f = dev.rubymod.gun.Gunfire.class.getDeclaredField("RELOADS");
					f.setAccessible(true);
					log("reloading? {} | free ammo {} | slot {}", ((java.util.Map<?, ?>) f.get(null)).get(me.getUUID()), dev.rubymod.gun.Gunfire.freeAmmo(me),
							me.getInventory().getSelectedSlot());
				} catch (ReflectiveOperationException e) {
					log("no field {}", e);
				}
			}));
			for (int k = 0; k < 20; k++) {
				then(10, mc -> onServer(mc, server -> log("reload tick: time {} avg tick {} ms max {} ms ammo {} slot {} held {}", me(server).level().getGameTime(), server.getAverageTickTimeNanos() / 1_000_000, maxTickMs(server), dev.rubymod.gun.GunItem.ammo(me(server).getMainHandItem()),
						me(server).getInventory().getSelectedSlot(), me(server).getMainHandItem())));
			}
			then(20, mc -> onServer(mc, server -> log("after reload: ammo {}", dev.rubymod.gun.GunItem.ammo(me(server).getMainHandItem()))));
			// More max health: arrives full.
			then(5, mc -> onServer(mc, server -> {
				log("health before {} / {}", me(server).getHealth(), me(server).getMaxHealth());
				dev.rubymod.game.rift.Rift.testSkip(server, "titan_blood");
			}));
			then(10, mc -> onServer(mc, server -> log("health after {} / {}", me(server).getHealth(), me(server).getMaxHealth())));
			then(5, mc -> {
				mc.gui.setScreen(null);
				mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
			});
			then(20, mc -> shot(mc, "riftfix-inventory"));
			then(5, mc -> mc.gui.setScreen(null));
			// The merchant.
			then(5, mc -> onServer(mc, server -> {
				dev.rubymod.game.rift.Rift.testSkip(server, "clear");
			}));
			then(100, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "room:SHOP")));
			for (int i = 0; i < 20; i++) {
				then(20, mc -> {});
			}
			then(200, mc -> {
				log("shop: {} | screen {} | last {}", dev.rubymod.game.rift.Rift.debugState(), mc.gui.screen(),
						dev.rubymod.client.rift.RiftClient.lastScreen == null ? null : dev.rubymod.client.rift.RiftClient.lastScreen.lines());
				dev.rubymod.client.rift.RiftClient.reopen();
			});
			then(30, mc -> shot(mc, "riftfix-shop"));
			then(5, mc -> {
				mc.gui.setScreen(null);
				onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "room:REST"));
			});
			for (int i = 0; i < 20; i++) {
				then(20, mc -> {});
			}
			then(200, mc -> {
				log("arsenal: {} | screen {} | last {}", dev.rubymod.game.rift.Rift.debugState(), mc.gui.screen(),
						dev.rubymod.client.rift.RiftClient.lastScreen == null ? null : dev.rubymod.client.rift.RiftClient.lastScreen.lines());
				shot(mc, "riftfix-arsenal");
			});
			then(5, mc -> {
				var lines = dev.rubymod.client.rift.RiftClient.lastScreen.lines();
				String pick = lines.get(1).split("[|]")[0];
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.RiftChoice("event", pick));
				log("picked {}", pick);
			});
			then(40, mc -> {
				log("inventory now: {}", inventorySummary(mc));
				shot(mc, "riftfix-picked");
			});
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("fusion")) {
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(160, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			then(30, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode())));
			for (int i = 0; i < 30; i++) {
				then(20, mc -> {});
			}
			then(5, mc -> onServer(mc, server -> {
				var me = me(server);
				for (String w : new String[] {"ruby_minigun", "dragons_breath", "rocket_launcher"}) {
					me.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
							net.minecraft.resources.Identifier.parse("rubymod:" + w))));
				}
				dev.rubymod.game.rift.Rift.testSkip(server, "clear");
			}));
			then(100, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "room:REST")));
			for (int i = 0; i < 25; i++) {
				then(20, mc -> {});
			}
			then(40, mc -> {
				log("arsenal: {}", dev.rubymod.client.rift.RiftClient.lastScreen == null ? null : dev.rubymod.client.rift.RiftClient.lastScreen.lines());
				dev.rubymod.client.rift.RiftClient.reopen();
			});
			then(20, mc -> shot(mc, "fusion-arsenal"));
			then(5, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.RiftChoice("event", "arm:fuse")));
			then(30, mc -> {
				log("fusions: {}", dev.rubymod.client.rift.RiftClient.lastScreen.lines());
				shot(mc, "fusion-list");
			});
			then(5, mc -> {
				String pick = dev.rubymod.client.rift.RiftClient.lastScreen.lines().get(1).split("[|]")[0];
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.RiftChoice("event", pick));
				log("fusing {}", pick);
			});
			then(30, mc -> {
				for (int i = 0; i < 36; i++) {
					var st = mc.player.getInventory().getItem(i);
					if (st.has(dev.rubymod.registry.ModComponents.TRAITS)) {
						log("fused: slot {} {} traits {} stats {}", i, st.getHoverName().getString(), st.get(dev.rubymod.registry.ModComponents.TRAITS),
								((dev.rubymod.gun.GunItem) st.getItem()).stats(st));
					}
				}
				shot(mc, "fusion-done");
			});
			then(5, mc -> {
				mc.gui.setScreen(null);
				onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "room:FIGHT"));
			});
			for (int i = 0; i < 25; i++) {
				then(20, mc -> {});
			}
			then(5, mc -> onServer(mc, server -> {
				var me = me(server);
				me.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 4, false, false));
				for (int i = 0; i < 36; i++) {
					if (me.getInventory().getItem(i).has(dev.rubymod.registry.ModComponents.TRAITS)) {
						var st = me.getInventory().getItem(i).copy();
						me.getInventory().setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
						me.getInventory().setItem(0, st);
					}
				}
				me.getInventory().setSelectedSlot(0);
				dev.rubymod.game.rift.Rift.testSkip(server, "spawn");
			}));
			then(2, mc -> mc.player.getInventory().setSelectedSlot(0));
			for (int t = 0; t < 120; t++) {
				int tick = t;
				then(1, mc -> onServer(mc, server -> {
					var me = me(server);
					var level = (net.minecraft.server.level.ServerLevel) me.level();
					var near = level.getEntities((net.minecraft.world.entity.Entity) null, me.getBoundingBox().inflate(30),
							e -> dev.rubymod.game.rift.Rift.isEnemy(e) && e.isAlive());
					near.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(me)));
					if (!near.isEmpty()) {
						var to = near.get(0).getBoundingBox().getCenter().subtract(me.getEyePosition());
						me.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
						me.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))));
					}
					me.clearFire();
					var stack = me.getInventory().getItem(0);
					if (stack.getItem() instanceof dev.rubymod.gun.GunItem g) {
						dev.rubymod.gun.Gunfire.tryFire(level, me, stack, g);
					}
				}));
				if (t == 40) {
					then(0, mc -> {
						var sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
						mc.player.setYRot(sp.getYRot());
						mc.player.setXRot(sp.getXRot());
						shot(mc, "fusion-fire");
						log("firing: {}", dev.rubymod.game.rift.Rift.debugState());
					});
				}
			}
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("portaltest")) {
			// The hub's portals, the Rift portal, fair spawns, Hold the Zone, and the brighter screens.
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
				command(mc, "execute as @p run hub");
			});
			then(80, mc -> onServer(mc, server -> {
				var me = me(server);
				me.teleportTo(0.5, dev.rubymod.game.Hub.FLOOR + 1, 4.5);
				me.setYRot(180);
				me.setXRot(-8);
			}));
			then(10, mc -> {
				mc.player.setYRot(180);
				mc.player.setXRot(-8);
			});
			then(30, mc -> shot(mc, "fixes-hub-front"));
			then(5, mc -> onServer(mc, server -> {
				me(server).setYRot(0);
			}));
			then(5, mc -> mc.player.setYRot(0));
			then(20, mc -> shot(mc, "fixes-hub-back"));
			// Walk into the Rift's portal.
			then(5, mc -> onServer(mc, server -> me(server).teleportTo(0.5, dev.rubymod.game.Hub.FLOOR + 1, -13.5)));
			then(80, mc -> onServer(mc, server -> log("after the Rift portal: {}", dev.rubymod.game.Hub.where(me(server)))));
			then(40, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			for (int i = 0; i < 30; i++) {
				then(20, mc -> {
					onServer(mc, server -> me(server).addEffect(new net.minecraft.world.effect.MobEffectInstance(
							net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 4, false, false)));
					if (dev.rubymod.game.rift.Rift.debugState().startsWith("VOTE")) {
						net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
								new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode()));
					}
				});
			}
			// Where did the first monsters come through?
			then(5, mc -> onServer(mc, server -> {
				var me = me(server);
				var mobs = me.level().getEntities((net.minecraft.world.entity.Entity) null, me.getBoundingBox().inflate(60),
						e -> dev.rubymod.game.rift.Rift.isEnemy(e));
				log("monsters: {} | distances {} | {}", mobs.size(), mobs.stream().map(e -> Math.round(e.distanceTo(me))).sorted().toList(),
						dev.rubymod.game.rift.Rift.debugState());
			}));
			then(5, mc -> onServer(mc, server -> {
				dev.rubymod.game.rift.Rift.testSkip(server, "clear");
			}));
			then(40, mc -> shot(mc, "fixes-offer"));
			then(5, mc -> {
				mc.gui.setScreen(null);
				onServer(mc, server -> {
					dev.rubymod.game.rift.Rift.testSkip(server, "nooffer");
					dev.rubymod.game.rift.Rift.testSkip(server, "room:HOLD");
				});
			});
			for (int i = 0; i < 15; i++) {
				then(20, mc -> mc.gui.setScreen(null));
			}
			then(40, mc -> {
				log("hold: zone {} | {}", dev.rubymod.game.rift.Rift.zonePos(), dev.rubymod.game.rift.Rift.debugState());
				shot(mc, "fixes-hold");
			});
			// Never step into the zone: the waves must still run out.
			for (int t = 0; t < 2400; t++) {
				int tick = t;
				then(1, mc -> onServer(mc, server -> {
					var me = me(server);
					var level = (net.minecraft.server.level.ServerLevel) me.level();
					if (!dev.rubymod.game.rift.Rift.debugState().startsWith("FIGHT")) {
						return;
					}
					var zone = dev.rubymod.game.rift.Rift.zonePos();
					if (zone != null && me.position().distanceTo(zone) < 8) {
						me.teleportTo(zone.x + 12, zone.y, zone.z);
					}
					aimAtNearest(server);
					var stack = me.getInventory().getItem(0);
					me.getInventory().setSelectedSlot(0);
					if (stack.getItem() instanceof dev.rubymod.gun.GunItem g) {
						if (dev.rubymod.gun.GunItem.ammo(stack) <= 0) {
							stack.set(dev.rubymod.registry.ModComponents.AMMO, 30);
						}
						dev.rubymod.gun.Gunfire.tryFire(level, me, stack, g);
					}
					if (tick % 400 == 0) {
						log("hold t{}: {}", tick, dev.rubymod.game.rift.Rift.debugState());
					}
				}));
			}
			then(5, mc -> {
				log("hold end: {}", dev.rubymod.game.rift.Rift.debugState());
				shot(mc, "fixes-hold-end");
			});
			then(5, mc -> {
				mc.gui.setScreen(null);
				onServer(mc, server -> {
					dev.rubymod.game.rift.Rift.testSkip(server, "nooffer");
					dev.rubymod.game.rift.Rift.testSkip(server, "room:SHOP");
				});
			});
			for (int i = 0; i < 12; i++) {
				then(20, mc -> {});
			}
			then(5, mc -> dev.rubymod.client.rift.RiftClient.reopen());
			then(20, mc -> shot(mc, "fixes-shop"));
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("fkey")) {
			// F in the Rift: your ability, and your hands don't swap.
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty peaceful");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(200, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.RiftChoice("pick_ability", "nova")));
			then(10, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			for (int i = 0; i < 30; i++) {
				then(20, mc -> {
					if (dev.rubymod.game.rift.Rift.debugState().startsWith("VOTE")) {
						net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
								new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode()));
					}
				});
			}
			then(5, mc -> onServer(mc, server -> {
				var me = me(server);
				me.getInventory().setSelectedSlot(0);
				log("before F: main {} | off {} | ability wait {} | {}", me.getMainHandItem().getItem(), me.getOffhandItem().getItem(),
						dev.rubymod.game.rift.Rift.abilityWait(me), dev.rubymod.game.rift.Rift.debugState());
			}));
			then(5, mc -> {
				mc.gui.setScreen(null);
				net.minecraft.client.KeyMapping.click(mc.options.keySwapOffhand.getDefaultKey());
			});
			then(10, mc -> onServer(mc, server -> {
				var me = me(server);
				log("after F: main {} | off {} | ability wait {}", me.getMainHandItem().getItem(), me.getOffhandItem().getItem(),
						dev.rubymod.game.rift.Rift.abilityWait(me));
			}));
			then(10, mc -> shot(mc, "fkey-hud"));
			then(400, mc -> net.minecraft.client.KeyMapping.click(dev.rubymod.client.RubyModClient.autopilotKey.getDefaultKey()));
			then(10, mc -> onServer(mc, server -> log("after Z (once F's cooldown is over): ability wait {}", dev.rubymod.game.rift.Rift.abilityWait(me(server)))));
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("convoy")) {
			// Ruby Convoy from the mine to the vault: the job cards, each stage's set piece, a depot, the Dreadnought, the delivery.
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
			});
			then(20, mc -> command(mc, "execute as @p run play convoy"));
			then(80, mc -> shot(mc, "convoy-jobs"));
			then(5, mc -> {
				if (mc.gui.screen() instanceof dev.rubymod.client.convoy.ConvoyScreen s) {
					s.pick(1);
				}
			});
			for (int i = 0; i < 12; i++) {
				then(40, mc -> log("waiting: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			}
			then(5, mc -> onServer(mc, server -> {
				var me = me(server);
				me.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 4, false, false));
				me.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION, -1, 2, false, false));
				log("ready {} | {} | {}", dev.rubymod.game.convoy.Convoy.ready(), dev.rubymod.game.convoy.Convoy.debugState(), inventorySummary(mc));
			}));
			convoyView(-7, 3, 10);
			then(30, mc -> shot(mc, "convoy-mine"));
			then(5, mc -> onServer(mc, server -> {
				dev.rubymod.game.convoy.Convoy.testSkip(server, "start");
			}));
			then(40, mc -> onServer(mc, server -> {
				dev.rubymod.game.convoy.Convoy.testSkip(server, "ride");
				dev.rubymod.game.convoy.Convoy.testSkip(server, "hp:5000");
			}));
			then(200, mc -> {
				log("rolling: {} | riding {}", dev.rubymod.game.convoy.Convoy.debugState(), mc.player.getVehicle());
				mc.player.setYRot(0);
				mc.player.setXRot(10);
			});
			then(10, mc -> shot(mc, "convoy-ride"));
			// Greenwood Road: the fallen logs.
			convoyGoto("" + (dev.rubymod.game.convoy.ConvoyRouteView.logs() - 6));
			then(120, mc -> log("logs: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			convoyView(9, 4, 2);
			then(20, mc -> shot(mc, "convoy-logs"));
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.convoy.Convoy.testSkip(server, "logs")));
			then(20, mc -> onServer(mc, server -> dev.rubymod.game.convoy.Convoy.testSkip(server, "ride")));
			then(80, mc -> log("after logs: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			// Into Depot 1.
			convoyGoto("170");
			then(20, mc -> onServer(mc, server -> dev.rubymod.game.convoy.Convoy.testSkip(server, "clear")));
			then(300, mc -> log("depot: {} | screen {}", dev.rubymod.game.convoy.Convoy.debugState(), mc.gui.screen()));
			then(10, mc -> shot(mc, "convoy-depot-cards"));
			then(5, mc -> {
				if (mc.gui.screen() instanceof dev.rubymod.client.convoy.ConvoyScreen s) {
					s.pick(0);
				}
			});
			convoyView(-9, 5, 14);
			then(20, mc -> shot(mc, "convoy-depot"));
			then(160, mc -> log("left depot: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			// Ember Town, and its tanks.
			convoyGoto("" + (dev.rubymod.game.convoy.ConvoyRouteView.start(1) + 40));
			then(100, mc -> log("town: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			convoyView(-6, 5, -8);
			then(20, mc -> shot(mc, "convoy-town"));
			convoyGoto("" + (dev.rubymod.game.convoy.ConvoyRouteView.square() - 24));
			then(160, mc -> log("tanks: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			convoyView(0, 9, -10);
			then(20, mc -> shot(mc, "convoy-tanks"));
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.convoy.Convoy.testSkip(server, "tanks")));
			then(60, mc -> log("after tanks: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			// Red Canyon: the gunship and the bridge.
			convoyGoto("346");
			then(20, mc -> onServer(mc, server -> dev.rubymod.game.convoy.Convoy.testSkip(server, "clear")));
			then(300, mc -> log("depot 2: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.convoy.Convoy.testSkip(server, "pick")));
			then(140, mc -> log("left depot 2: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			convoyGoto("" + (dev.rubymod.game.convoy.ConvoyRouteView.gap() - 8));
			then(160, mc -> log("bridge: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			convoyView(10, 6, -6);
			then(20, mc -> shot(mc, "convoy-bridge"));
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.convoy.Convoy.testSkip(server, "bridge")));
			then(40, mc -> onServer(mc, server -> dev.rubymod.game.convoy.Convoy.testSkip(server, "ride")));
			then(100, mc -> log("after bridge: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			convoyView(12, 4, 6);
			then(20, mc -> shot(mc, "convoy-bridge-built"));
			// Frost Pass: the avalanche.
			convoyGoto("522");
			then(20, mc -> onServer(mc, server -> dev.rubymod.game.convoy.Convoy.testSkip(server, "clear")));
			then(220, mc -> log("depot 3: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.convoy.Convoy.testSkip(server, "pick")));
			then(140, mc -> log("left depot 3: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			convoyGoto("" + (dev.rubymod.game.convoy.ConvoyRouteView.snow() - 26));
			then(200, mc -> log("avalanche: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			convoyView(-8, 5, -6);
			then(20, mc -> shot(mc, "convoy-avalanche"));
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.convoy.Convoy.testSkip(server, "logs")));
			then(40, mc -> log("dug out: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			// The Iron Fortress and the Dreadnought.
			convoyGoto("698");
			then(20, mc -> onServer(mc, server -> dev.rubymod.game.convoy.Convoy.testSkip(server, "clear")));
			then(280, mc -> log("depot 4: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.convoy.Convoy.testSkip(server, "pick")));
			then(140, mc -> log("left depot 4: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			convoyGoto("" + (dev.rubymod.game.convoy.ConvoyRouteView.courtyard() - 4));
			then(200, mc -> log("boss: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			convoyView(-10, 8, -12);
			then(20, mc -> shot(mc, "convoy-boss"));
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.convoy.Convoy.testSkip(server, "ride")));
			then(160, mc -> log("boss fight: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.convoy.Convoy.testSkip(server, "killboss")));
			then(120, mc -> log("boss down: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.convoy.Convoy.testSkip(server, "ride")));
			then(10, mc -> {
				mc.player.setYRot(0);
				mc.player.setXRot(5);
			});
			then(20, mc -> shot(mc, "convoy-gate"));
			for (int i = 0; i < 8; i++) {
				then(100, mc -> log("to the vault: {}", dev.rubymod.game.convoy.Convoy.debugState()));
			}
			then(5, mc -> shot(mc, "convoy-delivered"));
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("hubtour")) {
			// The hub from a few angles (silent: the test client's sound is off).
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "execute as @p run hub");
			});
			double[][] views = {
					{0.5, 1, 8.5, 180, -6}, // where you arrive
					{0.5, 1, 8.5, 0, -6}, // turning round: the Party Games gate
					{0.5, 26, 34, 180, 32}, // from above and behind
					{-26, 14, -2, 250, 18}, // from the side
					{0.5, 2, -6, 180, -12}, // up close: the Rift gate
					{-9, 2, -6, 180, -12}, // up close: the Ruby Wars gate
			};
			then(100, mc -> {});
			for (int i = 0; i < views.length; i++) {
				double[] v = views[i];
				int n = i;
				then(5, mc -> onServer(mc, server -> {
					var me = me(server);
					me.setNoGravity(true);
					me.teleportTo(v[0], dev.rubymod.game.Hub.FLOOR + v[1], v[2]);
					me.setYRot((float) v[3]);
					me.setXRot((float) v[4]);
				}));
				then(6, mc -> {
					mc.player.setYRot((float) v[3]);
					mc.player.setXRot((float) v[4]);
				});
				then(30, mc -> shot(mc, "hub-view-" + n));
			}
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("safety")) {
			// The map's rooms (no lanes), shooting your own companion, and falling into lava.
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(260, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.RiftChoice("companion", "pebble")));
			then(20, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			then(60, mc -> {
				shot(mc, "safety-map");
				var screen = dev.rubymod.client.rift.RiftClient.lastScreen;
				if (screen != null) {
					StringBuilder cols = new StringBuilder();
					for (String line : screen.lines()) {
						String[] f = line.split("[|]", -1);
						if (f.length >= 8 && !f[0].equals("boss") && !f[0].equals("votes") && !f[0].equals("keys")) {
							cols.append(f[1]).append(':').append(f[2]).append('=').append(f[4]).append(' ');
						}
					}
					log("map rooms: {}", cols);
				}
			});
			for (int i = 0; i < 40; i++) {
				then(20, mc -> {
					onServer(mc, server -> me(server).addEffect(new net.minecraft.world.effect.MobEffectInstance(
							net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 4, false, false)));
					if (dev.rubymod.game.rift.Rift.debugState().startsWith("VOTE")) {
						net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
								new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode()));
					}
				});
			}
			// Shoot the companion point blank.
			float[] before = {0};
			then(5, mc -> onServer(mc, server -> {
				var me = me(server);
				var stack = new net.minecraft.world.item.ItemStack(dev.rubymod.registry.ModItems.COMBAT_SHOTGUN);
				stack.set(dev.rubymod.registry.ModComponents.AMMO, 8);
				me.getInventory().setItem(0, stack);
				me.getInventory().setSelectedSlot(0);
			}));
			for (int t = 0; t < 40; t++) {
				then(1, mc -> onServer(mc, server -> {
					var me = me(server);
					var level = (net.minecraft.server.level.ServerLevel) me.level();
					var pets = level.getEntitiesOfClass(net.minecraft.world.entity.animal.golem.IronGolem.class, me.getBoundingBox().inflate(40),
							g -> g.hasCustomName() && g.getCustomName().getString().contains("Pebble"));
					if (pets.isEmpty()) {
						return;
					}
					var pet = pets.get(0);
					if (before[0] == 0) {
						before[0] = pet.getHealth();
						me.teleportTo(pet.getX() + 2, pet.getY(), pet.getZ());
					}
					var to = pet.getBoundingBox().getCenter().subtract(me.getEyePosition());
					me.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
					me.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))));
					var stack = me.getInventory().getItem(0);
					stack.set(dev.rubymod.registry.ModComponents.AMMO, 8);
					if (stack.getItem() instanceof dev.rubymod.gun.GunItem g) {
						dev.rubymod.gun.Gunfire.tryFire(level, me, stack, g);
					}
					me.hurtServer(level, me.damageSources().playerAttack(me), 0);
					pet.hurtServer(level, me.damageSources().playerAttack(me), 10);
				}));
			}
			then(5, mc -> onServer(mc, server -> {
				var me = me(server);
				var pets = me.level().getEntitiesOfClass(net.minecraft.world.entity.animal.golem.IronGolem.class, me.getBoundingBox().inflate(40),
						g -> g.hasCustomName() && g.getCustomName().getString().contains("Pebble"));
				log("companion health: before {} after {} ({})", before[0], pets.isEmpty() ? -1 : pets.get(0).getHealth(),
						pets.isEmpty() ? "none" : pets.get(0).getCustomName().getString());
			}));
			// Into the lava.
			then(5, mc -> onServer(mc, server -> {
				var me = me(server);
				me.removeAllEffects();
				var below = me.blockPosition();
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						me.level().setBlock(below.offset(dx, -1, dz), net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState(), 3);
						me.level().setBlock(below.offset(dx, 0, dz), net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState(), 3);
					}
				}
				log("into the lava at {} health {}", me.position(), me.getHealth());
			}));
			then(40, mc -> onServer(mc, server -> log("after the lava: at {} health {} burning {} | {}", me(server).position(), me(server).getHealth(),
					me(server).isOnFire(), dev.rubymod.game.rift.Rift.debugState())));
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("riftextras")) {
			// Companion and mutators (the Altar's EXTRAS page), music, a secret stash, the Codex rewards, the Endless Descent.
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(260, mc -> {
				var send = new dev.rubymod.net.MorePayloads.RiftChoice("companion", "hound");
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(send);
				for (String m : new String[] {"party", "explosive_kills", "low_gravity"}) {
					net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.RiftChoice("mutator", m));
				}
			});
			then(10, mc -> onServer(mc, server -> {
				dev.rubymod.game.rift.RiftMeta.sendTo(me(server));
				log("mutators: {}", dev.rubymod.game.rift.Rift.mutators());
			}));
			then(10, mc -> dev.rubymod.client.rift.RiftClient.reopen());
			then(10, mc -> {
				if (mc.gui.screen() instanceof dev.rubymod.client.rift.ThresholdScreen ts) {
					ts.flip();
				}
			});
			then(10, mc -> shot(mc, "extras-altar"));
			then(5, mc -> {
				mc.gui.setScreen(null);
				log("music in the Threshold: {}", dev.rubymod.client.rift.RiftMusic.current() == null ? "none"
						: dev.rubymod.client.rift.RiftMusic.current().sound().unwrapKey().map(Object::toString).orElse("?"));
				onServer(mc, server -> {
					dev.rubymod.game.rift.Rift.testSkip(server, "secret");
					dev.rubymod.game.rift.Rift.testSkip(server, "start");
				});
			});
			for (int i = 0; i < 40; i++) {
				then(20, mc -> {
					onServer(mc, server -> me(server).addEffect(new net.minecraft.world.effect.MobEffectInstance(
							net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 4, false, false)));
					if (dev.rubymod.game.rift.Rift.debugState().startsWith("VOTE")) {
						net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
								new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode()));
					}
				});
			}
			then(5, mc -> {
				log("music in a fight: {}", dev.rubymod.client.rift.RiftMusic.current() == null ? "none"
						: dev.rubymod.client.rift.RiftMusic.current().sound().unwrapKey().map(Object::toString).orElse("?"));
				onServer(mc, server -> {
					me(server).addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 3, false, false));
					log("secret at {} | {}", dev.rubymod.game.rift.RiftSecrets.where(), dev.rubymod.game.rift.Rift.debugState());
				});
			});
			then(5, mc -> onServer(mc, server -> {
				var at = dev.rubymod.game.rift.RiftSecrets.where();
				if (at != null) {
					var me = me(server);
					me.teleportTo(at.x + 3, at.y + 0.5, at.z + 3);
					var to = at.subtract(me.getEyePosition());
					me.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
					me.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))));
				}
			}));
			then(5, mc -> {
				var sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
				mc.player.setYRot(sp.getYRot());
				mc.player.setXRot(sp.getXRot());
				shot(mc, "extras-secret");
			});
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "opensecret")));
			then(15, mc -> {
				shot(mc, "extras-secret-open");
				log("after secret: {}", dev.rubymod.game.rift.Rift.debugState());
			});
			// Fight with the hound (and the mutators), then level it up.
			then(5, mc -> onServer(mc, server -> {
				var stack = new net.minecraft.world.item.ItemStack(dev.rubymod.registry.ModItems.ARR_RIFLE);
				stack.set(dev.rubymod.registry.ModComponents.AMMO, 30);
				me(server).getInventory().setItem(0, stack);
				dev.rubymod.game.rift.Rift.testSkip(server, "kills:85");
			}));
			for (int t = 0; t < 300; t++) {
				int tick = t;
				then(1, mc -> onServer(mc, server -> {
					var me = me(server);
					var level = (net.minecraft.server.level.ServerLevel) me.level();
					if (!dev.rubymod.game.rift.Rift.debugState().startsWith("FIGHT")) {
						return;
					}
					aimAtNearest(server);
					me.getInventory().setSelectedSlot(0);
					var stack = me.getInventory().getItem(0);
					if (dev.rubymod.gun.GunItem.ammo(stack) <= 0) {
						stack.set(dev.rubymod.registry.ModComponents.AMMO, 30);
					}
					if (tick % 2 == 0 && stack.getItem() instanceof dev.rubymod.gun.GunItem g) {
						dev.rubymod.gun.Gunfire.tryFire(level, me, stack, g);
					}
				}));
				if (t == 120) {
					then(0, mc -> {
						var sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
						mc.player.setYRot(sp.getYRot());
						mc.player.setXRot(sp.getXRot());
						shot(mc, "extras-fight");
						onServer(mc, server -> log("companions near: {}", me(server).level().getEntities((net.minecraft.world.entity.Entity) null,
								me(server).getBoundingBox().inflate(30), e -> e instanceof net.minecraft.world.entity.animal.wolf.Wolf || e.hasCustomName() && e.getCustomName().getString().contains("Ruby Hound"))
								.stream().map(e -> e.getName().getString() + " alive " + e.isAlive()).toList()));
					});
				}
			}
			// The Codex's Rewards tab.
			then(5, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.RiftChoice("codex", "open")));
			then(15, mc -> {
				if (mc.gui.screen() instanceof dev.rubymod.client.rift.CodexScreen cs) {
					cs.tab(5);
				}
			});
			then(10, mc -> shot(mc, "extras-codex-rewards"));
			// Win, then keep going down.
			then(5, mc -> {
				mc.gui.setScreen(null);
				onServer(mc, server -> {
					dev.rubymod.game.rift.Rift.testSkip(server, "nooffer");
					dev.rubymod.game.rift.Rift.testSkip(server, "win");
				});
			});
			then(40, mc -> shot(mc, "extras-descend-offer"));
			then(5, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.RiftChoice("event", "end:descend")));
			then(20, mc -> {
				log("descending: {}", dev.rubymod.game.rift.Rift.debugState());
				onServer(mc, server -> {
					var c = dev.rubymod.game.rift.Rift.roomCenter();
					if (c != null) {
						me(server).teleportTo(c.x, c.y + 0.2, c.z);
					}
				});
			});
			then(60, mc -> {
				log("depth: {}", dev.rubymod.game.rift.Rift.debugState());
				shot(mc, "extras-depth-map");
			});
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("polish")) {
			// The new Mysteries, the relic grid, a ping, and the run summary.
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(160, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			then(40, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode())));
			for (int i = 0; i < 15; i++) {
				then(20, mc -> {});
			}
			then(5, mc -> onServer(mc, server -> {
				me(server).addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 3, false, false));
				int k = 0;
				for (var relic : dev.rubymod.game.rift.Relic.values()) {
					if (relic.tier != dev.rubymod.game.rift.Relic.Tier.CURSED && k++ < 32) {
						dev.rubymod.game.rift.Rift.testSkip(server, relic.id());
					}
				}
			}));
			then(40, mc -> {
				mc.gui.setScreen(null);
				shot(mc, "polish-grid");
			});
			// A ping on the nearest monster.
			then(5, mc -> onServer(mc, server -> aimAtNearest(server)));
			then(3, mc -> {
				var sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
				mc.player.setYRot(sp.getYRot());
				mc.player.setXRot(sp.getXRot());
				dev.rubymod.client.map.Pinger.ping(mc);
			});
			then(20, mc -> shot(mc, "polish-ping"));
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "clear")));
			for (int kind = 3; kind < 13; kind++) {
				int kd = kind;
				then(60, mc -> {
					mc.gui.setScreen(null);
					onServer(mc, server -> {
						dev.rubymod.game.rift.Rift.testSkip(server, "nooffer");
						dev.rubymod.game.Stats.get(me(server)); // keep the gold up for the paid ones
						dev.rubymod.game.rift.Rift.testSkip(server, "event:" + kd);
					});
				});
				for (int i = 0; i < 14; i++) {
					then(20, mc -> {});
				}
				then(5, mc -> shot(mc, "event-" + kd));
				then(5, mc -> {
					var screen = dev.rubymod.client.rift.RiftClient.lastScreen;
					String pick = null;
					if (screen != null) {
						for (String line : screen.lines()) {
							if (!line.startsWith("title|")) {
								pick = line.split("[|]")[0];
								break;
							}
						}
					}
					log("event {} picks {}", kd, pick);
					if (pick != null) {
						net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.RiftChoice("event", pick));
					}
				});
				then(30, mc -> {
					shot(mc, "event-" + kd + "-after");
					log("after event {}: {}", kd, dev.rubymod.game.rift.Rift.debugState());
				});
			}
			then(60, mc -> {
				mc.gui.setScreen(null);
				onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "win"));
			});
			then(40, mc -> shot(mc, "polish-summary"));
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("finale")) {
			// All three keys, the act 3 boss, the Heart of the Rift, the echoes and the Sovereign.
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(160, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			then(40, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode())));
			for (int i = 0; i < 15; i++) {
				then(20, mc -> {});
			}
			then(5, mc -> onServer(mc, server -> {
				me(server).addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 3, false, false));
				for (String r : new String[] {"quick_hands", "twin_barrels", "headhunter", "chain_lightning", "key:flame", "key:gold", "key:life"}) {
					dev.rubymod.game.rift.Rift.testSkip(server, r);
				}
				var stack = new net.minecraft.world.item.ItemStack(dev.rubymod.registry.ModItems.ARR_RIFLE);
				stack.set(dev.rubymod.registry.ModComponents.AMMO, 30);
				me(server).getInventory().setItem(0, stack);
				dev.rubymod.game.rift.Rift.testSkip(server, "clear");
			}));
			then(60, mc -> {
				mc.gui.setScreen(null);
				onServer(mc, server -> {
					dev.rubymod.game.rift.Rift.testSkip(server, "nooffer");
					dev.rubymod.game.rift.Rift.testSkip(server, "act3");
				});
			});
			String[] last = {""};
			for (int t = 0; t < 7000; t++) {
				int tick = t;
				then(1, mc -> onServer(mc, server -> {
					var me = me(server);
					var level = (net.minecraft.server.level.ServerLevel) me.level();
					String st = dev.rubymod.game.rift.Rift.debugState();
					String phase = st.substring(0, st.indexOf(' ')) + " " + dev.rubymod.game.rift.Rift.bossDebug().split(" ")[0];
					if (!phase.equals(last[0])) {
						last[0] = phase;
						log("finale t{}: {} | {}", tick, st.length() > 120 ? st.substring(0, 120) : st, dev.rubymod.game.rift.Rift.bossDebug());
					}
					if (me.getHealth() < 12) {
						me.setHealth(me.getMaxHealth());
					}
					if (st.startsWith("FIGHT") && tick % 100 == 0 && dev.rubymod.game.rift.Rift.bossDebug().contains("titan")) {
						// (The bot can't plan around pylons: the Titan and its echo are skipped.)
						dev.rubymod.game.rift.Rift.testSkip(server, "killboss");
					}
					var held = me.getInventory().getItem(0);
					if (held.getItem() instanceof dev.rubymod.gun.GunItem && dev.rubymod.gun.GunItem.ammo(held) <= 0) {
						held.set(dev.rubymod.registry.ModComponents.AMMO, 30);
					}
					if (st.startsWith("FIGHT")) {
						aimAtNearest(server);
						me.clearFire();
						me.getInventory().setSelectedSlot(0);
						var stack = me.getInventory().getItem(0);
						if (stack.getItem() instanceof dev.rubymod.gun.GunItem g) {
							dev.rubymod.gun.Gunfire.tryFire(level, me, stack, g);
						}
					} else if (st.startsWith("CALM") && tick % 100 == 0) {
						// Done here: through the portal.
						dev.rubymod.game.rift.Rift.testSkip(server, "nooffer");
						var c = dev.rubymod.game.rift.Rift.roomCenter();
						if (c != null) {
							me.teleportTo(c.x, c.y + 0.2, c.z);
						}
					}
				}));
				if (t % 100 == 50) {
					then(0, mc -> {
						var st = dev.rubymod.game.rift.Rift.debugState();
						if (st.startsWith("VOTE")) {
							net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode()));
						}
						if (!(mc.gui.screen() instanceof dev.rubymod.client.rift.RiftMapScreen) || !st.startsWith("VOTE")) {
							mc.gui.setScreen(null);
						}
					});
				}
				if (t % 600 == 300) {
					then(0, mc -> {
						var sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
						mc.player.setYRot(sp.getYRot());
						mc.player.setXRot(sp.getXRot());
						shot(mc, "finale-" + tick);
					});
				}
			}
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("bosses")) {
			// The three new bosses, fought one after another.
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(160, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			then(40, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode())));
			for (int i = 0; i < 15; i++) {
				then(20, mc -> {});
			}
			boolean look = System.getenv("LOOK") != null;
			then(5, mc -> onServer(mc, server -> {
				me(server).addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 3, false, false));
				for (String r : look ? new String[0] : new String[] {"quick_hands", "twin_barrels", "headhunter", "chain_lightning"}) {
					dev.rubymod.game.rift.Rift.testSkip(server, r);
				}
				var stack = new net.minecraft.world.item.ItemStack(dev.rubymod.registry.ModItems.ARR_RIFLE);
				stack.set(dev.rubymod.registry.ModComponents.AMMO, 30);
				me(server).getInventory().setItem(0, stack);
				dev.rubymod.game.rift.Rift.testSkip(server, "clear");
			}));
			String only = System.getenv("BOSS");
			for (String b : only != null ? new String[] {only} : new String[] {"HIVE_QUEEN", "MIRROR_KNIGHT", "STORM_WYRM"}) {
				then(60, mc -> {
					mc.gui.setScreen(null);
					onServer(mc, server -> {
						dev.rubymod.game.rift.Rift.testSkip(server, "nooffer");
						dev.rubymod.game.rift.Rift.testSkip(server, "bossfight:" + b);
					});
				});
				for (int i = 0; i < 18; i++) {
					then(20, mc -> mc.gui.setScreen(null));
				}
				then(5, mc -> log("{} start: {} | {}", b, dev.rubymod.game.rift.Rift.bossDebug(), dev.rubymod.game.rift.Rift.debugState()));
				for (int t = 0; t < 2400; t++) {
					int tick = t;
					then(1, mc -> onServer(mc, server -> {
						var me = me(server);
						var level = (net.minecraft.server.level.ServerLevel) me.level();
						if (!dev.rubymod.game.rift.Rift.debugState().startsWith("FIGHT")) {
							return;
						}
						aimAtNearest(server);
						me.clearFire();
						me.getInventory().setSelectedSlot(0);
						var stack = me.getInventory().getItem(0);
						if (stack.getItem() instanceof dev.rubymod.gun.GunItem g && (!look || tick > 900)) {
							dev.rubymod.gun.Gunfire.tryFire(level, me, stack, g);
						}
						if (me.getHealth() < 12) {
							me.setHealth(me.getMaxHealth());
						}
						if (tick % 400 == 0) {
							log("{} t{}: {}", b, tick, dev.rubymod.game.rift.Rift.bossDebug());
						}
					}));
					if (look && t % 150 == 120 && t < 900) {
						// A wide view from off to the side.
						then(0, mc -> onServer(mc, server -> {
							var at = dev.rubymod.game.rift.Rift.bossPos(server);
							var me = me(server);
							if (at != null) {
								me.setNoGravity(true);
								var c = dev.rubymod.game.rift.Rift.roomCenter();
								me.teleportTo(c.x + 4, c.y + 1, c.z + 4);
								var to = at.add(0, 1.5, 0).subtract(me.getEyePosition());
								me.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
								me.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))));
							}
						}));
						then(4, mc -> {
							var sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
							mc.player.setYRot(sp.getYRot());
							mc.player.setXRot(sp.getXRot());
						});
						then(2, mc -> shot(mc, "boss-" + b.toLowerCase() + "-view" + tick));
						then(1, mc -> onServer(mc, server -> me(server).setNoGravity(false)));
					}
					if (t == 140 || t == 700 || t == 1300 || look && t % 150 == 50 && t < 900) {
						then(0, mc -> {
							mc.gui.setScreen(null);
							var sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
							mc.player.setYRot(sp.getYRot());
							mc.player.setXRot(sp.getXRot());
							shot(mc, "boss-" + b.toLowerCase() + "-" + tick);
						});
					}
				}
				then(5, mc -> log("{} end: {} | {}", b, dev.rubymod.game.rift.Rift.bossDebug(), dev.rubymod.game.rift.Rift.debugState()));
			}
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("rooms")) {
			// The four new rooms, one after another.
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(160, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			then(40, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode())));
			for (int i = 0; i < 15; i++) {
				then(20, mc -> {});
			}
			then(5, mc -> onServer(mc, server -> {
				me(server).addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 3, false, false));
				var stack = new net.minecraft.world.item.ItemStack(dev.rubymod.registry.ModItems.ARR_RIFLE);
				stack.set(dev.rubymod.registry.ModComponents.AMMO, 30);
				me(server).getInventory().setItem(0, stack);
				dev.rubymod.game.rift.Rift.testSkip(server, "quick_hands");
				dev.rubymod.game.rift.Rift.testSkip(server, "clear");
			}));
			for (String type : new String[] {"GAUNTLET", "HEIST", "RESCUE", "DARK"}) {
				then(60, mc -> {
					mc.gui.setScreen(null);
					onServer(mc, server -> {
						dev.rubymod.game.rift.Rift.testSkip(server, "nooffer");
						dev.rubymod.game.rift.Rift.testSkip(server, "room:" + type);
					});
				});
				for (int i = 0; i < 15; i++) {
					then(20, mc -> mc.gui.setScreen(null));
				}
				then(5, mc -> log("{} start: {}", type, dev.rubymod.game.rift.Rift.debugState()));
				for (int t = 0; t < 1350; t++) {
					int tick = t;
					then(1, mc -> onServer(mc, server -> {
						var me = me(server);
						var level = (net.minecraft.server.level.ServerLevel) me.level();
						if (!dev.rubymod.game.rift.Rift.debugState().startsWith("FIGHT")) {
							return;
						}
						if (type.equals("HEIST") && tick % 12 == 0) {
							var rubies = level.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class, me.getBoundingBox().inflate(40),
									d -> d.getItemStack().is(dev.rubymod.registry.ModItems.RUBY) || d.getItemStack().is(dev.rubymod.registry.ModBlocks.RUBY_BLOCK.asItem()));
							rubies.sort(java.util.Comparator.comparingDouble(d -> d.distanceToSqr(me)));
							if (!rubies.isEmpty()) {
								var d = rubies.get(0);
								me.teleportTo(d.getX(), d.getY() - 0.5, d.getZ());
							}
						}
						if (type.equals("RESCUE") && tick % 40 == 0) {
							var c = dev.rubymod.game.rift.Rift.roomCenter();
							if (c != null) {
								me.teleportTo(c.x + 3.2, c.y, c.z);
							}
						}
						aimAtNearest(server);
						me.clearFire();
						me.getInventory().setSelectedSlot(0);
						var stack = me.getInventory().getItem(0);
						if (stack.getItem() instanceof dev.rubymod.gun.GunItem g) {
							dev.rubymod.gun.Gunfire.tryFire(level, me, stack, g);
						}
						if (me.getHealth() < 10) {
							me.setHealth(me.getMaxHealth());
						}
					}));
					if (t == 160) {
						then(0, mc -> {
							mc.gui.setScreen(null);
							var sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
							mc.player.setYRot(sp.getYRot());
							mc.player.setXRot(sp.getXRot());
							shot(mc, "room-" + type.toLowerCase());
						});
					}
					if (t == 600 && type.equals("RESCUE")) {
						then(0, mc -> shot(mc, "room-rescue-late"));
					}
				}
				then(5, mc -> {
					log("{} end: {}", type, dev.rubymod.game.rift.Rift.debugState());
					shot(mc, "room-" + type.toLowerCase() + "-end");
				});
			}
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("codex")) {
			// The Codex (empty, then after finding things) and every charm, used in a fight.
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(140, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.RiftChoice("codex", "open")));
			then(20, mc -> shot(mc, "codex-empty"));
			then(5, mc -> mc.gui.setScreen(null));
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			then(40, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode())));
			for (int i = 0; i < 20; i++) {
				then(20, mc -> {});
			}
			then(5, mc -> onServer(mc, server -> {
				me(server).addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 3, false, false));
				for (String r : new String[] {"chain_lightning", "napalm", "blink", "hall_of_mirrors", "cryo"}) {
					dev.rubymod.game.rift.Rift.testSkip(server, r);
				}
			}));
			for (String c : new String[] {"BOTTLED_LIGHTNING", "SHIELD_TONIC", "RAGE_BREW", "FROST_BOMB", "HEALING_GEM", "TIME_STOP", "POCKET_PORTAL",
					"METEOR_CALL"}) {
				then(5, mc -> onServer(mc, server -> {
					dev.rubymod.game.rift.Rift.testSkip(server, "spawn");
					dev.rubymod.game.rift.Rift.testSkip(server, "charm:" + c);
				}));
				then(50, mc -> onServer(mc, server -> {
					var me = me(server);
					aimAtNearest(server);
					var inv = me.getInventory();
					for (int i = 0; i < inv.getContainerSize(); i++) {
						var st = inv.getItem(i);
						if (c.equals(st.get(dev.rubymod.registry.ModComponents.CHARM))) {
							boolean used = dev.rubymod.game.rift.RiftCharms.use(me, st);
							log("charm {} used {} | {}", c, used, dev.rubymod.game.rift.Rift.debugState());
							break;
						}
					}
				}));
				then(12, mc -> {
					var sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
					mc.player.setYRot(sp.getYRot());
					mc.player.setXRot(sp.getXRot());
					shot(mc, "charm-" + c.toLowerCase());
				});
			}
			// A full pouch: the 4th sells.
			then(5, mc -> onServer(mc, server -> {
				for (int k = 0; k < 4; k++) {
					dev.rubymod.game.rift.Rift.testSkip(server, "charm:HEALING_GEM");
				}
				log("charms held after 4: {}", me(server).getInventory().countItem(net.minecraft.world.item.Items.GOLDEN_APPLE));
			}));
			then(10, mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player)));
			then(15, mc -> shot(mc, "charm-inventory"));
			then(5, mc -> {
				mc.gui.setScreen(null);
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.RiftChoice("codex", "open"));
			});
			then(20, mc -> shot(mc, "codex-relics"));
			for (int t = 1; t < 5; t++) {
				int tab = t;
				then(5, mc -> {
					if (mc.gui.screen() instanceof dev.rubymod.client.rift.CodexScreen cs) {
						cs.tab(tab);
					}
				});
				then(10, mc -> shot(mc, "codex-tab" + tab));
			}
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("lanes")) {
			// The three-lane map, the Ruby Spring's three gifts, and a Burning Elite.
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(160, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			then(60, mc -> shot(mc, "lanes-map"));
			then(5, mc -> {
				var screen = dev.rubymod.client.rift.RiftClient.lastScreen;
				if (screen != null) {
					for (String line : screen.lines()) {
						log("map line: {}", line);
					}
				}
			});
			then(5, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode())));
			for (int i = 0; i < 20; i++) {
				then(20, mc -> {});
			}
			then(5, mc -> onServer(mc, server -> {
				me(server).addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 3, false, false));
				for (String r : new String[] {"chain_lightning", "napalm", "quick_hands"}) {
					dev.rubymod.game.rift.Rift.testSkip(server, r);
				}
				me(server).setHealth(6);
				dev.rubymod.game.rift.Rift.testSkip(server, "clear");
			}));
			then(60, mc -> {
				mc.gui.setScreen(null);
				onServer(mc, server -> {
					dev.rubymod.game.rift.Rift.testSkip(server, "nooffer");
					dev.rubymod.game.rift.Rift.testSkip(server, "room:SPRING");
				});
			});
			for (int i = 0; i < 15; i++) {
				then(20, mc -> {});
			}
			then(5, mc -> shot(mc, "lanes-spring"));
			then(5, mc -> {
				log("spring hp before: {}", mc.player.getHealth());
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.RiftChoice("event", "spr:relic"));
			});
			then(20, mc -> shot(mc, "lanes-spring-relic"));
			then(5, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.RiftChoice("event", "spr:back")));
			then(10, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.RiftChoice("event", "spr:temper")));
			then(20, mc -> shot(mc, "lanes-spring-temper"));
			then(5, mc -> {
				var screen = dev.rubymod.client.rift.RiftClient.lastScreen;
				String pick = "spr:back";
				if (screen != null) {
					for (String line : screen.lines()) {
						if (line.startsWith("spr:t:")) {
							pick = line.split("[|]")[0];
							break;
						}
					}
				}
				log("temper pick {}", pick);
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.RiftChoice("event", pick));
			});
			then(30, mc -> onServer(mc, server -> {
				var me = me(server);
				for (int i = 0; i < 9; i++) {
					var s = me.getInventory().getItem(i);
					if (!s.isEmpty()) {
						log("slot {}: {} traits {}", i, s.getHoverName().getString(), s.get(dev.rubymod.registry.ModComponents.TRAITS));
					}
				}
			}));
			then(10, mc -> shot(mc, "lanes-tempered"));
			// A Burning Elite.
			then(5, mc -> {
				mc.gui.setScreen(null);
				onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "room:ELITE:burning"));
			});
			for (int i = 0; i < 22; i++) {
				then(20, mc -> {});
			}
			then(5, mc -> onServer(mc, server -> {
				var me = me(server);
				var stack = new net.minecraft.world.item.ItemStack(dev.rubymod.registry.ModItems.ARR_RIFLE);
				stack.set(dev.rubymod.registry.ModComponents.AMMO, 30);
				me.getInventory().setItem(0, stack);
				log("burning: {}", dev.rubymod.game.rift.Rift.debugState());
			}));
			for (int t = 0; t < 600; t++) {
				int tick = t;
				then(1, mc -> onServer(mc, server -> {
					var me = me(server);
					var level = (net.minecraft.server.level.ServerLevel) me.level();
					aimAtNearest(server);
					me.clearFire();
					me.getInventory().setSelectedSlot(0);
					var stack = me.getInventory().getItem(0);
					if (stack.getItem() instanceof dev.rubymod.gun.GunItem g) {
						dev.rubymod.gun.Gunfire.tryFire(level, me, stack, g);
					}
					if (me.getHealth() < 10) {
						me.setHealth(me.getMaxHealth());
					}
				}));
				if (t == 200) {
					then(0, mc -> {
						var sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
						mc.player.setYRot(sp.getYRot());
						mc.player.setXRot(sp.getXRot());
						shot(mc, "lanes-burning");
					});
				}
			}
			then(40, mc -> {
				log("after burning: {}", dev.rubymod.game.rift.Rift.debugState());
				shot(mc, "lanes-burning-reward");
			});
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("combos")) {
			// Relic duos, the new Bonds and every legendary's signature, in a real fight.
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(160, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			then(30, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode())));
			for (int i = 0; i < 25; i++) {
				then(20, mc -> {});
			}
			boolean legendsOnly = System.getenv("LEGENDS_ONLY") != null;
			// A card that would complete a combo.
			if (!legendsOnly) {
			then(5, mc -> onServer(mc, server -> {
				me(server).addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 3, false, false));
				dev.rubymod.game.rift.Rift.testSkip(server, "blink");
				dev.rubymod.game.rift.Rift.testSkip(server, "offer:hall_of_mirrors,quick_hands,cryo");
			}));
			then(60, mc -> shot(mc, "combos-card"));
			then(5, mc -> {
				mc.gui.setScreen(null);
				onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "nooffer"));
			});
			// Every duo (one at a time, so each one's COMBO! fires), then every new Bond.
			String[] relics = {"hall_of_mirrors", "bottomless_mag", "quick_hands", "twin_barrels", "echo_shot", "titan_blood", "thorn_armor", "rocket_boots",
					"ground_pound", "field_medic", "time_loop", "headhunter", "lucky_coin", "ricochet", "split_shot", "overkill", "glass_cannon", "drop_turret",
					"arsenal", "shadow_clone", "necromancer", "mirror_guard", "rewind", "black_hole", "overclock", "the_hunted", "gamblers_dice",
					"thunderbomb", "neurotoxin", "firestorm", "ember_heart", "crystal_heart", "black_ice", "gem_bomb", "plague_bomb", "bloodsucker",
					"chain_lightning", "venom_rounds", "napalm", "cryo", "domino"};
			for (String r : relics) {
				then(3, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, r)));
				if (r.equals("quick_hands")) {
					then(8, mc -> shot(mc, "combos-banner"));
				}
			}
			then(10, mc -> onServer(mc, server -> {
				log("combos: {}", dev.rubymod.game.rift.Rift.debugCombos());
				me(server).setHealth(me(server).getMaxHealth());
			}));
			then(10, mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player)));
			then(20, mc -> shot(mc, "combos-inventory"));
			then(5, mc -> mc.gui.setScreen(null));
			// Fight with all of it: shoot, blink, reload, again and again.
			then(5, mc -> onServer(mc, server -> {
				var me = me(server);
				var stack = new net.minecraft.world.item.ItemStack(dev.rubymod.registry.ModItems.ARR_RIFLE);
				stack.set(dev.rubymod.registry.ModComponents.AMMO, 30);
				me.getInventory().setItem(0, stack);
				dev.rubymod.game.rift.Rift.testSkip(server, "spawn");
			}));
			for (int t = 0; t < 240; t++) {
				int tick = t;
				then(1, mc -> onServer(mc, server -> {
					var me = me(server);
					var level = (net.minecraft.server.level.ServerLevel) me.level();
					aimAtNearest(server);
					me.clearFire();
					me.getInventory().setSelectedSlot(0);
					var stack = me.getInventory().getItem(0);
					if (stack.getItem() instanceof dev.rubymod.gun.GunItem g) {
						dev.rubymod.gun.Gunfire.tryFire(level, me, stack, g);
					}
					if (tick % 60 == 30) {
						dev.rubymod.game.rift.Rift.testSkip(server, "do:blink");
					}
					if (tick % 80 == 40) {
						dev.rubymod.game.rift.Rift.testSkip(server, "do:reloaded");
					}
					if (tick % 80 == 0) {
						dev.rubymod.game.rift.Rift.testSkip(server, "spawn");
					}
					if (me.getHealth() < 10) {
						me.setHealth(me.getMaxHealth());
					}
				}));
				if (t == 60 || t == 150) {
					then(0, mc -> {
						log("combos fight: {} | hp {}", dev.rubymod.game.rift.Rift.debugState(), mc.player.getHealth());
						shot(mc, "combos-fight-" + tick);
					});
				}
			}
			}
			then(5, mc -> onServer(mc, server -> me(server).addEffect(new net.minecraft.world.effect.MobEffectInstance(
					net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 3, false, false))));
			// Every legendary's signature, in a fresh fight.
			then(5, mc -> {
				mc.gui.setScreen(null);
				onServer(mc, server -> {
					dev.rubymod.game.rift.Rift.testSkip(server, "nooffer");
					dev.rubymod.game.rift.Rift.testSkip(server, "room:FIGHT");
				});
			});
			for (int i = 0; i < 20; i++) {
				then(20, mc -> mc.gui.setScreen(null));
			}
			String[][] legends = {{"ruby_minigun", "Inferno Gatling"}, {"ruby_chakram", "Storm Disc"}, {"longshot_sniper", "Event Horizon"},
					{"swarm_launcher", "Apocalypse Pod"}, {"combat_shotgun", "Dragon Duster"}, {"ruby_warhammer", "Ruby Titan Blade"},
					{"arr_rifle", "Railstorm Rifle"}, {"rail_lance", "Prism Lance"}, {"swarm_launcher", "Hydra"}, {"tesla_coilgun", "Thunderbird"},
					{"service_pistol", "Deadeye"}, {"bouncer", "Firecracker"}};
			for (String[] lg : legends) {
				then(5, mc -> onServer(mc, server -> {
					var me = me(server);
					var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("rubymod:" + lg[0]));
					var stack = new net.minecraft.world.item.ItemStack(item);
					java.util.List<String> traits = new java.util.ArrayList<>();
					if (lg[1].equals("Railstorm Rifle")) {
						traits.add("RAIL");
					}
					traits.add("LEGEND:" + lg[1]);
					stack.set(dev.rubymod.registry.ModComponents.TRAITS, traits);
					if (item instanceof dev.rubymod.gun.GunItem g) {
						stack.set(dev.rubymod.registry.ModComponents.AMMO, g.stats(stack).magazine());
					}
					me.getInventory().setItem(0, stack);
					dev.rubymod.game.rift.Rift.testSkip(server, "spawn");
				}));
				then(30, mc -> {});
				for (int t = 0; t < 60; t++) {
					int tick = t;
					then(1, mc -> onServer(mc, server -> {
						var me = me(server);
						var level = (net.minecraft.server.level.ServerLevel) me.level();
						aimAtNearest(server);
						me.clearFire();
						me.getInventory().setSelectedSlot(0);
						var stack = me.getInventory().getItem(0);
						if (stack.getItem() instanceof dev.rubymod.gun.GunItem g) {
							dev.rubymod.gun.Gunfire.tryFire(level, me, stack, g);
						}
						if (me.getHealth() < 10) {
							me.setHealth(me.getMaxHealth());
						}
					}));
					if (t == 14) {
						then(0, mc -> {
							mc.gui.setScreen(null);
							var sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
							mc.player.setYRot(sp.getYRot());
							mc.player.setXRot(sp.getXRot());
							shot(mc, "legend-" + lg[1].replace(" ", "_").toLowerCase());
						});
					}
				}
				then(5, mc -> log("legend {}: {}", lg[1], dev.rubymod.game.rift.Rift.debugState()));
			}
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("relics2")) {
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(160, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			then(30, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode())));
			for (int i = 0; i < 30; i++) {
				then(20, mc -> {});
			}
			then(5, mc -> onServer(mc, server -> {
				for (String r : new String[] {"lucky_coin", "thorn_armor", "overclock", "venom_rounds", "echo_shot", "ground_pound", "split_shot",
						"static_charge", "hall_of_mirrors", "meteor_shower", "ruby_dragon", "rewind", "the_hunted", "gamblers_dice", "superconductor",
						"toxic_inferno", "cryo", "chain_lightning", "napalm"}) {
					dev.rubymod.game.rift.Rift.testSkip(server, r);
				}
				dev.rubymod.game.rift.Rift.testSkip(server, "clear");
			}));
			then(100, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "room:FIGHT")));
			for (int i = 0; i < 30; i++) {
				then(20, mc -> {});
			}
			then(5, mc -> onServer(mc, server -> {
				me(server).addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, -1, 3, false, false));
				dev.rubymod.game.rift.Rift.testSkip(server, "spawn");
			}));
			for (int t = 0; t < 300; t++) {
				int tick = t;
				then(1, mc -> onServer(mc, server -> {
					var me = me(server);
					var level = (net.minecraft.server.level.ServerLevel) me.level();
					var near = level.getEntities((net.minecraft.world.entity.Entity) null, me.getBoundingBox().inflate(30),
							e -> e.entityTags().contains(dev.rubymod.game.rift.Rift.TAG) && e instanceof net.minecraft.world.entity.Mob m && m.isAlive()
									&& !dev.rubymod.game.rift.Rift.isAllyEntity(e) && dev.rubymod.game.rift.Rift.isEnemy(e));
					near.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(me)));
					if (!near.isEmpty()) {
						var to = near.get(0).getBoundingBox().getCenter().subtract(me.getEyePosition());
						me.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
						me.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))));
					}
					me.clearFire();
					me.setSprinting(tick % 60 < 30);
					var stack = me.getInventory().getItem(0);
					me.getInventory().setSelectedSlot(0);
					if (stack.getItem() instanceof dev.rubymod.gun.GunItem g) {
						dev.rubymod.gun.Gunfire.tryFire(level, me, stack, g);
					}
					if (tick % 100 == 50) {
						dev.rubymod.game.rift.Rift.testSkip(server, "spawn");
					}
				}));
				if (t == 120 || t == 240) {
					then(0, mc -> {
						log("relics2: {} | hp {}", dev.rubymod.game.rift.Rift.debugState(), mc.player.getHealth());
						shot(mc, "relics2-" + tick);
					});
				}
			}
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("weapons")) {
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(160, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			then(30, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode())));
			then(220, mc -> command(mc, "effect give @a minecraft:resistance infinite 255 true"));
			for (int i = 0; i < 30; i++) {
				then(20, mc -> {});
			}
			then(5, mc -> command(mc, "effect give @a minecraft:resistance infinite 255 true"));
			String[] weapons = System.getenv("GUNS") != null ? System.getenv("GUNS").split(",") : new String[] {"ruby_minigun", "rail_lance", "ruby_chakram",
					"dragons_breath", "swarm_launcher", "tesla_coilgun", "bouncer", "six_shooter", "ruby_warhammer", "singularity_cannon"};
			for (String w : weapons) {
				int[] before = new int[1];
				then(5, mc -> onServer(mc, server -> {
					dev.rubymod.game.rift.Rift.testSkip(server, "spawn");
					var me = me(server);
					var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("rubymod:" + w));
					var stack = new net.minecraft.world.item.ItemStack(item);
					if (item instanceof dev.rubymod.gun.GunItem g) {
						stack.set(dev.rubymod.registry.ModComponents.AMMO, g.stats().magazine());
					}
					me.getInventory().setItem(0, stack);
					me.getInventory().setSelectedSlot(0);
				}));
				then(2, mc -> mc.player.getInventory().setSelectedSlot(0));
				then(40, mc -> onServer(mc, server -> {
					before[0] = (int) server.getLevel(dev.rubymod.game.Duels.ARENA).getEntities((net.minecraft.world.entity.Entity) null,
							me(server).getBoundingBox().inflate(40), e -> e.entityTags().contains(dev.rubymod.game.rift.Rift.TAG)
									&& e instanceof net.minecraft.world.entity.Mob).size();
				}));
				for (int t = 0; t < 50; t++) {
					int tick = t;
					then(1, mc -> onServer(mc, server -> {
						var me = me(server);
						var level = (net.minecraft.server.level.ServerLevel) me.level();
						// Face the nearest monster, then pull the trigger.
						var near = level.getEntities((net.minecraft.world.entity.Entity) null, me.getBoundingBox().inflate(30),
								e -> e.entityTags().contains(dev.rubymod.game.rift.Rift.TAG) && e instanceof net.minecraft.world.entity.Mob m && m.isAlive()
										&& !dev.rubymod.game.rift.Rift.isAllyEntity(e));
						near.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(me)));
						if (!near.isEmpty()) {
							var to = near.get(0).getBoundingBox().getCenter().subtract(me.getEyePosition());
							float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
							float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
							me.setYRot(yaw);
							me.setXRot(pitch);
							me.setYHeadRot(yaw);
						}
						me.clearFire();
						me.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 100, 0, false, false));
						var stack = me.getMainHandItem();
						if (stack.getItem() instanceof dev.rubymod.gun.GunItem g) {
							dev.rubymod.gun.Gunfire.tryFire(level, me, stack, g);
						}
					}));
					if (t == 1 || t == 12) {
						then(0, mc -> {
							var sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
							mc.player.setYRot(sp.getYRot());
							mc.player.setXRot(sp.getXRot());
						});
					}
					if (t == 8 || System.getenv("GUNS") != null && (t == 4 || t == 20 || t == 35)) {
						then(0, mc -> shot(mc, "weapon-" + w + (tick == 8 ? "" : "-" + tick)));
					}
				}
				then(40, mc -> onServer(mc, server -> {
					int after = (int) server.getLevel(dev.rubymod.game.Duels.ARENA).getEntities((net.minecraft.world.entity.Entity) null,
							me(server).getBoundingBox().inflate(40), e -> e.entityTags().contains(dev.rubymod.game.rift.Rift.TAG)
									&& e instanceof net.minecraft.world.entity.Mob).size();
					log("weapon {}: monsters {} -> {} | {}", w, before[0], after, dev.rubymod.game.rift.Rift.debugState());
				}));
			}
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("shapes")) {
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "gamemode creative @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(160, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			then(30, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode())));
			then(220, mc -> {});
			for (String shape : new String[] {"CIRCLE", "OCTAGON", "CROSS", "COURTYARD", "TIERED"}) {
				then(5, mc -> onServer(mc, server -> {
					dev.rubymod.game.rift.Rift.testSkip(server, "clear");
					dev.rubymod.game.rift.Rift.testSkip(server, "shape:" + shape);
				}));
				then(80, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "room:FIGHT")));
				for (int i = 0; i < 25; i++) {
					then(20, mc -> {});
				}
				then(5, mc -> onServer(mc, server -> {
					dev.rubymod.game.rift.Rift.testSkip(server, "clear");
					var me = me(server);
					me.teleportTo(me.getX(), me.getY() + 12, me.getZ() + 2);
					me.setXRot(38);
					me.setYRot(180);
				}));
				then(40, mc -> {
					mc.gui.setScreen(null);
					mc.player.setXRot(38);
					mc.player.setYRot(180);
				});
				then(10, mc -> {
					log("shape {}: {}", shape, dev.rubymod.game.rift.Rift.jobLine());
					onServer(mc, server -> log("plan {}{}", shape, dev.rubymod.game.rift.Rift.floorPlan(server)));
					shot(mc, "shape-" + shape);
				});
			}
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("stage2")) {
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(160, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			then(30, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode())));
			then(220, mc -> {
				command(mc, "effect give @a minecraft:resistance infinite 255 true");
				log("first room: {} | {}", dev.rubymod.game.rift.Rift.debugState(), dev.rubymod.game.rift.Rift.jobLine());
				shot(mc, "stage2-room1");
			});
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "clear")));
			then(100, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "room:DEFEND")));
			for (int i = 0; i < 25; i++) {
				then(20, mc -> {});
			}
			then(5, mc -> command(mc, "effect give @a minecraft:resistance infinite 255 true"));
			for (int i = 0; i < 6; i++) {
				int n = i;
				then(100, mc -> {
					log("defend: {} | {}", dev.rubymod.game.rift.Rift.debugState(), dev.rubymod.game.rift.Rift.jobLine());
					if (n == 1 || n == 4) {
						shot(mc, "stage2-defend" + n);
					}
				});
			}
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "clear")));
			then(100, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "room:HOLD")));
			for (int i = 0; i < 25; i++) {
				then(20, mc -> {});
			}
			then(5, mc -> command(mc, "effect give @a minecraft:resistance infinite 255 true"));
			then(40, mc -> onServer(mc, server -> {
				var z = dev.rubymod.game.rift.Rift.zonePos();
				log("hold: zone at {} | {}", z, dev.rubymod.game.rift.Rift.jobLine());
				if (z != null) {
					me(server).teleportTo(z.x, z.y, z.z);
				}
			}));
			for (int i = 0; i < 14; i++) {
				int n = i;
				then(100, mc -> {
					onServer(mc, server -> {
						var z = dev.rubymod.game.rift.Rift.zonePos();
						if (z != null && me(server).position().distanceTo(z) > 2) {
							me(server).teleportTo(z.x, z.y, z.z);
						}
					});
					log("hold: {} | {}", dev.rubymod.game.rift.Rift.debugState(), dev.rubymod.game.rift.Rift.jobLine());
					if (n == 2) {
						shot(mc, "stage2-hold");
					}
				});
			}
			then(5, mc -> shot(mc, "stage2-after"));
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("stage1")) {
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(160, mc -> onServer(mc, server -> dev.rubymod.game.rift.RiftChoices.handle(me(server), "pick_ability", "nova")));
			then(20, mc -> dev.rubymod.client.rift.RiftClient.reopen());
			then(30, mc -> {
				shot(mc, "stage1-altar");
				mc.gui.setScreen(null);
			});
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			then(30, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode())));
			then(220, mc -> {
				command(mc, "effect give @a minecraft:resistance infinite 255 true");
				log("fight: {}", dev.rubymod.game.rift.Rift.debugState());
			});
			// Ruby Nova (Z).
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.RiftChoices.handle(me(server), "ability", "active")));
			then(2, mc -> shot(mc, "stage1-nova"));
			then(30, mc -> {
				log("after nova: {}", dev.rubymod.game.rift.Rift.debugState());
				shot(mc, "stage1-cooldown");
			});
			// Try again at once: still recharging.
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.RiftChoices.handle(me(server), "ability", "active")));
			// Switch to the Shield Dome mid-run (only allowed at the Threshold: should refuse).
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.RiftChoices.handle(me(server), "pick_ability", "dome")));
			// Combining: Chain Lightning three times.
			then(5, mc -> onServer(mc, server -> {
				for (int k = 0; k < 3; k++) {
					dev.rubymod.game.rift.Rift.testSkip(server, "chain_lightning");
				}
				dev.rubymod.game.rift.Rift.testSkip(server, "quick_hands");
				dev.rubymod.game.rift.Rift.testSkip(server, "quick_hands");
			}));
			then(30, mc -> {
				log("relics on the HUD: {}", dev.rubymod.client.rift.RiftClient.hud.relics());
				shot(mc, "stage1-combined");
			});
			then(5, mc -> {
				mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
			});
			then(20, mc -> {
				shot(mc, "stage1-inventory");
				mc.gui.setScreen(null);
			});
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "clear")));
			then(100, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "boss")));
			for (int i = 0; i < 40; i++) {
				then(20, mc -> {});
			}
			then(5, mc -> command(mc, "effect give @a minecraft:resistance infinite 255 true"));
			then(200, mc -> {
				log("boss: {}", dev.rubymod.game.rift.Rift.debugState());
				shot(mc, "stage1-boss");
			});
			then(200, mc -> shot(mc, "stage1-boss2"));
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("gunship")) {
			// Straight to the act 2 boss: where does the Gunship fly?
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(160, mc -> command(mc, "effect give @a minecraft:resistance infinite 255 true"));
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			then(30, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode())));
			then(200, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "clear")));
			then(100, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "act2")));
			then(5, mc -> command(mc, "effect give @a minecraft:resistance infinite 255 true"));
			for (int i = 0; i < 90; i++) {
				int n = i;
				then(20, mc -> {
					String st = dev.rubymod.game.rift.Rift.debugState();
					if (st.contains("gunship")) {
						log("gunship: {}", st.substring(st.indexOf("boss ")));
					}
					if (n % 15 == 14 && st.contains("gunship")) {
						shot(mc, "gunship-" + n);
					}
				});
			}
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("titan")) {
			// Straight to the act 3 boss with a Ruby Golem and Necromancer: does the Titan stay?
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play rift"));
			then(160, mc -> command(mc, "effect give @a minecraft:resistance infinite 255 true"));
			then(5, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "start")));
			then(30, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.rubymod.net.MorePayloads.RiftChoice("vote", riftOpenNode())));
			then(200, mc -> onServer(mc, server -> {
				dev.rubymod.game.rift.Rift.testSkip(server, "ruby_golem");
				dev.rubymod.game.rift.Rift.testSkip(server, "necromancer");
				dev.rubymod.game.rift.Rift.testSkip(server, "clear");
			}));
			then(100, mc -> onServer(mc, server -> dev.rubymod.game.rift.Rift.testSkip(server, "act3")));
			for (int i = 0; i < 70; i++) {
				then(20, mc -> onServer(mc, server -> log("titan: {} | at {}", dev.rubymod.game.rift.Rift.debugState(), me(server).blockPosition())));
			}
			then(5, mc -> shot(mc, "titan"));
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("starter")) {
			// Start a season, place a Heart: the starter base appears and the drill fills the bank.
			net.minecraft.core.BlockPos[] heart = new net.minecraft.core.BlockPos[1];
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "gamemode survival @a");
				command(mc, "rubywars start");
			});
			then(300, mc -> onServer(mc, server -> {
				var me = me(server);
				var level = server.overworld();
				int x = 180;
				int z = 40;
				level.getChunk(x >> 4, z >> 4);
				int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				heart[0] = new net.minecraft.core.BlockPos(x, y, z);
				me.teleportTo(level, x + 0.5, y, z - 6.5, java.util.Set.of(), 0, 30, true);
				dev.rubymod.game.Research.sync(me);
			}));
			then(60, mc -> onServer(mc, server -> {
				var me = me(server);
				var level = server.overworld();
				log("problem placing here? {}", dev.rubymod.game.Claims.placementProblem(level, me, heart[0]));
				level.setBlock(heart[0], dev.rubymod.registry.ModBlocks.RUBY_HEART.defaultBlockState(), 3);
				dev.rubymod.game.Claims.onHeartPlaced(level, me, heart[0]);
				java.util.List<String> found = new java.util.ArrayList<>();
				for (var pos : net.minecraft.core.BlockPos.betweenClosed(heart[0].offset(-4, -3, -4), heart[0].offset(4, 3, 4))) {
					var b = level.getBlockState(pos).getBlock();
					if (b instanceof dev.rubymod.industry.MachineBlock || b == dev.rubymod.registry.ModBlocks.ARMORY) {
						found.add(b.getName().getString() + "@" + pos.subtract(heart[0]).toShortString());
					}
				}
				log("starter base: {}", found);
			}));
			then(40, mc -> {
				log("guide step {} | bounties {}", dev.rubymod.client.Guide.current(), ClientState.bounties.size());
				shot(mc, "starter-base");
			});
			for (int i = 0; i < 4; i++) {
				then(400, mc -> onServer(mc, server -> log("bank {} | charge {}", dev.rubymod.industry.Grid.rubies(me(server).getUUID()),
						dev.rubymod.industry.Grid.charge(me(server).getUUID()))));
			}
			then(5, mc -> command(mc, "tp @p ~ ~8 ~ 0 50"));
			then(40, mc -> shot(mc, "starter-above"));
			then(5, mc -> mc.gui.setScreen(new ResearchScreen()));
			then(20, mc -> shot(mc, "starter-guide"));
			then(5, mc -> mc.gui.setScreen(null));
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("guide")) {
			// The NEXT STEP panel and the J guide as goals get done.
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "gamemode survival @a");
			});
			then(300, mc -> onServer(mc, server -> {
				var me = me(server);
				dev.rubymod.game.SeasonData.get().player(me.getUUID(), me.getName().getString());
				dev.rubymod.game.Research.sync(me);
			}));
			then(40, mc -> {
				log("guide: step {} | phase {} | research {}", dev.rubymod.client.Guide.current(), ClientState.phase(), ClientState.research != null);
				shot(mc, "guide-hud-start");
			});
			then(5, mc -> onServer(mc, server -> {
				var me = me(server);
				dev.rubymod.game.Goals.add(server, me.getUUID(), "heart", 1);
				dev.rubymod.game.Goals.add(server, me.getUUID(), "ruby10", 4);
			}));
			then(60, mc -> {
				log("guide: step {}", dev.rubymod.client.Guide.current());
				shot(mc, "guide-hud-ruby");
			});
			then(5, mc -> onServer(mc, server -> {
				var me = me(server);
				for (String id : new String[] {"ruby10", "armory", "power", "drill"}) {
					dev.rubymod.game.Goals.add(server, me.getUUID(), id, 10);
				}
			}));
			then(60, mc -> {
				log("guide: step {}", dev.rubymod.client.Guide.current());
				mc.gui.setScreen(new ResearchScreen());
			});
			then(20, mc -> shot(mc, "guide-tab"));
			then(5, mc -> mc.gui.setScreen(new ManualScreen()));
			then(20, mc -> shot(mc, "guide-manual"));
			then(5, mc -> mc.gui.setScreen(null));
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("riftthemes")) {
			// Each level's glow color on a rifle, with the sword and a grenade in the hotbar.
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "gamemode creative @a");
				command(mc, "tp @p ~ ~ ~ 0 0");
			});
			then(400, mc -> log("world ready"));
			String[][] themes = {{"threshold", "16724580", ""}, {"crystal", "11817215", ""}, {"forge", "16747546", ""}, {"frost", "6088959", ""},
					{"lab", "7077706", ""}, {"foundry", "16765466", ""}, {"skydock", "4033535", ""}, {"heart", "16719695", ",\"pulse\""}};
			for (String[] t : themes) {
				String data = "[custom_model_data={strings:[\"rift\"" + t[2] + "],colors:[" + t[1] + "]}]";
				then(10, mc -> {
					command(mc, "item replace entity @p hotbar.1 with rubymod:ruby_sword" + data);
					command(mc, "item replace entity @p hotbar.2 with rubymod:frag_grenade" + data);
					command(mc, "item replace entity @p hotbar.3 with rubymod:service_pistol" + data);
					command(mc, "item replace entity @p weapon.mainhand with rubymod:arr_rifle" + data);
				});
				then(30, mc -> shot(mc, "theme-" + t[0]));
			}
			then(5, mc -> command(mc, "item replace entity @p weapon.mainhand with rubymod:ruby_sword[custom_model_data={strings:[\"rift\"],colors:[11817215]}]"));
			then(30, mc -> shot(mc, "theme-sword"));
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("riftguns")) {
			// Each Rift gun skin in the hand, against the sky and against stone.
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "gamemode creative @a");
				command(mc, "tp @p ~ ~ ~ 0 0");
			});
			then(100, mc -> log("world ready"));
			String[] guns = System.getenv("GUNS") != null ? System.getenv("GUNS").split(",")
					: new String[] {"arr_rifle", "service_pistol", "combat_shotgun", "longshot_sniper", "ruby_blaster", "rocket_launcher", "skyhammer", "lancer"};
			for (String gun : guns) {
				then(10, mc -> command(mc, "item replace entity @p weapon.mainhand with rubymod:" + gun + "[custom_model_data={strings:[\"rift\"]}]"));
				then(30, mc -> shot(mc, "riftgun-" + gun));
				then(5, mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT));
				then(25, mc -> shot(mc, "riftgun-" + gun + "-3p"));
				then(5, mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
			}
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("villagetour")) {
			// Villages and ruins on the Ruby Wars map (run on a world made with that map type).
			then(0, mc -> {
				command(mc, "gamemode spectator @a");
				command(mc, "time set noon");
				command(mc, "weather clear");
			});
			var centers = dev.rubymod.world.MapFeatures.villageCenters();
			var ruins = dev.rubymod.world.MapFeatures.ruins();
			then(5, mc -> log("villages at {} | {} ruins", centers.stream().map(c -> c[0] + "," + c[1]).toList(), ruins.size()));
			for (int i = 0; i < centers.size(); i++) {
				int[] c = centers.get(i);
				int g = dev.rubymod.world.WarzoneTerrain.column(c[0], c[1]).height();
				int n = i;
				then(10, mc -> command(mc, String.format("tp @p %d %d %d facing %d %d %d", c[0] + 34, g + 26, c[1] + 34, c[0], g, c[1])));
				then(300, mc -> shot(mc, "village-" + n));
			}
			for (int i = 0; i < 8; i++) {
				var r = ruins.get(i * ruins.size() / 8);
				int g = dev.rubymod.world.WarzoneTerrain.column(r.x(), r.z()).height();
				int n = i;
				then(10, mc -> command(mc, String.format("tp @p %d %d %d facing %d %d %d", r.x() + 11, g + 7, r.z() + 11, r.x(), g + 2, r.z())));
				then(200, mc -> {
					log("ruin {} kind {} at {},{}", n, r.kind(), r.x(), r.z());
					shot(mc, "ruin-" + n);
				});
			}
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("botswitch")) {
			// Party bots: off by default with 2+ players, a switch in the game picker.
			then(120, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.ModeSelect("")));
			then(20, mc -> mc.gui.setScreen(new ModeScreen()));
			then(30, mc -> {
				log("picker lines: {}", ModeScreen.info.modes().stream().filter(l -> l.startsWith("game:")).map(l -> l.substring(l.lastIndexOf('|'))).toList());
				shot(mc, "bots-picker");
				mc.gui.setScreen(null);
			});
			then(5, mc -> command(mc, "execute as @p run play gungame"));
			then(10, mc -> onServer(mc, server -> dev.rubymod.game.party.PartyGames.addTestPlayer(pg("gungame"), partyBuddy(server))));
			for (int i = 0; i < 20; i++) {
				then(40, mc -> onServer(mc, server -> pg("gungame").skipLobby()));
			}
			then(60, mc -> log("two players, bots off: {}", pg("gungame").debugState()));
			then(5, mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.ModeSelect("bots:gungame")));
			then(20, mc -> {
				mc.gui.setScreen(new ModeScreen());
				log("after the switch: {}", ModeScreen.info.modes().stream().filter(l -> l.startsWith("game:gungame")).toList());
			});
			then(20, mc -> {
				shot(mc, "bots-on");
				mc.gui.setScreen(null);
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("hordebase")) {
			// Co-op Horde as base defense: the battlefield, the Armory, walls the horde smashes.
			then(0, mc -> {
				command(mc, "time set noon");
				command(mc, "difficulty normal");
				command(mc, "gamemode survival @a");
			});
			then(20, mc -> command(mc, "execute as @p run play horde normal"));
			then(200, mc -> {
				log("joined: {} | {}", dev.rubymod.game.Horde.debugState(), inventorySummary(mc));
				command(mc, "effect give @a minecraft:resistance infinite 255 true");
				command(mc, "effect give @a minecraft:night_vision infinite 0 true");
				command(mc, "execute as @p at @s run tp @s -20000 112 52 180 45");
			});
			then(60, mc -> shot(mc, "base-field"));
			then(5, mc -> command(mc, "execute as @p at @s run tp @s -20000.5 65 3.5 0 10"));
			then(20, mc -> mc.player.connection.sendCommand("horde shop"));
			then(20, mc -> {
				log("shop: {}", mc.gui.screen());
				shot(mc, "base-shop");
			});
			then(5, mc -> {
				for (String id : new String[] {"turret", "walls", "landmines", "solar", "tesla"}) {
					net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.MorePayloads.HordeBuy(id));
				}
			});
			then(20, mc -> {
				log("bought: {} | {}", dev.rubymod.game.Horde.debugState(), inventorySummary(mc));
				shot(mc, "base-shop-after");
				mc.gui.setScreen(null);
			});
			// A concrete ring round the Core, 2 high.
			then(5, mc -> {
				command(mc, "execute in rubymod:arena run fill -20007 65 -7 -19993 66 -7 rubymod:concrete_wall");
				command(mc, "execute in rubymod:arena run fill -20007 65 7 -19993 66 7 rubymod:concrete_wall");
				command(mc, "execute in rubymod:arena run fill -20007 65 -7 -20007 66 7 rubymod:concrete_wall");
				command(mc, "execute in rubymod:arena run fill -19993 65 -7 -19993 66 7 rubymod:concrete_wall");
			});
			then(10, mc -> onServer(mc, server -> dev.rubymod.game.Horde.skipWait()));
			then(60, mc -> {
				log("wave 1: {}", dev.rubymod.game.Horde.debugState());
				command(mc, "execute as @p at @s run tp @s -20000.5 65 -4.5 0 15");
			});
			then(600, mc -> {
				log("30s into wave 1: {}", dev.rubymod.game.Horde.debugState());
				shot(mc, "base-siege");
			});
			then(400, mc -> {
				onServer(mc, server -> {
					var arena = server.getLevel(dev.rubymod.game.Duels.ARENA);
					int walls = 0;
					for (int x = -20007; x <= -19993; x++) {
						for (int z = -7; z <= 7; z++) {
							for (int y = 65; y <= 66; y++) {
								if (arena.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).is(dev.rubymod.registry.ModBlocks.CONCRETE_WALL)) {
									walls++;
								}
							}
						}
					}
					log("walls left {} of 112 | {}", walls, dev.rubymod.game.Horde.debugState());
				});
				shot(mc, "base-siege2");
			});
			then(5, mc -> command(mc, "kill @e[tag=rubymod_horde,type=!minecraft:text_display]"));
			then(30, mc -> command(mc, "kill @e[tag=rubymod_horde,type=!minecraft:text_display]"));
			then(30, mc -> command(mc, "kill @e[tag=rubymod_horde,type=!minecraft:text_display]"));
			then(60, mc -> log("cleared: {}", dev.rubymod.game.Horde.debugState()));
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("hordeplus")) {
			hordePlusScript();
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("joinprofile")) {
			// Dedicated server: does the profile (P) fill in when opened fresh?
			then(60, mc -> {
				ProfileScreen.data = null;
				mc.gui.setScreen(new ProfileScreen());
			});
			then(40, mc -> {
				log("profile data arrived: {}", ProfileScreen.data == null ? "NO" : ProfileScreen.data.stats().size() + " stat lines, "
						+ ProfileScreen.data.cosmetics().size() + " cosmetics");
				shot(mc, "join-profile");
			});
			then(5, mc -> mc.gui.setScreen(null));
			then(5, mc -> mc.player.connection.sendCommand("play horde"));
			then(120, mc -> log("in horde: {}", inventorySummary(mc)));
			then(5, mc -> mc.player.connection.sendCommand("horde ready"));
			then(40, mc -> shot(mc, "join-horde-ready"));
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("joinflow")) {
			// Joined a real (dedicated) server: should land in the hub, then switch games by command.
			then(60, mc -> {
				log("joined: {}", inventorySummary(mc));
				shot(mc, "join-hub");
			});
			then(5, mc -> mc.player.connection.sendCommand("play rubywars"));
			then(80, mc -> { log("ruby wars: {} screen {}", inventorySummary(mc), mc.gui.screen()); shot(mc, "join-rubywars"); });
			then(5, mc -> mc.player.connection.sendCommand("play horde"));
			then(80, mc -> { log("horde: {} screen {}", inventorySummary(mc), mc.gui.screen()); shot(mc, "join-horde"); });
			then(5, mc -> mc.player.connection.sendCommand("hub"));
			then(80, mc -> { log("hub again: {} screen {}", inventorySummary(mc), mc.gui.screen()); shot(mc, "join-hub2"); });
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("hordefight")) {
			hordeFightScript();
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("hub")) {
			hubScript();
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("maptour")) {
			mapTourScript();
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		if (mode.equals("training") || mode.equals("yard")) {
			if (mode.equals("training")) {
				trainingScript();
			} else {
				yardScript();
			}
			then(20, mc -> {
				log("done");
				mc.stop();
			});
			return;
		}
		then(0, mc -> {
			command(mc, "time set noon");
			command(mc, "weather clear");
			command(mc, "gamemode creative @a");
			command(mc, "kill @e[type=!minecraft:player]");
			// A flat 60x60 arena (split into quarters to stay under the fill limit).
			for (String q : new String[] {"~-30 ~-1 ~-30 ~0 ~14 ~0", "~1 ~-1 ~-30 ~30 ~14 ~0", "~-30 ~-1 ~1 ~0 ~14 ~30", "~1 ~-1 ~1 ~30 ~14 ~30"}) {
				command(mc, "execute at @p run fill " + q + " minecraft:air");
			}
			for (String q : new String[] {"~-30 ~-1 ~-30 ~0 ~-1 ~0", "~1 ~-1 ~-30 ~30 ~-1 ~0", "~-30 ~-1 ~1 ~0 ~-1 ~30", "~1 ~-1 ~1 ~30 ~-1 ~30"}) {
				command(mc, "execute at @p run fill " + q + " minecraft:smooth_stone");
			}
			command(mc, "clear @a");
		});
		then(15, mc -> command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 10"));
		switch (mode) {
			case "guns" -> gunsScript();
			case "view" -> viewScript();
			case "features" -> featuresScript();
			case "raid" -> raidScript();
			case "gear" -> gearScript();
			case "impact" -> impactScript();
			case "follow" -> followScript();
			case "bombrun" -> bombRunScript();
			case "duel" -> duelScript();
			case "tankfire" -> {
				then(5, mc -> command(mc, "execute at @p run summon rubymod:tank ~ ~ ~8 {Rotation:[0f,0f]}"));
				then(10, mc -> command(mc, "ride @p mount @e[type=rubymod:tank,limit=1,sort=nearest]"));
				then(10, mc -> {
					mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
					mc.player.setYRot(mc.player.getVehicle().getYRot() + 40);
					mc.player.setYHeadRot(mc.player.getYRot());
					mc.player.setXRot(-15);
				});
				then(60, mc -> {
					var t = (dev.rubymod.entity.VehicleEntity) mc.player.getVehicle();
					log("tank yaw {} turret {} pitch {}", t.getYRot(), t.turretYaw(), t.barrelPitch());
					ClientPlayNetworking.send(new Payloads.VehicleFire());
				});
				then(1, mc -> shot(mc, "tankfire-1"));
				then(2, mc -> shot(mc, "tankfire-3"));
				then(6, mc -> shot(mc, "tankfire-9"));
				then(2, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
				then(10, mc -> shot(mc, "tankfire-front"));
				then(2, mc -> {
					mc.options.setCameraType(CameraType.FIRST_PERSON);
					command(mc, "ride @p dismount");
				});
				// Side view: the turret was aimed 40 degrees right (toward -x) and 15 up; look at it across its gun.
				then(10, mc -> command(mc, "execute as @p at @e[type=rubymod:tank,limit=1,sort=nearest] run tp @s ~-9 ~1 ~-2 -100 0"));
				then(15, mc -> shot(mc, "tankfire-side"));
			}
			case "turret" -> {
				then(5, mc -> command(mc, "execute at @p run summon rubymod:tank ~ ~ ~8 {Rotation:[0f,0f]}"));
				then(10, mc -> command(mc, "ride @p mount @e[type=rubymod:tank,limit=1,sort=nearest]"));
				then(5, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
				// Turn the view the way a mouse would, and see whether the turret follows.
				for (int deg : new int[] {0, 60, 120, -90}) {
					then(5, mc -> {
						float yaw = mc.player.getVehicle().getYRot() + deg;
						mc.player.setYRot(yaw);
						mc.player.setYHeadRot(yaw);
						mc.player.setXRot(-10);
					});
					then(40, mc -> {
						var t = (dev.rubymod.entity.VehicleEntity) mc.player.getVehicle();
						log("turret test: look {} hull {} turret {} (rel {}) pitch {}", Math.round(mc.player.getYRot()), Math.round(t.getYRot()),
								Math.round(t.turretYaw()), Math.round(net.minecraft.util.Mth.wrapDegrees(t.turretYaw() - t.getYRot())), Math.round(t.barrelPitch()));
						shot(mc, "turret-" + (deg < 0 ? "m" + -deg : deg));
					});
				}
				// Barrel elevation from the side: aim 90 right and up, then look across it.
				then(5, mc -> {
					float yaw = mc.player.getVehicle().getYRot() - 90;
					mc.player.setYRot(yaw);
					mc.player.setYHeadRot(yaw);
					mc.player.setXRot(-20);
				});
				then(40, mc -> {
					mc.options.setCameraType(CameraType.FIRST_PERSON);
					command(mc, "ride @p dismount");
				});
				then(5, mc -> command(mc, "execute as @p at @e[type=rubymod:tank,limit=1,sort=nearest] run tp @s ~3 ~2.5 ~-11 0 8"));
				then(25, mc -> shot(mc, "turret-side-up"));
			}
			case "scope" -> {
				then(5, mc -> {
					command(mc, "clear @p");
					command(mc, "give @p rubymod:longshot_sniper");
					command(mc, "give @p rubymod:skyhammer");
					command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 0");
					command(mc, "execute at @p run summon rubymod:target_dummy ~ ~ ~20");
				});
				then(10, mc -> mc.player.getInventory().setSelectedSlot(0));
				then(10, mc -> aim(mc, true));
				then(25, mc -> shot(mc, "scope-sniper"));
				then(2, mc -> aim(mc, false));
				then(10, mc -> mc.player.getInventory().setSelectedSlot(1));
				then(10, mc -> aim(mc, true));
				then(25, mc -> shot(mc, "scope-skyhammer"));
				then(2, mc -> aim(mc, false));
			}
			case "apoff" -> {
				then(5, mc -> {
					command(mc, "execute as @p at @s run tp @s ~ ~ ~ 180 0");
					command(mc, "execute at @p run summon rubymod:fighter_jet ~ ~60 ~ {Rotation:[180f,0f]}");
				});
				then(5, mc -> command(mc, "ride @p mount @e[type=rubymod:fighter_jet,limit=1,sort=nearest]"));
				then(5, mc -> {
					var a = aircraft(mc);
					a.launch(2.4);
					mc.player.setYRot(180);
					mc.player.setXRot(0);
					mc.options.keyUp.setDown(true);
				});
				then(40, mc -> {
					mc.options.keyUp.setDown(false);
					Vehicles.toggleAutopilot(mc);
					Vehicles.freePitch = 70; // looking down to aim
					mc.options.keyDown.setDown(true); // throttle back
				});
				then(30, mc -> mc.options.keyDown.setDown(false));
				for (int i = 0; i < 6; i++) {
					then(20, mc -> {
						var a = aircraft(mc);
						if (a == null) { log("autopilot: not flying, vehicle {} pos {}", mc.player.getVehicle(), mc.player.blockPosition()); return; }
						log("autopilot: y {} speed {} pitch {} throttle {}", Math.round(a.getY()), String.format("%.2f", a.speed()), Math.round(a.getXRot()), String.format("%.2f", a.throttle()));
					});
				}
				then(1, mc -> Vehicles.toggleAutopilot(mc));
				for (int i = 0; i < 8; i++) {
					then(10, mc -> {
						var a = aircraft(mc);
						log("after off: y {} speed {} pitch {} player pitch {}", Math.round(a.getY()), String.format("%.2f", a.speed()), Math.round(a.getXRot()), Math.round(mc.player.getXRot()));
					});
				}
			}
			case "heli" -> {
				then(5, mc -> {
					command(mc, "gamemode survival @p");
					command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 0");
					command(mc, "execute at @p run summon rubymod:attack_heli ~ ~ ~3 {Rotation:[0f,0f]}");
					command(mc, "execute at @p run summon rubymod:tank ~ ~ ~60 {Rotation:[180f,0f]}");
				});
				then(10, mc -> command(mc, "ride @p mount @e[type=rubymod:attack_heli,limit=1,sort=nearest]"));
				then(10, mc -> {
					mc.player.setYRot(0);
					mc.player.setXRot(0);
					mc.options.keyJump.setDown(true);
				});
				then(30, mc -> mc.options.keyJump.setDown(false));
				then(5, mc -> heliY = aircraft(mc).getY());
				then(40, mc -> log("heli hover: drifted {} blocks vertically, speed {}", String.format("%.2f", aircraft(mc).getY() - heliY),
						String.format("%.2f", aircraft(mc).speed())));
				then(1, mc -> {
					mc.player.setXRot(40); // looking down must NOT fly it forward any more
				});
				then(30, mc -> {
					log("heli looking down, no keys: speed {}", String.format("%.2f", aircraft(mc).speed()));
					mc.player.setXRot(0);
					mc.options.keyUp.setDown(true);
				});
				then(40, mc -> {
					log("heli W held 2s: speed {} km/h, nose pitch {}", Math.round(aircraft(mc).speed() * 72), Math.round(aircraft(mc).getXRot()));
					mc.options.keyUp.setDown(false);
				});
				then(40, mc -> log("heli released 2s: speed {} km/h", Math.round(aircraft(mc).speed() * 72)));
				then(1, mc -> {
					for (var e : mc.level.entitiesForRendering()) {
						if (e instanceof dev.rubymod.entity.VehicleEntity v) {
							track = v;
						}
					}
				});
				then(1, mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
				// Aim the crosshair at the tank (look only) and use the gun.
				for (int i = 0; i < 40; i++) {
					int n = i;
					then(1, mc -> {
						if (track == null || aircraft(mc) == null) {
							return;
						}
						Vec3 to = track.getBoundingBox().getCenter().subtract(mc.player.getEyePosition());
						mc.player.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
						mc.player.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))));
						if (n == 10) {
							heliHp = ((net.minecraft.world.entity.LivingEntity) track).getHealth();
						}
						if (n >= 10) {
							Trigger.fireAircraft(dev.rubymod.entity.AircraftType.Weapon.CANNON);
						}
						if (n == 20) {
							shot(mc, "heli-gun");
						}
					});
				}
				then(10, mc -> {
					float hp = ((net.minecraft.world.entity.LivingEntity) track).getHealth();
					log("heli chin gun: tank hp {} -> {} (look pitch {})", heliHp, hp, Math.round(mc.player.getXRot()));
					heliHp = hp;
					ClientPlayNetworking.send(new Payloads.AircraftCycle());
				});
				for (int i = 0; i < 4; i++) {
					then(8, mc -> {
						Vec3 to = track.getBoundingBox().getCenter().subtract(mc.player.getEyePosition());
						mc.player.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
						mc.player.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))));
						Trigger.fireAircraft(dev.rubymod.entity.AircraftType.Weapon.ROCKETS);
					});
				}
				then(3, mc -> shot(mc, "heli-rockets"));
				then(40, mc -> log("heli rockets: tank hp {} -> {}, weapon {}", heliHp, ((net.minecraft.world.entity.LivingEntity) track).getHealth(),
						aircraft(mc).selectedWeapon()));
				then(5, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
				then(15, mc -> shot(mc, "heli-third"));
			}
			case "zoom" -> {
				then(5, mc -> {
					command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 0");
					command(mc, "execute at @p run summon rubymod:attack_heli ~ ~ ~3 {Rotation:[0f,0f]}");
					command(mc, "execute at @p run summon rubymod:tank ~ ~ ~90 {Rotation:[180f,0f]}");
				});
				then(10, mc -> command(mc, "ride @p mount @e[type=rubymod:attack_heli,limit=1,sort=nearest]"));
				then(10, mc -> {
					mc.player.setYRot(0);
					mc.player.setXRot(8);
					mc.options.keyJump.setDown(true);
				});
				then(25, mc -> mc.options.keyJump.setDown(false));
				then(30, mc -> shot(mc, "zoom-1x"));
				then(2, mc -> {
					for (int i = 0; i < 3; i++) {
						mc.mouseHandler.onScroll(mc.getWindow().handle(), 0, 1);
					}
					log("zoom after 3 scrolls up: {}x, hotbar slot {}", Vehicles.zoom, mc.player.getInventory().getSelectedSlot());
				});
				then(20, mc -> {
					log("zoomed fov {}", Math.round(mc.gameRenderer.mainCamera().getFov()));
					shot(mc, "zoom-8x");
					float before = mc.player.getYRot();
					mc.player.turn(100, 0);
					log("mouse turn at 8x: {} degrees (unzoomed would be 15)", String.format("%.2f", mc.player.getYRot() - before));
				});
				then(2, mc -> {
					mc.mouseHandler.onScroll(mc.getWindow().handle(), 0, -1);
					log("zoom after 1 scroll down: {}x", Vehicles.zoom);
				});
				then(5, mc -> command(mc, "ride @p dismount"));
				then(5, mc -> log("zoom after getting out: {}x", Vehicles.zoom));
				// Handheld: aim the Lancer and zoom.
				then(5, mc -> {
					command(mc, "give @p rubymod:lancer");
					mc.player.getInventory().setSelectedSlot(0);
				});
				then(10, mc -> {
					mc.player.getInventory().setSelectedSlot(java.util.stream.IntStream.range(0, 9)
							.filter(i -> mc.player.getInventory().getItem(i).getItem() instanceof dev.rubymod.item.LockOnLauncherItem).findFirst().orElse(0));
				});
				then(5, mc -> aim(mc, true));
				then(15, mc -> {
					int slot = mc.player.getInventory().getSelectedSlot();
					mc.mouseHandler.onScroll(mc.getWindow().handle(), 0, 1);
					mc.mouseHandler.onScroll(mc.getWindow().handle(), 0, 1);
					log("lancer zoom: {}x, slot unchanged {}", Vehicles.zoom, slot == mc.player.getInventory().getSelectedSlot());
				});
				then(15, mc -> shot(mc, "zoom-lancer"));
				then(2, mc -> aim(mc, false));
				then(3, mc -> log("lancer lowered: zoom {}x", Vehicles.zoom));
				// Tank: drive and zoom.
				then(5, mc -> command(mc, "ride @p mount @e[type=rubymod:tank,limit=1,sort=nearest]"));
				then(10, mc -> {
					mc.mouseHandler.onScroll(mc.getWindow().handle(), 0, 1);
					log("tank zoom: {}x, riding {}", Vehicles.zoom, mc.player.getVehicle() != null);
				});
				then(15, mc -> shot(mc, "zoom-tank"));
			}
			case "crew" -> {
				// 1. A crash with nobody to blame: the pilot dies with the aircraft.
				then(5, mc -> {
					command(mc, "rubywars phase war");
					command(mc, "gamemode survival @p");
					command(mc, "execute as @p at @s run tp @s ~ ~ ~ 180 0");
					command(mc, "execute at @p run summon rubymod:fighter_jet ~ ~40 ~ {Rotation:[180f,0f]}");
				});
				then(5, mc -> command(mc, "ride @p mount @e[type=rubymod:fighter_jet,limit=1,sort=nearest]"));
				then(5, mc -> aircraft(mc).launch(2.2));
				then(20, mc -> {
					IntegratedServer server = mc.getSingleplayerServer();
					server.execute(() -> {
						var p = server.getPlayerList().getPlayers().get(0);
						if (p.getVehicle() instanceof dev.rubymod.entity.AircraftEntity a) {
							a.hurtServer((net.minecraft.server.level.ServerLevel) p.level(), p.level().damageSources().generic(), 10000.0F);
						}
						log("crew: after the aircraft blew up (no attacker): pilot dead {}, health {}", p.isDeadOrDying(), p.getHealth());
					});
				});
				then(20, mc -> {
					if (mc.player.isDeadOrDying() || mc.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen) {
						mc.player.respawn();
					}
				});
				then(10, mc -> {
					// Dying on Battle Day without a Heart eliminates you; bring the test player back.
					command(mc, "rubywars revive " + mc.player.getName().getString());
					command(mc, "gamemode survival @p");
				});
				// 2. Shot down by another player in war: the pilot dies and the shooter gets the kill.
				then(40, mc -> {
					command(mc, "execute as @p at @s run tp @s ~ ~ ~ 180 0");
					command(mc, "execute at @p run summon rubymod:fighter_jet ~ ~40 ~ {Rotation:[180f,0f]}");
				});
				then(5, mc -> command(mc, "ride @p mount @e[type=rubymod:fighter_jet,limit=1,sort=nearest]"));
				then(5, mc -> aircraft(mc).launch(2.2));
				then(20, mc -> {
					IntegratedServer server = mc.getSingleplayerServer();
					server.execute(() -> {
						var p = server.getPlayerList().getPlayers().get(0);
						var shooter = net.fabricmc.fabric.api.entity.FakePlayer.get((net.minecraft.server.level.ServerLevel) p.level(),
								new com.mojang.authlib.GameProfile(java.util.UUID.fromString("00000000-0000-0000-0000-0000000051b2"), "Gunner"));
						dev.rubymod.game.SeasonData.get().player(shooter.getUUID(), "Gunner");
						log("crew: before shot down: riding {}, canHurt {}", p.getVehicle(), dev.rubymod.game.Rules.canHurt(shooter, p));
						if (p.getVehicle() instanceof dev.rubymod.entity.AircraftEntity a) {
							dev.rubymod.game.SpawnShield.clear(p);
							dev.rubymod.game.Armor.hit((net.minecraft.server.level.ServerLevel) p.level(), a, null, shooter, 10000.0F);
						}
						log("crew: shot down by Gunner: riding {}, pilot dead {}, killer {}", p.getVehicle(), p.isDeadOrDying(),
								p.getLastDamageSource() == null ? "none" : p.getLastDamageSource().getEntity() == null ? "none" : p.getLastDamageSource().getEntity().getName().getString());
					});
				});
				then(20, mc -> {
					if (mc.player.isDeadOrDying() || mc.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen) {
						mc.player.respawn();
					}
				});
				then(10, mc -> {
					// Dying on Battle Day without a Heart eliminates you; bring the test player back.
					command(mc, "rubywars revive " + mc.player.getName().getString());
					command(mc, "gamemode survival @p");
				});
				// 3. Hit confirmation on an animal: a rifle hit, then a rocket kill.
				then(40, mc -> {
					command(mc, "clear @p");
					command(mc, "give @p rubymod:arr_rifle");
					command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 0");
					command(mc, "execute at @p run summon minecraft:cow ~ ~ ~8 {NoAI:1b}");
				});
				then(10, mc -> {
					mc.player.getInventory().setSelectedSlot(0);
					ClientState.hitConfirm = null;
				});
				then(5, mc -> {
					for (var e : mc.level.entitiesForRendering()) {
						if (e instanceof net.minecraft.world.entity.animal.cow.Cow) {
							Vec3 to = e.getBoundingBox().getCenter().subtract(mc.player.getEyePosition());
							mc.player.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
							mc.player.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))));
						}
					}
				});
				then(20, mc -> ClientPlayNetworking.send(new Payloads.Fire()));
				then(4, mc -> {
					var h = ClientState.hitConfirm;
					log("crew: rifle ammo {} (30 before), hit marker {} ms ago", mc.player.getMainHandItem().get(dev.rubymod.registry.ModComponents.AMMO),
							System.currentTimeMillis() - ClientState.hitMarkerAt);
					log("crew: rifle on a cow -> {}", h == null ? "no confirmation" : (h.destroyed() ? "KILLED " : "HIT ") + h.target() + " " + h.percentLeft() + "%");
					shot(mc, "crew-hit-cow");
				});
				then(5, mc -> {
					IntegratedServer server = mc.getSingleplayerServer();
					server.execute(() -> {
						var p = server.getPlayerList().getPlayers().get(0);
						var level = (net.minecraft.server.level.ServerLevel) p.level();
						for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.animal.cow.Cow.class, p.getBoundingBox().inflate(20))) {
							Vec3 from = p.getEyePosition();
							level.addFreshEntity(dev.rubymod.entity.RocketEntity.shell(level, p, from, e.getBoundingBox().getCenter().subtract(from).normalize().scale(2.0), 3.0F));
						}
					});
				});
				then(15, mc -> {
					var h = ClientState.hitConfirm;
					log("crew: rocket on the cow -> {}", h == null ? "no confirmation" : (h.destroyed() ? (h.percentLeft() < 0 ? "KILLED " : "DESTROYED ") : "HIT ") + h.target());
					shot(mc, "crew-kill-cow");
				});
			}
			case "bot" -> {
				then(5, mc -> command(mc, "execute as @p run duel guns bot 2"));
				then(60, mc -> {
					log("bot duel: dim {}, score {}", mc.player.level().dimension().identifier(), botScore(mc));
					IntegratedServer server = mc.getSingleplayerServer();
					server.execute(() -> {
						var p = server.getPlayerList().getPlayers().get(0);
						var bots = p.level().getEntitiesOfClass(dev.rubymod.entity.SoldierEntity.class, p.getBoundingBox().inflate(200));
						log("bot duel server: {} soldiers near, player at {}, first {}", bots.size(), p.blockPosition(), bots.isEmpty() ? "-" : bots.get(0).blockPosition()
								+ " target " + bots.get(0).getTarget() + " removed " + bots.get(0).isRemoved());
					});
					shot(mc, "bot-start");
				});
				// Fight: auto-aim at the bot (the AutoTest tick aims at `track`) and shoot.
				for (int i = 0; i < 480; i++) {
					int n = i;
					then(4, mc -> {
						if (track == null || !track.isAlive()) {
							track = null;
							for (var e : mc.level.entitiesForRendering()) {
								if (e instanceof dev.rubymod.entity.SoldierEntity) {
									track = e;
								}
							}
						}
						if (ClientState.deathInfo != null && System.currentTimeMillis() - ClientState.deathInfoAt < 400 && !botShotKillcam) {
							botShotKillcam = true;
							shot(mc, "bot-killcam");
							log("bot death card: {} (killcam {})", GameHud.deathLine(ClientState.deathInfo), Killcam.active());
						}
						if (mc.player.isSpectator() || track == null) {
							return;
						}
						Integer ammo = mc.player.getMainHandItem().get(dev.rubymod.registry.ModComponents.AMMO);
						if (ammo != null && ammo == 0) {
							ClientPlayNetworking.send(new Payloads.Reload());
						} else if (botShotKillcam && mc.player.hasLineOfSight(track) && n % 3 == 0) { // hold fire until the bot gets a kill
							ClientPlayNetworking.send(new Payloads.Fire());
						}
						if (n % 25 == 0) {
							IntegratedServer server = mc.getSingleplayerServer();
							int botId = track.getId();
							server.execute(() -> {
								var p = server.getPlayerList().getPlayers().get(0);
								if (p.level().getEntity(botId) instanceof dev.rubymod.entity.SoldierEntity b) {
									log("bot server: target {}, hostile to me {}, sees me {}, my mode {}", b.getTarget() == null ? "none" : b.getTarget().getName().getString(),
											b.isHostileTo(p), b.hasLineOfSight(p), p.gameMode.getGameModeForPlayer());
								}
							});
							log("bot duel t{}: score {}, bot hp {} at {} (dist {}), my hp {}, bot target {}", n, botScore(mc), ((net.minecraft.world.entity.LivingEntity) track).getHealth(),
									track.blockPosition(), Math.round(track.distanceTo(mc.player)), mc.player.getHealth(),
									((net.minecraft.world.entity.Mob) track).getTarget());
						}
					});
				}
				then(5, mc -> {
					log("bot duel end: score {}, dim {}", botScore(mc), mc.player.level().dimension().identifier());
					shot(mc, "bot-scoreboard");
					track = null;
				});
				// Update notice: pretend this client has an old modpack.
				then(120, mc -> {
					IntegratedServer server = mc.getSingleplayerServer();
					server.execute(() -> {
						var p = server.getPlayerList().getPlayers().get(0);
						dev.rubymod.game.VersionCheck.onHello(p, "oldbuild");
						dev.rubymod.game.VersionCheck.onJoin(p);
					});
				});
				then(110, mc -> shot(mc, "version-notice"));
			}
			case "thirdguns" -> {
				String[] guns = {"arr_rifle", "service_pistol", "combat_shotgun", "longshot_sniper", "ruby_blaster", "rocket_launcher"};
				then(5, mc -> {
					command(mc, "clear @p");
					for (String gun : guns) {
						command(mc, "give @p rubymod:" + gun);
					}
					command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 0");
					mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
				});
				for (int i = 0; i < guns.length; i++) {
					int slot = i;
					String name = guns[i];
					then(10, mc -> mc.player.getInventory().setSelectedSlot(slot));
					then(12, mc -> shot(mc, "third-" + name));
				}
			}
			case "newstuff" -> {
				// --- No mobs, no hunger
				then(5, mc -> {
					command(mc, "gamemode survival @p");
					command(mc, "difficulty normal");
					command(mc, "execute at @p run summon minecraft:zombie ~3 ~ ~3");
					command(mc, "execute at @p run summon minecraft:cow ~-3 ~ ~3");
					mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).getFoodData().setFoodLevel(4));
				});
				then(40, mc -> {
					long zombies = mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class, mc.player.getBoundingBox().inflate(20)).size();
					long cows = mc.level.getEntitiesOfClass(net.minecraft.world.entity.animal.cow.Cow.class, mc.player.getBoundingBox().inflate(20)).size();
					log("no mobs: zombies {} cows {}, food {}", zombies, cows, mc.player.getFoodData().getFoodLevel());
				});
				// --- Goals
				then(5, mc -> {
					IntegratedServer server = mc.getSingleplayerServer();
					server.execute(() -> {
						var p = server.getPlayerList().getPlayers().get(0);
						dev.rubymod.game.Goals.add(server, p.getUUID(), "heart", 1);
						dev.rubymod.game.Goals.add(server, p.getUUID(), "ruby10", 4);
					});
				});
				then(20, mc -> {
					log("goals: stars {}, first goals {}", ClientState.research == null ? -1 : ClientState.research.points(),
							ClientState.research == null ? "none" : ClientState.research.goals().subList(0, 2));
					shot(mc, "goal-complete");
					mc.gui.setScreen(new ResearchScreen());
				});
				then(10, mc -> shot(mc, "goals-tab"));
				then(2, mc -> mc.gui.setScreen(null));
				// --- Jetpack
				then(5, mc -> {
					command(mc, "item replace entity @p armor.chest with rubymod:jetpack");
					command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 0");
				});
				then(10, mc -> {
					heliY = mc.player.getY();
					mc.options.keyJump.setDown(true);
				});
				for (int i = 0; i < 6; i++) {
					then(10, mc -> log("jetpack: height +{} fuel bar shown {}", String.format("%.1f", mc.player.getY() - heliY), Jetpack.wearing(mc.player)));
				}
				then(1, mc -> {
					shot(mc, "jetpack-hud");
					mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				});
				then(6, mc -> shot(mc, "jetpack-back"));
				then(2, mc -> {
					mc.options.keyJump.setDown(false);
					mc.options.setCameraType(CameraType.FIRST_PERSON);
					command(mc, "execute as @p at @s run tp @s ~ -60 ~ 0 0");
				});
				then(30, mc -> log("jetpack: back on the ground, health {}", mc.player.getHealth()));
				// --- Holo Camouflage: a 3-wide wall disguised as grass, 4 blocks ahead
				then(5, mc -> {
					IntegratedServer server = mc.getSingleplayerServer();
					server.execute(() -> {
						var p = server.getPlayerList().getPlayers().get(0);
						var level = (net.minecraft.server.level.ServerLevel) p.level();
						p.teleportTo(level, Math.floor(p.getX()) + 0.5, Math.floor(p.getY()), Math.floor(p.getZ()) + 0.5, java.util.Set.of(), 0, 0, true);
						var base = p.blockPosition().south(4);
						for (int dx = -1; dx <= 1; dx++) {
							for (int dy = 0; dy <= 2; dy++) {
								var pos = base.offset(dx, dy, 0);
								level.setBlockAndUpdate(pos, dev.rubymod.registry.ModBlocks.HOLO_CAMO.defaultBlockState().setValue(dev.rubymod.block.HoloBlock.DISGUISED, true));
								if (level.getBlockEntity(pos) instanceof dev.rubymod.block.HoloBlockEntity holo) {
									holo.setOwner(p.getUUID());
									holo.setDisguise(net.minecraft.world.level.block.Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
								}
							}
						}
						var stranger = net.fabricmc.fabric.api.entity.FakePlayer.get(level,
								new com.mojang.authlib.GameProfile(java.util.UUID.fromString("00000000-0000-0000-0000-0000000051b3"), "Stranger"));
						var shape = level.getBlockState(base).getCollisionShape(level, base, net.minecraft.world.phys.shapes.CollisionContext.of(stranger));
						var mine = level.getBlockState(base).getCollisionShape(level, base, net.minecraft.world.phys.shapes.CollisionContext.of(p));
						log("holo: solid for a stranger {}, solid for its owner {}", !shape.isEmpty(), !mine.isEmpty());
					});
				});
				then(20, mc -> shot(mc, "holo-wall"));
				then(2, mc -> {
					heliY = mc.player.getZ();
					mc.options.keyUp.setDown(true);
				});
				then(40, mc -> {
					mc.options.keyUp.setDown(false);
					log("holo: owner walked from z {} to {} (wall at +4)", String.format("%.1f", heliY), String.format("%.1f", mc.player.getZ()));
				});
			}
			case "attach" -> {
				then(5, mc -> command(mc, "give @a rubymod:arr_rifle[rubymod:attachments=[\"RED_DOT\",\"SUPPRESSOR\",\"GRIP\",\"EXTENDED_MAG\"]]"));
				then(15, mc -> mc.player.getInventory().setSelectedSlot(0));
				then(15, mc -> shot(mc, "attach-hip"));
				then(2, mc -> aim(mc, true));
				then(18, mc -> shot(mc, "attach-aim"));
				then(2, mc -> aim(mc, false));
				then(2, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
				then(12, mc -> shot(mc, "attach-third"));
				then(2, mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
			}
			case "ghostbench" -> then(5, mc -> {
				var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
				var wall = net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState();
				java.util.List<Integer> cells = new java.util.ArrayList<>();
				for (int y = 0; y < 8; y++) {
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							boolean shell = x == 0 || z == 0 || x == 15 || z == 15 || y == 0 || y == 7;
							cells.add(shell ? 0 : -1);
						}
					}
				}
				var data = new dev.rubymod.industry.Blueprints.Data(16, 8, 16, java.util.List.of(wall), cells);
				long t0 = System.nanoTime();
				var shape = dev.rubymod.client.map.BlueprintGhost.shape(data, net.minecraft.core.Direction.NORTH);
				log("ghost shape for {} blocks took {} ms", data.blocks(), (System.nanoTime() - t0) / 1_000_000);
			});
			case "vehicles" -> vehiclesScript();
			case "strikes" -> strikesScript();
			case "planes" -> planesScript();
			case "showcase" -> showcaseScript();
			case "industry" -> industryScript();
			case "yard" -> yardScript();
			default -> {
				gunsScript();
				tourScript();
				vehiclesScript();
				planesScript();
			}
		}
		then(20, mc -> {
			log("done");
			mc.stop();
		});
	}

	private static final String[] GUNS = {"service_pistol", "arr_rifle", "combat_shotgun", "longshot_sniper", "ruby_blaster", "rocket_launcher"};

	private static void gunsScript() {
		then(5, mc -> {
			for (String gun : GUNS) {
				command(mc, "give @a rubymod:" + gun);
			}
		});
		for (int i = 0; i < GUNS.length; i++) {
			int slot = i;
			String name = GUNS[i];
			then(15, mc -> mc.player.getInventory().setSelectedSlot(slot));
			then(15, mc -> shot(mc, "gun-" + name + "-hold"));
			then(2, mc -> aim(mc, true));
			then(18, mc -> shot(mc, "gun-" + name + "-aim"));
			then(2, mc -> aim(mc, false));
			then(2, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
			then(12, mc -> shot(mc, "gun-" + name + "-third"));
			then(2, mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
		}
		// Fire the rifle: the trigger is left-click (a Fire packet).
		then(5, mc -> {
			mc.player.getInventory().setSelectedSlot(1);
			command(mc, "gamemode survival @a");
			command(mc, "give @a rubymod:heavy_ammo 64");
		});
		then(10, mc -> ClientPlayNetworking.send(new Payloads.Fire()));
		then(2, mc -> shot(mc, "gun-rifle-firing"));
		then(10, mc -> log("rifle ammo after one shot: {}", mc.player.getMainHandItem().get(ModComponents.AMMO)));
		then(2, mc -> command(mc, "gamemode creative @a"));
	}

	/** First-person gun poses for tuning: hip, aimed, mid-reload (mag out, mag in), firing, sprinting; then the hotbar. */
	private static void viewScript() {
		String[] guns = {"arr_rifle", "service_pistol", "combat_shotgun", "longshot_sniper", "ruby_blaster", "rocket_launcher", "skyhammer", "lancer"};
		then(5, mc -> {
			for (String gun : guns) {
				command(mc, "give @a rubymod:" + gun);
			}
			command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 0");
		});
		for (int i = 0; i < guns.length; i++) {
			int slot = i;
			String name = guns[i];
			then(12, mc -> mc.player.getInventory().setSelectedSlot(slot));
			then(14, mc -> shot(mc, "view-" + name + "-hip"));
			if (!name.equals("longshot_sniper")) {
				then(2, mc -> aim(mc, true));
				then(14, mc -> shot(mc, "view-" + name + "-aim"));
				then(2, mc -> aim(mc, false));
			}
			then(8, mc -> {
				ClientState.reloadTicks = 40;
				ClientState.reloadStartedAt = System.currentTimeMillis() - 500;
			});
			then(1, mc -> shot(mc, "view-" + name + "-reload1"));
			then(1, mc -> ClientState.reloadStartedAt = System.currentTimeMillis() - 1300);
			then(1, mc -> shot(mc, "view-" + name + "-reload2"));
			then(1, mc -> ClientState.reloadTicks = 0);
			then(6, mc -> dev.rubymod.client.gun.GunView.onShot());
			then(1, mc -> shot(mc, "view-" + name + "-fire"));
			then(6, mc -> dev.rubymod.client.gun.GunView.debugSprint = true);
			then(12, mc -> shot(mc, "view-" + name + "-sprint"));
			then(1, mc -> dev.rubymod.client.gun.GunView.debugSprint = false);
		}
		then(5, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
		then(14, mc -> shot(mc, "view-third"));
		then(2, mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
		then(2, mc -> {
			for (String item : new String[] {"tank", "jeep", "fighter_jet", "attack_jet", "bomber", "attack_heli", "heavy_aa_gun", "railgun",
					"frag_grenade", "c4", "rocket", "light_ammo", "heavy_ammo", "ruby", "rangefinder", "breaching_charge", "architect_wand",
					"ruby_drill", "combustion_generator", "vault", "red_dot_sight", "suppressor"}) {
				command(mc, "give @a rubymod:" + item);
			}
		});
		then(4, mc -> command(mc, "gamemode survival @a"));
		then(10, mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player)));
		then(12, mc -> shot(mc, "view-inventory"));
		then(2, mc -> {
			mc.gui.setScreen(null);
			command(mc, "gamemode creative @a");
		});
	}

	/** Squads, map, markers, look-at, guards, raiders, research, paint, blueprint ghost. */
	private static void featuresScript() {
		then(3, mc -> log("map chunks known at start: {}", dev.rubymod.client.map.MapCache.size()));
		then(5, mc -> {
			command(mc, "rubywars reset confirm");
			command(mc, "rubywars start");
			command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 10");
			command(mc, "waypoint add Rally Point");
		});
		then(10, mc -> {
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var player = server.getPlayerList().getPlayers().get(0);
				var level = player.level();
				net.minecraft.core.BlockPos base = player.blockPosition();
				// A guard, a squad of raiders, a generator to look at, a painted tank.
				var guard = dev.rubymod.entity.SoldierEntity.guard(level, player, base.offset(-3, 0, 6), dev.rubymod.entity.SoldierEntity.RIFLEMAN);
				level.addFreshEntity(guard);
				var at = dev.rubymod.entity.SoldierEntity.guard(level, player, base.offset(3, 0, 6), dev.rubymod.entity.SoldierEntity.AT_GUNNER);
				level.addFreshEntity(at);
				for (int i = 0; i < 3; i++) {
					var r = dev.rubymod.entity.SoldierEntity.raider(level, base.offset(-4 + i * 4, 0, 22), base, i == 2 ? 1 : 0, -1);
					r.setNoAi(true);
					level.addFreshEntity(r);
				}
				level.setBlockAndUpdate(base.offset(0, 0, 3), dev.rubymod.registry.ModBlocks.COMBUSTION_GENERATOR.defaultBlockState());
				level.setBlockAndUpdate(base.offset(3, 0, 9), dev.rubymod.registry.ModBlocks.FLAK_CANNON.defaultBlockState());
				level.setBlockAndUpdate(base.offset(-3, 0, 9), dev.rubymod.registry.ModBlocks.SAM_SITE.defaultBlockState());
				level.setBlockAndUpdate(base.offset(0, 0, 10), dev.rubymod.registry.ModBlocks.SENTRY_TURRET.defaultBlockState());
				if (level.getBlockEntity(base.offset(0, 0, 3)) instanceof dev.rubymod.industry.MachineBlockEntity m) {
					m.setOwner(player.getUUID());
				}
				var tank = dev.rubymod.registry.ModEntities.TANK.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				tank.snapTo(base.getX() + 10, base.getY(), base.getZ() + 14, 200, 0);
				tank.setPaint(1);
				level.addFreshEntity(tank);
				var jeep = dev.rubymod.registry.ModEntities.JEEP.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				jeep.snapTo(base.getX() - 10, base.getY(), base.getZ() + 14, 160, 0);
				jeep.setPaint(5);
				level.addFreshEntity(jeep);
				dev.rubymod.game.Research.addPoints(player.getUUID(), 6);
				dev.rubymod.game.Research.sync(player);
				// A blueprint of a little 3x3 tower.
				for (var p : net.minecraft.core.BlockPos.betweenClosed(base.offset(20, 0, 0), base.offset(22, 3, 2))) {
					level.setBlockAndUpdate(p, net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
				}
				var data = dev.rubymod.industry.Blueprints.capture(level, base.offset(20, 0, 0), base.offset(22, 3, 2));
				var paper = new net.minecraft.world.item.ItemStack(dev.rubymod.registry.ModItems.BLUEPRINT);
				paper.set(ModComponents.BLUEPRINT, data);
				player.getInventory().setItem(8, paper);
			});
		});
		then(30, mc -> {
			long now = System.currentTimeMillis();
			ClientState.KILLS.addFirst(new ClientState.Kill(new Payloads.KillFeed("Raven", mc.player.getName().getString(), "Longshot Sniper", true), now));
			ClientState.KILLS.addFirst(new ClientState.Kill(new Payloads.KillFeed(mc.player.getName().getString(), "Ghost", "AR-R Rifle", false), now));
			ClientState.KILLS.addFirst(new ClientState.Kill(new Payloads.KillFeed("Ghost", "Raven", "Sentry Turret", false), now));
		});
		then(10, mc -> shot(mc, "features-overview"));
		for (int i = 0; i < 3; i++) {
			then(40, mc -> log("fps {} (minimap {})", mc.getFps(), dev.rubymod.client.map.Minimap.enabled));
		}
		then(1, mc -> dev.rubymod.client.map.Minimap.enabled = false);
		for (int i = 0; i < 3; i++) {
			then(40, mc -> log("fps {} (minimap {})", mc.getFps(), dev.rubymod.client.map.Minimap.enabled));
		}
		then(1, mc -> dev.rubymod.client.map.Minimap.enabled = true);
		then(2, mc -> command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 21"));
		then(20, mc -> shot(mc, "features-look-machine"));
		then(2, mc -> command(mc, "execute as @p at @s run tp @s ~ ~ ~ 26.5 5"));
		then(20, mc -> shot(mc, "features-look-guard"));
		then(2, mc -> command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 15"));
		then(5, mc -> dev.rubymod.client.map.Pinger.ping(mc));
		then(30, mc -> shot(mc, "features-ping"));
		then(2, mc -> {
			mc.player.getInventory().setSelectedSlot(8);
			command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 50");
		});
		then(20, mc -> shot(mc, "features-blueprint-ghost"));
		then(2, mc -> {
			var manual = new ManualScreen();
			manual.showPage(2);
			mc.gui.setScreen(manual);
		});
		then(12, mc -> shot(mc, "features-manual"));
		then(2, mc -> mc.gui.setScreen(new SquadScreen()));
		then(12, mc -> shot(mc, "features-squad"));
		then(2, mc -> {
			mc.gui.setScreen(null);
			mc.player.connection.sendCommand("squad create Ruby Raiders");
		});
		then(40, mc -> mc.gui.setScreen(new SquadScreen()));
		then(12, mc -> shot(mc, "features-squad-member"));
		then(2, mc -> mc.gui.setScreen(new ResearchScreen()));
		then(12, mc -> shot(mc, "features-research"));
		then(2, mc -> mc.player.connection.sendCommand("waypoint at 40 -30 Forward Base"));
		then(30, mc -> log("waypoints: {}", ClientState.map.markers().stream().filter(m -> m.kind() == 0).map(m -> m.name() + "@" + m.x() + "," + m.z()).toList()));
		then(2, mc -> mc.gui.setScreen(new dev.rubymod.client.map.MapScreen()));
		then(12, mc -> shot(mc, "features-map"));
		then(2, mc -> mc.gui.setScreen(null));
		then(2, mc -> command(mc, "execute as @p at @s run tp @s ~ ~4 ~-6 0 25"));
		then(20, mc -> shot(mc, "features-paint"));
		then(2, mc -> {
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var player = server.getPlayerList().getPlayers().get(0);
				var drone = dev.rubymod.entity.ReconDroneEntity.launch(player.level(), player);
				player.level().addFreshEntity(drone);
				var r = dev.rubymod.entity.SoldierEntity.raider(player.level(), player.blockPosition().offset(30, 0, -20), player.blockPosition(), 0, -1);
				r.setNoAi(true);
				player.level().addFreshEntity(r);
			});
		});
		then(120, mc -> {
			long spotted = ClientState.map == null ? -1 : ClientState.map.markers().stream().filter(m -> m.kind() == 7).count();
			log("recon drone: {} enemies spotted on the map", spotted);
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var player = server.getPlayerList().getPlayers().get(0);
				for (var d : player.level().getEntitiesOfClass(dev.rubymod.entity.ReconDroneEntity.class, player.getBoundingBox().inflate(100))) {
					log("drone at {} (player at {})", d.blockPosition(), player.blockPosition());
				}
			});
			for (var e : mc.level.entitiesForRendering()) {
				if (e instanceof dev.rubymod.entity.ReconDroneEntity d) {
					var rel = d.position().subtract(mc.player.getEyePosition());
					mc.player.setYRot((float) Math.toDegrees(Math.atan2(-rel.x, rel.z)));
					mc.player.setXRot((float) -Math.toDegrees(Math.atan2(rel.y, Math.hypot(rel.x, rel.z))));
				}
			}
		});
		then(2, mc -> shot(mc, "features-drone"));
	}

	/** A real raid on a defended base: Heart, a sentry turret, two guards. Logs the fight every 10 seconds. */
	private static void raidScript() {
		then(5, mc -> {
			command(mc, "rubywars reset confirm");
			command(mc, "rubywars start");
		});
		then(10, mc -> {
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var player = server.getPlayerList().getPlayers().get(0);
				var level = player.level();
				var base = player.blockPosition();
				var heart = base.offset(0, 0, 8);
				level.setBlockAndUpdate(heart, dev.rubymod.registry.ModBlocks.RUBY_HEART.defaultBlockState());
				dev.rubymod.game.Claims.onHeartPlaced(level, player, heart);
				var turret = heart.offset(2, 0, 2);
				level.setBlockAndUpdate(turret, dev.rubymod.registry.ModBlocks.SENTRY_TURRET.defaultBlockState());
				if (level.getBlockEntity(turret) instanceof dev.rubymod.block.DefenseBlockEntity d) {
					d.ammo = 300;
				}
				level.addFreshEntity(dev.rubymod.entity.SoldierEntity.guard(level, player, heart.offset(-3, 0, 0), 0));
				level.addFreshEntity(dev.rubymod.entity.SoldierEntity.guard(level, player, heart.offset(3, 0, -2), 1));
				dev.rubymod.industry.Grid.deposit(player.getUUID(), 200_000L);
				String error = dev.rubymod.game.Raids.force(player);
				log("raid started: {}", error == null ? "ok" : error);
			});
		});
		then(100, mc -> {
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var player = server.getPlayerList().getPlayers().get(0);
				player.teleportTo(player.getX() + 18, player.getY(), player.getZ() + 8);
				player.setYRot(-90);
				player.setXRot(10);
			});
		});
		then(20, mc -> shot(mc, "raid-claim-edge"));
		for (int i = 0; i < 12; i++) {
			int n = i;
			then(200, mc -> {
				IntegratedServer server = mc.getSingleplayerServer();
				server.execute(() -> {
					var player = server.getPlayerList().getPlayers().get(0);
					var level = player.level();
					var raiders = level.getEntitiesOfClass(dev.rubymod.entity.SoldierEntity.class, player.getBoundingBox().inflate(200),
							dev.rubymod.entity.SoldierEntity::isRaider);
					var guards = level.getEntitiesOfClass(dev.rubymod.entity.SoldierEntity.class, player.getBoundingBox().inflate(200), sol -> !sol.isRaider());
					double nearest = raiders.stream().mapToDouble(r -> r.distanceTo(player)).min().orElse(-1);
					log("t+{}s raiders {} (nearest {}m) guards {} bank {} tick {}ms fps {}", (n + 1) * 10, raiders.size(), Math.round(nearest), guards.size(),
							dev.rubymod.industry.Grid.rubies(player.getUUID()), String.format("%.2f", server.getAverageTickTimeNanos() / 1e6), mc.getFps());
				});
				if (n % 3 == 0) {
					shot(mc, "raid-" + (n + 1) * 10);
				}
			});
		}
	}

	/** Armory pages (research locks), a backpack, a War Grave after dying. */
	private static void gearScript() {
		then(5, mc -> {
			command(mc, "rubywars reset confirm");
			command(mc, "rubywars start");
			command(mc, "gamemode survival @a");
			command(mc, "give @a rubymod:field_pack");
			command(mc, "give @a rubymod:ruby 20");
			command(mc, "give @a rubymod:arr_rifle");
		});
		for (int page = 0; page < 4; page++) {
			int p = page;
			then(15, mc -> {
				IntegratedServer server = mc.getSingleplayerServer();
				server.execute(() -> Shop.open(server.getPlayerList().getPlayers().get(0), p));
			});
			then(10, mc -> {
				if (mc.gui.screen() instanceof ArmoryScreen armory) {
					armory.selected = p == 0 ? 1 : 0;
				}
			});
			then(5, mc -> shot(mc, "gear-armory-" + p));
			then(2, mc -> mc.player.closeContainer());
		}
		then(5, mc -> {
			int slot = mc.player.getInventory().findSlotMatchingItem(new net.minecraft.world.item.ItemStack(dev.rubymod.registry.ModItems.FIELD_PACK));
			if (slot >= 0 && slot < 9) {
				mc.player.getInventory().setSelectedSlot(slot);
			}
		});
		then(5, mc -> mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND));
		then(15, mc -> shot(mc, "gear-backpack"));
		then(2, mc -> mc.player.closeContainer());
		then(2, mc -> command(mc, "effect clear @a"));
		then(2, mc -> {
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> server.getPlayerList().getPlayers().get(0).setHealth(4.0F));
		});
		then(10, mc -> shot(mc, "gear-lowhealth"));
		then(2, mc -> {
			command(mc, "gamerule keep_inventory false");
			command(mc, "gamerule keepInventory false");
		});
		then(5, mc -> {
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var player = server.getPlayerList().getPlayers().get(0);
				player.hurtServer(player.level(), player.damageSources().genericKill(), 1000.0F);
				log("player dead: {}", player.isDeadOrDying());
			});
		});
		then(10, mc -> {
			ClientState.lastDeath = new Payloads.KillFeed("Raven", mc.player.getName().getString(), "Longshot Sniper", true);
			ClientState.lastDeathAt = System.currentTimeMillis();
		});
		then(25, mc -> shot(mc, "gear-deathscreen"));
		then(5, mc -> {
			if (mc.player.isDeadOrDying()) {
				mc.player.respawn();
			}
		});
		then(40, mc -> {
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var player = server.getPlayerList().getPlayers().get(0);
				log("grave block: {}", player.level().getBlockState(new net.minecraft.core.BlockPos(0, -61, 0)));
				player.teleportTo(0.5, -61, -2.5);
				player.setYRot(0);
				player.setXRot(35);
			});
		});
		then(20, mc -> shot(mc, "gear-grave"));
	}

	/** Rifle fire into a stone wall: debris, sparks, bullet holes. */
	private static void impactScript() {
		then(5, mc -> {
			command(mc, "execute as @p at @s run tp @s ~ ~ ~ 180 0");
			command(mc, "execute at @p run fill ~-3 ~ ~-6 ~3 ~3 ~-6 minecraft:stone_bricks");
			command(mc, "give @a rubymod:arr_rifle");
			command(mc, "give @a rubymod:heavy_ammo 64");
		});
		then(10, mc -> mc.player.getInventory().setSelectedSlot(0));
		for (int i = 0; i < 12; i++) {
			int n = i;
			then(4, mc -> {
				mc.player.setYRot(180 + (n % 4 - 1.5F) * 6);
				mc.player.setXRot((n / 4 - 1) * 6);
				ClientPlayNetworking.send(new Payloads.Fire());
			});
		}
		then(1, mc -> shot(mc, "impact-firing"));
		then(20, mc -> {
			mc.player.setYRot(180);
			mc.player.setXRot(0);
		});
		then(10, mc -> shot(mc, "impact-holes"));
	}

	/** A guard told to follow keeps up as the player walks off. */
	private static void followScript() {
		then(5, mc -> {
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var player = server.getPlayerList().getPlayers().get(0);
				var guard = dev.rubymod.entity.SoldierEntity.guard(player.level(), player, player.blockPosition().offset(2, 0, 0), 0);
				player.level().addFreshEntity(guard);
				guard.interact(player, net.minecraft.world.InteractionHand.MAIN_HAND, guard.position());
				log("guard following: {}", guard.following());
			});
		});
		for (int step = 1; step <= 3; step++) {
			int n = step;
			then(10, mc -> command(mc, "execute as @p at @s run tp @s ~ ~ ~" + 8 + " 0 0"));
			then(100, mc -> {
				IntegratedServer server = mc.getSingleplayerServer();
				server.execute(() -> {
					var player = server.getPlayerList().getPlayers().get(0);
					for (var gd : player.level().getEntitiesOfClass(dev.rubymod.entity.SoldierEntity.class, player.getBoundingBox().inflate(80))) {
						log("step {}: guard {} blocks away", n, Math.round(gd.distanceTo(player)));
					}
				});
			});
		}
		then(5, mc -> shot(mc, "follow"));
	}

	/** Autopilot bombing run: level the bomber, look down at a tank, release when IN REACH, check the hit. */
	private static void bombRunScript() {
		then(5, mc -> {
			command(mc, "rubywars phase war");
			command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 0");
			command(mc, "execute at @p run summon rubymod:tank ~ ~ ~70 {Rotation:[0f,0f]}");
			command(mc, "execute at @p run summon rubymod:bomber ~ ~50 ~-60 {Rotation:[0f,0f]}");
		});
		then(5, mc -> command(mc, "ride @p mount @e[type=rubymod:bomber,limit=1,sort=nearest]"));
		then(5, mc -> {
			var a = aircraft(mc);
			if (a == null) {
				log("FAIL: could not board the bomber");
				return;
			}
			a.launch(2.4);
			mc.options.keyUp.setDown(true);
			mc.player.setYRot(0);
			mc.player.setXRot(0);
			for (var e : mc.level.entitiesForRendering()) {
				if (e instanceof dev.rubymod.entity.VehicleEntity v) {
					track = v;
				}
			}
		});
		then(4, mc -> {
			Vehicles.toggleAutopilot(mc);
			log("autopilot {} at altitude {}", Vehicles.autopilot, Math.round(Vehicles.autopilotAltitude));
		});
		// Every few ticks: look at the tank; drop when the HUD says it's in reach.
		for (int i = 0; i < 60; i++) {
			int n = i;
			then(3, mc -> {
				var a = aircraft(mc);
				if (a == null || track == null) {
					return;
				}
				Vec3 rel = track.position().subtract(mc.player.getEyePosition());
				float yaw = (float) Math.toDegrees(Math.atan2(-rel.x, rel.z));
				float pitch = (float) -Math.toDegrees(Math.atan2(rel.y, Math.hypot(rel.x, rel.z)));
				Vehicles.freeYaw = net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot());
				Vehicles.freePitch = Math.min(89.0F, pitch - mc.player.getXRot());
				Vec3 t = Targeting.aimPoint;
				Vec3 impact = Targeting.bombImpact;
				if (t != null && impact != null) {
					double alt = Math.max(1.0, a.getY() - t.y);
					double fall = Math.sqrt(2.0 * alt / 0.08);
					double reach = 0.5 * dev.rubymod.entity.BombEntity.MAX_STEER * fall * fall * 0.55 + 4.0;
					double miss = Math.hypot(impact.x - t.x, impact.z - t.z);
					if (n % 5 == 0) {
						log("t{} alt {} miss {} reach {} pitch {} plane pitch {} speed {} y {} ap {}", n, Math.round(alt), Math.round(miss), Math.round(reach),
								Math.round(mc.player.getXRot()), Math.round(a.getXRot()), Math.round(a.speed() * 72), Math.round(a.getY()), Vehicles.autopilot);
					}
					if (miss <= reach && a.ammo(dev.rubymod.entity.AircraftType.Weapon.BOMBS) > 0 && n % 2 == 0 && dropped < 3) {
						if (dropped == 0) {
							shot(mc, "bombrun-release");
						}
						dropped++;
						Trigger.fireAircraft(dev.rubymod.entity.AircraftType.Weapon.BOMBS);
					}
				}
			});
		}
		then(60, mc -> {
			log("bomb run: dropped {}, tank {}", dropped, track == null ? "none" : track.isAlive()
					? "alive, hp " + ((net.minecraft.world.entity.LivingEntity) track).getHealth() : "destroyed");
			mc.options.keyUp.setDown(false);
		});
	}

	private static net.fabricmc.fabric.api.entity.FakePlayer opponent;

	/** Fake opponent "Sibling" on the server, created once. */
	private static void startDuel(Minecraft mc, dev.rubymod.game.Duels.Mode mode, int kills) {
		IntegratedServer server = mc.getSingleplayerServer();
		server.execute(() -> {
			var p = server.getPlayerList().getPlayers().get(0);
			if (opponent == null) {
				opponent = net.fabricmc.fabric.api.entity.FakePlayer.get(p.level(),
						new com.mojang.authlib.GameProfile(java.util.UUID.fromString("00000000-0000-0000-0000-0000000051b1"), "Sibling"));
			}
			dev.rubymod.game.Duels.startForTest(p, opponent, mode, kills);
		});
	}

	private static void killOpponent(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		server.execute(() -> {
			var p = server.getPlayerList().getPlayers().get(0);
			dev.rubymod.game.Duels.onDeath(opponent, p.damageSources().playerAttack(p));
		});
	}

	private static void overview(Minecraft mc, int height, int back) {
		IntegratedServer server = mc.getSingleplayerServer();
		server.execute(() -> {
			var p = server.getPlayerList().getPlayers().get(0);
			p.stopRiding();
			p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
			double cx = Math.round(p.getX() / 5000.0) * 5000.0;
			p.teleportTo((net.minecraft.server.level.ServerLevel) p.level(), cx, 65 + height, -back, java.util.Set.of(), 0, 35, true);
		});
	}

	private static void duelScript() {
		then(5, mc -> {
			command(mc, "give @p rubymod:ruby 7");
			startDuel(mc, dev.rubymod.game.Duels.Mode.GUNS, 2);
		});
		then(60, mc -> {
			log("duel guns: dim {} gamemode {} rifle {} ruby {} y {}", mc.player.level().dimension().identifier(), mc.gameMode.getPlayerMode(),
					mc.player.getInventory().countItem(dev.rubymod.registry.ModItems.ARR_RIFLE), mc.player.getInventory().countItem(dev.rubymod.registry.ModItems.RUBY),
					Math.round(mc.player.getY()));
			shot(mc, "duel-guns-spawn");
		});
		then(5, mc -> killOpponent(mc));
		then(10, mc -> shot(mc, "duel-guns-plus1"));
		then(40, mc -> command(mc, "kill @p"));
		then(10, mc -> {
			log("duel after real death: gamemode {} alive {} health {}", mc.gameMode.getPlayerMode(), mc.player.isAlive(), mc.player.getHealth());
			shot(mc, "duel-guns-killed");
		});
		then(70, mc -> log("duel respawned: gamemode {} rifle {} pos {}", mc.gameMode.getPlayerMode(),
				mc.player.getInventory().countItem(dev.rubymod.registry.ModItems.ARR_RIFLE), mc.player.blockPosition()));
		then(5, mc -> overview(mc, 22, 44));
		then(40, mc -> shot(mc, "duel-guns-overview"));
		then(5, mc -> killOpponent(mc));
		then(10, mc -> shot(mc, "duel-guns-victory"));
		then(110, mc -> log("duel over: dim {} gamemode {} ruby {} rifle {}", mc.player.level().dimension().identifier(), mc.gameMode.getPlayerMode(),
				mc.player.getInventory().countItem(dev.rubymod.registry.ModItems.RUBY), mc.player.getInventory().countItem(dev.rubymod.registry.ModItems.ARR_RIFLE)));

		then(10, mc -> startDuel(mc, dev.rubymod.game.Duels.Mode.VEHICLES, 10));
		then(80, mc -> {
			log("duel vehicles: riding {}", mc.player.getVehicle());
			shot(mc, "duel-vehicles-spawn");
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		});
		then(15, mc -> shot(mc, "duel-vehicles-third"));
		then(5, mc -> {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			overview(mc, 45, 160);
		});
		then(60, mc -> shot(mc, "duel-vehicles-overview"));
		then(5, mc -> command(mc, "execute as @p run duel leave"));
		then(20, mc -> log("duel left: dim {} ruby {}", mc.player.level().dimension().identifier(), mc.player.getInventory().countItem(dev.rubymod.registry.ModItems.RUBY)));

		then(10, mc -> startDuel(mc, dev.rubymod.game.Duels.Mode.PLANES, 10));
		then(80, mc -> {
			log("duel planes: riding {}", mc.player.getVehicle());
			shot(mc, "duel-planes-spawn");
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		});
		then(15, mc -> shot(mc, "duel-planes-third"));
		then(5, mc -> {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			overview(mc, 30, 330);
		});
		then(60, mc -> shot(mc, "duel-planes-overview"));
		then(5, mc -> command(mc, "execute as @p run duel leave"));
		then(20, mc -> {
			log("duel left planes: dim {} ruby {} gamemode {}", mc.player.level().dimension().identifier(),
					mc.player.getInventory().countItem(dev.rubymod.registry.ModItems.RUBY), mc.gameMode.getPlayerMode());
			command(mc, "execute as @p run duel");
		});
		then(20, mc -> shot(mc, "duel-help"));
	}

	private static int dropped;
	private static boolean botShotKillcam;

	private static String botScore(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		var p = server.getPlayerList().getPlayers().get(0);
		return String.valueOf(dev.rubymod.game.Duels.score(p));
	}
	private static double heliY;
	private static float heliHp;

	private static void tourScript() {
		then(5, mc -> {
			command(mc, "rubywars reset confirm");
			command(mc, "rubywars start");
			String[] blocks = {"ruby_block", "ruby_ore", "deepslate_ruby_ore", "ruby_heart", "armory", "sandbags", "concrete_wall",
					"reinforced_concrete", "sentry_turret", "missile_interceptor", "radar_station", "alarm_block", "mortar", "missile_silo",
					"landmine", "barbed_wire"};
			for (int i = 0; i < blocks.length; i++) {
				command(mc, "execute at @p run setblock ~" + (i - 4) + " ~ ~5 rubymod:" + blocks[i]);
			}
		});
		then(20, mc -> shot(mc, "tour-blocks"));
		then(5, mc -> {
			command(mc, "rubywars phase war");
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> Shop.open(server.getPlayerList().getPlayers().get(0), 0));
		});
		then(25, mc -> shot(mc, "tour-armory"));
		then(5, mc -> mc.player.closeContainer());
		then(5, mc -> {
			command(mc, "execute as @p run rubywars kit");
			mc.player.getInventory().setSelectedSlot(1);
		});
		then(10, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
		then(15, mc -> shot(mc, "tour-kit-armor"));
		then(2, mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
	}

	private static void vehiclesScript() {
		then(5, mc -> {
			command(mc, "execute as @p at @s run tp @s ~ ~ ~ 180 15");
			command(mc, "execute at @p run summon rubymod:tank ~-5 ~ ~-10 {Rotation:[150f,0f]}");
			command(mc, "execute at @p run summon rubymod:jeep ~5 ~ ~-9 {Rotation:[210f,0f]}");
		});
		then(20, mc -> shot(mc, "vehicles-front"));
		then(2, mc -> command(mc, "execute as @p at @s run tp @s ~-14 ~4 ~-6 -125 20"));
		then(15, mc -> shot(mc, "vehicles-side"));
		then(2, mc -> {
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			command(mc, "ride @p mount @e[type=rubymod:tank,limit=1,sort=nearest]");
		});
		then(10, mc -> {
			if (mc.player.getVehicle() == null) {
				log("FAIL: could not mount the tank");
				return;
			}
			start = mc.player.getVehicle().position();
			mc.options.keyUp.setDown(true);
			mc.player.setYRot(mc.player.getYRot() + 60);
		});
		then(40, mc -> {
			mc.options.keyUp.setDown(false);
			if (mc.player.getVehicle() != null && start != null) {
				log("tank drove {} blocks", String.format("%.1f", mc.player.getVehicle().position().distanceTo(start)));
			}
		});
		then(15, mc -> shot(mc, "vehicles-turret-aim"));
		then(2, mc -> ClientPlayNetworking.send(new Payloads.VehicleFire()));
		then(3, mc -> shot(mc, "vehicles-tank-firing"));
		then(30, mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
		then(10, mc -> shot(mc, "vehicles-tank-first-person"));
		then(15, mc -> command(mc, "ride @p dismount"));
		then(10, mc -> command(mc, "ride @p mount @e[type=rubymod:jeep,limit=1,sort=nearest]"));
		then(5, mc -> {
			mc.player.setXRot(5);
			mc.player.setYRot(mc.player.getVehicle() != null ? mc.player.getVehicle().getYRot() : 0);
		});
		then(20, mc -> {
			log("jeep eye {} above jeep, local {}, jeep yaw {}, player yaw {}", String.format("%.2f", mc.player.getEyeY() - mc.player.getVehicle().getY()),
					mc.player.position().subtract(mc.player.getVehicle().position()), mc.player.getVehicle().getYRot(), mc.player.getYRot());
			shot(mc, "vehicles-jeep-first-person");
		});
		then(2, mc -> mc.player.setYRot(mc.player.getYRot() + 180));
		then(5, mc -> shot(mc, "vehicles-jeep-first-person-b"));
		then(5, mc -> command(mc, "ride @p dismount"));

		then(40, mc -> {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			command(mc, "kill @e[type=rubymod:tank]");
			command(mc, "kill @e[type=rubymod:jeep]");
		});
		// Air strike flyover over a spot ahead of the player.
		then(20, mc -> {
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var player = server.getPlayerList().getPlayers().get(0);
				var level = (net.minecraft.server.level.ServerLevel) player.level();
				Vec3 target = player.position().add(0, 0, 22);
				level.addFreshEntity(dev.rubymod.entity.AircraftEntity.airStrike(level, player, target, 90.0F));
			});
			command(mc, "execute as @p at @s run tp @s ~ ~ ~-8 0 -10");
		});
		then(40, mc -> shot(mc, "airstrike-flyover"));
		then(25, mc -> shot(mc, "airstrike-impact"));
		then(2, mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
	}

	private static Vec3 origin;

	/** Teleport relative to the training ground origin (the spawn point). */
	private static void tpTo(Minecraft mc, double dx, double dy, double dz, float yaw, float pitch) {
		command(mc, String.format(java.util.Locale.ROOT, "tp @p %.2f %.2f %.2f %.1f %.1f", origin.x + dx, origin.y + dy, origin.z + dz, yaw, pitch));
	}

	/** Tour of the training world built by /rubywars training (run in the "Ruby Wars Training" save). */
	private static void trainingScript() {
		then(0, mc -> {
			var level = mc.getSingleplayerServer().overworld();
			origin = net.minecraft.world.phys.Vec3.atBottomCenterOf(level.getRespawnData().pos());
			command(mc, "gamemode creative @a");
			log("training origin {}", origin);
		});
		then(10, mc -> tpTo(mc, 0, 14, -22, 0, 25));
		then(30, mc -> shot(mc, "training-overview"));
		then(2, mc -> tpTo(mc, 30, 40, -50, 20, 22));
		then(30, mc -> shot(mc, "training-overview-high"));
		// Gun range: stand on the firing line, shoot a 10 m target with the rifle.
		then(2, mc -> {
			tpTo(mc, -40.5, 0, -3, 0, 2);
			command(mc, "clear @a");
			command(mc, "give @a rubymod:arr_rifle");
			command(mc, "give @a rubymod:heavy_ammo 64");
			command(mc, "give @a rubymod:service_pistol");
			command(mc, "give @a rubymod:light_ammo 64");
		});
		then(30, mc -> {
			shot(mc, "training-gunrange");
			mc.player.getInventory().setSelectedSlot(0);
			command(mc, "gamemode survival @a");
		});
		then(5, mc -> ClientPlayNetworking.send(new Payloads.Reload()));
		then(50, mc -> {
			// Aim at the nearest dummy's chest.
			for (var e : mc.level.entitiesForRendering()) {
				if (e instanceof dev.rubymod.entity.TargetDummyEntity && e.distanceTo(mc.player) < 16) {
					track = e;
				}
			}
			log("gun range target: {}", track == null ? "none" : track.position());
		});
		for (int i = 0; i < 4; i++) {
			then(5, mc -> ClientPlayNetworking.send(new Payloads.Fire()));
		}
		then(10, mc -> {
			log("dummy label after 4 shots: {}", track == null ? "-" : track.getCustomName().getString());
			track = null;
		});
		then(5, mc -> shot(mc, "training-dummy-hit"));
		// Block damage walls: pistol into the dirt and planks.
		then(5, mc -> {
			tpTo(mc, -61.5, 0, 12, 180, -8);
			mc.player.getInventory().setSelectedSlot(2);
		});
		then(10, mc -> ClientPlayNetworking.send(new Payloads.Reload()));
		then(40, mc -> mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, new Vec3(origin.x - 62.5, origin.y + 1.5, origin.z + 20.5)));
		then(3, mc -> ClientPlayNetworking.send(new Payloads.Fire()));
		then(8, mc -> ClientPlayNetworking.send(new Payloads.Fire()));
		then(8, mc -> shot(mc, "training-wall-cracked"));
		then(8, mc -> ClientPlayNetworking.send(new Payloads.Fire()));
		then(10, mc -> {
			var pos = net.minecraft.core.BlockPos.containing(origin.x - 62.5, origin.y + 1.5, origin.z + 20.5);
			log("dirt wall block after 3 pistol hits: {}", mc.level.getBlockState(pos));
			shot(mc, "training-wall-holed");
			command(mc, "gamemode creative @a");
		});
		then(5, mc -> tpTo(mc, 37, 6, -14, 0, 20));
		then(30, mc -> shot(mc, "training-vehicle-yard"));
		then(2, mc -> tpTo(mc, 4, 8, 92, -60, 18));
		then(30, mc -> shot(mc, "training-apron"));
		then(2, mc -> tpTo(mc, 40, 25, 225, 0, 35));
		then(30, mc -> shot(mc, "training-bombing-range"));
		// Taxi the parked fighter to the runway and take off.
		then(2, mc -> tpTo(mc, 16, 0, 106, 180, 10));
		then(20, mc -> command(mc, "ride @p mount @e[type=rubymod:fighter_jet,limit=1,sort=nearest]"));
		then(10, mc -> {
			var a = aircraft(mc);
			if (a == null) {
				log("FAIL: could not board the parked fighter");
				return;
			}
			runwayStart = a.position();
			mc.player.setYRot(90);
			mc.player.setXRot(0);
			mc.options.keyUp.setDown(true);
		});
		then(12, mc -> mc.options.keyUp.setDown(false));
		then(60, mc -> {
			var a = aircraft(mc);
			if (a != null) {
				log("taxi: moved {} blocks, speed {} km/h, heading {}", String.format("%.1f", a.position().distanceTo(runwayStart)),
						Math.round(a.speed() * 72), Math.round(a.getYRot()));
				shot(mc, "training-taxi");
			}
		});
		// Turn onto the runway centerline (left turn toward south) with the A key.
		then(2, mc -> mc.options.keyLeft.setDown(true));
		then(30, mc -> {
			mc.options.keyLeft.setDown(false);
			var a = aircraft(mc);
			if (a != null) {
				log("after steering left: heading {}, at x {} z {}", Math.round(a.getYRot()), String.format("%.1f", a.getX() - origin.x),
						String.format("%.1f", a.getZ() - origin.z));
			}
		});
		then(40, mc -> {
			var a = aircraft(mc);
			if (a != null) {
				log("rolling: heading {}, speed {} km/h, at x {} z {}", Math.round(a.getYRot()), Math.round(a.speed() * 72),
						String.format("%.1f", a.getX() - origin.x), String.format("%.1f", a.getZ() - origin.z));
			}
			mc.options.keyDown.setDown(true); // Brake to a stop.
		});
		then(60, mc -> {
			mc.options.keyDown.setDown(false);
			var a = aircraft(mc);
			if (a != null) {
				log("after braking: speed {} km/h", Math.round(a.speed() * 72));
			}
			command(mc, "ride @p dismount");
		});
		then(10, mc -> shot(mc, "training-after-taxi"));
	}

	/** The training world's Industry Yard: the yard, the Constructor at work, and machine screens. */
	private static void yardScript() {
		then(0, mc -> {
			var level = mc.getSingleplayerServer().overworld();
			origin = Vec3.atBottomCenterOf(level.getRespawnData().pos());
			command(mc, "gamemode creative @a");
		});
		then(5, mc -> command(mc, String.format(java.util.Locale.ROOT, "tp @p %.1f %.1f %.1f facing %.1f %.1f %.1f", origin.x, origin.y + 9,
				origin.z - 6, origin.x, origin.y, origin.z - 26)));
		then(1, mc -> mc.player.getAbilities().flying = true);
		then(30, mc -> shot(mc, "yard-overview"));
		then(2, mc -> command(mc, String.format(java.util.Locale.ROOT, "tp @p %.1f %.1f %.1f facing %.1f %.1f %.1f", origin.x, origin.y + 7,
				origin.z - 32, origin.x, origin.y + 2, origin.z - 44)));
		then(1, mc -> mc.player.getAbilities().flying = true);
		then(30, mc -> shot(mc, "yard-north"));
		then(2, mc -> command(mc, String.format(java.util.Locale.ROOT, "tp @p %.1f %.1f %.1f facing %.1f %.1f %.1f", origin.x - 11, origin.y + 3,
				origin.z - 19, origin.x - 11, origin.y, origin.z - 24)));
		then(1, mc -> mc.player.getAbilities().flying = true);
		then(25, mc -> shot(mc, "yard-ore-line"));
		then(2, mc -> command(mc, String.format(java.util.Locale.ROOT, "tp @p %.1f %.1f %.1f facing %.1f %.1f %.1f", origin.x + 22, origin.y + 6,
				origin.z - 22, origin.x + 16, origin.y, origin.z - 29)));
		then(1, mc -> mc.player.getAbilities().flying = true);
		then(240, mc -> shot(mc, "yard-constructor-building"));
		then(200, mc -> command(mc, String.format(java.util.Locale.ROOT, "tp @p %.1f %.1f %.1f facing %.1f %.1f %.1f", origin.x - 11, origin.y + 2,
				origin.z - 21, origin.x - 11, origin.y, origin.z - 24)));
		for (int i = 0; i < 4; i++) {
			then(i == 0 ? 5 : 100, mc -> {
				IntegratedServer server = mc.getSingleplayerServer();
				server.execute(() -> {
					var level = server.overworld();
					StringBuilder sb = new StringBuilder();
					for (int x : new int[] {-15, -14, -9}) {
						if (level.getBlockEntity(net.minecraft.core.BlockPos.containing(origin.x + x, origin.y, origin.z - 24))
								instanceof dev.rubymod.industry.MachineBlockEntity be) {
							sb.append(be.kind()).append('[');
							for (int k = 0; k < be.getContainerSize(); k++) {
								if (!be.getItem(k).isEmpty()) {
									sb.append(be.getItem(k)).append(' ');
								}
							}
							sb.append("] ");
						}
					}
					var items = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
							new net.minecraft.world.phys.AABB(origin.x - 16, origin.y - 1, origin.z - 26, origin.x - 8, origin.y + 2, origin.z - 22));
					sb.append("items on belt: ");
					items.forEach(it -> sb.append(it.getItem()).append(String.format(java.util.Locale.ROOT, "@%.1f ", it.getX() - origin.x)));
					sb.append(" bank ").append(dev.rubymod.industry.Grid.milliRubies(new java.util.UUID(0, 0)));
					log("ore line: {}", sb);
				});
			});
		}
		then(5, mc -> shot(mc, "yard-conveyor"));
		for (int[] m : new int[][] {{16, -24}, {-6, -24}, {-9, -24}, {-30, -24}, {-15, -24}}) {
			then(5, mc -> {
				command(mc, String.format(java.util.Locale.ROOT, "tp @p %.1f %.1f %.1f", origin.x + m[0], origin.y, origin.z + m[1] + 2.5));
				IntegratedServer server = mc.getSingleplayerServer();
				server.execute(() -> {
					var p = server.getPlayerList().getPlayers().get(0);
					var pos = net.minecraft.core.BlockPos.containing(origin.x + m[0], origin.y, origin.z + m[1]);
					if (p.level().getBlockEntity(pos) instanceof dev.rubymod.industry.MachineBlockEntity be) {
						p.openMenu(be);
					} else {
						log("no machine at {}", pos);
					}
				});
			});
			then(20, mc -> shot(mc, "yard-screen-" + m[0]));
			then(2, mc -> mc.player.closeContainer());
		}
	}

	/** The industry machines, their screen, the Heavy AA Gun, and the Architect's Wand. */
	private static void industryScript() {
		String[] machines = {"combustion_generator", "solar_panel", "battery", "ruby_drill", "ore_refinery", "ammo_factory", "trade_depot",
				"vault", "shield_generator", "point_defense", "constructor"};
		then(5, mc -> {
			origin = mc.player.position();
			command(mc, "gamemode creative @a");
			command(mc, "execute at @p run fill ~-12 ~ ~-4 ~24 ~12 ~20 minecraft:air");
			command(mc, "execute at @p run fill ~-12 ~-1 ~-4 ~24 ~-1 ~20 minecraft:smooth_stone");
			for (int i = 0; i < machines.length; i++) {
				command(mc, String.format(java.util.Locale.ROOT, "setblock %d %d %d rubymod:%s[facing=north]", (int) Math.floor(origin.x) - 5 + i * 2,
						(int) Math.floor(origin.y), (int) Math.floor(origin.z) + 6, machines[i]));
			}
		});
		then(10, mc -> command(mc, String.format(java.util.Locale.ROOT, "tp @p %.1f %.1f %.1f facing %.1f %.1f %.1f", origin.x + 5, origin.y + 3,
				origin.z - 2, origin.x + 5, origin.y + 0.5, origin.z + 6.5)));
		then(1, mc -> mc.player.getAbilities().flying = true);
		then(25, mc -> shot(mc, "industry-machines"));
		then(2, mc -> command(mc, String.format(java.util.Locale.ROOT, "tp @p %.1f %.1f %.1f facing %.1f %.1f %.1f", origin.x - 3, origin.y + 1.5,
				origin.z + 3, origin.x - 1, origin.y + 0.5, origin.z + 6.5)));
		then(1, mc -> mc.player.getAbilities().flying = true);
		then(20, mc -> shot(mc, "industry-close"));
		// Open each kind of machine screen.
		for (int i : new int[] {0, 3, 4, 5, 7, 10}) {
			int index = i;
			then(5, mc -> {
				IntegratedServer server = mc.getSingleplayerServer();
				server.execute(() -> {
					var p = server.getPlayerList().getPlayers().get(0);
					var pos = net.minecraft.core.BlockPos.containing(origin.x - 5 + index * 2, origin.y, origin.z + 6);
					if (p.level().getBlockEntity(pos) instanceof dev.rubymod.industry.MachineBlockEntity be) {
						if (index == 0) {
							be.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL, 12));
						}
						p.openMenu(be);
					}
				});
			});
			then(20, mc -> shot(mc, "industry-screen-" + machines[index]));
			then(2, mc -> mc.player.closeContainer());
		}
		// Architect's Wand: a hollow room of stone bricks.
		then(5, mc -> {
			command(mc, "clear @a");
			command(mc, "give @a rubymod:architect_wand");
			command(mc, "item replace entity @p weapon.offhand with minecraft:stone_bricks 64");
			command(mc, String.format(java.util.Locale.ROOT, "tp @p %.1f %.1f %.1f facing %.1f %.1f %.1f", origin.x + 10, origin.y + 6, origin.z - 3,
					origin.x + 14, origin.y, origin.z + 4));
		});
		then(10, mc -> {
			mc.player.getInventory().setSelectedSlot(0);
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var p = server.getPlayerList().getPlayers().get(0);
				var stack = p.getMainHandItem();
				stack.set(dev.rubymod.registry.ModComponents.WAND_MODE, dev.rubymod.item.BuildShapes.Mode.ROOM.ordinal());
				stack.set(dev.rubymod.registry.ModComponents.CORNER, net.minecraft.core.BlockPos.containing(origin.x + 10, origin.y, origin.z + 2));
			});
		});
		then(15, mc -> shot(mc, "industry-wand-preview"));
		then(2, mc -> {
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var p = server.getPlayerList().getPlayers().get(0);
				var hit = new net.minecraft.world.phys.BlockHitResult(new Vec3(origin.x + 16, origin.y - 0.5, origin.z + 8), net.minecraft.core.Direction.UP,
						net.minecraft.core.BlockPos.containing(origin.x + 16, origin.y - 1, origin.z + 8), false);
				p.getMainHandItem().useOn(new net.minecraft.world.item.context.UseOnContext(p, net.minecraft.world.InteractionHand.MAIN_HAND, hit));
			});
		});
		then(15, mc -> shot(mc, "industry-wand-room"));
		// Heavy AA Gun against a drone.
		then(5, mc -> {
			command(mc, String.format(java.util.Locale.ROOT, "summon rubymod:heavy_aa_gun %.1f %.1f %.1f {Rotation:[0f,0f]}", origin.x, origin.y, origin.z - 2));
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var level = (net.minecraft.server.level.ServerLevel) server.getPlayerList().getPlayers().get(0).level();
				level.addFreshEntity(dev.rubymod.entity.AircraftEntity.targetDrone(level, new Vec3(origin.x, origin.y + 40, origin.z + 60), 30));
			});
			command(mc, String.format(java.util.Locale.ROOT, "tp @p %.1f %.1f %.1f facing %.1f %.1f %.1f", origin.x + 5, origin.y + 2, origin.z - 8,
					origin.x, origin.y + 1, origin.z - 2));
		});
		then(15, mc -> {
			mc.player.getAbilities().flying = true;
			shot(mc, "industry-aa-gun");
		});
		then(2, mc -> command(mc, "ride @p mount @e[type=rubymod:heavy_aa_gun,limit=1,sort=nearest]"));
		then(10, mc -> {
			double best = Double.MAX_VALUE;
			for (var e : mc.level.entitiesForRendering()) {
				if (e instanceof dev.rubymod.entity.AircraftEntity d && !d.isVehicle() && d.distanceTo(mc.player) < best) {
					best = d.distanceTo(mc.player);
					track = d;
				}
			}
			mc.options.keyAttack.setDown(true);
		});
		then(20, mc -> {
			shot(mc, "industry-aa-firing");
			IntegratedServer server = mc.getSingleplayerServer();
			int id = track == null ? -1 : track.getId();
			server.execute(() -> {
				var p = server.getPlayerList().getPlayers().get(0);
				if (p.getVehicle() instanceof dev.rubymod.entity.HeavyAAGunEntity gun && p.level().getEntity(id) instanceof net.minecraft.world.entity.Entity d) {
					Vec3 to = d.getBoundingBox().getCenter().subtract(gun.position().add(0, 1.5, 0));
					log("AA aim: gun yaw {} pitch {} (player {} {}), to drone yaw {} pitch {} dist {}, ammo {}", gun.turretYaw(), gun.gunPitch(),
							p.getYHeadRot(), p.getXRot(), Math.toDegrees(Math.atan2(-to.x, to.z)),
							-Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))), to.length(), gun.ammo());
				} else {
					log("AA aim: not riding ({}) or no drone {}", p.getVehicle(), id);
				}
			});
		});
		then(60, mc -> {
			mc.options.keyAttack.setDown(false);
			log("drone after Heavy AA: {}", track == null ? "none" : track.isAlive() ? "alive, hp " + ((net.minecraft.world.entity.LivingEntity) track).getHealth() : "destroyed");
			track = null;
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		});
		then(15, mc -> shot(mc, "industry-aa-third"));
		then(5, mc -> {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			command(mc, "ride @p dismount");
		});

		// Railgun vs a tank.
		then(10, mc -> {
			command(mc, "kill @e[type=rubymod:heavy_aa_gun]");
			command(mc, String.format(java.util.Locale.ROOT, "summon rubymod:railgun %.1f %.1f %.1f {Rotation:[0f,0f]}", origin.x, origin.y, origin.z - 2));
			command(mc, String.format(java.util.Locale.ROOT, "summon rubymod:tank %.1f %.1f %.1f {Rotation:[90f,0f]}", origin.x, origin.y, origin.z + 40));
			command(mc, "gamemode survival @a");
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var p = server.getPlayerList().getPlayers().get(0);
				dev.rubymod.industry.Grid.account(p.getUUID()).charge = 9000;
			});
		});
		then(10, mc -> command(mc, "ride @p mount @e[type=rubymod:railgun,limit=1,sort=nearest]"));
		then(10, mc -> {
			for (var e : mc.level.entitiesForRendering()) {
				if (e instanceof dev.rubymod.entity.VehicleEntity v) {
					track = v;
				}
			}
		});
		then(25, mc -> shot(mc, "industry-railgun-sight"));
		then(2, mc -> ClientPlayNetworking.send(new Payloads.VehicleFire()));
		then(2, mc -> shot(mc, "industry-railgun-beam"));
		then(20, mc -> {
			log("tank after railgun: {}", track == null ? "none" : track.isAlive() ? "hp " + ((net.minecraft.world.entity.LivingEntity) track).getHealth() : "destroyed");
			track = null;
			command(mc, "ride @p dismount");
			command(mc, "gamemode creative @a");
		});

		// Tesla Coil vs a zombie, and an Elevator ride.
		then(10, mc -> {
			command(mc, String.format(java.util.Locale.ROOT, "setblock %d %d %d rubymod:tesla_coil", (int) Math.floor(origin.x) - 8,
					(int) Math.floor(origin.y), (int) Math.floor(origin.z) - 2));
			command(mc, String.format(java.util.Locale.ROOT, "summon husk %.1f %.1f %.1f {NoAI:1b}", origin.x - 12, origin.y, origin.z - 2));
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> dev.rubymod.industry.Grid.account(new java.util.UUID(0, 0)).charge = 9000);
			command(mc, String.format(java.util.Locale.ROOT, "setblock %d %d %d rubymod:elevator", (int) Math.floor(origin.x) + 8,
					(int) Math.floor(origin.y) - 1, (int) Math.floor(origin.z) - 6));
			command(mc, String.format(java.util.Locale.ROOT, "setblock %d %d %d rubymod:elevator", (int) Math.floor(origin.x) + 8,
					(int) Math.floor(origin.y) + 9, (int) Math.floor(origin.z) - 6));
			command(mc, String.format(java.util.Locale.ROOT, "tp @p %.1f %.1f %.1f facing %.1f %.1f %.1f", origin.x - 4, origin.y + 1, origin.z - 6,
					origin.x - 10, origin.y + 1, origin.z - 2));
		});
		then(1, mc -> mc.player.getAbilities().flying = false);
		then(12, mc -> shot(mc, "industry-tesla"));
		then(30, mc -> {
			boolean found = false;
			for (var e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.monster.zombie.Zombie z) {
					log("husk near the tesla coil: hp {} (20 max)", z.getHealth());
					found = true;
				}
			}
			if (!found) {
				log("husk near the tesla coil: gone (zapped to death)");
			}
			command(mc, String.format(java.util.Locale.ROOT, "tp @p %.1f %.1f %.1f", origin.x + 8.5, origin.y + 2, origin.z - 5.5));
		});
		then(10, mc -> {
			command(mc, "execute at @p run setblock ~ ~-1 ~ rubymod:elevator");
			command(mc, "execute at @p run setblock ~ ~9 ~ rubymod:elevator");
		});
		then(10, mc -> {
			start = mc.player.position();
			ClientPlayNetworking.send(new Payloads.Elevator(true));
		});
		then(10, mc -> log("elevator ride: {} -> {}", String.format("%.1f", start.y), String.format("%.1f", mc.player.getY())));
	}

	/** Screenshots of every aircraft model, parked and flying, and the helicopter flown. */
	private static void showcaseScript() {
		then(5, mc -> {
			origin = mc.player.position();
			mc.options.fov().set(30);
			command(mc, "gamemode creative @a");
			command(mc, "execute at @p run fill ~-40 ~ ~-10 ~40 ~20 ~60 minecraft:air");
			command(mc, "execute at @p run fill ~-40 ~-1 ~-10 ~40 ~-1 ~60 minecraft:gray_concrete");
		});
		String[] types = {"fighter_jet", "attack_jet", "bomber", "attack_heli"};
		for (String type : types) {
			then(10, mc -> {
				command(mc, "kill @e[type=rubymod:fighter_jet]");
				command(mc, "kill @e[type=rubymod:attack_jet]");
				command(mc, "kill @e[type=rubymod:bomber]");
				command(mc, "kill @e[type=rubymod:attack_heli]");
				command(mc, String.format(java.util.Locale.ROOT, "summon rubymod:%s %.1f %.1f %.1f {Rotation:[0f,0f]}", type, origin.x, origin.y, origin.z + 20));
			});
			double far = type.equals("bomber") ? 1.6 : 1.0;
			then(15, mc -> command(mc, String.format(java.util.Locale.ROOT, "tp @p %.1f %.1f %.1f facing %.1f %.1f %.1f",
					origin.x - 11 * far, origin.y + 4 * far, origin.z + 20 + 13 * far, origin.x, origin.y + 1.5, origin.z + 20)));
			then(20, mc -> shot(mc, "showcase-" + type + "-front"));
			then(2, mc -> command(mc, String.format(java.util.Locale.ROOT, "tp @p %.1f %.1f %.1f facing %.1f %.1f %.1f",
					origin.x + 10 * far, origin.y + 7 * far, origin.z + 20 - 10 * far, origin.x, origin.y + 1, origin.z + 20)));
			then(1, mc -> mc.player.getAbilities().flying = true);
			then(19, mc -> {
				shot(mc, "showcase-" + type + "-rear");
				for (var e : mc.level.entitiesForRendering()) {
					if (e instanceof dev.rubymod.entity.AircraftEntity a) {
						log("rear view: camera at {} rot {} {}, aircraft at {}", mc.player.position(), mc.player.getYRot(), mc.player.getXRot(), a.position());
					}
				}
			});
			then(2, mc -> command(mc, String.format(java.util.Locale.ROOT, "tp @p %.1f %.1f %.1f facing %.1f %.1f %.1f",
					origin.x + 18 * far, origin.y + 1.5, origin.z + 20, origin.x, origin.y + 1.5, origin.z + 20)));
			then(20, mc -> shot(mc, "showcase-" + type + "-side"));
		}
		then(1, mc -> mc.options.fov().set(70));
		then(5, mc -> command(mc, String.format(java.util.Locale.ROOT, "tp @p %.1f %.1f %.1f facing %.1f %.1f %.1f",
				origin.x - 8, origin.y, origin.z + 20, origin.x, origin.y + 1, origin.z + 20)));
		// Fly the helicopter: climb, then forward.
		then(2, mc -> command(mc, "ride @p mount @e[type=rubymod:attack_heli,limit=1,sort=nearest]"));
		then(10, mc -> {
			var a = aircraft(mc);
			if (a == null) {
				log("FAIL: could not board the helicopter");
				return;
			}
			start = a.position();
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			mc.options.keyUp.setDown(true);
			mc.player.setXRot(10);
		});
		then(50, mc -> {
			var a = aircraft(mc);
			if (a != null) {
				log("heli climbed {} blocks in 50 ticks", String.format("%.1f", a.getY() - start.y));
			}
			mc.options.keyUp.setDown(false);
			mc.player.setXRot(35);
		});
		then(40, mc -> {
			var a = aircraft(mc);
			if (a != null) {
				log("heli forward flight: speed {} km/h, altitude change {}", Math.round(a.speed() * 72), String.format("%.1f", a.getY() - start.y));
			}
			shot(mc, "showcase-heli-flying");
			mc.options.setCameraType(CameraType.FIRST_PERSON);
		});
		then(10, mc -> shot(mc, "showcase-heli-hud"));
		then(5, mc -> {
			mc.player.setXRot(10);
			ClientPlayNetworking.send(new Payloads.AircraftCycle());
		});
		then(5, mc -> Trigger.fireAircraft(dev.rubymod.entity.AircraftType.Weapon.ROCKETS));
		then(3, mc -> {
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			shot(mc, "showcase-heli-rockets");
		});
		then(20, mc -> {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			command(mc, "ride @p dismount");
		});
		// A fighter flying past at speed: afterburner and contrails.
		then(10, mc -> {
			command(mc, "kill @e[type=rubymod:attack_heli]");
			command(mc, "execute as @p at @s run tp @s ~ ~ ~ 90 5");
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var p = server.getPlayerList().getPlayers().get(0);
				var level = (net.minecraft.server.level.ServerLevel) p.level();
				level.addFreshEntity(dev.rubymod.entity.AircraftEntity.targetDrone(level, p.position().add(-30, 8, 0), 20));
			});
		});
		then(30, mc -> shot(mc, "showcase-drone-flyby"));
	}

	/** Entity the player keeps looking at (the pilot steers by looking). */
	private static net.minecraft.world.entity.Entity track;
	private static int liftoffTick;
	private static Vec3 runwayStart;

	private static dev.rubymod.entity.AircraftEntity aircraft(Minecraft mc) {
		return mc.player.getVehicle() instanceof dev.rubymod.entity.AircraftEntity a ? a : null;
	}

	private static void planesScript() {
		// A 9-wide, 320-long runway heading south (+Z) from the player, with the air above it cleared.
		then(5, mc -> {
			command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 0");
			for (int z = 0; z < 720; z += 40) {
				command(mc, "execute at @p run fill ~-7 ~ ~" + z + " ~7 ~14 ~" + (z + 39) + " minecraft:air");
				command(mc, "execute at @p run fill ~-7 ~-1 ~" + z + " ~7 ~-1 ~" + (z + 39) + " minecraft:smooth_stone");
				command(mc, "execute at @p run fill ~-4 ~-1 ~" + z + " ~4 ~-1 ~" + (z + 39) + " minecraft:gray_concrete");
			}
			command(mc, "execute at @p run summon rubymod:fighter_jet ~ ~ ~4 {Rotation:[0f,0f]}");
		});
		then(20, mc -> {
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			command(mc, "execute as @p at @s run tp @s ~-6 ~3 ~-4 -40 15");
		});
		then(10, mc -> shot(mc, "planes-fighter-parked"));
		then(2, mc -> command(mc, "ride @p mount @e[type=rubymod:fighter_jet,limit=1,sort=nearest]"));
		then(10, mc -> {
			var a = aircraft(mc);
			if (a == null) {
				log("FAIL: could not board the fighter");
				return;
			}
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			runwayStart = a.position();
			liftoffTick = -1;
			mc.player.setYRot(0);
			mc.player.setXRot(0);
			mc.options.keyUp.setDown(true);
		});
		then(15, mc -> shot(mc, "planes-hud-taxi"));
		// Takeoff roll: hold the nose level until rotate speed, then pull up.
		for (int i = 0; i < 40; i++) {
			int n = i;
			then(10, mc -> {
				var a = aircraft(mc);
				if (a == null) {
					return;
				}
				mc.player.setYRot(0);
				boolean rotate = a.speed() >= a.kind().rotateSpeed();
				mc.player.setXRot(rotate ? -14 : 0);
				if (liftoffTick < 0 && !a.onGround()) {
					liftoffTick = n;
					log("fighter lifted off after {} blocks of runway at {} km/h", String.format("%.0f", a.position().distanceTo(runwayStart)),
							Math.round(a.speed() * 72));
				}
				if (n % 4 == 3) {
					log("takeoff t={}: speed {} km/h, throttle {}, on ground {}, alt +{}", (n + 1) * 10, Math.round(a.speed() * 72),
							String.format("%.2f", a.throttle()), a.onGround(), String.format("%.1f", a.getY() - runwayStart.y));
				}
				if (n == 20) {
					shot(mc, "planes-hud-rotate");
				}
			});
		}
		then(5, mc -> {
			var a = aircraft(mc);
			if (a != null && liftoffTick < 0) {
				log("FAIL: fighter never left the runway (speed {} km/h)", Math.round(a.speed() * 72));
			}
		});
		then(20, mc -> {
			mc.player.setXRot(-5);
			shot(mc, "planes-hud-climb");
		});
		// Air-to-air: a target drone ahead, switch to missiles, lock, fire.
		then(5, mc -> {
			var a = aircraft(mc);
			if (a == null) {
				return;
			}
			IntegratedServer server = mc.getSingleplayerServer();
			Vec3 center = a.position().add(a.forward().multiply(1, 0, 1).normalize().scale(100)).add(0, 12, 0);
			server.execute(() -> {
				var level = (net.minecraft.server.level.ServerLevel) server.getPlayerList().getPlayers().get(0).level();
				level.addFreshEntity(dev.rubymod.entity.AircraftEntity.targetDrone(level, center, 25));
			});
			ClientPlayNetworking.send(new Payloads.AircraftCycle());
		});
		then(10, mc -> {
			for (var e : mc.level.entitiesForRendering()) {
				if (e instanceof dev.rubymod.entity.AircraftEntity d && d != aircraft(mc)) {
					track = d;
				}
			}
			var a = aircraft(mc);
			log("drone found: {}, selected weapon {}", track != null, a == null ? null : a.selectedWeapon());
		});
		then(25, mc -> {
			log("seeker: candidate {}, lock ticks {}, locked {}", Targeting.candidate == null ? "none" : Targeting.candidate.name(),
					Targeting.lockTicks, Targeting.locked(dev.rubymod.entity.AircraftType.Weapon.AAM));
			shot(mc, "planes-hud-lock");
			Trigger.fireAircraft(dev.rubymod.entity.AircraftType.Weapon.AAM);
		});
		then(6, mc -> shot(mc, "planes-missile-away"));
		then(40, mc -> {
			log("drone after missile: {}", track == null ? "none" : track.isAlive() ? "alive, hp " + ((net.minecraft.world.entity.LivingEntity) track).getHealth() : "destroyed");
			track = null;
			mc.options.keyUp.setDown(false);
			command(mc, "ride @p dismount");
			command(mc, "kill @e[type=rubymod:fighter_jet]");
		});

		// Attack jet: in the air over the runway, AGM lock on a parked tank.
		then(20, mc -> {
			command(mc, "execute at @p run summon rubymod:tank ~ ~ ~");
			command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 0");
		});
		then(5, mc -> {
			command(mc, "execute at @p run summon rubymod:attack_jet ~ ~45 ~-110 {Rotation:[0f,0f]}");
		});
		then(5, mc -> command(mc, "ride @p mount @e[type=rubymod:attack_jet,limit=1,sort=nearest]"));
		then(5, mc -> {
			var a = aircraft(mc);
			if (a == null) {
				log("FAIL: could not board the attack jet");
				return;
			}
			a.launch(2.6);
			mc.options.keyUp.setDown(true);
			ClientPlayNetworking.send(new Payloads.AircraftCycle());
			for (var e : mc.level.entitiesForRendering()) {
				if (e instanceof dev.rubymod.entity.VehicleEntity v) {
					track = v;
				}
			}
		});
		then(30, mc -> {
			var a = aircraft(mc);
			log("attack jet: weapon {}, seeker {}, locked {}, speed {} km/h", a == null ? null : a.selectedWeapon(),
					Targeting.candidate == null ? "none" : Targeting.candidate.name(),
					Targeting.locked(dev.rubymod.entity.AircraftType.Weapon.AGM), a == null ? 0 : Math.round(a.speed() * 72));
			shot(mc, "planes-hud-agm");
			Trigger.fireAircraft(dev.rubymod.entity.AircraftType.Weapon.AGM);
		});
		then(70, mc -> {
			log("tank after AGM: {}", track == null ? "none" : track.isAlive() ? "alive, hp " + ((net.minecraft.world.entity.LivingEntity) track).getHealth() : "destroyed");
			track = null;
			mc.options.keyUp.setDown(false);
			command(mc, "ride @p dismount");
			command(mc, "kill @e[type=rubymod:attack_jet]");
			command(mc, "kill @e[type=rubymod:tank]");
		});

		// Bomber: level flight, bombs selected, CCIP pipper.
		then(20, mc -> command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 0"));
		then(5, mc -> command(mc, "execute at @p run summon rubymod:bomber ~ ~40 ~-60 {Rotation:[0f,0f]}"));
		then(5, mc -> command(mc, "ride @p mount @e[type=rubymod:bomber,limit=1,sort=nearest]"));
		then(5, mc -> {
			var a = aircraft(mc);
			if (a == null) {
				log("FAIL: could not board the bomber");
				return;
			}
			a.launch(2.4);
			mc.options.keyUp.setDown(true);
			mc.player.setYRot(0);
			mc.player.setXRot(25);
		});
		then(30, mc -> {
			var a = aircraft(mc);
			log("bomber: weapon {}, CCIP {}, speed {} km/h", a == null ? null : a.selectedWeapon(), Targeting.bombImpact, a == null ? 0 : Math.round(a.speed() * 72));
			shot(mc, "planes-hud-ccip");
			Trigger.fireAircraft(dev.rubymod.entity.AircraftType.Weapon.BOMBS);
		});
		then(4, mc -> Trigger.fireAircraft(dev.rubymod.entity.AircraftType.Weapon.BOMBS));
		then(30, mc -> {
			mc.options.keyUp.setDown(false);
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		});
		then(10, mc -> shot(mc, "planes-bomber-third"));
		then(5, mc -> {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			command(mc, "ride @p dismount");
			command(mc, "kill @e[type=rubymod:bomber]");
		});

		// Handheld launchers: Skyhammer vs a drone, Lancer vs a tank.
		then(20, mc -> {
			command(mc, "clear @a");
			command(mc, "give @a rubymod:skyhammer");
			command(mc, "give @a rubymod:aa_missile 4");
			command(mc, "execute as @p at @s positioned over world_surface run tp @s ~ ~ ~ 0 -25");
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var p = server.getPlayerList().getPlayers().get(0);
				Vec3 center = Vec3.atBottomCenterOf(p.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
						p.blockPosition())).add(0, 35, 60);
				var level = (net.minecraft.server.level.ServerLevel) p.level();
				boolean added = level.addFreshEntity(dev.rubymod.entity.AircraftEntity.targetDrone(level, center, 30));
				log("skyhammer drone added {} at {} (player at {})", added, center, p.position());
			});
		});
		then(30, mc -> {
			mc.player.getInventory().setSelectedSlot(0);
			for (var e : mc.level.entitiesForRendering()) {
				if (e instanceof dev.rubymod.entity.AircraftEntity d && !d.isVehicle()) {
					track = d;
				}
			}
			aim(mc, true);
		});
		then(40, mc -> {
			log("skyhammer: candidate {}, lock ticks {}, locked {}", LauncherSeeker.candidate == null ? "none" : LauncherSeeker.candidate.name(),
					LauncherSeeker.lockTicks, LauncherSeeker.locked());
			shot(mc, "launcher-skyhammer-lock");
			ClientPlayNetworking.send(new Payloads.LauncherFire(LauncherSeeker.locked() ? LauncherSeeker.candidate.id() : -1));
		});
		then(8, mc -> shot(mc, "launcher-skyhammer-fired"));
		then(70, mc -> {
			aim(mc, false);
			log("drone after Skyhammer: {}", track == null ? "none" : track.isAlive() ? "alive, hp " + ((net.minecraft.world.entity.LivingEntity) track).getHealth() : "destroyed");
			track = null;
			command(mc, "kill @e[type=rubymod:fighter_jet]");
			command(mc, "clear @a");
			command(mc, "give @a rubymod:lancer");
			command(mc, "give @a rubymod:at_missile 4");
			command(mc, "execute as @p at @s positioned over world_surface run tp @s ~ ~ ~ 0 5");
			command(mc, "execute at @p positioned ~ ~ ~45 positioned over world_surface run summon rubymod:tank ~ ~ ~ {Rotation:[90f,0f]}");
		});
		then(20, mc -> {
			mc.player.getInventory().setSelectedSlot(0);
			for (var e : mc.level.entitiesForRendering()) {
				if (e instanceof dev.rubymod.entity.VehicleEntity v) {
					track = v;
				}
			}
			aim(mc, true);
		});
		then(40, mc -> {
			log("lancer: candidate {}, locked {}", LauncherSeeker.candidate == null ? "none" : LauncherSeeker.candidate.name(),
					LauncherSeeker.locked());
			shot(mc, "launcher-lancer-lock");
			ClientPlayNetworking.send(new Payloads.LauncherFire(LauncherSeeker.locked() ? LauncherSeeker.candidate.id() : -1));
		});
		then(25, mc -> {
			track = null; // stop following so the camera can watch the dive
			shot(mc, "launcher-lancer-climb");
		});
		for (int i = 0; i < 8; i++) {
			then(10, mc -> {
				for (var e : mc.level.entitiesForRendering()) {
					if (e instanceof dev.rubymod.entity.RocketEntity r) {
						log("lancer missile at {} vel {} target {}", r.position(), r.getDeltaMovement(), r.targetId());
					}
				}
			});
		}
		then(20, mc -> {
			aim(mc, false);
			for (var e : mc.level.entitiesForRendering()) {
				if (e instanceof dev.rubymod.entity.VehicleEntity v) {
					log("tank after Lancer: hp {}", v.getHealth());
				}
			}
			command(mc, "gamemode creative @a");
			command(mc, "kill @e[type=rubymod:tank]");
		});

		// Crash check: fly a fighter into a stone wall.
		then(20, mc -> {
			command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 0");
			command(mc, "execute at @p run fill ~-10 ~0 ~40 ~10 ~40 ~41 minecraft:stone");
			log("crash test wall at z {} y {}..{}", Math.round(mc.player.getZ() + 40), Math.round(mc.player.getY() + 20), Math.round(mc.player.getY() + 40));
			command(mc, "execute at @p run summon rubymod:fighter_jet ~ ~30 ~ {Rotation:[0f,0f]}");
		});
		then(5, mc -> command(mc, "ride @p mount @e[type=rubymod:fighter_jet,limit=1,sort=nearest]"));
		then(10, mc -> {
			var a = aircraft(mc);
			if (a == null) {
				log("FAIL: crash test could not board the fighter");
				return;
			}
			a.launch(3.0);
			mc.player.setYRot(0);
			mc.player.setXRot(0);
		});
		for (int i = 0; i < 6; i++) {
			then(8, mc -> {
				var a = aircraft(mc);
				if (a != null) {
					log("crash test flying: pos {} speed {} pitch {} collided {}", a.position(), String.format("%.2f", a.speed()), a.getXRot(), a.horizontalCollision);
				}
			});
		}
		then(12, mc -> {
			var a = aircraft(mc);
			log("crash test: {}", a == null ? "pilot thrown clear (aircraft destroyed)" : "still flying at z+" + Math.round(a.getZ()));
			command(mc, "ride @p dismount");
			command(mc, "kill @e[type=rubymod:fighter_jet]");
		});
	}

	private static net.minecraft.core.BlockPos house;

	private static void strikesScript() {
		// A hollow cobblestone house 26 blocks south of the player, watched from the north.
		then(5, mc -> {
			command(mc, "rubywars phase war");
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var player = server.getPlayerList().getPlayers().get(0);
				var level = player.level();
				house = player.blockPosition().offset(-4, 0, 22);
				for (var pos : net.minecraft.core.BlockPos.betweenClosed(house, house.offset(8, 6, 8))) {
					boolean shell = pos.getX() == house.getX() || pos.getX() == house.getX() + 8 || pos.getZ() == house.getZ()
							|| pos.getZ() == house.getZ() + 8 || pos.getY() == house.getY() + 6;
					level.setBlockAndUpdate(pos, shell ? net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState()
							: net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
				}
				log("house built at {}", house);
			});
			command(mc, "execute as @p at @s run tp @s ~ ~6 ~-6 0 12");
		});
		then(20, mc -> {
			shot(mc, "strike-before");
			countHouse(mc, "before strikes");
		});
		then(2, mc -> {
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var player = server.getPlayerList().getPlayers().get(0);
				var level = (net.minecraft.server.level.ServerLevel) player.level();
				Vec3 target = Vec3.atCenterOf(house.offset(4, 0, 4));
				level.addFreshEntity(dev.rubymod.entity.AircraftEntity.airStrike(level, player, target, 90.0F));
			});
		});
		for (int t = 30; t <= 90; t += 15) {
			int at = t;
			then(15, mc -> shot(mc, "strike-air-" + at));
		}
		then(40, mc -> countHouse(mc, "after air strike"));
		then(5, mc -> {
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				var player = server.getPlayerList().getPlayers().get(0);
				var level = (net.minecraft.server.level.ServerLevel) player.level();
				var mortarPos = house.offset(12, 0, -48);
				level.setBlockAndUpdate(mortarPos, dev.rubymod.registry.ModBlocks.MORTAR.defaultBlockState());
				dev.rubymod.item.RangefinderItem.mark(player, house.offset(4, 0, 4));
				dev.rubymod.game.HeavyWeapons.fireMortar(level, player, mortarPos, new net.minecraft.world.item.ItemStack(dev.rubymod.registry.ModItems.MORTAR_SHELL, 5));
			});
		});
		for (int t = 20; t <= 100; t += 20) {
			int at = t;
			then(20, mc -> shot(mc, "strike-mortar-" + at));
		}
		then(20, mc -> countHouse(mc, "after mortar"));
	}

	private static void countHouse(Minecraft mc, String label) {
		IntegratedServer server = mc.getSingleplayerServer();
		server.execute(() -> {
			var level = server.getPlayerList().getPlayers().get(0).level();
			int count = 0;
			for (var pos : net.minecraft.core.BlockPos.betweenClosed(house, house.offset(8, 6, 8))) {
				if (!level.getBlockState(pos).isAir()) {
					count++;
				}
			}
			log("house blocks {}: {}", label, count);
		});
	}

	// ----- Runner -----

	private static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null && !String.valueOf(System.getenv("RUBYMOD_AUTOTEST")).startsWith("join")) {
			// Still on a menu: report which one, so a stuck launch can be diagnosed.
			if (++waiting % 100 == 0) {
				String screen = mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getName();
				log("waiting on screen {}", screen);
				shot(mc, "waiting-" + waiting);
				if (waiting >= 1200) {
					log("gave up waiting");
					mc.stop();
				}
			}
			return;
		}
		tick++;
		if (track != null && track.isAlive()) {
			Vec3 aimAt = track.getBoundingBox().getCenter();
			if (mc.player.getVehicle() instanceof dev.rubymod.entity.HeavyAAGunEntity) {
				// Lead the target like the gun sight does.
				Vec3 vel = new Vec3(track.getX() - track.xo, track.getY() - track.yo, track.getZ() - track.zo);
				double t = aimAt.distanceTo(mc.player.getEyePosition()) / dev.rubymod.entity.HeavyAAGunEntity.SHELL_SPEED;
				aimAt = aimAt.add(vel.scale(t)).add(0, 0.5 * 0.03 * t * t, 0);
			}
			Vec3 to = aimAt.subtract(mc.player.getEyePosition());
			mc.player.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
			mc.player.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))));
		}
		while (cursor < STEPS.size() && STEPS.get(cursor).tick() <= tick) {
			try {
				STEPS.get(cursor).action().accept(mc);
			} catch (RuntimeException e) {
				RubyMod.LOGGER.error("[autotest] step at tick {} failed", STEPS.get(cursor).tick(), e);
			}
			cursor++;
		}
	}
}
