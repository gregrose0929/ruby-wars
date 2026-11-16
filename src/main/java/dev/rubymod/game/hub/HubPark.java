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

	static final AABB WHOLE_HUB = new AABB(-175, FLOOR - 40, -175, 175, FLOOR + 70, 175);

	/**
	 * While an area is being built somewhere other than where its coordinates were first laid out, every block and
	 * prop the helpers place is shifted by this much (see {@link #at(int, int)}).
	 */
	static int OX;
	static int OZ;

	/** Build what {@code builder} lays out, shifted by dx, dz. */
	static void at(int dx, int dz, Runnable builder) {
		OX = dx;
		OZ = dz;
		try {
			builder.run();
		} finally {
			OX = 0;
			OZ = 0;
		}
	}

	static Vec3 shift(Vec3 v) {
		return v.add(OX, 0, OZ);
	}
	// Fun Park
	private static final int FX = 56;
	private static final int FZ = 2;
	static final AABB GALLERY_LINE = new AABB(45, FLOOR, -6, 60, FLOOR + 3, -1);
	static final BlockPos GALLERY_START = new BlockPos(52, FLOOR, -2);
	static final List<BlockPos> GALLERY_WALL = new ArrayList<>();
	static final BlockState GALLERY_WALL_BLOCK = Blocks.SPRUCE_PLANKS.defaultBlockState();
	// The Pond
	/** The pond and its fishing village were laid out at (0, 56); they're built shifted by this, on Splash Island. */
	static final int POND_DX = 9;
	static final int POND_DZ = -187;
	static final AABB POND = new AABB(-8 + POND_DX, FLOOR - 4, 46 + POND_DZ, 6 + POND_DX, FLOOR + 2, 57 + POND_DZ);
	// Mini Golf
	static final List<BlockPos> GOLF_TEES = new ArrayList<>();
	static final List<BlockPos> GOLF_HOLES = new ArrayList<>();
	// The Stage
	/** The stage was laid out at (-46, -34); it's built shifted by this, into the Fun Park. */
	static final int STAGE_DX = 156;
	static final int STAGE_DZ = 60;
	static final Vec3 STAGE_CENTER = new Vec3(-46 + STAGE_DX, FLOOR + 2, -40 + STAGE_DZ);
	static final List<BlockPos> DANCE_FLOOR = new ArrayList<>();
	// The plaza's things you click.
	private static final BlockPos TOY_CHEST = new BlockPos(3, FLOOR + 1, 10);
	private static final BlockPos FIREWORKS = new BlockPos(-6, FLOOR + 2, -4);
	private static final BlockPos ROD_BARREL = new BlockPos(-2 + POND_DX, FLOOR + 1, 45 + POND_DZ);
	private static final BlockPos SNOW_PILE = new BlockPos(68, FLOOR + 1, 5);
	private static final BlockPos JUKEBOX = new BlockPos(-46 + STAGE_DX, FLOOR + 2, -40 + STAGE_DZ);

	/** A launch pad: straight to {@code to}, or (when that's null) a travel pad that asks where you'd like to go. */
	private record Pad(String label, Vec3 to, String here) {
	}

	/** Everywhere a travel pad can fly you: id, name, a few words, an icon, where you land. */
	private record Place(String id, String name, String about, String icon, Vec3 land, String more) {
		Place(String id, String name, String about, String icon, Vec3 land) {
			this(id, name, about, icon, land, "");
		}
	}

	private static final List<Place> PLACES = List.of(
			new Place("plaza", "The Plaza", "Toys, Sky Rings", "minecraft:bell", stand(-1, FLOOR, 6), "Parkour, pets, photo wall"),
			new Place("funpark", "Fun Park", "Coaster, bumpers, more", "minecraft:minecart", stand(46, FLOOR, 5), "Mouse, swings, drop tower"),
			new Place("splash", "Splash Island", "Slides, flume, fishing", "minecraft:water_bucket", stand(54, FLOOR, -76), "Rapids, capsule, aquarium"),
			new Place("golf", "Mini Golf", "Four crazy holes", "minecraft:slime_ball", stand(-40, FLOOR, 57), "Windmill, volcano, cove"),
			new Place("speedway", "Speedway", "Kart racing on ice", "minecraft:snowball", stand(-109, FLOOR, -40), "Race the computer karts"),
			new Place("home", "Home Street", "Your own little house", "minecraft:oak_door", stand(-62, FLOOR, -40), "Main Street shops, museum"),
			new Place("beach", "Fossil Beach", "Dig up fossils", "minecraft:wooden_shovel", stand(97, FLOOR, -125), "Shovels at the museum"));

	private static final String MAP = "Park Map";

	/** Into the hub: a Park Map in your hotbar (right-click it to fly anywhere). */
	public static void welcome(ServerPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (HubGames.isToy(inv.getItem(i)) && inv.getItem(i).getHoverName().getString().equals(MAP)) {
				return;
			}
		}
		ItemStack map = HubGames.toy(net.minecraft.world.item.Items.PAPER, 1, MAP, ChatFormatting.AQUA);
		map.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, net.minecraft.resources.Identifier.withDefaultNamespace("map"));
		map.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
				Component.literal("Right-click to fly anywhere in the park").withStyle(ChatFormatting.GRAY))));
		if (inv.getItem(8).isEmpty()) {
			inv.setItem(8, map);
		} else {
			inv.add(map);
		}
		p.inventoryMenu.broadcastChanges();
	}

	/** Who has the travel menu open, and where they were standing. */
	private static final Map<UUID, Vec3> TRAVEL_MENU = new HashMap<>();

	/** Launch pads: each of the 3x3 blocks of a pad, and where it sends you. */
	private static final Map<BlockPos, Pad> PADS = new HashMap<>();
	private static final Set<UUID> ON_PAD = new HashSet<>();
	private static final Set<BlockPos> TRAMPOLINES = new HashSet<>();

	/** A block you bounce on (the bouncy castle's floor). */
	static void bouncy(BlockPos pos) {
		TRAMPOLINES.add(pos.offset(OX, 0, OZ));
	}
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
	private static final Vec3 CANNON_TARGET = new Vec3(-8.5 + POND_DX, FLOOR - 1, 62.5 + POND_DZ);
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
			if (HubPlay.punched(sp, entity) || Booths.punched(sp, entity) || MiniGolf.punched(sp, entity) || HubGames.chickenHit(sp, entity)) {
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.PhotoData.TYPE,
				(payload, context) -> Photos.received(context.player(), payload.pixels()));
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.TravelPick.TYPE,
				(payload, context) -> travelPicked(context.player(), payload.id()));
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.HostPick.TYPE,
				(payload, context) -> HubHosts.picked(context.player(), payload.host(), payload.key()));
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(dev.rubymod.net.MorePayloads.TravelAsk.TYPE, (payload, context) -> {
			ServerPlayer p = context.player();
			if (p.level().dimension() == Hub.HUB && !p.isPassenger()) {
				openTravel(p, null);
			}
		});
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || level.dimension() != Hub.HUB || !(player instanceof ServerPlayer sp)) {
				return InteractionResult.PASS;
			}
			if (HubHosts.clicked(sp, entity) || Pets.clicked(sp, entity) || Toyland.pet(sp, entity) || Wonder.clicked(sp, entity)) {
				return InteractionResult.SUCCESS;
			}
			if (HubRides.mountSwing(sp, entity)) {
				sp.sendOverlayMessage(Component.literal("Wheee! Crouch to get off.").withStyle(ChatFormatting.YELLOW));
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
	}

	/** Right-clicking a block in the hub. Null: not one of ours. */
	public static InteractionResult useBlock(ServerPlayer p, BlockPos pos, BlockState state) {
		InteractionResult dig = Museum.useBlock(p, pos);
		if (dig != null) {
			return dig;
		}
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
		if (MainStreet.useItem(p, held)) {
			return true;
		}
		if (MiniGolf.isPutter(held)) {
			return MiniGolf.putt(p);
		}
		if (Booths.isMallet(held)) {
			return Booths.swing(p);
		}
		if (HubGames.isToy(held) && held.getHoverName().getString().equals(MAP)) {
			if (!p.isPassenger()) {
				openTravel(p, null);
			}
			return true;
		}
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

	static void set(ServerLevel level, int x, int y, int z, BlockState state) {
		BlockPos pos = new BlockPos(x + OX, y, z + OZ);
		if (level.getBlockState(pos) != state) {
			level.setBlock(pos, state, Block.UPDATE_CLIENTS);
		}
	}

	static void box(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
					set(level, x, y, z, state);
				}
			}
		}
	}

	static void ifAir(ServerLevel level, int x, int y, int z, BlockState state) {
		if (level.getBlockState(new BlockPos(x + OX, y, z + OZ)).isAir()) {
			set(level, x, y, z, state);
		}
	}

	/** A floating island: grass on top at FLOOR (or {@code top}), a rocky underside tapering to a point, a low hedge round the rim. */
	static void island(ServerLevel level, int cx, int cz, int r, int top) {
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

	/**
	 * One big island made of overlapping circles ({x, z, radius} each): grass on top, a rocky underside tapering
	 * down, a hedge only round the outside edge.
	 */
	static void blob(ServerLevel level, int[][] circles, int top) {
		int x1 = Integer.MAX_VALUE;
		int x2 = Integer.MIN_VALUE;
		int z1 = Integer.MAX_VALUE;
		int z2 = Integer.MIN_VALUE;
		for (int[] c : circles) {
			x1 = Math.min(x1, c[0] - c[2] - 1);
			x2 = Math.max(x2, c[0] + c[2] + 1);
			z1 = Math.min(z1, c[1] - c[2] - 1);
			z2 = Math.max(z2, c[1] + c[2] + 1);
		}
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int x = x1; x <= x2; x++) {
			for (int z = z1; z <= z2; z++) {
				// How far in from the edge: the deepest any circle has this column.
				double in = -1;
				for (int[] c : circles) {
					in = Math.max(in, c[2] - Math.hypot(x - c[0], z - c[1]));
				}
				if (in < -0.4) {
					continue;
				}
				set(level, x, top, z, Blocks.GRASS_BLOCK.defaultBlockState());
				for (int y = 1; y <= 12; y++) {
					set(level, x, top + y, z, air);
				}
				for (int dd = 1; dd <= 30; dd++) {
					boolean solid = in > dd * 1.15 - Math.sin(x * 0.7 + z * 0.5) * 0.8;
					set(level, x, top - dd, z, !solid ? air : dd == 1 ? Blocks.DIRT.defaultBlockState()
							: (x * 7 + z * 11 + dd * 5) % 37 == 0 ? ModBlocks.RUBY_BLOCK.defaultBlockState()
									: dd < 4 ? Blocks.STONE.defaultBlockState() : Blocks.DEEPSLATE.defaultBlockState());
				}
				if (in < 0.9) {
					set(level, x, top + 1, z, Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
				}
			}
		}
	}

	static void lamp(ServerLevel level, int x, int y, int z) {
		box(level, x, y, z, x, y + 2, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
		set(level, x, y + 3, z, Blocks.GLASS.defaultBlockState());
		LAMPS.add(new BlockPos(x + OX, y + 3, z + OZ));
	}

	static Display.TextDisplay label(ServerLevel level, double x, double y, double z, String text, ChatFormatting color, float scale) {
		Display.TextDisplay d = Props.text(level, new Vec3(x + OX, y, z + OZ), Component.literal(text).withStyle(color, ChatFormatting.BOLD), scale, TAG);
		keep(d);
		return d;
	}

	static Display.BlockDisplay piece(ServerLevel level, Vec3 at0, BlockState state, Vector3f size, Quaternionf rot) {
		Vec3 at = shift(at0);
		Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d == null) {
			return null;
		}
		d.snapTo(at.x, at.y, at.z, 0, 0);
		((BlockDisplayAccessor) d).rubymod$setBlockState(state);
		Vector3f half = rot.transform(new Vector3f(size).mul(0.5F));
		((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f().sub(half), rot, size, new Quaternionf()));
		float big = Math.max(size.x, Math.max(size.y, size.z));
		((DisplayAccessor) d).rubymod$setViewRange(big >= 4F ? 3.0F : big >= 0.9F ? 1.8F : 0.9F);
		level.addFreshEntity(d);
		keep(d);
		return d;
	}

	/** A long box from {@code a} to {@code b} ({@code w} wide, {@code h} tall, its middle on the line): rails, beams, struts. */
	static Display.BlockDisplay beam(ServerLevel level, Vec3 a0, Vec3 b0, float w, float h, BlockState state) {
		Vec3 a = shift(a0);
		Vec3 b = shift(b0);
		Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d == null) {
			return null;
		}
		Vec3 dir = b.subtract(a);
		float len = (float) dir.length();
		Quaternionf rot = new Quaternionf().rotateY((float) Math.atan2(dir.x, dir.z)).rotateX((float) -Math.asin(Math.max(-1, Math.min(1, dir.y / Math.max(1e-6, len)))));
		Vector3f off = rot.transform(new Vector3f(-w / 2, -h / 2, 0));
		d.snapTo(a.x, a.y, a.z, 0, 0);
		((BlockDisplayAccessor) d).rubymod$setBlockState(state);
		((DisplayAccessor) d).rubymod$setTransformation(new Transformation(off, rot, new Vector3f(w, h, len), new Quaternionf()));
		((DisplayAccessor) d).rubymod$setViewRange(Math.max(w, h) >= 0.3F ? 2.5F : len > 6 ? 1.6F : 0.9F);
		level.addFreshEntity(d);
		keep(d);
		return d;
	}

	/** One of the park's models (park_<id>), kept with the park. */
	static Display.ItemDisplay model(ServerLevel level, Vec3 at, float yaw, String id, float size, float up) {
		return parkModel(level, at, yaw, id, size, up, false);
	}

	/** A model that comes and goes (a ride's car): temporary, cleaned up if the server stops mid-ride. */
	static Display.ItemDisplay tempModel(ServerLevel level, Vec3 at, float yaw, String id, float size, float up) {
		return parkModel(level, at, yaw, id, size, up, true);
	}

	/**
	 * Like Props.model, but noted as wanted before it's added to the world (the clean-up of leftovers runs as an entity
	 * loads, and would otherwise take a tagged model straight away).
	 */
	private static Display.ItemDisplay parkModel(ServerLevel level, Vec3 at0, float yaw, String id, float size, float up, boolean temp) {
		Vec3 at = shift(at0);
		Display.ItemDisplay d = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d == null) {
			return null;
		}
		d.snapTo(at.x, at.y, at.z, yaw, 0);
		ItemStack stack = new ItemStack(net.minecraft.world.item.Items.STICK);
		stack.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, dev.rubymod.RubyMod.id("park_" + id));
		((dev.rubymod.mixin.ItemDisplayAccessor) d).rubymod$setItemStack(stack);
		((DisplayAccessor) d).rubymod$setViewRange(size >= 2 ? 6.0F : size >= 1 ? 2.5F : 1.2F);
		((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(temp ? 2 : 3);
		((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(0, up, 0), new Quaternionf().rotateY((float) Math.PI),
				new Vector3f(size, size, size), new Quaternionf()));
		if (temp) {
			d.addTag(HubRides.TEMP);
			live(d);
		} else {
			d.addTag(TAG);
			KEEP.add(d.getUUID());
		}
		level.addFreshEntity(d);
		return d;
	}

	/** An invisible seat (a display: it glides smoothly when moved). */
	static Display.BlockDisplay seat(ServerLevel level, Vec3 at) {
		Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d == null) {
			return null;
		}
		((BlockDisplayAccessor) d).rubymod$setBlockState(Blocks.AIR.defaultBlockState());
		((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(2);
		d.snapTo(at.x, at.y, at.z, 0, 0);
		level.addFreshEntity(d);
		d.addTag(HubRides.TEMP);
		live(d);
		return d;
	}

	static void discard(ServerLevel level, UUID id) {
		Entity e = id == null ? null : level.getEntity(id);
		if (e != null) {
			e.discard();
		}
		HubRides.LIVE.remove(id);
	}

	/** Clear a box of blocks to air (before rebuilding an area that's changed shape). */
	static void clear(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2) {
		box(level, x1, y1, z1, x2, y2, z2, Blocks.AIR.defaultBlockState());
	}

	/** A launch pad (3x3, glowing middle) at x, y, z, sending you to {@code to}. */
	private static void pad(ServerLevel level, int x, int y, int z, String label, Vec3 to) {
		Pad pad = new Pad(label, to, null);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				set(level, x + dx, y, z + dz, dx == 0 && dz == 0 ? Blocks.EMERALD_BLOCK.defaultBlockState() : Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState());
				set(level, x + dx, y + 1, z + dz, Blocks.AIR.defaultBlockState());
				set(level, x + dx, y + 2, z + dz, Blocks.AIR.defaultBlockState());
				PADS.put(new BlockPos(x + dx + OX, y, z + dz + OZ), pad);
			}
		}
		label(level, x + 0.5, y + 2.6, z + 0.5, "↗ " + label, ChatFormatting.GREEN, 1.0F);
	}

	/** A travel pad (3x3, light blue): step on it to pick any place in the hub to fly to. {@code here}: this place's id. */
	static void travelPad(ServerLevel level, int x, int y, int z, String here) {
		Pad pad = new Pad("", null, here);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				set(level, x + dx, y, z + dz, dx == 0 && dz == 0 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.CONCRETE.pick(DyeColor.LIGHT_BLUE).defaultBlockState());
				set(level, x + dx, y + 1, z + dz, Blocks.AIR.defaultBlockState());
				set(level, x + dx, y + 2, z + dz, Blocks.AIR.defaultBlockState());
				PADS.put(new BlockPos(x + dx + OX, y, z + dz + OZ), pad);
			}
		}
		label(level, x + 0.5, y + 2.9, z + 0.5, "✈ Fly anywhere", ChatFormatting.AQUA, 1.1F);
		label(level, x + 0.5, y + 2.5, z + 0.5, "step on to pick a place", ChatFormatting.GRAY, 0.7F);
	}

	private static void fly(ServerPlayer p, Vec3 to, String name) {
		ServerLevel level = (ServerLevel) p.level();
		Vec3 from = p.position();
		double dist = from.distanceTo(to);
		int ticks = (int) (24 + dist * 0.75);
		HubRides.start(p, HubRides.arc(from, to, 5 + dist * 0.15, ticks), HubRides.Kind.FLIGHT, null, 0, 0, 0);
		level.playSound(null, p.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.0F, 1.2F);
		p.sendOverlayMessage(Component.literal("Off to " + name + "!").withStyle(ChatFormatting.GREEN));
	}

	private static void openTravel(ServerPlayer p, String here) {
		List<String> list = new ArrayList<>();
		for (Place pl : PLACES) {
			if (!pl.id().equals(here)) {
				list.add(pl.id() + "|" + pl.name() + "|" + pl.about() + "|" + pl.icon() + "|" + pl.more());
			}
		}
		TRAVEL_MENU.put(p.getUUID(), p.position());
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new dev.rubymod.net.MorePayloads.TravelMenu(list));
		p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.4F);
	}

	/** A place picked from the travel menu (only from someone who was just shown it, still near that pad). */
	private static void travelPicked(ServerPlayer p, String id) {
		Vec3 at = TRAVEL_MENU.remove(p.getUUID());
		if (at == null || p.level().dimension() != Hub.HUB || p.isPassenger() || p.position().distanceTo(at) > 6) {
			return;
		}
		for (Place pl : PLACES) {
			if (pl.id().equals(id)) {
				fly(p, pl.land(), pl.name());
				return;
			}
		}
	}

	public static void build(ServerLevel level) {
		for (int cx = -11; cx <= 10; cx++) {
			for (int cz = -10; cz <= 7; cz++) {
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
		moveOut(level);
		Removals.run(level);
		HubRides.reset();
		TrackRide.ALL.clear();
		HubGames.reset();
		HubHosts.reset();
		FunPark.reset();
		BumperBoats.reset();
		FunRides.reset();
		MiniGolf.reset();
		Coaster.reset();
		Spooky.reset();
		Village.reset();
		Speedway.reset();
		Thrill.reset();
		WaterPark.reset();
		SkyZip.reset();
		SkyRings.reset();
		HubToys.reset();
		Toyland.reset();
		NightShow.reset();
		Rapids.reset();
		Fishing.reset();
		Butterflies.reset();
		Photos.reset();
		SpinCoaster.reset();
		MainStreet.reset();
		Museum.reset();
		ParkTrain.reset();
		SkyTours.reset();
		Dressing.reset();
		Bridges.reset();
		Music.reset();
		Parade.reset();
		Eggs.reset();
		Wonder.reset();
		Plunge.reset();
		plaza(level);
		FunPark.build(level);
		BumperBoats.build(level);
		FunRides.build(level);
		SplashIsland.build(level);
		Butterflies.build(level);
		HomeStreet.build(level);
		SpinCoaster.build(level);
		Photos.build(level);
		WaterPark.build(level);
		SkyZip.build(level);
		SkyRings.build(level);
		at(POND_DX, POND_DZ, () -> pond(level));
		MiniGolf.build(level);
		at(STAGE_DX, STAGE_DZ, () -> stage(level));
		parkour(level);
		HubPlay.build(level);
		Speedway.build(level);
		Thrill.build(level);
		Booths.build(level);
		Pets.build(level);
		Records.build(level);
		Prizes.build(level);
		Seasons.build(level);
		Coaster.build(level);
		Scenery.build(level);
		SkyTours.build(level);
		Plunge.build(level);
		// Last of all: nothing solid near a rider's head, on any ride.
		for (TrackRide r : TrackRide.ALL) {
			int n = r.clearHeadroom(level);
			if (n > 0) {
				dev.rubymod.RubyMod.LOGGER.info("Headroom: cleared {} blocks over the {}", n, r.name);
			}
		}
		Coaster.clearHeadroom(level);
		// (the green arrows on the ground are gone: they looked messy)
		plazaLights(level);
		Dressing.build(level);
		Eggs.build(level);
		Wonder.build(level, new Vec3(60.5, FLOOR + 16, -96.5));

	}

	static Vec3 stand(double x, int y, double z) {
		return new Vec3(x + 0.5, y + 1, z + 0.5);
	}

	// ----- The plaza -----

	private static void plaza(ServerLevel level) {
		// Launch pads round the garden, one to each island.
		pad(level, 21, FLOOR, 0, "Fun Park", stand(46, FLOOR, 5));
		pad(level, 19, FLOOR, -8, "Splash Island", stand(54, FLOOR, -76));
		pad(level, -11, FLOOR, 18, "Mini Golf", stand(-40, FLOOR, 57));
		pad(level, -20, FLOOR, 9, "Speedway", stand(-109, FLOOR, -40));
		pad(level, -17, FLOOR, -10, "Sky Parkour", stand(-22, FLOOR + 3, -36));
		// The toy chest, the fireworks button.
		set(level, TOY_CHEST.getX(), TOY_CHEST.getY(), TOY_CHEST.getZ(), Blocks.BARREL.defaultBlockState());
		HubHosts.add(level, new Vec3(4.5, FLOOR + 1, 11.5), 180, net.minecraft.world.entity.npc.villager.VillagerProfession.MASON,
				net.minecraft.world.entity.npc.villager.VillagerType.PLAINS, new HubHosts.Host("toys", "Tilly", "Toy Maker", 0xFF7CC0, "minecraft:firework_star",
						"Pick a box of toys! The classics (confetti cannon, rubber chicken, bouncy ball, snowballs), outdoor toys (a kite, a "
								+ "frisbee, a beach ball, a pogo stick), or silly ones (bubbles, water balloons, a sparkler, a whoopee cushion). "
								+ "Right-click to use them. They stay in the hub.",
						"Toys: right-click Tilly for some!", q -> List.of("classic|The classics", "outdoor|Kite, frisbee, beach ball, pogo",
								"silly|Bubbles, balloons, sparkler, cushion", "gadgets|Grapple, paint gun, boots, morph, camera"),
								(q, key) -> HubGames.toyChest(q, key)));
		guide(level);
		set(level, FIREWORKS.getX(), FIREWORKS.getY() - 1, FIREWORKS.getZ(), Blocks.QUARTZ_PILLAR.defaultBlockState());
		set(level, FIREWORKS.getX(), FIREWORKS.getY(), FIREWORKS.getZ(), Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.FLOOR));
		label(level, FIREWORKS.getX() + 0.5, FIREWORKS.getY() + 0.9, FIREWORKS.getZ() + 0.5, "FIREWORKS!", ChatFormatting.GOLD, 0.9F);
		// Benches facing the fountain.
		for (int z = 3; z <= 5; z++) {
			set(level, -13, FLOOR + 1, z, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
			set(level, 13, FLOOR + 1, z, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
		}
		// The cannon: aimed over the park at the splash pool on Splash Island.
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

	/** The park guide by the spawn: what's where, and how the pads work. */
	private static void guide(ServerLevel level) {
		// West of the spawn, clear of the Party Games gate (x -4..4 at z 15) and the garden's trees.
		for (int x : new int[] {-10, -6}) {
			box(level, x, FLOOR + 1, 11, x, FLOOR + 4, 11, Blocks.DARK_OAK_LOG.defaultBlockState());
		}
		box(level, -10, FLOOR + 5, 11, -6, FLOOR + 5, 11, Blocks.DARK_OAK_SLAB.defaultBlockState());
		Component text = Component.literal("PARK GUIDE").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
				.append(Component.literal("\nStep on a green pad to fly to an island.\nStep on a blue pad to fly anywhere.\nRight-click the people to play!\n")
						.withStyle(ChatFormatting.WHITE))
				.append(Component.literal("\n\u2192 Fun Park: the Ruby Rocket, the Twister Mouse,\n   bumper cars, swings, slingshot, drop tower,\n   pirate ship, teacups, Big Wheel, the Stage")
						.withStyle(ChatFormatting.YELLOW))
				.append(Component.literal("\n\u2193 Splash Island: slides, drop capsule, flume,\n   river rapids, fishing and the aquarium").withStyle(ChatFormatting.AQUA))
				.append(Component.literal("\n\u2199 Mini Golf: four crazy holes").withStyle(ChatFormatting.GREEN))
				.append(Component.literal("\n\u2190 Speedway: kart racing on ice").withStyle(ChatFormatting.RED))
				.append(Component.literal("\n\u2190 Sky Parkour, and Sky Rings with Wren").withStyle(ChatFormatting.YELLOW))
				.append(Component.literal("\n\u2196 Home Street: your own house! And Main Street:\n   ice cream, caf\u00e9, costumes, furniture, museum").withStyle(ChatFormatting.GOLD))
				.append(Component.literal("\n\u2192 Fossil Beach (by Splash Island): dig up fossils").withStyle(ChatFormatting.GOLD))
				.append(Component.literal("\n\nCaptain Cloud flies you on a happy ghast!").withStyle(ChatFormatting.AQUA))
				.append(Component.literal("\n\nTilly has toys and gadgets (and a camera!).\nPhotos go on the Family Photo Wall.").withStyle(ChatFormatting.WHITE))
				.append(Component.literal("\n\nYour Park Map (in your hotbar) flies you anywhere.\nCoach Kit runs Tag, Hide and Seek and more!")
						.withStyle(ChatFormatting.WHITE));
		keep(Props.text(level, new Vec3(-7.5, FLOOR + 1.6, 11.5), text, 0.55F, TAG));
	}

	/**
	 * The park was rearranged (the gallery went, islands merged and grew): clear away the old islands, once. A marker in
	 * the world's folder says it's been done.
	 */
	private static void moveOut(ServerLevel level) {
		java.nio.file.Path mark = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("rubywars").resolve("park_v3");
		if (java.nio.file.Files.exists(mark)) {
			return;
		}
		// x, z, radius: the gallery, the stage, the pond, the old tower and its slides, the flume, Spooky Hollow, the old
		// Speedway, and the old coaster's footings round the plaza.
		int[][] old = {{-41, 6, 12}, {-46, -34, 14}, {0, 56, 16}, {44, -44, 26}, {6, -63, 16}, {44, 40, 18}, {-96, 6, 32}};
		for (int[] o : old) {
			for (int x = -o[2]; x <= o[2]; x++) {
				for (int z = -o[2]; z <= o[2]; z++) {
					if (x * x + z * z > o[2] * o[2]) {
						continue;
					}
					for (int y = FLOOR - 32; y <= FLOOR + 48; y++) {
						BlockPos p = new BlockPos(o[0] + x, y, o[1] + z);
						if (!level.getBlockState(p).isAir()) {
							level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
						}
					}
				}
			}
		}
		// The old coaster's support footings in the sky, and the old islets.
		for (int x = -130; x <= 130; x++) {
			for (int z = -130; z <= 130; z++) {
				for (int y = FLOOR - 13; y <= FLOOR - 4; y++) {
					BlockPos p = new BlockPos(x, y, z);
					if (Math.hypot(x, z) > 25 && level.getBlockState(p).is(Blocks.GRASS_BLOCK) && level.getBlockState(p.below()).is(Blocks.DIRT)) {
						for (int dx = -5; dx <= 5; dx++) {
							for (int dz = -5; dz <= 5; dz++) {
								for (int dy = -9; dy <= 7; dy++) {
									BlockPos q = p.offset(dx, dy, dz);
									if (Math.hypot(q.getX(), q.getZ()) > 25 && q.getY() < FLOOR - 2 && !level.getBlockState(q).isAir()) {
										level.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
									}
								}
							}
						}
					}
				}
			}
		}
		try {
			java.nio.file.Files.createDirectories(mark.getParent());
			java.nio.file.Files.writeString(mark, "done");
		} catch (java.io.IOException e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't mark the park as moved", e);
		}
	}

	/** Is anyone (in the hub) within {@code r} blocks of x, z? */
	static boolean near(ServerLevel level, double x, double z, double r) {
		for (ServerPlayer p : level.players()) {
			if (!p.isSpectator() && Math.hypot(p.getX() - x, p.getZ() - z) < r) {
				return true;
			}
		}
		return false;
	}

	/** Glowing arrows on the ground from the spawn out to each green launch pad. */
	private static void arrows(ServerLevel level) {
		Vec3 spawn = new Vec3(0.5, FLOOR + 1.03, 8.5);
		int[][] pads = {{21, 0}, {19, -8}, {11, 18}, {-11, 18}, {-20, 9}, {-20, -4}, {-17, -10}};
		// (the one at -20, -4 is the Sky Rings launch pad now)
		BlockState glow = Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState();
		for (int[] pad : pads) {
			Vec3 to = new Vec3(pad[0] + 0.5, FLOOR + 1.03, pad[1] + 0.5);
			Vec3 dir = to.subtract(spawn).normalize();
			Vec3 side = new Vec3(-dir.z, 0, dir.x);
			double len = to.distanceTo(spawn);
			for (double d = 3.5; d < len - 2.5; d += 2.6) {
				Vec3 tip = spawn.add(dir.scale(d));
				for (int s : new int[] {-1, 1}) {
					Display.BlockDisplay b = beam(level, tip.subtract(dir.scale(0.45)).add(side.scale(0.4 * s)), tip, 0.13F, 0.03F, glow);
					if (b != null) {
						((DisplayAccessor) b).rubymod$setBrightness(new net.minecraft.util.Brightness(15, 15));
					}
				}
			}
		}
	}

	/** Strings of coloured lights from cherry tree to cherry tree round the plaza. */
	private static void plazaLights(ServerLevel level) {
		int[][] trees = {{15, -6}, {17, 2}, {14, 8}, {7, 16}, {-7, 16}, {-14, 8}, {-17, 2}, {-15, -6}};
		DyeColor[] colors = Seasons.lights();
		int n = 0;
		for (int k = 0; k + 1 < trees.length; k++) {
			Vec3 a = new Vec3(trees[k][0] + 0.5, FLOOR + 4.6, trees[k][1] + 0.5);
			Vec3 b = new Vec3(trees[k + 1][0] + 0.5, FLOOR + 4.6, trees[k + 1][1] + 0.5);
			int steps = (int) (a.distanceTo(b) / 1.2);
			for (int i = 1; i < steps; i++) {
				double t = i / (double) steps;
				Vec3 p = a.lerp(b, t).add(0, -1.1 * Math.sin(Math.PI * t), 0);
				Display.BlockDisplay d = piece(level, p, Blocks.STAINED_GLASS.pick(colors[n++ % colors.length]).defaultBlockState(), new Vector3f(0.22F, 0.3F, 0.22F),
						new Quaternionf());
				if (d != null) {
					((DisplayAccessor) d).rubymod$setBrightness(new net.minecraft.util.Brightness(15, 15));
				}
			}
		}
	}

	/** Each island's own sounds, for whoever's on it: carnival music, waves and gulls, a spooky drone, rushing water. */
	private static void ambience(ServerLevel level) {
		if (tick % 20 != 0) {
			return;
		}
		var random = level.getRandom();
		for (ServerPlayer p : level.players()) {
			Vec3 at = p.position();
			if (at.distanceTo(new Vec3(FunPark.MX, FLOOR, FunPark.MZ)) < 45) {
				if (tick % 240 == 0) {
					hear(p, dev.rubymod.registry.ModSounds.PARK_CARNIVAL, new Vec3(56, FLOOR + 3, 2), 0.7F);
				}
				if (tick % 160 == 80) {
					hear(p, dev.rubymod.registry.ModSounds.PARK_CROWD, at, 0.35F);
				}
			} else if (at.distanceTo(new Vec3(SplashIsland.MX, FLOOR, SplashIsland.MZ)) < 55) {
				if (tick % 180 == 0) {
					hear(p, dev.rubymod.registry.ModSounds.PARK_WAVES, at.add(random.nextDouble() * 10 - 5, 0, random.nextDouble() * 10 - 5), 0.6F);
				}
				if (random.nextInt(9) == 0) {
					hear(p, dev.rubymod.registry.ModSounds.PARK_GULLS, at.add(random.nextDouble() * 16 - 8, 8, random.nextDouble() * 16 - 8), 0.6F);
				}
			} else if (false) {
				// (the Haunted Mansion's gone)
			}
		}
	}

	private static void hear(ServerPlayer p, net.minecraft.sounds.SoundEvent sound, Vec3 at, float volume) {
		p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
				net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), SoundSource.AMBIENT, at.x, at.y, at.z, volume, 1.0F,
				p.getRandom().nextLong()));
	}

	// ----- The Pond -----

	private static void pond(ServerLevel level) {
		label(level, 0.5, FLOOR + 9, 56.5, "FISHING VILLAGE", ChatFormatting.AQUA, 2.4F);
		// The fishing pond, with a dock.
		box(level, -8, FLOOR - 4, 46, 5, FLOOR - 4, 57, Blocks.CLAY.defaultBlockState());
		for (int x = -8; x <= 5; x++) {
			for (int z = 46; z <= 57; z++) {
				boolean rim = x == -8 || x == 5 || z == 46 || z == 57;
				box(level, x, FLOOR - 3, z, x, FLOOR, z, rim ? Blocks.MUD_BRICKS.defaultBlockState() : Blocks.WATER.defaultBlockState());
			}
		}
		box(level, -1, FLOOR, 46, 0, FLOOR, 50, Blocks.SPRUCE_PLANKS.defaultBlockState());
		set(level, -2, FLOOR + 1, 45, Blocks.BARREL.defaultBlockState());
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
		HubRides.swing(level, shift(new Vec3(6.5, FLOOR + 5.9, 62.5)), 4.3);
		HubRides.swing(level, shift(new Vec3(8.5, FLOOR + 5.9, 62.5)), 4.3);
		label(level, 7.5, FLOOR + 8, 62.5, "SWINGS\nright-click a seat", ChatFormatting.YELLOW, 0.8F);
		for (int z = 50; z <= 52; z++) {
			set(level, 9, FLOOR + 1, z, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
		}
		lamp(level, -12, FLOOR + 1, 52);
		lamp(level, 11, FLOOR + 1, 58);
		Village.build(level);
	}

	// ----- The Stage -----

	private static void stage(ServerLevel level) {
		// The stage, its back wall of lights, the DJ's jukebox.
		box(level, -52, FLOOR + 1, -43, -40, FLOOR + 1, -38, Blocks.DARK_OAK_PLANKS.defaultBlockState());
		for (int x = -52; x <= -40; x++) {
			for (int y = FLOOR + 2; y <= FLOOR + 6; y++) {
				set(level, x, y, -44, (x + y) % 3 == 0 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState());
			}
		}
		set(level, -46, FLOOR + 2, -40, Blocks.JUKEBOX.defaultBlockState());
		for (int x : new int[] {-51, -41}) {
			box(level, x, FLOOR + 2, -42, x, FLOOR + 3, -42, Blocks.NOTE_BLOCK.defaultBlockState());
		}
		// The dance floor: glass over lights (it changes colour while music plays).
		for (int x = -51; x <= -41; x++) {
			for (int z = -36; z <= -29; z++) {
				set(level, x, FLOOR - 1, z, Blocks.SEA_LANTERN.defaultBlockState());
				set(level, x, FLOOR, z, Blocks.STAINED_GLASS.pick((x + z) % 2 == 0 ? DyeColor.PURPLE : DyeColor.BLACK).defaultBlockState());
				DANCE_FLOOR.add(new BlockPos(x + OX, FLOOR, z + OZ));
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
		Concert.build(level);
	}

	// ----- Sky parkour -----

	private static void parkour(ServerLevel level) {
		int x0 = -22;
		BlockState[] colors = {Blocks.CONCRETE.pick(DyeColor.LIGHT_BLUE).defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.MAGENTA).defaultBlockState(),
			Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState()};
		// The start: a platform just off the plaza.
		box(level, x0 - 2, FLOOR + 2, -38, x0 + 2, FLOOR + 2, -34, Blocks.QUARTZ_BLOCK.defaultBlockState());
		PARKOUR.add(new BlockPos(x0, FLOOR + 2, -36));
		CHECKPOINTS.add(0);
		for (int x : new int[] {x0 - 2, x0 + 2}) {
			box(level, x, FLOOR + 3, -38, x, FLOOR + 6, -38, Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState());
		}
		box(level, x0 - 2, FLOOR + 7, -38, x0 + 2, FLOOR + 7, -38, Blocks.CONCRETE.pick(DyeColor.MAGENTA).defaultBlockState());
		label(level, x0 + 0.5, FLOOR + 8.6, -37.5, "SKY PARKOUR", ChatFormatting.YELLOW, 1.6F);
		HubHosts.add(level, new Vec3(x0 + 1.5, FLOOR + 3, -34.5), 180, net.minecraft.world.entity.npc.villager.VillagerProfession.BUTCHER,
				net.minecraft.world.entity.npc.villager.VillagerType.SNOW, new HubHosts.Host("parkour", "Pip", "Sky Parkour", 0xFFD040, "minecraft:feather",
						"Jump from block to block across the sky! Fall off and you pop back to the last gold checkpoint. How fast can you reach the end?",
						"Sky Parkour: right-click Pip to start!", HubHosts.play("Start the course!"), (q, key) -> {
							q.teleportTo(-21.5, FLOOR + 3, -36.5);
							q.setYRot(180);
							PARKOUR_AT.remove(q.getUUID());
							PARKOUR_STARTED.put(q.getUUID(), ((ServerLevel) q.level()).getGameTime());
							q.sendOverlayMessage(Component.literal("Go! Jump to the coloured blocks.").withStyle(ChatFormatting.YELLOW));
						}));
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
				model(level, new Vec3(x + 1.6, y + 1, z + 0.5), 0, "golf_flag", 1.3F, 0.0F);
			}
		}
		BlockPos end = PARKOUR.get(PARKOUR.size() - 1);
		label(level, end.getX() + 0.5, end.getY() + 3.4, end.getZ() + 0.5, "YOU MADE IT!", ChatFormatting.GOLD, 1.2F);
		travelPad(level, end.getX(), end.getY(), end.getZ() - 4, "parkour");
		box(level, end.getX() - 1, end.getY(), end.getZ() - 3, end.getX() + 1, end.getY(), end.getZ() - 3, Blocks.GOLD_BLOCK.defaultBlockState());
		parkourZone = new AABB(x0 - 8, FLOOR - 30, end.getZ() - 8, x0 + 8, FLOOR + 40, -32);
	}

	// ----- Every tick -----

	public static void tick(ServerLevel level) {
		tick++;
		HubRides.tick(level);
		HubGames.tick(level);
		HubHosts.tick(level, tick);
		// Each area only runs while someone's close enough to see it (riders are, by being on it).
		if (near(level, FunPark.MX, FunPark.MZ, 95) || Coaster.busy()) {
			Coaster.tick(level, tick);
		}
		if (near(level, FunPark.MX, FunPark.MZ, 60) || BumperBoats.busy() || FunRides.busy()) {
			FunPark.tick(level);
			BumperBoats.tick(level);
			FunRides.tick(level);
		}
		if (near(level, -44, 44, 40)) {
			MiniGolf.tick(level);
		}
		if (near(level, WaterPark.CX + WaterPark.DX, WaterPark.CZ + WaterPark.DZ, 45)) {
			WaterPark.tick(level);
		}
		SplashIsland.tick(level, tick);
		SkyZip.tick(level, tick);
		if (near(level, STAGE_CENTER.x, STAGE_CENTER.z, 40)) {
			Concert.tick(level);
		}
		if (near(level, POND.getCenter().x, POND.getCenter().z, 40)) {
			Village.tick(level, tick);
		}
		HubPlay.tick(level);
		SkyRings.tick(level);
		HubToys.tick(level);
		NightShow.tick(level, tick, lampsLit);
		Fishing.tick(level);
		if (!level.players().isEmpty()) {
			Butterflies.tick(level);
		}
		if (near(level, 50, 75, 45) || SpinCoaster.busy()) {
			SpinCoaster.tick(level);
		}
		if (tick % 20 == 0) {
			for (ServerPlayer p : level.players()) {
				SkyRings.tidy(p);
			}
		}
		if (near(level, Speedway.CX, Speedway.CZ, 60) || Speedway.busy()) {
			Speedway.tick(level, tick);
		}
		if (near(level, Thrill.CX, Thrill.CZ, 50) || Thrill.busy()) {
			Thrill.tick(level);
			Booths.tick(level);
		}
		Pets.tick(level, tick);
		MainStreet.tick(level, tick);
		SkyTours.tick(level);
		Dressing.tick(level, tick);
		Eggs.tick(level);
		HomeStreet.tick(level, tick);
		Wonder.tick(level);
		Plunge.tick(level);
		if (Museum.near(level)) {
			Museum.tick(level, tick);
		}
		Seasons.tick(level, tick);
		Scenery.tick(level, tick);
		ambience(level);
		if (lampsLit && tick % 2400 == 1200) {
			fireworksUntil = tick + 8 * 20;
		}
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
				if (pad.to() == null) {
					openTravel(p, pad.here());
				} else {
					fly(p, pad.to(), pad.label());
					continue;
				}
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
					HubRides.start(p, HubRides.arc(from, CANNON_TARGET, 45, 150), HubRides.Kind.FLIGHT, null, 0, 0, 0);
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
							if (started != null) {
								Records.submit(level, "Fastest sky parkour", p.getName().getString(), (int) (level.getGameTime() - started), true, Speedway::time);
							}
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
		int[][] spots = {{FX, FZ, 15}, {FunPark.MX, FunPark.MZ + 20, 20}, {SplashIsland.MX, SplashIsland.MZ, 25}, {8, -134, 12}, {-44, 44, 14},
			{STAGE_DX - 46, STAGE_DZ - 34, 10}, {0, 0, 20}};
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
