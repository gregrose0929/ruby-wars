package dev.rubymod.item;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Grappling Hook (Armory): right-click a block up to {@link #RANGE} blocks away and you're reeled in along the rope
 * (which you can see until you're there) and up over the edge, for the mountains, cliffs and ruins. Crouch to let go. No fall damage until you land. A short cooldown; it
 * wears out after a while.
 */
public class GrapplingHookItem extends Item {
	public static final int RANGE = 40;
	private static final int COOLDOWN = 30;
	/** Someone on a hook line: where it's hooked, how long they've been on it, and whether they're up and over yet. */
	private static final class Line {
		final Vec3 hook;
		int age;
		boolean over;
		int grounded;

		Line(Vec3 hook) {
			this.hook = hook;
		}
	}

	private static final Map<UUID, Line> PULLED = new HashMap<>();

	public GrapplingHookItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer p)) {
			return InteractionResult.SUCCESS;
		}
		ItemStack held = p.getItemInHand(hand);
		if (p.getCooldowns().isOnCooldown(held) || p.isPassenger()) {
			return InteractionResult.FAIL;
		}
		Vec3 eye = p.getEyePosition();
		var hit = server.clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(RANGE)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		if (hit.getType() != HitResult.Type.BLOCK) {
			p.sendOverlayMessage(Component.literal("Nothing to hook onto there (it reaches " + RANGE + " blocks).").withStyle(ChatFormatting.GRAY));
			return InteractionResult.FAIL;
		}
		p.getCooldowns().addCooldown(held, COOLDOWN);
		Vec3 to = hit.getLocation().subtract(p.position());
		double dist = to.length();
		PULLED.put(p.getUUID(), new Line(hit.getLocation()));
		reel(p, PULLED.get(p.getUUID()));
		rope(server, p, hit.getLocation());
		server.playSound(null, p.blockPosition(), SoundEvents.FISHING_BOBBER_THROW, SoundSource.PLAYERS, 1.0F, 0.6F);
		server.playSound(null, BlockPos.containing(hit.getLocation()), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1.2F, 1.3F);
		held.hurtAndBreak(1, p, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
		return InteractionResult.SUCCESS;
	}

	/**
	 * Reeling in: pulled along the rope toward the hook (up a cliff face, the rope scrapes you up it; to a hook below
	 * you, you're let down to it), aiming just over the top of where it's hooked, and at the end a little push on over
	 * the edge so you land on top. Off the line when you land, let go (crouch), or after a few seconds.
	 */
	private static void reel(ServerPlayer p, Line line) {
		if (line.over) {
			return;
		}
		Vec3 target = line.hook.add(0, 1.1, 0);
		Vec3 to = target.subtract(p.position());
		double dist = to.length();
		double across = Math.hypot(to.x, to.z);
		Vec3 flat = across < 1e-6 ? Vec3.ZERO : new Vec3(to.x / across, 0, to.z / across);
		if (dist < 1.4 || line.age >= 80 || across < 0.8 && p.getY() >= line.hook.y + 0.6) {
			// There: over the edge and onto the top.
			line.over = true;
			p.setDeltaMovement(flat.scale(0.35).add(0, to.y > -0.5 ? 0.25 : 0, 0));
			p.syncVelocity = true;
			return;
		}
		Vec3 dir = to.scale(1 / dist);
		double speed = Math.min(1.1, 0.45 + dist * 0.06);
		Vec3 v = dir.scale(speed);
		if (p.horizontalCollision && to.y > 0) {
			// Against a wall: straight up it, still leaning in.
			v = new Vec3(flat.x * 0.15, Math.max(v.y, 0.75), flat.z * 0.15);
		}
		p.setDeltaMovement(v);
		p.syncVelocity = true;
	}

	/** The rope, from the hand to the hook (everyone nearby sees it). */
	private static void rope(ServerLevel level, ServerPlayer p, Vec3 hook) {
		Vec3 hand = p.getEyePosition().add(0, -0.45, 0).add(p.getLookAngle().scale(0.4));
		Vec3 to = hook.subtract(hand);
		double dist = to.length();
		DustParticleOptions dust = new DustParticleOptions(0xFF6E6457, 0.6F);
		for (double d = 0; d < dist; d += 0.5) {
			Vec3 q = hand.add(to.scale(d / dist));
			level.sendParticles(dust, q.x, q.y, q.z, 1, 0, 0, 0, 0);
		}
	}

	/** Every tick: the rope, the pull, and no fall damage while on the line (until you land, or ten seconds). */
	public static void tick(MinecraftServer server) {
		for (Iterator<Map.Entry<UUID, Line>> it = PULLED.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, Line> e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			Line line = e.getValue();
			line.age++;
			line.grounded = p != null && p.onGround() ? line.grounded + 1 : 0;
			if (p == null || line.age > 200 || line.grounded >= 10 && line.age > 5 || p.isPassenger()) {
				it.remove();
				continue;
			}
			p.resetFallDistance();
			if (p.isShiftKeyDown()) {
				line.over = true; // let go
			}
			reel(p, line);
			if (!line.over && p.level() instanceof ServerLevel level) {
				rope(level, p, line.hook);
			}
		}
	}

	/** For the self-test: is this player protected from the fall right now? */
	public static boolean onLine(Player p) {
		return PULLED.containsKey(p.getUUID());
	}
}
