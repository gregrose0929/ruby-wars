package dev.rubymod.game;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockItemTagId;
import net.minecraft.tags.BlockItemTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Three small daily tasks per player during the build phase, each paying a few
 * rubies. They give people a reason to log in every day and steer them toward
 * the gun play that Battle Day needs.
 */
public final class Bounties {
	public enum Type {
		/** Retired (no hostile mobs any more); kept so old saves still load. Never handed out. */
		KILL_MONSTERS("Kill %d monsters", 15, 30, 3),
		GUN_HITS("Land %d gun hits on targets (dummies, drones, raiders)", 25, 50, 3),
		HEADSHOTS("Score %d headshots on targets", 5, 10, 4),
		TRAVEL("Travel %d blocks in a vehicle or aircraft", 400, 900, 3),
		MINE_STONE("Mine %d stone or deepslate", 128, 256, 2),
		MINE_ORE("Mine %d ores of any kind", 12, 24, 3);

		final String text;
		final int min;
		final int max;
		final int reward;

		Type(String text, int min, int max, int reward) {
			this.text = text;
			this.min = min;
			this.max = max;
			this.reward = reward;
		}
	}

	/** One bounty, stored in the player's season record. */
	public static class Bounty {
		public Type type;
		public int target;
		public int progress;
		public int reward;
		public boolean done;

		public String describe() {
			return String.format(type.text, target) + "  (" + Math.min(progress, target) + "/" + target + ")  reward: " + reward + " rubies"
					+ (done ? "  DONE" : "");
		}
	}

	private Bounties() {
	}

	/** Send today's bounties to the player's HUD tracker (build phase only; empty otherwise). */
	public static void sync(ServerPlayer player) {
		List<String> lines = new ArrayList<>();
		if (SeasonData.get().phase == Phase.BUILD) {
			for (Bounty b : today(player)) {
				lines.add(String.format(b.type.text, b.target) + "|" + Math.min(b.progress, b.target) + "|" + b.target + "|" + b.reward + "|" + b.done);
			}
		}
		dev.rubymod.net.Net.send(player, new dev.rubymod.net.MorePayloads.BountySync(lines));
	}

	/** True when every one of today's bounties is done. */
	public static boolean allDone(SeasonData.PlayerRecord record) {
		return !record.bounties.isEmpty() && record.bounties.stream().allMatch(b -> b.done);
	}

	/** Make sure the player has today's bounties; hands out new ones when the day changes. */
	public static List<Bounty> today(ServerPlayer player) {
		SeasonData.PlayerRecord record = SeasonData.get().player(player.getUUID(), player.getName().getString());
		int day = SeasonTicker.currentDay();
		if (day > 0 && (record.bountyDay != day || record.bounties.stream().anyMatch(b -> b.type == null || b.type == Type.KILL_MONSTERS))) {
			record.bountyDay = day;
			record.bounties = roll(player.getRandom());
			SeasonData.markDirty();
			player.sendSystemMessage(Component.literal("New daily bounties! Type /rubywars bounties to see them.").withStyle(ChatFormatting.AQUA));
		}
		return record.bounties;
	}

	private static List<Bounty> roll(RandomSource random) {
		List<Type> types = new ArrayList<>(List.of(Type.values()));
		types.remove(Type.KILL_MONSTERS);
		List<Bounty> picked = new ArrayList<>();
		for (int i = 0; i < 3 && !types.isEmpty(); i++) {
			Type type = types.remove(random.nextInt(types.size()));
			Bounty bounty = new Bounty();
			bounty.type = type;
			bounty.target = type.min + random.nextInt(type.max - type.min + 1);
			bounty.reward = type.reward;
			picked.add(bounty);
		}
		return picked;
	}

	private static void progress(ServerPlayer player, Type type, int amount) {
		if (SeasonData.get().phase != Phase.BUILD) {
			return;
		}
		for (Bounty bounty : today(player)) {
			if (bounty.type != type || bounty.done) {
				continue;
			}
			bounty.progress += amount;
			SeasonData.markDirty();
			if (bounty.progress >= bounty.target) {
				bounty.done = true;
				ItemStack reward = new ItemStack(ModItems.RUBY, bounty.reward);
				if (!player.getInventory().add(reward)) {
					player.drop(reward, false, net.minecraft.util.Prediction.SERVER_ONLY);
				}
				player.sendSystemMessage(Component.literal("Bounty complete: " + String.format(type.text, bounty.target) + "! +"
						+ bounty.reward + " rubies").withStyle(ChatFormatting.GOLD));
				Alerts.ping(player, 1.5F);
				Goals.add(player.level().getServer(), player.getUUID(), "bounties", 1);
			}
		}
	}

	public static void onKill(ServerPlayer killer, Entity victim) {
		if (victim instanceof Enemy) {
			progress(killer, Type.KILL_MONSTERS, 1);
		}
	}

	public static void onTravel(ServerPlayer player, int blocks) {
		progress(player, Type.TRAVEL, blocks);
	}

	public static void onGunHit(ServerPlayer shooter, Entity target, boolean headshot) {
		if (target instanceof net.minecraft.world.entity.LivingEntity && !(target instanceof net.minecraft.world.entity.player.Player)) {
			progress(shooter, Type.GUN_HITS, 1);
			if (headshot) {
				progress(shooter, Type.HEADSHOTS, 1);
			}
		}
	}

	private static final List<BlockItemTagId> ORE_TAGS = List.of(BlockItemTags.COAL_ORES, BlockItemTags.IRON_ORES, BlockItemTags.COPPER_ORES,
			BlockItemTags.GOLD_ORES, BlockItemTags.REDSTONE_ORES, BlockItemTags.LAPIS_ORES, BlockItemTags.DIAMOND_ORES, BlockItemTags.EMERALD_ORES);

	private static boolean isOre(BlockState state) {
		if (state.is(ModBlocks.RUBY_ORE) || state.is(ModBlocks.DEEPSLATE_RUBY_ORE)) {
			return true;
		}
		for (BlockItemTagId tag : ORE_TAGS) {
			if (state.is(tag.block())) {
				return true;
			}
		}
		return false;
	}

	public static void onBlockBroken(ServerPlayer player, BlockState state) {
		if (state.is(BlockTags.BASE_STONE_OVERWORLD)) {
			progress(player, Type.MINE_STONE, 1);
		} else if (isOre(state)) {
			progress(player, Type.MINE_ORE, 1);
		}
	}
}
