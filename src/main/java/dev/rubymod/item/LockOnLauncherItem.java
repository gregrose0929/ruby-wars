package dev.rubymod.item;

import java.util.function.Consumer;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.RocketEntity;
import dev.rubymod.entity.VehicleEntity;
import dev.rubymod.game.Countermeasures;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Shoulder-fired lock-on missiles. Hold right-click to aim: the seeker locks
 * whatever stays in the circle (a tone builds up, then a steady tone when
 * locked). Left-click fires; the missile homes on the locked target by itself.
 *
 * Skyhammer (anti-air): locks aircraft in flight, fast, leads its target, can
 * be flared. Lancer (anti-tank): locks vehicles and anything on the ground,
 * flies a top-attack profile (climbs, then dives onto the thin roof armor).
 */
public class LockOnLauncherItem extends Item {
	public enum Kind {
		ANTI_AIR,
		ANTI_TANK
	}

	private static final int RELOAD = 60;
	private final Kind kind;

	public LockOnLauncherItem(Kind kind, Properties properties) {
		super(properties);
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}

	/** Can this launcher's seeker lock {@code e}? (Client seeker and server check both use this.) */
	public static boolean canLock(Kind kind, Entity e, Player shooter) {
		if (e == shooter || !e.isAlive() || e.isSpectator() || e.getVehicle() != null || e.hasPassenger(shooter)) {
			return false;
		}
		if (kind == Kind.ANTI_AIR) {
			return e instanceof AircraftEntity a && !a.onGround();
		}
		return e instanceof VehicleEntity || (e instanceof AircraftEntity a && a.onGround()) || (e instanceof LivingEntity l && l.onGround());
	}

	public static double range(Kind kind) {
		return kind == Kind.ANTI_AIR ? 400 : 250;
	}

	public Item ammo() {
		return kind == Kind.ANTI_AIR ? ModItems.AA_MISSILE : ModItems.AT_MISSILE;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		player.startUsingItem(hand);
		return InteractionResult.CONSUME;
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity user) {
		return 72000;
	}

	/** Left-click (LauncherFire packet) with the client's locked target. */
	public void fire(ServerPlayer player, ItemStack stack, Entity target) {
		if (player.getCooldowns().isOnCooldown(stack)) {
			return;
		}
		if (!player.isCreative() && player.getInventory().countItem(ammo()) == 0) {
			player.sendOverlayMessage(Component.literal("No " + Component.translatable(ammo().getDescriptionId()).getString() + "s!"));
			return;
		}
		ServerLevel level = (ServerLevel) player.level();
		Vec3 look = player.getLookAngle();
		if (target != null) {
			Vec3 to = target.getBoundingBox().getCenter().subtract(player.getEyePosition());
			double angle = Math.toDegrees(Math.acos(Mth.clamp(to.normalize().dot(look), -1, 1)));
			if (!canLock(kind, target, player) || to.length() > range(kind) + 20 || angle > 20 || Countermeasures.obscured(level, target)) {
				target = null;
			}
		}
		Vec3 from = player.getEyePosition().add(look.scale(1.2)).add(0, -0.15, 0);
		RocketEntity missile;
		if (kind == Kind.ANTI_AIR) {
			missile = RocketEntity.missile(level, player, player, from, look.scale(4.5), 3.0F, 9.0, target, null).withAntiArmor(100.0F);
		} else {
			// Lancer: soft launch upward, then the top-attack climb and dive.
			Vec3 launch = look.add(0, 0.35, 0).normalize().scale(2.0);
			missile = RocketEntity.missile(level, player, player, from, launch, 3.5F, 7.0, target, target == null ? aimPoint(player) : null)
					.withAntiArmor(64.0F).topAttack();
		}
		level.addFreshEntity(missile);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), dev.rubymod.registry.ModSounds.ROCKET_LAUNCH, SoundSource.PLAYERS, 6.0F, 1.0F);
		if (!player.isCreative()) {
			for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
				ItemStack s = player.getInventory().getItem(i);
				if (s.is(ammo())) {
					s.shrink(1);
					break;
				}
			}
		}
		player.getCooldowns().addCooldown(stack, RELOAD);
		if (target == null) {
			player.sendOverlayMessage(Component.literal("Fired without a lock"));
		}
	}

	private static Vec3 aimPoint(ServerPlayer player) {
		Vec3 eye = player.getEyePosition();
		var hit = player.level().clip(new net.minecraft.world.level.ClipContext(eye, eye.add(player.getLookAngle().scale(250)),
				net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
		return hit.getLocation();
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.literal("Hold right-click to lock on, left-click to fire").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.literal(kind == Kind.ANTI_AIR
				? "Locks aircraft in flight. Leads and intercepts; flares can fool it."
				: "Locks vehicles and targets on the ground. Top attack: dives onto the roof.").withStyle(ChatFormatting.DARK_GRAY));
		builder.accept(Component.literal("Ammo: " + Component.translatable(ammo().getDescriptionId()).getString()).withStyle(ChatFormatting.DARK_GRAY));
	}
}
