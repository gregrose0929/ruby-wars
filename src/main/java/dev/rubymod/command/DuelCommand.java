package dev.rubymod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.rubymod.game.Duels;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;

/**
 * /duel guns|vehicles|planes <player> [kills]: challenge someone to a 1v1 test duel
 * (first to 10 kills unless you pick another number). /duel accept, /duel decline,
 * /duel leave, /duel list.
 */
public final class DuelCommand {
	private DuelCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("duel").executes(DuelCommand::help);
		for (Duels.Mode mode : Duels.Mode.values()) {
			root.then(Commands.literal(mode.id)
					.then(Commands.literal("bot")
							.executes(c -> result(c, Duels.startBot(c.getSource().getPlayerOrException(), mode, 10), null))
							.then(Commands.argument("kills", IntegerArgumentType.integer(1, 100))
									.executes(c -> result(c, Duels.startBot(c.getSource().getPlayerOrException(), mode,
											IntegerArgumentType.getInteger(c, "kills")), null))))
					.then(Commands.argument("player", EntityArgument.player())
							.executes(c -> challenge(c, mode, 10))
							.then(Commands.argument("kills", IntegerArgumentType.integer(1, 100))
									.executes(c -> challenge(c, mode, IntegerArgumentType.getInteger(c, "kills"))))));
		}
		root.then(Commands.literal("accept").executes(c -> result(c, Duels.accept(c.getSource().getPlayerOrException()), null)));
		root.then(Commands.literal("decline").executes(c -> result(c, Duels.decline(c.getSource().getPlayerOrException()), "Challenge declined.")));
		root.then(Commands.literal("leave").executes(c -> result(c, Duels.leave(c.getSource().getPlayerOrException()), null)));
		root.then(Commands.literal("list").executes(c -> {
			var lines = Duels.status();
			if (lines.isEmpty()) {
				c.getSource().sendSystemMessage(Component.literal("No duels going on.").withStyle(ChatFormatting.GRAY));
			}
			for (String line : lines) {
				c.getSource().sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.GOLD));
			}
			return lines.size();
		}));
		dispatcher.register(root);
	}

	private static int challenge(CommandContext<CommandSourceStack> c, Duels.Mode mode, int kills) throws CommandSyntaxException {
		var from = c.getSource().getPlayerOrException();
		var to = EntityArgument.getPlayer(c, "player");
		return result(c, Duels.challenge(from, to, mode, kills), "Challenge sent to " + to.getName().getString() + ". They click ACCEPT in chat (or type /duel accept).");
	}

	private static int result(CommandContext<CommandSourceStack> c, String error, String success) {
		if (error != null) {
			c.getSource().sendFailure(Component.literal(error));
			return 0;
		}
		if (success != null) {
			c.getSource().sendSuccess(() -> Component.literal(success).withStyle(ChatFormatting.GREEN), false);
		}
		return 1;
	}

	private static int help(CommandContext<CommandSourceStack> c) {
		var source = c.getSource();
		source.sendSystemMessage(Component.literal("1v1 test duels (first to 10 kills, respawn after 3s, your own gear back afterwards)")
				.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		for (Duels.Mode mode : Duels.Mode.values()) {
			source.sendSystemMessage(Component.literal("/duel " + mode.id + " <player> [kills]").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(": " + Duels.kitSummary(mode)).withStyle(ChatFormatting.GRAY)));
		}
		source.sendSystemMessage(Component.literal("/duel guns bot [kills]").withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(": practice against an AI soldier").withStyle(ChatFormatting.GRAY)));
		source.sendSystemMessage(Component.literal("/duel accept, /duel decline, /duel leave, /duel list").withStyle(ChatFormatting.YELLOW));
		return 1;
	}
}
