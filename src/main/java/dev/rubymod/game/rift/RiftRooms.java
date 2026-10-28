package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.math.Transformation;

import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.mixin.ItemDisplayAccessor;
import dev.rubymod.registry.ModItems;

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
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The newer rooms, each a fight with a twist:
 * <ul>
 * <li>Gauntlet: survive 60 seconds of nonstop monsters; every kill counts toward the reward.</li>
 * <li>Vault Heist: rubies lie all over the vault; grab them before it seals (45 seconds) while guards pour in.</li>
 * <li>Rescue: a Guardian is caged in the middle. Stand by the cage (with no monster near it) to free it: it fights beside you for the rest of the act.</li>
 * <li>Lights Out: the lights are gone. Only the monsters glow. A better relic for clearing it.</li>
 * </ul>
 */
final class RiftRooms {
	private RiftRooms() {
	}

	static RiftMap.Type type;
	private static long until;
	private static int kills;
	private static int loot;
	/** Vault Heist: the rubies still lying around (display entity ids). */
	private static final List<UUID> RUBIES = new ArrayList<>();
	/** Rescue: the caged Guardian, its cage, and how far along freeing it is (0-100). */
	private static UUID prisoner;
	private static final List<BlockPos> CAGE = new ArrayList<>();
	private static int freeing;
	private static boolean freed;
	/** Guardians freed this act: each one comes along into every room. */
	static int guardians;

	/** Gold stolen in this Vault Heist. */
	static int loot() {
		return type == RiftMap.Type.HEIST ? loot : 0;
	}

	static boolean active() {
		return type != null;
	}

	static boolean isNew(RiftMap.Type t) {
		return t == RiftMap.Type.GAUNTLET || t == RiftMap.Type.HEIST || t == RiftMap.Type.RESCUE || t == RiftMap.Type.DARK;
	}

	static void start(ServerLevel level, RiftRoom room, RiftMap.Type t, List<ServerPlayer> players) {
		clear(level);
		type = t;
		kills = 0;
		loot = 0;
		freeing = 0;
		freed = false;
		long now = level.getGameTime();
		RandomSource random = level.getRandom();
		switch (t) {
			case GAUNTLET -> {
				until = now + 60 * 20;
				for (ServerPlayer p : players) {
					dev.rubymod.game.Alerts.title(p, "THE GAUNTLET", "Survive 60 seconds. Every kill counts!", ChatFormatting.RED);
				}
			}
			case HEIST -> {
				until = now + 45 * 20;
				// Rubies all over the floor.
				List<BlockPos> spots = new ArrayList<>(room.spawns);
				java.util.Collections.shuffle(spots, new java.util.Random(random.nextLong()));
				for (int i = 0; i < Math.min(16, spots.size() * 3); i++) {
					BlockPos s = spots.get(i % spots.size());
					Vec3 at = new Vec3(s.getX() + 0.5 + random.nextInt(7) - 3, s.getY() + 0.6, s.getZ() + 0.5 + random.nextInt(7) - 3);
					if (!room.inside(at) || !level.getBlockState(BlockPos.containing(at)).isAir()) {
						at = Vec3.atBottomCenterOf(s).add(0, 0.6, 0);
					}
					Display.ItemDisplay d = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
					if (d != null) {
						d.snapTo(at.x, at.y, at.z, 0, 0);
						((ItemDisplayAccessor) d).rubymod$setItemStack(i % 4 == 0 ? new ItemStack(dev.rubymod.registry.ModBlocks.RUBY_BLOCK) : new ItemStack(ModItems.RUBY));
						((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.8F, 0.8F, 0.8F),
								new Quaternionf()));
						((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(2);
						d.addTag(Rift.TAG);
						level.addFreshEntity(d);
						RUBIES.add(d.getUUID());
					}
				}
				for (ServerPlayer p : players) {
					dev.rubymod.game.Alerts.title(p, "VAULT HEIST", "Grab the rubies before the vault seals (45s)!", ChatFormatting.GOLD);
				}
			}
			case RESCUE -> {
				BlockPos c = room.center.above();
				for (int dx = -2; dx <= 2; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) == 2) {
							for (int y = 0; y < 4; y++) {
								BlockPos b = c.offset(dx, y, dz);
								level.setBlock(b, Blocks.IRON_BARS.defaultBlockState(), 3);
								CAGE.add(b);
							}
						}
					}
				}
				Mob golem = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.EVENT);
				if (golem != null) {
					golem.snapTo(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 180, 0);
					golem.setNoAi(true);
					golem.setPermanentlyInvulnerable(true);
					golem.setCustomName(Component.literal("Caged Guardian").withStyle(ChatFormatting.AQUA));
					golem.addTag(Rift.TAG);
					level.addFreshEntity(golem);
					prisoner = golem.getUUID();
				}
				for (ServerPlayer p : players) {
					dev.rubymod.game.Alerts.title(p, "RESCUE", "Stand by the cage to free the Guardian!", ChatFormatting.AQUA);
				}
			}
			case DARK -> {
				for (ServerPlayer p : players) {
					dev.rubymod.game.Alerts.title(p, "LIGHTS OUT", "Only the monsters glow...", ChatFormatting.DARK_PURPLE);
				}
				level.playSound(null, room.center, SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 2.0F, 0.5F);
			}
			default -> {
			}
		}
	}

	/** A Rift monster died in one of these rooms. */
	static void onKill() {
		if (type != null) {
			kills++;
		}
	}

	/** Every fight tick. True when the room's done (time up). */
	static boolean tick(ServerLevel level, RiftRoom room, List<ServerPlayer> players) {
		if (type == null) {
			return false;
		}
		long now = level.getGameTime();
		switch (type) {
			case GAUNTLET -> {
				if (now % 20 == 0 && until - now <= 10 * 20 && until > now) {
					for (ServerPlayer p : players) {
						p.sendOverlayMessage(Component.literal((until - now) / 20 + "...").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
						level.playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 1.0F, 1.6F);
					}
				}
				return now >= until;
			}
			case HEIST -> {
				for (Iterator<UUID> it = RUBIES.iterator(); it.hasNext();) {
					Entity d = level.getEntity(it.next());
					if (d == null) {
						it.remove();
						continue;
					}
					// Spinning, glinting.
					((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(0, (float) Math.sin(now * 0.15 + d.getId()) * 0.1F, 0),
							new Quaternionf().rotateY(now * 0.1F + d.getId()), new Vector3f(0.8F, 0.8F, 0.8F), new Quaternionf()));
					if (now % 10 == 0) {
						level.sendParticles(ParticleTypes.WAX_ON, d.getX(), d.getY() + 0.3, d.getZ(), 1, 0.2, 0.2, 0.2, 0.2);
					}
					for (ServerPlayer p : players) {
						if (!p.isSpectator() && p.position().add(0, 0.5, 0).distanceTo(d.position()) < 1.8) {
							int value = ((Display.ItemDisplay) d).getItemStack().is(ModItems.RUBY) ? 12 : 30;
							Rift.gold += value;
							loot += value;
							level.sendParticles(ParticleTypes.WAX_ON, d.getX(), d.getY(), d.getZ(), 20, 0.3, 0.3, 0.3, 0.6);
							RiftFx.burst(level, d.position(), 0xFFFF3D6E, 10, 0.3);
							level.playSound(null, d.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2F, 1.2F + RUBIES.size() * 0.03F);
							p.sendOverlayMessage(Component.literal("+" + value + " gold (" + loot + " stolen)").withStyle(ChatFormatting.GOLD));
							d.discard();
							it.remove();
							break;
						}
					}
				}
				if (now % 20 == 0 && until - now <= 10 * 20 && until > now) {
					for (ServerPlayer p : players) {
						level.playSound(null, p.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 0.6F, 1.4F);
					}
				}
				return now >= until || RUBIES.isEmpty() && now % 20 == 0 && Rift.MOBS.isEmpty();
			}
			case RESCUE -> {
				if (freed || prisoner == null) {
					return false;
				}
				Vec3 c = Vec3.atBottomCenterOf(room.center.above());
				boolean near = players.stream().anyMatch(p -> !p.isSpectator() && p.position().distanceTo(c) < 4.0);
				boolean blocked = !RelicEffects.enemiesNear(level, c, 4.5, null).isEmpty();
				if (near && !blocked) {
					freeing++;
				} else if (!near) {
					freeing = Math.max(0, freeing - 1);
				}
				if (now % 4 == 0) {
					int col = blocked ? 0xFF4040 : 0x66E0FF;
					RiftFx.ring(level, c.add(0, 0.2, 0), 3.0, RiftFx.dust(col, 1.2F), 20);
					RiftFx.ring(level, c.add(0, 0.3, 0), 3.0 * freeing / 100.0, RiftFx.dust(0xFFE680, 1.0F), 14);
				}
				if (near && now % 10 == 0) {
					for (ServerPlayer p : players) {
						p.sendOverlayMessage(Component.literal(blocked ? "Monsters at the cage! Clear them off." : "Freeing the Guardian... " + freeing + "%")
								.withStyle(blocked ? ChatFormatting.RED : ChatFormatting.AQUA));
					}
				}
				if (freeing >= 100) {
					free(level);
				}
				return false;
			}
			case DARK -> {
				if (now % 30 == 0) {
					for (ServerPlayer p : players) {
						p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0, false, false));
						p.removeEffect(MobEffects.NIGHT_VISION);
					}
					for (UUID id : Rift.MOBS) {
						if (level.getEntity(id) instanceof LivingEntity e) {
							e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false));
						}
					}
				}
				// Eyes in the dark.
				if (now % 8 == 0) {
					for (UUID id : Rift.MOBS) {
						if (level.getEntity(id) instanceof LivingEntity e) {
							level.sendParticles(RiftFx.dust(0xFF2020, 0.6F), e.getX(), e.getEyeY(), e.getZ(), 2, 0.15, 0.02, 0.15, 0);
						}
					}
				}
				return false;
			}
			default -> {
				return false;
			}
		}
	}

	private static void free(ServerLevel level) {
		freed = true;
		guardians++;
		for (BlockPos b : CAGE) {
			level.setBlock(b, Blocks.AIR.defaultBlockState(), 3);
			level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, Blocks.IRON_BARS.defaultBlockState()), b.getX() + 0.5,
					b.getY() + 0.5, b.getZ() + 0.5, 4, 0.2, 0.2, 0.2, 0.2);
		}
		CAGE.clear();
		if (level.getEntity(prisoner) instanceof Mob golem) {
			golem.setNoAi(false);
			golem.setCustomName(Component.literal("Freed Guardian").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
			ServerPlayer owner = Rift.players(level.getServer()).stream().findFirst().orElse(null);
			if (owner != null) {
				RelicEffects.ALLIES.put(golem.getUUID(), new RelicEffects.Ally(Long.MAX_VALUE, owner.getUUID(), false));
			}
			RiftFx.beam(level, golem.position(), 16, ParticleTypes.END_ROD, 0xFF66E0FF);
			level.playSound(null, golem.blockPosition(), SoundEvents.IRON_GOLEM_REPAIR, SoundSource.PLAYERS, 1.5F, 1.0F);
		}
		prisoner = null;
		for (ServerPlayer p : Rift.players(level.getServer())) {
			dev.rubymod.game.Alerts.title(p, "GUARDIAN FREED!", "It fights beside you for the rest of the act", ChatFormatting.AQUA);
			level.playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8F, 1.2F);
		}
	}

	/** Every room this act: each freed Guardian comes along. */
	static void bringGuardians(ServerLevel level, List<ServerPlayer> players) {
		if (guardians <= 0 || players.isEmpty()) {
			return;
		}
		ServerPlayer owner = players.get(0);
		for (int g = 0; g < guardians; g++) {
			Mob golem = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.EVENT);
			if (golem == null) {
				continue;
			}
			golem.snapTo(owner.getX() + (g % 2 == 0 ? 2.5 : -2.5), owner.getY(), owner.getZ() + 1.5, owner.getYRot(), 0);
			var hp = golem.getAttribute(Attributes.MAX_HEALTH);
			if (hp != null) {
				hp.setBaseValue(hp.getBaseValue() * 1.4);
				golem.setHealth(golem.getMaxHealth());
			}
			golem.setCustomName(Component.literal("Freed Guardian").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
			golem.addTag(Rift.TAG);
			level.addFreshEntity(golem);
			RelicEffects.ALLIES.put(golem.getUUID(), new RelicEffects.Ally(Long.MAX_VALUE, owner.getUUID(), false));
			RiftFx.beam(level, golem.position(), 8, ParticleTypes.END_ROD, 0xFF66E0FF);
		}
	}

	/** The boss bar while one of these is running (null: the usual). */
	static String bar(ServerLevel level, int enemies) {
		if (type == null) {
			return null;
		}
		long left = Math.max(0, (until - level.getGameTime()) / 20);
		return switch (type) {
			case GAUNTLET -> "GAUNTLET  •  " + left + "s left  •  " + kills + " kills";
			case HEIST -> "VAULT HEIST  •  seals in " + left + "s  •  " + RUBIES.size() + " rubies left  •  " + loot + " gold stolen";
			case RESCUE -> freed ? null : "RESCUE  •  free the Guardian: " + freeing + "%  •  " + enemies + " enemies";
			case DARK -> "LIGHTS OUT  •  " + enemies + " enemies in the dark";
			default -> null;
		};
	}

	static float progress(ServerLevel level) {
		return switch (type) {
			case GAUNTLET -> 1 - Math.max(0, until - level.getGameTime()) / (60F * 20);
			case HEIST -> 1 - Math.max(0, until - level.getGameTime()) / (45F * 20);
			case RESCUE -> freeing / 100F;
			default -> 1;
		};
	}

	/** The reward line and how good a relic (an elite's for a great Gauntlet, Lights Out). */
	static RiftMap.Type rewardTier() {
		if (type == null) {
			return RiftMap.Type.FIGHT;
		}
		return switch (type) {
			case GAUNTLET -> kills >= 25 ? RiftMap.Type.ELITE : RiftMap.Type.FIGHT;
			case DARK -> RiftMap.Type.ELITE;
			default -> RiftMap.Type.FIGHT;
		};
	}

	static int bonusGold() {
		if (type == null) {
			return 0;
		}
		return switch (type) {
			case GAUNTLET -> kills * 2;
			case DARK -> 40;
			case RESCUE -> freed ? 20 : 0;
			default -> 0;
		};
	}

	static String rewardTitle() {
		if (type == null) {
			return null;
		}
		return switch (type) {
			case GAUNTLET -> "Gauntlet survived: " + kills + " kills" + (kills >= 25 ? " (a better relic!)" : "");
			case HEIST -> "Heist over: " + loot + " gold stolen";
			case RESCUE -> freed ? "Guardian freed: choose a relic" : "Room cleared (the Guardian stayed caged)";
			case DARK -> "Survived the dark: a better relic";
			default -> null;
		};
	}

	static void clear(ServerLevel level) {
		for (UUID id : RUBIES) {
			Entity d = level.getEntity(id);
			if (d != null) {
				d.discard();
			}
		}
		RUBIES.clear();
		for (BlockPos b : CAGE) {
			level.setBlock(b, Blocks.AIR.defaultBlockState(), 3);
		}
		CAGE.clear();
		if (prisoner != null && level.getEntity(prisoner) instanceof Entity e) {
			e.discard();
		}
		prisoner = null;
		type = null;
	}
}
