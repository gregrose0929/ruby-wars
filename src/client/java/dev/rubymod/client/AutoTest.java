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

	private static void log(String message, Object... args) {
		RubyMod.LOGGER.info("[autotest] " + message, args);
	}

	private static void aim(Minecraft mc, boolean on) {
		mc.options.keyUse.setDown(on);
		if (on) {
			mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
		}
	}

	// ----- Scripts -----

	private static void buildScript(String mode) {
		at = 40;
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
			case "vehicles" -> vehiclesScript();
			case "strikes" -> strikesScript();
			default -> {
				gunsScript();
				tourScript();
				vehiclesScript();
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
		then(15, mc -> command(mc, "ride @p dismount"));

		// Jet: spawn it high up, throttle up, climb, shoot, bomb.
		then(10, mc -> {
			command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 0");
			command(mc, "execute at @p run summon rubymod:jet ~ ~70 ~6 {Rotation:[0f,0f]}");
		});
		then(10, mc -> command(mc, "ride @p mount @e[type=rubymod:jet,limit=1,sort=nearest]"));
		then(10, mc -> {
			if (!(mc.player.getVehicle() instanceof dev.rubymod.entity.JetEntity)) {
				log("FAIL: could not board the jet");
				return;
			}
			start = mc.player.getVehicle().position();
			mc.options.keyUp.setDown(true);
			mc.player.setXRot(0);
		});
		then(70, mc -> {
			if (mc.player.getVehicle() instanceof dev.rubymod.entity.JetEntity jet) {
				log("jet after 70 ticks of throttle: speed {} throttle {} altitude change {}", String.format("%.2f", jet.speed()),
						String.format("%.2f", jet.throttle()), String.format("%.1f", jet.getY() - start.y));
				start = jet.position();
				mc.player.setXRot(-25);
			}
		});
		then(30, mc -> {
			if (mc.player.getVehicle() instanceof dev.rubymod.entity.JetEntity jet) {
				log("jet climbing: altitude change {} over 30 ticks, pitch {}", String.format("%.1f", jet.getY() - start.y), jet.getXRot());
				mc.player.setXRot(0);
			}
		});
		then(5, mc -> shot(mc, "jet-flying"));
		then(2, mc -> ClientPlayNetworking.send(new Payloads.VehicleFire()));
		then(4, mc -> shot(mc, "jet-missile"));
		then(2, mc -> ClientPlayNetworking.send(new Payloads.VehicleBomb()));
		then(20, mc -> {
			mc.options.keyUp.setDown(false);
			command(mc, "ride @p dismount");
			command(mc, "kill @e[type=rubymod:jet]");
		});
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
				level.addFreshEntity(dev.rubymod.entity.JetEntity.airStrike(level, player, target, 90.0F));
			});
			command(mc, "execute as @p at @s run tp @s ~ ~ ~-8 0 -10");
		});
		then(40, mc -> shot(mc, "airstrike-flyover"));
		then(25, mc -> shot(mc, "airstrike-impact"));
		then(2, mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
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
				level.addFreshEntity(dev.rubymod.entity.JetEntity.airStrike(level, player, target, 90.0F));
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
		if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
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
