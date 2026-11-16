package dev.rubymod.game.party;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.phys.Vec3;

/**
 * Pie Mystery: someone in town is secretly the Prankster, sneaking up on people with cream pies. The Detective (everyone
 * knows who) has to work out who it is: everyone else hunts for clues round town (the paper "?"s), and the Detective
 * hears every clue. Get pied and you wear a cake on your head (and you're a bit slow). The Detective points the
 * magnifying glass at someone to say "It's you!": right, and the town wins; three wrong guesses, everyone pied, or the
 * clock running out, and the Prankster wins.
 */
public class PieMystery extends PartyGame {
	private static final int ROUND_TICKS = 5 * 60 * 20;
	private static final int TOWN = 0;
	private static final int PRANKSTER = 1;
	private static final int DETECTIVE = 2;
	private static final String PIE = "Cream Pie (shh! it's a secret)";
	private static final String GLASS = "Magnifying Glass";
	private static final String NOTEBOOK = "Clue Notebook";
	private static final String[] SHIRT_NAMES = {"red", "blue", "yellow", "green", "pink", "purple", "orange", "white", "black", "sky blue", "brown", "lime"};
	private static final int[] SHIRT_RGB = {0xE03040, 0x3060E0, 0xFFD030, 0x309040, 0xFF80C0, 0x9050E0, 0xFF9020, 0xF0F0F0, 0x303038, 0x80C8FF, 0x7A4A2A,
		0x90E040};

	private final Map<UUID, Integer> shirt = new HashMap<>();
	private final Set<UUID> pied = new HashSet<>();
	private final Map<UUID, List<String>> notes = new HashMap<>();
	private final Map<UUID, Integer> lastUse = new HashMap<>();
	private UUID prankster;
	private UUID detective;
	private int pieReady;
	private int guessReady;
	private int wrongGuesses;
	private int cluesFound;
	private boolean letterGiven;

	public PieMystery() {
		super("pie", "Pie Mystery", "Someone's secretly pieing people! Find the clues and help the Detective catch the Prankster.", "minecraft:pumpkin_pie", 3,
				false, false, false);
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(-125000, GROUND + 1, 0);
	}

	@Override
	protected void build(ServerLevel level) {
		Town.build(this, level);
	}

	private net.minecraft.server.level.ServerLevel lvl() {
		return level();
	}

	// ----- The round -----

	@Override
	protected void startRound() {
		shirt.clear();
		pied.clear();
		notes.clear();
		lastUse.clear();
		wrongGuesses = 0;
		cluesFound = 0;
		letterGiven = false;
		pieReady = 20 * 20;
		guessReady = 40 * 20;
		List<UUID> ids = new ArrayList<>(players.keySet());
		Collections.shuffle(ids);
		prankster = ids.isEmpty() ? null : ids.get(0);
		detective = ids.size() < 2 ? null : ids.get(1);
		List<Integer> colours = new ArrayList<>();
		for (int i = 0; i < SHIRT_NAMES.length; i++) {
			colours.add(i);
		}
		Collections.shuffle(colours);
		for (int i = 0; i < ids.size(); i++) {
			Part part = players.get(ids.get(i));
			part.team = ids.get(i).equals(prankster) ? PRANKSTER : ids.get(i).equals(detective) ? DETECTIVE : TOWN;
			shirt.put(ids.get(i), colours.get(i % colours.size()));
			notes.put(ids.get(i), new ArrayList<>());
		}
		// Clues hidden round town.
		clearPickups();
		for (int i = 0; i < 10; i++) {
			BlockPos at = spot();
			addPickup("clue", Vec3.atBottomCenterOf(at), new ItemStack(Items.PAPER), "?", ChatFormatting.YELLOW, -1, 1.6);
		}
		for (ServerPlayer p : online()) {
			Part part = players.get(p.getUUID());
			spawn(p, part);
			String colour = SHIRT_NAMES[shirt.get(p.getUUID())];
			switch (part.team) {
				case PRANKSTER -> {
					dev.rubymod.game.Alerts.title(p, "YOU'RE THE PRANKSTER!", "Shh! Sneak up and right-click people with your pie", ChatFormatting.LIGHT_PURPLE);
					p.sendSystemMessage(Component.literal("You're the secret Prankster! Right-click someone close with your Cream Pie to pie them (every 15 seconds). "
							+ "Pie everyone before the Detective works out it's you! Don't get seen: people who see you get a clue.").withStyle(ChatFormatting.LIGHT_PURPLE));
				}
				case DETECTIVE -> {
					dev.rubymod.game.Alerts.title(p, "YOU'RE THE DETECTIVE!", "Find out who the Prankster is", ChatFormatting.AQUA);
					p.sendSystemMessage(Component.literal("You hear every clue anyone finds. When you know who it is, point your Magnifying Glass at them and right-click! "
							+ "(Three wrong guesses and the Prankster wins.)").withStyle(ChatFormatting.AQUA));
				}
				default -> {
					dev.rubymod.game.Alerts.title(p, "FIND THE CLUES!", "Someone's a secret Prankster...", ChatFormatting.YELLOW);
					p.sendSystemMessage(Component.literal("Run round town and grab the paper ?s: each one is a clue about who the Prankster is. "
							+ "The Detective hears them too. Watch out for pies!").withStyle(ChatFormatting.YELLOW));
				}
			}
			p.sendSystemMessage(Component.literal("Your shirt is " + colour + ".").withStyle(ChatFormatting.GRAY));
		}
		ServerPlayer d = detective == null ? null : PartyGames.player(server, detective);
		if (d != null) {
			tell(Component.literal(d.getName().getString() + " is the Detective! Help them find the Prankster.").withStyle(ChatFormatting.AQUA));
		}
	}

	private BlockPos spot() {
		var random = lvl().getRandom();
		BlockPos c = center();
		for (int tries = 0; tries < 60; tries++) {
			int r = Town.HALF - 3;
			BlockPos p = c.offset(random.nextInt(2 * r) - r, 0, random.nextInt(2 * r) - r);
			if (lvl().getBlockState(p).isAir() && lvl().getBlockState(p.above()).isAir() && lvl().getBlockState(p.below()).isSolid()) {
				return p;
			}
		}
		return c.offset(0, 0, 8);
	}

	@Override
	protected void spawn(ServerPlayer player, Part part) {
		BlockPos at = spot();
		teleport(player, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, player.getRandom().nextFloat() * 360);
		ready(player);
		clearPlayer(player);
		int colour = shirt.getOrDefault(player.getUUID(), 0);
		ItemStack top = new ItemStack(Items.LEATHER_CHESTPLATE);
		top.set(DataComponents.DYED_COLOR, new DyedItemColor(SHIRT_RGB[colour]));
		top.set(DataComponents.CUSTOM_NAME, Component.literal("Your " + SHIRT_NAMES[colour] + " shirt"));
		player.setItemSlot(EquipmentSlot.CHEST, top);
		if (part.team == PRANKSTER) {
			give(player, named(Items.PUMPKIN_PIE, PIE, ChatFormatting.LIGHT_PURPLE));
		} else if (part.team == DETECTIVE) {
			give(player, named(Items.COMPASS, GLASS, ChatFormatting.AQUA));
		}
		give(player, named(Items.BOOK, NOTEBOOK, ChatFormatting.YELLOW));
		if (pied.contains(player.getUUID())) {
			creamed(player);
		}
	}

	private static ItemStack named(net.minecraft.world.item.Item item, String name, ChatFormatting color) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(color));
		return s;
	}

	private void creamed(ServerPlayer p) {
		p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CAKE));
		p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, -1, 0, false, false));
	}

	@Override
	protected String roundStatus() {
		int left = Math.max(0, ROUND_TICKS - roundTicks) / 20;
		long toPie = players.entrySet().stream().filter(e -> !e.getValue().watching && !e.getKey().equals(prankster)).count();
		return "Pied: " + pied.size() + " of " + toPie + "  •  Clues found: " + cluesFound + "  •  " + left / 60 + ":" + String.format("%02d", left % 60);
	}

	@Override
	protected float barProgress() {
		return 1 - roundTicks / (float) ROUND_TICKS;
	}

	@Override
	public Boolean canHurt(Player attacker, Player victim) {
		if (players.containsKey(attacker.getUUID()) || players.containsKey(victim.getUUID())) {
			return false;
		}
		return null;
	}

	@Override
	public boolean allowsBreak(ServerPlayer player, BlockPos pos) {
		return false;
	}

	@Override
	public boolean onUseBlock(ServerPlayer player, BlockPos pos, ItemStack held, BlockPos placeAt) {
		return phase == Phase.PLAYING && !held.isEmpty() && held.getItem() instanceof net.minecraft.world.item.BlockItem;
	}

	// ----- Pies, guesses, the notebook -----

	@Override
	public boolean allowsItemUse(ServerPlayer player, ItemStack stack) {
		if (phase != Phase.PLAYING || !players.containsKey(player.getUUID())) {
			return true;
		}
		String name = stack.getHoverName().getString();
		if (!name.equals(PIE) && !name.equals(GLASS) && !name.equals(NOTEBOOK)) {
			return !(stack.getItem() instanceof net.minecraft.world.item.BlockItem);
		}
		Integer last = lastUse.get(player.getUUID());
		if (last != null && roundTicks - last < 8) {
			return false;
		}
		lastUse.put(player.getUUID(), roundTicks);
		if (name.equals(NOTEBOOK)) {
			List<String> mine = notes.getOrDefault(player.getUUID(), List.of());
			player.sendSystemMessage(Component.literal(mine.isEmpty() ? "Your notebook is empty. Find the paper ?s round town!" : "Your clues:")
					.withStyle(ChatFormatting.YELLOW));
			for (String n : mine) {
				player.sendSystemMessage(Component.literal(" • " + n).withStyle(ChatFormatting.WHITE));
			}
		} else if (name.equals(PIE) && player.getUUID().equals(prankster)) {
			pie(player);
		} else if (name.equals(GLASS) && player.getUUID().equals(detective)) {
			guess(player);
		}
		return false;
	}

	/** Who {@code p} is pointing at (close by, near the middle of their view), or null. */
	private ServerPlayer aimedAt(ServerPlayer p, double reach) {
		Vec3 look = p.getLookAngle();
		ServerPlayer best = null;
		double bestDot = 0.85;
		for (ServerPlayer q : online()) {
			Part part = players.get(q.getUUID());
			if (q == p || part == null || part.watching || q.isSpectator()) {
				continue;
			}
			Vec3 to = q.getBoundingBox().getCenter().subtract(p.getEyePosition());
			if (to.length() > reach) {
				continue;
			}
			double dot = to.normalize().dot(look);
			if (dot > bestDot && p.hasLineOfSight(q)) {
				bestDot = dot;
				best = q;
			}
		}
		return best;
	}

	private void pie(ServerPlayer p) {
		if (roundTicks < pieReady) {
			p.sendOverlayMessage(Component.literal("Baking a new pie... " + ((pieReady - roundTicks) / 20 + 1) + "s").withStyle(ChatFormatting.LIGHT_PURPLE));
			return;
		}
		ServerPlayer target = aimedAt(p, 3.5);
		if (target == null || pied.contains(target.getUUID())) {
			p.sendOverlayMessage(Component.literal(target == null ? "Get closer and point right at someone!" : "They're already covered in cream!")
					.withStyle(ChatFormatting.LIGHT_PURPLE));
			return;
		}
		pieReady = roundTicks + 15 * 20;
		pied.add(target.getUUID());
		players.get(p.getUUID()).score++;
		creamed(target);
		target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 50, 0, false, false));
		dev.rubymod.game.Alerts.title(target, "SPLAT!", "You got pied! Keep looking for clues.", ChatFormatting.LIGHT_PURPLE);
		lvl().playSound(null, target.blockPosition(), SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 1.5F, 0.8F);
		lvl().playSound(null, target.blockPosition(), SoundEvents.HONEY_BLOCK_BREAK, SoundSource.PLAYERS, 1.5F, 1.0F);
		lvl().sendParticles(new DustParticleOptions(0xFFF8E0, 2.0F), target.getX(), target.getEyeY(), target.getZ(), 40, 0.4, 0.4, 0.4, 0);
		p.sendOverlayMessage(Component.literal("SPLAT! Got " + target.getName().getString() + "! Next pie in 15 seconds.").withStyle(ChatFormatting.LIGHT_PURPLE));
		tell(Component.literal(target.getName().getString() + " got pied! Who did it?").withStyle(ChatFormatting.LIGHT_PURPLE));
		// Anyone who could see the Prankster just then saw their shirt.
		String colour = SHIRT_NAMES[shirt.getOrDefault(p.getUUID(), 0)];
		for (ServerPlayer w : online()) {
			if (w != p && w != target && players.containsKey(w.getUUID()) && w.distanceTo(p) < 12 && w.hasLineOfSight(p)) {
				clue(w, "You saw someone in a " + colour + " shirt pie " + target.getName().getString() + "!");
			}
		}
		long left = players.entrySet().stream().filter(e -> !e.getValue().watching && !e.getKey().equals(prankster) && !pied.contains(e.getKey())).count();
		if (left == 0) {
			prankWins("Everyone got pied!");
		}
	}

	private void guess(ServerPlayer d) {
		if (roundTicks < guessReady) {
			d.sendOverlayMessage(Component.literal("Gather some clues first! You can guess in " + ((guessReady - roundTicks) / 20 + 1) + "s").withStyle(ChatFormatting.AQUA));
			return;
		}
		ServerPlayer target = aimedAt(d, 6);
		if (target == null) {
			d.sendOverlayMessage(Component.literal("Point the glass right at someone close by!").withStyle(ChatFormatting.AQUA));
			return;
		}
		String who = target.getName().getString();
		tell(Component.literal(d.getName().getString() + " points at " + who + ": \"It was YOU!\"").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		if (target.getUUID().equals(prankster)) {
			lvl().playSound(null, target.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.5F, 1.0F);
			List<UUID> town = new ArrayList<>(players.keySet());
			town.remove(prankster);
			endRound("CAUGHT!", d.getName().getString() + " caught the Prankster: it was " + who + "!", ChatFormatting.AQUA, town);
			return;
		}
		wrongGuesses++;
		guessReady = roundTicks + 30 * 20;
		lvl().playSound(null, target.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 1.5F, 1.0F);
		tell(Component.literal("Not " + who + "! (" + (3 - wrongGuesses) + " guesses left)").withStyle(ChatFormatting.GRAY));
		for (List<String> n : notes.values()) {
			n.add("It's not " + who + " (the Detective checked).");
		}
		if (wrongGuesses >= 3) {
			prankWins("The Detective ran out of guesses!");
		}
	}

	private void prankWins(String why) {
		ServerPlayer p = prankster == null ? null : PartyGames.player(server, prankster);
		String who = p == null ? "someone" : p.getName().getString();
		endRound("THE PRANKSTER WINS!", why + " It was " + who + " all along!", ChatFormatting.LIGHT_PURPLE, prankster == null ? List.of() : List.of(prankster));
	}

	private void clue(ServerPlayer finder, String text) {
		notes.computeIfAbsent(finder.getUUID(), k -> new ArrayList<>()).add(text);
		finder.sendSystemMessage(Component.literal("CLUE: " + text).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
		lvl().playSound(null, finder.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0F, 1.2F);
		if (detective != null && !detective.equals(finder.getUUID())) {
			notes.computeIfAbsent(detective, k -> new ArrayList<>()).add(text + " (from " + finder.getName().getString() + ")");
			ServerPlayer d = PartyGames.player(server, detective);
			if (d != null) {
				d.sendSystemMessage(Component.literal("CLUE from " + finder.getName().getString() + ": " + text).withStyle(ChatFormatting.AQUA));
			}
		}
	}

	@Override
	protected boolean onPickup(ServerPlayer player, Pickup pickup) {
		if (!"clue".equals(pickup.kind)) {
			return false;
		}
		if (player.getUUID().equals(prankster)) {
			player.sendOverlayMessage(Component.literal("A clue about you! You hide it quickly...").withStyle(ChatFormatting.LIGHT_PURPLE));
			return true;
		}
		if (pied.contains(player.getUUID())) {
			player.sendOverlayMessage(Component.literal("You can't read it: you've got cream in your eyes!").withStyle(ChatFormatting.GRAY));
			return false;
		}
		cluesFound++;
		players.get(player.getUUID()).score++;
		clue(player, makeClue());
		return true;
	}

	/** Something true about the Prankster. */
	private String makeClue() {
		var random = lvl().getRandom();
		ServerPlayer p = prankster == null ? null : PartyGames.player(server, prankster);
		List<UUID> innocents = new ArrayList<>(players.keySet());
		innocents.remove(prankster);
		if (p == null || innocents.isEmpty()) {
			return "Crumbs everywhere... but no clue who.";
		}
		int kind = random.nextInt(4);
		if (cluesFound >= 4 && !letterGiven && players.size() >= 4) {
			letterGiven = true;
			return "A note signed with a squiggle: the Prankster's name starts with \"" + p.getName().getString().substring(0, 1).toUpperCase() + "\".";
		}
		UUID innocent = innocents.get(random.nextInt(innocents.size()));
		Part ip = players.get(innocent);
		switch (kind) {
			case 0 -> {
				return "It's NOT " + ip.name + " (they were somewhere else).";
			}
			case 1 -> {
				return "A thread of cloth: the Prankster's shirt is NOT " + SHIRT_NAMES[shirt.getOrDefault(innocent, 0)] + ".";
			}
			case 2 -> {
				BlockPos c = center();
				String where = (p.getZ() < c.getZ() ? "north" : "south") + (p.getX() < c.getX() ? "-west" : "-east");
				return "Creamy footprints, fresh! The Prankster is somewhere in the " + where + " of town right now.";
			}
			default -> {
				return "Pie crumbs on a sleeve: the Prankster's shirt is " + (dark(shirt.getOrDefault(prankster, 0)) ? "a dark colour." : "a bright colour.");
			}
		}
	}

	private static boolean dark(int colour) {
		return colour == 1 || colour == 3 || colour == 5 || colour == 8 || colour == 10;
	}

	@Override
	protected void tickRound() {
		if (roundTicks == pieReady && prankster != null) {
			ServerPlayer p = PartyGames.player(server, prankster);
			if (p != null) {
				p.sendOverlayMessage(Component.literal("Your pie's ready! Sneak up on someone...").withStyle(ChatFormatting.LIGHT_PURPLE));
			}
		}
		if (roundTicks == guessReady && detective != null) {
			ServerPlayer d = PartyGames.player(server, detective);
			if (d != null) {
				d.sendOverlayMessage(Component.literal("You can make a guess now: point your glass at someone and right-click.").withStyle(ChatFormatting.AQUA));
			}
		}
		if (roundTicks >= ROUND_TICKS) {
			prankWins("Time's up: the Prankster got away!");
		}
	}

	@Override
	protected void onLeft(UUID id, Part part) {
		if (id.equals(prankster)) {
			List<UUID> town = new ArrayList<>(players.keySet());
			town.remove(id);
			endRound("THE PRANKSTER WENT HOME", "It was " + part.name + "! Nobody gets pied today.", ChatFormatting.AQUA, town);
		} else if (id.equals(detective)) {
			// Someone else takes over the case.
			for (UUID other : players.keySet()) {
				if (!other.equals(prankster) && !other.equals(id)) {
					detective = other;
					players.get(other).team = DETECTIVE;
					ServerPlayer d = PartyGames.player(server, other);
					if (d != null) {
						give(d, named(Items.COMPASS, GLASS, ChatFormatting.AQUA));
						dev.rubymod.game.Alerts.title(d, "YOU'RE THE DETECTIVE NOW!", "The Detective went home: it's up to you", ChatFormatting.AQUA);
					}
					return;
				}
			}
		}
	}

	@Override
	protected List<Component> extraAwards() {
		List<Component> lines = new ArrayList<>();
		ServerPlayer p = prankster == null ? null : PartyGames.player(server, prankster);
		if (p != null) {
			lines.add(award("The Prankster", p.getName().getString() + " (" + players.getOrDefault(prankster, new Part("")).score + " pies)", ChatFormatting.LIGHT_PURPLE));
		}
		players.entrySet().stream().filter(e -> !e.getKey().equals(prankster) && e.getValue().score > 0)
				.max(java.util.Comparator.comparingInt(e -> e.getValue().score))
				.ifPresent(e -> lines.add(award("Best clue finder", e.getValue().name + " (" + e.getValue().score + ")", ChatFormatting.YELLOW)));
		return lines;
	}
}
