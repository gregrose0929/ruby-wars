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
			command(mc, "gamerule send_command_feedback false");
			command(mc, "gamerule sendCommandFeedback false");
		});
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
			log("skyhammer: candidate {}, lock ticks {}, locked {}", LauncherSeeker.candidate == null ? "none" : LauncherSeeker.candidate.getName().getString(),
					LauncherSeeker.lockTicks, LauncherSeeker.locked());
			shot(mc, "launcher-skyhammer-lock");
			ClientPlayNetworking.send(new Payloads.LauncherFire(LauncherSeeker.locked() ? LauncherSeeker.candidate.getId() : -1));
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
			log("lancer: candidate {}, locked {}", LauncherSeeker.candidate == null ? "none" : LauncherSeeker.candidate.getName().getString(),
					LauncherSeeker.locked());
			shot(mc, "launcher-lancer-lock");
			ClientPlayNetworking.send(new Payloads.LauncherFire(LauncherSeeker.locked() ? LauncherSeeker.candidate.getId() : -1));
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
