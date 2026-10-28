package dev.rubymod.game;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.VehicleEntity;
import dev.rubymod.gun.GunItem;
import dev.rubymod.gun.Gunfire;
import dev.rubymod.gun.LagCompensation;
import dev.rubymod.item.BreachingChargeItem;
import dev.rubymod.item.ExcavatorItem;
import dev.rubymod.item.RocketLauncherItem;
import dev.rubymod.net.Net;
import dev.rubymod.net.Payloads;
import dev.rubymod.registry.ModBlocks;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Hooks the game rules into Minecraft's events. */
public final class GameEvents {
	private GameEvents() {
	}

	/**
	 * Tell the shooter they hit (or killed) a player or creature with any weapon: the X marker and
	 * "HIT - Zombie 40%" / "KILLED - Zombie" under the crosshair. Vehicles and aircraft get theirs
	 * from {@link Armor#hit}; percentLeft -1 means killed.
	 */
	private static void confirmHit(net.minecraft.world.entity.LivingEntity victim, net.minecraft.world.damagesource.DamageSource source, boolean killed) {
		if (!(source.getEntity() instanceof ServerPlayer shooter) || shooter == victim || Armor.isArmored(victim) || victim.hasPassenger(shooter)) {
			return;
		}
		int left = killed ? -1 : Math.max(1, Math.round(victim.getHealth() / victim.getMaxHealth() * 100));
		Net.send(shooter, new Payloads.HitConfirm(victim.getName().getString(), left, killed));
		Net.send(shooter, new Payloads.HitMarker(false, killed));
	}

	public static void register() {
		// No hostile mobs (config: hostileMobs); raid soldiers, guards and animals are unaffected.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (Cosmetics.strayHat(entity) || dev.rubymod.game.party.FreeRoam.stale(entity, world)) {
				entity.discard();
				return;
			}
			// No baby slimes in the Rift: a slime that dies there stays dead.
			if (entity instanceof net.minecraft.world.entity.monster.cubemob.AbstractCubeMob && !entity.entityTags().contains(dev.rubymod.game.rift.Rift.TAG)
					&& dev.rubymod.game.rift.Rift.protects(world, entity.blockPosition())) {
				entity.discard();
				return;
			}
			// Nothing drops in the Rift (no loot, no experience).
			if ((entity instanceof net.minecraft.world.entity.item.ItemEntity || entity instanceof net.minecraft.world.entity.ExperienceOrb)
					&& dev.rubymod.game.rift.Rift.protects(world, entity.blockPosition())) {
				entity.discard();
				return;
			}
			if (!GameConfig.get().hostileMobs && entity instanceof net.minecraft.world.entity.monster.Enemy && !entity.entityTags().contains("rubymod_horde") && !entity.entityTags().contains(dev.rubymod.game.rift.Rift.TAG)
					&& entity.getType().getCategory() == net.minecraft.world.entity.MobCategory.MONSTER) {
				entity.discard();
			}
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			// Always daytime, in every world.
			var quiet = server.createCommandSourceStack().withSuppressedOutput();
			server.getCommands().performPrefixedCommand(quiet, "gamerule advance_time false");
			server.getCommands().performPrefixedCommand(quiet, "time set day");
			// Nobody sees where other players are (the locator bar), unless a drone or ping spots them.
			server.getCommands().performPrefixedCommand(quiet, "gamerule locator_bar false");
			GameConfig.load();
			SeasonData.load(server);
			dev.rubymod.industry.Grid.load(server);
			Explosions.clearHits();
			Duels.reset();
			Horde.reset();
			dev.rubymod.game.rift.Rift.reset();
			Horde.load(server);
			Arenas.load(server);
			dev.rubymod.game.party.PartyGames.reset(server);
			dev.rubymod.game.party.PartyGames.prebuild(server);
			Horde.prebuild(server);
			Stats.load(server);
			Cosmetics.reset();
			Hub.reset();
			if (server.getLevel(Hub.HUB) != null) {
				Hub.ensureBuilt(server.getLevel(Hub.HUB));
			}
			// The Ruby Wars map is an island: keep everyone on it, even before the season starts.
			if (BattleDirector.onWarzoneMap(server) && server.overworld().getWorldBorder().getSize() > dev.rubymod.world.WarzoneTerrain.BORDER) {
				server.overworld().getWorldBorder().setCenter(0, 0);
				server.overworld().getWorldBorder().setSize(dev.rubymod.world.WarzoneTerrain.BORDER);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			SeasonData.save();
			dev.rubymod.industry.Grid.saveIfDirty();
			Stats.save();
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				Gunfire.tickPlayer(player);
				SpawnShield.tick(player);
			}
			LagCompensation.record(server);
			CannonRounds.tick();
			Explosions.tick(server);
			Countermeasures.tick();
			Range.tick(server);
			Duels.tick(server);
			try {
				Horde.tick(server);
			} catch (RuntimeException e) {
				Horde.hiccup(server, e);
			}
			dev.rubymod.game.party.PartyGames.tick(server);
			try {
				dev.rubymod.game.rift.Rift.tick(server);
			} catch (RuntimeException e) {
				riftErrors++;
				if (riftErrors % 200 == 1) {
					dev.rubymod.RubyMod.LOGGER.error("Ruby Rift hit an error (skipping the tick)", e);
				}
			}
			try {
				Hub.tick(server);
				Cosmetics.tick(server);
			} catch (RuntimeException e) {
				dev.rubymod.RubyMod.LOGGER.error("Hub or cosmetics hit an error", e);
			}
			Jetpacks.tick(server);
			VersionCheck.tick(server);
			if (server.getTickCount() % 20 == 0) {
				SeasonTicker.tickSecond(server);
				server.getAllLevels().forEach(dev.rubymod.gun.BlockDamage::heal);
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					safeZoneNotice(player);
					var id = Horde.playing(player) ? Horde.TEAM : dev.rubymod.game.rift.Rift.playing(player) ? new java.util.UUID(0, 1) : player.getUUID();
					Net.send(player, new Payloads.Economy(dev.rubymod.industry.Grid.rubies(id), dev.rubymod.industry.Grid.charge(id),
							dev.rubymod.industry.Grid.capacity(id), dev.rubymod.industry.Grid.producedPerSecond(id),
							dev.rubymod.industry.Grid.consumedPerSecond(id)));
				}
				TrainingGround.tick();
				Waypoints.sync(server);
				Raids.tick(server);
				if (server.getTickCount() % 100 == 0) {
					for (ServerPlayer player : server.getPlayerList().getPlayers()) {
						Bounties.sync(player);
					}
				}
				Goals.tickSecond(server);
				Hub.tickSecond(server);
				if (!GameConfig.get().hunger) {
					for (ServerPlayer player : server.getPlayerList().getPlayers()) {
						player.getFoodData().setFoodLevel(20);
					}
				}
			}
			dev.rubymod.industry.Grid.endTick(server.overworld().getGameTime());
			if (server.getTickCount() % 1200 == 0) {
				dev.rubymod.industry.Grid.saveIfDirty();
				SeasonData.saveIfDirty();
				Stats.saveIfDirty();
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			BattleDirector.onJoin(handler.getPlayer());
			VersionCheck.onJoin(handler.getPlayer());
			// Moving a player to another dimension while they're still being added to the world
			// breaks them, so duel restores and hub routing wait a few ticks.
			Hub.queueJoin(handler.getPlayer());
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			Duels.onLeave(server, handler.getPlayer());
			Horde.onLeave(server, handler.getPlayer());
			dev.rubymod.game.rift.Rift.onDisconnect(handler.getPlayer());
			dev.rubymod.game.party.PartyGames.onDisconnect(handler.getPlayer());
			VersionCheck.onLeave(handler.getPlayer());
			Jetpacks.forget(handler.getPlayer().getUUID());
			CombatTag.onLeave(server, handler.getPlayer());
			BattleDirector.onLeave(server, handler.getPlayer());
		});

		ServerPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.JetpackThrust.TYPE, (payload, context) ->
				Jetpacks.setThrusting(context.player(), payload.on()));
		ServerPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.Hello.TYPE, (payload, context) ->
				VersionCheck.onHello(context.player(), payload.build()));
		ServerPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.Ping.TYPE, (payload, context) ->
				Waypoints.ping(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.LookQuery.TYPE, (payload, context) ->
				LookInfo.answer(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.ModeSelect.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (payload.mode().startsWith("bots:")) {
				dev.rubymod.game.party.PartyGames.toggleBots(player, payload.mode().substring(5));
			} else if (!payload.mode().isEmpty()) {
				String error = Hub.go(player, payload.mode());
				if (error != null) {
					player.sendSystemMessage(Component.literal(error).withStyle(ChatFormatting.RED));
				}
			}
			Net.send(player, new dev.rubymod.net.MorePayloads.ModeInfo(Hub.whereLabel(player), Hub.modeLines(player.level().getServer())));
		});
		ServerPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.ProfileRequest.TYPE, (payload, context) -> {
			Cosmetics.checkUnlocks(context.player());
			Net.send(context.player(), new dev.rubymod.net.MorePayloads.ProfileSync(Stats.profileLines(context.player()), Cosmetics.lines(context.player())));
		});
		ServerPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.CosmeticSelect.TYPE, (payload, context) -> {
			String error = Cosmetics.select(context.player(), payload.id());
			if (error != null) {
				context.player().sendOverlayMessage(Component.literal(error).withStyle(ChatFormatting.RED));
			}
			Net.send(context.player(), new dev.rubymod.net.MorePayloads.ProfileSync(Stats.profileLines(context.player()), Cosmetics.lines(context.player())));
		});
		ServerPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.RiftChoice.TYPE, (payload, context) ->
				dev.rubymod.game.rift.RiftChoices.handle(context.player(), payload.kind(), payload.id()));
		ServerPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.HordeBuy.TYPE, (payload, context) -> {
			String error = HordeShop.buy(context.player(), payload.id());
			if (error != null) {
				context.player().sendOverlayMessage(Component.literal(error).withStyle(ChatFormatting.RED));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.ResearchUnlock.TYPE, (payload, context) -> {
			String error = Research.unlock(context.player(), payload.node());
			if (error != null) {
				context.player().sendOverlayMessage(Component.literal(error).withStyle(ChatFormatting.RED));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Reload.TYPE, (payload, context) -> {
			if (context.player().getMainHandItem().getItem() instanceof dev.rubymod.item.ArchitectWandItem) {
				dev.rubymod.item.ArchitectWandItem.undo(context.player());
			} else {
				Gunfire.startReload(context.player());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Fire.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			ItemStack held = player.getMainHandItem();
			if (held.getItem() instanceof GunItem gun) {
				Gunfire.tryFire((ServerLevel) player.level(), player, held, gun);
			} else if (held.getItem() instanceof RocketLauncherItem) {
				RocketLauncherItem.fire(player, held);
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Payloads.VehicleFire.TYPE, (payload, context) -> {
			if (context.player().getVehicle() instanceof VehicleEntity vehicle) {
				vehicle.fireCannon(context.player());
			} else if (context.player().getVehicle() instanceof dev.rubymod.entity.HeavyAAGunEntity gun) {
				gun.fire(context.player());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Payloads.AircraftFire.TYPE, (payload, context) -> {
			if (context.player().getVehicle() instanceof AircraftEntity aircraft) {
				var target = payload.targetId() >= 0 ? context.player().level().getEntity(payload.targetId()) : null;
				var point = payload.hasPoint() ? new net.minecraft.world.phys.Vec3(payload.x(), payload.y(), payload.z()) : null;
				aircraft.fire(context.player(), target, point);
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Payloads.AircraftCycle.TYPE, (payload, context) -> {
			if (context.player().getVehicle() instanceof AircraftEntity aircraft) {
				aircraft.cycleWeapon(context.player());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Payloads.AircraftDesignate.TYPE, (payload, context) -> {
			if (context.player().getVehicle() instanceof AircraftEntity aircraft) {
				var target = payload.entityId() >= 0 ? context.player().level().getEntity(payload.entityId()) : null;
				var point = payload.hasPoint() ? new net.minecraft.world.phys.Vec3(payload.x(), payload.y(), payload.z()) : null;
				aircraft.designate(context.player(), target, point);
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Payloads.LauncherFire.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			ItemStack held = player.getMainHandItem();
			if (held.getItem() instanceof dev.rubymod.item.LockOnLauncherItem launcher && player.getVehicle() == null) {
				launcher.fire(player, held, payload.targetId() >= 0 ? player.level().getEntity(payload.targetId()) : null);
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Elevator.TYPE, (payload, context) -> Elevators.ride(context.player(), payload.up()));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.VehicleSmoke.TYPE, (payload, context) -> {
			if (context.player().getVehicle() instanceof VehicleEntity vehicle) {
				vehicle.popSmoke(context.player());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.Autopilot.TYPE, (payload, context) -> {
			if (context.player().getVehicle() instanceof dev.rubymod.entity.AircraftEntity aircraft) {
				aircraft.setAutopilot(context.player(), payload.on(), payload.heading(), payload.altitude());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Payloads.AircraftGear.TYPE, (payload, context) -> {
			if (context.player().getVehicle() instanceof AircraftEntity aircraft) {
				aircraft.toggleGear(context.player());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Payloads.AircraftCrash.TYPE, (payload, context) -> {
			if (context.player().getVehicle() instanceof AircraftEntity aircraft) {
				aircraft.reportCrash(context.player(), Math.min(payload.severity(), 5.0F));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Payloads.AircraftFlares.TYPE, (payload, context) -> {
			if (context.player().getVehicle() instanceof AircraftEntity aircraft) {
				aircraft.flares(context.player());
			}
		});

		// --- Combat ---
		// Nothing gets hurt in the hub.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> entity.level().dimension() != Hub.HUB
				&& Rules.canHurt(source.getEntity(), entity) && !Armor.crewProtected(entity, source)
				&& (!(entity instanceof ServerPlayer runner) || dev.rubymod.game.rift.RelicEffects.allowDamage(runner, source, amount))
				&& !dev.rubymod.game.rift.Rift.shielded(entity)
				&& !(source.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL) && entity instanceof ServerPlayer p
						&& dev.rubymod.game.party.PartyGames.of(p) != null));
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
			if (entity instanceof ServerPlayer victim && damageTaken > 0 && source.getEntity() instanceof ServerPlayer attacker) {
				CombatTag.tag(attacker, victim);
			}
			if (entity instanceof ServerPlayer player && damageTaken > 0 && source.getSourcePosition() != null) {
				Net.send(player, new Payloads.DamageFrom(source.getSourcePosition().x, source.getSourcePosition().z));
			}
			if (damageTaken > 0) {
				try {
					if (source.getEntity() instanceof ServerPlayer attacker && attacker != entity && dev.rubymod.game.rift.Rift.playing(attacker)) {
						int color = dev.rubymod.game.rift.RelicEffects.effectColor >= 0 ? dev.rubymod.game.rift.RelicEffects.effectColor
								: source.is(dev.rubymod.registry.ModDamage.HEADSHOT) ? 0xFFFFD040 : 0xFFFFFFFF;
						dev.rubymod.game.rift.RiftFx.damageNumber(attacker, entity, damageTaken, color);
						dev.rubymod.game.rift.RelicEffects.onHit(attacker, entity, damageTaken);
					}
					if (entity instanceof ServerPlayer victim && dev.rubymod.game.rift.Rift.playing(victim)) {
						dev.rubymod.game.rift.RelicEffects.afterDamage(victim);
					}
				} catch (RuntimeException e) {
					dev.rubymod.RubyMod.LOGGER.error("Ruby Rift hit effects failed", e);
				}
				confirmHit(entity, source, false);
				Duels.onHit(entity, source, damageTaken);
			}
		});
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer player) {
				// Duel and Horde deaths respawn you in the arena instead (no grave, no stats).
				if (source.getEntity() instanceof ServerPlayer killer && killer != player) {
					Cosmetics.onKill(killer, player.position());
				}
				boolean hordeDeath;
				try {
					hordeDeath = Horde.onDeath(player, source);
				} catch (RuntimeException e) {
					Horde.hiccup(player.level().getServer(), e);
					hordeDeath = Horde.playing(player);
				}
				if (hordeDeath || dev.rubymod.game.rift.Rift.onDeath(player, source) || dev.rubymod.game.party.PartyGames.onDeath(player, source)
						|| Duels.onDeath(player, source)) {
					return false;
				}
				dev.rubymod.block.GraveBlock.bury(player);
			}
			return true;
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) {
				BattleDirector.onDeath(player, source);
				BattleDirector.sendDeathInfo(player, source);
			} else {
				Duels.onEntityDeath(entity, source);
				Horde.onEntityDeath(entity, source);
				try {
					if (source.getEntity() instanceof ServerPlayer killer && dev.rubymod.game.rift.Rift.playing(killer) && dev.rubymod.game.rift.Rift.isEnemy(entity)) {
						dev.rubymod.game.rift.RelicEffects.onKill(killer, entity);
					}
					dev.rubymod.game.rift.Rift.onMobDeath(entity, source);
					dev.rubymod.game.rift.RelicCombos.onDeath(entity);
				} catch (RuntimeException e) {
					dev.rubymod.RubyMod.LOGGER.error("Ruby Rift kill effects failed", e);
				}
				if (source.getEntity() instanceof ServerPlayer killer && entity instanceof net.minecraft.world.entity.Mob) {
					Cosmetics.onKill(killer, entity.position());
				}
				dev.rubymod.game.party.PartyGames.onEntityDeath(entity, source);
			}
			confirmHit(entity, source, true);
		});
		ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, killer, victim, source) -> {
			if (killer instanceof ServerPlayer player) {
				Bounties.onKill(player, victim);
			}
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (!Duels.onRespawn(newPlayer)) {
				BattleDirector.onRespawn(newPlayer);
			}
		});

		// --- Claim protection ---
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (level.isClientSide()) {
				return true;
			}
			if (player instanceof ServerPlayer sp && !dev.rubymod.game.party.PartyGames.allowsBreak(sp, pos)) {
				return false;
			}
			if (level instanceof ServerLevel serverLevel && Horde.protectedBlock(serverLevel, pos)) {
				deny(player, "That's part of the battlefield.");
				return false;
			}
			if (state.is(ModBlocks.RUBY_HEART)) {
				return canBreakHeart((ServerPlayer) player, level, pos);
			}
			if (!Rules.canBuild(player, level, pos)) {
				deny(player, "This land is claimed by " + ownerName(level, pos) + ".");
				return false;
			}
			return true;
		});
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
				Bounties.onBlockBroken(serverPlayer, state);
				if (state.is(dev.rubymod.registry.ModBlocks.RUBY_ORE) || state.is(dev.rubymod.registry.ModBlocks.DEEPSLATE_RUBY_ORE)) {
					Goals.add(serverPlayer.level().getServer(), serverPlayer.getUUID(), "ruby10", 1);
					Goals.add(serverPlayer.level().getServer(), serverPlayer.getUUID(), "ruby50", 1);
				}
				ExcavatorItem.afterBreak(serverLevel, serverPlayer, pos, state);
			}
		});

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() || player.isSpectator()) {
				return InteractionResult.PASS;
			}
			BlockPos pos = hit.getBlockPos();
			BlockState state = level.getBlockState(pos);
			ItemStack held = player.getItemInHand(hand);
			if (player instanceof ServerPlayer sp && dev.rubymod.game.party.PartyGames.of(sp) != null) {
				BlockPos placeAt = state.canBeReplaced() ? pos : pos.relative(hit.getDirection());
				if (dev.rubymod.game.party.PartyGames.onUseBlock(sp, pos, held, placeAt)) {
					return InteractionResult.FAIL;
				}
				if (!dev.rubymod.game.party.PartyGames.allowsItemUse(sp, held)) {
					return InteractionResult.FAIL;
				}
			}
			boolean planting = held.getItem() instanceof BreachingChargeItem;
			if (!planting && !Rules.canInteract(player, level, pos, state) && !(player.isShiftKeyDown() && held.getItem() instanceof BlockItem)) {
				deny(player, "That belongs to " + ownerName(level, pos) + ".");
				return InteractionResult.FAIL;
			}
			if (held.getItem() instanceof BlockItem || held.getItem() instanceof BucketItem) {
				BlockPos placeAt = state.canBeReplaced() ? pos : pos.relative(hit.getDirection());
				if (!Rules.canBuild(player, level, placeAt)) {
					deny(player, "This land is claimed by " + ownerName(level, placeAt) + ".");
					return InteractionResult.FAIL;
				}
			}
			return InteractionResult.PASS;
		});

		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide()) {
				return InteractionResult.PASS;
			}
			if (Rules.isEliminated(player.getUUID()) && Hub.inRubyWarsWorld(player)) {
				return InteractionResult.FAIL;
			}
			ItemStack held = player.getItemInHand(hand);
			if (player instanceof ServerPlayer sp && !dev.rubymod.game.party.PartyGames.allowsItemUse(sp, held)) {
				return InteractionResult.FAIL;
			}
			if (held.getItem() instanceof BucketItem) {
				Vec3 eye = player.getEyePosition();
				BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(player.getLookAngle().scale(6)), ClipContext.Block.OUTLINE,
						ClipContext.Fluid.SOURCE_ONLY, player));
				if (hit.getType() == HitResult.Type.BLOCK && !Rules.canBuild(player, level, hit.getBlockPos().relative(hit.getDirection()))) {
					deny(player, "You can't use buckets in someone else's claim.");
					return InteractionResult.FAIL;
				}
			}
			return InteractionResult.PASS;
		});
	}

	private static boolean canBreakHeart(ServerPlayer player, Level level, BlockPos pos) {
		SeasonData.Claim claim = SeasonData.get().heartAt(Rules.dim(level), pos);
		if (claim == null) {
			return true; // A stray Heart block with no claim.
		}
		boolean owner = claim.owner.equals(player.getUUID().toString());
		if (Rules.phase().isBattle()) {
			deny(player, owner ? "You can't move your Heart on Battle Day." : "Hearts can only be destroyed with a Breaching Charge.");
			return false;
		}
		if (!owner && !Rules.isAdminBypass(player)) {
			deny(player, "That's " + claim.ownerName + "'s Ruby Heart.");
			return false;
		}
		if (owner && Rules.phase() == Phase.BUILD && SeasonData.get().player(player.getUUID()).lastHeartMoveDay == SeasonTicker.currentDay()) {
			deny(player, "You can only move your Heart once per day.");
			return false;
		}
		ServerPlayer ownerPlayer = level.getServer().getPlayerList().getPlayer(java.util.UUID.fromString(claim.owner));
		if (ownerPlayer != null) {
			Claims.onHeartRemoved(ownerPlayer, claim);
		} else {
			SeasonData.get().claims.remove(claim.owner);
			SeasonData.markDirty();
		}
		return true;
	}

	private static String ownerName(Level level, BlockPos pos) {
		SeasonData.Claim claim = SeasonData.get().claimAt(Rules.dim(level), pos);
		return claim == null ? "someone" : claim.ownerName;
	}

	private static int riftErrors;

	/** Who was standing in a base last second (build phase: safe inside, fair game outside). */
	private static final java.util.Map<java.util.UUID, Boolean> IN_BASE = new java.util.HashMap<>();

	private static void safeZoneNotice(ServerPlayer player) {
		Phase phase = Rules.phase();
		if ((phase != Phase.BUILD && phase != Phase.LOBBY) || !Hub.inRubyWarsWorld(player) || player.isSpectator()) {
			IN_BASE.remove(player.getUUID());
			return;
		}
		boolean in = Rules.inBase(player);
		Boolean was = IN_BASE.put(player.getUUID(), in);
		if (was != null && was != in) {
			player.sendOverlayMessage(in ? Component.literal("\u2714 In a base: nobody can hurt you here").withStyle(ChatFormatting.GREEN)
					: Component.literal("\u2694 Outside the bases: players can fight you here!").withStyle(ChatFormatting.GOLD));
		}
	}

	private static void deny(Player player, String message) {
		if (player instanceof ServerPlayer serverPlayer) {
			serverPlayer.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED), true);
		}
	}
}
