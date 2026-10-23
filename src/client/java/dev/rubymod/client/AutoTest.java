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
