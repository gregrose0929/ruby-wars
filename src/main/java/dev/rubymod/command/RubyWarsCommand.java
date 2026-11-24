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
				.then(Commands.literal("leaderboard").executes(RubyWarsCommand::leaderboard))
				.then(Commands.literal("event").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("stop").executes(c -> {
							dev.rubymod.game.WorldEvents.stop(c.getSource().getServer());
							return ok(c, "World event stopped.");
						}))
						.then(Commands.literal("supplydrop").executes(c -> {
							var spot = dev.rubymod.game.SupplyDrops.incoming(c.getSource().getServer().overworld());
							return spot == null ? 0 : ok(c, "Supply drop coming down at " + spot.toShortString() + " (it only stays on Battle Day).");
						}))
						.then(Commands.literal("cache").executes(c -> worldEvent(c, dev.rubymod.game.WorldEvents.Kind.CACHE)))
						.then(Commands.literal("shrine").executes(c -> worldEvent(c, dev.rubymod.game.WorldEvents.Kind.SHRINE)))
						.then(Commands.literal("meteor").executes(c -> worldEvent(c, dev.rubymod.game.WorldEvents.Kind.METEOR))))
				.then(Commands.literal("research").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("points", IntegerArgumentType.integer(0))
								.executes(c -> {
									var target = EntityArgument.getPlayer(c, "player");
									dev.rubymod.game.Research.addPoints(target.getUUID(), IntegerArgumentType.getInteger(c, "points"));
									dev.rubymod.game.Research.sync(target);
									return ok(c, "Research points given.");
								}))))
				.then(Commands.literal("trust").then(Commands.argument("player", EntityArgument.player()).executes(c -> trust(c, true))))
				.then(Commands.literal("untrust").then(Commands.argument("player", EntityArgument.player()).executes(c -> trust(c, false))))
				.then(Commands.literal("start").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> start(c, 0))
						.then(Commands.literal("untilisay").executes(c -> start(c, 0)))
						.then(Commands.literal("1week").executes(c -> start(c, WEEK)))
						.then(Commands.literal("2weeks").executes(c -> start(c, 2 * WEEK)))
						.then(Commands.literal("days").then(Commands.argument("days", IntegerArgumentType.integer(1, 60))
								.executes(c -> start(c, IntegerArgumentType.getInteger(c, "days") * 1440L)))))
				.then(Commands.literal("schedule").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("untilisay").executes(c -> schedule(c, 0)))
						.then(Commands.literal("1week").executes(c -> schedule(c, WEEK)))
						.then(Commands.literal("2weeks").executes(c -> schedule(c, 2 * WEEK)))
						.then(Commands.literal("days").then(Commands.argument("days", IntegerArgumentType.integer(1, 60))
								.executes(c -> schedule(c, IntegerArgumentType.getInteger(c, "days") * 1440L)))))
				.then(Commands.literal("admin").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(RubyWarsCommand::admin))
				.then(Commands.literal("choose").then(Commands.argument("kind", com.mojang.brigadier.arguments.StringArgumentType.word())
						.then(Commands.argument("value", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> {
							String value = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "value");
							String error = dev.rubymod.game.SeasonChoices.choose(c.getSource().getPlayerOrException(),
									com.mojang.brigadier.arguments.StringArgumentType.getString(c, "kind"),
									value.equals("solo") || value.equals("none") ? "" : value);
							if (error != null) {
								c.getSource().sendFailure(Component.literal(error));
								return 0;
							}
							return 1;
						}))))
				.then(Commands.literal("recap").executes(c -> {
					String error = dev.rubymod.game.BattleDirector.sendRecap(c.getSource().getPlayerOrException());
					if (error != null) {
						c.getSource().sendFailure(Component.literal(error));
						return 0;
					}
					return 1;
				}))
				.then(Commands.literal("watch")
						.then(Commands.literal("next").executes(c -> watch(c, 1)))
						.then(Commands.literal("prev").executes(c -> watch(c, -1)))
						.then(Commands.literal("free").executes(c -> watch(c, 0))))
				.then(Commands.literal("ride").then(Commands.argument("spot", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 10))
						.executes(c -> {
							var game = (dev.rubymod.game.party.CoasterWorkshop) dev.rubymod.game.party.PartyGames.byId("coasters");
							String error = game.rideSpot(c.getSource().getPlayerOrException(),
									com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "spot"));
							if (error != null) {
								c.getSource().sendFailure(Component.literal(error));
								return 0;
							}
							return 1;
						})))
				.then(Commands.literal("coasters").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
					var game = (dev.rubymod.game.party.CoasterWorkshop) dev.rubymod.game.party.PartyGames.byId("coasters");
					for (String line : game.spots()) {
						c.getSource().sendSystemMessage(Component.literal(line));
					}
					c.getSource().sendSystemMessage(Component.literal("To give a spot up: /rubywars coasters free <name> (their coaster goes).")
							.withStyle(ChatFormatting.GRAY));
					return 1;
				})
						.then(Commands.literal("free").then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
							var game = (dev.rubymod.game.party.CoasterWorkshop) dev.rubymod.game.party.PartyGames.byId("coasters");
							String name = StringArgumentType.getString(c, "name");
							return game.free(name) ? ok(c, name + "'s spot in Ruby Coaster Park is free now.") : fail(c, name + " doesn't have a spot.");
						}))))
				.then(Commands.literal("battle").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(c -> battle(c, 60))
						.then(Commands.literal("cancel").executes(c -> SeasonTicker.cancelBattle(c.getSource().getServer())
								? ok(c, "Battle Day called off.") : fail(c, "No battle has been called.")))
						.then(Commands.argument("seconds", IntegerArgumentType.integer(5, 3600)).executes(c -> battle(c, IntegerArgumentType.getInteger(c, "seconds")))))
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
					// It flattens a huge area where you stand: never in the hub, the game arenas or the Ruby Wars map.
					var level = c.getSource().getLevel();
					if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD
							|| level.getChunkSource().getGenerator() instanceof dev.rubymod.world.WarzoneGenerator) {
						c.getSource().sendFailure(Component.literal("The training ground can only be built in a separate test world, "
								+ "not on the Ruby Wars map, the hub or a game arena (it flattens everything around you)."));
						return 0;
					}
					dev.rubymod.game.TrainingGround.build(c.getSource().getLevel(), net.minecraft.core.BlockPos.containing(c.getSource().getPosition()));
					return ok(c, "Training ground built: gun range to the west, vehicle yard to the east, airfield to the south.");
				}))
				.then(Commands.literal("nightbattle").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("on").executes(c -> nightBattle(c, true)))
						.then(Commands.literal("off").executes(c -> nightBattle(c, false))))
				.then(Commands.literal("selftest").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(c -> SelfTest.run(c.getSource()))
						.then(Commands.literal("prepare").executes(c -> SelfTest.prepare(c.getSource(), true)))
						.then(Commands.literal("done").executes(c -> SelfTest.prepare(c.getSource(), false))))
				.then(Commands.literal("reset").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("confirm").executes(RubyWarsCommand::reset))
						.then(Commands.literal("everything").then(Commands.literal("confirm").executes(c -> {
							dev.rubymod.game.FreshStart.resetHistory(c.getSource().getServer());
							ok(c, "Ruby Wars is back to Season 1: no champions, no season wins. Everyone starts fresh in Ruby Wars.");
							return ok(c, "To remake the island too, run on the server: rubywars newseason everything");
						})))));
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
			long length = SeasonTicker.durationOf(Phase.BUILD);
			source.sendSystemMessage(Component.literal("Day " + SeasonTicker.currentDay() + (length > 0 ? " of " + Math.max(1, length / 86_400_000L)
					: " of the build phase (it ends when an admin runs /rubywars battle)")));
			String event = dev.rubymod.game.WorldEvents.describe();
			source.sendSystemMessage(Component.literal(event.isEmpty() ? "No world event on right now (they come every so often while you play)"
					: "World event: " + event).withStyle(event.isEmpty() ? ChatFormatting.GRAY : ChatFormatting.AQUA));
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


	private static int watch(CommandContext<CommandSourceStack> c, int dir) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		String error = dev.rubymod.game.BattleDirector.watch(c.getSource().getPlayerOrException(), dir);
		if (error != null) {
			c.getSource().sendFailure(Component.literal(error));
			return 0;
		}
		return 1;
	}

	/** Night Battle on or off (saved): Battle Day fought at night, with searchlights and flares. */
	private static int nightBattle(CommandContext<CommandSourceStack> c, boolean on) {
		dev.rubymod.game.GameConfig.get().nightBattle = on;
		dev.rubymod.game.GameConfig.save();
		c.getSource().sendSystemMessage(Component.literal(on ? "Night Battle is on: Battle Day will be fought at night, with searchlights and flares."
				: "Night Battle is off: Battle Day by daylight.").withStyle(ChatFormatting.GOLD));
		return 1;
	}

	/** Admin: a world event now (told two minutes ahead, as usual), somewhere fair for everyone in Ruby Wars. */
	private static int worldEvent(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c, dev.rubymod.game.WorldEvents.Kind kind) {
		var server = c.getSource().getServer();
		var players = dev.rubymod.game.WorldEvents.inRubyWars(server);
		if (players.isEmpty() && c.getSource().getPlayer() != null) {
			players = java.util.List.of(c.getSource().getPlayer());
		}
		String error = dev.rubymod.game.WorldEvents.begin(server, players, kind, null, 2 * 60_000L);
		if (error != null) {
			c.getSource().sendFailure(Component.literal(error));
			return 0;
		}
		return ok(c, kind.title + " coming in 2 minutes at " + dev.rubymod.game.WorldEvents.at().toShortString() + ".");
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
				{ModBlocks.AT_MINE, 4}, {ModItems.HEAVY_AA_GUN, 1}, {ModItems.RAILGUN, 1}, {ModItems.ARCHITECT_WAND, 1}, {ModItems.REMODELER, 1}, {ModItems.BLUEPRINT, 2},
				{ModBlocks.COMBUSTION_GENERATOR, 2}, {ModBlocks.SOLAR_PANEL, 4}, {ModBlocks.BATTERY, 2}, {ModBlocks.RUBY_DRILL, 2},
				{ModBlocks.ORE_REFINERY, 1}, {ModBlocks.AMMO_FACTORY, 1}, {ModBlocks.TRADE_DEPOT, 1}, {ModBlocks.VAULT, 1},
				{ModBlocks.SHIELD_GENERATOR, 1}, {ModBlocks.POINT_DEFENSE, 1}, {ModBlocks.CONSTRUCTOR, 1},
				{ModBlocks.REINFORCED_GLASS, 32}, {ModBlocks.FLOODLIGHT, 4}, {ModBlocks.CONVEYOR, 32}, {ModBlocks.TESLA_COIL, 1}, {ModBlocks.ELEVATOR, 4}, {ModBlocks.CAMO_NET, 32}, {ModBlocks.FLAK_CANNON, 1}, {ModBlocks.SAM_SITE, 1}, {ModBlocks.AIRFIELD_PAD, 1}, {ModItems.BUILDERS_WAND, 1}, {ModItems.EXCAVATOR, 1}, {ModItems.RUBY, 64},
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

	private static int leaderboard(CommandContext<CommandSourceStack> c) {
		var top = dev.rubymod.industry.Grid.leaderboard();
		c.getSource().sendSystemMessage(Component.literal("Ruby Wars tycoons (rubies earned / in the bank)").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		if (top.isEmpty()) {
			c.getSource().sendSystemMessage(Component.literal("Nobody has earned anything yet. Build a Ruby Drill and a Trade Depot!")
					.withStyle(ChatFormatting.GRAY));
		}
		int rank = 1;
		for (var e : top) {
			var record = SeasonData.get().player(java.util.UUID.fromString(e.getKey()));
			String name = record != null && !record.name.isEmpty() ? record.name : e.getKey().substring(0, 8);
			c.getSource().sendSystemMessage(Component.literal(String.format(java.util.Locale.ROOT, "%2d. %-16s %,8d  /  %,d", rank++, name,
					e.getValue().lifetimeEarned / 1000, e.getValue().milliRubies / 1000)).withStyle(rank == 2 ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
		}
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

	/** /rubywars battle [seconds]: end the build phase. Battle Day (the lockdown, then open war) begins after the countdown. */
	private static int battle(CommandContext<CommandSourceStack> c, int seconds) {
		String error = SeasonTicker.callBattle(c.getSource().getServer(), seconds);
		if (error != null) {
			return fail(c, error);
		}
		return ok(c, "Battle Day begins in " + seconds + " seconds (then " + GameConfig.get().lockdownMinutes + " minutes of lockdown before PvP). "
				+ "/rubywars battle cancel calls it off.");
	}

	private static int fail(CommandContext<CommandSourceStack> c, String message) {
		c.getSource().sendFailure(Component.literal(message));
		return 0;
	}

	private static final long WEEK = 7 * 1440L;

	/** /rubywars start [untilisay | 1week | 2weeks | days N]: begin the season; Battle Day comes then (or when you call it). */
	private static int start(CommandContext<CommandSourceStack> c, long minutes) {
		if (SeasonData.get().phase != Phase.LOBBY) {
			c.getSource().sendFailure(Component.literal("The season already started. To change when Battle Day comes: /rubywars schedule. "
					+ "To start over: /rubywars reset confirm, then /rubywars start."));
			return 0;
		}
		SeasonData.get().buildMinutes = minutes;
		SeasonData.markDirty();
		BattleDirector.startSeason(c.getSource().getServer());
		return ok(c, minutes > 0 ? "Season started! Battle Day begins in " + SeasonTicker.describe(minutes * 60_000L)
				+ " (/rubywars battle starts it sooner)." : "Season started! Build for as long as you like: when everyone's ready, run /rubywars battle.");
	}

	private static int schedule(CommandContext<CommandSourceStack> c, long minutes) {
		String error = SeasonTicker.schedule(c.getSource().getServer(), minutes);
		if (error != null) {
			return fail(c, error);
		}
		return ok(c, minutes > 0 ? "Battle Day begins in " + SeasonTicker.describe(minutes * 60_000L) + "."
				: "Battle Day has no date now: it begins when you run /rubywars battle.");
	}

	private static net.minecraft.network.chat.MutableComponent button(String label, String command, String hover, ChatFormatting color) {
		return Component.literal("[" + label + "]").withStyle(s -> s.withColor(color).withBold(true)
				.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(command))
				.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal(hover))));
	}

	/** /rubywars admin: the season at a glance, with buttons to click. */
	private static int admin(CommandContext<CommandSourceStack> c) {
		SeasonData data = SeasonData.get();
		CommandSourceStack s = c.getSource();
		s.sendSystemMessage(Component.literal("Ruby Wars admin - Season " + data.season + ": " + data.phase.title()).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		if (data.phase == Phase.LOBBY) {
			s.sendSystemMessage(Component.literal("Start the season. Battle Day comes: ").withStyle(ChatFormatting.GRAY)
					.append(button("When I say", "/rubywars start untilisay", "No date: you start the battle with /rubywars battle", ChatFormatting.GREEN))
					.append(" ").append(button("In 1 week", "/rubywars start 1week", "Battle Day starts by itself a week from now", ChatFormatting.AQUA))
					.append(" ").append(button("In 2 weeks", "/rubywars start 2weeks", "Battle Day starts by itself two weeks from now", ChatFormatting.AQUA)));
		} else if (data.phase == Phase.BUILD) {
			long left = SeasonTicker.remainingMillis();
			s.sendSystemMessage(Component.literal("Day " + SeasonTicker.currentDay() + " of building. Battle Day: "
					+ (left < 0 ? "when you say" : "in " + SeasonTicker.describe(left))).withStyle(ChatFormatting.GRAY));
			s.sendSystemMessage(Component.literal("Battle: ").withStyle(ChatFormatting.GRAY)
					.append(button("Start it now", "/rubywars battle", "A one-minute countdown everyone sees, then Battle Day", ChatFormatting.RED))
					.append(" ").append(button("Call it off", "/rubywars battle cancel", "Stop a countdown you started", ChatFormatting.GRAY)));
			s.sendSystemMessage(Component.literal("Change the date: ").withStyle(ChatFormatting.GRAY)
					.append(button("When I say", "/rubywars schedule untilisay", "No date: you start it", ChatFormatting.GREEN))
					.append(" ").append(button("In 1 week", "/rubywars schedule 1week", "A week from now", ChatFormatting.AQUA))
					.append(" ").append(button("In 2 weeks", "/rubywars schedule 2weeks", "Two weeks from now", ChatFormatting.AQUA)));
		} else {
			s.sendSystemMessage(Component.literal("The battle is on. Players left: " + BattleDirector.aliveCount()).withStyle(ChatFormatting.GRAY));
		}
		if (data.phase != Phase.LOBBY) {
			s.sendSystemMessage(Component.literal("New season: ").withStyle(ChatFormatting.GRAY)
					.append(button("End this season", "/rubywars reset confirm",
							"Clears every base claim and record and goes back to the lobby (champions are kept). Then start a new one here.",
							ChatFormatting.DARK_RED)));
		}
		return 1;
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
		int nextSeason = dev.rubymod.game.FreshStart.resetSeason(c.getSource().getServer());
		ok(c, "Reset to the lobby for Season " + nextSeason + ". Everyone starts fresh in Ruby Wars (the hub and other games keep their things).");
		return ok(c, "To also clear the old bases off the island, run on the server: rubywars newseason. Then /rubywars admin (or /rubywars start) when everyone's ready.");
	}
}
