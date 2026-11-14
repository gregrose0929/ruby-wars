package dev.rubymod.game.hub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.game.Alerts;
import dev.rubymod.game.Hub;
import dev.rubymod.game.Props;
import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.registry.ModBlocks;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The hub's park: six little islands floating round the plaza, reached by launch pads, and the toys on the plaza.
 *
 * <ul>
 * <li>Fun Park (east): the rollercoaster station, trampolines, a bumper-car ice rink, the shooting gallery, a snow fort.</li>
 * <li>The Tower (north-east): a tower to climb, a water slide spiralling down it, a diving board, a zipline home.</li>
 * <li>The Pond (south): fishing, swings, and the splash pool the plaza's cannon fires you into.</li>
 * <li>Mini Golf (south-west): three holes.</li>
 * <li>The Stage (north-west): a DJ booth, a dance floor that lights up to the music, a photo spot.</li>
 * <li>Sky parkour (north-west): a jumping course in the sky, with checkpoints.</li>
 * <li>On the plaza: the launch pads, the cannon, the toy chest, a fireworks button, benches, and some critters.</li>
 * </ul>
 *
 * Rebuilt (blocks and display entities) every time the server starts; rides and the like are in HubRides and HubGames.
 */
public final class HubPark {
	public static final int FLOOR = Hub.FLOOR;
	static final String TAG = "rubymod_park";
	private static final String CRITTER = "rubymod_critter";
	/** The park's display entities from this build (any others with the tag are from before, and go). */
	private static final Set<UUID> KEEP = new HashSet<>();

	static final AABB WHOLE_HUB = new AABB(-130, FLOOR - 40, -130, 130, FLOOR + 70, 130);
	// Fun Park
	private static final int FX = 56;
	private static final int FZ = 2;
	static final AABB GALLERY_LINE = new AABB(45, FLOOR, -6, 60, FLOOR + 3, -1);
	static final BlockPos GALLERY_START = new BlockPos(52, FLOOR, -2);
	static final List<BlockPos> GALLERY_WALL = new ArrayList<>();
	static final BlockState GALLERY_WALL_BLOCK = Blocks.SPRUCE_PLANKS.defaultBlockState();
	// The Pond
	static final AABB POND = new AABB(-8, FLOOR - 4, 46, 6, FLOOR + 2, 57);
	// Mini Golf
	static final List<BlockPos> GOLF_TEES = new ArrayList<>();
	static final List<BlockPos> GOLF_HOLES = new ArrayList<>();
	// The Stage
	static final Vec3 STAGE_CENTER = new Vec3(-46, FLOOR + 2, -40);
	static final List<BlockPos> DANCE_FLOOR = new ArrayList<>();
	// The plaza's things you click.
	private static final BlockPos TOY_CHEST = new BlockPos(3, FLOOR + 1, 10);
	private static final BlockPos FIREWORKS = new BlockPos(-6, FLOOR + 2, -4);
	private static final BlockPos ROD_BARREL = new BlockPos(-2, FLOOR + 1, 45);
	private static final BlockPos SNOW_PILE = new BlockPos(68, FLOOR + 1, 5);
	private static final BlockPos JUKEBOX = new BlockPos(-46, FLOOR + 2, -40);

	private record Pad(String label, Vec3 to) {
	}

	/** Launch pads: each of the 3x3 blocks of a pad, and where it sends you. */
	private static final Map<BlockPos, Pad> PADS = new HashMap<>();
	private static final Set<UUID> ON_PAD = new HashSet<>();
	private static final Set<BlockPos> TRAMPOLINES = new HashSet<>();
	private static final List<BlockPos> LAMPS = new ArrayList<>();
	private static boolean lampsLit;
	/** Rides' paths. */
	private static List<Vec3> coaster = List.of();
	private static List<Vec3> slide = List.of();
	private static List<Vec3> zip = List.of();
	private static final Set<BlockPos> COASTER_BOARD = new HashSet<>();
	private static final BlockPos SLIDE_START = new BlockPos(48, FLOOR + 16, -44);
	private static final BlockPos ZIP_START = new BlockPos(40, FLOOR + 16, -44);
	private static final BlockPos CANNON = new BlockPos(0, FLOOR, 20);
	private static final Vec3 CANNON_TARGET = new Vec3(-8.5, FLOOR - 1, 62.5);
	private static final Map<UUID, Integer> CANNON_COUNT = new HashMap<>();
	// Parkour: its platforms' tops, which are checkpoints, and each player's last checkpoint.
	private static final List<BlockPos> PARKOUR = new ArrayList<>();
	private static final Set<Integer> CHECKPOINTS = new HashSet<>();
	private static final Map<UUID, Integer> PARKOUR_AT = new HashMap<>();
	private static final Map<UUID, Long> PARKOUR_STARTED = new HashMap<>();
	private static AABB parkourZone = new AABB(0, 0, 0, 0, 0, 0);
	private static final Map<UUID, Double> BOUNCE_TOP = new HashMap<>();
	private static int fireworksUntil;
	private static int fireworksReady;
	private static int tick;

	private HubPark() {
	}

	// ----- Hooks (from GameEvents) -----

	public static void register() {
		net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || level.dimension() != Hub.HUB || !(player instanceof ServerPlayer sp)) {
				return InteractionResult.PASS;
			}
			if (HubGames.golfHit(sp, entity) || HubGames.chickenHit(sp, entity)) {
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || level.dimension() != Hub.HUB || !(player instanceof ServerPlayer sp)) {
				return InteractionResult.PASS;
			}
			if (HubRides.isSwingSeat(entity) && !entity.isVehicle() && !sp.isPassenger()) {
				sp.startRiding(entity, true, true);
				sp.sendOverlayMessage(Component.literal("Wheee! Crouch to get off.").withStyle(ChatFormatting.YELLOW));
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
	}

	/** Right-clicking a block in the hub. Null: not one of ours. */
	public static InteractionResult useBlock(ServerPlayer p, BlockPos pos, BlockState state) {
		if (pos.equals(TOY_CHEST)) {
			HubGames.toyChest(p);
			return InteractionResult.SUCCESS;
		}
		if (pos.equals(FIREWORKS)) {
			fireworksButton(p);
			return InteractionResult.SUCCESS;
		}
		if (pos.equals(ROD_BARREL)) {
			HubGames.fishingRods(p);
			return InteractionResult.SUCCESS;
		}
		if (pos.equals(SNOW_PILE)) {
			HubGames.snowPile(p);
			return InteractionResult.SUCCESS;
		}
		if (pos.equals(JUKEBOX)) {
			HubGames.nextSong(p);
			return InteractionResult.SUCCESS;
		}
		if (state.getBlock() instanceof StairBlock) {
			HubRides.sit(p, pos);
			return InteractionResult.SUCCESS;
		}
		return null;
	}

	/** Right-clicking with an item in the hub: the toys. */
	public static boolean useItem(ServerPlayer p, ItemStack held) {
		return HubGames.useToy(p, held);
	}

	/** A park entity left over from before (saved with the world): it goes. */
	public static boolean stale(Entity e) {
		return e.entityTags().contains(TAG) && !KEEP.contains(e.getUUID()) || HubRides.stale(e);
	}

	static void keep(Entity e) {
		if (e != null) {
			e.addTag(TAG);
			KEEP.add(e.getUUID());
		}
	}

	static void live(Entity e) {
		HubRides.LIVE.add(e.getUUID());
	}

	static boolean water(ServerLevel level, BlockPos pos) {
		return !level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty();
	}

	/** Back on your feet after a ride or a flight: a pad you land on doesn't send you straight off again. */
	static void landed(ServerPlayer p) {
		ON_PAD.add(p.getUUID());
	}

	static void say(ServerLevel level, Vec3 near, String text) {
		for (ServerPlayer p : level.players()) {
			if (p.position().distanceTo(near) < 40) {
				p.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.YELLOW));
			}
		}
	}

	/** A firework burst (the show, a hole in one, the parkour finish). */
	static void burst(ServerLevel level, Vec3 at) {
		var random = level.getRandom();
		int[] colors = {0xFFFF3050, 0xFFFFD040, 0xFF40C8FF, 0xFF60FF80, 0xFFC060FF};
		dev.rubymod.game.rift.RiftFx.burst(level, at, colors[random.nextInt(colors.length)], 50, 1.6);
		level.sendParticles(ParticleTypes.FIREWORK, at.x, at.y, at.z, 40, 0.5, 0.5, 0.5, 0.25);
		level.playSound(null, BlockPos.containing(at), SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.AMBIENT, 2.5F, 0.9F + random.nextFloat() * 0.3F);
	}

	private static void fireworksButton(ServerPlayer p) {
		if (tick < fireworksReady) {
			p.sendOverlayMessage(Component.literal("The fireworks are reloading: " + ((fireworksReady - tick) / 20 + 1) + "s").withStyle(ChatFormatting.GRAY));
			return;
		}
		fireworksUntil = tick + 8 * 20;
		fireworksReady = tick + 25 * 20;
		say((ServerLevel) p.level(), Vec3.ZERO, p.getName().getString() + " lit the fireworks!");
	}

	// ----- Building -----

	private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
		BlockPos pos = new BlockPos(x, y, z);
		if (level.getBlockState(pos) != state) {
			level.setBlock(pos, state, Block.UPDATE_CLIENTS);
		}
	}

	private static void box(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
					set(level, x, y, z, state);
				}
			}
		}
	}

	private static void ifAir(ServerLevel level, int x, int y, int z, BlockState state) {
		if (level.getBlockState(new BlockPos(x, y, z)).isAir()) {
			set(level, x, y, z, state);
		}
	}

	/** A floating island: grass on top at FLOOR (or {@code top}), a rocky underside tapering to a point, a low hedge round the rim. */
	private static void island(ServerLevel level, int cx, int cz, int r, int top) {
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int x = -r - 1; x <= r + 1; x++) {
			for (int z = -r - 1; z <= r + 1; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d > r + 0.4) {
					continue;
				}
				set(level, cx + x, top, cz + z, Blocks.GRASS_BLOCK.defaultBlockState());
				for (int y = 1; y <= 12; y++) {
					set(level, cx + x, top + y, cz + z, air);
				}
				for (int dd = 1; dd <= r; dd++) {
					boolean solid = d < r - dd * 1.15 + Math.sin(x * 0.7 + z * 0.5) * 0.8;
					set(level, cx + x, top - dd, cz + z, !solid ? air : dd == 1 ? Blocks.DIRT.defaultBlockState()
							: (x * 7 + z * 11 + dd * 5) % 37 == 0 ? ModBlocks.RUBY_BLOCK.defaultBlockState()
									: dd < 4 ? Blocks.STONE.defaultBlockState() : Blocks.DEEPSLATE.defaultBlockState());
				}
				if (d > r - 0.9) {
					set(level, cx + x, top + 1, cz + z, Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState()
							.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
				}
			}
		}
	}

	private static void lamp(ServerLevel level, int x, int y, int z) {
		box(level, x, y, z, x, y + 2, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
		set(level, x, y + 3, z, Blocks.GLASS.defaultBlockState());
		LAMPS.add(new BlockPos(x, y + 3, z));
	}

	private static Display.TextDisplay label(ServerLevel level, double x, double y, double z, String text, ChatFormatting color, float scale) {
		Display.TextDisplay d = Props.text(level, new Vec3(x, y, z), Component.literal(text).withStyle(color, ChatFormatting.BOLD), scale, TAG);
		keep(d);
		return d;
	}

	private static Display.BlockDisplay piece(ServerLevel level, Vec3 at, BlockState state, Vector3f size, Quaternionf rot) {
		Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d == null) {
			return null;
		}
		d.snapTo(at.x, at.y, at.z, 0, 0);
		((BlockDisplayAccessor) d).rubymod$setBlockState(state);
		Vector3f half = rot.transform(new Vector3f(size).mul(0.5F));
		((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f().sub(half), rot, size, new Quaternionf()));
		((DisplayAccessor) d).rubymod$setViewRange(6.0F);
		level.addFreshEntity(d);
		keep(d);
		return d;
	}

	/** A launch pad (3x3, glowing middle) at x, y, z, sending you to {@code to}. */
	private static void pad(ServerLevel level, int x, int y, int z, String label, Vec3 to) {
		Pad pad = new Pad(label, to);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				set(level, x + dx, y, z + dz, dx == 0 && dz == 0 ? Blocks.EMERALD_BLOCK.defaultBlockState() : Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState());
				set(level, x + dx, y + 1, z + dz, Blocks.AIR.defaultBlockState());
				set(level, x + dx, y + 2, z + dz, Blocks.AIR.defaultBlockState());
				PADS.put(new BlockPos(x + dx, y, z + dz), pad);
			}
		}
		label(level, x + 0.5, y + 2.6, z + 0.5, "↗ " + label, ChatFormatting.GREEN, 1.0F);
	}

	public static void build(ServerLevel level) {
		for (int cx = -5; cx <= 4; cx++) {
			for (int cz = -6; cz <= 4; cz++) {
				level.getChunk(cx, cz);
			}
		}
		for (Entity old : level.getEntities((Entity) null, WHOLE_HUB, e -> e.entityTags().contains(TAG) || e.entityTags().contains(HubRides.TEMP))) {
			old.discard();
		}
		KEEP.clear();
		PADS.clear();
		TRAMPOLINES.clear();
		LAMPS.clear();
		GALLERY_WALL.clear();
		GOLF_TEES.clear();
		GOLF_HOLES.clear();
		DANCE_FLOOR.clear();
		PARKOUR.clear();
		CHECKPOINTS.clear();
		COASTER_BOARD.clear();
		HubRides.reset();
		HubGames.reset();
		plaza(level);
		funPark(level);
		tower(level);
		pond(level);
		golf(level);
		stage(level);
		parkour(level);
		coasterTrack(level);
	}

	private static Vec3 stand(double x, int y, double z) {
		return new Vec3(x + 0.5, y + 1, z + 0.5);
	}

	// ----- The plaza -----

	private static void plaza(ServerLevel level) {
		// Launch pads round the garden, one to each island.
		pad(level, 21, FLOOR, 0, "Fun Park", stand(46, FLOOR, 1));
		pad(level, 19, FLOOR, -8, "The Tower", stand(37, FLOOR, -40));
		pad(level, 11, FLOOR, 18, "The Pond", stand(6, FLOOR, 46));
		pad(level, -11, FLOOR, 18, "Mini Golf", stand(-39, FLOOR, 54));
		pad(level, -20, FLOOR, 9, "The Gallery", stand(-33, FLOOR - 2, 6));
		pad(level, -20, FLOOR, -4, "The Stage", stand(-37, FLOOR, -35));
		pad(level, -17, FLOOR, -10, "Sky Parkour", stand(-22, FLOOR + 3, -36));
		// The toy chest, the fireworks button.
		set(level, TOY_CHEST.getX(), TOY_CHEST.getY(), TOY_CHEST.getZ(), Blocks.BARREL.defaultBlockState());
		label(level, TOY_CHEST.getX() + 0.5, TOY_CHEST.getY() + 1.5, TOY_CHEST.getZ() + 0.5, "TOY CHEST", ChatFormatting.LIGHT_PURPLE, 0.9F);
		set(level, FIREWORKS.getX(), FIREWORKS.getY() - 1, FIREWORKS.getZ(), Blocks.QUARTZ_PILLAR.defaultBlockState());
		set(level, FIREWORKS.getX(), FIREWORKS.getY(), FIREWORKS.getZ(), Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.FLOOR));
		label(level, FIREWORKS.getX() + 0.5, FIREWORKS.getY() + 0.9, FIREWORKS.getZ() + 0.5, "FIREWORKS!", ChatFormatting.GOLD, 0.9F);
		// Benches facing the fountain.
		for (int z = 3; z <= 5; z++) {
			set(level, -13, FLOOR + 1, z, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
			set(level, 13, FLOOR + 1, z, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
		}
		// The cannon: aimed over the garden at the Pond's splash pool.
		box(level, -1, FLOOR, 19, 1, FLOOR, 21, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
		set(level, 0, FLOOR, 20, Blocks.REDSTONE_BLOCK.defaultBlockState());
		Vec3 aim = CANNON_TARGET.subtract(0.5, FLOOR, 20.5).normalize();
		float yaw = (float) Math.atan2(aim.x, aim.z);
		Quaternionf barrel = new Quaternionf().rotateY(yaw).rotateX((float) Math.toRadians(-40));
		piece(level, new Vec3(0.5, FLOOR + 2.0, 20.5), Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(), new Vector3f(1.3F, 1.3F, 3.6F), barrel);
		piece(level, new Vec3(0.5, FLOOR + 2.0, 20.5).add(new Vec3(barrel.transform(new Vector3f(0, 0, 1.85F)))),
				Blocks.GOLD_BLOCK.defaultBlockState(), new Vector3f(1.5F, 1.5F, 0.3F), barrel);
		for (int s : new int[] {-1, 1}) {
			piece(level, new Vec3(0.5 + s * 0.95, FLOOR + 1.6, 20.5), Blocks.DARK_OAK_PLANKS.defaultBlockState(), new Vector3f(0.25F, 1.2F, 1.2F), new Quaternionf());
		}
		label(level, 0.5, FLOOR + 4.6, 20.5, "HUMAN CANNON\nstand in it!", ChatFormatting.RED, 0.8F);
		// Where the zipline drops you.
		set(level, 16, FLOOR, -11, Blocks.HAY_BLOCK.defaultBlockState());
	}

	// ----- Fun Park -----

	private static void funPark(ServerLevel level) {
		island(level, FX, FZ, 17, FLOOR);
		label(level, FX + 0.5, FLOOR + 9, FZ + 0.5, "FUN PARK", ChatFormatting.GOLD, 3.0F);
		pad(level, 42, FLOOR, 6, "The Plaza", stand(20, FLOOR, 3));
		// The rollercoaster station.
		box(level, 39, FLOOR, -3, 44, FLOOR, 3, Blocks.POLISHED_ANDESITE.defaultBlockState());
		for (int[] post : new int[][] {{39, -3}, {44, -3}, {39, 3}, {44, 3}}) {
			box(level, post[0], FLOOR + 1, post[1], post[0], FLOOR + 3, post[1], Blocks.RED_NETHER_BRICK_WALL.defaultBlockState());
		}
		box(level, 39, FLOOR + 4, -3, 44, FLOOR + 4, 3, Blocks.WOOL.pick(DyeColor.RED).defaultBlockState());
		for (int z = -1; z <= 1; z++) {
			set(level, 43, FLOOR, z, Blocks.GOLD_BLOCK.defaultBlockState());
			COASTER_BOARD.add(new BlockPos(43, FLOOR, z));
		}
		label(level, 43.5, FLOOR + 2.8, 0.5, "ROLLERCOASTER\nstep on the gold", ChatFormatting.RED, 0.8F);
		// Trampolines.
		for (int[] t : new int[][] {{64, -8}, {69, -3}, {63, -2}}) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					set(level, t[0] + dx, FLOOR, t[1] + dz, Blocks.SLIME_BLOCK.defaultBlockState());
					TRAMPOLINES.add(new BlockPos(t[0] + dx, FLOOR, t[1] + dz));
				}
			}
		}
		label(level, 66.5, FLOOR + 3, -4.5, "TRAMPOLINES", ChatFormatting.GREEN, 1.0F);
		// The bumper-car ice rink.
		box(level, 46, FLOOR, 7, 64, FLOOR, 15, Blocks.BLUE_ICE.defaultBlockState());
		for (int x = 45; x <= 65; x++) {
			for (int z = 6; z <= 16; z++) {
				boolean edge = x == 45 || x == 65 || z == 6 || z == 16;
				boolean gate = z == 6 && x >= 54 && x <= 56;
				set(level, x, FLOOR + 1, z, edge && !gate ? Blocks.SPRUCE_FENCE.defaultBlockState() : Blocks.AIR.defaultBlockState());
			}
		}
		label(level, 55.5, FLOOR + 3.5, 6.5, "BUMPER CARS & ICE RINK", ChatFormatting.AQUA, 1.0F);
		// The shooting gallery.
		box(level, 46, FLOOR + 1, -12, 58, FLOOR + 5, -12, GALLERY_WALL_BLOCK);
		for (int x = 46; x <= 58; x += 2) {
			set(level, x, FLOOR + 5, -12, Blocks.WOOL.pick(DyeColor.RED).defaultBlockState());
		}
		for (int x = 47; x <= 57; x++) {
			for (int y = FLOOR + 2; y <= FLOOR + 4; y++) {
				GALLERY_WALL.add(new BlockPos(x, y, -12));
			}
		}
		box(level, 46, FLOOR + 1, -6, 58, FLOOR + 1, -6, Blocks.SPRUCE_FENCE.defaultBlockState());
		set(level, GALLERY_START.getX(), GALLERY_START.getY(), GALLERY_START.getZ(), Blocks.GOLD_BLOCK.defaultBlockState());
		label(level, 52.5, FLOOR + 6.5, -11.5, "SHOOTING GALLERY", ChatFormatting.AQUA, 1.2F);
		label(level, 52.5, FLOOR + 2.4, -1.5, "Stand on the gold to start", ChatFormatting.WHITE, 0.7F);
		// The snow fort.
		for (int x = 66; x <= 71; x++) {
			for (int z = 1; z <= 9; z++) {
				set(level, x, FLOOR, z, Blocks.SNOW_BLOCK.defaultBlockState());
				boolean wall = (x == 66 || x == 71 || z == 1 || z == 9) && (x + z) % 4 != 0;
				box(level, x, FLOOR + 1, z, x, FLOOR + 2, z, wall ? Blocks.SNOW_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
			}
		}
		set(level, SNOW_PILE.getX(), SNOW_PILE.getY(), SNOW_PILE.getZ(), Blocks.SNOW_BLOCK.defaultBlockState());
		label(level, SNOW_PILE.getX() + 0.5, SNOW_PILE.getY() + 1.6, SNOW_PILE.getZ() + 0.5, "SNOWBALLS\nright-click", ChatFormatting.WHITE, 0.7F);
		for (int[] l : new int[][] {{40, 8}, {60, -14}, {72, 0}, {48, 17}}) {
			lamp(level, l[0], FLOOR + 1, l[1]);
		}
	}

	// ----- The Tower -----

	private static void tower(ServerLevel level) {
		int cx = 44;
		int cz = -44;
		island(level, cx, cz, 11, FLOOR);
		label(level, 36.5, FLOOR + 8, -39.5, "THE TOWER", ChatFormatting.AQUA, 2.5F);
		pad(level, 36, FLOOR, -46, "The Plaza", stand(18, FLOOR, -5));
		BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
		// The tower: 5x5, hollow, a ladder inside up the north wall, a door on the south side.
		for (int y = FLOOR + 1; y <= FLOOR + 15; y++) {
			for (int x = cx - 2; x <= cx + 2; x++) {
				for (int z = cz - 2; z <= cz + 2; z++) {
					boolean wall = x == cx - 2 || x == cx + 2 || z == cz - 2 || z == cz + 2;
					set(level, x, y, z, wall ? (y % 4 == 0 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : brick) : Blocks.AIR.defaultBlockState());
				}
			}
			set(level, cx, y, cz - 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
		}
		box(level, cx, FLOOR + 1, cz + 2, cx, FLOOR + 2, cz + 2, Blocks.AIR.defaultBlockState());
		// The top: a platform with a rail, a hole for the ladder, and three ways off.
		box(level, cx - 3, FLOOR + 16, cz - 3, cx + 3, FLOOR + 16, cz + 3, Blocks.SMOOTH_STONE.defaultBlockState());
		set(level, cx, FLOOR + 16, cz - 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
		for (int x = cx - 3; x <= cx + 3; x++) {
			for (int z = cz - 3; z <= cz + 3; z++) {
				boolean rim = x == cx - 3 || x == cx + 3 || z == cz - 3 || z == cz + 3;
				boolean gap = x == cx && z == cz - 3 || x == cx - 3 && z == cz || x == cx + 3 && z == cz;
				set(level, x, FLOOR + 17, z, rim && !gap ? Blocks.OAK_FENCE.defaultBlockState() : Blocks.AIR.defaultBlockState());
			}
		}
		// The pool (north) and the diving board out over it.
		box(level, cx - 4, FLOOR - 3, cz - 9, cx + 3, FLOOR, cz - 5, Blocks.WATER.defaultBlockState());
		box(level, cx - 5, FLOOR - 4, cz - 10, cx + 4, FLOOR - 4, cz - 4, Blocks.PRISMARINE_BRICKS.defaultBlockState());
		for (int x = cx - 5; x <= cx + 4; x++) {
			for (int z = cz - 10; z <= cz - 4; z++) {
				if (x == cx - 5 || x == cx + 4 || z == cz - 10 || z == cz - 4) {
					box(level, x, FLOOR - 3, z, x, FLOOR, z, Blocks.PRISMARINE_BRICKS.defaultBlockState());
				}
			}
		}
		box(level, cx, FLOOR + 16, cz - 4, cx, FLOOR + 16, cz - 8, Blocks.BIRCH_PLANKS.defaultBlockState());
		label(level, cx + 0.5, FLOOR + 18.5, cz - 7.5, "DIVING BOARD", ChatFormatting.AQUA, 0.8F);
		// The water slide: twice round the tower, then out into the pool.
		List<Vec3> pts = new ArrayList<>();
		pts.add(new Vec3(SLIDE_START.getX() + 0.5, FLOOR + 16.6, cz + 0.5));
		for (int i = 0; i <= 32; i++) {
			double a = i / 16.0 * Math.PI * 2;
			double y = FLOOR + 16.2 - 13.0 * i / 32;
			pts.add(new Vec3(cx + 0.5 + Math.cos(a) * 5.5, y, cz + 0.5 + Math.sin(a) * 5.5));
		}
		pts.add(new Vec3(cx + 3.5, FLOOR + 2.2, cz - 5.0));
		pts.add(new Vec3(cx + 0.5, FLOOR + 0.6, cz - 7.5));
		slide = HubRides.spline(pts, 0.5);
		for (int i = 0; i < slide.size() - 6; i++) {
			Vec3 p = slide.get(i);
			Vec3 q = slide.get(Math.min(slide.size() - 1, i + 1));
			Vec3 side = new Vec3(q.z - p.z, 0, -(q.x - p.x)).normalize();
			int fy = (int) Math.floor(p.y - 0.4);
			ifAir(level, (int) Math.floor(p.x), fy, (int) Math.floor(p.z), Blocks.CONCRETE.pick(DyeColor.LIGHT_BLUE).defaultBlockState());
			for (int s : new int[] {-1, 1}) {
				Vec3 r = p.add(side.scale(s * 1.1));
				ifAir(level, (int) Math.floor(r.x), fy + 1, (int) Math.floor(r.z), Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE).defaultBlockState());
			}
		}
		set(level, SLIDE_START.getX(), SLIDE_START.getY(), SLIDE_START.getZ(), Blocks.PRISMARINE.defaultBlockState());
		set(level, SLIDE_START.getX(), SLIDE_START.getY() + 1, SLIDE_START.getZ(), Blocks.AIR.defaultBlockState());
		label(level, SLIDE_START.getX() + 0.5, SLIDE_START.getY() + 2.6, SLIDE_START.getZ() + 0.5, "WATER SLIDE", ChatFormatting.AQUA, 0.8F);
		// The zipline: from the top, down to the plaza.
		set(level, ZIP_START.getX(), ZIP_START.getY(), ZIP_START.getZ(), Blocks.IRON_BLOCK.defaultBlockState());
		set(level, ZIP_START.getX(), ZIP_START.getY() + 1, ZIP_START.getZ(), Blocks.AIR.defaultBlockState());
		box(level, ZIP_START.getX(), ZIP_START.getY() + 1, ZIP_START.getZ() - 1, ZIP_START.getX(), ZIP_START.getY() + 4, ZIP_START.getZ() - 1,
				Blocks.DARK_OAK_FENCE.defaultBlockState());
		label(level, ZIP_START.getX() + 0.5, ZIP_START.getY() + 2.6, ZIP_START.getZ() + 0.5, "ZIPLINE", ChatFormatting.GOLD, 0.8F);
		Vec3 top = new Vec3(ZIP_START.getX() + 0.5, ZIP_START.getY() + 4.2, ZIP_START.getZ() + 0.5);
		Vec3 bottom = new Vec3(16.5, FLOOR + 3.6, -10.5);
		List<Vec3> cable = new ArrayList<>();
		List<Vec3> ride = new ArrayList<>();
		int n = (int) (top.distanceTo(bottom) / 0.5);
		for (int i = 0; i <= n; i++) {
			double t = i / (double) n;
			Vec3 c = top.lerp(bottom, t).add(0, -2.5 * Math.sin(Math.PI * t), 0);
			cable.add(c);
			ride.add(c.add(0, -2.4, 0));
		}
		ride.add(new Vec3(16.5, FLOOR + 1, -10.5));
		zip = HubRides.even(ride, 0.5);
		for (int i = 0; i < cable.size(); i += 2) {
			piece(level, cable.get(i), Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState(), new Vector3f(0.09F, 0.09F, 0.09F), new Quaternionf());
		}
		lamp(level, 50, FLOOR + 1, -37);
		lamp(level, 38, FLOOR + 1, -51);
	}

	// ----- The Pond -----

	private static void pond(ServerLevel level) {
		island(level, 0, 56, 14, FLOOR);
		label(level, 0.5, FLOOR + 8, 56.5, "THE POND", ChatFormatting.AQUA, 2.5F);
		pad(level, 9, FLOOR, 48, "The Plaza", stand(10, FLOOR, 15));
		// The fishing pond, with a dock.
		box(level, -8, FLOOR - 4, 46, 5, FLOOR - 4, 57, Blocks.CLAY.defaultBlockState());
		for (int x = -8; x <= 5; x++) {
			for (int z = 46; z <= 57; z++) {
				boolean rim = x == -8 || x == 5 || z == 46 || z == 57;
				box(level, x, FLOOR - 3, z, x, FLOOR, z, rim ? Blocks.MUD_BRICKS.defaultBlockState() : Blocks.WATER.defaultBlockState());
			}
		}
		box(level, -1, FLOOR, 46, 0, FLOOR, 50, Blocks.SPRUCE_PLANKS.defaultBlockState());
		set(level, ROD_BARREL.getX(), ROD_BARREL.getY(), ROD_BARREL.getZ(), Blocks.BARREL.defaultBlockState());
		label(level, ROD_BARREL.getX() + 0.5, ROD_BARREL.getY() + 1.5, ROD_BARREL.getZ() + 0.5, "FISHING RODS\nright-click", ChatFormatting.AQUA, 0.7F);
		for (int i = 0; i < 6; i++) {
			set(level, -6 + i * 2, FLOOR, 54 - (i % 3), Blocks.LILY_PAD.defaultBlockState());
		}
		// The splash pool the cannon lands in.
		for (int x = -11; x <= -6; x++) {
			for (int z = 60; z <= 65; z++) {
				boolean rim = x == -11 || x == -6 || z == 60 || z == 65;
				box(level, x, FLOOR - 3, z, x, FLOOR, z, rim ? Blocks.QUARTZ_BRICKS.defaultBlockState() : Blocks.WATER.defaultBlockState());
				set(level, x, FLOOR - 4, z, Blocks.QUARTZ_BRICKS.defaultBlockState());
			}
		}
		label(level, -8, FLOOR + 3, 62.5, "SPLASH POOL", ChatFormatting.AQUA, 0.8F);
		// The swings.
		for (int x : new int[] {4, 10}) {
			box(level, x, FLOOR + 1, 62, x, FLOOR + 5, 62, Blocks.OAK_LOG.defaultBlockState());
		}
		box(level, 4, FLOOR + 6, 62, 10, FLOOR + 6, 62, Blocks.OAK_LOG.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
				Direction.Axis.X));
		HubRides.swing(level, new Vec3(6.5, FLOOR + 5.9, 62.5), 4.3);
		HubRides.swing(level, new Vec3(8.5, FLOOR + 5.9, 62.5), 4.3);
		label(level, 7.5, FLOOR + 8, 62.5, "SWINGS\nright-click a seat", ChatFormatting.YELLOW, 0.8F);
		for (int z = 50; z <= 52; z++) {
			set(level, 9, FLOOR + 1, z, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
		}
		lamp(level, -12, FLOOR + 1, 52);
		lamp(level, 11, FLOOR + 1, 58);
	}

	// ----- Mini Golf -----

	private static void golf(ServerLevel level) {
		int cx = -44;
		int cz = 44;
		island(level, cx, cz, 16, FLOOR);
		label(level, cx + 0.5, FLOOR + 8, cz + 0.5, "MINI GOLF", ChatFormatting.GREEN, 2.5F);
		pad(level, -36, FLOOR, 56, "The Plaza", stand(-13, FLOOR, 15));
		BlockState wall = Blocks.SPRUCE_PLANKS.defaultBlockState();
		BlockState green = Blocks.MOSS_BLOCK.defaultBlockState();
		// {x1, z1, x2, z2} of each hole's lane (tee at the west end, hole near the east), and obstacles inside.
		int[][] lanes = {{-54, 35, -38, 37}, {-54, 41, -38, 43}, {-52, 47, -40, 51}};
		int[][][] bumps = {{}, {{-46, 41}, {-46, 42}}, {{-47, 48}, {-47, 50}, {-43, 49}, {-44, 47}}};
		for (int h = 0; h < lanes.length; h++) {
			int[] l = lanes[h];
			for (int x = l[0] - 1; x <= l[2] + 1; x++) {
				for (int z = l[1] - 1; z <= l[3] + 1; z++) {
					boolean edge = x == l[0] - 1 || x == l[2] + 1 || z == l[1] - 1 || z == l[3] + 1;
					set(level, x, FLOOR, z, edge ? Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState() : green);
					set(level, x, FLOOR + 1, z, edge ? wall : Blocks.AIR.defaultBlockState());
				}
			}
			for (int[] b : bumps[h]) {
				set(level, b[0], FLOOR + 1, b[1], wall);
			}
			int mid = (l[1] + l[3]) / 2;
			BlockPos tee = new BlockPos(l[0], FLOOR, mid);
			BlockPos hole = new BlockPos(l[2] - 1, FLOOR, mid);
			set(level, tee.getX(), tee.getY(), tee.getZ(), Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState());
			set(level, hole.getX(), hole.getY(), hole.getZ(), Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState());
			GOLF_TEES.add(tee);
			GOLF_HOLES.add(hole);
			// A flag over the hole (a picture: the ball rolls through it).
			piece(level, new Vec3(hole.getX() + 0.5, FLOOR + 2.0, hole.getZ() + 0.5), Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState(), new Vector3f(0.06F, 2.0F, 0.06F),
					new Quaternionf());
			piece(level, new Vec3(hole.getX() + 0.5, FLOOR + 2.7, hole.getZ() + 0.85), Blocks.WOOL.pick(DyeColor.RED).defaultBlockState(), new Vector3f(0.04F, 0.5F, 0.7F),
					new Quaternionf());
			label(level, tee.getX() + 0.5, FLOOR + 2.3, tee.getZ() + 0.5, "HOLE " + (h + 1) + "\nstand on the lime tee", ChatFormatting.GREEN, 0.6F);
		}
		lamp(level, -58, FLOOR + 1, 44);
		lamp(level, -32, FLOOR + 1, 38);
	}

	// ----- The Stage -----

	private static void stage(ServerLevel level) {
		int cx = -46;
		int cz = -34;
		island(level, cx, cz, 12, FLOOR);
		label(level, cx + 0.5, FLOOR + 10, cz + 0.5, "THE STAGE", ChatFormatting.LIGHT_PURPLE, 2.5F);
		pad(level, -36, FLOOR, -31, "The Plaza", stand(-18, FLOOR, -1));
		// The stage, its back wall of lights, the DJ's jukebox.
		box(level, -52, FLOOR + 1, -43, -40, FLOOR + 1, -38, Blocks.DARK_OAK_PLANKS.defaultBlockState());
		for (int x = -52; x <= -40; x++) {
			for (int y = FLOOR + 2; y <= FLOOR + 6; y++) {
				set(level, x, y, -44, (x + y) % 3 == 0 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState());
			}
		}
		set(level, JUKEBOX.getX(), JUKEBOX.getY(), JUKEBOX.getZ(), Blocks.JUKEBOX.defaultBlockState());
		for (int x : new int[] {-51, -41}) {
			box(level, x, FLOOR + 2, -42, x, FLOOR + 3, -42, Blocks.NOTE_BLOCK.defaultBlockState());
		}
		label(level, JUKEBOX.getX() + 0.5, JUKEBOX.getY() + 1.6, JUKEBOX.getZ() + 0.5, "DJ BOOTH\nright-click for the next song", ChatFormatting.LIGHT_PURPLE,
				0.7F);
		// The dance floor: glass over lights (it changes colour while music plays).
		for (int x = -51; x <= -41; x++) {
			for (int z = -36; z <= -29; z++) {
				set(level, x, FLOOR - 1, z, Blocks.SEA_LANTERN.defaultBlockState());
				set(level, x, FLOOR, z, Blocks.STAINED_GLASS.pick((x + z) % 2 == 0 ? DyeColor.PURPLE : DyeColor.BLACK).defaultBlockState());
				DANCE_FLOOR.add(new BlockPos(x, FLOOR, z));
			}
		}
		// The photo spot: a pretty wall, and a star to stand on.
		for (int x = -50; x <= -42; x++) {
			for (int y = FLOOR + 1; y <= FLOOR + 4; y++) {
				set(level, x, y, -24, y == FLOOR + 4 || x == -50 || x == -42 ? Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState()
						.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true) : Blocks.WOOL.pick(DyeColor.PINK).defaultBlockState());
			}
			set(level, x, FLOOR + 1, -25, (x % 2 == 0 ? Blocks.PINK_TULIP : Blocks.ALLIUM).defaultBlockState());
		}
		set(level, -46, FLOOR, -27, Blocks.GOLD_BLOCK.defaultBlockState());
		label(level, -45.5, FLOOR + 5.6, -24.2, "PHOTO SPOT", ChatFormatting.LIGHT_PURPLE, 1.0F);
		label(level, -45.5, FLOOR + 2.2, -26.5, "Stand on the gold, then switch your camera\n(Toggle Perspective) to face yourself",
				ChatFormatting.WHITE, 0.5F);
		lamp(level, -56, FLOOR + 1, -30);
		lamp(level, -36, FLOOR + 1, -40);
	}

	// ----- Sky parkour -----

	private static void parkour(ServerLevel level) {
		int x0 = -22;
		BlockState[] colors = {Blocks.CONCRETE.pick(DyeColor.LIGHT_BLUE).defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.MAGENTA).defaultBlockState(),
			Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState()};
		// The start: a platform just off the plaza.
		box(level, x0 - 1, FLOOR + 2, -37, x0 + 1, FLOOR + 2, -35, Blocks.QUARTZ_BLOCK.defaultBlockState());
		PARKOUR.add(new BlockPos(x0, FLOOR + 2, -36));
		CHECKPOINTS.add(0);
		label(level, x0 + 0.5, FLOOR + 5, -35.5, "SKY PARKOUR\nfall and you're back at the last gold", ChatFormatting.YELLOW, 0.8F);
		int y = FLOOR + 2;
		for (int i = 1; i <= 18; i++) {
			int x = x0 + (int) Math.round(3 * Math.sin(i * 0.55));
			int z = -36 - i * 3;
			if (i % 2 == 0) {
				y++;
			}
			boolean checkpoint = i == 6 || i == 12;
			boolean last = i == 18;
			int size = last ? 2 : checkpoint ? 1 : i < 6 ? 1 : 0;
			box(level, x - size, y, z - size, x + (size > 0 ? size : 0), y, z + (size > 0 ? size : 0),
					last || checkpoint ? Blocks.GOLD_BLOCK.defaultBlockState() : colors[i % colors.length]);
			PARKOUR.add(new BlockPos(x, y, z));
			if (checkpoint || last) {
				CHECKPOINTS.add(i);
			}
		}
		BlockPos end = PARKOUR.get(PARKOUR.size() - 1);
		label(level, end.getX() + 0.5, end.getY() + 3.4, end.getZ() + 0.5, "YOU MADE IT!", ChatFormatting.GOLD, 1.2F);
		pad(level, end.getX(), end.getY(), end.getZ() - 4, "The Plaza", stand(-1, FLOOR, 6));
		box(level, end.getX() - 1, end.getY(), end.getZ() - 3, end.getX() + 1, end.getY(), end.getZ() - 3, Blocks.GOLD_BLOCK.defaultBlockState());
		parkourZone = new AABB(x0 - 8, FLOOR - 30, end.getZ() - 8, x0 + 8, FLOOR + 40, -32);
	}

	// ----- The rollercoaster -----

	private static void coasterTrack(ServerLevel level) {
		double[][] c = {{43, 0, 1}, {38, 0, 1.5}, {33, -4, 3}, {30, -10, 8}, {24, -20, 14}, {14, -27, 16}, {0, -31, 6}, {-14, -28, 3}, {-24, -20, 7},
			{-30, -8, 12}, {-31, 6, 9}, {-27, 17, 4}, {-18, 26, 2}, {-6, 31, 8}, {8, 30, 13}, {20, 24, 9}, {28, 14, 5}, {34, 6, 3}, {39, 2, 1.5},
			{43, 1, 1}};
		List<Vec3> pts = new ArrayList<>();
		for (double[] p : c) {
			pts.add(new Vec3(p[0] + 0.5, FLOOR + p[2], p[1] + 0.5));
		}
		coaster = HubRides.spline(pts, 0.5);
		for (int i = 0; i < coaster.size(); i++) {
			Vec3 p = coaster.get(i);
			int x = (int) Math.floor(p.x);
			int y = (int) Math.floor(p.y - 0.3);
			int z = (int) Math.floor(p.z);
			ifAir(level, x, y, z, Blocks.DARK_OAK_SLAB.defaultBlockState());
			if (i % 12 == 0) {
				for (int d = 1; d <= 4; d++) {
					ifAir(level, x, y - d, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
				}
			}
		}
	}

	// ----- Every tick -----

	public static void tick(ServerLevel level) {
		tick++;
		HubRides.tick(level);
		HubGames.tick(level);
		for (ServerPlayer p : level.players()) {
			if (p.isSpectator() || p.isPassenger()) {
				continue;
			}
			BlockPos under = p.blockPosition().below();
			if (!p.onGround()) {
				under = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());
			}
			// Launch pads (only when you step onto one, not while you stand there after landing).
			Pad pad = p.onGround() ? PADS.get(under) : null;
			if (pad == null) {
				ON_PAD.remove(p.getUUID());
			} else if (ON_PAD.add(p.getUUID())) {
				Vec3 from = p.position();
				double dist = from.distanceTo(pad.to());
				int ticks = (int) (24 + dist * 0.75);
				HubRides.start(p, HubRides.arc(from, pad.to(), 5 + dist * 0.15, ticks), HubRides.Kind.FLIGHT, null, 0, 0, 0);
				level.playSound(null, p.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.0F, 1.2F);
				p.sendOverlayMessage(Component.literal("Off to " + pad.label() + "!").withStyle(ChatFormatting.GREEN));
				continue;
			}
			// Trampolines.
			if (p.onGround() && TRAMPOLINES.contains(under)) {
				Double top = BOUNCE_TOP.remove(p.getUUID());
				if (top != null && top - under.getY() > 2) {
					p.sendOverlayMessage(Component.literal("Boing! " + (int) Math.round(top - under.getY() - 1) + " blocks high").withStyle(ChatFormatting.GREEN));
				}
				p.setDeltaMovement(p.getDeltaMovement().x, 1.25, p.getDeltaMovement().z);
				p.syncVelocity = true;
				level.playSound(null, p.blockPosition(), SoundEvents.SLIME_JUMP, SoundSource.PLAYERS, 0.6F, 1.2F);
				BOUNCE_TOP.put(p.getUUID(), p.getY());
			} else if (BOUNCE_TOP.containsKey(p.getUUID())) {
				BOUNCE_TOP.merge(p.getUUID(), p.getY(), Math::max);
				if (p.onGround()) {
					BOUNCE_TOP.remove(p.getUUID());
				}
			}
			// The rollercoaster, the slide, the zipline.
			if (p.onGround() && COASTER_BOARD.contains(under)) {
				HubRides.start(p, coaster, HubRides.Kind.COASTER, Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState(), 1.1F, 0.55F, 1.5F);
				level.playSound(null, p.blockPosition(), SoundEvents.MINECART_RIDING, SoundSource.PLAYERS, 0.8F, 1.0F);
				Alerts.title(p, "", "Hold on tight!", ChatFormatting.RED);
				continue;
			}
			if (p.onGround() && under.equals(SLIDE_START)) {
				HubRides.start(p, slide, HubRides.Kind.SLIDE, Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState(), 1.0F, 0.25F, 1.3F);
				level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 0.8F, 1.2F);
				continue;
			}
			if (p.onGround() && under.equals(ZIP_START)) {
				HubRides.start(p, zip, HubRides.Kind.ZIP, null, 0, 0, 0);
				level.playSound(null, p.blockPosition(), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1.0F, 0.8F);
				continue;
			}
			// The cannon: stand in it for three seconds.
			if (p.onGround() && under.equals(CANNON)) {
				int count = CANNON_COUNT.merge(p.getUUID(), 1, Integer::sum);
				if (count % 20 == 1 && count < 60) {
					Alerts.title(p, String.valueOf(3 - count / 20), "Human cannon!", ChatFormatting.RED);
					level.playSound(null, p.blockPosition(), SoundEvents.TNT_PRIMED, SoundSource.PLAYERS, 0.6F, 1.6F);
				}
				if (count >= 60) {
					CANNON_COUNT.remove(p.getUUID());
					Vec3 from = new Vec3(0.5, FLOOR + 2, 20.5);
					HubRides.start(p, HubRides.arc(from, CANNON_TARGET, 18, 70), HubRides.Kind.FLIGHT, null, 0, 0, 0);
					level.playSound(null, BlockPos.containing(from), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.5F, 1.2F);
					level.sendParticles(ParticleTypes.EXPLOSION, from.x, from.y + 1, from.z, 3, 0.5, 0.5, 0.5, 0);
					Alerts.title(p, "BOOM!", "", ChatFormatting.RED);
				}
				continue;
			} else {
				CANNON_COUNT.remove(p.getUUID());
			}
			tickParkour(level, p, under);
		}
		if (tick < fireworksUntil && tick % 8 == 0) {
			var random = level.getRandom();
			burst(level, new Vec3((random.nextDouble() - 0.5) * 30, FLOOR + 16 + random.nextDouble() * 10, (random.nextDouble() - 0.5) * 30));
		}
		if (tick % 200 == 0) {
			critters(level);
		}
		if (tick % 100 == 0) {
			lamps(level);
		}
		if (tick % 10 == 0) {
			sparkle(level);
		}
	}

	private static void tickParkour(ServerLevel level, ServerPlayer p, BlockPos under) {
		if (!parkourZone.contains(p.position())) {
			PARKOUR_AT.remove(p.getUUID());
			PARKOUR_STARTED.remove(p.getUUID());
			return;
		}
		int at = PARKOUR_AT.getOrDefault(p.getUUID(), 0);
		if (p.onGround()) {
			for (int i = 0; i < PARKOUR.size(); i++) {
				BlockPos b = PARKOUR.get(i);
				if (Math.abs(under.getX() - b.getX()) <= 2 && Math.abs(under.getZ() - b.getZ()) <= 2 && under.getY() == b.getY()) {
					if (i == 0) {
						PARKOUR_STARTED.put(p.getUUID(), level.getGameTime());
					}
					if (CHECKPOINTS.contains(i) && i > at) {
						PARKOUR_AT.put(p.getUUID(), i);
						if (i == PARKOUR.size() - 1) {
							Long started = PARKOUR_STARTED.remove(p.getUUID());
							String time = started == null ? "" : " in " + (level.getGameTime() - started) / 20 + " seconds";
							Alerts.title(p, "YOU MADE IT!", "Sky parkour" + time, ChatFormatting.GOLD);
							burst(level, p.position().add(0, 4, 0));
						} else if (i > 0) {
							p.sendOverlayMessage(Component.literal("Checkpoint!").withStyle(ChatFormatting.GOLD));
							level.playSound(null, p.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8F, 1.2F);
						}
					}
					break;
				}
			}
		}
		BlockPos cp = PARKOUR.get(at);
		if (p.getY() < cp.getY() - 5) {
			p.teleportTo(cp.getX() + 0.5, cp.getY() + 1, cp.getZ() + 0.5);
			p.setDeltaMovement(Vec3.ZERO);
			p.syncVelocity = true;
			p.resetFallDistance();
			p.sendOverlayMessage(Component.literal("Whoops! Back to the last checkpoint.").withStyle(ChatFormatting.YELLOW));
		}
	}

	/** Lamps light up at night. */
	private static void lamps(ServerLevel level) {
		long time = level.getOverworldClockTime() % 24000;
		boolean night = time > 12800 && time < 23200;
		if (night == lampsLit) {
			return;
		}
		lampsLit = night;
		for (BlockPos l : LAMPS) {
			level.setBlock(l, night ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	/** Butterflies by day, fireflies by night. */
	private static void sparkle(ServerLevel level) {
		var random = level.getRandom();
		int[][] spots = {{FX, FZ, 15}, {0, 56, 12}, {-44, 44, 14}, {-46, -34, 10}, {44, -44, 9}, {0, 0, 20}};
		int[] s = spots[random.nextInt(spots.length)];
		double a = random.nextDouble() * Math.PI * 2;
		double r = random.nextDouble() * s[2];
		double x = s[0] + Math.cos(a) * r;
		double z = s[1] + Math.sin(a) * r;
		if (lampsLit) {
			level.sendParticles(ParticleTypes.END_ROD, x, FLOOR + 1.5 + random.nextDouble() * 2, z, 1, 0.3, 0.3, 0.3, 0.005);
		} else {
			int[] wings = {0xFFFF9F40, 0xFF40A0FF, 0xFFFFE04A, 0xFFFF6FD0, 0xFFFFFFFF};
			level.sendParticles(new DustParticleOptions(wings[random.nextInt(wings.length)], 0.7F), x, FLOOR + 1.3 + random.nextDouble() * 1.5, z, 2, 0.15, 0.1,
					0.15, 0);
		}
	}

	/** The plaza's critters: three parrots, a cat, fish in the fountain. Kept there, and replaced if they go missing. */
	private static void critters(ServerLevel level) {
		if (level.players().isEmpty()) {
			return;
		}
		List<Entity> have = level.getEntities((Entity) null, new AABB(-60, FLOOR - 30, -60, 60, FLOOR + 40, 60), e -> e.entityTags().contains(CRITTER));
		int parrots = 0;
		int cats = 0;
		int fish = 0;
		for (Entity e : have) {
			if (e.getType() == EntityTypes.PARROT) {
				parrots++;
				if (e.position().horizontalDistance() > 30 || e.getY() < FLOOR - 5) {
					e.teleportTo(level.getRandom().nextInt(16) - 8, FLOOR + 6, level.getRandom().nextInt(16) - 8);
				}
			} else if (e.getType() == EntityTypes.CAT) {
				cats++;
				if (e.position().horizontalDistance() > 21) {
					e.teleportTo(5.5, FLOOR + 1, 5.5);
				}
			} else if (e.getType() == EntityTypes.TROPICAL_FISH) {
				fish++;
				if (e.position().horizontalDistance() > 3.5) {
					e.teleportTo(0.5 + level.getRandom().nextInt(3) - 1, FLOOR + 0.3, 1.8);
				}
			}
		}
		for (int i = parrots; i < 3; i++) {
			critter(level, EntityTypes.PARROT.create(level, EntitySpawnReason.EVENT), new Vec3(level.getRandom().nextInt(16) - 8, FLOOR + 5, level.getRandom().nextInt(16) - 8));
		}
		for (int i = cats; i < 1; i++) {
			critter(level, EntityTypes.CAT.create(level, EntitySpawnReason.EVENT), new Vec3(5.5, FLOOR + 1, 5.5));
		}
		for (int i = fish; i < 4; i++) {
			critter(level, EntityTypes.TROPICAL_FISH.create(level, EntitySpawnReason.EVENT), new Vec3(0.5 + i % 2 * 2 - 1, FLOOR + 0.3, 0.5 + (i / 2) * 2 - 1));
		}
	}

	private static void critter(ServerLevel level, Mob mob, Vec3 at) {
		if (mob == null) {
			return;
		}
		mob.snapTo(at.x, at.y, at.z, level.getRandom().nextFloat() * 360, 0);
		mob.setPersistenceRequired();
		mob.setPermanentlyInvulnerable(true);
		mob.addTag(CRITTER);
		level.addFreshEntity(mob);
	}
}
