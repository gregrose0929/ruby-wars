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
import dev.rubymod.game.Bounties;
import dev.rubymod.game.GameConfig;
import dev.rubymod.game.Phase;
import dev.rubymod.game.SeasonData;
import dev.rubymod.game.SeasonTicker;
import dev.rubymod.gun.GunItem;
import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/**
 * /rubywars — everything for running a season.
 *
 * Everyone: status, heart, trust, untrust, champions.
 * Admins: start, phase, timer, pause, resume, reload, backup, revive, reset, kit, selftest.
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
				.then(Commands.literal("bounties").executes(RubyWarsCommand::bounties))
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
				.then(Commands.literal("kit").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(RubyWarsCommand::kit))
				.then(Commands.literal("rearm").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
					if (c.getSource().getPlayerOrException().getVehicle() instanceof dev.rubymod.entity.AircraftEntity aircraft) {
						aircraft.rearm();
						return ok(c, "Rearmed and repaired.");
					}
					c.getSource().sendFailure(Component.literal("Get in an aircraft first."));
					return 0;
				}))
				.then(Commands.literal("training").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
					dev.rubymod.game.TrainingGround.build(c.getSource().getLevel(), net.minecraft.core.BlockPos.containing(c.getSource().getPosition()));
					return ok(c, "Training ground built: gun range to the west, vehicle yard to the east, airfield to the south.");
				}))
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

	private static int bounties(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		if (SeasonData.get().phase != Phase.BUILD) {
			c.getSource().sendSystemMessage(Component.literal("Bounties run during the build phase."));
			return 0;
		}
		c.getSource().sendSystemMessage(Component.literal("Today's bounties (day " + SeasonTicker.currentDay() + "):").withStyle(ChatFormatting.AQUA));
		for (Bounties.Bounty bounty : Bounties.today(player)) {
			c.getSource().sendSystemMessage(Component.literal(" - " + bounty.describe())
					.withStyle(bounty.done ? ChatFormatting.GREEN : ChatFormatting.WHITE));
		}
		return 1;
	}

	/** Admin testing kit: every gun loaded, ammo, raid gear, attachments, vehicles, and a full set of ruby armor. */
	private static int kit(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		Item[] guns = {ModItems.SERVICE_PISTOL, ModItems.COMBAT_SHOTGUN, ModItems.ARR_RIFLE, ModItems.LONGSHOT_SNIPER,
				ModItems.RUBY_BLASTER};
		for (Item gun : guns) {
			ItemStack stack = new ItemStack(gun);
			stack.set(ModComponents.AMMO, ((GunItem) gun).stats().magazine());
			give(player, stack);
		}
		give(player, new ItemStack(ModItems.ROCKET_LAUNCHER));
		give(player, new ItemStack(ModItems.FRAG_GRENADE, 16));
		Object[][] stacks = {
				{ModItems.LIGHT_AMMO, 64}, {ModItems.HEAVY_AMMO, 64}, {ModItems.SHOTGUN_SHELL, 64}, {ModItems.SNIPER_ROUND, 64},
				{ModItems.RUBY_CELL, 64}, {ModItems.ROCKET, 16}, {ModItems.SMOKE_GRENADE, 16}, {ModItems.AIR_STRIKE_FLARE, 1},
				{ModItems.RANGEFINDER, 1}, {ModItems.BREACHING_CHARGE, 8}, {ModItems.MORTAR_SHELL, 16}, {ModItems.CRUISE_MISSILE, 1},
				{ModItems.EXTENDED_MAG, 4}, {ModItems.RED_DOT_SIGHT, 4}, {ModItems.VERTICAL_GRIP, 4}, {ModItems.SUPPRESSOR, 4},
				{ModItems.JEEP, 1}, {ModItems.TANK, 1}, {ModItems.FIGHTER_JET, 1}, {ModItems.ATTACK_JET, 1}, {ModItems.BOMBER, 1}, {ModItems.ATTACK_HELI, 1},
				{ModItems.SKYHAMMER, 1}, {ModItems.AA_MISSILE, 8}, {ModItems.LANCER, 1}, {ModItems.AT_MISSILE, 8}, {ModItems.C4, 8},
				{ModBlocks.AT_MINE, 4}, {ModBlocks.FLAK_CANNON, 1}, {ModBlocks.SAM_SITE, 1}, {ModBlocks.AIRFIELD_PAD, 1}, {ModItems.BUILDERS_WAND, 1}, {ModItems.EXCAVATOR, 1}, {ModItems.RUBY, 64},
				{ModBlocks.ARMORY, 1}, {ModBlocks.MORTAR, 1}, {ModBlocks.MISSILE_SILO, 1}};
		for (Object[] entry : stacks) {
			give(player, new ItemStack((net.minecraft.world.level.ItemLike) entry[0], (Integer) entry[1]));
		}
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.RUBY_HELMET));
		player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.RUBY_CHESTPLATE));
		player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModItems.RUBY_LEGGINGS));
		player.setItemSlot(EquipmentSlot.FEET, new ItemStack(ModItems.RUBY_BOOTS));
		return ok(c, "Kit delivered! Defenses and fortifications are in the Ruby Wars creative tab, or buy them at the Armory.");
	}

	private static void give(ServerPlayer player, ItemStack stack) {
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
		}
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
