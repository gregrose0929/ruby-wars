package dev.rubymod.item;

import dev.rubymod.game.Fire;
import dev.rubymod.game.Rules;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Flamethrower (Armory): hold right-click to spray fire about {@link #REACH} blocks. Whatever it touches catches fire;
 * blocks only burn where a weapon could break them (inside a claim, that's Battle Day). Its fuel is the bar under it:
 * twenty seconds of flame, and it refills by itself while it's not firing (empty to full in about a minute).
 */
public class FlamethrowerItem extends Item {
	public static final double REACH = 7;

	public FlamethrowerItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (stack.getDamageValue() >= stack.getMaxDamage() - 1) {
			if (player instanceof ServerPlayer sp) {
				sp.sendOverlayMessage(Component.literal("Out of fuel: it refills by itself in a little while.").withStyle(ChatFormatting.GRAY));
			}
			return InteractionResult.FAIL;
		}
		player.startUsingItem(hand);
		return InteractionResult.CONSUME;
	}

	/** When each player last fired one (the tank only refills once they've stopped for a couple of seconds). */
	private static final java.util.Map<java.util.UUID, Long> LAST_FLAME = new java.util.HashMap<>();

	/** It refills by itself while it's not in use: empty to full in about a minute. */
	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, net.minecraft.world.entity.Entity owner, net.minecraft.world.entity.EquipmentSlot slot) {
		if (level.getGameTime() % 3 == 0) {
			refill(stack, level, owner);
		}
	}

	/** One step of refilling (if it's not being fired, and hasn't been for a couple of seconds). Whether it went up. */
	public static boolean refill(ItemStack stack, ServerLevel level, net.minecraft.world.entity.Entity owner) {
		if (stack.getDamageValue() <= 0) {
			return false;
		}
		if (owner instanceof LivingEntity user && user.isUsingItem() && user.getUseItem() == stack) {
			return false;
		}
		if (level.getGameTime() - LAST_FLAME.getOrDefault(owner.getUUID(), -1000L) < 40) {
			return false;
		}
		stack.setDamageValue(stack.getDamageValue() - 1);
		return true;
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity user) {
		return 72000;
	}

	@Override
	public ItemUseAnimation getUseAnimation(ItemStack stack) {
		return ItemUseAnimation.NONE;
	}

	@Override
	public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remaining) {
		if (!(level instanceof ServerLevel server) || !(user instanceof ServerPlayer p)) {
			return;
		}
		if (stack.getDamageValue() >= stack.getMaxDamage() - 1) {
			p.stopUsingItem();
			p.sendOverlayMessage(Component.literal("Out of fuel: it refills by itself in a little while.").withStyle(ChatFormatting.GRAY));
			return;
		}
		spray(server, p);
		LAST_FLAME.put(p.getUUID(), server.getGameTime());
		stack.hurtAndBreak(1, p, p.getUsedItemHand() == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
	}

	/** One tick of flame: the stream, whoever's in it, and whatever it lands on. */
	public static int spray(ServerLevel level, ServerPlayer p) {
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getLookAngle();
		Vec3 nozzle = eye.add(look.scale(0.8)).add(0, -0.25, 0);
		for (int i = 0; i < 6; i++) {
			Vec3 v = look.scale(0.5 + level.getRandom().nextDouble() * 0.3).add(level.getRandom().nextGaussian() * 0.06, level.getRandom().nextGaussian() * 0.06,
					level.getRandom().nextGaussian() * 0.06);
			level.sendParticles(ParticleTypes.FLAME, nozzle.x, nozzle.y, nozzle.z, 0, v.x, v.y, v.z, 1.0);
		}
		if (p.tickCount % 6 == 0) {
			level.playSound(null, p.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.5F, 0.6F + level.getRandom().nextFloat() * 0.2F);
		}
		// Whoever's in the stream: alight (a cone, out to REACH, and only through open air).
		int burned = 0;
		for (Entity e : level.getEntities(p, new AABB(eye, eye).inflate(REACH), e -> e instanceof LivingEntity && e.isAlive())) {
			Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
			if (to.length() > REACH || to.normalize().dot(look) < 0.9 || !p.hasLineOfSight(e)) {
				continue;
			}
			if (e instanceof Player victim && !Rules.canHurt(p, victim)) {
				continue;
			}
			e.igniteForSeconds(5);
			if (p.tickCount % 5 == 0) {
				e.hurtServer(level, level.damageSources().playerAttack(p), 1.5F);
			}
			burned++;
		}
		// What it lands on catches fire (a few times a second, splashing about where you aim).
		if (p.tickCount % 4 == 0) {
			Vec3 splash = look.add(level.getRandom().nextGaussian() * 0.12, level.getRandom().nextGaussian() * 0.12, level.getRandom().nextGaussian() * 0.12).normalize();
			BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(splash.scale(REACH)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
			if (hit.getType() == HitResult.Type.BLOCK) {
				if (!level.getFluidState(hit.getBlockPos()).isEmpty()) {
					level.sendParticles(ParticleTypes.CLOUD, hit.getLocation().x, hit.getLocation().y, hit.getLocation().z, 3, 0.2, 0.1, 0.2, 0.01);
				} else {
					Fire.lightAgainst(level, hit.getBlockPos(), hit.getDirection());
				}
			}
		}
		return burned;
	}
}
