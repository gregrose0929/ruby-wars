package dev.rubymod.game.party;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.rubymod.game.Duels;
import dev.rubymod.game.TrainingGround;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Free Roam: a playground with every gun, weapon, vehicle and aircraft in the game (the
 * training ground's layout: gun range with chests of everything, vehicle yard, airfield
 * with jets and a helicopter, bombing and air defense ranges, industry yard). No rounds
 * and no score: come and go as you like, ammo refills itself, and anyone can press the
 * big red button on the spawn pad to put the whole place back the way it was.
 *
 * Everything in the playground carries the tag of the build it belongs to, so a reset
 * also clears vehicles and drones sitting in chunks nobody had loaded (they're dropped
 * as soon as they load).
 */
public class FreeRoam extends PartyGame {
	private static final String GEN = "rubymod_fr_";
	/** The reset button, relative to the spawn pad: a polished blackstone base, a redstone block, a button on top. */
	private static final int BUTTON_X = 4;
	private static final int BUTTON_Z = 4;
	private static final int RESET_COOLDOWN = 30 * 20;
	private static final Set<Item> REFILL = Set.of(ModItems.LIGHT_AMMO, ModItems.HEAVY_AMMO, ModItems.SHOTGUN_SHELL, ModItems.SNIPER_ROUND,
			ModItems.RUBY_CELL, ModItems.ROCKET, ModItems.AA_MISSILE, ModItems.AT_MISSILE, ModItems.MORTAR_SHELL, ModItems.FRAG_GRENADE,
			ModItems.SMOKE_GRENADE, ModItems.C4, ModItems.BREACHING_CHARGE);

	private static FreeRoam instance;

	/** This build's tag; anything with an older one is left over from before a reset. */
	private String gen = GEN + "0";
	private boolean wasBuilding;
	/** Ticks until the machines get stocked after a build (the commands need a moment). */
	private int finishIn = -1;
	/** Counting down to a reset (-1: none coming). */
	private int resetIn = -1;
	private boolean resetting;
	private long nextReset;
	private int[] forced;
	/** Built since the server started (anything older is cleared as it loads: its tag is unknown). */
	private boolean builtThisRun;
	/** The button was pressed: clear and build even though it's built. */
	private boolean rebuild;
	private final List<String> chestCommands = new ArrayList<>();

	public FreeRoam() {
		super("freeroam", "Free Roam", "Try every gun, weapon, vehicle and aircraft together. No rounds, no score: ammo refills itself, "
				+ "and the big red button on the spawn pad resets the whole world.", "rubymod:attack_heli", 1, false, true, false);
		instance = this;
		for (String cmd : TrainingGround.layout(false, 2)) {
			if (cmd.startsWith("setblock") && cmd.contains("chest[") && cmd.contains("{Items")) {
				chestCommands.add(cmd);
			}
		}
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(-100000, GROUND + 1, 0);
	}

	/** The playground and the sky around it (jets fly well past the runway). */
	private AABB area() {
		BlockPos c = center();
		return new AABB(c.getX() - 400, -64, c.getZ() - 400, c.getX() + 400, 400, c.getZ() + 1100);
	}

	@Override
	protected String roundStatus() {
		int n = activeCount();
		return n + (n == 1 ? " player" : " players") + " exploring";
	}

	// ----- Building -----

	@Override
	protected void build(ServerLevel level) {
		gen = GEN + Long.toString(System.currentTimeMillis(), 36);
		BlockPos c = center();
		// Commands only work in loaded chunks: keep the whole ground loaded until it's built.
		int[] ch = TrainingGround.chunks(c);
		forced = ch;
		for (int cx = ch[0]; cx <= ch[1]; cx++) {
			for (int cz = ch[2]; cz <= ch[3]; cz++) {
				int fx = cx;
				int fz = cz;
				later(() -> {
					level.setChunkForced(fx, fz, true);
					level.getChunk(fx, fz);
				});
			}
		}
		// Take the button off first, or clearing the block under it pops it off as an item.
		later(() -> run(level, "setblock ~" + BUTTON_X + " ~2 ~" + BUTTON_Z + " air"));
		for (String cmd : TrainingGround.layout(false, 2)) {
			later(() -> run(level, cmd));
		}
		// The reset button.
		List<String> button = new ArrayList<>();
		button.add("setblock ~" + BUTTON_X + " ~ ~" + BUTTON_Z + " polished_blackstone");
		button.add("setblock ~" + BUTTON_X + " ~1 ~" + BUTTON_Z + " redstone_block");
		button.add("setblock ~" + BUTTON_X + " ~2 ~" + BUTTON_Z + " crimson_button[face=floor]");
		TrainingGround.label(button, BUTTON_X + 0.5, 3.4, BUTTON_Z + 0.5, "RESET WORLD", "red", 1.2);
		TrainingGround.label(button, BUTTON_X + 0.5, 2.9, BUTTON_Z + 0.5, "Press to rebuild everything", "gray", 0.6);
		for (String cmd : button) {
			later(() -> run(level, cmd));
		}
	}

	private void run(ServerLevel level, String cmd) {
		CommandSourceStack source = server.createCommandSourceStack().withLevel(level).withPosition(Vec3.atBottomCenterOf(center()))
				.withSuppressedOutput();
		server.getCommands().performPrefixedCommand(source, cmd);
	}

	/** Every tick, playing or not: notice when a build has finished. */
	private void watchBuild() {
		ServerLevel level = level();
		if (level == null) {
			return;
		}
		boolean now = building();
		if (wasBuilding && !now) {
			finishIn = 20;
		}
		wasBuilding = now;
		if (finishIn > 0 && --finishIn == 0) {
			finishIn = -1;
			finishBuild(level);
		}
	}

	/** The blocks are down: stock the machines, send up the drones, tag it all, let the chunks go. */
	private void finishBuild(ServerLevel level) {
		BlockPos c = center();
		TrainingGround.stock(level, c);
		TrainingGround.drones(level, c);
		tagAll(level);
		if (forced != null) {
			for (int cx = forced[0]; cx <= forced[1]; cx++) {
				for (int cz = forced[2]; cz <= forced[3]; cz++) {
					level.setChunkForced(cx, cz, false);
				}
			}
			forced = null;
		}
		if (resetting) {
			resetting = false;
			for (ServerPlayer p : online()) {
				toPad(p);
				ready(p);
				dev.rubymod.game.Alerts.title(p, "WORLD RESET", "Everything's back where it started", ChatFormatting.GREEN);
				p.level().playSound(null, p.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.8F, 1.2F);
			}
		}
	}

	/** Mark everything in the playground as this build's (vehicles people place get it within a second). */
	private void tagAll(ServerLevel level) {
		for (Entity e : level.getEntities((Entity) null, area(), e -> !(e instanceof Player))) {
			String old = buildTag(e);
			if (old == null) {
				e.addTag(gen);
			} else if (!old.equals(gen)) {
				e.discard();
			}
		}
	}

	private static String buildTag(Entity e) {
		for (String t : e.entityTags()) {
			if (t.startsWith(GEN)) {
				return t;
			}
		}
		return null;
	}

	/**
	 * The world stays as people left it between visits (no wait to get in): it's only
	 * rebuilt by the button, or on the first visit after the server starts.
	 */
	@Override
	public void queueBuild() {
		if (builtThisRun && !rebuild) {
			return;
		}
		rebuild = false;
		builtThisRun = true;
		super.queueBuild();
	}

	@Override
	protected void clearArea() {
		ServerLevel level = level();
		if (level == null || builtThisRun && !rebuild) {
			return;
		}
		for (Entity e : level.getEntities((Entity) null, area(), e -> !(e instanceof Player))) {
			e.discard();
		}
		spawned.clear();
	}

	// ----- Playing -----

	@Override
	public void tick() {
		if (server == null) {
			return;
		}
		// Build in the background once the server's up, so the first visit doesn't wait.
		if (!builtThisRun && phase == Phase.IDLE && level() != null) {
			queueBuild();
		}
		watchBuild();
		// No countdown: straight in as soon as the ground is built.
		if (phase == Phase.LOBBY && !building() && timer > 1) {
			timer = 1;
		}
		super.tick();
	}

	/** A (re)started server: nothing built yet this run. */
	@Override
	public void reset() {
		super.reset();
		gen = GEN + "0";
		builtThisRun = false;
		rebuild = false;
		wasBuilding = false;
		finishIn = -1;
		resetIn = -1;
		resetting = false;
		forced = null;
	}

	@Override
	protected void startRound() {
		resetIn = -1;
		for (ServerPlayer p : online()) {
			spawn(p, players.get(p.getUUID()));
			dev.rubymod.game.Alerts.title(p, "FREE ROAM", "Try everything! Chests are at the gun range (west)", ChatFormatting.GOLD);
		}
	}

	@Override
	protected void spawn(ServerPlayer player, Part part) {
		toPad(player);
		ready(player);
		// A starter kit the first time; after that you keep what you picked up.
		if (part.level == 0) {
			part.level = 1;
			clearPlayer(player);
			give(player, gun(ModItems.ARR_RIFLE), gun(ModItems.SERVICE_PISTOL), new ItemStack(ModItems.FRAG_GRENADE, 4),
					new ItemStack(ModItems.HEAVY_AMMO, 64), new ItemStack(ModItems.LIGHT_AMMO, 64));
			player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.RUBY_HELMET));
			player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.RUBY_CHESTPLATE));
		}
	}

	private void toPad(ServerPlayer player) {
		player.stopRiding();
		BlockPos c = center();
		teleport(player, c.getX() + 0.5 + player.getRandom().nextGaussian() * 1.5, c.getY(), c.getZ() + 0.5 + player.getRandom().nextGaussian() * 1.5, 0);
	}

	@Override
	protected void tickRound() {
		ServerLevel level = level();
		if (resetIn > 0) {
			if (resetIn % 20 == 0) {
				int s = resetIn / 20;
				for (ServerPlayer p : online()) {
					dev.rubymod.game.Alerts.title(p, String.valueOf(s), "The world resets in " + s + "...", ChatFormatting.RED);
					p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.8F, 0.8F);
				}
			}
			resetIn--;
		} else if (resetIn == 0) {
			resetIn = -1;
			resetting = true;
			for (ServerPlayer p : online()) {
				toPad(p);
				p.sendOverlayMessage(Component.literal("Rebuilding the world...").withStyle(ChatFormatting.YELLOW));
			}
			rebuild = true;
			clearArea();
			queueBuild();
			wasBuilding = true;
		}
		if (resetting && roundTicks % 20 == 0) {
			for (ServerPlayer p : online()) {
				p.sendOverlayMessage(Component.literal("Rebuilding the world...").withStyle(ChatFormatting.YELLOW));
			}
		}
		// Ammo, grenades and missiles top themselves up.
		if (roundTicks % 40 == 0) {
			for (ServerPlayer p : online()) {
				refill(p);
			}
		}
		if (roundTicks % 20 == 0 && !building()) {
			tagAll(level);
		}
		// The chests at the gun range refill every minute.
		if (roundTicks % 1200 == 600 && !building()) {
			for (String cmd : chestCommands) {
				// Clear the spot first: setblock won't replace a chest with the same chest.
				run(level, cmd.substring(0, cmd.indexOf(" chest[")) + " air");
				run(level, cmd);
			}
		}
	}

	private static void refill(ServerPlayer player) {
		var inv = player.getInventory();
		boolean changed = false;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && REFILL.contains(s.getItem()) && s.getCount() < s.getMaxStackSize() / 2) {
				s.setCount(s.getMaxStackSize());
				changed = true;
			}
		}
		if (changed) {
			player.inventoryMenu.broadcastChanges();
		}
	}

	// ----- The reset button -----

	private boolean isButton(BlockPos pos) {
		BlockPos c = center();
		return pos.getX() == c.getX() + BUTTON_X && pos.getZ() == c.getZ() + BUTTON_Z && pos.getY() >= c.getY() && pos.getY() <= c.getY() + 2;
	}

	@Override
	public boolean onUseBlock(ServerPlayer player, BlockPos pos, ItemStack held, BlockPos placeAt) {
		if (!isButton(pos) && !isButton(placeAt)) {
			return false;
		}
		if (phase != Phase.PLAYING || resetting || resetIn >= 0) {
			player.sendOverlayMessage(Component.literal("Already resetting...").withStyle(ChatFormatting.YELLOW));
			return true;
		}
		long now = level().getGameTime();
		if (now < nextReset) {
			player.sendOverlayMessage(Component.literal("The button needs " + (nextReset - now + 19) / 20 + " more seconds to recharge.")
					.withStyle(ChatFormatting.YELLOW));
			return true;
		}
		nextReset = now + RESET_COOLDOWN;
		resetIn = 100;
		level().playSound(null, pos, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 1.0F, 1.0F);
		tell(Component.literal(player.getName().getString() + " pressed the RESET button! The world resets in 5 seconds.").withStyle(ChatFormatting.RED));
		return true;
	}

	@Override
	public boolean allowsBreak(ServerPlayer player, BlockPos pos) {
		return !isButton(pos);
	}

	// ----- Hooks for the rest of the mod -----

	/** The reset button can't be blown up. */
	public static boolean protects(Level level, BlockPos pos) {
		return instance != null && instance.server != null && level.dimension() == Duels.ARENA && instance.isButton(pos);
	}

	/** An entity loading in from before the last reset (or while nobody's here): it goes. */
	public static boolean stale(Entity entity, Level level) {
		if (instance == null || level.dimension() != Duels.ARENA || entity instanceof Player) {
			return false;
		}
		String tag = buildTag(entity);
		return tag != null && !tag.equals(instance.gen) && instance.area().contains(entity.position());
	}

	/** Should something (a training drone coming back) still be placed at pos? Not in a closed Free Roam. */
	public static boolean keeps(Level level, Vec3 pos) {
		return instance == null || level.dimension() != Duels.ARENA || !instance.area().contains(pos) || instance.running();
	}
}
