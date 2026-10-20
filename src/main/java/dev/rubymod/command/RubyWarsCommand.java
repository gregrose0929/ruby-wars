package dev.rubymod.command;

import java.util.Locale;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.rubymod.game.Backups;
import dev.rubymod.game.BattleDirector;
import dev.rubymod.game.GameConfig;
import dev.rubymod.game.Phase;
import dev.rubymod.game.SeasonData;
import dev.rubymod.game.SeasonTicker;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * /rubywars — everything for running a season.
 *
 * Everyone: status, heart, trust, untrust, champions.
 * Admins: start, phase, timer, pause, resume, reload, backup, revive, reset.
 */
public final class RubyWarsCommand {
	private RubyWarsCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("rubywars")
				.executes(RubyWarsCommand::status)
				.then(Commands.literal("status").executes(RubyWarsCommand::status))
				.then(Commands.literal("heart").executes(RubyWarsCommand::heart))
				.then(Commands.literal("champions").executes(RubyWarsCommand::champions))
				.then(Commands.literal("trust").then(Commands.argument("player", EntityArgument.player()).executes(c -> trust(c, true))))
				.then(Commands.literal("untrust").then(Commands.argument("player", EntityArgument.player()).executes(c -> trust(c, false))))
				.then(Commands.literal("start").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(RubyWarsCommand::start))
				.then(Commands.literal("phase").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("phase", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(
										java.util.Arrays.stream(Phase.values()).map(p -> p.name().toLowerCase(Locale.ROOT)), b))
								.executes(RubyWarsCommand::phase)))
				.then(Commands.literal("timer").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("minutes", IntegerArgumentType.integer(0)).executes(RubyWarsCommand::timer)))
				.then(Commands.literal("pause").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
					SeasonTicker.pause();
					return ok(c, "Season timer paused.");
				}))
				.then(Commands.literal("resume").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
					SeasonTicker.resume();
					return ok(c, "Season timer resumed.");
				}))
				.then(Commands.literal("reload").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
					GameConfig.load();
					return ok(c, "Reloaded config/rubywars.json.");
				}))
				.then(Commands.literal("backup").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c ->
						Backups.run(c.getSource().getServer()) ? ok(c, "Backup started. It'll be in the backups folder next to the world.")
								: ok(c, "A backup is already running.")))
				.then(Commands.literal("revive").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("player", EntityArgument.player()).executes(RubyWarsCommand::revive)))
				.then(Commands.literal("selftest").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(c -> SelfTest.run(c.getSource())))
				.then(Commands.literal("reset").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("confirm").executes(RubyWarsCommand::reset))));
	}

	private static int ok(CommandContext<CommandSourceStack> c, String message) {
		c.getSource().sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static String formatTime(long millis) {
		if (millis < 0) {
			return "no timer";
		}
		long s = millis / 1000;
		long d = s / 86400;
		long h = (s % 86400) / 3600;
		long m = (s % 3600) / 60;
		return d > 0 ? d + "d " + h + "h " + m + "m" : h > 0 ? h + "h " + m + "m" : m + "m " + (s % 60) + "s";
	}

	private static int status(CommandContext<CommandSourceStack> c) {
		SeasonData data = SeasonData.get();
		CommandSourceStack source = c.getSource();
		source.sendSystemMessage(Component.literal("Ruby Wars - Season " + data.season).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		String paused = data.pausedRemaining >= 0 ? " (paused)" : "";
		source.sendSystemMessage(Component.literal("Phase: " + data.phase.title() + "  |  Time left: " + formatTime(SeasonTicker.remainingMillis()) + paused));
		if (data.phase == Phase.BUILD) {
			source.sendSystemMessage(Component.literal("Day " + SeasonTicker.currentDay() + " of 14"));
		}
		if (data.phase.isBattle() || data.phase == Phase.ENDED) {
			source.sendSystemMessage(Component.literal("Players remaining: " + BattleDirector.aliveCount()));
		}
		if (!data.winner.isEmpty()) {
			source.sendSystemMessage(Component.literal("Champion: " + data.winner).withStyle(ChatFormatting.GOLD));
		}
		return 1;
	}

	private static int heart(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		SeasonData.Claim claim = SeasonData.get().claimOf(player.getUUID());
		if (claim == null) {
			c.getSource().sendSystemMessage(Component.literal("You haven't placed a Ruby Heart yet."));
			return 0;
		}
		if (claim.destroyed) {
			boolean battle = SeasonData.get().phase.isBattle() || SeasonData.get().phase == Phase.ENDED;
			c.getSource().sendSystemMessage(Component.literal(battle ? "Your Ruby Heart was destroyed." : "Your Ruby Heart isn't placed right now."));
			return 0;
		}
		StringBuilder trusted = new StringBuilder();
		for (String id : claim.trusted) {
			SeasonData.PlayerRecord r = SeasonData.get().player(UUID.fromString(id));
			trusted.append(r == null ? id : r.name).append(" ");
		}
		c.getSource().sendSystemMessage(Component.literal("Your Ruby Heart: " + claim.x + ", " + claim.y + ", " + claim.z
				+ "  |  Tier " + claim.tier + "  |  HP " + claim.hp + "/" + claim.maxHp() + "  |  Radius " + claim.radius()));
		c.getSource().sendSystemMessage(Component.literal("Trusted: " + (trusted.isEmpty() ? "nobody" : trusted.toString().trim())));
		return 1;
	}

	private static int champions(CommandContext<CommandSourceStack> c) {
		var list = SeasonData.get().pastChampions;
		if (list.isEmpty()) {
			c.getSource().sendSystemMessage(Component.literal("No champions yet. Will it be you?"));
		}
		for (String line : list) {
			c.getSource().sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.GOLD));
		}
		return 1;
	}

	private static int trust(CommandContext<CommandSourceStack> c, boolean add) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		ServerPlayer target = EntityArgument.getPlayer(c, "player");
		SeasonData.Claim claim = SeasonData.get().claimOf(player.getUUID());
		if (claim == null) {
			c.getSource().sendFailure(Component.literal("Place your Ruby Heart first."));
			return 0;
		}
		if (add) {
			claim.trusted.add(target.getUUID().toString());
		} else {
			claim.trusted.remove(target.getUUID().toString());
		}
		SeasonData.markDirty();
		return ok(c, (add ? "Trusted " : "Removed trust for ") + target.getName().getString() + ".");
	}

	private static int start(CommandContext<CommandSourceStack> c) {
		if (SeasonData.get().phase != Phase.LOBBY) {
			c.getSource().sendFailure(Component.literal("The season already started. Use /rubywars phase to jump around, or /rubywars reset confirm."));
			return 0;
		}
		BattleDirector.startSeason(c.getSource().getServer());
		return ok(c, "Season started! The build phase runs " + GameConfig.get().buildPhaseMinutes + " minutes.");
	}

	private static int phase(CommandContext<CommandSourceStack> c) {
		String name = StringArgumentType.getString(c, "phase").toUpperCase(Locale.ROOT);
		Phase phase;
		try {
			phase = Phase.valueOf(name);
		} catch (IllegalArgumentException e) {
			c.getSource().sendFailure(Component.literal("Unknown phase. Try: lobby, build, lockdown, war, sudden_death, final_circle, ended."));
			return 0;
		}
		if (phase == Phase.BUILD && SeasonData.get().phase == Phase.LOBBY) {
			BattleDirector.startSeason(c.getSource().getServer());
		} else {
			BattleDirector.enterPhase(c.getSource().getServer(), phase);
		}
		return ok(c, "Phase set to " + phase.title() + ".");
	}

	private static int timer(CommandContext<CommandSourceStack> c) {
		int minutes = IntegerArgumentType.getInteger(c, "minutes");
		if (SeasonData.get().phaseEndsAt == 0 && SeasonData.get().pausedRemaining < 0) {
			c.getSource().sendFailure(Component.literal("This phase has no timer."));
			return 0;
		}
		SeasonTicker.setRemaining(minutes * 60_000L);
		return ok(c, "The " + SeasonData.get().phase.title() + " now ends in " + minutes + " minutes.");
	}

	private static int revive(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer target = EntityArgument.getPlayer(c, "player");
		SeasonData.PlayerRecord record = SeasonData.get().player(target.getUUID(), target.getName().getString());
		record.eliminated = false;
		SeasonData.markDirty();
		target.setGameMode(GameType.SURVIVAL);
		return ok(c, target.getName().getString() + " is back in the game.");
	}

	private static int reset(CommandContext<CommandSourceStack> c) {
		SeasonData data = SeasonData.get();
		int nextSeason = data.season + 1;
		var champions = new java.util.ArrayList<>(data.pastChampions);
		data.claims.clear();
		data.players.clear();
		data.phase = Phase.LOBBY;
		data.phaseEndsAt = 0;
		data.pausedRemaining = -1;
		data.buildStartedAt = 0;
		data.winner = "";
		data.season = nextSeason;
		data.pastChampions = champions;
		SeasonData.save();
		GameConfig config = GameConfig.get();
		var border = c.getSource().getServer().overworld().getWorldBorder();
		border.setCenter(config.borderCenterX, config.borderCenterZ);
		border.setSize(config.borderStartSize);
		for (ServerPlayer player : c.getSource().getServer().getPlayerList().getPlayers()) {
			if (player.isSpectator()) {
				player.setGameMode(GameType.SURVIVAL);
			}
		}
		return ok(c, "Reset to the lobby for Season " + nextSeason + ". Run /rubywars start when everyone's ready.");
	}
}
