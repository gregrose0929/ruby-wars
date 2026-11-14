package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.game.Alerts;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Quick games everyone in the hub can join, run by Coach Kit on the plaza: Tag, Hide and Seek, and King of the Castle.
 * Somebody picks one, everyone gets 20 seconds to come and join, then it runs for a few minutes with a timer bar at the
 * top of the screen. Just for fun: nothing is kept afterwards.
 */
final class HubPlay {
	private HubPlay() {
	}

	private enum Game {
		TAG("Tag", 120), HIDE("Hide and Seek", 180), KING("King of the Castle", 120);

		final String title;
		final int seconds;

		Game(String title, int seconds) {
			this.title = title;
			this.seconds = seconds;
		}
	}

	private static final Vec3 KIT = new Vec3(-3.5, FLOOR + 1, 11.5);
	private static final AABB CASTLE = new AABB(67, FLOOR, -1, 72, FLOOR + 6, 6);
	private static final Vec3 CASTLE_FRONT = new Vec3(64.5, FLOOR + 1, 2.5);

	private static Game game;
	/** Before it starts: ticks left to join. Then: ticks left to play. */
	private static int lobby;
	private static int left;
	private static final Set<UUID> PLAYERS = new LinkedHashSet<>();
	/** Tag: who's It, and how long each player has been It. Hide and Seek: the seekers. */
	private static final Set<UUID> IT = new LinkedHashSet<>();
	private static final Map<UUID, Integer> SCORE = new HashMap<>();
	private static final Map<UUID, Integer> SAFE = new HashMap<>();
	private static ServerBossEvent bar;
	private static int tick;

	static void build(ServerLevel level) {
		end(level, null);
		HubHosts.add(level, KIT, 180, VillagerProfession.WEAPONSMITH, VillagerType.TAIGA,
				new HubHosts.Host("games", "Coach Kit", "Group Games", 0xFFAA40, "minecraft:bell",
						"Quick games for everyone in the hub! Pick one and everybody gets 20 seconds to come and join. "
								+ "TAG: don't be It at the end. HIDE AND SEEK: hide anywhere in the park. KING OF THE CASTLE: hold the bouncy castle!",
						"Group games: right-click Coach Kit!", p -> {
							List<String> b = new ArrayList<>();
							if (game != null && lobby > 0) {
								b.add("join|Join " + game.title + "!");
							} else if (game != null) {
								b.add("busy|" + game.title + " is on (" + left / 20 + "s left)");
							} else {
								b.add("TAG|Tag (2 minutes)");
								b.add("HIDE|Hide and Seek (3 minutes)");
								b.add("KING|King of the Castle (2 minutes)");
							}
							return b;
						}, HubPlay::picked));
	}

	private static void picked(ServerPlayer p, String key) {
		ServerLevel level = (ServerLevel) p.level();
		if ("join".equals(key)) {
			join(level, p);
			return;
		}
		if (game != null) {
			return;
		}
		try {
			game = Game.valueOf(key);
		} catch (IllegalArgumentException e) {
			return;
		}
		lobby = 20 * 20;
		PLAYERS.clear();
		join(level, p);
		for (ServerPlayer q : level.players()) {
			q.sendSystemMessage(Component.literal("[Coach Kit] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
					.append(Component.literal(p.getName().getString() + " wants to play " + game.title + "! Right-click Coach Kit on the plaza in the next 20 seconds to join.")
							.withStyle(ChatFormatting.WHITE)));
			level.playSound(null, q.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.0F, 1.2F);
		}
	}

	private static void join(ServerLevel level, ServerPlayer p) {
		if (game == null || lobby <= 0) {
			return;
		}
		PLAYERS.add(p.getUUID());
		p.sendOverlayMessage(Component.literal("You're in! " + game.title + " starts soon.").withStyle(ChatFormatting.GOLD));
	}

	private static List<ServerPlayer> players(ServerLevel level) {
		List<ServerPlayer> out = new ArrayList<>();
		for (UUID id : PLAYERS) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
			if (p != null && p.level() == level) {
				out.add(p);
			}
		}
		return out;
	}

	private static void start(ServerLevel level) {
		List<ServerPlayer> ps = players(level);
		if (ps.size() < 2) {
			for (ServerPlayer p : ps) {
				p.sendSystemMessage(Component.literal("[Coach Kit] Not enough players this time. Find a friend and try again!").withStyle(ChatFormatting.GOLD));
			}
			end(level, null);
			return;
		}
		left = game.seconds * 20;
		bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(game.title), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
		IT.clear();
		SCORE.clear();
		SAFE.clear();
		for (ServerPlayer p : ps) {
			if (p.isPassenger()) {
				p.stopRiding();
			}
			bar.addPlayer(p);
			SCORE.put(p.getUUID(), 0);
		}
		var random = level.getRandom();
		ServerPlayer first = ps.get(random.nextInt(ps.size()));
		switch (game) {
			case TAG -> {
				it(first, true);
				for (ServerPlayer p : ps) {
					Alerts.title(p, "TAG!", p == first ? "You're IT! Punch someone to tag them" : first.getName().getString() + " is It. RUN!",
							ChatFormatting.GOLD);
				}
			}
			case HIDE -> {
				IT.add(first.getUUID());
				first.teleportTo(0.5, FLOOR + 1, 0.5);
				first.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 25 * 20, 0, false, false));
				first.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 25 * 20, 9, false, false));
				for (ServerPlayer p : ps) {
					Alerts.title(p, "HIDE!", p == first ? "You're the seeker. Count to 25..." : "Hide anywhere in the park! 25 seconds",
							ChatFormatting.GOLD);
				}
			}
			case KING -> {
				for (ServerPlayer p : ps) {
					p.teleportTo(CASTLE_FRONT.x + random.nextDouble() * 2 - 1, CASTLE_FRONT.y, CASTLE_FRONT.z + random.nextDouble() * 4 - 2);
					Alerts.title(p, "KING OF THE CASTLE", "Be the only one in the castle to score. Punch to push!", ChatFormatting.GOLD);
				}
			}
		}
		lobby = 0;
	}

	private static void it(ServerPlayer p, boolean on) {
		if (on) {
			IT.clear();
			IT.add(p.getUUID());
			p.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100000, 0, false, false));
		} else {
			p.removeEffect(MobEffects.GLOWING);
		}
	}

	/** Somebody punched somebody in the hub. True if it was part of a game. */
	static boolean punched(ServerPlayer attacker, Entity target) {
		if (game == null || lobby > 0 || !(target instanceof ServerPlayer victim) || !PLAYERS.contains(attacker.getUUID()) || !PLAYERS.contains(victim.getUUID())) {
			return false;
		}
		ServerLevel level = (ServerLevel) attacker.level();
		switch (game) {
			case TAG -> {
				if (IT.contains(attacker.getUUID()) && SAFE.getOrDefault(victim.getUUID(), 0) <= tick) {
					it(attacker, false);
					it(victim, true);
					SAFE.put(attacker.getUUID(), tick + 60);
					Alerts.title(victim, "YOU'RE IT!", "Tag someone else!", ChatFormatting.RED);
					attacker.sendOverlayMessage(Component.literal("Tagged " + victim.getName().getString() + "! Run!").withStyle(ChatFormatting.GREEN));
					level.playSound(null, victim.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0F, 1.5F);
				}
			}
			case HIDE -> {
				if (IT.contains(attacker.getUUID()) && !IT.contains(victim.getUUID()) && left < (game.seconds - 25) * 20) {
					IT.add(victim.getUUID());
					victim.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100000, 0, false, false));
					Alerts.title(victim, "FOUND!", "Now you're a seeker too", ChatFormatting.RED);
					for (ServerPlayer p : players(level)) {
						p.sendOverlayMessage(Component.literal(attacker.getName().getString() + " found " + victim.getName().getString() + "!").withStyle(ChatFormatting.GOLD));
					}
					level.playSound(null, victim.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0F, 1.2F);
				}
			}
			case KING -> {
				Vec3 push = victim.position().subtract(attacker.position()).multiply(1, 0, 1).normalize().scale(1.1).add(0, 0.45, 0);
				victim.setDeltaMovement(victim.getDeltaMovement().add(push));
				victim.syncVelocity = true;
				level.playSound(null, victim.blockPosition(), SoundEvents.SLIME_ATTACK, SoundSource.PLAYERS, 0.8F, 1.0F);
			}
		}
		return true;
	}

	static void tick(ServerLevel level) {
		tick++;
		if (game == null) {
			return;
		}
		if (lobby > 0) {
			if (--lobby <= 0) {
				start(level);
			} else if (lobby % 100 == 0) {
				for (ServerPlayer p : players(level)) {
					p.sendOverlayMessage(Component.literal(game.title + " starts in " + lobby / 20 + " seconds... (" + PLAYERS.size() + " playing)")
							.withStyle(ChatFormatting.GOLD));
				}
			}
			return;
		}
		List<ServerPlayer> ps = players(level);
		// Anyone who left the hub is out of the game.
		PLAYERS.removeIf(id -> ps.stream().noneMatch(p -> p.getUUID().equals(id)));
		if (ps.size() < 2) {
			end(level, "Everyone else left, so that's the end of the game!");
			return;
		}
		left--;
		bar.setProgress(Math.max(0, left / (float) (game.seconds * 20)));
		switch (game) {
			case TAG -> {
				for (UUID id : IT) {
					SCORE.merge(id, 1, Integer::sum);
				}
				if (IT.isEmpty() || !PLAYERS.containsAll(IT)) {
					ServerPlayer p = ps.get(level.getRandom().nextInt(ps.size()));
					it(p, true);
					Alerts.title(p, "YOU'RE IT!", "Tag someone!", ChatFormatting.RED);
				}
				String it = IT.isEmpty() ? "?" : name(level, IT.iterator().next());
				bar.setName(Component.literal("Tag  •  " + it + " is It  •  " + left / 20 + "s"));
			}
			case HIDE -> {
				boolean counting = left > (game.seconds - 25) * 20;
				int hiders = (int) ps.stream().filter(p -> !IT.contains(p.getUUID())).count();
				bar.setName(Component.literal(counting ? "Hide and Seek  •  hide! " + (left / 20 - (game.seconds - 25)) + "s"
						: "Hide and Seek  •  " + hiders + " still hidden  •  " + left / 20 + "s"));
				if (!counting && left % (20 * 30) == 0) {
					// Every half minute the hiders give themselves away with a squeak.
					for (ServerPlayer p : ps) {
						if (!IT.contains(p.getUUID())) {
							level.playSound(null, p.blockPosition(), SoundEvents.CHICKEN_HURT_BABY.value(), SoundSource.PLAYERS, 2.0F, 1.6F);
							p.sendOverlayMessage(Component.literal("Squeak! (the seekers heard that)").withStyle(ChatFormatting.YELLOW));
						}
					}
				}
				if (!counting && hiders == 0) {
					end(level, "Everyone was found! The seekers win!");
					return;
				}
			}
			case KING -> {
				List<ServerPlayer> inside = ps.stream().filter(p -> CASTLE.contains(p.position())).toList();
				if (inside.size() == 1 && tick % 20 == 0) {
					ServerPlayer king = inside.get(0);
					SCORE.merge(king.getUUID(), 1, Integer::sum);
					king.sendOverlayMessage(Component.literal("♚ You hold the castle! " + SCORE.get(king.getUUID())).withStyle(ChatFormatting.GOLD));
				}
				String king = inside.size() == 1 ? inside.get(0).getName().getString() + " is King" : inside.isEmpty() ? "the castle is empty" : "fight for the castle!";
				bar.setName(Component.literal("King of the Castle  •  " + king + "  •  " + left / 20 + "s"));
			}
		}
		if (left <= 0) {
			end(level, results(level));
		}
	}

	private static String name(ServerLevel level, UUID id) {
		ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
		return p == null ? "?" : p.getName().getString();
	}

	private static String results(ServerLevel level) {
		return switch (game) {
			case TAG -> {
				UUID best = null;
				for (UUID id : PLAYERS) {
					if (best == null || SCORE.getOrDefault(id, 0) < SCORE.getOrDefault(best, 0)) {
						best = id;
					}
				}
				yield "Time! " + (IT.isEmpty() ? "" : name(level, IT.iterator().next()) + " was It at the end. ") + "Least time as It: " + name(level, best) + "!";
			}
			case HIDE -> {
				List<String> left = new ArrayList<>();
				for (UUID id : PLAYERS) {
					if (!IT.contains(id)) {
						left.add(name(level, id));
					}
				}
				yield "Time! Never found: " + String.join(", ", left) + ". The hiders win!";
			}
			case KING -> {
				UUID best = null;
				for (UUID id : PLAYERS) {
					if (best == null || SCORE.getOrDefault(id, 0) > SCORE.getOrDefault(best, 0)) {
						best = id;
					}
				}
				yield "Time! " + name(level, best) + " is King of the Castle with " + SCORE.getOrDefault(best, 0) + " points!";
			}
		};
	}

	private static void end(ServerLevel level, String message) {
		for (ServerPlayer p : players(level)) {
			p.removeEffect(MobEffects.GLOWING);
			p.removeEffect(MobEffects.BLINDNESS);
			p.removeEffect(MobEffects.SLOWNESS);
			if (message != null) {
				p.sendSystemMessage(Component.literal("[Coach Kit] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
						.append(Component.literal(message).withStyle(ChatFormatting.WHITE)));
				Alerts.title(p, "Game over!", message.length() > 60 ? "" : message, ChatFormatting.GOLD);
				HubPark.burst(level, p.position().add(0, 3, 0));
			}
		}
		if (bar != null) {
			bar.removeAllPlayers();
			bar = null;
		}
		game = null;
		lobby = 0;
		PLAYERS.clear();
		IT.clear();
		SCORE.clear();
	}
}
