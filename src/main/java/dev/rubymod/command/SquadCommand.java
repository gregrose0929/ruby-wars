package dev.rubymod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import dev.rubymod.game.SeasonData;
import dev.rubymod.game.Squads;
import dev.rubymod.game.Waypoints;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /squad create|invite|join|leave|kick|list, /sc <message> (squad chat), and
 * /waypoint add|remove|list.
 */
public final class SquadCommand {
	private SquadCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("squad")
				.executes(SquadCommand::list)
				.then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.greedyString())
						.executes(c -> reply(c, Squads.create(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")),
								"Squad created. Invite players with /squad invite <player>."))))
				.then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player())
						.executes(c -> reply(c, Squads.invite(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player")),
								"Invite sent."))))
				.then(Commands.literal("join").then(Commands.argument("name", StringArgumentType.greedyString())
						.executes(c -> reply(c, Squads.join(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")),
								"You joined the squad."))))
				.then(Commands.literal("leave").executes(c -> reply(c, Squads.leave(c.getSource().getPlayerOrException()), "You left your squad.")))
				.then(Commands.literal("kick").then(Commands.argument("name", StringArgumentType.word())
						.executes(c -> reply(c, Squads.kick(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")), "Done."))))
				.then(Commands.literal("list").executes(SquadCommand::list)));
		dispatcher.register(Commands.literal("sc").then(Commands.argument("message", StringArgumentType.greedyString())
				.executes(c -> {
					String error = Squads.chat(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "message"));
					if (error != null) {
						c.getSource().sendFailure(Component.literal(error));
						return 0;
					}
					return 1;
				})));
		dispatcher.register(Commands.literal("waypoint")
				.executes(SquadCommand::waypoints)
				.then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.greedyString())
						.executes(c -> reply(c, Waypoints.add(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")),
								"Waypoint added. It shows on your map and HUD."))))
				.then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.greedyString())
						.executes(c -> Waypoints.remove(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name"))
								? reply(c, null, "Waypoint removed.") : reply(c, "No waypoint with that name.", ""))))
				.then(Commands.literal("list").executes(SquadCommand::waypoints)));
	}

	private static int reply(CommandContext<CommandSourceStack> c, String error, String success) {
		if (error != null) {
			c.getSource().sendFailure(Component.literal(error));
			return 0;
		}
		c.getSource().sendSuccess(() -> Component.literal(success).withStyle(ChatFormatting.AQUA), false);
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> c) {
		var squads = SeasonData.get().squads.values();
		if (squads.isEmpty()) {
			c.getSource().sendSuccess(() -> Component.literal("No squads yet. Make one: /squad create <name>"), false);
			return 1;
		}
		for (Squads.Squad squad : squads) {
			StringBuilder names = new StringBuilder();
			for (String id : squad.members) {
				SeasonData.PlayerRecord r = SeasonData.get().players.get(id);
				names.append(names.isEmpty() ? "" : ", ").append(r != null ? r.name : "?").append(id.equals(squad.leader) ? " (leader)" : "");
			}
			c.getSource().sendSuccess(() -> Component.literal(squad.name + ": " + names).withStyle(ChatFormatting.AQUA), false);
		}
		return 1;
	}

	private static int waypoints(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		var list = Waypoints.list(player);
		if (list.isEmpty()) {
			c.getSource().sendSuccess(() -> Component.literal("No waypoints. Add one where you stand: /waypoint add <name>. Press B to ping a spot."),
					false);
		}
		for (Waypoints.Waypoint w : list) {
			c.getSource().sendSuccess(() -> Component.literal(w.name + ": " + w.x + " " + w.y + " " + w.z).withStyle(ChatFormatting.GRAY), false);
		}
		return 1;
	}
}
