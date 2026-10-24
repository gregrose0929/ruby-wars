package dev.rubymod.game.party;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.item.ArchitectWandItem;
import dev.rubymod.item.BuildersWandItem;
import dev.rubymod.registry.ModBlocks;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Build Battle: everyone gets the same theme and 5 minutes to build it on their own
 * plot (creative mode, any block). Then everyone visits every plot and rates it 1 to 5
 * stars with the colored wool in their hotbar. Highest average wins.
 */
public class BuildBattle extends PartyGame {
	private static final int PLOTS = 8;
	private static final int SPACING = 24;
	private static final int PLOT_HALF = 7;
	private static final int BUILD_TICKS = 5 * 60 * 20;
	private static final int VOTE_TICKS = 12 * 20;
	private static final String[] THEMES = {"Castle", "Rocket ship", "Treehouse", "Dragon", "Pirate ship", "Ice cream", "Robot", "Haunted house",
			"Car", "Volcano", "Underwater base", "Giant food", "Spaceship", "Zoo", "Rainbow", "Lighthouse", "Snowman", "Race car", "Jungle temple",
			"Birthday cake", "Tank", "Airplane", "Dream house", "Friendly monster", "Garden", "Waterfall", "Your favorite animal", "Fort", "Train",
			"Mushroom house"};
	private static final DyeColor[] VOTE_COLORS = {DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.LIME, DyeColor.GREEN};
	private static final String[] VOTE_NAMES = {"1 ★  Meh", "2 ★  OK", "3 ★  Good", "4 ★  Great", "5 ★  AMAZING"};

	private String theme = "";
	private boolean voting;
	private int votePlot;
	private int voteStartedAt;
	private final List<UUID> plotOwners = new ArrayList<>();
	/** Votes per plot: voter -> stars. */
	private final Map<Integer, Map<UUID, Integer>> votes = new HashMap<>();

	public BuildBattle() {
		super("build", "Build Battle", "Same theme for everyone, 5 minutes to build it, then vote on the best!", "rubymod:builders_wand",
				2, false, false, false);
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(-75000, GROUND + 1, 0);
	}

	@Override
	protected int reach() {
		return 110;
	}

	private int plotX(int i) {
		return -((PLOTS - 1) * SPACING) / 2 + i * SPACING;
	}

	@Override
	protected void build(ServerLevel level) {
		flatArena(level, (PLOTS * SPACING) / 2 + 4, 16, 34, Blocks.SMOOTH_STONE.defaultBlockState(), true);
		for (int i = 0; i < PLOTS; i++) {
			int x = plotX(i);
			box(level, x - PLOT_HALF - 1, 0, -PLOT_HALF - 1, x + PLOT_HALF + 1, 0, PLOT_HALF + 1, Blocks.POLISHED_ANDESITE.defaultBlockState());
			box(level, x - PLOT_HALF, 0, -PLOT_HALF, x + PLOT_HALF, 0, PLOT_HALF, Blocks.GRASS_BLOCK.defaultBlockState());
		}
	}

	@Override
	protected String roundStatus() {
		if (!voting) {
			int left = Math.max(0, BUILD_TICKS - roundTicks) / 20;
			return "Theme: " + theme + "  •  " + left / 60 + ":" + String.format("%02d", left % 60);
		}
		return "Voting: plot " + (votePlot + 1) + " of " + plotOwners.size() + "  •  theme: " + theme;
	}

	@Override
	protected float barProgress() {
		return voting ? 1 - (roundTicks - voteStartedAt) / (float) VOTE_TICKS : 1 - roundTicks / (float) BUILD_TICKS;
	}

	@Override
	protected void startRound() {
		theme = THEMES[level().getRandom().nextInt(THEMES.length)];
		voting = false;
		votes.clear();
		plotOwners.clear();
		for (ServerPlayer p : online()) {
			Part part = players.get(p.getUUID());
			if (plotOwners.size() >= PLOTS) {
				part.watching = true;
				watch(p, center().above(10));
				continue;
			}
			part.level = plotOwners.size();
			plotOwners.add(p.getUUID());
			spawn(p, part);
			dev.rubymod.game.Alerts.title(p, "BUILD: " + theme.toUpperCase(), "5 minutes. Make it awesome!", ChatFormatting.AQUA);
			p.sendSystemMessage(Component.literal("Theme: " + theme + ". You have 5 minutes on your plot (creative mode: open your inventory for any block). "
					+ "Then everyone votes!").withStyle(ChatFormatting.AQUA));
		}
	}

	@Override
	protected void spawn(ServerPlayer player, Part part) {
		BlockPos c = center();
		if (!voting) {
			teleport(player, c.getX() + plotX(part.level) + 0.5, c.getY(), c.getZ() + 0.5, 0);
			player.setGameMode(GameType.CREATIVE);
			clearPlayer(player);
			give(player, new ItemStack(net.minecraft.world.item.Items.GRASS_BLOCK.asItem()));
		}
	}

	private boolean insidePlot(int plot, BlockPos pos) {
		int x = pos.getX() - center().getX() - plotX(plot);
		int z = pos.getZ() - center().getZ();
		return Math.abs(x) <= PLOT_HALF && Math.abs(z) <= PLOT_HALF && pos.getY() >= GROUND && pos.getY() <= GROUND + 30;
	}

	private Part part(ServerPlayer player) {
		return players.get(player.getUUID());
	}

	/** Blocks and the building wands only: no explosives, mobs, guns or machines. */
	private static boolean buildable(ItemStack stack) {
		if (stack.isEmpty() || stack.getItem() instanceof BuildersWandItem || stack.getItem() instanceof ArchitectWandItem) {
			return true;
		}
		if (!(stack.getItem() instanceof BlockItem blockItem)) {
			return false;
		}
		Block block = blockItem.getBlock();
		String ns = BuiltInRegistries.BLOCK.getKey(block).getNamespace();
		if (!ns.equals("minecraft")) {
			return block == ModBlocks.RUBY_BLOCK || block == ModBlocks.SANDBAGS || block == ModBlocks.CONCRETE_WALL
					|| block == ModBlocks.REINFORCED_CONCRETE;
		}
		return block != Blocks.TNT && block != Blocks.SPAWNER && block != Blocks.COMMAND_BLOCK && block != Blocks.CHAIN_COMMAND_BLOCK
				&& block != Blocks.REPEATING_COMMAND_BLOCK && block != Blocks.STRUCTURE_BLOCK && block != Blocks.JIGSAW && block != Blocks.BARRIER;
	}

	@Override
	public boolean allowsItemUse(ServerPlayer player, ItemStack stack) {
		if (phase != Phase.PLAYING || part(player) == null) {
			return true;
		}
		if (voting) {
			vote(player, stack);
			return false;
		}
		return buildable(stack);
	}

	@Override
	public boolean allowsGuns(ServerPlayer player) {
		return false;
	}

	@Override
	public boolean onUseBlock(ServerPlayer player, BlockPos pos, ItemStack held, BlockPos placeAt) {
		Part part = part(player);
		if (phase != Phase.PLAYING || part == null) {
			return false;
		}
		if (voting) {
			vote(player, held);
			return true;
		}
		if (!buildable(held)) {
			return true;
		}
		return !part.watching && !held.isEmpty() && !insidePlot(part.level, placeAt) && !insidePlot(part.level, pos);
	}

	@Override
	public boolean allowsBreak(ServerPlayer player, BlockPos pos) {
		Part part = part(player);
		return part != null && phase == Phase.PLAYING && !voting && !part.watching && insidePlot(part.level, pos);
	}

	@Override
	public Boolean canHurt(Player attacker, Player victim) {
		return contains(attacker) || contains(victim) ? Boolean.FALSE : null;
	}

	private void vote(ServerPlayer player, ItemStack stack) {
		for (int i = 0; i < VOTE_COLORS.length; i++) {
			if (stack.is(Items.WOOL.pick(VOTE_COLORS[i]))) {
				if (player.getUUID().equals(plotOwners.get(votePlot))) {
					player.sendOverlayMessage(Component.literal("That's your own build! Wait for the next one.").withStyle(ChatFormatting.GRAY));
					return;
				}
				votes.computeIfAbsent(votePlot, k -> new HashMap<>()).put(player.getUUID(), i + 1);
				player.sendOverlayMessage(Component.literal("You voted: " + VOTE_NAMES[i]).withStyle(ChatFormatting.GOLD));
				player.level().playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.7F, 0.6F + i * 0.2F);
				return;
			}
		}
	}

	private void showPlot(int plot) {
		votePlot = plot;
		voteStartedAt = roundTicks;
		BlockPos c = center();
		for (ServerPlayer p : online()) {
			teleport(p, c.getX() + plotX(plot) + 0.5, c.getY() + 3, c.getZ() - PLOT_HALF - 6.5, 0);
			p.setGameMode(GameType.ADVENTURE);
			p.getAbilities().mayfly = true;
			p.onUpdateAbilities();
			clearPlayer(p);
			for (int i = 0; i < VOTE_COLORS.length; i++) {
				ItemStack wool = new ItemStack(Items.WOOL.pick(VOTE_COLORS[i]));
				wool.set(DataComponents.CUSTOM_NAME, Component.literal(VOTE_NAMES[i]).withStyle(ChatFormatting.GOLD));
				p.getInventory().setItem(i, wool);
			}
			p.inventoryMenu.broadcastChanges();
			boolean mine = p.getUUID().equals(plotOwners.get(plot));
			dev.rubymod.game.Alerts.title(p, "PLOT " + (plot + 1) + " OF " + plotOwners.size(), mine ? "This one's yours!" : "Right-click a wool to rate it",
					ChatFormatting.AQUA);
		}
	}

	@Override
	protected void tickRound() {
		if (!voting) {
			// Keep builders on their own plots.
			if (roundTicks % 10 == 0) {
				for (ServerPlayer p : online()) {
					Part part = part(p);
					if (part.watching) {
						continue;
					}
					BlockPos c = center();
					double dx = p.getX() - (c.getX() + plotX(part.level) + 0.5);
					double dz = p.getZ() - (c.getZ() + 0.5);
					if (Math.abs(dx) > PLOT_HALF + 1.5 || Math.abs(dz) > PLOT_HALF + 1.5 || p.getY() > GROUND + 32) {
						teleport(p, c.getX() + plotX(part.level) + 0.5, c.getY() + 1, c.getZ() + 0.5, p.getYRot());
						p.sendOverlayMessage(Component.literal("Stay on your own plot!").withStyle(ChatFormatting.RED));
					}
				}
			}
			int left = BUILD_TICKS - roundTicks;
			if (left == 60 * 20 || left == 10 * 20) {
				titleAll(left / 20 + " SECONDS LEFT", "Theme: " + theme, ChatFormatting.YELLOW);
			}
			if (left <= 0) {
				voting = true;
				titleAll("TIME'S UP!", "Now let's vote", ChatFormatting.GOLD);
				showPlot(0);
			}
			return;
		}
		if (roundTicks - voteStartedAt >= VOTE_TICKS) {
			if (votePlot + 1 < plotOwners.size()) {
				showPlot(votePlot + 1);
			} else {
				results();
			}
		}
	}

	private void results() {
		List<int[]> scores = new ArrayList<>();
		for (int plot = 0; plot < plotOwners.size(); plot++) {
			Map<UUID, Integer> v = votes.getOrDefault(plot, Map.of());
			int avg = v.isEmpty() ? 0 : (int) Math.round(v.values().stream().mapToInt(Integer::intValue).average().orElse(0) * 10);
			scores.add(new int[] {plot, avg});
		}
		scores.sort(Comparator.comparingInt((int[] s) -> s[1]).reversed());
		StringBuilder b = new StringBuilder();
		for (int[] s : scores) {
			Part part = players.get(plotOwners.get(s[0]));
			String name = part == null ? "someone who left" : part.name;
			b.append(b.isEmpty() ? "" : ", ").append(name).append(" ").append(s[1] / 10).append(".").append(s[1] % 10).append("★");
		}
		int[] best = scores.get(0);
		Part winner = players.get(plotOwners.get(best[0]));
		// Show off the winning build.
		BlockPos c = center();
		for (ServerPlayer p : online()) {
			teleport(p, c.getX() + plotX(best[0]) + 0.5, c.getY() + 3, c.getZ() - PLOT_HALF - 6.5, 0);
		}
		endRound((winner == null ? "SOMEONE" : winner.name.toUpperCase()) + " WINS!", "\"" + theme + "\": " + b, ChatFormatting.AQUA);
	}

	@Override
	protected void onLeft(UUID id, Part part) {
		if (activeCount() < 2 && !voting) {
			endRound("NOT ENOUGH BUILDERS", "Build Battle needs at least two people.", ChatFormatting.GRAY);
		}
	}
}
