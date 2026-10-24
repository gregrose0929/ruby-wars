package dev.rubymod.game.party;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** All the party games, and the hooks the rest of the mod calls into. */
public final class PartyGames {
	public static final List<PartyGame> ALL = List.of(
			new GunGame(),
			new HideAndSeek(),
			new Infection(),
			new CaptureTheFlag(),
			new KingOfTheHill(),
			new TankBattle(),
			new SkyRace(),
			new BuildBattle(),
			new RubyRush());

	/** For autotests: stand-in players (fake players) that aren't on the player list. */
	public static final Map<UUID, ServerPlayer> TEST_PLAYERS = new HashMap<>();

	private PartyGames() {
	}

	public static PartyGame byId(String id) {
		for (PartyGame game : ALL) {
			if (game.id.equals(id)) {
				return game;
			}
		}
		return null;
	}

	public static PartyGame of(Player player) {
		for (PartyGame game : ALL) {
			if (game.contains(player)) {
				return game;
			}
		}
		return null;
	}

	static ServerPlayer player(MinecraftServer server, UUID id) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		return p != null ? p : TEST_PLAYERS.get(id);
	}

	public static void reset(MinecraftServer server) {
		TEST_PLAYERS.clear();
		for (PartyGame game : ALL) {
			game.attach(server);
			game.reset();
		}
	}

	public static void tick(MinecraftServer server) {
		for (PartyGame game : ALL) {
			game.attach(server);
			game.tick();
		}
	}

	public static boolean onDeath(ServerPlayer victim, DamageSource source) {
		PartyGame game = of(victim);
		return game != null && game.onDeath(victim, source);
	}

	public static void onEntityDeath(LivingEntity entity, DamageSource source) {
		for (PartyGame game : ALL) {
			game.entityDied(entity, source);
		}
	}

	public static Boolean canHurt(Player attacker, Player victim) {
		PartyGame a = of(attacker);
		PartyGame v = of(victim);
		if (a == null && v == null) {
			return null;
		}
		if (a != v) {
			return false;
		}
		return a.canHurt(attacker, victim);
	}

	public static void onDisconnect(ServerPlayer player) {
		PartyGame game = of(player);
		if (game != null) {
			game.onDisconnect(player);
		}
	}

	public static boolean allowsItemUse(ServerPlayer player, ItemStack stack) {
		PartyGame game = of(player);
		return game == null || game.allowsItemUse(player, stack);
	}

	public static boolean allowsGuns(ServerPlayer player) {
		PartyGame game = of(player);
		return game == null || game.allowsGuns(player);
	}

	public static boolean onUseBlock(ServerPlayer player, BlockPos pos, ItemStack held, BlockPos placeAt) {
		PartyGame game = of(player);
		return game != null && game.onUseBlock(player, pos, held, placeAt);
	}

	public static boolean allowsBreak(ServerPlayer player, BlockPos pos) {
		PartyGame game = of(player);
		return game == null || game.allowsBreak(player, pos);
	}

	public static long playerCount() {
		long n = 0;
		for (PartyGame game : ALL) {
			n += game.players.size();
		}
		return n;
	}

	/** For the game picker: "game:id|title|status|icon|about" per game. */
	public static List<String> lines() {
		List<String> lines = new ArrayList<>();
		for (PartyGame game : ALL) {
			lines.add("game:" + game.id + "|" + game.title + "|" + game.status() + "|" + game.icon + "|" + game.about);
		}
		return lines;
	}

	/** For autotests: a fake player joins a game. */
	public static void addTestPlayer(PartyGame game, ServerPlayer fake) {
		TEST_PLAYERS.put(fake.getUUID(), fake);
		game.join(fake);
	}
}
