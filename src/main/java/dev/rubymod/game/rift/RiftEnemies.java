package dev.rubymod.game.rift;

import java.util.List;

import dev.rubymod.entity.SoldierEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Who you fight in the Rift: each look has its own monsters (with names), plus Rift
 * Troopers with guns everywhere. Elites are big, glowing, tough, and have a twist:
 * Volatile (blows up when it dies), Vampiric (heals by hitting you), Swift, Armored, or
 * Summoner (calls in help).
 */
public final class RiftEnemies {
	public static final int RAID_ID = 4242;

	record Kind(EntityType<? extends Mob> type, String name, int cost, int weight) {
	}

	public enum Twist {
		VOLATILE("Volatile", 0xFFFF7A2E), VAMPIRIC("Vampiric", 0xFFFF3D6E), SWIFT("Swift", 0xFF66E0FF), ARMORED("Armored", 0xFFB8C4D0),
		SUMMONER("Summoner", 0xFFC27BFF);

		public final String title;
		public final int color;

		Twist(String title, int color) {
			this.title = title;
			this.color = color;
		}
	}

	static final Kind TROOPER = new Kind(null, "Rift Trooper", 3, 2);

	/** Shoots from a distance (gunmen, archers, blazes, breezes, witches). */
	static boolean ranged(Kind k) {
		return k.type() == null || k.type() == EntityTypes.SKELETON || k.type() == EntityTypes.STRAY || k.type() == EntityTypes.BLAZE
				|| k.type() == EntityTypes.BREEZE || k.type() == EntityTypes.WITCH;
	}

	/** The most shooters one wave brings: one early on, a few more deeper down and with a bigger team. */
	static int maxRanged(int players) {
		return Math.min(5, 1 + Rift.depth() / 7 + Math.max(0, players - 1));
	}

	/**
	 * How dangerous the gunmen are: 0 Rift Recruit (act 1), 1 Rift Trooper (act 2),
	 * 2 Rift Commando (act 3, full strength). Bloodthirst Heat and up: one rank higher.
	 */
	static int gunmanRank() {
		return Math.min(2, Rift.act - 1 + (RiftMeta.heat >= 4 ? 1 : 0));
	}

	/** For autotests: a gunman as act {@code act} would bring one. */
	public static Mob testGunman(ServerLevel level, BlockPos at, int act) {
		int was = Rift.act;
		Rift.act = act;
		try {
			return create(level, TROOPER, at, at, 1.0, null);
		} finally {
			Rift.act = was;
		}
	}

	/** The most gunmen one wave brings at each rank. */
	static int maxGunmen() {
		return switch (gunmanRank()) {
			case 0 -> 2;
			case 1 -> 4;
			default -> 99;
		};
	}

	private RiftEnemies() {
	}

	static List<Kind> roster(RiftRoom.Theme theme) {
		return switch (theme) {
			case FORGE, FOUNDRY -> List.of(new Kind(EntityTypes.ZOMBIE, "Ash Walker", 1, 4), new Kind(EntityTypes.WITHER_SKELETON, "Forge Reaver", 3, 3),
					new Kind(EntityTypes.BLAZE, "Cinder", 3, 2), new Kind(EntityTypes.MAGMA_CUBE, "Slag", 2, 2),
					new Kind(EntityTypes.PIGLIN_BRUTE, "Furnace Brute", 4, 1), TROOPER);
			case FROST -> List.of(new Kind(EntityTypes.STRAY, "Frost Archer", 2, 2), new Kind(EntityTypes.ZOMBIE, "Frozen Husk", 1, 4),
					new Kind(EntityTypes.BREEZE, "Gale", 3, 2), new Kind(EntityTypes.CAVE_SPIDER, "Ice Crawler", 1, 3), TROOPER);
			case LAB -> List.of(new Kind(EntityTypes.DROWNED, "Lab Subject", 1, 4), new Kind(EntityTypes.WITCH, "Alchemist", 3, 2),
					new Kind(EntityTypes.SLIME, "Ooze", 2, 2), new Kind(EntityTypes.ZOMBIE_VILLAGER, "Test Subject", 1, 3), TROOPER);
			default -> List.of(new Kind(EntityTypes.ZOMBIE, "Crystal Husk", 1, 5), new Kind(EntityTypes.SKELETON, "Shard Archer", 2, 2),
					new Kind(EntityTypes.SPIDER, "Rift Crawler", 1, 3), TROOPER);
		};
	}

	static Kind pick(RiftRoom.Theme theme, RandomSource random) {
		List<Kind> list = roster(theme);
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

	/** How tough everything is: floor by floor, and per extra player. */
	static double toughness(int floor, int players) {
		return (1 + floor * 0.12) * (1 + 0.45 * (players - 1));
	}

	/** Make one (null if it couldn't be made). */
	static Mob create(ServerLevel level, Kind kind, BlockPos at, BlockPos objective, double toughness, Twist twist) {
		Mob mob;
		String name = kind.name();
		double healthScale = 1.0;
		if (kind.type() == null) {
			SoldierEntity s = SoldierEntity.raider(level, at, objective, SoldierEntity.RIFLEMAN, RAID_ID);
			if (s != null) {
				switch (gunmanRank()) {
					case 0 -> {
						// Misses a lot, half a heart a bullet, two-round bursts after a second of aiming (with a laser).
						s.soften(2.4F, 0.5F, 2, 30, 22);
						name = "Rift Recruit";
						healthScale = 0.7;
					}
					case 1 -> {
						s.soften(1.5F, 0.75F, 3, 12, 10);
						name = "Rift Trooper";
					}
					default -> name = "Rift Commando";
				}
			}
			mob = s;
		} else {
			mob = kind.type().create(level, EntitySpawnReason.EVENT);
			if (mob != null) {
				mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360, 0);
			}
		}
		if (mob == null) {
			return null;
		}
		mob.setPersistenceRequired();
		if (mob.getType() == EntityTypes.SLIME || mob.getType() == EntityTypes.MAGMA_CUBE) {
			((net.minecraft.world.entity.monster.cubemob.AbstractCubeMob) mob).setSize(3, true);
		}
		if (kind.type() == EntityTypes.ZOMBIE && kind.name().equals("Frozen Husk")) {
			mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.ICE));
		} else if (kind.type() == EntityTypes.ZOMBIE && kind.name().equals("Crystal Husk")) {
			mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.AMETHYST_CLUSTER));
		}
		mob.setDropChance(EquipmentSlot.HEAD, 0);
		mob.setDropChance(EquipmentSlot.MAINHAND, 0);
		scale(mob, Attributes.MAX_HEALTH, toughness * healthScale * (twist != null ? 4.0 : 1.0) * (RiftMeta.heat >= 1 ? 1.3 : 1.0));
		scale(mob, Attributes.ATTACK_DAMAGE, Math.min(2.5, 0.8 + toughness * 0.2) * (twist != null ? 1.4 : 1.0) * (RiftMeta.heat >= 4 ? 1.4 : 1.0));
		if (RiftMeta.heat >= 4) {
			mob.addEffect(new MobEffectInstance(MobEffects.SPEED, -1, 0, false, false));
		}
		AttributeInstance follow = mob.getAttribute(Attributes.FOLLOW_RANGE);
		if (follow != null) {
			follow.setBaseValue(64);
		}
		mob.setHealth(mob.getMaxHealth());
		ChatFormatting color = ChatFormatting.RED;
		if (twist != null) {
			AttributeInstance size = mob.getAttribute(Attributes.SCALE);
			if (size != null) {
				size.setBaseValue(1.6);
			}
			switch (twist) {
				case SWIFT -> mob.addEffect(new MobEffectInstance(MobEffects.SPEED, -1, 1, false, false));
				case ARMORED -> mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, -1, 1, false, false));
				default -> {
				}
			}
			name = twist.title + " " + name;
			color = ChatFormatting.GOLD;
		}
		mob.setCustomName(Component.literal(name).withStyle(color, twist != null ? ChatFormatting.BOLD : ChatFormatting.RESET));
		mob.addTag(Rift.TAG);
		if (twist != null) {
			mob.addTag(Rift.TAG + "_" + twist.name().toLowerCase());
		}
		return mob;
	}

	private static void scale(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr, double by) {
		AttributeInstance a = mob.getAttribute(attr);
		if (a != null) {
			a.setBaseValue(a.getBaseValue() * by);
		}
	}

	static Twist twistOf(Mob mob) {
		for (Twist t : Twist.values()) {
			if (mob.entityTags().contains(Rift.TAG + "_" + t.name().toLowerCase())) {
				return t;
			}
		}
		return null;
	}
}
