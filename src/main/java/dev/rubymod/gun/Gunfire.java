package dev.rubymod.gun;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.game.Bounties;
import dev.rubymod.game.Rules;
import dev.rubymod.game.SpawnShield;
import dev.rubymod.net.Net;
import dev.rubymod.net.Payloads;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModDamage;


import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Server-side shooting: hitscan bullets, reloading, recoil, and hit feedback. */
public final class Gunfire {
	private static final Map<UUID, Long> NEXT_SHOT = new HashMap<>();
	private static final Map<UUID, Reload> RELOADS = new HashMap<>();

	private record Reload(int slot, GunItem gun, long finishAt) {
	}

	private Gunfire() {
	}

	public static void tryFire(ServerLevel level, ServerPlayer player, ItemStack stack, GunItem gun) {
		if (player.isSpectator() || Rules.isEliminated(player.getUUID())) {
			return;
		}
		long now = level.getGameTime();
		if (RELOADS.containsKey(player.getUUID())) {
			return;
		}
		if (now < NEXT_SHOT.getOrDefault(player.getUUID(), 0L)) {
			return;
		}
		GunStats stats = gun.stats(stack);
		int ammo = GunItem.ammo(stack);
		if (ammo <= 0) {
			NEXT_SHOT.put(player.getUUID(), now + 10);
			level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.6F, 1.6F);
			startReload(player);
			return;
		}

		NEXT_SHOT.put(player.getUUID(), now + stats.fireInterval());
		SpawnShield.clear(player);
		if (!player.isCreative()) {
			stack.set(ModComponents.AMMO, ammo - 1);
		}

		boolean aiming = GunItem.isAiming(player);
		float spread = aiming ? stats.aimSpread() : stats.hipSpread();
		Vec3 eye = player.getEyePosition();
		for (int i = 0; i < stats.pellets(); i++) {
			Vec3 dir = withSpread(player.getLookAngle(), spread, player.getRandom());
			trace(level, player, stats, eye, dir, stats.range(), stats.special() == GunStats.Special.BOUNCE ? 1 : 0);
		}

		playShot(level, player, stats);
		if (!stats.silenced()) {
			Vec3 muzzle = eye.add(player.getLookAngle().scale(0.8)).add(0, -0.15, 0);
			// Others see the muzzle smoke; the shooter doesn't (it would cloud their sights).
			sendToOthers(level, player, ParticleTypes.SMOKE, muzzle, 2, 0.02, 0.01);
		}

		RandomSource random = player.getRandom();
		float kick = aiming ? stats.recoil() * 0.6F : stats.recoil();
		Net.send(player, new Payloads.Recoil(kick, (random.nextFloat() - 0.5F) * kick * 0.5F));

		if (ammo - 1 <= 0 && !player.isCreative()) {
			startReload(player);
		}
	}

	private static void trace(ServerLevel level, ServerPlayer shooter, GunStats stats, Vec3 start, Vec3 dir, double range, int bounces) {
		Vec3 end = start.add(dir.scale(range));
		BlockHitResult blockHit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
		Vec3 blockEnd = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getLocation();

		Hit entityHit = findEntity(level, shooter, start, blockEnd, LagCompensation.rewindTicks(shooter));

		Vec3 hitPos;
		if (entityHit != null) {
			hitPos = entityHit.location();
			hitEntity(level, shooter, stats, entityHit.entity(), hitPos, entityHit.eyeY());
		} else if (blockHit.getType() == HitResult.Type.BLOCK) {
			hitPos = blockHit.getLocation();
			BlockPos pos = blockHit.getBlockPos();
			BlockState state = level.getBlockState(pos);
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), hitPos.x, hitPos.y, hitPos.z, 4, 0.05, 0.05, 0.05, 0.1);
			if (stats.special() == GunStats.Special.BREACH && isBreachable(state) && Rules.canWeaponBreak(level, pos, state)) {
				level.destroyBlock(pos, false, shooter);
			}
			if (bounces > 0) {
				double travelled = start.distanceTo(hitPos);
				Vec3 normal = Vec3.atLowerCornerOf(blockHit.getDirection().getUnitVec3i());
				Vec3 reflected = dir.subtract(normal.scale(2 * dir.dot(normal)));
				drawTracer(level, start, hitPos, stats);
				trace(level, shooter, stats, hitPos.add(normal.scale(0.05)), reflected, range - travelled, bounces - 1);
				return;
			}
		} else {
			hitPos = end;
		}
		drawTracer(level, start, hitPos, stats);
	}

	private record Hit(Entity entity, Vec3 location, double eyeY) {
	}

	/**
	 * The closest entity the bullet passes through. Players are tested where the
	 * shooter saw them ({@code rewind} ticks ago, see {@link LagCompensation}).
	 */
	private static Hit findEntity(ServerLevel level, ServerPlayer shooter, Vec3 start, Vec3 end, int rewind) {
		// Players may have moved up to ~1 block per tick since the shooter saw them, so search a wider box.
		AABB searchBox = new AABB(start, end).inflate(1.0 + rewind);
		Hit best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entity e : level.getEntities(shooter, searchBox,
				e -> e.isAlive() && e.isPickable() && !e.isSpectator() && !e.isPassengerOfSameVehicle(shooter))) {
			AABB box = LagCompensation.boxAt(e, rewind).inflate(0.1);
			var clip = box.contains(start) ? java.util.Optional.of(start) : box.clip(start, end);
			if (clip.isPresent()) {
				double distance = start.distanceToSqr(clip.get());
				if (distance < bestDistance) {
					bestDistance = distance;
					double eyeY = LagCompensation.positionAt(e, rewind).y + e.getEyeHeight();
					best = new Hit(e, clip.get(), eyeY);
				}
			}
		}
		return best;
	}

	private static void hitEntity(ServerLevel level, ServerPlayer shooter, GunStats stats, Entity target, Vec3 hitPos, double eyeY) {
		if (!Rules.canHurt(shooter, target)) {
			return;
		}
		boolean headshot = target instanceof LivingEntity && hitPos.y >= eyeY - 0.22;
		float damage = stats.damage() * (headshot ? stats.headshot() : 1.0F);
		target.setInvulnerableTime(0);
		boolean wasAlive = target.isAlive();
		target.hurtServer(level, ModDamage.source(level, headshot ? ModDamage.HEADSHOT : ModDamage.BULLET, shooter, shooter), damage);
		boolean killed = wasAlive && !target.isAlive();
		Bounties.onGunHit(shooter, target, headshot);
		level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, hitPos.x, hitPos.y, hitPos.z, 1, 0.1, 0.1, 0.1, 0.0);
		Net.send(shooter, new Payloads.HitMarker(headshot, killed));
	}

	private static boolean isBreachable(BlockState state) {
		return state.getSoundType() == SoundType.GLASS || state.is(BlockTags.WOODEN_DOORS) || state.is(BlockTags.WOODEN_TRAPDOORS);
	}

	private static void drawTracer(ServerLevel level, Vec3 from, Vec3 to, GunStats stats) {
		if (stats.silenced()) {
			return;
		}
		ParticleOptions particle = stats.ammo() == AmmoType.RUBY_CELL
				? new DustParticleOptions(0xFF2040, 1.0F)
				: ParticleTypes.ELECTRIC_SPARK;
		Vec3 delta = to.subtract(from);
		double length = delta.length();
		if (length < 1.5) {
			return;
		}
		Vec3 step = delta.normalize();
		double spacing = stats.ammo() == AmmoType.RUBY_CELL ? 0.6 : 2.0;
		int count = (int) Math.min(length / spacing, 60);
		int first = (int) Math.ceil(3.0 / spacing);
		for (int i = first; i <= count; i++) {
			Vec3 p = from.add(step.scale(i * spacing));
			level.sendParticles(particle, p.x, p.y, p.z, 1, 0, 0, 0, 0);
		}
	}

	private static void playShot(ServerLevel level, ServerPlayer player, GunStats stats) {
		SoundEvent sound;
		float pitch;
		float volume = 1.5F;
		switch (stats.ammo()) {
			case SHELLS -> {
				sound = SoundEvents.GENERIC_EXPLODE.value();
				pitch = 1.9F;
				volume = 0.8F;
			}
			case SNIPER -> {
				sound = SoundEvents.FIREWORK_ROCKET_LARGE_BLAST;
				pitch = 0.7F;
				volume = 3.0F;
			}
			case RUBY_CELL -> {
				sound = SoundEvents.AMETHYST_BLOCK_CHIME;
				pitch = 1.8F;
			}
			case HEAVY -> {
				sound = SoundEvents.FIREWORK_ROCKET_BLAST;
				pitch = 1.1F;
			}
			default -> {
				sound = SoundEvents.FIREWORK_ROCKET_BLAST;
				pitch = 1.6F;
			}
		}
		if (stats.silenced()) {
			volume *= 0.25F;
			pitch += 0.4F;
		}
		level.playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, volume, pitch + (player.getRandom().nextFloat() - 0.5F) * 0.1F);
	}

	private static Vec3 withSpread(Vec3 dir, float degrees, RandomSource random) {
		if (degrees <= 0) {
			return dir;
		}
		double s = Math.tan(Math.toRadians(degrees));
		return dir.add(random.triangle(0, s), random.triangle(0, s), random.triangle(0, s)).normalize();
	}

	// ----- Reloading -----

	public static void startReload(ServerPlayer player) {
		if (RELOADS.containsKey(player.getUUID())) {
			return;
		}
		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof GunItem gun)) {
			return;
		}
		GunStats stats = gun.stats(stack);
		if (GunItem.ammo(stack) >= stats.magazine()) {
			return;
		}
		if (!player.isCreative() && player.getInventory().countItem(stats.ammo().item()) == 0) {
			player.sendSystemMessage(Component.literal("Out of ammo! Craft or buy ")
					.append(Component.translatable(stats.ammo().item().getDescriptionId())), true);
			return;
		}
		long finish = player.level().getGameTime() + stats.reloadTicks();
		RELOADS.put(player.getUUID(), new Reload(player.getInventory().getSelectedSlot(), gun, finish));
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.CROSSBOW_LOADING_START.value(), SoundSource.PLAYERS, 0.8F, 1.2F);
		Net.send(player, new Payloads.ReloadState(stats.reloadTicks()));
	}

	/** Called every server tick for each player. */
	public static void tickPlayer(ServerPlayer player) {
		Reload reload = RELOADS.get(player.getUUID());
		if (reload != null) {
			ItemStack held = player.getMainHandItem();
			if (player.getInventory().getSelectedSlot() != reload.slot() || held.getItem() != reload.gun()) {
				RELOADS.remove(player.getUUID());
				Net.send(player, new Payloads.ReloadState(0));
			} else if (player.level().getGameTime() >= reload.finishAt()) {
				RELOADS.remove(player.getUUID());
				finishReload(player, held, reload.gun().stats(held));
			}
		}

		// Sniper glint: aiming a scoped rifle gives your position away.
		if (player.tickCount % 10 == 0 && GunItem.isAiming(player)
				&& player.getMainHandItem().getItem() instanceof GunItem gun && gun.stats(player.getMainHandItem()).special() == GunStats.Special.GLINT) {
			Vec3 p = player.getEyePosition().add(player.getLookAngle().scale(0.6));
			sendToOthers((ServerLevel) player.level(), player, ParticleTypes.END_ROD, p, 1, 0, 0);
		}
	}

	private static void finishReload(ServerPlayer player, ItemStack stack, GunStats stats) {
		int current = GunItem.ammo(stack);
		int needed = stats.magazine() - current;
		int taken = player.isCreative() ? needed : takeAmmo(player, stats.ammo(), needed);
		stack.set(ModComponents.AMMO, current + taken);
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.CROSSBOW_LOADING_END.value(), SoundSource.PLAYERS, 0.8F, 1.3F);
		Net.send(player, new Payloads.ReloadState(0));
	}

	private static int takeAmmo(ServerPlayer player, AmmoType type, int needed) {
		int taken = 0;
		var inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize() && taken < needed; i++) {
			ItemStack s = inventory.getItem(i);
			if (s.is(type.item())) {
				int take = Math.min(needed - taken, s.getCount());
				s.shrink(take);
				taken += take;
			}
		}
		inventory.setChanged();
		return taken;
	}

	/** Particles every player sees except the shooter (so they never fog the shooter's own view or scope). */
	private static void sendToOthers(ServerLevel level, ServerPlayer shooter, ParticleOptions particle, Vec3 at, int count, double spread, double speed) {
		for (ServerPlayer viewer : level.players()) {
			if (viewer != shooter) {
				level.sendParticles(viewer, particle, false, false, at.x, at.y, at.z, count, spread, spread, spread, speed);
			}
		}
	}

	public static void forget(UUID player) {
		NEXT_SHOT.remove(player);
		RELOADS.remove(player);
	}
}
