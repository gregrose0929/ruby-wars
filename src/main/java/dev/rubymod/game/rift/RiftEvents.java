package dev.rubymod.game.rift;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * Mystery rooms: a choice with a catch. A Blood Altar (half your health for a Rare
 * relic), a Gambler's Shrine (Legendary or Cursed, 50/50), or a Lost Supply Cache (gold
 * for the team, or a free Common relic). Each player chooses for themselves.
 */
final class RiftEvents {
	private RiftEvents() {
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
			default -> {
			}
		}
	}

	private static void grant(ServerPlayer p, Rift.Runner r, Relic.Tier tier, RandomSource random) {
		List<Relic> pool = java.util.Arrays.stream(Relic.values()).filter(x -> x.tier == tier && !r.has(x)).toList();
		if (!pool.isEmpty()) {
			Rift.give(p, r, pool.get(random.nextInt(pool.size())));
		}
	}
}
