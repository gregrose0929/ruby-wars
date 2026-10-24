package dev.rubymod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.rubymod.game.Hub;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /hub: back to the hub. /play rubywars|horde|hub: switch games (the same as the portals, or the H key).
 * /play horde easy|normal|hard starts Horde at that difficulty (or changes it before wave 1).
 */
public final class HubCommand {
	private HubCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("hub").executes(c -> go(c, "hub")));
		var play = Commands.literal("play").executes(HubCommand::list);
		for (dev.rubymod.game.party.PartyGame game : dev.rubymod.game.party.PartyGames.ALL) {
			play.then(Commands.literal(game.id).executes(c -> go(c, "game:" + game.id)));
		}
		dispatcher.register(play
				.then(Commands.literal("rubywars").executes(c -> go(c, "rubywars")))
				.then(Commands.literal("horde").executes(c -> go(c, "horde"))
						.then(Commands.literal("easy").executes(c -> go(c, "horde:easy")))
						.then(Commands.literal("normal").executes(c -> go(c, "horde:normal")))
						.then(Commands.literal("hard").executes(c -> go(c, "horde:hard"))))
				.then(Commands.literal("hub").executes(c -> go(c, "hub"))));
	}

	private static int go(CommandContext<CommandSourceStack> c, String mode) throws CommandSyntaxException {
		String error = Hub.go(c.getSource().getPlayerOrException(), mode);
		if (error != null) {
			c.getSource().sendFailure(Component.literal(error));
			return 0;
		}
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> c) {
		for (String line : Hub.modeLines(c.getSource().getServer())) {
			String[] f = line.split("[|]", 3);
			c.getSource().sendSystemMessage(Component.literal("/play " + f[0]).withStyle(ChatFormatting.GOLD)
					.append(Component.literal("  " + f[1] + ": " + f[2]).withStyle(ChatFormatting.GRAY)));
		}
		c.getSource().sendSystemMessage(Component.literal("/hub  back to the hub (or press H)").withStyle(ChatFormatting.GRAY));
		return 1;
	}
}
