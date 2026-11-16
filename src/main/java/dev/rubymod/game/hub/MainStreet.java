package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Main Street, north of Home Street: pretend-play shops. Scoops' Ice Cream (pick up to three scoops from the tubs, then
 * eat it a scoop at a time), Hazel's Café (hot chocolate, cookies, cake), Dot's Costume Shop (hats and outfits to try on),
 * and Benny's little bus, which takes you round both streets. Baby animals live round the park too.
 */
final class MainStreet {
	private MainStreet() {
	}

	private static final String SCOOPS = "rubymod_scoops";
	private static final String BITES = "rubymod_bites";
	private static final String COSTUME = "rubymod_costume";

	// ----- Ice cream -----

	private static final String[] FLAVOURS = {"Strawberry", "Chocolate", "Mint", "Vanilla", "Blueberry", "Lemon"};
	private static final int[] FLAVOUR_RGB = {0xFF8FB8, 0x8A5530, 0x9EF0C0, 0xFFF3D0, 0x7F9BFF, 0xFFEB6E};
	private static final DyeColor[] TUBS = {DyeColor.PINK, DyeColor.BROWN, DyeColor.LIME, DyeColor.WHITE, DyeColor.LIGHT_BLUE, DyeColor.YELLOW};
	private static final Map<BlockPos, Integer> TUB_AT = new HashMap<>();

	// ----- Baby animals -----

	private record Baby(UUID id, Vec3 home) {
	}

	private static final List<Baby> BABIES = new ArrayList<>();
	private static TrackRide bus;
	private static int tick;

	static void reset() {
		if (bus != null) {
			bus.reset();
		}
		BABIES.clear();
		TUB_AT.clear();
	}

	static void build(ServerLevel level) {
		TUB_AT.clear();
		iceCream(level);
		cafe(level);
		costumes(level);
		bus(level);
		babies(level);
		HubPark.label(level, -61.5, FLOOR + 8, -68.5, "MAIN STREET", ChatFormatting.AQUA, 2.2F);
	}

	/** A little shop: walls, a floor, a flat roof with a coloured trim, a doorway in the east wall. */
	private static void shell(ServerLevel level, int x1, int z1, int x2, int z2, BlockState wall, BlockState floorA, BlockState floorB, BlockState trim) {
		int doorZ = (z1 + z2) / 2;
		for (int x = x1; x <= x2; x++) {
			for (int z = z1; z <= z2; z++) {
				boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
				HubPark.set(level, x, FLOOR, z, (x + z) % 2 == 0 ? floorA : floorB);
				for (int y = 1; y <= 5; y++) {
					boolean door = x == x2 && y <= 3 && Math.abs(z - doorZ) <= 1;
					boolean window = x == x2 && y >= 2 && y <= 3 && (Math.abs(z - doorZ) == 3 || Math.abs(z - doorZ) == 4);
					HubPark.set(level, x, FLOOR + y, z, !edge || door ? Blocks.AIR.defaultBlockState() : window ? Blocks.GLASS.defaultBlockState() : wall);
				}
				HubPark.set(level, x, FLOOR + 6, z, edge ? trim : Blocks.SMOOTH_QUARTZ.defaultBlockState());
			}
		}
		for (int x = x1 + 2; x < x2; x += 4) {
			for (int z = z1 + 2; z < z2; z += 4) {
				HubPark.set(level, x, FLOOR + 5, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
			}
		}
		// A striped awning over the door.
		for (int z = doorZ - 4; z <= doorZ + 4; z++) {
			HubPark.set(level, x2 + 1, FLOOR + 4, z, Blocks.WOOL.pick(Math.floorMod(z, 2) == 0 ? DyeColor.WHITE : colourOf(trim)).defaultBlockState());
		}
	}

	private static DyeColor colourOf(BlockState trim) {
		for (DyeColor c : DyeColor.values()) {
			if (trim.is(Blocks.CONCRETE.pick(c))) {
				return c;
			}
		}
		return DyeColor.RED;
	}

	private static void iceCream(ServerLevel level) {
		int x1 = -80;
		int x2 = -68;
		int z1 = -84;
		int z2 = -74;
		shell(level, x1, z1, x2, z2, Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.PINK).defaultBlockState(),
				Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.PINK).defaultBlockState());
		// The counter, with the tubs set in its top.
		for (int z = z1 + 1; z < z2; z++) {
			HubPark.set(level, -75, FLOOR + 1, z, Blocks.QUARTZ_BLOCK.defaultBlockState());
		}
		for (int k = 0; k < FLAVOURS.length; k++) {
			int z = z1 + 2 + k;
			BlockPos tub = new BlockPos(-75, FLOOR + 1, z);
			HubPark.set(level, tub.getX(), tub.getY(), tub.getZ(), Blocks.CONCRETE_POWDER.pick(TUBS[k]).defaultBlockState());
			TUB_AT.put(tub, k);
			HubPark.label(level, -74.3, FLOOR + 2.15, z + 0.5, FLAVOURS[k], ChatFormatting.WHITE, 0.4F).setYRot(90);
		}
		HubPark.label(level, -74.5, FLOOR + 4.3, -78.5, "Right-click a tub for a scoop!\n(up to 3 on a cone)", ChatFormatting.LIGHT_PURPLE, 0.6F).setYRot(90);
		// Little tables and stools.
		for (int[] t : new int[][] {{-71, -82}, {-71, -76}}) {
			HubPark.set(level, t[0], FLOOR + 1, t[1], Blocks.CHERRY_FENCE.defaultBlockState());
			HubPark.set(level, t[0], FLOOR + 2, t[1], Blocks.CHERRY_PRESSURE_PLATE.defaultBlockState());
			HubPark.set(level, t[0] + 1, FLOOR + 1, t[1], Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
			HubPark.set(level, t[0] - 1, FLOOR + 1, t[1], Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
		}
		HubPark.label(level, x2 + 1.2, FLOOR + 7.3, -78.5, "SCOOPS' ICE CREAM", ChatFormatting.LIGHT_PURPLE, 1.5F).setYRot(-90);
		HubHosts.add(level, new Vec3(-77.5, FLOOR + 1, -78.5), -90, VillagerProfession.BUTCHER, VillagerType.SNOW,
				new HubHosts.Host("icecream", "Scoops", "Ice Cream", 0xFF8FB8, "minecraft:snowball",
						"Make your own ice cream! Right-click the tubs on the counter: each one adds a scoop to your cone, up to three. "
								+ "Strawberry, chocolate, mint, vanilla, blueberry and lemon. Then right-click to eat it, one scoop at a time. Yum!",
						"Ice cream: right-click the tubs on the counter!", p -> List.of("cone|An empty cone, please"), (p, key) -> {
							hold(p, cone(new ArrayList<>()));
							p.sendOverlayMessage(Component.literal("Here's your cone! Now right-click the tubs for scoops.").withStyle(ChatFormatting.LIGHT_PURPLE));
						}));
	}

	private static ItemStack cone(List<Integer> scoops) {
		StringBuilder name = new StringBuilder();
		List<Integer> colours = new ArrayList<>();
		StringBuilder data = new StringBuilder();
		for (int i = 0; i < scoops.size(); i++) {
			int f = scoops.get(i);
			name.append(i == 0 ? "" : i == scoops.size() - 1 ? " & " : ", ").append(FLAVOURS[f]);
			colours.add(FLAVOUR_RGB[f]);
			data.append(i == 0 ? "" : ",").append(f);
		}
		ItemStack s = HubGames.toy(Items.STICK, 1, scoops.isEmpty() ? "Ice Cream Cone" : name + " Ice Cream", ChatFormatting.LIGHT_PURPLE);
		s.set(DataComponents.ITEM_MODEL, dev.rubymod.RubyMod.id("park_cone_" + scoops.size()));
		s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(), colours));
		CompoundTag tag = s.get(DataComponents.CUSTOM_DATA).copyTag();
		tag.putString(SCOOPS, data.toString());
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return s;
	}

	private static List<Integer> scoops(ItemStack s) {
		var data = s.get(DataComponents.CUSTOM_DATA);
		if (data == null || !data.copyTag().contains(SCOOPS)) {
			return null;
		}
		List<Integer> out = new ArrayList<>();
		for (String f : data.copyTag().getStringOr(SCOOPS, "").split(",")) {
			if (!f.isEmpty()) {
				out.add(Integer.parseInt(f));
			}
		}
		return out;
	}

	/** Into their hand: swapped for what they're holding if that's a cone or a snack, else into the inventory. */
	private static void hold(ServerPlayer p, ItemStack s) {
		ItemStack held = p.getMainHandItem();
		if (held.isEmpty() || scoops(held) != null || bites(held) >= 0) {
			p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, s);
		} else {
			// Whatever they were holding goes in their pocket; the treat goes in their hand.
			ItemStack was = held.copy();
			p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, s);
			if (!p.getInventory().add(was)) {
				p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, was);
				if (!p.getInventory().add(s)) {
					p.sendOverlayMessage(Component.literal("Your hands are full!"));
				}
			}
		}
		p.inventoryMenu.broadcastChanges();
	}

	/** Right-clicking a block: an ice cream tub. Null: not one. */
	static net.minecraft.world.InteractionResult useBlock(ServerPlayer p, BlockPos pos) {
		Integer flavour = TUB_AT.get(pos);
		if (flavour == null) {
			return null;
		}
		ItemStack held = p.getMainHandItem();
		List<Integer> have = scoops(held);
		if (have == null || have.size() >= 3) {
			have = new ArrayList<>();
		}
		have.add(flavour);
		hold(p, cone(have));
		ServerLevel level = (ServerLevel) p.level();
		level.playSound(null, pos, SoundEvents.SNOW_PLACE, SoundSource.PLAYERS, 1.0F, 1.3F);
		level.sendParticles(new DustParticleOptions(FLAVOUR_RGB[flavour], 1.2F), pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 8, 0.25, 0.1, 0.25, 0);
		p.sendOverlayMessage(Component.literal(have.size() < 3 ? "A scoop of " + FLAVOURS[flavour] + "! (" + have.size() + " of 3)"
				: "Three scoops! Right-click to eat it.").withStyle(ChatFormatting.LIGHT_PURPLE));
		return net.minecraft.world.InteractionResult.SUCCESS;
	}

	// ----- The café -----

	private static ItemStack snack(Item icon, String model, String name, int bites) {
		ItemStack s = HubGames.toy(icon, 1, name, ChatFormatting.GOLD);
		if (model != null) {
			s.set(DataComponents.ITEM_MODEL, dev.rubymod.RubyMod.id("park_" + model));
		}
		CompoundTag tag = s.get(DataComponents.CUSTOM_DATA).copyTag();
		tag.putInt(BITES, bites);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return s;
	}

	private static int bites(ItemStack s) {
		var data = s.get(DataComponents.CUSTOM_DATA);
		return data == null ? -1 : data.copyTag().getIntOr(BITES, -1);
	}

	private static void cafe(ServerLevel level) {
		int x1 = -80;
		int x2 = -68;
		int z1 = -98;
		int z2 = -88;
		shell(level, x1, z1, x2, z2, Blocks.BRICKS.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState(),
				Blocks.CONCRETE.pick(DyeColor.GREEN).defaultBlockState());
		for (int z = z1 + 1; z < z2; z++) {
			HubPark.set(level, -75, FLOOR + 1, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
		}
		HubPark.set(level, -75, FLOOR + 2, z1 + 2, Blocks.CAKE.defaultBlockState());
		HubPark.set(level, -75, FLOOR + 2, z1 + 4, Blocks.DECORATED_POT.defaultBlockState());
		HubPark.set(level, -78, FLOOR + 1, z1 + 2, Blocks.SMOKER.defaultBlockState().setValue(net.minecraft.world.level.block.FurnaceBlock.FACING, Direction.EAST));
		HubPark.set(level, -78, FLOOR + 1, z1 + 3, Blocks.BARREL.defaultBlockState());
		for (int[] t : new int[][] {{-71, -96}, {-71, -90}}) {
			HubPark.set(level, t[0], FLOOR + 1, t[1], Blocks.SPRUCE_FENCE.defaultBlockState());
			HubPark.set(level, t[0], FLOOR + 2, t[1], Blocks.SPRUCE_PRESSURE_PLATE.defaultBlockState());
			HubPark.set(level, t[0] + 1, FLOOR + 1, t[1], Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
			HubPark.set(level, t[0] - 1, FLOOR + 1, t[1], Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
		}
		HubPark.label(level, x2 + 1.2, FLOOR + 7.3, -92.5, "HAZEL'S CAFE", ChatFormatting.GREEN, 1.6F).setYRot(-90);
		HubHosts.add(level, new Vec3(-77.5, FLOOR + 1, -92.5), -90, VillagerProfession.FARMER, VillagerType.PLAINS,
				new HubHosts.Host("cafe", "Hazel", "Café", 0x60C060, "minecraft:cookie",
						"Welcome to my café! What would you like? Everything's free. Right-click to eat or drink it, then sit down at a table for a rest.",
						"Hazel's Café: right-click Hazel for a treat!",
						p -> List.of("cocoa|Hot chocolate", "cookie|A cookie", "cake|A slice of cake", "pie|Pumpkin pie", "lemonade|Pink lemonade"), (p, key) -> {
							ItemStack s = switch (key) {
								case "cocoa" -> snack(Items.STICK, "cup_cocoa", "Hot Chocolate", 3);
								case "lemonade" -> snack(Items.STICK, "cup_lemonade", "Pink Lemonade", 3);
								case "cookie" -> snack(Items.COOKIE, null, "Chocolate Cookie", 2);
								case "cake" -> snack(Items.CAKE, null, "Slice of Cake", 3);
								default -> snack(Items.PUMPKIN_PIE, null, "Pumpkin Pie", 3);
							};
							hold(p, s);
							p.sendOverlayMessage(Component.literal("Here you go! Right-click to have some.").withStyle(ChatFormatting.GREEN));
						}));
	}

	/** Right-clicking with something: eat a scoop, a bite, a sip. True if it was one. */
	static boolean useItem(ServerPlayer p, ItemStack held) {
		ServerLevel level = (ServerLevel) p.level();
		Vec3 mouth = p.getEyePosition().add(p.getLookAngle().scale(0.4)).add(0, -0.2, 0);
		List<Integer> sc = scoops(held);
		if (sc != null) {
			if (sc.isEmpty()) {
				p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				level.playSound(null, p.blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.PLAYERS, 1.0F, 1.4F);
				p.sendOverlayMessage(Component.literal("Crunch! All gone. That was yummy!").withStyle(ChatFormatting.LIGHT_PURPLE));
			} else {
				int top = sc.remove(sc.size() - 1);
				p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, cone(sc));
				level.playSound(null, p.blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.PLAYERS, 1.0F, 1.2F);
				level.sendParticles(new DustParticleOptions(FLAVOUR_RGB[top], 1.0F), mouth.x, mouth.y, mouth.z, 10, 0.15, 0.1, 0.15, 0);
				p.sendOverlayMessage(Component.literal("Mmm, " + FLAVOURS[top].toLowerCase() + "!").withStyle(ChatFormatting.LIGHT_PURPLE));
			}
			p.inventoryMenu.broadcastChanges();
			return true;
		}
		int b = bites(held);
		if (b >= 0) {
			boolean drink = held.getHoverName().getString().contains("Chocolate") && !held.getHoverName().getString().contains("Cookie")
					|| held.getHoverName().getString().contains("Lemonade");
			level.playSound(null, p.blockPosition(), drink ? SoundEvents.GENERIC_DRINK.value() : SoundEvents.GENERIC_EAT.value(), SoundSource.PLAYERS, 1.0F, 1.1F);
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, mouth.x, mouth.y, mouth.z, 3, 0.15, 0.1, 0.15, 0);
			if (b <= 1) {
				p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				p.sendOverlayMessage(Component.literal("All gone! Thank you, Hazel!").withStyle(ChatFormatting.GREEN));
			} else {
				CompoundTag tag = held.get(DataComponents.CUSTOM_DATA).copyTag();
				tag.putInt(BITES, b - 1);
				held.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
				p.sendOverlayMessage(Component.literal(drink ? "Slurp!" : "Nom nom!").withStyle(ChatFormatting.GREEN));
			}
			p.inventoryMenu.broadcastChanges();
			return true;
		}
		return false;
	}

	// ----- The costume shop -----

	private static final String[] HATS = {"hat_party", "hat_crown", "hat_bow", "hat_top", "hat_flower", "creeper", "dragon", "piglin", "pumpkin", "skeleton"};
	private static final String[] HAT_NAMES = {"Party Hat", "Crown", "Big Bow", "Top Hat", "Flower Crown", "Creeper Mask", "Dragon Mask", "Piglin Mask",
		"Pumpkin Head", "Skeleton Mask"};
	private static final int[] OUTFIT_RGB = {0xE03040, 0x3070E0, 0xFF80C0, 0x40C060, 0xFFD040, 0x9050E0, 0xF0F0F0, 0x303038};
	private static final String[] OUTFIT_NAMES = {"Red", "Blue", "Pink", "Green", "Yellow", "Purple", "White", "Black", "Knight", "Royal Gold"};
	private static final Map<UUID, Integer> HAT_ON = new HashMap<>();
	private static final Map<UUID, Integer> OUTFIT_ON = new HashMap<>();

	private static void costumes(ServerLevel level) {
		int x1 = -80;
		int x2 = -68;
		int z1 = -112;
		int z2 = -102;
		shell(level, x1, z1, x2, z2, Blocks.CONCRETE.pick(DyeColor.PURPLE).defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.MAGENTA).defaultBlockState(),
				Blocks.CONCRETE.pick(DyeColor.PURPLE).defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState());
		// Mirrors on the back wall, and mannequins in hats.
		for (int z = z1 + 2; z <= z2 - 2; z += 3) {
			HubPark.box(level, x1 + 1, FLOOR + 1, z, x1 + 1, FLOOR + 3, z, Blocks.GLASS.defaultBlockState());
		}
		String[] shown = {"hat_crown", "hat_top", "hat_party", "hat_flower"};
		for (int k = 0; k < shown.length; k++) {
			int z = z1 + 2 + k * 2;
			HubPark.set(level, -76, FLOOR + 1, z, Blocks.OAK_FENCE.defaultBlockState());
			HubPark.set(level, -76, FLOOR + 2, z, Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState());
			HubPark.model(level, new Vec3(-75.5, FLOOR + 3.0, z + 0.5), -90, shown[k], 0.8F, 0.0F);
		}
		HubPark.label(level, x2 + 1.2, FLOOR + 7.3, -106.5, "DOT'S COSTUMES", ChatFormatting.YELLOW, 1.5F).setYRot(-90);
		HubHosts.add(level, new Vec3(-72.5, FLOOR + 1, -110.5), -90, VillagerProfession.LIBRARIAN, VillagerType.SWAMP,
				new HubHosts.Host("costumes", "Dot", "Costume Shop", 0xFFD040, "minecraft:carved_pumpkin",
						"Dress up! Try on hats and masks, and outfits in every colour (even a knight's armour). Press the buttons again for the next one. "
								+ "Press F5 to see yourself! (Costumes are for fun: they stay here at the park.)",
						"Dot's Costumes: right-click Dot to dress up!", p -> List.of("hat|Try the next hat", "outfit|Try the next outfit", "off|Take it all off"),
						(p, key) -> {
							switch (key) {
								case "hat" -> {
									int i = (HAT_ON.getOrDefault(p.getUUID(), -1) + 1) % HATS.length;
									HAT_ON.put(p.getUUID(), i);
									p.setItemSlot(EquipmentSlot.HEAD, hat(i));
									p.sendOverlayMessage(Component.literal("Ta-da! A " + HAT_NAMES[i] + ". (Press again for the next one.)").withStyle(ChatFormatting.YELLOW));
								}
								case "outfit" -> {
									int i = (OUTFIT_ON.getOrDefault(p.getUUID(), -1) + 1) % OUTFIT_NAMES.length;
									OUTFIT_ON.put(p.getUUID(), i);
									outfit(p, i);
									p.sendOverlayMessage(Component.literal("Looking good: the " + OUTFIT_NAMES[i] + " outfit! (Press again for the next one.)")
											.withStyle(ChatFormatting.YELLOW));
								}
								default -> {
									for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
										if (costume(p.getItemBySlot(slot))) {
											p.setItemSlot(slot, ItemStack.EMPTY);
										}
									}
									HAT_ON.remove(p.getUUID());
									OUTFIT_ON.remove(p.getUUID());
									p.sendOverlayMessage(Component.literal("Back to normal!").withStyle(ChatFormatting.GRAY));
								}
							}
							((ServerLevel) p.level()).sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 1, p.getZ(), 12, 0.4, 0.6, 0.4, 0);
							p.level().playSound(null, p.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 1.0F, 1.0F);
						}));
	}

	private static ItemStack wearable(Item item, String name) {
		ItemStack s = HubGames.toy(item, 1, name, ChatFormatting.YELLOW);
		CompoundTag tag = s.get(DataComponents.CUSTOM_DATA).copyTag();
		tag.putBoolean(COSTUME, true);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		s.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return s;
	}

	private static boolean costume(ItemStack s) {
		var data = s.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(COSTUME);
	}

	private static ItemStack hat(int i) {
		String id = HATS[i];
		if (id.startsWith("hat_")) {
			ItemStack s = wearable(Items.STICK, HAT_NAMES[i]);
			s.set(DataComponents.ITEM_MODEL, dev.rubymod.RubyMod.id("park_" + id));
			return s;
		}
		Item head = switch (id) {
			case "creeper" -> Items.CREEPER_HEAD;
			case "dragon" -> Items.DRAGON_HEAD;
			case "piglin" -> Items.PIGLIN_HEAD;
			case "pumpkin" -> Items.CARVED_PUMPKIN;
			default -> Items.SKELETON_SKULL;
		};
		return wearable(head, HAT_NAMES[i]);
	}

	private static void outfit(ServerPlayer p, int i) {
		Item[] pieces;
		if (i == 8) {
			pieces = new Item[] {Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
		} else if (i == 9) {
			pieces = new Item[] {Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS};
		} else {
			pieces = new Item[] {Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
		}
		EquipmentSlot[] slots = {EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		for (int k = 0; k < 3; k++) {
			ItemStack s = wearable(pieces[k], OUTFIT_NAMES[i] + " Outfit");
			if (i < OUTFIT_RGB.length) {
				s.set(DataComponents.DYED_COLOR, new DyedItemColor(OUTFIT_RGB[i]));
			}
			p.setItemSlot(slots[k], s);
		}
	}

	// ----- Benny's bus -----

	private static void bus(ServerLevel level) {
		double y = FLOOR + 1;
		double[][] c = {{-56, -50}, {-46, -50}, {-40, -50}, {-38, -52}, {-40, -54}, {-50, -54}, {-58, -54}, {-60, -57}, {-60, -70}, {-60, -90}, {-60, -112},
			{-62, -116}, {-64, -112}, {-64, -90}, {-64, -70}, {-64, -57}, {-66, -54}, {-76, -54}, {-84, -54}, {-86, -52}, {-84, -50}, {-74, -50}, {-64, -50},
			{-58, -50}};
		List<Vec3> pts = new ArrayList<>();
		for (double[] p : c) {
			pts.add(new Vec3(p[0] + 0.5, y, p[1] + 0.5));
		}
		List<Vec3> path = HubRides.spline(pts, TrackRide.STEP);
		double[] speed = new double[path.size()];
		java.util.Arrays.fill(speed, 0.16);
		bus = new TrackRide("bus", path, speed, "bus", 3.0F, new Vec3(-59.5, FLOOR + 1, -45.5), "Benny");
		bus.seatsPer = 4;
		bus.upright = true;
		bus.seatHeight = 0.55;
		bus.waitTicks = 100;
		bus.again = "That's the end of the line! Talk to Benny to go round again.";
		bus.events.put(path.size() / 5, (lv, car) -> HubPark.say(lv, car.pos, "Beep beep! Here's Home Street. Who lives here?"));
		bus.events.put(path.size() * 2 / 5, (lv, car) -> HubPark.say(lv, car.pos, "Main Street: ice cream, the café, costumes, furniture and the museum!"));
		bus.events.put(path.size() * 3 / 4, (lv, car) -> HubPark.say(lv, car.pos, "Nearly back! Wave to everyone!"));
		HubPark.label(level, -58.5, FLOOR + 3.2, -46.5, "BUS STOP", ChatFormatting.YELLOW, 0.9F);
		HubPark.set(level, -58, FLOOR + 1, -46, Blocks.IRON_BARS.defaultBlockState());
		HubPark.set(level, -58, FLOOR + 2, -46, Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState());
		HubHosts.add(level, new Vec3(-60.5, FLOOR + 1, -45.5), 180, VillagerProfession.TOOLSMITH, VillagerType.DESERT,
				new HubHosts.Host("bus", "Benny", "Little Bus", 0xFFD040, "minecraft:minecart",
						"All aboard the little bus! It goes all the way round Home Street and up Main Street and back. Four seats: bring your friends! "
								+ "Crouch to hop off any time.",
						"The little bus: right-click Benny to ride!", HubHosts.rideButtons("All aboard!"), (p, key) -> bus.board(p, HubHosts.group(p, key))));
	}

	static boolean busy() {
		return bus != null && !bus.cars.isEmpty();
	}

	// ----- Baby animals round the park -----

	private static void babies(ServerLevel level) {
		BABIES.clear();
		Object[][] spots = {
			{EntityTypes.CHICKEN, -63, -40}, {EntityTypes.CHICKEN, -61, -39}, {EntityTypes.RABBIT, -64, -38}, {EntityTypes.CAT, -60, -43},
			{EntityTypes.PIG, -71, -100}, {EntityTypes.CAT, -8, 4}, {EntityTypes.RABBIT, 9, -6}, {EntityTypes.CHICKEN, -6, -8},
			{EntityTypes.SHEEP, 62, 14}, {EntityTypes.PIG, 70, 24}, {EntityTypes.COW, 100, 8}, {EntityTypes.TURTLE, 40, -118},
			{EntityTypes.TURTLE, 44, -120}, {EntityTypes.SHEEP, -40, 34}, {EntityTypes.COW, -50, 52}, {EntityTypes.FOX, -100, -30},
			{EntityTypes.ARMADILLO, -118, 30}, {EntityTypes.SNIFFER, -85, -100}, {EntityTypes.PANDA, 24, 72}, {EntityTypes.CAMEL, 84, -10}};
		for (Object[] s : spots) {
			@SuppressWarnings("unchecked")
			EntityType<? extends Mob> type = (EntityType<? extends Mob>) s[0];
			BlockPos at = ground(level, (Integer) s[1], (Integer) s[2]);
			if (at == null) {
				continue;
			}
			Mob m = type.create(level, EntitySpawnReason.EVENT);
			if (m == null) {
				continue;
			}
			m.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360, 0);
			m.setPermanentlyInvulnerable(true);
			m.setPersistenceRequired();
			if (m instanceof AgeableMob a) {
				a.setAge(-24000);
			}
			if (m instanceof net.minecraft.world.entity.animal.sheep.Sheep sh) {
				sh.setColor(level.getRandom().nextBoolean() ? DyeColor.PINK : DyeColor.LIGHT_BLUE);
			}
			HubPark.keep(m);
			level.addFreshEntity(m);
			Toyland.ANIMALS.add(m.getUUID());
			BABIES.add(new Baby(m.getUUID(), Vec3.atBottomCenterOf(at)));
		}
	}

	/** Somewhere to stand near x, z (an open spot on top of something solid), or null. */
	private static BlockPos ground(ServerLevel level, int x, int z) {
		for (int r = 0; r <= 4; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					for (int y = FLOOR + 4; y >= FLOOR - 2; y--) {
						BlockPos p = new BlockPos(x + dx, y, z + dz);
						if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir() && level.getBlockState(p.below()).isSolidRender()) {
							return p;
						}
					}
				}
			}
		}
		return null;
	}

	static void tick(ServerLevel level, int t) {
		tick = t;
		if (bus != null) {
			bus.tick(level);
		}
		if (tick % 40 != 0) {
			return;
		}
		for (Baby b : BABIES) {
			Entity e = level.getEntity(b.id());
			if (e == null) {
				continue;
			}
			// Babies stay babies, and don't wander far.
			if (tick % 1200 == 0 && e instanceof AgeableMob a) {
				a.setAge(-24000);
			}
			if (e.position().distanceTo(b.home()) > 7 || e.getY() < FLOOR - 4) {
				e.teleportTo(b.home().x, b.home().y, b.home().z);
				if (e instanceof Mob m) {
					m.getNavigation().stop();
				}
			}
		}
	}
}
