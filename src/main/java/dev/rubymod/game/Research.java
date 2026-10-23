package dev.rubymod.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * The research tree: advanced Armory gear starts locked. Research points (shown
 * as stars) come from finishing Goals (place your Heart, mine ruby, repel a raid,
 * win a duel...), never from time played; spend them on unlocks in four branches.
 * You can't unlock everything in one season, so squads split the tree.
 *
 * Basic guns, the Heart, base blocks, basic industry and basic defenses never
 * need research.
 */
public final class Research {
	/** One unlock. {@code items} are Armory item ids it opens up. */
	public record Node(String id, String title, String branch, int cost, String requires, String icon, String description, List<String> items) {
	}

	public static final Map<String, Node> NODES = new LinkedHashMap<>();

	static {
		// Arms
		node("marksman", "Marksman", "Arms", 2, null, "longshot_sniper", "Longshot Sniper and sniper rounds", "longshot_sniper", "sniper_round");
		node("ruby_tech", "Ruby Energy Weapons", "Arms", 3, "marksman", "ruby_blaster", "Ruby Blaster and ruby cells", "ruby_blaster", "ruby_cell");
		node("anti_armor", "Anti-Armor", "Arms", 2, null, "rocket_launcher", "Rocket Launcher, rockets, Lancer AT and AT missiles",
				"rocket_launcher", "rocket", "lancer", "at_missile");
		node("demolitions", "Demolitions", "Arms", 3, "anti_armor", "c4", "C4, AT mines and Breaching Charges", "c4", "at_mine", "breaching_charge");
		// Vehicles & air
		node("motor_pool", "Motor Pool", "Vehicles", 2, null, "jeep", "Jeep and Tank", "jeep", "tank");
		node("aviation", "Aviation", "Vehicles", 4, "motor_pool", "fighter_jet", "Fighter Jet, Attack Helicopter and the Airfield Pad",
				"fighter_jet", "attack_heli", "airfield_pad");
		node("strike_aircraft", "Strike Aircraft", "Vehicles", 4, "aviation", "bomber", "Attack Jet and Bomber", "attack_jet", "bomber");
		node("strategic", "Strategic Weapons", "Vehicles", 5, "strike_aircraft", "cruise_missile", "Missile Silo and cruise missiles",
				"missile_silo", "cruise_missile");
		// Air defense
		node("air_defense", "Air Defense", "Defense", 2, null, "skyhammer", "Skyhammer AA, AA missiles and the Flak Cannon",
				"skyhammer", "aa_missile", "flak_cannon");
		node("integrated_ad", "Integrated Air Defense", "Defense", 4, "air_defense", "sam_site", "SAM Site, Missile Interceptor and Heavy AA Gun",
				"sam_site", "missile_interceptor", "heavy_aa_gun");
		node("shields", "Energy Shields", "Defense", 4, "integrated_ad", "shield_generator", "Shield Generator, Point Defense and Tesla Coil",
				"shield_generator", "point_defense", "tesla_coil");
		node("railgun", "Railgun", "Defense", 5, "shields", "railgun", "The Railgun Emplacement", "railgun");
		// Engineering & support
		node("logistics", "Logistics", "Support", 2, null, "conveyor", "Conveyors, Constructor and Elevators", "conveyor", "constructor", "elevator");
		node("guards", "Hired Guards", "Support", 3, null, "guard_contract", "Guard Contracts (riflemen and AT gunners)",
				"guard_contract", "at_guard_contract");
		node("recon", "Recon", "Support", 2, null, "recon_drone", "Recon Drone and Mortar", "recon_drone", "mortar", "mortar_shell");
		node("artillery", "Artillery", "Support", 4, "recon", "mortar_shell", "Air Strike Flares from the Armory", "air_strike_flare");
	}

	private static void node(String id, String title, String branch, int cost, String requires, String icon, String description, String... items) {
		NODES.put(id, new Node(id, title, branch, cost, requires, icon, description, List.of(items)));
	}

	private Research() {
	}

	/** The node that locks this Armory item, or null if it's always available. */
	public static Node lockFor(String itemId) {
		for (Node node : NODES.values()) {
			if (node.items().contains(itemId)) {
				return node;
			}
		}
		return null;
	}

	public static boolean unlocked(ServerPlayer player, String itemId) {
		if (!GameConfig.get().research || player.isCreative()) {
			return true;
		}
		Node node = lockFor(itemId);
		return node == null || has(player.getUUID(), node.id());
	}

	/** You (or a squadmate) researched it. */
	public static boolean has(UUID player, String node) {
		SeasonData.PlayerRecord r = SeasonData.get().players.get(player.toString());
		if (r != null && r.research.contains(node)) {
			return true;
		}
		if (GameConfig.get().squadsShareResearch) {
			for (UUID mate : Squads.mates(player)) {
				SeasonData.PlayerRecord m = SeasonData.get().players.get(mate.toString());
				if (m != null && m.research.contains(node)) {
					return true;
				}
			}
		}
		return false;
	}

	public static void addPoints(UUID player, int points) {
		SeasonData.PlayerRecord r = SeasonData.get().players.get(player.toString());
		if (r != null) {
			r.researchPoints += points;
			SeasonData.markDirty();
		}
	}

	public static String unlock(ServerPlayer player, String id) {
		Node node = NODES.get(id);
		if (node == null) {
			return "Unknown research.";
		}
		SeasonData.PlayerRecord r = SeasonData.get().player(player.getUUID(), player.getName().getString());
		if (has(player.getUUID(), id)) {
			return "Already researched.";
		}
		if (node.requires() != null && !has(player.getUUID(), node.requires())) {
			return "Research " + NODES.get(node.requires()).title() + " first.";
		}
		if (r.researchPoints < node.cost()) {
			return "You need " + node.cost() + " stars (you have " + r.researchPoints + "). Finish goals to earn more.";
		}
		r.researchPoints -= node.cost();
		r.research.add(id);
		SeasonData.markDirty();
		player.level().playSound(null, player.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1.0F, 1.2F);
		player.sendSystemMessage(Component.literal("Researched " + node.title() + ": " + node.description() + " now in the Armory.")
				.withStyle(ChatFormatting.AQUA));
		Squads.Squad squad = Squads.of(player.getUUID());
		if (squad != null && GameConfig.get().squadsShareResearch) {
			Squads.tell(player.level().getServer(), squad, player.getName().getString() + " researched " + node.title() + " for the squad.");
		}
		sync(player);
		return null;
	}

	public static void sync(ServerPlayer player) {
		SeasonData.PlayerRecord r = SeasonData.get().players.get(player.getUUID().toString());
		List<String> unlocked = new ArrayList<>();
		for (String id : NODES.keySet()) {
			if (has(player.getUUID(), id)) {
				unlocked.add(id);
			}
		}
		Net.send(player, new MorePayloads.ResearchSync(r == null ? 0 : r.researchPoints, unlocked, Goals.describe(player.getUUID())));
	}
}
