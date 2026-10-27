package dev.rubymod.game.party;

import java.util.UUID;

import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModItems;

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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Capture the Flag: Red against Blue. Run into the other team's base, grab their flag
 * (walk onto it), and bring it to your own flag. Drop it if you're hit down; your team
 * walks over a dropped flag to send it home. 20 seconds to build sandbag defenses first.
 * First to 3 captures wins.
 */
public class CaptureTheFlag extends PartyGame {
	private static final int HALF_X = 26;
	private static final int HALF_Z = 48;
	private static final int BASE_Z = 42;
	private static final int SETUP_TICKS = 20 * 20;
	private static final int ROUND_TICKS = 8 * 60 * 20;
	private static final int WIN = 3;

	private final int[] captures = new int[2];
	private final boolean[] home = {true, true};
	private final UUID[] carrier = new UUID[2];
	private final BlockPos[] dropped = new BlockPos[2];
	private final int[] droppedAt = new int[2];
	/** Bots: their team, and whether they attack (or defend). */
	private final java.util.Map<UUID, int[]> bots = new java.util.HashMap<>();
	/** Bots coming back: {tick, team, attacker}. */
	private final java.util.List<int[]> botRespawns = new java.util.ArrayList<>();

	public CaptureTheFlag() {
		super("ctf", "Capture the Flag", "Red vs Blue: steal their flag and bring it home. First to 3!", "minecraft:red_wool",
				1, true, true, true);
	}

	@Override
	protected boolean hasBots() {
		return true;
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(-60000, GROUND + 1, 0);
	}

	@Override
	protected int reach() {
		return 70;
	}

	private int baseZ(int team) {
		return team == 0 ? -BASE_Z : BASE_Z;
	}

	private BlockState flagBlock(int team) {
		return Blocks.WOOL.pick(team == 0 ? DyeColor.RED : DyeColor.BLUE).defaultBlockState();
	}

	private BlockPos pedestal(int team) {
		return center().offset(0, 0, baseZ(team));
	}

	@Override
	protected void build(ServerLevel level) {
		flatArena(level, HALF_X, HALF_Z, 25, Blocks.GRASS_BLOCK.defaultBlockState(), true);
		for (int team = 0; team < 2; team++) {
			int z = baseZ(team);
			int s = team == 0 ? -1 : 1;
			BlockState concrete = Blocks.CONCRETE.pick(team == 0 ? DyeColor.RED : DyeColor.BLUE).defaultBlockState();
			box(level, -6, 0, z - 4, 6, 0, z + 4, concrete);
			// Base walls with a gap toward the middle.
			box(level, -7, 1, z - 5 * s, 7, 2, z - 5 * s, Blocks.STONE_BRICKS.defaultBlockState());
			box(level, -2, 1, z - 5 * s, 2, 2, z - 5 * s, Blocks.AIR.defaultBlockState());
			box(level, -7, 1, z - 5 * s, -7, 2, z + 5 * s, Blocks.STONE_BRICKS.defaultBlockState());
			box(level, 7, 1, z - 5 * s, 7, 2, z + 5 * s, Blocks.STONE_BRICKS.defaultBlockState());
			// The flag on its pedestal.
			set(level, 0, 1, z, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
			set(level, 0, 2, z, flagBlock(team));
			set(level, 0, 3, z, Blocks.AIR.defaultBlockState());
		}
		// Cover in the middle (the same on both sides).
		BlockState bricks = Blocks.STONE_BRICKS.defaultBlockState();
		BlockState sandbags = ModBlocks.SANDBAGS.defaultBlockState();
		BlockState crate = Blocks.BARREL.defaultBlockState();
		for (int s : new int[] {1, -1}) {
			box(level, -12 * s, 1, 8 * s, -6 * s, 2, 8 * s, bricks);
			box(level, 6 * s, 1, 18 * s, 12 * s, 1, 18 * s, sandbags);
			box(level, -18 * s, 1, 24 * s, -18 * s, 2, 30 * s, bricks);
			box(level, 16 * s, 1, 28 * s, 16 * s, 2, 32 * s, bricks);
			set(level, 3 * s, 1, 14 * s, crate);
			set(level, 4 * s, 1, 14 * s, crate);
			set(level, 3 * s, 2, 14 * s, crate);
			set(level, -20 * s, 1, 4 * s, crate);
			set(level, 20 * s, 1, -4 * s, crate);
			box(level, -3 * s, 1, 26 * s, 3 * s, 1, 26 * s, sandbags);
		}
		box(level, -3, 1, -1, 3, 3, 1, bricks);
		box(level, -1, 1, -3, 1, 3, 3, bricks);
	}

	private void midWall(boolean up) {
		box(level(), -HALF_X, 1, 0, HALF_X, 4, 0, up ? Blocks.TINTED_GLASS.defaultBlockState() : Blocks.AIR.defaultBlockState());
		if (!up) {
			box(level(), -3, 1, 0, 3, 3, 0, Blocks.STONE_BRICKS.defaultBlockState());
			box(level(), -1, 1, -3, 1, 3, 3, Blocks.STONE_BRICKS.defaultBlockState());
		}
	}

	private boolean setup() {
		return roundTicks < SETUP_TICKS;
	}

	@Override
	protected String roundStatus() {
		if (setup()) {
			return "Build defenses! Go in " + (SETUP_TICKS - roundTicks) / 20 + "s";
		}
		int left = Math.max(0, ROUND_TICKS - roundTicks) / 20;
		return "Red " + captures[0] + " - " + captures[1] + " Blue  •  " + left / 60 + ":" + String.format("%02d", left % 60);
	}

	@Override
	protected float barProgress() {
		return Math.max(captures[0], captures[1]) / (float) WIN;
	}

	@Override
	protected void startRound() {
		captures[0] = 0;
		captures[1] = 0;
		for (int t = 0; t < 2; t++) {
			home[t] = true;
			carrier[t] = null;
			dropped[t] = null;
		}
		midWall(true);
		bots.clear();
		botRespawns.clear();
		// Fill both teams to at least two (and even) with bots: half attack, half defend.
		int want = useBots() ? Math.max(2, Math.max(teamIds(0).size(), teamIds(1).size())) : 0;
		for (int team = 0; team < 2; team++) {
			int add = Math.min(3, want - teamIds(team).size());
			for (int i = 0; i < add; i++) {
				spawnCtfBot(team, i % 2 == 0);
			}
		}
		for (ServerPlayer p : online()) {
			Part part = players.get(p.getUUID());
			spawn(p, part);
			dev.rubymod.game.Alerts.title(p, "YOU'RE " + teamName(part.team).toUpperCase(), "20 seconds to build defenses (sandbags!)",
					part.team == 0 ? ChatFormatting.RED : ChatFormatting.BLUE);
		}
	}

	@Override
	protected void spawn(ServerPlayer player, Part part) {
		int z = baseZ(part.team) + (part.team == 0 ? -3 : 3);
		BlockPos c = center();
		teleport(player, c.getX() + 0.5 + player.getRandom().nextInt(7) - 3, c.getY(), c.getZ() + z + 0.5, part.team == 0 ? 0 : 180);
		ready(player);
		clearPlayer(player);
		give(player, gun(ModItems.ARR_RIFLE), gun(ModItems.SERVICE_PISTOL), new ItemStack(ModItems.HEAVY_AMMO, 64), new ItemStack(ModItems.HEAVY_AMMO, 64),
				new ItemStack(ModItems.LIGHT_AMMO, 48), new ItemStack(ModItems.FRAG_GRENADE, 2), new ItemStack(ModBlocks.SANDBAGS, 16));
		int color = part.team == 0 ? 0xC03030 : 0x3050D0;
		player.setItemSlot(EquipmentSlot.HEAD, dyed(Items.LEATHER_HELMET, color));
		player.setItemSlot(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, color));
		player.setItemSlot(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, color));
		player.setItemSlot(EquipmentSlot.FEET, dyed(Items.LEATHER_BOOTS, color));
		player.getInventory().setSelectedSlot(0);
	}

	private void spawnCtfBot(int team, boolean attacker) {
		BlockPos c = center();
		int z = baseZ(team) + (team == 0 ? -3 : 3);
		var bot = dev.rubymod.entity.SoldierEntity.partyBot(level(), c.offset(level().getRandom().nextInt(7) - 3, 0, z), team);
		if (bot == null) {
			return;
		}
		bot.setWeapon(ModItems.ARR_RIFLE);
		bot.setEasy();
		bot.setCustomName(Component.literal(teamName(team) + " Bot").withStyle(team == 0 ? ChatFormatting.RED : ChatFormatting.BLUE));
		bot.addTag(tag());
		level().addFreshEntity(bot);
		joinTeam(bot, team);
		spawned.add(bot.getUUID());
		bots.put(bot.getUUID(), new int[] {team, attacker ? 1 : 0});
	}

	private static ItemStack dyed(net.minecraft.world.item.Item item, int color) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.DYED_COLOR, new DyedItemColor(color));
		return stack;
	}

	private void putFlagHome(int team) {
		home[team] = true;
		carrier[team] = null;
		if (dropped[team] != null) {
			level().setBlock(dropped[team], Blocks.AIR.defaultBlockState(), 3);
			dropped[team] = null;
		}
		level().setBlock(pedestal(team).above(), flagBlock(team), 3);
	}

	private void take(net.minecraft.world.entity.LivingEntity player, int flag) {
		home[flag] = false;
		carrier[flag] = player.getUUID();
		if (dropped[flag] != null) {
			level().setBlock(dropped[flag], Blocks.AIR.defaultBlockState(), 3);
			dropped[flag] = null;
		}
		level().setBlock(pedestal(flag).above(), Blocks.AIR.defaultBlockState(), 3);
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(flagBlock(flag).getBlock()));
		player.addEffect(new MobEffectInstance(MobEffects.GLOWING, -1, 0, false, false));
		tell(Component.literal(player.getName().getString() + " took the " + teamName(flag) + " flag!")
				.withStyle(flag == 0 ? ChatFormatting.RED : ChatFormatting.BLUE));
		level().playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.5F, 1.2F);
	}

	private void drop(net.minecraft.world.entity.LivingEntity player, int flag, Vec3 at) {
		carrier[flag] = null;
		BlockPos pos = BlockPos.containing(at);
		ServerLevel level = level();
		while (pos.getY() > GROUND + 1 && level.getBlockState(pos.below()).isAir()) {
			pos = pos.below();
		}
		if (!level.getBlockState(pos).isAir()) {
			pos = pos.above();
		}
		dropped[flag] = pos;
		droppedAt[flag] = roundTicks;
		level.setBlock(pos, flagBlock(flag), 3);
		if (player != null) {
			player.removeEffect(MobEffects.GLOWING);
		}
		tell(Component.literal("The " + teamName(flag) + " flag was dropped!").withStyle(ChatFormatting.YELLOW));
	}

	@Override
	protected void onDowned(ServerPlayer victim, Part part, Entity killer) {
		part.respawnIn = 60;
		for (int flag = 0; flag < 2; flag++) {
			if (victim.getUUID().equals(carrier[flag])) {
				drop(victim, flag, victim.position());
			}
		}
	}

	@Override
	protected void onEntityDied(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.damagesource.DamageSource source) {
		int[] bot = bots.remove(entity.getUUID());
		if (bot == null) {
			return;
		}
		for (int flag = 0; flag < 2; flag++) {
			if (entity.getUUID().equals(carrier[flag])) {
				drop(entity, flag, entity.position());
			}
		}
		if (source.getEntity() instanceof ServerPlayer k && players.containsKey(k.getUUID())) {
			players.get(k.getUUID()).kills++;
		}
		if (phase == Phase.PLAYING) {
			botRespawns.add(new int[] {roundTicks + 200, bot[0], bot[1]});
		}
	}

	/** Every half second: send each bot after a flag, home with one, or to defend. */
	private void steerBots() {
		ServerLevel level = level();
		for (UUID id : bots.keySet()) {
			if (!(level.getEntity(id) instanceof dev.rubymod.entity.SoldierEntity bot) || !bot.isAlive()) {
				continue;
			}
			int[] b = bots.get(id);
			int mine = b[0];
			int theirs = 1 - mine;
			BlockPos goal;
			if (setup()) {
				goal = pedestal(mine).offset(0, 0, mine == 0 ? 6 : -6);
			} else if (id.equals(carrier[theirs])) {
				goal = pedestal(mine);
			} else if (b[1] == 1) {
				// Attacker: their flag (or escort whoever has it).
				Entity runner = carrier[theirs] == null ? null : findRunner(carrier[theirs]);
				goal = dropped[theirs] != null ? dropped[theirs] : home[theirs] ? pedestal(theirs) : runner != null ? runner.blockPosition() : pedestal(theirs);
			} else {
				// Defender: get our flag back, or guard it.
				Entity thief = carrier[mine] == null ? null : findRunner(carrier[mine]);
				goal = dropped[mine] != null ? dropped[mine] : thief != null ? thief.blockPosition()
						: pedestal(mine).offset(level.getRandom().nextInt(9) - 4, 0, (mine == 0 ? 1 : -1) * (3 + level.getRandom().nextInt(4)));
			}
			bot.setObjective(goal);
		}
	}

	private Entity findRunner(UUID id) {
		ServerPlayer p = PartyGames.player(server, id);
		return p != null ? p : level().getEntity(id);
	}

	@Override
	protected void onLeft(UUID id, Part part) {
		for (int flag = 0; flag < 2; flag++) {
			if (id.equals(carrier[flag])) {
				putFlagHome(flag);
			}
		}
	}

	@Override
	protected void tickRound() {
		if (roundTicks == SETUP_TICKS) {
			midWall(false);
			titleAll("GO!", "Grab their flag and bring it home", ChatFormatting.GOLD);
		}
		ServerLevel level = level();
		for (var it = botRespawns.iterator(); it.hasNext();) {
			int[] r = it.next();
			if (roundTicks >= r[0]) {
				it.remove();
				spawnCtfBot(r[1], r[2] == 1);
			}
		}
		if (roundTicks % 10 == 0) {
			steerBots();
		}
		if (roundTicks % 4 == 0 && !setup()) {
			java.util.List<net.minecraft.world.entity.LivingEntity> runners = new java.util.ArrayList<>();
			for (ServerPlayer p : online()) {
				Part part = players.get(p.getUUID());
				if (!part.watching && !p.isSpectator() && part.respawnIn <= 0) {
					runners.add(p);
				}
			}
			for (UUID id : bots.keySet()) {
				if (level.getEntity(id) instanceof net.minecraft.world.entity.LivingEntity bot && bot.isAlive()) {
					runners.add(bot);
				}
			}
			for (net.minecraft.world.entity.LivingEntity p : runners) {
				Part part = players.get(p.getUUID());
				int mine = part != null ? part.team : bots.get(p.getUUID())[0];
				int theirs = 1 - mine;
				Vec3 pos = p.position();
				// Grab their flag (from its pedestal, or where it was dropped).
				if (carrier[theirs] == null && (home[theirs] && pos.distanceTo(Vec3.atBottomCenterOf(pedestal(theirs).above())) < 1.8
						|| dropped[theirs] != null && pos.distanceTo(Vec3.atBottomCenterOf(dropped[theirs])) < 1.5)) {
					take(p, theirs);
				}
				// Send our dropped flag home.
				if (dropped[mine] != null && pos.distanceTo(Vec3.atBottomCenterOf(dropped[mine])) < 1.5) {
					putFlagHome(mine);
					tell(Component.literal(p.getName().getString() + " returned the " + teamName(mine) + " flag.").withStyle(ChatFormatting.GRAY));
				}
				// Capture: their flag to our pedestal, while ours is home.
				if (p.getUUID().equals(carrier[theirs]) && home[mine] && pos.distanceTo(Vec3.atBottomCenterOf(pedestal(mine).above())) < 2.2) {
					captures[mine]++;
					if (part != null) {
						part.score++;
					}
					p.removeEffect(MobEffects.GLOWING);
					p.setItemSlot(EquipmentSlot.HEAD, part != null ? dyed(Items.LEATHER_HELMET, mine == 0 ? 0xC03030 : 0x3050D0) : ItemStack.EMPTY);
					putFlagHome(theirs);
					titleAll(teamName(mine).toUpperCase() + " SCORES!", p.getName().getString() + " captured the flag (" + captures[0] + " - " + captures[1] + ")",
							mine == 0 ? ChatFormatting.RED : ChatFormatting.BLUE);
					for (ServerPlayer q : online()) {
						q.level().playSound(null, q.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.7F, 1.0F);
					}
					if (captures[mine] >= WIN) {
						endRound(teamName(mine).toUpperCase() + " WINS!", "Captures: Red " + captures[0] + " - " + captures[1] + " Blue. "
								+ "Flag runner: " + p.getName().getString(), mine == 0 ? ChatFormatting.RED : ChatFormatting.BLUE, teamIds(mine));
						return;
					}
				}
			}
			// Dropped flags go home on their own after 20 seconds.
			for (int flag = 0; flag < 2; flag++) {
				if (dropped[flag] != null && roundTicks - droppedAt[flag] > 400) {
					putFlagHome(flag);
					tell(Component.literal("The " + teamName(flag) + " flag went home.").withStyle(ChatFormatting.GRAY));
				}
				BlockPos at = home[flag] ? pedestal(flag).above() : dropped[flag];
				if (at != null && roundTicks % 20 == 0) {
					level.sendParticles(new DustParticleOptions(flag == 0 ? 0xFF3030 : 0x3060FF, 2.0F), at.getX() + 0.5, at.getY() + 1.5, at.getZ() + 0.5,
							10, 0.2, 1.5, 0.2, 0);
				}
			}
		}
		if (roundTicks >= ROUND_TICKS) {
			String head = captures[0] == captures[1] ? "DRAW" : (captures[0] > captures[1] ? "RED" : "BLUE") + " WINS!";
			endRound(head, "Time's up: Red " + captures[0] + " - " + captures[1] + " Blue", ChatFormatting.GOLD,
					captures[0] == captures[1] ? java.util.List.<UUID>of() : teamIds(captures[0] > captures[1] ? 0 : 1));
		}
	}

	/** Only sandbags can be broken (the arena stays put). */
	@Override
	public boolean allowsBreak(ServerPlayer player, BlockPos pos) {
		return level().getBlockState(pos).is(ModBlocks.SANDBAGS);
	}

	/** Players can place their sandbags, nothing else. */
	@Override
	public boolean onUseBlock(ServerPlayer player, BlockPos pos, ItemStack held, BlockPos placeAt) {
		return phase == Phase.PLAYING && held.getItem() instanceof net.minecraft.world.item.BlockItem && !held.is(ModBlocks.SANDBAGS.asItem());
	}
}
