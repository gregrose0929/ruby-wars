package dev.rubymod.industry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import dev.rubymod.RubyMod;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Everyone's Blueprints, kept from season to season: each one you fill is remembered (the {@link #MAX} latest designs),
 * and when a new season starts and your things are cleared, they come back to you, ready for a Constructor. Only the
 * designs carry over: the blocks to build them, you gather again. Kept in world/rubywars/blueprint-library/ (which a new
 * season's island leaves alone).
 */
public final class BlueprintLibrary {
	private BlueprintLibrary() {
	}

	public static final int MAX = 12;

	private static Path file(MinecraftServer server, UUID player) {
		return server.getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("blueprint-library").resolve(player + ".dat");
	}

	/** This player's saved designs (blueprint items), oldest first. */
	public static List<ItemStack> load(MinecraftServer server, UUID player) {
		List<ItemStack> out = new ArrayList<>();
		Path path = file(server, player);
		if (!Files.exists(path)) {
			return out;
		}
		try {
			CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
			var ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
			for (Tag t : root.getListOrEmpty("blueprints")) {
				ItemStack.CODEC.parse(ops, t).result().ifPresent(out::add);
			}
		} catch (Exception e) {
			RubyMod.LOGGER.warn("Couldn't read the blueprint library {}", path, e);
		}
		return out;
	}

	private static void save(MinecraftServer server, UUID player, List<ItemStack> list) {
		Path path = file(server, player);
		try {
			Files.createDirectories(path.getParent());
			var ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
			ListTag tags = new ListTag();
			for (ItemStack s : list) {
				ItemStack.CODEC.encodeStart(ops, s).result().ifPresent(tags::add);
			}
			CompoundTag root = new CompoundTag();
			root.put("blueprints", tags);
			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
			NbtIo.writeCompressed(root, tmp);
			Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			RubyMod.LOGGER.warn("Couldn't save the blueprint library {}", path, e);
		}
	}

	/** A Blueprint was just filled (or is about to be cleared away): remember its design. */
	public static void remember(MinecraftServer server, UUID player, ItemStack blueprint) {
		if (!blueprint.is(ModItems.BLUEPRINT) || !blueprint.has(ModComponents.BLUEPRINT)) {
			return;
		}
		ItemStack copy = blueprint.copyWithCount(1);
		List<ItemStack> list = load(server, player);
		list.removeIf(s -> Objects.equals(s.get(ModComponents.BLUEPRINT), copy.get(ModComponents.BLUEPRINT)));
		list.add(copy);
		while (list.size() > MAX) {
			list.remove(0);
		}
		save(server, player, list);
	}

	/** A new season's start: hand back every saved design. Returns how many. */
	public static int giveBack(ServerPlayer player) {
		List<ItemStack> list = load(player.level().getServer(), player.getUUID());
		for (ItemStack s : list) {
			ItemStack give = s.copy();
			give.remove(ModComponents.BLUEPRINT_AT); // last season's spot is on another island: build it fresh
			if (!player.getInventory().add(give)) {
				player.drop(give, false, net.minecraft.util.Prediction.SERVER_ONLY);
			}
		}
		if (!list.isEmpty()) {
			player.sendSystemMessage(Component.literal("Your Blueprints came with you to the new season (" + list.size()
					+ "): put one in a Constructor and give it the blocks.").withStyle(ChatFormatting.AQUA));
		}
		return list.size();
	}
}
