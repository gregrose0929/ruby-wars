package dev.rubymod.client;

import dev.rubymod.RubyMod;
import dev.rubymod.game.Shop;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/**
 * A scripted visual check for development. When the RUBYMOD_AUTOTEST
 * environment variable is set, the client joins the world it was launched
 * into (--quickPlaySingleplayer), lays out every block, cycles through the
 * guns, fires one, opens the Armory, takes screenshots of each step into
 * screenshots/autotest-*.png, and quits. Does nothing in normal play.
 */
public final class AutoTest {
	private static int tick = -1;

	private AutoTest() {
	}

	public static void register() {
		if (System.getenv("RUBYMOD_AUTOTEST") == null) {
			return;
		}
		RubyMod.LOGGER.info("[autotest] enabled");
		ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
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

	private static int waiting;
	private static net.minecraft.world.phys.Vec3 start;

	private static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
			// Still on a menu: report which one, so a stuck launch can be diagnosed.
			if (++waiting % 100 == 0) {
				String screen = mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getName();
				RubyMod.LOGGER.info("[autotest] waiting on screen {}", screen);
				shot(mc, "waiting-" + waiting);
				if (waiting >= 1200) {
					RubyMod.LOGGER.info("[autotest] gave up waiting");
					mc.stop();
				}
			}
			return;
		}
		tick++;
		switch (tick) {
			case 40 -> {
				command(mc, "time set noon");
				command(mc, "weather clear");
				command(mc, "gamemode creative @a");
				command(mc, "rubywars reset confirm");
				command(mc, "rubywars start");
				command(mc, "execute at @p run fill ~-2 ~-1 ~-2 ~20 ~-1 ~6 minecraft:smooth_stone");
				command(mc, "execute at @p run fill ~-2 ~ ~-2 ~20 ~6 ~6 minecraft:air");
				String[] blocks = {"ruby_block", "ruby_ore", "deepslate_ruby_ore", "ruby_heart", "armory", "sandbags", "concrete_wall",
						"reinforced_concrete", "sentry_turret", "missile_interceptor", "radar_station", "alarm_block", "mortar", "missile_silo",
						"landmine", "barbed_wire"};
				for (int i = 0; i < blocks.length; i++) {
					command(mc, "execute at @p run setblock ~" + (i + 1) + " ~ ~4 rubymod:" + blocks[i]);
				}
				command(mc, "execute at @p run setblock ~3 ~ ~2 rubymod:claymore[facing=south]");
				command(mc, "execute at @p run setblock ~5 ~ ~2 rubymod:blast_door[half=lower,facing=south]");
				command(mc, "execute at @p run setblock ~5 ~1 ~2 rubymod:blast_door[half=upper,facing=south]");
				command(mc, "clear @a");
				command(mc, "give @a rubymod:arr_rifle");
				command(mc, "give @a rubymod:longshot_sniper");
				command(mc, "give @a rubymod:combat_shotgun");
				command(mc, "give @a rubymod:ruby_blaster");
				command(mc, "give @a rubymod:rocket_launcher");
				command(mc, "give @a rubymod:frag_grenade 4");
				command(mc, "give @a rubymod:heavy_ammo 64");
				command(mc, "give @a rubymod:ruby 64");
				command(mc, "give @a rubymod:cruise_missile");
			}
			case 60 -> command(mc, "execute as @p at @s run tp @s ~ ~ ~ 0 20");
			case 100 -> {
				mc.player.getInventory().setSelectedSlot(0);
				shot(mc, "1-blocks-and-rifle");
			}
			case 110 -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
			case 112 -> shot(mc, "2-firing");
			case 130 -> {
				mc.player.getInventory().setSelectedSlot(1);
				mc.options.keyShift.setDown(true);
			}
			case 145 -> shot(mc, "3-sniper-scope");
			case 150 -> {
				mc.options.keyShift.setDown(false);
				mc.player.getInventory().setSelectedSlot(4);
			}
			case 160 -> shot(mc, "4-rocket-launcher");
			case 170 -> {
				mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
				mc.player.getInventory().setSelectedSlot(0);
			}
			case 185 -> shot(mc, "5-third-person");
			case 190 -> {
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				command(mc, "execute at @p run summon rubymod:frag_grenade ~2 ~1.5 ~3 {NoGravity:1b}");
				command(mc, "execute at @p run summon rubymod:cruise_missile ~-1 ~2 ~4 {NoGravity:1b}");
				command(mc, "execute at @p run summon rubymod:tank ~-4 ~ ~8 {Rotation:[30f,0f]}");
				command(mc, "execute at @p run summon rubymod:jeep ~5 ~ ~8 {Rotation:[-30f,0f]}");
				command(mc, "rubywars phase war");
			}
			case 200 -> shot(mc, "6-war-hud");
			case 210 -> {
				IntegratedServer server = mc.getSingleplayerServer();
				server.execute(() -> {
					ServerPlayer player = server.getPlayerList().getPlayers().get(0);
					Shop.open(player, 0);
				});
			}
			case 240 -> shot(mc, "7-armory");
			case 250 -> {
				mc.player.closeContainer();
				command(mc, "kill @e[type=rubymod:cruise_missile]");
				command(mc, "item replace entity @p armor.head with rubymod:ruby_helmet");
				command(mc, "item replace entity @p armor.chest with rubymod:ruby_chestplate");
				command(mc, "item replace entity @p armor.legs with rubymod:ruby_leggings");
				command(mc, "item replace entity @p armor.feet with rubymod:ruby_boots");
				command(mc, "execute as @p at @s run tp @s ~ ~ ~ 180 15");
				command(mc, "execute at @p run summon rubymod:tank ~-3 ~ ~-9 {Rotation:[150f,0f]}");
				command(mc, "execute at @p run summon rubymod:jeep ~4 ~ ~-7 {Rotation:[210f,0f]}");
			}
			case 270 -> shot(mc, "8-vehicles");
			case 275 -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
			case 290 -> shot(mc, "9-armor-third-person");
			case 300 -> {
				mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				command(mc, "ride @p mount @e[type=rubymod:tank,limit=1,sort=nearest]");
			}
			case 310 -> {
				if (mc.player.getVehicle() != null) {
					start = mc.player.getVehicle().position();
					RubyMod.LOGGER.info("[autotest] riding {}", mc.player.getVehicle().getType().getDescriptionId());
				} else {
					RubyMod.LOGGER.info("[autotest] FAIL: could not mount the tank");
				}
				mc.options.keyUp.setDown(true);
				mc.options.keyLeft.setDown(true);
			}
			case 350 -> {
				mc.options.keyUp.setDown(false);
				mc.options.keyLeft.setDown(false);
				if (mc.player.getVehicle() != null && start != null) {
					RubyMod.LOGGER.info("[autotest] tank drove {} blocks, now facing {}",
							String.format("%.1f", mc.player.getVehicle().position().distanceTo(start)), mc.player.getVehicle().getYRot());
				}
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.rubymod.net.Payloads.VehicleFire());
			}
			case 354 -> shot(mc, "10-tank-firing");
			case 370 -> command(mc, "ride @p dismount");
			case 380 -> {
				RubyMod.LOGGER.info("[autotest] done");
				mc.stop();
			}
			default -> {
			}
		}
	}
}
