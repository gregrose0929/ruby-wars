package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.rubymod.registry.ModComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Rift Charms: one-use items that drop from fights. Right-click to use. You carry up to
 * three; a fourth is sold on the spot. Simple, big moments: "save the run" buttons.
 */
public final class RiftCharms {
	public enum Charm {
		BOTTLED_LIGHTNING("Bottled Lightning", "minecraft:experience_bottle", 0xFF66E0FF, "Lightning strikes every enemy in the room."),
		SHIELD_TONIC("Shield Tonic", "minecraft:honey_bottle", 0xFFFFC02E, "Nothing can hurt you for 5 seconds."),
		RAGE_BREW("Rage Brew", "minecraft:dragon_breath", 0xFFFF3D3D, "Double damage for 10 seconds."),
		FROST_BOMB("Frost Bomb", "minecraft:snowball", 0xFFA8E8FF, "Freezes every enemy near you solid."),
		HEALING_GEM("Healing Gem", "minecraft:golden_apple", 0xFF6EE38A, "Heals the whole team by half."),
		TIME_STOP("Time Stop", "minecraft:clock", 0xFFC27BFF, "Every enemy freezes in time for 4 seconds."),
		POCKET_PORTAL("Pocket Portal", "minecraft:ender_pearl", 0xFF8040FF, "Whisks you back to the room's entrance, untouchable for 3 seconds."),
		METEOR_CALL("Meteor Call", "minecraft:fire_charge", 0xFFFF7A2E, "Meteors rain down on the room, twice.");

		public final String title;
		public final String icon;
		public final int color;
		public final String about;

		Charm(String title, String icon, int color, String about) {
			this.title = title;
			this.icon = icon;
			this.color = color;
			this.about = about;
		}
	}

	static final int MAX = 3;

	private RiftCharms() {
	}

	/** How many you can carry: 3, or 4 with every combo in the Codex. */
	static int max(ServerPlayer p) {
		return RiftCodex.done(p, "combos") ? MAX + 1 : MAX;
	}

	public static ItemStack stack(Charm c) {
		ItemStack s = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(c.icon)));
		s.set(ModComponents.CHARM, c.name());
		s.set(DataComponents.CUSTOM_NAME, Component.literal("Charm: " + c.title).withColor(c.color & 0xFFFFFF).withStyle(ChatFormatting.BOLD)
				.withStyle(st -> st.withItalic(false)));
		s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(c.about).withStyle(ChatFormatting.GRAY).withStyle(st -> st.withItalic(false)),
				Component.literal("Right-click to use (one use).").withStyle(ChatFormatting.YELLOW).withStyle(st -> st.withItalic(false)))));
		s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		s.set(DataComponents.MAX_STACK_SIZE, 1);
		return s;
	}

	public static Charm of(ItemStack s) {
		String name = s.get(ModComponents.CHARM);
		if (name == null) {
			return null;
		}
		try {
			return Charm.valueOf(name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	static int count(ServerPlayer p) {
		int n = 0;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (of(inv.getItem(i)) != null) {
				n++;
			}
		}
		return n;
	}

	static void give(ServerPlayer p, Charm c) {
		RiftCodex.learn(p, "charm:" + c.name(), c.title);
		if (count(p) >= max(p)) {
			Rift.gold += 20;
			p.sendSystemMessage(Component.literal("Your charm pouch is full (" + max(p) + "): the " + c.title + " sold for 20 gold.").withStyle(ChatFormatting.GOLD));
			return;
		}
		ItemStack s = stack(c);
		if (!p.getInventory().add(s)) {
			p.drop(s, false, net.minecraft.util.Prediction.SERVER_ONLY);
		}
		p.inventoryMenu.broadcastChanges();
		ServerLevel level = (ServerLevel) p.level();
		RiftFx.burst(level, p.position().add(0, 1, 0), c.color, 20, 0.5);
		level.playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.4F);
		p.sendSystemMessage(Component.literal("✦ Charm found: ").withColor(c.color & 0xFFFFFF)
				.append(Component.literal(c.title).withStyle(ChatFormatting.BOLD)).append(Component.literal(" - " + c.about + " (right-click to use)")
						.withStyle(ChatFormatting.GRAY)));
	}

	/** After a room: a chance of a charm each. */
	static void drop(List<ServerPlayer> players, float chance, RandomSource random) {
		for (ServerPlayer p : players) {
			if (random.nextFloat() < chance) {
				give(p, Charm.values()[random.nextInt(Charm.values().length)]);
			}
		}
	}

	/** Right-clicked a charm: true if it was one (used up). */
	public static boolean use(ServerPlayer p, ItemStack held) {
		Charm c = of(held);
		if (c == null) {
			return false;
		}
		Rift.Runner r = Rift.runner(p);
		if (r == null || !(p.level() instanceof ServerLevel level)) {
			p.sendOverlayMessage(Component.literal("Charms only work in the Ruby Rift.").withStyle(ChatFormatting.GRAY));
			return true;
		}
		held.shrink(1);
		p.inventoryMenu.broadcastChanges();
		long now = level.getGameTime();
		Vec3 at = p.position();
		RiftFx.burst(level, at.add(0, 1.2, 0), c.color, 30, 0.7);
		RiftFx.ring(level, at.add(0, 0.2, 0), 2.0, RiftFx.dust(c.color & 0xFFFFFF, 1.6F), 30);
		level.playSound(null, p.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.5F, 1.6F);
		dev.rubymod.game.Alerts.title(p, c.title.toUpperCase(), c.about, ChatFormatting.WHITE);
		switch (c) {
			case BOTTLED_LIGHTNING -> {
				List<LivingEntity> all = RelicEffects.enemiesNear(level, at, 32, null);
				for (int i = 0; i < Math.min(14, all.size()); i++) {
					LivingEntity e = all.get(i);
					var bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
					if (bolt != null) {
						bolt.snapTo(e.getX(), e.getY(), e.getZ(), 0, 0);
						bolt.setVisualOnly(true);
						level.addFreshEntity(bolt);
					}
					RelicEffects.hurt(p, e, 16, 0xFF66E0FF);
					RelicCombos.shocked(level, p, e, false);
				}
				RiftFx.screen(p, "flash", 0xFF66E0FF, 8);
			}
			case SHIELD_TONIC -> {
				r.fx.untouchableUntil = Math.max(r.fx.untouchableUntil, now + 100);
				r.fx.charmAura = now + 100;
				r.fx.charmColor = c.color;
			}
			case RAGE_BREW -> {
				r.fx.rageUntil = now + 200;
				r.fx.charmAura = now + 200;
				r.fx.charmColor = c.color;
				level.playSound(null, p.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, 0.8F, 1.4F);
			}
			case FROST_BOMB -> {
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState()), at.x, at.y + 1, at.z, 80, 3, 1, 3, 0.3);
				level.sendParticles(ParticleTypes.SNOWFLAKE, at.x, at.y + 1, at.z, 120, 5, 1.5, 5, 0.1);
				for (LivingEntity e : RelicEffects.enemiesNear(level, at, 11, null)) {
					if (!RiftBoss.isBoss(e) && !RelicEffects.FROZEN.containsKey(e.getUUID())) {
						RelicEffects.freeze(level, e);
					} else if (RiftBoss.isBoss(e)) {
						e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 3, false, true));
					}
				}
				level.playSound(null, p.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.2F, 0.6F);
			}
			case HEALING_GEM -> {
				for (ServerPlayer mate : Rift.players(level.getServer())) {
					if (!mate.isSpectator()) {
						mate.heal(mate.getMaxHealth() * 0.5F);
						level.sendParticles(ParticleTypes.HEART, mate.getX(), mate.getY() + 2, mate.getZ(), 8, 0.4, 0.3, 0.4, 0);
						RiftFx.line(level, at.add(0, 1.2, 0), mate.position().add(0, 1.2, 0), RiftFx.dust(0x6EE38A, 1.0F), 0.5);
					}
				}
			}
			case TIME_STOP -> {
				for (UUID id : new ArrayList<>(Rift.MOBS)) {
					if (level.getEntity(id) instanceof LivingEntity e) {
						boolean boss = RiftBoss.isBoss(e);
						e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, boss ? 2 : 9, false, true));
						e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 80, 4, false, true));
						if (e instanceof Mob m && !boss) {
							m.setTarget(null);
						}
						level.sendParticles(RiftFx.dust(0xC27BFF, 1.2F), e.getX(), e.getY() + 1, e.getZ(), 8, 0.3, 0.5, 0.3, 0);
					}
				}
				for (ServerPlayer mate : Rift.players(level.getServer())) {
					RiftFx.screen(mate, "slowmo", 0xFFC27BFF, 80);
				}
				level.playSound(null, p.blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.0F, 0.5F);
			}
			case POCKET_PORTAL -> {
				Vec3 to = Rift.entry();
				if (to != null) {
					level.sendParticles(ParticleTypes.PORTAL, at.x, at.y + 1, at.z, 60, 0.4, 0.8, 0.4, 0.6);
					p.teleportTo(to.x, to.y, to.z);
					p.resetFallDistance();
					level.sendParticles(ParticleTypes.REVERSE_PORTAL, to.x, to.y + 1, to.z, 60, 0.4, 0.8, 0.4, 0.6);
				}
				r.fx.untouchableUntil = Math.max(r.fx.untouchableUntil, now + 60);
				level.playSound(null, p.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
			}
			case METEOR_CALL -> {
				RelicEffects.meteors(level, p);
				r.fx.meteorCallAt = now + 50;
			}
		}
		return true;
	}

	/** Each tick (from RelicEffects): the aura of a running charm, and Meteor Call's second shower. */
	static void tick(ServerPlayer p, Rift.Runner r, ServerLevel level) {
		long now = level.getGameTime();
		if (now < r.fx.charmAura && now % 3 == 0) {
			RiftFx.ring(level, p.position().add(0, 0.2 + (now % 12) * 0.15, 0), 0.9, RiftFx.dust(r.fx.charmColor & 0xFFFFFF, 1.1F), 10);
		}
		if (r.fx.meteorCallAt > 0 && now >= r.fx.meteorCallAt) {
			r.fx.meteorCallAt = 0;
			RelicEffects.meteors(level, p);
		}
	}
}
