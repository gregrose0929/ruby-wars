package dev.rubymod.game;

import dev.rubymod.entity.VehicleEntity;
import dev.rubymod.gun.Gunfire;
import dev.rubymod.item.BreachingChargeItem;
import dev.rubymod.item.ExcavatorItem;
import dev.rubymod.net.Payloads;
import dev.rubymod.registry.ModBlocks;

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
			Explosions.clearHits();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> SeasonData.save());

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				Gunfire.tickPlayer(player);
			}
			if (server.getTickCount() % 20 == 0) {
				SeasonTicker.tickSecond(server);
			}
			if (server.getTickCount() % 1200 == 0) {
				SeasonData.saveIfDirty();
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> BattleDirector.onJoin(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> BattleDirector.onLeave(server, handler.getPlayer()));

		ServerPlayNetworking.registerGlobalReceiver(Payloads.Reload.TYPE, (payload, context) -> Gunfire.startReload(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.VehicleFire.TYPE, (payload, context) -> {
			if (context.player().getVehicle() instanceof VehicleEntity vehicle) {
				vehicle.fireCannon(context.player());
			}
		});

		// --- Combat ---
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> Rules.canHurt(source.getEntity(), entity));
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) {
				BattleDirector.onDeath(player, source);
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
