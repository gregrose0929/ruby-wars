package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.UUID;

import dev.rubymod.game.Props;
import dev.rubymod.mixin.TextDisplayAccessor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Pond, as a little fishing village: a longer dock with lanterns, a boathouse, Captain Finn who hands out rods,
 * a board of the latest catches, and a picnic spot by the swings.
 */
final class Village {
	private Village() {
	}

	private static UUID board;

	/** The swan boats: a real boat you steer, wearing a swan. */
	private record Swan(Vec3 home, UUID[] ids) {
	}

	private static final java.util.List<Swan> SWANS = new java.util.ArrayList<>();

	static void build(ServerLevel level) {
		// The old benches by the travel pad go (the boathouse is there now).
		HubPark.box(level, 9, FLOOR + 1, 50, 9, FLOOR + 1, 52, Blocks.AIR.defaultBlockState());
		// The dock: out into the pond, then along, with lantern posts.
		BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState();
		HubPark.box(level, -1, FLOOR, 46, 0, FLOOR, 52, plank);
		HubPark.box(level, -5, FLOOR, 51, 0, FLOOR, 52, plank);
		for (int[] post : new int[][] {{-1, 46}, {-1, 52}, {-5, 52}, {0, 49}}) {
			HubPark.set(level, post[0], FLOOR + 1, post[1], Blocks.SPRUCE_FENCE.defaultBlockState());
			HubPark.set(level, post[0], FLOOR + 2, post[1], Blocks.LANTERN.defaultBlockState());
		}
		// The boathouse.
		BlockState wall = Blocks.SPRUCE_PLANKS.defaultBlockState();
		BlockState log = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
		for (int x = 7; x <= 11; x++) {
			for (int z = 52; z <= 56; z++) {
				boolean edge = x == 7 || x == 11 || z == 52 || z == 56;
				boolean corner = (x == 7 || x == 11) && (z == 52 || z == 56);
				for (int y = FLOOR + 1; y <= FLOOR + 3; y++) {
					boolean door = x == 7 && z == 54 && y <= FLOOR + 2;
					boolean window = edge && !corner && y == FLOOR + 2 && (z == 54 && x == 11 || x == 9 && (z == 52 || z == 56));
					HubPark.set(level, x, y, z, !edge || door ? Blocks.AIR.defaultBlockState() : window ? Blocks.GLASS_PANE.defaultBlockState() : corner ? log : wall);
				}
			}
		}
		for (int i = 0; i <= 3; i++) {
			for (int z = 51; z <= 57; z++) {
				HubPark.set(level, 6 + i, FLOOR + 4 + i, z, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
				HubPark.set(level, 12 - i, FLOOR + 4 + i, z, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
			}
		}
		HubPark.box(level, 9, FLOOR + 7, 51, 9, FLOOR + 7, 57, Blocks.DARK_OAK_SLAB.defaultBlockState());
		HubPark.set(level, 8, FLOOR + 1, 55, Blocks.BARREL.defaultBlockState());
		HubPark.set(level, 10, FLOOR + 1, 55, Blocks.BARREL.defaultBlockState());
		HubPark.set(level, 6, FLOOR + 1, 53, Blocks.BARREL.defaultBlockState());
		HubPark.label(level, 9.5, FLOOR + 8.6, 54.5, "BOATHOUSE", ChatFormatting.GOLD, 1.0F);
		// The catch board.
		HubPark.box(level, 3, FLOOR + 1, 43, 6, FLOOR + 3, 43, Blocks.DARK_OAK_PLANKS.defaultBlockState());
		HubPark.box(level, 3, FLOOR + 4, 43, 6, FLOOR + 4, 43, Blocks.DARK_OAK_SLAB.defaultBlockState());
		Display.TextDisplay t = Props.text(level, new Vec3(5.0, FLOOR + 1.3, 44.0), Component.literal("BIG CATCH BOARD"), 0.6F, HubPark.TAG);
		HubPark.keep(t);
		board = t == null ? null : t.getUUID();
		catchBoard(level);
		// A picnic by the swings.
		for (int x = 1; x <= 3; x++) {
			for (int z = 58; z <= 60; z++) {
				HubPark.set(level, x, FLOOR + 1, z, Blocks.CARPET.pick((x + z) % 2 == 0 ? DyeColor.RED : DyeColor.WHITE).defaultBlockState());
			}
		}
		HubPark.set(level, 4, FLOOR + 1, 59, Blocks.BARREL.defaultBlockState());
		SWANS.clear();
		for (Vec3 home : new Vec3[] {new Vec3(-5.5, FLOOR + 0.9, 48.5), new Vec3(2.5, FLOOR + 0.9, 55.5), new Vec3(-5.5, FLOOR + 0.9, 55.5)}) {
			SWANS.add(new Swan(home, new UUID[2]));
		}
		HubPark.label(level, -9.5, FLOOR + 2.6, 50.5, "SWAN BOATS\nright-click a swan, steer with W A S D", ChatFormatting.WHITE, 0.7F);
		HubHosts.add(level, new Vec3(-3.5, FLOOR + 1, 44.5), 180, VillagerProfession.FISHERMAN, VillagerType.SWAMP,
				new HubHosts.Host("fishing", "Captain Finn", "Fishing", 0x40C8FF, "minecraft:fishing_rod",
						"Grab a rod and cast into the pond (right-click), then right-click again when the bobber dips! You never know what you'll catch.",
						"Fishing: right-click Captain Finn for a rod!", HubHosts.play("Give me a rod!"), (p, key) -> {
							HubGames.fishingRods(p);
							HubGames.hold(p, "Pond Rod");
						}));
	}

	/** Swans follow their boats; a missing boat comes back, and an empty one that's drifted off goes home. */
	static void tick(ServerLevel level, int tick) {
		if (SWANS.isEmpty() || !level.isPositionEntityTicking(BlockPos.containing(SWANS.get(0).home()))) {
			return;
		}
		// Every so often: any boats or swans round the pond that aren't one of ours (left over from before) go.
		if (tick % 200 == 0) {
			java.util.Set<UUID> ours = new java.util.HashSet<>();
			for (Swan s : SWANS) {
				ours.add(s.ids()[0]);
				ours.add(s.ids()[1]);
			}
			for (Entity e : level.getEntities((Entity) null, HubPark.POND.inflate(6, 4, 6), e -> e.entityTags().contains(HubRides.TEMP)
					&& (e instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat || e instanceof Display.ItemDisplay) && !ours.contains(e.getUUID()) && !e.isVehicle())) {
				e.discard();
			}
		}
		for (Swan s : SWANS) {
			Entity boat = s.ids()[0] == null ? null : level.getEntity(s.ids()[0]);
			Entity swan = s.ids()[1] == null ? null : level.getEntity(s.ids()[1]);
			if (boat == null) {
				net.minecraft.world.entity.vehicle.boat.Boat b = net.minecraft.world.entity.EntityTypes.OAK_BOAT.create(level,
						net.minecraft.world.entity.EntitySpawnReason.EVENT);
				if (b == null) {
					continue;
				}
				b.snapTo(s.home().x, s.home().y, s.home().z, 0, 0);
				b.setPermanentlyInvulnerable(true);
				b.addTag(HubRides.TEMP);
				HubPark.live(b);
				level.addFreshEntity(b);
				s.ids()[0] = b.getUUID();
				boat = b;
			}
			if (swan == null) {
				Display.ItemDisplay d = HubPark.tempModel(level, boat.position(), boat.getYRot(), "swan", 2.1F, 0.6F);
				s.ids()[1] = d == null ? null : d.getUUID();
				swan = d;
			}
			if (swan != null) {
				swan.teleportTo(boat.getX(), boat.getY() + 0.1, boat.getZ());
				swan.setYRot(boat.getYRot() + 180);
			}
			if (!boat.isVehicle() && tick % 200 == 0 && !HubPark.POND.inflate(1, 2, 1).contains(boat.position())) {
				boat.teleportTo(s.home().x, s.home().y, s.home().z);
				boat.setDeltaMovement(Vec3.ZERO);
			}
		}
	}

	static void reset() {
		SWANS.clear();
	}

	/** Write the latest catches on the board. */
	static void catchBoard(ServerLevel level) {
		if (board == null || !(level.getEntity(board) instanceof Display.TextDisplay t)) {
			return;
		}
		MutableComponent text = Component.literal("BIG CATCH BOARD").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		if (HubGames.CATCH_LOG.isEmpty()) {
			text.append(Component.literal("\nNothing caught yet.\nTalk to Captain Finn!").withStyle(ChatFormatting.WHITE));
		}
		for (String c : HubGames.CATCH_LOG) {
			text.append(Component.literal("\n" + c).withStyle(ChatFormatting.WHITE));
		}
		((TextDisplayAccessor) t).rubymod$setText(text);
	}
}
