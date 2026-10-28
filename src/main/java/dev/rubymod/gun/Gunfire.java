package dev.rubymod.gun;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.game.Bounties;
import dev.rubymod.game.Rules;
import dev.rubymod.game.SpawnShield;
import dev.rubymod.net.Net;
import dev.rubymod.net.Payloads;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.game.rift.RelicEffects;
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
		if (player.isSpectator() || Rules.isEliminated(player.getUUID()) && dev.rubymod.game.Hub.inRubyWarsWorld(player)
				|| player instanceof net.minecraft.server.level.ServerPlayer sp && !dev.rubymod.game.party.PartyGames.allowsGuns(sp)) {
			return;
		}
		long now = level.getGameTime();
		if (RELOADS.containsKey(player.getUUID())) {
			return;
		}
		if (now < NEXT_SHOT.getOrDefault(player.getUUID(), 0L)) {
			return;
		}
		// Shooting ends your spawn protection (no firing from behind it).
		dev.rubymod.game.SpawnShield.clear(player);
		GunStats stats = gun.stats(stack);
		int ammo = GunItem.ammo(stack);
		if (ammo <= 0) {
			NEXT_SHOT.put(player.getUUID(), now + 10);
			level.playSound(null, player.getX(), player.getY(), player.getZ(), dev.rubymod.registry.ModSounds.DRY_FIRE, SoundSource.PLAYERS, 0.8F, 1.0F);
			startReload(player);
			return;
		}

		boolean rift = dev.rubymod.game.rift.Rift.playing(player);
		// Fused traits for this shot (read by the bullets and the hits it makes).
		CURRENT = Trait.of(stack);
		PIERCED = 0;
		dev.rubymod.game.rift.RiftWeapons.armed(player, CURRENT, Trait.legendary(stack));
		int interval = stats.special() == GunStats.Special.SPINUP ? dev.rubymod.game.rift.RiftWeapons.spinInterval(player)
				: CURRENT.contains(Trait.SPINUP) ? Math.max(1, Math.round(stats.fireInterval() * dev.rubymod.game.rift.RiftWeapons.spinInterval(player) / 6.0F))
				: stats.fireInterval();
		NEXT_SHOT.put(player.getUUID(), now + (rift ? Math.max(1, Math.round(interval * RelicEffects.fireIntervalScale(player))) : interval));
		SpawnShield.clear(player);
		boolean infinite = rift && RelicEffects.infiniteAmmo(player);
		if (!player.isCreative() && !infinite) {
			stack.set(ModComponents.AMMO, ammo - 1);
		}

		if (dev.rubymod.game.rift.RiftWeapons.handles(stats.special())) {
			// The Rift's exotic weapons fire their own way (beams, blades, flames, missiles...).
			dev.rubymod.game.rift.RiftWeapons.fire(level, player, stack, stats);
			if (rift) {
				RelicEffects.onShot(level, player, stack);
			}
			float kick = stats.recoil();
			Net.send(player, new Payloads.Recoil(kick, (player.getRandom().nextFloat() - 0.5F) * kick * 0.5F));
			if (ammo - 1 <= 0 && !player.isCreative() && !infinite) {
				startReload(player);
			}
			return;
		}
		boolean aiming = GunItem.isAiming(player);
		float spread = aiming ? stats.aimSpread() : stats.hipSpread();
		Vec3 eye = player.getEyePosition();
		dev.rubymod.game.Duels.onShot(player, stats.pellets());
		int volleys = rift ? RelicEffects.shotMultiplier(player) : 1;
		int bounces = (stats.special() == GunStats.Special.BOUNCE ? 1 : 0) + (rift ? RelicEffects.extraBounces(player) : 0)
				+ (CURRENT.contains(Trait.BOUNCE) ? 2 : 0);
		for (int v = 0; v < volleys; v++) {
			for (int i = 0; i < stats.pellets(); i++) {
				Vec3 dir = withSpread(player.getLookAngle(), spread + v * 1.2F, player.getRandom());
				if (CURRENT.contains(Trait.HOMING)) {
					dir = dev.rubymod.game.rift.RiftWeapons.homing(level, player, eye, dir);
				}
				trace(level, player, stats, eye, dir, stats.range(), bounces);
			}
		}
		if (rift && !CURRENT.isEmpty()) {
			dev.rubymod.game.rift.RiftWeapons.traitShot(level, player, stats, CURRENT);
			dev.rubymod.game.rift.RiftWeapons.legendShot(level, player, stats);
		}
		if (rift) {
			RelicEffects.onShot(level, player, stack);
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

		if (ammo - 1 <= 0 && !player.isCreative() && !infinite) {
			startReload(player);
		}
	}

	/** Ruby Rift's Arsenal: another gun in your hotbar fires along with the one in your hand (no ammo used). */
	public static void ghostShot(ServerLevel level, ServerPlayer player, GunStats stats) {
		Vec3 eye = player.getEyePosition();
		for (int i = 0; i < stats.pellets(); i++) {
			trace(level, player, stats, eye, withSpread(player.getLookAngle(), stats.hipSpread(), player.getRandom()), stats.range(), 0);
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
			// Pierce: on through, into whatever's behind it (up to 3).
			if ((CURRENT.contains(Trait.PIERCE) || dev.rubymod.game.rift.RelicCombos.pierces(shooter)) && PIERCED < 3
					&& dev.rubymod.game.rift.Rift.playing(shooter)) {
				PIERCED++;
				Vec3 beyond = hitPos.add(dir.scale(entityHit.entity().getBbWidth() + 0.7));
				drawTracer(level, start, hitPos, stats);
				trace(level, shooter, stats, beyond, dir, range - start.distanceTo(beyond), bounces);
				return;
			}
		} else if (blockHit.getType() == HitResult.Type.BLOCK) {
			hitPos = blockHit.getLocation();
			BlockPos pos = blockHit.getBlockPos();
			BlockState state = level.getBlockState(pos);
			impact(level, blockHit, state, stats);
			if (stats.special() == GunStats.Special.BREACH && isBreachable(state) && Rules.canWeaponBreak(level, pos, state)) {
				level.destroyBlock(pos, false, shooter);
			} else if (BlockDamage.hit(level, pos, stats.damage(), shooter)) {
				drawTracer(level, start, hitPos, stats);
				return; // Shot through: the block is gone.
			}
			if (bounces > 0) {
				double travelled = start.distanceTo(hitPos);
				Vec3 normal = Vec3.atLowerCornerOf(blockHit.getDirection().getUnitVec3i());
				Vec3 reflected = dir.subtract(normal.scale(2 * dir.dot(normal)));
				if (dev.rubymod.game.rift.Rift.playing(shooter)) {
					reflected = RelicEffects.bounceAim(level, shooter, hitPos, reflected);
				}
				drawTracer(level, start, hitPos, stats);
				trace(level, shooter, stats, hitPos.add(normal.scale(0.05)), reflected, range - travelled, bounces - 1);
				// Pinball: every bounce throws off two fragments.
				if (dev.rubymod.game.rift.Rift.playing(shooter) && dev.rubymod.game.rift.RelicCombos.pinball(shooter)) {
					level.sendParticles(ParticleTypes.ELECTRIC_SPARK, hitPos.x, hitPos.y, hitPos.z, 6, 0.1, 0.1, 0.1, 0.2);
					for (int k = 0; k < 2; k++) {
						trace(level, shooter, stats.scaled(RelicEffects.splitDamage(shooter)), hitPos.add(normal.scale(0.05)),
								withSpread(reflected, 22.0F, shooter.getRandom()), 14, -1);
					}
				}
				return;
			}
			// Split Shot: a bullet that hits a wall bursts into three fragments (fragments, at -1 bounces, don't split again).
			if (bounces == 0 && dev.rubymod.game.rift.Rift.playing(shooter) && RelicEffects.splitShot(shooter)) {
				Vec3 normal = Vec3.atLowerCornerOf(blockHit.getDirection().getUnitVec3i());
				Vec3 reflected = dir.subtract(normal.scale(2 * dir.dot(normal)));
				drawTracer(level, start, hitPos, stats);
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, hitPos.x, hitPos.y, hitPos.z, 8, 0.1, 0.1, 0.1, 0.2);
				for (int k = 0; k < 3; k++) {
					Vec3 frag = withSpread(reflected, 18.0F, shooter.getRandom());
					trace(level, shooter, stats.scaled(RelicEffects.splitDamage(shooter)), hitPos.add(normal.scale(0.05)), frag, 14, -1);
				}
				return;
			}
		} else {
			hitPos = end;
		}
		drawTracer(level, start, hitPos, stats);
		nearMisses(level, shooter, start, hitPos, entityHit == null ? null : entityHit.entity());
	}

	/** Players the bullet passed close to (but didn't hit) hear it crack past and flinch a little. */
	private static void nearMisses(ServerLevel level, ServerPlayer shooter, Vec3 from, Vec3 to, Entity hit) {
		Vec3 seg = to.subtract(from);
		double len2 = seg.lengthSqr();
		if (len2 < 64) {
			return;
		}
		for (ServerPlayer p : level.players()) {
			if (p == shooter || p == hit || p.isSpectator() || p.distanceToSqr(shooter) < 64) {
				continue;
			}
			Vec3 eye = p.getEyePosition();
			double t = Math.max(0, Math.min(1, eye.subtract(from).dot(seg) / len2));
			Vec3 closest = from.add(seg.scale(t));
			double d = closest.distanceTo(eye);
			if (d < 2.5 && t > 0.05 && t < 0.999) {
				p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
						net.minecraft.core.Holder.direct(dev.rubymod.registry.ModSounds.BULLET_WHIZ), SoundSource.PLAYERS,
						closest.x, closest.y, closest.z, 0.9F, 0.85F + p.getRandom().nextFloat() * 0.3F, p.getRandom().nextLong()));
				Net.send(p, new Payloads.Shake(0.6F * (float) (1.0 - d / 2.5), 4));
			}
		}
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
		boolean rift = dev.rubymod.game.rift.Rift.playing(shooter);
		if (rift) {
			damage = RelicEffects.damage(shooter, target, damage, headshot);
			if (headshot) {
				RelicEffects.headshot(shooter, target, damage);
				if (stats.special() == GunStats.Special.FAN) {
					dev.rubymod.game.rift.RiftWeapons.fanRicochet(level, shooter, target, damage);
				}
			}
		}
		float healthBefore = target instanceof LivingEntity living ? living.getHealth() : 0;
		target.setInvulnerableTime(0);
		boolean wasAlive = target.isAlive();
		target.hurtServer(level, ModDamage.source(level, headshot ? ModDamage.HEADSHOT : ModDamage.BULLET, shooter, shooter), damage);
		boolean killed = wasAlive && !target.isAlive();
		if (rift && killed) {
			RelicEffects.overkill(shooter, target, damage - healthBefore);
		}
		if (rift && !CURRENT.isEmpty() && target instanceof net.minecraft.world.entity.LivingEntity living) {
			dev.rubymod.game.rift.RiftWeapons.traitHit(level, shooter, living, damage, CURRENT, headshot);
			dev.rubymod.game.rift.RiftWeapons.legendHit(level, shooter, living, damage, headshot);
		}
		Bounties.onGunHit(shooter, target, headshot);
		level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, hitPos.x, hitPos.y, hitPos.z, 1, 0.1, 0.1, 0.1, 0.0);
		Net.send(shooter, new Payloads.HitMarker(headshot, killed));
	}

	/** A bullet hitting a block: chips, a dust puff, sparks off stone and metal, the block's hit sound, and a bullet hole. */
	private static void impact(ServerLevel level, BlockHitResult hit, BlockState state, GunStats stats) {
		dev.rubymod.game.rift.RiftSecrets.onImpact(level, hit.getBlockPos());
		Vec3 p = hit.getLocation();
		Vec3 n = Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i());
		Vec3 out = p.add(n.scale(0.05));
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), out.x, out.y, out.z, 8, 0.06, 0.06, 0.06, 0.15);
		level.sendParticles(ParticleTypes.SMOKE, out.x, out.y, out.z, 2, 0.04, 0.04, 0.04, 0.01);
		var sound = state.getSoundType();
		boolean hard = sound == net.minecraft.world.level.block.SoundType.METAL || sound == net.minecraft.world.level.block.SoundType.STONE
				|| sound == net.minecraft.world.level.block.SoundType.DEEPSLATE || sound == net.minecraft.world.level.block.SoundType.NETHERITE_BLOCK;
		if (hard && stats.ammo() != AmmoType.RUBY_CELL) {
			level.sendParticles(ParticleTypes.CRIT, out.x, out.y, out.z, 3, 0.02, 0.02, 0.02, 0.25);
		}
		level.playSound(null, p.x, p.y, p.z, sound.getHitSound(), net.minecraft.sounds.SoundSource.BLOCKS, 0.7F, sound.getPitch() * 1.3F);
		if (state.isSolidRender() && stats.ammo() != AmmoType.RUBY_CELL) {
			var hole = new dev.rubymod.net.MorePayloads.BulletHole((float) p.x, (float) p.y, (float) p.z, (byte) hit.getDirection().get3DDataValue(),
					stats.ammo() == AmmoType.SHELLS ? (byte) 0 : stats.ammo() == AmmoType.SNIPER ? (byte) 2 : (byte) 1);
			for (ServerPlayer viewer : level.players()) {
				if (viewer.distanceToSqr(p) < 64 * 64) {
					Net.send(viewer, hole);
				}
			}
		}
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
		SoundEvent sound = switch (stats.ammo()) {
			case SHELLS -> dev.rubymod.registry.ModSounds.SHOTGUN_SHOT;
			case SNIPER -> dev.rubymod.registry.ModSounds.SNIPER_SHOT;
			case RUBY_CELL -> dev.rubymod.registry.ModSounds.BLASTER_SHOT;
			case HEAVY -> dev.rubymod.registry.ModSounds.RIFLE_SHOT;
			default -> dev.rubymod.registry.ModSounds.PISTOL_SHOT;
		};
		// Shots fade out over 96 blocks (sounds.json); volume above 1 stretches that: rifles ~120, the sniper ~240.
		float volume = switch (stats.ammo()) {
			case SNIPER -> 2.5F;
			case SHELLS, HEAVY -> 1.25F;
			case RUBY_CELL -> 0.9F;
			default -> 1.0F;
		};
		if (stats.silenced()) {
			sound = dev.rubymod.registry.ModSounds.SILENCED_SHOT;
			volume = 0.3F;
		}
		level.playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, volume, 0.95F + player.getRandom().nextFloat() * 0.1F);
	}


	private static Vec3 withSpread(Vec3 dir, float degrees, RandomSource random) {
		if (degrees <= 0) {
			return dir;
		}
		double s = Math.tan(Math.toRadians(degrees));
		return dir.add(random.triangle(0, s), random.triangle(0, s), random.triangle(0, s)).normalize();
	}

	// ----- Reloading -----

	/** No ammo needed: creative, or anywhere in Ruby Rift (magazines still empty and reload). */
	public static boolean freeAmmo(net.minecraft.world.entity.player.Player player) {
		return player.isCreative() || dev.rubymod.game.rift.Rift.playing(player);
	}

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
		if (!freeAmmo(player) && player.getInventory().countItem(stats.ammo().item()) == 0) {
			player.sendSystemMessage(Component.literal("Out of ammo! Craft or buy ")
					.append(Component.translatable(stats.ammo().item().getDescriptionId())), true);
			return;
		}
		long finish = player.level().getGameTime() + stats.reloadTicks();
		RELOADS.put(player.getUUID(), new Reload(player.getInventory().getSelectedSlot(), gun, finish));
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), stats.ammo() == AmmoType.SHELLS ? dev.rubymod.registry.ModSounds.SHELL_IN
				: dev.rubymod.registry.ModSounds.MAG_OUT, SoundSource.PLAYERS, 0.9F, 1.0F);
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
		int taken = freeAmmo(player) ? needed : takeAmmo(player, stats.ammo(), needed);
		if (dev.rubymod.game.rift.Rift.playing(player)) {
			RelicEffects.onReloaded(player);
		}
		stack.set(ModComponents.AMMO, current + taken);
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), dev.rubymod.registry.ModSounds.BOLT, SoundSource.PLAYERS, 0.9F, 1.0F);
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

	/** The traits of the gun being fired right now, and how many enemies this shot has pierced. */
	private static List<Trait> CURRENT = List.of();
	private static int PIERCED;

	/** Drop a pending reload (the Ruby Chakram came back to your hand first). */
	public static void forgetReload(ServerPlayer player) {
		if (RELOADS.remove(player.getUUID()) != null) {
			Net.send(player, new Payloads.ReloadState(0));
		}
	}

	public static void forget(UUID player) {
		NEXT_SHOT.remove(player);
		RELOADS.remove(player);
	}
}
