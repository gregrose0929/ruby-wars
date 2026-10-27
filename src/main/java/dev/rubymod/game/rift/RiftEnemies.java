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

	private RiftEnemies() {
	}

	static List<Kind> roster(RiftRoom.Theme theme) {
		return switch (theme) {
			case FORGE, FOUNDRY -> List.of(new Kind(EntityTypes.ZOMBIE, "Ash Walker", 1, 4), new Kind(EntityTypes.WITHER_SKELETON, "Forge Reaver", 3, 3),
					new Kind(EntityTypes.BLAZE, "Cinder", 3, 2), new Kind(EntityTypes.MAGMA_CUBE, "Slag", 2, 2),
					new Kind(EntityTypes.PIGLIN_BRUTE, "Furnace Brute", 4, 1), TROOPER);
			case FROST -> List.of(new Kind(EntityTypes.STRAY, "Frost Archer", 2, 4), new Kind(EntityTypes.ZOMBIE, "Frozen Husk", 1, 4),
					new Kind(EntityTypes.BREEZE, "Gale", 3, 2), new Kind(EntityTypes.CAVE_SPIDER, "Ice Crawler", 1, 2), TROOPER);
			case LAB -> List.of(new Kind(EntityTypes.DROWNED, "Lab Subject", 1, 4), new Kind(EntityTypes.WITCH, "Alchemist", 3, 2),
					new Kind(EntityTypes.SLIME, "Ooze", 2, 2), new Kind(EntityTypes.ZOMBIE_VILLAGER, "Test Subject", 1, 3), TROOPER);
			default -> List.of(new Kind(EntityTypes.ZOMBIE, "Crystal Husk", 1, 5), new Kind(EntityTypes.SKELETON, "Shard Archer", 2, 3),
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
		if (kind.type() == null) {
			SoldierEntity s = SoldierEntity.raider(level, at, objective, SoldierEntity.RIFLEMAN, RAID_ID);
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
		scale(mob, Attributes.MAX_HEALTH, toughness * (twist != null ? 4.0 : 1.0) * (RiftMeta.heat >= 1 ? 1.3 : 1.0));
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
		String name = kind.name();
		if (twist != null) {
			AttributeInstance size = mob.getAttribute(Attributes.SCALE);
			if (size != null) {
				size.setBaseValue(1.6);
			}
			mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, -1, 0, false, false));
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
