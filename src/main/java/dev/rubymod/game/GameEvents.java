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

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			GameConfig.load();
			SeasonData.load(server);
			dev.rubymod.industry.Grid.load(server);
			Explosions.clearHits();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			SeasonData.save();
			dev.rubymod.industry.Grid.saveIfDirty();
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
			if (server.getTickCount() % 20 == 0) {
				SeasonTicker.tickSecond(server);
				server.getAllLevels().forEach(dev.rubymod.gun.BlockDamage::heal);
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					var id = player.getUUID();
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
				if (SeasonData.get().phase == Phase.BUILD) {
					for (ServerPlayer player : server.getPlayerList().getPlayers()) {
						Research.onDayLogin(player, SeasonTicker.currentDay());
					}
				}
			}
			dev.rubymod.industry.Grid.endTick(server.overworld().getGameTime());
			if (server.getTickCount() % 1200 == 0) {
				dev.rubymod.industry.Grid.saveIfDirty();
				SeasonData.saveIfDirty();
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> BattleDirector.onJoin(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			CombatTag.onLeave(server, handler.getPlayer());
			BattleDirector.onLeave(server, handler.getPlayer());
		});

		ServerPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.Ping.TYPE, (payload, context) ->
				Waypoints.ping(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.LookQuery.TYPE, (payload, context) ->
				LookInfo.answer(context.player(), payload));
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
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> Rules.canHurt(source.getEntity(), entity));
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
			if (entity instanceof ServerPlayer victim && damageTaken > 0 && source.getEntity() instanceof ServerPlayer attacker) {
				CombatTag.tag(attacker, victim);
			}
			if (entity instanceof ServerPlayer player && damageTaken > 0 && source.getSourcePosition() != null) {
				Net.send(player, new Payloads.DamageFrom(source.getSourcePosition().x, source.getSourcePosition().z));
			}
		});
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer player) {
				dev.rubymod.block.GraveBlock.bury(player);
			}
			return true;
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) {
				BattleDirector.onDeath(player, source);
			}
		});
		ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, killer, victim, source) -> {
			if (killer instanceof ServerPlayer player) {
				Bounties.onKill(player, victim);
			}
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> BattleDirector.onRespawn(newPlayer));

		// --- Claim protection ---
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (level.isClientSide()) {
				return true;
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
			if (Rules.isEliminated(player.getUUID())) {
				return InteractionResult.FAIL;
			}
			ItemStack held = player.getItemInHand(hand);
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
		SeasonData.Claim claim = SeasonData.get().claimAt(Rules.dim(level), pos.getX(), pos.getZ());
		return claim == null ? "someone" : claim.ownerName;
	}

	private static void deny(Player player, String message) {
		if (player instanceof ServerPlayer serverPlayer) {
			serverPlayer.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED), true);
		}
	}
}
