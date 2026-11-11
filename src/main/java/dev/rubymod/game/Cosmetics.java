package dev.rubymod.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.math.Transformation;

import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.mixin.ItemDisplayAccessor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Cosmetics, earned by playing (they never change how anything plays):
 *
 * <ul>
 * <li>Armor sets: a look for whatever armor you're wearing (the armor itself, and what it protects, stays the same).
 * In the hub, where nobody wears armor, you get a set to show it off; it's gone when you leave.</li>
 * <li>A trail of particles when you move, and an effect when you get a kill.</li>
 * <li>Paint for your Ruby Convoy truck.</li>
 * </ul>
 *
 * Unlocked by your level (XP comes from every game mode) and by feats (deliver a convoy, win a Rift run...), and
 * picked on the profile screen (I). Hidden while you're invisible or watching.
 */
public final class Cosmetics {
	public enum Kind { ARMOR, TRAIL, KILL, PAINT }

	public record Cosmetic(String id, Kind kind, String name, String icon, String how, Predicate<Stats.Record> earned) {
	}

	private static Predicate<Stats.Record> level(int n) {
		return r -> Stats.level(r.xp)[0] >= n;
	}

	public static final List<Cosmetic> ALL = List.of(
			new Cosmetic("set_recruit", Kind.ARMOR, "Recruit Fatigues", "minecraft:iron_chestplate", "Reach level 2", level(2)),
			new Cosmetic("set_ranger", Kind.ARMOR, "Forest Ranger", "minecraft:fern", "Reach level 5", level(5)),
			new Cosmetic("set_frost", Kind.ARMOR, "Frost Guard", "minecraft:packed_ice", "Reach level 10", level(10)),
			new Cosmetic("set_shadow", Kind.ARMOR, "Shadow Ops", "minecraft:netherite_chestplate", "Reach level 15", level(15)),
			new Cosmetic("set_champion", Kind.ARMOR, "Ruby Champion", "rubymod:ruby_chestplate", "Reach level 25", level(25)),
			new Cosmetic("set_hauler", Kind.ARMOR, "Convoy Hauler", "rubymod:jeep", "Deliver a Ruby Convoy", r -> r.convoyWins >= 1),
			new Cosmetic("set_legion", Kind.ARMOR, "Iron Legion", "rubymod:tank", "Deliver a Ruby Convoy on Hard", r -> r.convoyHardWins >= 1),
			new Cosmetic("set_riftwalker", Kind.ARMOR, "Rift Walker", "minecraft:end_crystal", "Win a Ruby Rift run", r -> r.riftWins >= 1),
			new Cosmetic("set_breaker", Kind.ARMOR, "Horde Breaker", "minecraft:bone", "Reach wave 10 in Co-op Horde", r -> r.hordeBestWave >= 10),
			new Cosmetic("set_star", Kind.ARMOR, "Party Star", "minecraft:nether_star", "Win 5 party games", r -> r.totalWins() >= 5),
			new Cosmetic("set_victor", Kind.ARMOR, "Season Victor", "minecraft:golden_chestplate", "Win a Ruby Wars season", r -> r.seasonsWon >= 1),
			new Cosmetic("trail_ruby", Kind.TRAIL, "Ruby Sparkles", "rubymod:ruby", "Everyone starts with this one", r -> true),
			new Cosmetic("trail_hearts", Kind.TRAIL, "Hearts", "minecraft:red_dye", "Revive a teammate in Horde", r -> r.hordeRevives >= 1),
			new Cosmetic("trail_snow", Kind.TRAIL, "Snowflakes", "minecraft:snowball", "Reach wave 5 in Co-op Horde", r -> r.hordeBestWave >= 5),
			new Cosmetic("trail_clouds", Kind.TRAIL, "Clouds", "rubymod:jetpack", "Finish a Sky Race", r -> r.skyBest > 0),
			new Cosmetic("trail_notes", Kind.TRAIL, "Music Notes", "minecraft:note_block", "Win Build Battle", r -> r.wins("build") >= 1),
			new Cosmetic("trail_flames", Kind.TRAIL, "Flames", "minecraft:blaze_powder", "Get 25 kills in party games", r -> r.partyKills >= 25),
			new Cosmetic("trail_magic", Kind.TRAIL, "Magic", "minecraft:amethyst_shard", "Win 3 duels", r -> r.duelsWon >= 3),
			new Cosmetic("kill_confetti", Kind.KILL, "Confetti", "minecraft:firework_rocket", "Everyone starts with this one", r -> true),
			new Cosmetic("kill_hearts", Kind.KILL, "Heart Burst", "minecraft:pink_dye", "Get 50 kills in party games", r -> r.partyKills >= 50),
			new Cosmetic("kill_lightning", Kind.KILL, "Lightning", "minecraft:lightning_rod", "Win Gun Game", r -> r.wins("gungame") >= 1),
			new Cosmetic("kill_ruby", Kind.KILL, "Ruby Explosion", "rubymod:ruby_block", "Win Ruby Rush", r -> r.wins("rush") >= 1),
			new Cosmetic("paint_red", Kind.PAINT, "Ruby Red", "minecraft:red_concrete", "Everyone starts with this one", r -> true),
			new Cosmetic("paint_blue", Kind.PAINT, "Deep Blue", "minecraft:blue_concrete", "Deliver a Ruby Convoy", r -> r.convoyWins >= 1),
			new Cosmetic("paint_black", Kind.PAINT, "Night Black", "minecraft:black_concrete", "Deliver 3 Ruby Convoys", r -> r.convoyWins >= 3),
			new Cosmetic("paint_gold", Kind.PAINT, "Gold", "minecraft:yellow_concrete", "Deliver a Ruby Convoy on Hard", r -> r.convoyHardWins >= 1));

	private static final String HAT_TAG = "rubymod_hat";
	/** Marks the armor lent to you in the hub to show your set off. */
	private static final String LENT = "rubymod_cosmetic";

	private Cosmetics() {
	}

	public static Cosmetic byId(String id) {
		for (Cosmetic c : ALL) {
			if (c.id().equals(id)) {
				return c;
			}
		}
		return null;
	}

	public static void reset() {
	}

	/** Unlock anything newly earned, and say so. */
	public static void checkUnlocks(ServerPlayer player) {
		Stats.Record r = Stats.get(player);
		List<Cosmetic> fresh = new ArrayList<>();
		for (Cosmetic c : ALL) {
			if (!r.unlocked.contains(c.id()) && c.earned().test(r)) {
				r.unlocked.add(c.id());
				Stats.markDirty();
				boolean free = c.how().startsWith("Everyone");
				if (free && c.kind() == Kind.TRAIL && r.trail.isEmpty()) {
					r.trail = c.id();
				} else if (free && c.kind() == Kind.KILL && r.killEffect.isEmpty()) {
					r.killEffect = c.id();
				} else if (free && c.kind() == Kind.PAINT && r.paint.isEmpty()) {
					r.paint = c.id();
				}
				if (!free) {
					fresh.add(c);
				}
			}
		}
		if (fresh.isEmpty()) {
			return;
		}
		// One line, however many it was (the first time after levels arrived, it can be a dozen).
		String what = fresh.size() <= 2
				? "New " + kindName(fresh.get(0).kind()) + " unlocked: " + fresh.get(0).name()
						+ (fresh.size() == 2 ? ", and " + fresh.get(1).name() : "") + "!"
				: fresh.size() + " new things unlocked (" + fresh.get(0).name() + ", " + fresh.get(1).name() + " and more)!";
		player.sendSystemMessage(Component.literal(what + " Press I to use " + (fresh.size() == 1 ? "it." : "them.")).withStyle(ChatFormatting.LIGHT_PURPLE));
		player.level().playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.5F, 1.4F);
	}

	public static String kindName(Kind kind) {
		return switch (kind) {
			case ARMOR -> "armor set";
			case TRAIL -> "trail";
			case KILL -> "kill effect";
			case PAINT -> "truck paint";
		};
	}

	/** Wear (or take off, with "none:ARMOR" etc.) a cosmetic. Returns an error or null. */
	public static String select(ServerPlayer player, String id) {
		Stats.Record r = Stats.get(player);
		if (id.startsWith("none:")) {
			Kind kind;
			try {
				kind = Kind.valueOf(id.substring(5));
			} catch (IllegalArgumentException e) {
				return null;
			}
			switch (kind) {
				case ARMOR -> r.armor = "";
				case TRAIL -> r.trail = "";
				case KILL -> r.killEffect = "";
				case PAINT -> r.paint = "paint_red";
			}
			Stats.markDirty();
			dress(player);
			return null;
		}
		Cosmetic c = byId(id);
		if (c == null || !r.unlocked.contains(id)) {
			return "You haven't unlocked that yet.";
		}
		switch (c.kind()) {
			case ARMOR -> r.armor = id;
			case TRAIL -> r.trail = id;
			case KILL -> r.killEffect = id;
			case PAINT -> r.paint = id;
		}
		Stats.markDirty();
		dress(player);
		return null;
	}

	/** For the profile screen: "id|kind|name|icon|how|unlocked|wearing" per cosmetic. */
	public static List<String> lines(ServerPlayer player) {
		Stats.Record r = Stats.get(player);
		List<String> lines = new ArrayList<>();
		for (Cosmetic c : ALL) {
			boolean wearing = c.id().equals(r.armor) || c.id().equals(r.trail) || c.id().equals(r.killEffect) || c.id().equals(r.paint);
			lines.add(c.id() + "|" + c.kind() + "|" + c.name() + "|" + c.icon() + "|" + c.how() + "|" + r.unlocked.contains(c.id()) + "|" + wearing);
		}
		return lines;
	}

	/** The colour of this player's convoy truck. */
	public static net.minecraft.world.item.DyeColor paint(ServerPlayer player) {
		return switch (Stats.get(player).paint) {
			case "paint_blue" -> net.minecraft.world.item.DyeColor.BLUE;
			case "paint_black" -> net.minecraft.world.item.DyeColor.BLACK;
			case "paint_gold" -> net.minecraft.world.item.DyeColor.YELLOW;
			default -> net.minecraft.world.item.DyeColor.RED;
		};
	}

	/** Cosmetics are hidden while hiding, invisible or watching. */
	private static boolean hidden(ServerPlayer player) {
		return player.isSpectator() || player.hasEffect(MobEffects.INVISIBILITY) || player.isDeadOrDying();
	}

	// ----- Armor sets and trails -----

	public static void tick(MinecraftServer server) {
		int t = server.getTickCount();
		if (t % 3 == 0) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				Stats.Record r = Stats.get(p);
				if (r.trail.isEmpty() || hidden(p)) {
					continue;
				}
				Vec3 move = p.getDeltaMovement();
				if (move.horizontalDistanceSqr() < 0.002 && Math.abs(move.y) < 0.05) {
					continue;
				}
				trail((ServerLevel) p.level(), r.trail, p.position().add(0, 0.15, 0));
			}
		}
		if (t % 20 == 0) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				dress(p);
			}
		}
	}

	private static final net.minecraft.world.entity.EquipmentSlot[] ARMOR_SLOTS = {net.minecraft.world.entity.EquipmentSlot.HEAD,
			net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET};
	private static final net.minecraft.world.item.Item[] LENT_ITEMS = {net.minecraft.world.item.Items.LEATHER_HELMET,
			net.minecraft.world.item.Items.LEATHER_CHESTPLATE, net.minecraft.world.item.Items.LEATHER_LEGGINGS, net.minecraft.world.item.Items.LEATHER_BOOTS};

	private static boolean lent(ItemStack stack) {
		var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(LENT);
	}

	/** Lent hub armor on the ground (someone threw it): it doesn't stay. */
	public static boolean lentItem(Entity e) {
		return e instanceof net.minecraft.world.entity.item.ItemEntity item && lent(item.getItem());
	}

	/**
	 * Make this player's armor look like their set: every piece of real armor they have on is drawn with the set's
	 * texture (or its own again, with no set). Nothing about the armor itself changes. In the hub, with no armor on,
	 * they're lent a set to show; lent armor never leaves the hub.
	 */
	public static void dress(ServerPlayer player) {
		Stats.Record r = Stats.get(player);
		String set = r.armor == null ? "" : r.armor;
		boolean hub = player.level().dimension() == Hub.HUB;
		var inv = player.getInventory();
		// Lent armor: only on your body, only in the hub, only with a set chosen.
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && lent(stack)) {
				boolean worn = false;
				for (var slot : ARMOR_SLOTS) {
					worn |= player.getItemBySlot(slot) == stack;
				}
				if (!worn || !hub || set.isEmpty()) {
					inv.setItem(i, ItemStack.EMPTY);
				}
			}
		}
		if (hub && !set.isEmpty() && !player.isSpectator()) {
			for (int i = 0; i < ARMOR_SLOTS.length; i++) {
				if (player.getItemBySlot(ARMOR_SLOTS[i]).isEmpty()) {
					ItemStack piece = new ItemStack(LENT_ITEMS[i]);
					var tag = new net.minecraft.nbt.CompoundTag();
					tag.putBoolean(LENT, true);
					piece.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
					// For show only: it protects from nothing.
					piece.set(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS, net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
					piece.set(net.minecraft.core.component.DataComponents.ITEM_NAME, Component.literal("Your armor set (for show)"));
					player.setItemSlot(ARMOR_SLOTS[i], piece);
				}
			}
		}
		for (var slot : ARMOR_SLOTS) {
			ItemStack stack = player.getItemBySlot(slot);
			if (stack.isEmpty()) {
				continue;
			}
			var own = stack.getPrototype().get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
			// Real armor only: something with its own armor texture (not an elytra, a jetpack or a pumpkin).
			if (own == null || own.assetId().isEmpty() || own.slot() != slot || stack.is(net.minecraft.world.item.Items.ELYTRA)
					|| stack.is(dev.rubymod.registry.ModItems.JETPACK)) {
				continue;
			}
			var want = own;
			if (!set.isEmpty() && !hidden(player)) {
				var asset = net.minecraft.resources.ResourceKey.create(net.minecraft.world.item.equipment.EquipmentAssets.ROOT_ID, dev.rubymod.RubyMod.id(set));
				want = new net.minecraft.world.item.equipment.Equippable(own.slot(), own.equipSound(), java.util.Optional.of(asset), own.cameraOverlay(),
						own.allowedEntities(), own.dispensable(), own.swappable(), own.damageOnHurt(), own.equipOnInteract(), own.canBeSheared(), own.shearingSound());
			}
			if (!want.equals(stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE))) {
				stack.set(net.minecraft.core.component.DataComponents.EQUIPPABLE, want);
			}
		}
	}

	private static void trail(ServerLevel level, String id, Vec3 at) {
		ParticleOptions particle = switch (id) {
			case "trail_hearts" -> ParticleTypes.HEART;
			case "trail_snow" -> ParticleTypes.SNOWFLAKE;
			case "trail_clouds" -> ParticleTypes.CLOUD;
			case "trail_notes" -> ParticleTypes.NOTE;
			case "trail_flames" -> ParticleTypes.FLAME;
			case "trail_magic" -> ParticleTypes.WITCH;
			default -> new DustParticleOptions(0xE0202A, 1.0F);
		};
		level.sendParticles(particle, at.x, at.y, at.z, 1, 0.15, 0.05, 0.15, 0.0);
	}

	/** Hats are gone: any left floating from before are cleaned up as they load. */
	public static boolean strayHat(Entity e) {
		return e.entityTags().contains(HAT_TAG);
	}

	// ----- Kill effects -----

	public static void onKill(ServerPlayer killer, Vec3 at) {
		Stats.Record r = Stats.get(killer);
		String id = r.killEffect;
		if (id.isEmpty()) {
			return;
		}
		ServerLevel level = (ServerLevel) killer.level();
		switch (id) {
			case "kill_hearts" -> level.sendParticles(ParticleTypes.HEART, at.x, at.y + 1, at.z, 12, 0.5, 0.5, 0.5, 0.1);
			case "kill_lightning" -> {
				LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
				if (bolt != null) {
					bolt.snapTo(at.x, at.y, at.z, 0, 0);
					bolt.setVisualOnly(true);
					level.addFreshEntity(bolt);
				}
			}
			case "kill_ruby" -> {
				level.sendParticles(new DustParticleOptions(0xE0202A, 2.0F), at.x, at.y + 1, at.z, 40, 0.8, 0.8, 0.8, 0.2);
				level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0);
				level.playSound(null, BlockPos.containing(at), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.5F, 0.8F);
			}
			default -> level.sendParticles(ParticleTypes.FIREWORK, at.x, at.y + 1, at.z, 20, 0.3, 0.5, 0.3, 0.15);
		}
	}
}
