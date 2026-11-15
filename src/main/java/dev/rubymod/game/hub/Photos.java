package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.game.Hub;
import dev.rubymod.mixin.ItemFrameAccessor;
import dev.rubymod.net.MorePayloads;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The Toy Camera and the Family Photo Wall: snap a photo of whatever you're looking at (your game takes it, without the
 * screen clutter), and it comes out as a picture: one for your pocket, one up on the photo wall on the plaza.
 */
final class Photos {
	private Photos() {
	}

	static final String CAMERA = "Toy Camera";
	/** The wall: along z = 20, x -8 to -4, three rows; photos hang on its spawn side. */
	private static final int WX1 = -8;
	private static final int WX2 = -4;
	private static final int WZ = 20;
	private static final int ROWS = 3;
	private static final int SPOTS = (WX2 - WX1 + 1) * ROWS;
	private static final List<Integer> MAPS = new ArrayList<>();
	private static final List<UUID> FRAMES = new ArrayList<>();
	/** Who's been asked for a photo, and when (game tick). */
	private static final Map<UUID, Long> ASKED = new HashMap<>();
	private static boolean loaded;

	static void build(ServerLevel level) {
		FRAMES.clear();
		load(level);
		for (int x = WX1 - 1; x <= WX2 + 1; x++) {
			for (int y = FLOOR + 1; y <= FLOOR + ROWS + 1; y++) {
				boolean rim = x == WX1 - 1 || x == WX2 + 1 || y == FLOOR + ROWS + 1;
				HubPark.set(level, x, y, WZ, rim ? Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState() : Blocks.DARK_OAK_PLANKS.defaultBlockState());
			}
		}
		HubPark.label(level, (WX1 + WX2) / 2.0 + 0.5, FLOOR + ROWS + 2.6, WZ - 0.3, "FAMILY PHOTO WALL", ChatFormatting.GOLD, 1.0F);
		HubPark.label(level, (WX1 + WX2) / 2.0 + 0.5, FLOOR + ROWS + 2.1, WZ - 0.3, "Get a Toy Camera from Tilly!", ChatFormatting.WHITE, 0.5F);
		for (int i = 0; i < MAPS.size(); i++) {
			hang(level, i);
		}
	}

	private static BlockPos spot(int i) {
		int w = WX2 - WX1 + 1;
		return new BlockPos(WX1 + i % w, FLOOR + ROWS - i / w, WZ - 1);
	}

	private static void hang(ServerLevel level, int i) {
		GlowItemFrame f = new GlowItemFrame(level, spot(i), Direction.NORTH);
		ItemStack map = new ItemStack(Items.FILLED_MAP);
		map.set(DataComponents.MAP_ID, new MapId(MAPS.get(i)));
		f.setItem(map, false);
		((ItemFrameAccessor) f).rubymod$setFixed(true);
		f.setPermanentlyInvulnerable(true);
		HubPark.keep(f);
		level.addFreshEntity(f);
		FRAMES.add(f.getUUID());
	}

	/** Click! Ask the photographer's game for the picture. True if it was the camera. */
	static boolean snap(ServerPlayer p, ItemStack held) {
		if (!HubGames.isToy(held) || !held.getHoverName().getString().equals(CAMERA)) {
			return false;
		}
		if (p.getCooldowns().isOnCooldown(held)) {
			return true;
		}
		p.getCooldowns().addCooldown(held, 60);
		ASKED.put(p.getUUID(), p.level().getGameTime());
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new MorePayloads.PhotoAsk(p.getName().getString()));
		p.level().playSound(null, p.blockPosition(), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.PLAYERS, 1.0F, 1.4F);
		return true;
	}

	/** The picture's come back: make it a map, give one to the photographer, hang it on the wall. */
	static void received(ServerPlayer p, byte[] pixels) {
		Long asked = ASKED.remove(p.getUUID());
		if (asked == null || !(p.level() instanceof ServerLevel level) || !level.dimension().equals(Hub.HUB) || level.getGameTime() - asked > 200
				|| pixels.length != 128 * 128) {
			return;
		}
		MapItemSavedData data = MapItemSavedData.createFresh(0, 0, (byte) 0, false, false, level.dimension()).locked();
		for (int y = 0; y < 128; y++) {
			for (int x = 0; x < 128; x++) {
				data.setColor(x, y, pixels[y * 128 + x]);
			}
		}
		MapId id = level.getFreeMapId();
		level.setMapData(id, data);
		ItemStack photo = HubGames.toy(Items.FILLED_MAP, 1, "Photo by " + p.getName().getString(), ChatFormatting.GOLD);
		photo.set(DataComponents.MAP_ID, id);
		HubGames.hand(p, photo);
		// Up on the wall (the oldest comes down when it's full).
		load(level);
		MAPS.add(0, id.id());
		while (MAPS.size() > SPOTS) {
			MAPS.remove(MAPS.size() - 1);
		}
		save(level);
		for (UUID f : FRAMES) {
			HubPark.discard(level, f);
		}
		FRAMES.clear();
		for (int i = 0; i < MAPS.size(); i++) {
			hang(level, i);
		}
		p.sendSystemMessage(Component.literal("📷 Click! Your photo's in your pocket, and up on the Family Photo Wall on the plaza.").withStyle(ChatFormatting.GOLD));
	}

	private static Path file(ServerLevel level) {
		return level.getServer().getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("photos.txt");
	}

	private static void load(ServerLevel level) {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Path f = file(level);
			if (Files.exists(f)) {
				for (String line : Files.readAllLines(f)) {
					if (!line.isBlank()) {
						MAPS.add(Integer.parseInt(line.trim()));
					}
				}
			}
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't read the photo wall", e);
		}
	}

	private static void save(ServerLevel level) {
		StringBuilder b = new StringBuilder();
		for (int id : MAPS) {
			b.append(id).append('\n');
		}
		try {
			Files.createDirectories(file(level).getParent());
			Files.writeString(file(level), b.toString());
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't save the photo wall", e);
		}
	}

	static void reset() {
		FRAMES.clear();
		ASKED.clear();
	}
}
