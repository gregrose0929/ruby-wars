package dev.rubymod.game.convoy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.entity.SoldierEntity;
import dev.rubymod.entity.VehicleEntity;
import dev.rubymod.game.Explosions;
import dev.rubymod.game.rift.RiftFx;
import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.registry.ModDamage;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The Iron Legion and the monsters that follow it: who attacks on each stage, and how they
 * go about it. Most fight the players; some go for the truck: Sappers (TNT on their heads)
 * run at it and blow up, Brutes ram it, Rocketeers aim a red laser at it for a moment and
 * then fire (shoot them first!), and gunmen sometimes take potshots at it. Enemy tanks
 * show a red circle where each shell is about to land.
 */
final class ConvoyEnemies {
	enum Job { MELEE, RANGED, GUNMAN, ROCKETEER, SAPPER, BRUTE, SNIPER }

	record Kind(EntityType<? extends Mob> type, String name, int cost, int weight, Job job, Item head) {
		boolean ranged() {
			return job == Job.RANGED || job == Job.GUNMAN || job == Job.ROCKETEER || job == Job.SNIPER;
		}
	}

	private static final Map<UUID, Job> JOBS = new HashMap<>();
	private static final Map<UUID, int[]> TIMERS = new HashMap<>();
	/** Sappers about to go off (ticks left). */
	private static final Map<UUID, Integer> FUSES = new HashMap<>();
	/** Rocketeers aiming at the truck (ticks of aim left). */
	private static final Map<UUID, Integer> AIMING = new HashMap<>();

	private ConvoyEnemies() {
	}

	static List<Kind> roster(int stage) {
		Kind sapper = new Kind(EntityTypes.ZOMBIE, "Sapper", 2, 2, Job.SAPPER, Items.TNT);
		return switch (stage) {
			case 0 -> List.of(new Kind(EntityTypes.ZOMBIE, "Forest Ghoul", 1, 5, Job.MELEE, Items.MOSS_BLOCK),
					new Kind(EntityTypes.SPIDER, "Thicket Spider", 1, 3, Job.MELEE, null),
					new Kind(EntityTypes.SKELETON, "Bandit Archer", 2, 2, Job.RANGED, Items.CARVED_PUMPKIN),
					new Kind(null, "Bandit", 2, 2, Job.GUNMAN, null), sapper);
			case 1 -> List.of(new Kind(EntityTypes.HUSK, "Cinder Walker", 1, 5, Job.MELEE, null),
					new Kind(EntityTypes.ZOMBIE, "Firebug", 2, 2, Job.MELEE, Items.MAGMA_BLOCK),
					new Kind(null, "Legion Trooper", 3, 3, Job.GUNMAN, null),
					new Kind(null, "Legion Rocketeer", 4, 1, Job.ROCKETEER, null), sapper);
			case 2 -> List.of(new Kind(EntityTypes.HUSK, "Dust Ghoul", 1, 5, Job.MELEE, null),
					new Kind(EntityTypes.SKELETON, "Cliff Archer", 2, 2, Job.RANGED, Items.TERRACOTTA),
					new Kind(null, "Canyon Raider", 3, 3, Job.GUNMAN, null),
					new Kind(null, "Legion Rocketeer", 4, 1, Job.ROCKETEER, null), sapper);
			case 3 -> List.of(new Kind(EntityTypes.ZOMBIE, "Frozen Husk", 1, 5, Job.MELEE, Items.ICE),
					new Kind(EntityTypes.STRAY, "Frost Archer", 2, 2, Job.RANGED, Items.PACKED_ICE),
					new Kind(null, "Ridge Sniper", 3, 2, Job.SNIPER, null),
					new Kind(EntityTypes.RAVAGER, "Yeti", 6, 1, Job.BRUTE, null), sapper);
			default -> List.of(new Kind(EntityTypes.WITHER_SKELETON, "Iron Guard", 2, 4, Job.MELEE, null),
					new Kind(null, "Legion Commando", 3, 3, Job.GUNMAN, null),
					new Kind(null, "Legion Rocketeer", 4, 2, Job.ROCKETEER, null),
					new Kind(EntityTypes.RAVAGER, "Siege Ram", 6, 1, Job.BRUTE, null), sapper);
		};
	}

	static Kind pick(int stage, RandomSource random, boolean noRanged) {
		List<Kind> list = roster(stage).stream().filter(k -> !noRanged || !k.ranged()).toList();
		int total = list.stream().mapToInt(Kind::weight).sum();
		int r = random.nextInt(total);
		for (Kind k : list) {
			r -= k.weight();
			if (r < 0) {
				return k;
			}
		}
		return list.get(0);
	}

	static Kind melee(int stage) {
		return roster(stage).get(0);
	}

	/** How tough everything is: stage by stage, and per extra player. */
	static double toughness(int stage, int players) {
		return (1 + stage * 0.2) * (1 + 0.4 * (players - 1));
	}

	/** Make one and put it in the world (null if it couldn't be made). */
	static Mob spawn(ServerLevel level, Kind kind, BlockPos at, int stage, int players) {
		double tough = toughness(stage, players);
		Mob mob;
		if (kind.type() == null) {
			int role = kind.job() == Job.ROCKETEER ? SoldierEntity.AT_GUNNER : SoldierEntity.RIFLEMAN;
			SoldierEntity s = SoldierEntity.raider(level, at, at, role, dev.rubymod.game.Horde.SOLDIER_RAID_ID);
			if (s == null) {
				return null;
			}
			// Gunmen get better along the road: early ones miss a lot and warn you with a laser before they fire.
			int rank = stage <= 0 ? 0 : stage <= 3 ? 1 : 2;
			if (kind.job() == Job.SNIPER) {
				s.soften(0.6F, 0.9F, 1, 50, 40);
			} else if (rank == 0) {
				s.soften(2.4F, 0.45F, 2, 30, 22);
			} else if (rank == 1) {
				s.soften(1.6F, 0.65F, 3, 14, 12);
			} else {
				s.soften(1.2F, 0.8F, 3, 8, 8);
			}
			if (kind.job() == Job.SNIPER) {
				s.setWeapon(dev.rubymod.registry.ModItems.LONGSHOT_SNIPER);
				s.addEffect(new MobEffectInstance(MobEffects.GLOWING, -1, 0, false, false));
				s.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, -1, 3, false, false));
			}
			mob = s;
		} else {
			mob = kind.type().create(level, EntitySpawnReason.EVENT);
			if (mob == null) {
				return null;
			}
			mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360, 0);
		}
		mob.setPersistenceRequired();
		if (kind.head() != null) {
			mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(kind.head()));
			mob.setDropChance(EquipmentSlot.HEAD, 0);
		}
		mob.setDropChance(EquipmentSlot.MAINHAND, 0);
		double health = tough * (kind.job() == Job.BRUTE ? 1.4 : kind.job() == Job.SAPPER ? 0.6 : 1.0);
		scale(mob, Attributes.MAX_HEALTH, health);
		scale(mob, Attributes.ATTACK_DAMAGE, Math.min(2.0, 0.7 + stage * 0.15));
		set(mob, Attributes.FOLLOW_RANGE, 48);
		if (kind.job() == Job.SAPPER) {
			mob.addEffect(new MobEffectInstance(MobEffects.SPEED, -1, 1, false, false));
		}
		if (kind.name().equals("Firebug")) {
			mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, -1, 0, false, false));
		}
		if (kind.job() == Job.BRUTE) {
			set(mob, Attributes.MOVEMENT_SPEED, 0.22);
			if (kind.name().equals("Yeti")) {
				set(mob, Attributes.SCALE, 0.9);
			}
		}
		mob.setHealth(mob.getMaxHealth());
		mob.setCustomName(Component.literal(kind.name()).withStyle(kind.job() == Job.SAPPER ? ChatFormatting.GOLD : ChatFormatting.RED));
		mob.addTag(Convoy.TAG);
		level.addFreshEntity(mob);
		Convoy.MOBS.add(mob.getUUID());
		JOBS.put(mob.getUUID(), kind.job());
		TIMERS.put(mob.getUUID(), new int[] {40 + level.getRandom().nextInt(80)});
		return mob;
	}

	private static void scale(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr, double by) {
		AttributeInstance a = mob.getAttribute(attr);
		if (a != null) {
			a.setBaseValue(a.getBaseValue() * by);
		}
	}

	private static void set(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr, double value) {
		AttributeInstance a = mob.getAttribute(attr);
		if (a != null) {
			a.setBaseValue(value);
		}
	}

	static Job job(Entity e) {
		return e == null ? null : JOBS.get(e.getUUID());
	}

	static void clear() {
		JOBS.clear();
		TIMERS.clear();
		FUSES.clear();
		AIMING.clear();
		TANKS.clear();
	}

	private static boolean sees(ServerLevel level, Vec3 from, Vec3 to, Entity self) {
		return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self)).getType() == HitResult.Type.MISS;
	}

	// ----- Every tick -----

	static void tick(ServerLevel level, ConvoyTruck truck, List<ServerPlayer> targets, long ticks) {
		Vec3 truckPos = truck.position().add(0, 1.2, 0);
		for (UUID id : new ArrayList<>(Convoy.MOBS)) {
			Entity e = level.getEntity(id);
			if (!(e instanceof Mob mob) || !mob.isAlive()) {
				Convoy.MOBS.remove(id);
				JOBS.remove(id);
				TIMERS.remove(id);
				FUSES.remove(id);
				AIMING.remove(id);
				continue;
			}
			if (TANKS.containsKey(id) || Convoy.isBoss(mob)) {
				continue;
			}
			// Left far behind, or fell off the road into the gorge: gone.
			if (mob.getZ() < truck.z - 48 || mob.getY() < ConvoyRoute.Y - 8) {
				mob.discard();
				continue;
			}
			Job job = JOBS.getOrDefault(id, Job.MELEE);
			int[] timer = TIMERS.computeIfAbsent(id, k -> new int[] {60});
			timer[0]--;
			double toTruck = truck.distanceTo(mob.position());
			switch (job) {
				case SAPPER -> sapper(level, mob, truck, targets, toTruck);
				case BRUTE -> {
					if (ticks % 10 == 0) {
						mob.setTarget(null);
						mob.getNavigation().moveTo(truckPos.x, truckPos.y, truckPos.z, 1.2);
					}
					if (toTruck < 2.0 && timer[0] <= 0) {
						timer[0] = 30;
						truck.hurt(level, 9, mob.position().add(0, 1, 0));
						level.playSound(null, mob.blockPosition(), SoundEvents.RAVAGER_ATTACK, SoundSource.HOSTILE, 1.2F, 0.8F);
						ServerPlayer near = nearest(mob, targets);
						if (near != null && near.distanceTo(mob) < 10) {
							RiftFx.shake(near, 0.5F, 6);
						}
					}
				}
				case ROCKETEER -> rocketeer(level, mob, truck, targets, toTruck, timer);
				default -> {
					if (ticks % 10 == 0) {
						ServerPlayer near = nearest(mob, targets);
						boolean chase = near != null && near.distanceTo(mob) < (job == Job.MELEE ? 18 : 40);
						if (chase) {
							if (mob.getTarget() != near && !(mob instanceof SoldierEntity)) {
								mob.setTarget(near);
							}
						} else if (job == Job.MELEE) {
							mob.setTarget(null);
							mob.getNavigation().moveTo(truckPos.x, truckPos.y, truckPos.z, 1.0);
						}
					}
					// Monsters that reach the truck claw at it.
					if (job == Job.MELEE && toTruck < 1.6 && timer[0] <= 0) {
						timer[0] = 20;
						truck.hurt(level, 2.0F, mob.position().add(0, 1, 0));
					}
					// Gunmen keep up with the truck (snipers stay on their ridges).
					if (job == Job.GUNMAN && ticks % 40 == 0 && toTruck > 20 && mob instanceof SoldierEntity s) {
						s.setObjective(BlockPos.containing(truck.position()));
					}
					// Gunmen take a potshot at the truck now and then.
					if (job == Job.GUNMAN && timer[0] <= 0) {
						timer[0] = 50 + level.getRandom().nextInt(40);
						Vec3 eye = mob.getEyePosition();
						if (toTruck < 26 && level.getRandom().nextFloat() < 0.4F && sees(level, eye, truckPos, mob)) {
							RiftFx.line(level, eye, truckPos, ParticleTypes.CRIT, 0.9);
							level.playSound(null, mob.blockPosition(), dev.rubymod.registry.ModSounds.RIFLE_SHOT, SoundSource.HOSTILE, 1.0F, 0.9F);
							truck.hurt(level, 2.0F, truckPos);
						}
					}
				}
			}
		}
		tickTanks(level, truck, targets);
	}

	private static ServerPlayer nearest(Entity from, List<ServerPlayer> targets) {
		ServerPlayer best = null;
		for (ServerPlayer p : targets) {
			if (best == null || p.distanceToSqr(from) < best.distanceToSqr(from)) {
				best = p;
			}
		}
		return best;
	}

	/** A Sapper runs at the truck (or whoever's in the way), hisses and flashes for a second, then goes off. */
	private static void sapper(ServerLevel level, Mob mob, ConvoyTruck truck, List<ServerPlayer> targets, double toTruck) {
		UUID id = mob.getUUID();
		Integer fuse = FUSES.get(id);
		if (fuse == null) {
			Vec3 t = truck.position();
			if (level.getGameTime() % 10 == 0) {
				mob.setTarget(null);
				mob.getNavigation().moveTo(t.x, t.y, t.z, 1.25);
			}
			ServerPlayer near = nearest(mob, targets);
			if (toTruck < 2.6 || near != null && near.distanceTo(mob) < 2.2) {
				FUSES.put(id, 24);
				mob.getNavigation().stop();
				mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false));
				level.playSound(null, mob.blockPosition(), SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, 1.2F, 1.0F);
			}
			return;
		}
		FUSES.put(id, fuse - 1);
		level.sendParticles(ParticleTypes.SMOKE, mob.getX(), mob.getY() + 2.1, mob.getZ(), 2, 0.1, 0.1, 0.1, 0.02);
		if (fuse > 0) {
			return;
		}
		Vec3 at = mob.position();
		mob.discard();
		// The blast itself does the hurting (a small one: it's mostly the truck it's after).
		Explosions.explode(level, mob, at.x, at.y + 0.5, at.z, 1.6F, false);
		if (truck.distanceTo(at) < 3.0) {
			truck.hurt(level, 22, at.add(0, 1, 0));
		}
	}

	/** A Rocketeer: aims a red laser at the truck for a second and a half, then fires. */
	private static void rocketeer(ServerLevel level, Mob mob, ConvoyTruck truck, List<ServerPlayer> targets, double toTruck, int[] timer) {
		UUID id = mob.getUUID();
		Vec3 eye = mob.getEyePosition();
		Vec3 aim = truck.position().add(0, 1.4, 0);
		Integer left = AIMING.get(id);
		if (left == null) {
			if (level.getGameTime() % 10 == 0) {
				ServerPlayer near = nearest(mob, targets);
				if (near != null && near.distanceTo(mob) < 10) {
					mob.setTarget(near);
				} else if (toTruck > 22) {
					mob.getNavigation().moveTo(aim.x, aim.y, aim.z, 1.0);
				} else {
					mob.getNavigation().stop();
				}
			}
			if (timer[0] <= 0 && toTruck < 30 && sees(level, eye, aim, mob)) {
				AIMING.put(id, 30);
				mob.getNavigation().stop();
				level.playSound(null, mob.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 0.8F, 2.0F);
			}
			return;
		}
		mob.getLookControl().setLookAt(aim.x, aim.y, aim.z);
		RiftFx.line(level, eye, aim, RiftFx.dust(0xFF2020, 0.6F), 0.6);
		AIMING.put(id, left - 1);
		if (left > 0) {
			return;
		}
		AIMING.remove(id);
		timer[0] = 110 + level.getRandom().nextInt(50);
		RiftFx.line(level, eye, aim, ParticleTypes.FLAME, 0.5);
		RiftFx.line(level, eye, aim, ParticleTypes.SMOKE, 0.4);
		level.playSound(null, mob.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE, 1.5F, 0.6F);
		level.sendParticles(ParticleTypes.EXPLOSION, aim.x, aim.y, aim.z, 2, 0.6, 0.4, 0.6, 0);
		level.playSound(null, BlockPos.containing(aim), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.2F, 1.0F);
		truck.hurt(level, 14, aim);
	}

	// ----- Enemy tanks -----

	private record Part(BlockState block, float sx, float sy, float sz, float ox, float oy, float oz) {
	}

	private static final List<Part> TANK = List.of(
			new Part(Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(), 0.7F, 0.9F, 4.2F, -1.9F, 0.0F, -2.1F),
			new Part(Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(), 0.7F, 0.9F, 4.2F, 1.2F, 0.0F, -2.1F),
			new Part(Blocks.DEEPSLATE_TILES.defaultBlockState(), 3.0F, 1.0F, 4.0F, -1.5F, 0.45F, -2.0F),
			new Part(Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 1.8F, 0.9F, 1.9F, -0.9F, 1.45F, -1.0F),
			new Part(Blocks.POLISHED_BLACKSTONE.defaultBlockState(), 0.32F, 0.32F, 2.7F, -0.16F, 1.75F, 0.8F),
			new Part(Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState(), 3.04F, 0.2F, 0.4F, -1.52F, 1.2F, 1.0F),
			new Part(Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true), 0.3F, 0.22F, 0.3F, -0.15F, 2.35F, -0.6F));

	private static final class Tank {
		final List<UUID> parts = new ArrayList<>();
		int cooldown;
		Vec3 aim;
		int aimLeft;
	}

	static final Map<UUID, Tank> TANKS = new HashMap<>();

	static Mob spawnTank(ServerLevel level, BlockPos at, int stage, int players) {
		Mob base = EntityTypes.RAVAGER.create(level, EntitySpawnReason.EVENT);
		if (base == null) {
			return null;
		}
		base.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
		set(base, Attributes.SCALE, 1.25);
		set(base, Attributes.MAX_HEALTH, 110 * toughness(stage, players));
		set(base, Attributes.MOVEMENT_SPEED, 0.11);
		set(base, Attributes.FOLLOW_RANGE, 64);
		set(base, Attributes.KNOCKBACK_RESISTANCE, 1.0);
		base.setHealth(base.getMaxHealth());
		base.setSilent(true);
		base.setPersistenceRequired();
		base.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false));
		base.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, -1, 0, false, false));
		base.setCustomName(Component.literal("Legion Tank").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
		base.addTag(Convoy.TAG);
		level.addFreshEntity(base);
		Tank tank = new Tank();
		tank.cooldown = 60 + level.getRandom().nextInt(40);
		for (Part p : TANK) {
			Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (d == null) {
				continue;
			}
			d.snapTo(base.getX(), base.getY(), base.getZ(), 0, 0);
			((BlockDisplayAccessor) d).rubymod$setBlockState(p.block());
			((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(p.ox(), p.oy(), p.oz()), new Quaternionf(),
					new Vector3f(p.sx(), p.sy(), p.sz()), new Quaternionf()));
			((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(3);
			d.addTag(Convoy.TAG);
			level.addFreshEntity(d);
			tank.parts.add(d.getUUID());
		}
		TANKS.put(base.getUUID(), tank);
		Convoy.MOBS.add(base.getUUID());
		JOBS.put(base.getUUID(), Job.BRUTE);
		return base;
	}

	static int tanksLeft(ServerLevel level) {
		int n = 0;
		for (UUID id : TANKS.keySet()) {
			if (level.getEntity(id) instanceof Mob m && m.isAlive()) {
				n++;
			}
		}
		return n;
	}

	private static void tickTanks(ServerLevel level, ConvoyTruck truck, List<ServerPlayer> targets) {
		for (Iterator<Map.Entry<UUID, Tank>> it = TANKS.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			Tank tank = e.getValue();
			Entity base = level.getEntity(e.getKey());
			if (!(base instanceof Mob mob) || !mob.isAlive()) {
				Vec3 at = base != null ? base.position() : null;
				for (UUID id : tank.parts) {
					Entity d = level.getEntity(id);
					if (d != null) {
						at = at == null ? d.position() : at;
						d.discard();
					}
				}
				if (at != null) {
					level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0);
					level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 1, at.z, 30, 1, 0.6, 1, 0.05);
					level.playSound(null, BlockPos.containing(at), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2.0F, 0.7F);
				}
				it.remove();
				continue;
			}
			for (UUID id : tank.parts) {
				Entity d = level.getEntity(id);
				if (d != null) {
					d.teleportTo(mob.getX(), mob.getY(), mob.getZ());
					d.setYRot(mob.yBodyRot);
				}
			}
			if (level.getGameTime() % 4 == 0) {
				level.sendParticles(ParticleTypes.SMOKE, mob.getX(), mob.getY() + 2.6, mob.getZ(), 1, 0.1, 0.1, 0.1, 0.01);
			}
			// Roll toward the truck, stop at a distance, and shell it (or whoever's closest).
			Vec3 t = truck.position();
			mob.setTarget(null);
			if (truck.distanceTo(mob.position()) > 13) {
				if (level.getGameTime() % 20 == 0) {
					mob.getNavigation().moveTo(t.x, t.y, t.z, 1.0);
				}
			} else {
				mob.getNavigation().stop();
				mob.getLookControl().setLookAt(t.x, t.y + 1, t.z);
				float yaw = (float) -Math.toDegrees(Math.atan2(t.x - mob.getX(), t.z - mob.getZ()));
				mob.setYRot(yaw);
				mob.yBodyRot = yaw;
			}
			if (tank.aimLeft > 0) {
				tank.aimLeft--;
				RiftFx.ring(level, tank.aim, 3.0, RiftFx.dust(0xFF2020, 1.3F), 18);
				RiftFx.ring(level, tank.aim, 3.0 * tank.aimLeft / 28.0, RiftFx.dust(0xFFFFFF, 0.9F), 12);
				if (tank.aimLeft == 0) {
					fire(level, mob, tank.aim, truck, targets);
				}
			} else if (--tank.cooldown <= 0) {
				tank.cooldown = 90 + level.getRandom().nextInt(40);
				ServerPlayer near = nearest(mob, targets);
				boolean atTruck = near == null || level.getRandom().nextFloat() < 0.6F;
				Vec3 spot = atTruck ? truck.local((level.getRandom().nextDouble() - 0.5) * 2, 0, (level.getRandom().nextDouble() - 0.5) * 5)
						: near.position();
				tank.aim = new Vec3(spot.x, Math.floor(spot.y) + 0.1, spot.z);
				tank.aimLeft = 28;
				level.playSound(null, mob.blockPosition(), SoundEvents.PISTON_EXTEND, SoundSource.HOSTILE, 1.2F, 0.5F);
			}
		}
	}

	private static void fire(ServerLevel level, Mob mob, Vec3 land, ConvoyTruck truck, List<ServerPlayer> targets) {
		double yaw = Math.toRadians(mob.yBodyRot);
		Vec3 muzzle = mob.position().add(-Math.sin(yaw) * 3.4, 2.2, Math.cos(yaw) * 3.4);
		RiftFx.line(level, muzzle, land, ParticleTypes.SMOKE, 0.5);
		RiftFx.line(level, muzzle, land, RiftFx.dust(0xFF4020, 1.0F), 0.9);
		level.sendParticles(ParticleTypes.FLAME, muzzle.x, muzzle.y, muzzle.z, 12, 0.2, 0.2, 0.2, 0.05);
		level.sendParticles(ParticleTypes.EXPLOSION, land.x, land.y + 0.5, land.z, 3, 0.6, 0.4, 0.6, 0);
		level.playSound(null, BlockPos.containing(muzzle), dev.rubymod.registry.ModSounds.TANK_CANNON, SoundSource.HOSTILE, 2.0F, 0.9F);
		level.playSound(null, BlockPos.containing(land), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.4F, 1.0F);
		if (truck.distanceTo(land) < 3.0) {
			truck.hurt(level, 20, land.add(0, 1, 0));
		}
		for (ServerPlayer p : targets) {
			if (p.position().distanceTo(land) > 3.0) {
				continue;
			}
			if (p.getVehicle() instanceof VehicleEntity v) {
				v.hurtServer(level, ModDamage.source(level, ModDamage.ANTI_ARMOR, mob, mob), 20);
			} else {
				p.hurtServer(level, level.damageSources().explosion(mob, mob), 7);
			}
			RiftFx.shake(p, 0.9F, 8);
		}
	}
}
