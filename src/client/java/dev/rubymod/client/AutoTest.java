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
			command(mc, "execute at @p run fill ~-6 ~-1 ~-6 ~20 ~-1 ~20 minecraft:smooth_stone");
			command(mc, "execute at @p run fill ~-6 ~ ~-6 ~20 ~10 ~20 minecraft:air");
			command(mc, "clear @a");
		});
		then(15, mc -> command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 10"));
		switch (mode) {
			case "guns" -> gunsScript();
			case "vehicles" -> vehiclesScript();
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
			command(mc, "execute at @p run summon rubymod:tank ~-4 ~ ~-9 {Rotation:[150f,0f]}");
			command(mc, "execute at @p run summon rubymod:jeep ~4 ~ ~-8 {Rotation:[210f,0f]}");
		});
		then(20, mc -> shot(mc, "vehicles-front"));
		then(2, mc -> command(mc, "execute as @p at @s run tp @s ~-8 ~3 ~-14 -60 25"));
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
		then(2, mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
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
