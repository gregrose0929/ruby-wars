package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Mystery rooms: a choice with a catch, each runner choosing for themselves. Thirteen of
 * them, never the same one twice in a run (until they've all come up):
 * the Blood Altar, the Gambler's Shrine, a Lost Supply Cache, the Ruby Slots, a Cursed
 * Chest, the Fountain of Wishes, a Wandering Hero, Three Doors, Double or Nothing, the
 * Shrine of Blessings, the Relic Trader, a Treasure Goblin, and the Shrine of Echoes.
 */
public final class RiftEvents {
	private RiftEvents() {
	}

	static final int COUNT = 13;
	/** The ones seen this run (so they don't repeat). */
	static final Set<Integer> SEEN = new HashSet<>();
	/** The Treasure Goblin running loose, and when it gets away. */
	private static UUID goblin;
	private static long goblinGone;
	private static boolean goblinOut;

	/** A Mystery for this room: one not seen yet this run. */
	static int pick(RandomSource random) {
		if (SEEN.size() >= COUNT) {
			SEEN.clear();
		}
		int kind;
		do {
			kind = random.nextInt(COUNT);
		} while (SEEN.contains(kind));
		SEEN.add(kind);
		goblinOut = false;
		return kind;
	}

	/** "title|<heading>" then "option id|title|about|icon" lines. */
	static List<String> options(int kind) {
		return switch (kind) {
			case 0 -> List.of("title|The Blood Altar: it hungers",
					"blood|Feed the Altar|Lose half your health. Gain a Rare relic.|minecraft:redstone_block",
					"leave|Walk Away|Keep your blood.|minecraft:barrier");
			case 1 -> List.of("title|The Gambler's Shrine: fortune favors the bold",
					"gamble|Spin the Wheel|50%: a Legendary relic. 50%: a Cursed one.|minecraft:end_crystal",
					"leave|Walk Away|Play it safe.|minecraft:barrier");
			case 3 -> List.of("title|The Ruby Slots: pull the lever!",
					"slots|Pull (30 gold)|Anything from nothing to a Legendary relic. Pull as often as the team's gold allows.|minecraft:lever",
					"leave|Walk Away|Keep the gold.|minecraft:barrier");
			case 4 -> List.of("title|A Cursed Chest: it whispers your name",
					"cursed|Open It|A Legendary relic... and a Cursed one with it.|minecraft:ender_chest",
					"leave|Leave It Shut|Some things should stay closed.|minecraft:barrier");
			case 5 -> List.of("title|The Fountain of Wishes",
					"wish_relic|Wish on a Relic|Throw in one of your relics (at random). Get a random one a tier better.|minecraft:heart_of_the_sea",
					"wish_gold|Wish on Gold (40)|Everyone on the team is healed to full.|minecraft:gold_nugget",
					"leave|Walk Away|No wishes today.|minecraft:barrier");
			case 6 -> List.of("title|A Wandering Hero offers his sword",
					"hero|Hire Him (50 gold)|He fights beside the team for the next three rooms.|minecraft:netherite_axe",
					"hero_charm|Wish Him Well|He gives you a charm for the road.|minecraft:honey_bottle");
			case 7 -> List.of("title|Three Doors. Only one is good.",
					"door_l|The Left Door|Who knows?|minecraft:oak_door",
					"door_m|The Middle Door|Who knows?|minecraft:iron_door",
					"door_r|The Right Door|Who knows?|minecraft:crimson_door");
			case 8 -> List.of("title|Double or Nothing",
					"double|Raise the Stakes|The next fight has half again as many monsters, and pays triple gold.|minecraft:tnt",
					"leave|Fold|Keep it simple.|minecraft:barrier");
			case 9 -> List.of("title|The Shrine of Blessings (for the whole run)",
					"bless_speed|Blessing of Speed|You move 20% faster.|minecraft:sugar",
					"bless_health|Blessing of Life|Four more hearts.|minecraft:golden_apple",
					"bless_power|Blessing of Power|You deal 15% more damage.|minecraft:blaze_powder");
			case 10 -> List.of("title|The Relic Trader: two for one",
					"swap|Trade Your Newest Relic|Give it up for two random relics of the same tier.|minecraft:emerald",
					"leave|No Deal|Keep what you've got.|minecraft:barrier");
			case 11 -> List.of("title|A Treasure Goblin! It's getting away with the loot!",
					"goblin|Chase It!|Catch it within 20 seconds for 100 gold and a charm (it blinks away when hurt).|minecraft:gold_block",
					"leave|Let It Go|It's quick...|minecraft:barrier");
			case 12 -> List.of("title|The Shrine of Echoes",
					"echo_relic|Echo a Relic|One of your relics (at random) gains a star.|minecraft:echo_shard",
					"echo_charms|Echo of Luck|Two charms.|minecraft:amethyst_shard");
			default -> List.of("title|A Lost Supply Cache",
					"gold|Take the Gold|80 gold for the team.|minecraft:gold_ingot",
					"relic|Take the Strange Device|A free Common relic.|minecraft:compass");
		};
	}

	static void resolve(ServerPlayer p, Rift.Runner r, String option) {
		ServerLevel level = (ServerLevel) p.level();
		RandomSource random = level.getRandom();
		switch (option) {
			case "blood" -> {
				p.setHealth(Math.max(1, p.getHealth() / 2));
				RiftFx.burst(level, p.position().add(0, 1, 0), 0xFFB00020, 30, 0.6);
				level.playSound(null, p.blockPosition(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 1.0F, 1.0F);
				grant(p, r, Relic.Tier.RARE, random);
			}
			case "gamble" -> {
				boolean win = random.nextBoolean();
				level.playSound(null, p.blockPosition(), win ? SoundEvents.PLAYER_LEVELUP : SoundEvents.WITHER_AMBIENT, SoundSource.PLAYERS, 1.0F, 1.0F);
				p.sendSystemMessage(Component.literal(win ? "The wheel stops on gold!" : "The wheel stops on black...")
						.withStyle(win ? ChatFormatting.GOLD : ChatFormatting.DARK_PURPLE));
				grant(p, r, win ? Relic.Tier.LEGENDARY : Relic.Tier.CURSED, random);
			}
			case "gold" -> {
				Rift.gold += 80;
				level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.4F);
				RiftFx.burst(level, p.position().add(0, 1, 0), 0xFFFFC02E, 24, 0.6);
			}
			case "relic" -> grant(p, r, Relic.Tier.COMMON, random);
			case "slots" -> slots(p, r, level, random);
			case "cursed" -> {
				level.playSound(null, p.blockPosition(), SoundEvents.ENDER_CHEST_OPEN, SoundSource.PLAYERS, 1.0F, 0.6F);
				level.playSound(null, p.blockPosition(), SoundEvents.WITCH_CELEBRATE, SoundSource.PLAYERS, 1.0F, 0.6F);
				level.sendParticles(ParticleTypes.WITCH, p.getX(), p.getY() + 1, p.getZ(), 40, 0.6, 0.8, 0.6, 0.2);
				grant(p, r, Relic.Tier.LEGENDARY, random);
				grant(p, r, Relic.Tier.CURSED, random);
			}
			case "wish_relic" -> {
				List<Relic> mine = r.relics.stream().filter(x -> x.tier != Relic.Tier.BOND && x.tier != Relic.Tier.CURSED).toList();
				if (mine.isEmpty()) {
					p.sendSystemMessage(Component.literal("You have no relic to throw in... the fountain gives you one anyway.").withStyle(ChatFormatting.AQUA));
					grant(p, r, Relic.Tier.COMMON, random);
					return;
				}
				Relic gone = mine.get(random.nextInt(mine.size()));
				r.relics.remove(gone);
				r.levels.remove(gone);
				RelicEffects.apply(p, r);
				level.sendParticles(ParticleTypes.SPLASH, p.getX(), p.getY() + 0.5, p.getZ(), 60, 1, 0.3, 1, 0.3);
				level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 1.0F, 1.2F);
				p.sendSystemMessage(Component.literal("Your " + gone.title + " sinks into the fountain...").withStyle(ChatFormatting.AQUA));
				grant(p, r, gone.tier == Relic.Tier.COMMON ? Relic.Tier.RARE : Relic.Tier.LEGENDARY, random);
			}
			case "wish_gold" -> {
				if (Rift.gold < 40) {
					p.sendSystemMessage(Component.literal("The team doesn't have 40 gold.").withStyle(ChatFormatting.RED));
					return;
				}
				Rift.gold -= 40;
				for (ServerPlayer mate : Rift.players(level.getServer())) {
					mate.setHealth(mate.getMaxHealth());
					level.sendParticles(ParticleTypes.HEART, mate.getX(), mate.getY() + 2, mate.getZ(), 6, 0.4, 0.3, 0.4, 0);
				}
				level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 1.0F, 1.4F);
			}
			case "hero" -> {
				if (Rift.gold < 50) {
					p.sendSystemMessage(Component.literal("The team doesn't have 50 gold.").withStyle(ChatFormatting.RED));
					return;
				}
				Rift.gold -= 50;
				Rift.heroRooms = Math.max(Rift.heroRooms, 3);
				Rift.tell(level.getServer(), Component.literal("Sir Ruby, the Wandering Hero, joins the team for three rooms!").withStyle(ChatFormatting.GOLD));
				level.playSound(null, p.blockPosition(), SoundEvents.VINDICATOR_CELEBRATE, SoundSource.PLAYERS, 1.0F, 1.0F);
			}
			case "hero_charm" -> RiftCharms.give(p, RiftCharms.Charm.values()[random.nextInt(RiftCharms.Charm.values().length)]);
			case "door_l", "door_m", "door_r" -> door(p, r, level, random);
			case "double" -> {
				Rift.doubleNext = true;
				level.playSound(null, p.blockPosition(), SoundEvents.TNT_PRIMED, SoundSource.PLAYERS, 1.0F, 0.8F);
				Rift.tell(level.getServer(), Component.literal(p.getName().getString() + " raised the stakes: the next fight is bigger, and pays triple!")
						.withStyle(ChatFormatting.RED));
			}
			case "bless_speed", "bless_health", "bless_power" -> {
				r.blessings.add(option.substring(6));
				RelicEffects.apply(p, r);
				RiftFx.beam(level, p.position(), 12, ParticleTypes.END_ROD, 0xFFFFE680);
				level.playSound(null, p.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.0F, 1.2F);
				p.sendSystemMessage(Component.literal("Blessed for the rest of the run.").withStyle(ChatFormatting.GOLD));
			}
			case "swap" -> {
				Relic newest = null;
				for (Relic x : r.relics) {
					if (x.tier != Relic.Tier.BOND) {
						newest = x;
					}
				}
				if (newest == null) {
					p.sendSystemMessage(Component.literal("You have nothing to trade. The trader shrugs.").withStyle(ChatFormatting.GRAY));
					return;
				}
				r.relics.remove(newest);
				r.levels.remove(newest);
				RelicEffects.apply(p, r);
				p.sendSystemMessage(Component.literal("You trade away your " + newest.title + "...").withStyle(ChatFormatting.GREEN));
				level.playSound(null, p.blockPosition(), SoundEvents.VILLAGER_TRADE, SoundSource.PLAYERS, 1.0F, 1.0F);
				grant(p, r, newest.tier, random);
				grant(p, r, newest.tier, random);
			}
			case "goblin" -> {
				if (!goblinOut) {
					goblinOut = true;
					spawnGoblin(level, p);
				}
			}
			case "echo_relic" -> {
				List<Relic> can = r.relics.stream().filter(r::canCombine).toList();
				if (can.isEmpty()) {
					p.sendSystemMessage(Component.literal("Nothing to echo yet: two charms instead.").withStyle(ChatFormatting.LIGHT_PURPLE));
					resolve(p, r, "echo_charms");
					return;
				}
				Rift.give(p, r, can.get(random.nextInt(can.size())));
			}
			case "echo_charms" -> {
				for (int k = 0; k < 2; k++) {
					RiftCharms.give(p, RiftCharms.Charm.values()[random.nextInt(RiftCharms.Charm.values().length)]);
				}
			}
			default -> {
			}
		}
	}

	/** The Ruby Slots: three reels, one result. */
	private static void slots(ServerPlayer p, Rift.Runner r, ServerLevel level, RandomSource random) {
		if (Rift.gold < 30) {
			p.sendSystemMessage(Component.literal("The team doesn't have 30 gold.").withStyle(ChatFormatting.RED));
			return;
		}
		Rift.gold -= 30;
		int roll = random.nextInt(100);
		String[] faces = {"♦", "★", "♣", "7", "♥"};
		String reels;
		level.playSound(null, p.blockPosition(), SoundEvents.UI_LOOM_SELECT_PATTERN, SoundSource.PLAYERS, 1.0F, 1.6F);
		if (roll < 28) {
			reels = faces[random.nextInt(5)] + " " + faces[random.nextInt(5)] + " " + faces[(random.nextInt(4) + 1) % 5];
			dev.rubymod.game.Alerts.title(p, reels, "Nothing. Try again?", ChatFormatting.GRAY);
		} else if (roll < 55) {
			reels = "♦ ♦ ♣";
			Rift.gold += 60;
			dev.rubymod.game.Alerts.title(p, reels, "+60 gold!", ChatFormatting.YELLOW);
		} else if (roll < 75) {
			reels = "♣ ♣ ♣";
			dev.rubymod.game.Alerts.title(p, reels, "A charm!", ChatFormatting.LIGHT_PURPLE);
			RiftCharms.give(p, RiftCharms.Charm.values()[random.nextInt(RiftCharms.Charm.values().length)]);
		} else if (roll < 89) {
			reels = "★ ★ ★";
			dev.rubymod.game.Alerts.title(p, reels, "A Rare relic!", ChatFormatting.AQUA);
			grant(p, r, Relic.Tier.RARE, random);
		} else if (roll < 96) {
			reels = "♦ ♦ ♦";
			Rift.gold += 150;
			dev.rubymod.game.Alerts.title(p, reels, "JACKPOT! +150 gold!", ChatFormatting.GOLD);
			Rift.fireworks(level, p.position(), 3);
		} else {
			reels = "7 7 7";
			dev.rubymod.game.Alerts.title(p, reels, "LEGENDARY!", ChatFormatting.GOLD);
			grant(p, r, Relic.Tier.LEGENDARY, random);
			Rift.fireworks(level, p.position(), 4);
		}
		level.sendParticles(ParticleTypes.WAX_ON, p.getX(), p.getY() + 1.5, p.getZ(), 20, 0.5, 0.5, 0.5, 0.5);
		// Another pull?
		if (Rift.gold >= 30) {
			r.event = options(3);
			dev.rubymod.net.Net.send(p, new dev.rubymod.net.MorePayloads.RiftScreen("event", r.event));
		}
	}

	/** Three Doors: a random fate behind whichever you pick. */
	private static void door(ServerPlayer p, Rift.Runner r, ServerLevel level, RandomSource random) {
		int fate = random.nextInt(6);
		level.playSound(null, p.blockPosition(), SoundEvents.WOODEN_DOOR_OPEN, SoundSource.PLAYERS, 1.0F, 0.8F);
		switch (fate) {
			case 0 -> {
				dev.rubymod.game.Alerts.title(p, "TREASURE!", "+100 gold", ChatFormatting.GOLD);
				Rift.gold += 100;
			}
			case 1 -> {
				dev.rubymod.game.Alerts.title(p, "A RELIC!", "Something rare behind the door", ChatFormatting.AQUA);
				grant(p, r, Relic.Tier.RARE, random);
			}
			case 2 -> {
				dev.rubymod.game.Alerts.title(p, "A CHARM!", "Tucked behind the door", ChatFormatting.LIGHT_PURPLE);
				RiftCharms.give(p, RiftCharms.Charm.values()[random.nextInt(RiftCharms.Charm.values().length)]);
			}
			case 3 -> {
				dev.rubymod.game.Alerts.title(p, "A TRAP!", "Spikes! (but you got through)", ChatFormatting.RED);
				p.setHealth(Math.max(2, p.getHealth() - p.getMaxHealth() * 0.3F));
				level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_HURT_SWEET_BERRY_BUSH, SoundSource.PLAYERS, 1.0F, 0.8F);
			}
			case 4 -> {
				dev.rubymod.game.Alerts.title(p, "A CURSE!", "Something dark followed you out", ChatFormatting.DARK_PURPLE);
				grant(p, r, Relic.Tier.CURSED, random);
			}
			default -> {
				dev.rubymod.game.Alerts.title(p, "THE GOOD DOOR!", "A Legendary relic!", ChatFormatting.GOLD);
				grant(p, r, Relic.Tier.LEGENDARY, random);
			}
		}
	}

	private static void spawnGoblin(ServerLevel level, ServerPlayer p) {
		Mob g = EntityTypes.RABBIT.create(level, EntitySpawnReason.EVENT);
		if (g == null) {
			return;
		}
		Vec3 at = p.position().add(p.getLookAngle().multiply(1, 0, 1).normalize().scale(4));
		g.snapTo(at.x, p.getY(), at.z, 0, 0);
		AttributeInstance hp = g.getAttribute(Attributes.MAX_HEALTH);
		if (hp != null) {
			hp.setBaseValue(36);
			g.setHealth(36);
		}
		AttributeInstance scale = g.getAttribute(Attributes.SCALE);
		if (scale != null) {
			scale.setBaseValue(1.6);
		}
		g.addEffect(new MobEffectInstance(MobEffects.SPEED, -1, 3, false, false));
		g.addEffect(new MobEffectInstance(MobEffects.GLOWING, -1, 0, false, false));
		g.setCustomName(Component.literal("Treasure Goblin").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		g.addTag(Rift.TAG);
		level.addFreshEntity(g);
		goblin = g.getUUID();
		goblinGone = level.getGameTime() + 400;
		level.playSound(null, g.blockPosition(), SoundEvents.PIGLIN_CELEBRATE, SoundSource.HOSTILE, 1.5F, 1.6F);
		Rift.tell(level.getServer(), Component.literal("A Treasure Goblin! Catch it before it gets away (20 seconds)!").withStyle(ChatFormatting.GOLD));
	}

	/** Every tick: the Treasure Goblin's gold trail, its blinks, and its escape. */
	static void tick(ServerLevel level) {
		if (goblin == null) {
			return;
		}
		Entity e = level.getEntity(goblin);
		if (!(e instanceof LivingEntity g) || !g.isAlive()) {
			if (e == null) {
				goblin = null;
			}
			return;
		}
		long now = level.getGameTime();
		if (now % 2 == 0) {
			level.sendParticles(ParticleTypes.WAX_ON, g.getX(), g.getY() + 0.4, g.getZ(), 2, 0.2, 0.2, 0.2, 0.05);
		}
		if (g.hurtTime == 9 && level.getRandom().nextFloat() < 0.5F && Rift.room != null) {
			// Hurt: it blinks somewhere else in the room.
			var spots = Rift.room.spawns;
			var to = spots.get(level.getRandom().nextInt(spots.size()));
			level.sendParticles(ParticleTypes.PORTAL, g.getX(), g.getY() + 0.5, g.getZ(), 30, 0.3, 0.4, 0.3, 0.5);
			g.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
			level.playSound(null, g.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 1.8F);
		}
		if (now >= goblinGone) {
			level.sendParticles(ParticleTypes.POOF, g.getX(), g.getY() + 0.5, g.getZ(), 30, 0.3, 0.3, 0.3, 0.05);
			level.playSound(null, g.blockPosition(), SoundEvents.PIGLIN_RETREAT, SoundSource.HOSTILE, 1.5F, 1.6F);
			g.discard();
			goblin = null;
			Rift.tell(level.getServer(), Component.literal("The Treasure Goblin got away!").withStyle(ChatFormatting.GRAY));
		}
	}

	/** Something died: was it the Treasure Goblin? */
	public static void onDeath(LivingEntity dead, DamageSource source) {
		if (goblin == null || !dead.getUUID().equals(goblin) || !(dead.level() instanceof ServerLevel level)) {
			return;
		}
		goblin = null;
		Rift.gold += 100;
		Vec3 at = dead.position();
		level.sendParticles(ParticleTypes.WAX_ON, at.x, at.y + 0.5, at.z, 80, 0.6, 0.6, 0.6, 1.0);
		RiftFx.burst(level, at.add(0, 0.5, 0), 0xFFFFC02E, 50, 0.8);
		level.playSound(null, dead.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0F, 1.4F);
		Rift.tell(level.getServer(), Component.literal("Caught the Treasure Goblin! +100 gold").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		if (source.getEntity() instanceof ServerPlayer killer) {
			RiftCharms.give(killer, RiftCharms.Charm.values()[level.getRandom().nextInt(RiftCharms.Charm.values().length)]);
		}
	}

	static void clear(ServerLevel level) {
		if (goblin != null && level.getEntity(goblin) instanceof Entity g) {
			g.discard();
		}
		goblin = null;
	}

	private static void grant(ServerPlayer p, Rift.Runner r, Relic.Tier tier, RandomSource random) {
		List<Relic> pool = new ArrayList<>(java.util.Arrays.stream(Relic.values()).filter(x -> x.tier == tier && !r.has(x)).toList());
		if (!pool.isEmpty()) {
			Rift.give(p, r, pool.get(random.nextInt(pool.size())));
		}
	}
}
