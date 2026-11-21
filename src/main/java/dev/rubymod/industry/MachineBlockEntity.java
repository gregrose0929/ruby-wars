package dev.rubymod.industry;

import java.util.List;
import java.util.UUID;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.CruiseMissileEntity;
import dev.rubymod.entity.ModProjectile;
import dev.rubymod.game.Armor;
import dev.rubymod.game.Rules;
import dev.rubymod.game.SeasonData;
import dev.rubymod.registry.ModBlockEntities;
import dev.rubymod.registry.ModItems;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * The brains of every machine (see {@link MachineKind}). Inventories work with
 * hoppers from any side (inputs go in, outputs come out), and machines push their
 * output into any container or machine next to them, so a Drill next to a
 * Refinery next to a Trade Depot is a complete ore-to-rubies line.
 */
public class MachineBlockEntity extends BlockEntity implements WorldlyContainer, ExtendedMenuProvider<MachineMenu.Opening> {
	/** Status codes shown on the machine screen. */
	public static final int IDLE = 0;
	public static final int WORKING = 1;
	public static final int NO_POWER = 2;
	public static final int OUTPUT_FULL = 3;
	public static final int NO_INPUT = 4;
	public static final int NIGHT = 5;

	private static final UUID NOBODY = new UUID(0, 0);
	/** Machines keep working at this fraction of their speed while unloaded (up to a day). */
	private static final double OFFLINE_RATE = 0.5;

	private final NonNullList<ItemStack> items;
	private UUID owner = NOBODY;
	private int tier = 1;
	private int progress;
	private int burn;
	private int burnMax;
	private int recipe;
	/** What the Refinery is making from what it has now (for its screen). */
	private ItemStack refining = ItemStack.EMPTY;
	private int status;
	private long lastTick = -1;
	/** Real time it last ran (ms): the server stops its clock while nobody's on, and machines still catch up for that. */
	private long lastReal = -1;
	/** Catching up: belts are followed to their end instead of having things put on them. */
	private boolean catchingUp;
	private int aux;
	/** Constructor: raw id + 1 of the block item it's waiting for (0 = none). */
	private int missing;
	/** This machine's own tick counter (schedules don't depend on the world clock). */
	private long ticks;
	/** Ruby Drill: loads dug up, and how many of them were rubies. */
	private long loads;
	private long rubiesFound;
	/** Ruby Drill: what it looks for (Recipes.FOCUS_*), and whether it leaves plain rock behind. */
	private int focus;
	private boolean leaveStone;
	/** Constructor: repair the copied structure where it was copied, instead of building a copy in front. */
	private boolean repair;
	/** Ruby Drill: what the ground round it is rich in (looked at now and then, not saved). */
	private DrillSite site;
	private long siteAt = -100_000;
	/** Silos this machine takes its work from (block positions): once one runs dry it isn't filled with what this makes. */
	private final java.util.Set<Long> fedBy = new java.util.HashSet<>();
	/** Slot layout version (2: the Refinery has a sand slot). */
	private static final int LAYOUT = 2;

	public MachineBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.MACHINE, pos, state);
		this.items = NonNullList.withSize(Math.max(1, kind(state).slots()), ItemStack.EMPTY);
	}

	public static MachineKind kind(BlockState state) {
		return state.getBlock() instanceof MachineBlock block ? block.kind() : MachineKind.BATTERY;
	}

	public MachineKind kind() {
		return kind(getBlockState());
	}

	public UUID owner() {
		return owner;
	}

	/** The power grid this machine uses: its owner's (only you put equipment in your base). */
	public UUID power() {
		return owner;
	}

	public long loads() {
		return loads;
	}

	public long rubiesFound() {
		return rubiesFound;
	}

	private void found(ItemStack stack) {
		loads++;
		if (stack.is(ModItems.RUBY)) {
			rubiesFound += stack.getCount();
		}
		Production.add(owner, stack);
		setChanged();
	}

	public int focus() {
		return focus;
	}

	/** The Assembler's (or Ammo Factory's) recipe number. */
	public int recipe() {
		return recipe;
	}

	public boolean leavesStone() {
		return leaveStone;
	}

	public void cycleFocus(int step) {
		focus = Math.floorMod(focus + step, Recipes.FOCUS_NAMES.length);
		setChanged();
	}

	public boolean repairs() {
		return repair;
	}

	public void toggleRepair() {
		repair = !repair;
		progress = 0;
		setChanged();
	}

	public void toggleStone() {
		leaveStone = !leaveStone;
		setChanged();
	}

	/** What the ground round this drill is rich in (looked at again every few minutes). */
	public DrillSite site() {
		if (level instanceof ServerLevel server && (site == null || server.getGameTime() - siteAt > 6000 || server.getGameTime() < siteAt)) {
			site = DrillSite.scan(server, worldPosition);
			siteAt = server.getGameTime();
		}
		return site == null ? DrillSite.PLAIN : site;
	}

	/** One haul for this drill, or empty when it was plain rock it leaves behind. */
	private ItemStack dig(ServerLevel level) {
		ItemStack dug = Recipes.drill(level.getRandom(), tier, worldPosition.getY() < 0, focus, site());
		return leaveStone && Recipes.isRock(dug) ? ItemStack.EMPTY : dug;
	}

	/** Crouch-click with an empty hand: everything it has made, into your inventory. Returns how many items. */
	public int collectAll(ServerPlayer player) {
		int taken = 0;
		for (int i = firstOutput(); i < items.size(); i++) {
			ItemStack s = items.get(i);
			if (s.isEmpty()) {
				continue;
			}
			int before = s.getCount();
			player.getInventory().add(s);
			taken += before - s.getCount();
		}
		if (taken > 0) {
			setChanged();
			level.playSound(null, worldPosition, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4F, 1.2F);
		}
		return taken;
	}

	public int tier() {
		return tier;
	}

	public void setTier(int tier) {
		this.tier = Math.max(1, Math.min(MachineKind.MAX_TIER, tier));
		setChanged();
	}

	public int status() {
		return status;
	}

	public void setStatus(int status) {
		this.status = status;
	}

	public void setMissing(int missing) {
		this.missing = missing;
	}

	public int progress() {
		return progress;
	}

	public void setProgress(int progress) {
		this.progress = progress;
	}

	public void setOwner(UUID owner) {
		this.owner = owner;
		setChanged();
	}

	/** Can this player use the machine (owner, or trusted in the owner's claim)? Unowned machines (training ground) are shared. */
	public boolean canUse(Player player) {
		if (owner.equals(NOBODY)) {
			return true;
		}
		if (owner.equals(dev.rubymod.game.Horde.TEAM)) {
			return dev.rubymod.game.Horde.playing(player);
		}
		if (owner.equals(player.getUUID()) || Rules.isAdminBypass(player)) {
			return true;
		}
		SeasonData.Claim claim = SeasonData.get().claimOf(owner);
		return claim != null && claim.canUse(player.getUUID());
	}

	// ----- Ticking -----

	public static void serverTick(Level level, BlockPos pos, BlockState state, MachineBlockEntity be) {
		if (level instanceof ServerLevel serverLevel) {
			be.tick(serverLevel);
		}
	}

	public long ticks() {
		return ticks;
	}

	private void tick(ServerLevel level) {
		MachineKind kind = kind();
		long now = level.getGameTime();
		ticks++;
		long nowMs = System.currentTimeMillis();
		long away = lastTick >= 0 ? now - lastTick : 0;
		if (lastTick >= 0 && lastReal > 0 && nowMs > lastReal) {
			away = Math.max(away, (nowMs - lastReal) / 50);
		}
		if (away > 200) {
			catchUp(level, away);
		}
		lastTick = now;
		lastReal = nowMs;
		switch (kind) {
			case COMBUSTION_GENERATOR -> tickGenerator(level);
			case SOLAR_PANEL -> tickSolar(level);
			case BATTERY -> {
				Grid.addBattery(power(), kind.batteryCapacity(tier));
				status = WORKING;
			}
			case RUBY_DRILL -> tickDrill(level);
			case ORE_REFINERY -> tickRefinery(level);
			case AMMO_FACTORY, ASSEMBLER -> tickAmmo(level);
			case TRADE_DEPOT -> tickDepot(level);
			case VAULT -> tickVault(level);
			case SHIELD_GENERATOR -> tickShield(level);
			case POINT_DEFENSE -> tickPointDefense(level);
			case CONSTRUCTOR -> Blueprints.tickConstructor(level, this);
			case TESLA_COIL -> tickTesla(level);
		}
		if (kind.outputs > 0 && ticks % 10 == 0) {
			pushOutputs(level);
			pullFromSilos(level);
		}
		showWorking(level);
		if (ticks % 20 == 0) {
			BaseMachines.report(level, this);
		}
	}

	/** When it last worked; it shows as working for a couple of seconds after (so it doesn't flicker between jobs). */
	private long workedAt = -1000;

	/** Lit (glowing, with particles) while working. Machines work silently (no grinding or clanking at your base). */
	private void showWorking(ServerLevel level) {
		long now = level.getGameTime();
		if (status == WORKING) {
			workedAt = now;
		}
		boolean working = now - workedAt < 40 && kind() != MachineKind.BATTERY;
		if (status == WORKING && switch (kind()) {
			case RUBY_DRILL, ORE_REFINERY, AMMO_FACTORY, ASSEMBLER, TRADE_DEPOT, CONSTRUCTOR -> true;
			default -> false;
		}) {
			dev.rubymod.game.Goals.working(owner, worldPosition.asLong(), now);
		}
		BlockState state = getBlockState();
		if (state.hasProperty(MachineBlock.LIT) && state.getValue(MachineBlock.LIT) != working) {
			level.setBlock(worldPosition, state.setValue(MachineBlock.LIT, working), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
		}
	}

	/**
	 * Back after being unloaded: drills, Refineries, Assemblers, Ammo Factories and Trade Depots do what they would
	 * have (at half speed, up to a day), taking from silos touching them and filling silos (and, but for drills,
	 * chests and machines) beside them, until they run out of work or room. What they made is told once, all
	 * machines together ({@link AwayTime}).
	 */
	public void catchUp(ServerLevel level, long elapsed) {
		if (owner.equals(NOBODY) || Grid.special(owner)) {
			return; // the training ground's, and Horde's (a game, not a base)
		}
		MachineKind kind = kind();
		switch (kind) {
			case RUBY_DRILL, ORE_REFINERY, AMMO_FACTORY, ASSEMBLER, TRADE_DEPOT -> {
			}
			default -> {
				return;
			}
		}
		long cycles = (long) (Math.min(elapsed, 24000) * OFFLINE_RATE / Math.max(1, kind.cycle(tier)));
		catchingUp = true;
		try {
			catchUpCycles(level, kind, cycles);
		} finally {
			catchingUp = false;
		}
		progress = 0;
		setChanged();
	}

	/** For tests: as if this machine last ran {@code ms} of real time ago, with the world's clock stopped meanwhile. */
	public void lastRanAgo(long ms) {
		if (level != null) {
			lastTick = level.getGameTime();
		}
		lastReal = System.currentTimeMillis() - ms;
	}

	private void catchUpCycles(ServerLevel level, MachineKind kind, long cycles) {
		for (long i = 0; i < cycles; i++) {
			if (kind == MachineKind.TRADE_DEPOT) {
				long earned = sellOnce(level);
				if (earned <= 0) {
					break;
				}
				AwayTime.sold(owner, earned);
				continue;
			}
			ItemStack made = workOnce(level);
			if (made == null) {
				// Room made, or something to work on brought in? Then once more; otherwise that's all it could do.
				pushOutputs(level);
				pullFromSilos(level);
				made = workOnce(level);
				if (made == null) {
					break;
				}
			}
			AwayTime.made(owner, made);
		}
	}

	/** One finished job while away (no power needed): what it made (empty for rock a drill leaves), or null when it couldn't. */
	private ItemStack workOnce(ServerLevel level) {
		return switch (kind()) {
			case RUBY_DRILL -> {
				ItemStack dug = dig(level);
				if (!dug.isEmpty() && !canFit(dug)) {
					yield null;
				}
				ItemStack copy = dug.copy();
				insertOutput(dug);
				found(copy);
				yield copy;
			}
			case ORE_REFINERY -> {
				ItemStack made = refineOnce(level);
				yield made.isEmpty() ? null : made;
			}
			case AMMO_FACTORY, ASSEMBLER -> {
				ItemStack made = assembleOnce();
				yield made.isEmpty() ? null : made;
			}
			default -> null;
		};
	}

	private void tickGenerator(ServerLevel level) {
		MachineKind kind = kind();
		if (burn > 0) {
			burn--;
			Grid.produce(power(), kind.output(tier));
			status = WORKING;
			if (level.getGameTime() % 8 == 0) {
				level.sendParticles(ParticleTypes.SMOKE, worldPosition.getX() + 0.5, worldPosition.getY() + 1.05, worldPosition.getZ() + 0.5,
						1, 0.1, 0, 0.1, 0.01);
			}
			return;
		}
		ItemStack fuel = items.get(0);
		int ticks = Recipes.burnTicks(fuel);
		if (ticks > 0 && Grid.charge(power()) < Grid.capacity(power())) {
			burn = burnMax = ticks;
			if (fuel.is(Items.LAVA_BUCKET)) {
				items.set(0, new ItemStack(Items.BUCKET));
			} else {
				fuel.shrink(1);
			}
			setChanged();
		} else {
			status = ticks > 0 ? OUTPUT_FULL : NO_INPUT;
		}
	}

	private void tickSolar(ServerLevel level) {
		long time = level.getOverworldClockTime() % 24000;
		boolean day = time < 12500 && !level.isRaining() && level.canSeeSky(worldPosition.above());
		if (day) {
			Grid.produce(power(), kind().output(tier));
			status = WORKING;
		} else {
			status = NIGHT;
		}
	}

	private void tickDrill(ServerLevel level) {
		if (!hasOutputSpace()) {
			status = OUTPUT_FULL;
			return;
		}
		if (!Grid.consume(power(), kind().draw(tier))) {
			status = NO_POWER;
			BaseMachines.outOfPower(level, this);
			return;
		}
		status = WORKING;
		if (++progress >= kind().cycle(tier)) {
			progress = 0;
			ItemStack dug = dig(level);
			ItemStack copy = dug.copy();
			if (insertOutput(dug)) {
				found(copy);
			}
		}
		if (level.getGameTime() % 4 == 0) {
			level.sendParticles(ParticleTypes.CRIT, worldPosition.getX() + 0.5, worldPosition.getY() + 0.1, worldPosition.getZ() + 0.5,
					2, 0.3, 0.05, 0.3, 0.05);
		}
	}

	private void tickRefinery(ServerLevel level) {
		ItemStack input = items.get(0);
		ItemStack sand = items.get(1);
		boolean gunpowder = Recipes.isGunpowderFuel(input) && Recipes.isSand(sand);
		ItemStack result = gunpowder ? Recipes.gunpowder(tier) : Recipes.refine(level, input, tier);
		refining = result;
		if (result.isEmpty()) {
			progress = 0;
			status = NO_INPUT;
			return;
		}
		if (!canFit(result)) {
			status = OUTPUT_FULL;
			return;
		}
		if (!Grid.consume(power(), kind().draw(tier))) {
			status = NO_POWER;
			return;
		}
		status = WORKING;
		if (++progress >= kind().cycle(tier)) {
			progress = 0;
			refineOnce(level);
		}
	}

	/** The Refinery finishes one job: uses the input, puts the result out. Returns what it made (empty: it couldn't). */
	private ItemStack refineOnce(ServerLevel level) {
		ItemStack input = items.get(0);
		ItemStack sand = items.get(1);
		boolean gunpowder = Recipes.isGunpowderFuel(input) && Recipes.isSand(sand);
		ItemStack result = gunpowder ? Recipes.gunpowder(tier) : Recipes.refine(level, input, tier);
		if (result.isEmpty() || !canFit(result)) {
			return ItemStack.EMPTY;
		}
		input.shrink(1);
		if (gunpowder) {
			sand.shrink(1);
		}
		Production.add(owner, result);
		ItemStack made = result.copy();
		insertOutput(result);
		return made;
	}

	private void tickAmmo(ServerLevel level) {
		Recipes.AmmoRecipe r = Recipes.recipe(kind(), recipe);
		ItemStack metal = items.get(0);
		ItemStack extra = items.get(1);
		if (!Recipes.fits(r, r.metal(), metal) || metal.getCount() < r.metalCount()
				|| r.extra() != null && (!Recipes.fits(r, r.extra(), extra) || extra.getCount() < r.extraCount())) {
			progress = 0;
			status = NO_INPUT;
			return;
		}
		ItemStack result = new ItemStack(r.result(), r.count());
		if (!canFit(result)) {
			status = OUTPUT_FULL;
			return;
		}
		if (!Grid.consume(power(), kind().draw(tier))) {
			status = NO_POWER;
			return;
		}
		status = WORKING;
		if (++progress >= kind().cycle(tier)) {
			progress = 0;
			assembleOnce();
		}
	}

	/** The Assembler (or Ammo Factory) finishes one job. Returns what it made (empty: it couldn't). */
	private ItemStack assembleOnce() {
		Recipes.AmmoRecipe r = Recipes.recipe(kind(), recipe);
		ItemStack metal = items.get(0);
		ItemStack extra = items.get(1);
		if (!Recipes.fits(r, r.metal(), metal) || metal.getCount() < r.metalCount()
				|| r.extra() != null && (!Recipes.fits(r, r.extra(), extra) || extra.getCount() < r.extraCount())) {
			return ItemStack.EMPTY;
		}
		ItemStack result = new ItemStack(r.result(), r.count());
		if (!canFit(result)) {
			return ItemStack.EMPTY;
		}
		metal.shrink(r.metalCount());
		if (r.extra() != null) {
			extra.shrink(r.extraCount());
		}
		Production.add(owner, result);
		ItemStack made = result.copy();
		insertOutput(result);
		return made;
	}

	/** The Trade Depot's last four sales (item id + 1, count, milli-rubies), newest first. */
	private final int[][] SALES = new int[4][];

	private void recordSale(ItemStack stack, int count, long paid) {
		int id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(stack.getItem()) + 1;
		if (SALES[0] != null && SALES[0][0] == id) {
			SALES[0] = new int[] {id, SALES[0][1] + count, (int) Math.min(Integer.MAX_VALUE, SALES[0][2] + paid)};
			return;
		}
		System.arraycopy(SALES, 0, SALES, 1, SALES.length - 1);
		SALES[0] = new int[] {id, count, (int) Math.min(Integer.MAX_VALUE, paid)};
	}

	private void tickDepot(ServerLevel level) {
		if (ticks % kind().cycle(tier) != 0) {
			return;
		}
		long before = Grid.rubies(owner);
		long earned = sellOnce(level);
		status = earned > 0 ? WORKING : IDLE;
		if (earned > 0 && Grid.rubies(owner) > before) {
			level.playSound(null, worldPosition, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.7F, 1.4F);
		}
	}

	/** One round of selling (up to 8 items a tier). Returns what it was paid, in milli-rubies. */
	private long sellOnce(ServerLevel level) {
		int budget = 8 * tier;
		long earned = 0;
		for (int i = 0; i < items.size() && budget > 0; i++) {
			ItemStack stack = items.get(i);
			int price = Recipes.price(stack, tier);
			if (stack.isEmpty() || price <= 0) {
				continue;
			}
			int sell = Math.min(budget, stack.getCount());
			earned += (long) price * sell;
			recordSale(stack, sell, (long) price * sell);
			stack.shrink(sell);
			budget -= sell;
		}
		if (earned > 0) {
			dev.rubymod.game.Goals.add(level.getServer(), owner, "sell100", 8 * tier - budget);
			Grid.deposit(owner, earned);
			aux = (int) Math.min(Integer.MAX_VALUE, aux + earned);
			setChanged();
		}
		return earned;
	}

	private void tickVault(ServerLevel level) {
		ItemStack in = items.get(0);
		if (in.is(ModItems.RUBY)) {
			Grid.deposit(owner, in.getCount() * 1000L);
			items.set(0, ItemStack.EMPTY);
			level.playSound(null, worldPosition, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.6F, 1.2F);
			setChanged();
		}
		status = WORKING;
		// Daily interest, once per player per Minecraft day.
		long day = level.getOverworldClockTime() / 24000;
		Grid.Account account = Grid.account(owner);
		if (account.lastInterestDay < 0) {
			account.lastInterestDay = day;
		} else if (day > account.lastInterestDay) {
			account.lastInterestDay = day;
			long interest = Math.min(Grid.rubies(owner) * kind().interestPercent(tier) / 100, kind().interestCap(tier));
			if (interest > 0) {
				Grid.deposit(owner, interest * 1000);
				if (level.getServer().getPlayerList().getPlayer(owner) instanceof ServerPlayer player) {
					dev.rubymod.game.Alerts.toast(player, "rubymod:vault", "Vault interest: +" + interest + " rubies",
							String.format(java.util.Locale.ROOT, "Bank: %,d rubies", Grid.rubies(owner)), dev.rubymod.game.Alerts.GOLD);
				}
			}
		}
	}

	// ----- Base defenses -----

	private boolean friendly(Entity shooter) {
		if (shooter == null) {
			return false;
		}
		if (shooter.getUUID().equals(owner)) {
			return true;
		}
		if (owner.equals(dev.rubymod.game.Horde.TEAM)) {
			return shooter instanceof Player p && dev.rubymod.game.Horde.playing(p) || !dev.rubymod.game.Horde.isEnemy(shooter);
		}
		SeasonData.Claim claim = SeasonData.get().claimOf(owner);
		return claim != null && claim.canUse(shooter.getUUID());
	}

	private void tickShield(ServerLevel level) {
		MachineKind kind = kind();
		if (!Grid.consume(power(), kind.draw(tier))) {
			status = NO_POWER;
			return;
		}
		status = WORKING;
		int r = kind.radius(tier);
		Vec3 center = Vec3.atCenterOf(worldPosition);
		if (ticks % 2 == 0) {
			for (ModProjectile p : level.getEntitiesOfClass(ModProjectile.class, new AABB(center, center).inflate(r + 4),
					p -> p.isAlive() && p.position().distanceTo(center) <= r && !friendly(p.getOwner()))) {
				long cost = p instanceof CruiseMissileEntity ? 6000 : 1500;
				if (!Grid.consume(power(), cost)) {
					break;
				}
				Vec3 at = p.position();
				level.sendParticles(new DustParticleOptions(0xFF3070, 2.0F), true, true, at.x, at.y, at.z, 30, 0.8, 0.8, 0.8, 0);
				level.playSound(null, at.x, at.y, at.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 3.0F, 1.6F);
				p.discard();
			}
		}
		// A faint shimmer on the dome so people can see where it is.
		if (level.getGameTime() % 5 == 0) {
			var random = level.getRandom();
			for (int i = 0; i < 6; i++) {
				double yaw = random.nextDouble() * Math.PI * 2;
				double pitch = random.nextDouble() * Math.PI / 2;
				double x = center.x + Math.cos(yaw) * Math.cos(pitch) * r;
				double y = center.y + Math.sin(pitch) * r;
				double z = center.z + Math.sin(yaw) * Math.cos(pitch) * r;
				level.sendParticles(new DustParticleOptions(0xFF4D88, 1.2F), true, false, x, y, z, 1, 0, 0, 0, 0);
			}
		}
	}

	private void tickPointDefense(ServerLevel level) {
		if (ticks % 4 != 0) {
			return;
		}
		MachineKind kind = kind();
		int r = kind.radius(tier);
		Vec3 gun = Vec3.atCenterOf(worldPosition).add(0, 0.8, 0);
		Entity target = null;
		double best = r;
		for (Entity e : level.getEntities((Entity) null, new AABB(gun, gun).inflate(r), e -> e.isAlive()
				&& (e instanceof ModProjectile p && !friendly(p.getOwner()) || e instanceof AircraftEntity a && hostileAircraft(a)))) {
			double d = e.position().distanceTo(gun);
			if (d < best && canSee(level, gun, e.getBoundingBox().getCenter())) {
				best = d;
				target = e;
			}
		}
		if (target == null) {
			status = IDLE;
			return;
		}
		if (!Grid.consume(power(), 200)) {
			status = NO_POWER;
			return;
		}
		status = WORKING;
		Vec3 aim = target.getBoundingBox().getCenter();
		Vec3 step = aim.subtract(gun).normalize();
		for (double d = 1; d < best; d += 1.5) {
			Vec3 p = gun.add(step.scale(d));
			level.sendParticles(ParticleTypes.FLAME, true, false, p.x, p.y, p.z, 1, 0, 0, 0, 0);
		}
		level.playSound(null, worldPosition, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.BLOCKS, 2.0F, 2.0F);
		level.playSound(null, worldPosition, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 0.5F, 2.0F);
		if (target instanceof ModProjectile projectile) {
			if (level.getRandom().nextFloat() < 0.35F + 0.08F * tier) {
				level.sendParticles(ParticleTypes.EXPLOSION, true, true, aim.x, aim.y, aim.z, 1, 0, 0, 0, 0);
				projectile.discard();
			}
		} else {
			Armor.hit(level, target, null, ownerPlayer(level), 3.0F + tier);
		}
	}

	/** Tesla Coil: every 3/4 second, an arc to the nearest hostile in range. */
	private void tickTesla(ServerLevel level) {
		if (ticks % 15 != 0) {
			return;
		}
		int r = kind().radius(tier);
		Vec3 top = Vec3.atCenterOf(worldPosition).add(0, 0.9, 0);
		SeasonData.Claim claim = SeasonData.get().claimOf(owner);
		net.minecraft.world.entity.LivingEntity target = null;
		double best = r;
		for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
				new AABB(top, top).inflate(r), e -> e.isAlive() && !e.isSpectator())) {
			boolean hostile = dev.rubymod.game.Horde.isEnemy(e) || (claim == null ? e instanceof net.minecraft.world.entity.monster.Enemy
					: Rules.defenseTarget(claim, e) || dev.rubymod.game.Practice.isDummyFor(claim, e));
			double d = e.position().distanceTo(top);
			if (hostile && d < best && canSee(level, top, e.getEyePosition())) {
				best = d;
				target = e;
			}
		}
		if (target == null) {
			status = IDLE;
			return;
		}
		if (!Grid.consume(power(), 400)) {
			status = NO_POWER;
			return;
		}
		status = WORKING;
		// A jagged arc.
		Vec3 to = target.getBoundingBox().getCenter();
		Vec3 prev = top;
		var random = level.getRandom();
		for (int i = 1; i <= 8; i++) {
			Vec3 p = top.lerp(to, i / 8.0).add(i < 8 ? (random.nextDouble() - 0.5) * 0.8 : 0, i < 8 ? (random.nextDouble() - 0.5) * 0.8 : 0,
					i < 8 ? (random.nextDouble() - 0.5) * 0.8 : 0);
			for (int k = 0; k <= 4; k++) {
				Vec3 q = prev.lerp(p, k / 4.0);
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, true, false, q.x, q.y, q.z, 1, 0, 0, 0, 0);
			}
			level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0, 0, 0, 0);
			prev = p;
		}
		level.playSound(null, worldPosition, SoundEvents.TRIDENT_THUNDER.value(), SoundSource.BLOCKS, 0.5F, 1.8F);
		if (dev.rubymod.game.Practice.isDummy(target)) {
			dev.rubymod.game.Practice.hit(target, "Tesla Coil");
		} else {
			target.hurtServer(level, dev.rubymod.registry.ModDamage.source(level, dev.rubymod.registry.ModDamage.TURRET, null, ownerPlayer(level)), 3.0F + tier);
		}
	}

	private boolean hostileAircraft(AircraftEntity a) {
		if (a.onGround()) {
			return false;
		}
		Entity pilot = a.getControllingPassenger();
		if (pilot == null) {
			return a.isDrone();
		}
		return !friendly(pilot) && (Rules.phase().pvp() || Rules.phase() == dev.rubymod.game.Phase.LOBBY);
	}

	private ServerPlayer ownerPlayer(ServerLevel level) {
		return level.getServer().getPlayerList().getPlayer(owner);
	}

	private static boolean canSee(ServerLevel level, Vec3 from, Vec3 to) {
		return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())).getType()
				== HitResult.Type.MISS;
	}

	// ----- Outputs -----

	private int firstOutput() {
		return kind().inputs;
	}

	private boolean hasOutputSpace() {
		for (int i = firstOutput(); i < items.size(); i++) {
			if (items.get(i).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private boolean canFit(ItemStack stack) {
		int left = stack.getCount();
		for (int i = firstOutput(); i < items.size(); i++) {
			ItemStack s = items.get(i);
			if (s.isEmpty()) {
				return true;
			}
			if (ItemStack.isSameItemSameComponents(s, stack)) {
				left -= s.getMaxStackSize() - s.getCount();
				if (left <= 0) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean insertOutput(ItemStack stack) {
		for (int i = firstOutput(); i < items.size() && !stack.isEmpty(); i++) {
			ItemStack s = items.get(i);
			if (s.isEmpty()) {
				items.set(i, stack.copy());
				stack.setCount(0);
			} else if (ItemStack.isSameItemSameComponents(s, stack)) {
				int move = Math.min(stack.getCount(), s.getMaxStackSize() - s.getCount());
				s.grow(move);
				stack.shrink(move);
			}
		}
		setChanged();
		return stack.isEmpty();
	}

	/** Push outputs into neighboring containers (chests, hoppers, other machines' inputs). */
	/**
	 * Push outputs onward. Machines and conveyor belts come first (so a production line
	 * keeps flowing); plain chests next to the machine only catch what's left over.
	 */
	private void pushOutputs(ServerLevel level) {
		// A drill keeps what it digs (come and collect it), unless a conveyor belt, a hopper or an Ore Silo touching it takes it.
		boolean beltsOnly = kind() == MachineKind.RUBY_DRILL;
		if (beltsOnly) {
			pushToSilos(level);
		}
		for (int pass = 0; pass < 2; pass++) {
			for (Direction dir : Direction.values()) {
				BlockPos side = worldPosition.relative(dir);
				if (pass == 0 && dir.getAxis().isHorizontal() && ConveyorBlock.leadsAway(level.getBlockState(side), dir)) {
					if (catchingUp) {
						alongBelt(level, side);
					} else {
						dropOnBelt(level, side);
					}
					continue;
				}
				if (beltsOnly) {
					continue;
				}
				BlockEntity neighbor = level.getBlockEntity(side);
				if (neighbor instanceof SiloBlockEntity silo && fedBy.contains(side.asLong()) && silo.isEmpty()) {
					continue; // it feeds this machine: keep it for more of the same
				}
				if (!(neighbor instanceof Container target) || neighbor instanceof net.minecraft.world.level.block.entity.HopperBlockEntity
						|| (neighbor instanceof MachineBlockEntity) != (pass == 0)) {
					continue;
				}
				for (int i = firstOutput(); i < items.size(); i++) {
					ItemStack s = items.get(i);
					if (s.isEmpty()) {
						continue;
					}
					ItemStack rest = HopperBlockEntity.addItem(this, target, s.copy(), dir.getOpposite());
					if (rest.getCount() != s.getCount()) {
						items.set(i, rest);
						setChanged();
					}
				}
			}
		}
	}

	/** Is this silo one this machine may use (yours, unowned, or a teammate's in your claim)? */
	private boolean mayUse(SiloBlockEntity silo) {
		UUID them = silo.owner();
		if (them == null || them.equals(owner) || owner.equals(NOBODY)) {
			return true;
		}
		SeasonData.Claim claim = SeasonData.get().claimOf(owner);
		return claim != null && claim.canUse(them);
	}

	/** The Ore Silos touching this machine that it may use. */
	private List<SiloBlockEntity> silos(ServerLevel level) {
		List<SiloBlockEntity> out = new java.util.ArrayList<>(2);
		for (Direction dir : Direction.values()) {
			if (level.getBlockEntity(worldPosition.relative(dir)) instanceof SiloBlockEntity silo && mayUse(silo)) {
				out.add(silo);
			}
		}
		return out;
	}

	/** A drill: what it dug, into silos touching it (each takes one kind, so silos all round sort it). */
	private void pushToSilos(ServerLevel level) {
		List<SiloBlockEntity> silos = silos(level);
		if (silos.isEmpty()) {
			return;
		}
		boolean moved = false;
		for (int i = firstOutput(); i < items.size(); i++) {
			ItemStack s = items.get(i);
			if (s.isEmpty()) {
				continue;
			}
			// A silo that already holds it first, then an empty one.
			for (int pass = 0; pass < 2 && !s.isEmpty(); pass++) {
				for (SiloBlockEntity silo : silos) {
					boolean holds = !silo.type().isEmpty();
					if (holds == (pass == 0) && silo.put(s) > 0) {
						moved = true;
					}
				}
			}
		}
		if (moved) {
			setChanged();
		}
	}

	/**
	 * Refineries, Assemblers and Ammo Factories take what they work on from Ore Silos touching them, so a silo of
	 * raw iron against a Refinery needs no belt. Never something it makes itself (a silo it fills stays full).
	 */
	private void pullFromSilos(ServerLevel level) {
		switch (kind()) {
			case ORE_REFINERY, AMMO_FACTORY, ASSEMBLER -> {
			}
			default -> {
				return;
			}
		}
		List<SiloBlockEntity> silos = silos(level);
		if (silos.isEmpty()) {
			return;
		}
		for (int slot = kind().inputs - 1; slot >= 0; slot--) {
			for (SiloBlockEntity silo : silos) {
				ItemStack type = silo.type();
				ItemStack in = items.get(slot);
				if (type.isEmpty() || makes(type) || !canPlaceItem(slot, type)
						|| !in.isEmpty() && !ItemStack.isSameItemSameComponents(in, type)) {
					continue;
				}
				int room = type.getMaxStackSize() - in.getCount();
				if (room <= 0) {
					break;
				}
				ItemStack got = silo.take(room);
				if (got.isEmpty()) {
					continue;
				}
				fedBy.add(silo.getBlockPos().asLong());
				if (in.isEmpty()) {
					items.set(slot, got);
				} else {
					in.grow(got.getCount());
				}
				setChanged();
			}
		}
	}

	/** Does this machine take what's in this silo (it's touching, may use it, and it's something it works on)? */
	public boolean takesFrom(SiloBlockEntity silo) {
		switch (kind()) {
			case ORE_REFINERY, AMMO_FACTORY, ASSEMBLER -> {
			}
			default -> {
				return false;
			}
		}
		ItemStack type = silo.type();
		if (type.isEmpty() || !mayUse(silo) || makes(type)) {
			return false;
		}
		for (int slot = 0; slot < kind().inputs; slot++) {
			if (canPlaceItem(slot, type)) {
				return true;
			}
		}
		return false;
	}

	/** Is this a drill that fills this silo? */
	public boolean fills(SiloBlockEntity silo) {
		return kind() == MachineKind.RUBY_DRILL && mayUse(silo);
	}

	/** What it takes from a silo touching it right now (a slot it goes in is free or holds the same; empty when nothing). */
	public ItemStack takingFromSilo(ServerLevel level) {
		for (SiloBlockEntity silo : silos(level)) {
			if (takesFrom(silo)) {
				ItemStack type = silo.type();
				for (int slot = 0; slot < kind().inputs; slot++) {
					ItemStack in = items.get(slot);
					if (canPlaceItem(slot, type) && (in.isEmpty() || ItemStack.isSameItemSameComponents(in, type))) {
						return type;
					}
				}
			}
		}
		return ItemStack.EMPTY;
	}

	/** How many silos touching this drill it fills. */
	public int silosFilled(ServerLevel level) {
		return kind() == MachineKind.RUBY_DRILL ? silos(level).size() : 0;
	}

	/** Is this something this machine has made (it's in an output slot, or it's the recipe's result)? */
	private boolean makes(ItemStack stack) {
		for (int i = firstOutput(); i < items.size(); i++) {
			if (ItemStack.isSameItem(items.get(i), stack)) {
				return true;
			}
		}
		if (kind() == MachineKind.ORE_REFINERY) {
			return ItemStack.isSameItem(refining, stack);
		}
		return Recipes.recipe(kind(), recipe).result() == stack.getItem();
	}

	// ----- Settings kept on the item (picked up with the Wrench, or broken) -----

	/** Put this machine's settings (recipe, what a drill looks for, Stone: Leave it) on its item. */
	public void keepSettings(ItemStack item) {
		net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
		if (recipe != 0 && hasRecipes()) {
			tag.putInt("recipe", recipe);
		}
		if (kind() == MachineKind.RUBY_DRILL) {
			if (focus != 0) {
				tag.putInt("focus", focus);
			}
			if (leaveStone) {
				tag.putBoolean("leave_stone", true);
			}
		}
		if (!tag.isEmpty()) {
			item.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
		}
	}

	/** Placed from an item that kept its settings: set them again. */
	public void applySettings(ItemStack item) {
		var data = item.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
		if (data == null) {
			return;
		}
		net.minecraft.nbt.CompoundTag tag = data.copyTag();
		if (hasRecipes()) {
			recipe = Math.floorMod(tag.getIntOr("recipe", 0), Recipes.recipes(kind()).size());
		}
		if (kind() == MachineKind.RUBY_DRILL) {
			focus = Math.floorMod(tag.getIntOr("focus", 0), Recipes.FOCUS_NAMES.length);
			leaveStone = tag.getBooleanOr("leave_stone", false);
		}
		setChanged();
	}

	private boolean hasRecipes() {
		return kind() == MachineKind.ASSEMBLER || kind() == MachineKind.AMMO_FACTORY;
	}

	/** The settings an item kept, as tooltip lines (none when it has none). */
	public static List<String> settingsText(MachineKind kind, ItemStack item) {
		List<String> lines = new java.util.ArrayList<>();
		var data = item.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
		if (data == null) {
			return lines;
		}
		net.minecraft.nbt.CompoundTag tag = data.copyTag();
		if ((kind == MachineKind.ASSEMBLER || kind == MachineKind.AMMO_FACTORY) && tag.contains("recipe")) {
			lines.add("Set to make " + Recipes.recipe(kind, tag.getIntOr("recipe", 0)).name());
		}
		if (kind == MachineKind.RUBY_DRILL) {
			int f = Math.floorMod(tag.getIntOr("focus", 0), Recipes.FOCUS_NAMES.length);
			if (f != 0) {
				lines.add("Looks for: " + Recipes.FOCUS_NAMES[f]);
			}
			if (tag.getBooleanOr("leave_stone", false)) {
				lines.add("Leaves plain stone behind");
			}
		}
		return lines;
	}

	/** Catching up: what it made goes straight to wherever the belt line next to it ends. */
	private void alongBelt(ServerLevel level, BlockPos belt) {
		for (int i = firstOutput(); i < items.size(); i++) {
			ItemStack s = items.get(i);
			if (s.isEmpty()) {
				continue;
			}
			Belts.End end = Belts.endOf(level, belt, s);
			if (end == null || end.pos().equals(worldPosition)) {
				continue;
			}
			ItemStack rest = Belts.insert(level, end.pos(), end.face(), s.copy());
			if (rest.getCount() != s.getCount()) {
				items.set(i, rest);
				setChanged();
			}
		}
	}

	/** A few items at a time onto a conveyor belt. */
	private void dropOnBelt(ServerLevel level, BlockPos belt) {
		if (ConveyorBlock.busy(level, belt)) {
			return; // the line's backed up (or stopped): keep it in here until there's room
		}
		for (int i = firstOutput(); i < items.size(); i++) {
			ItemStack s = items.get(i);
			if (!s.isEmpty()) {
				net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(level,
						belt.getX() + 0.5, belt.getY() + 0.3, belt.getZ() + 0.5, s.split(Math.min(4, s.getCount())));
				drop.setDeltaMovement(Vec3.ZERO);
				drop.setPickUpDelay(20);
				level.addFreshEntity(drop);
				setChanged();
				return;
			}
		}
	}


	// ----- Upgrades and buttons -----

	/** Spend rubies (inventory first, then bank) to raise the tier. Returns a message. */
	public String upgrade(ServerPlayer player) {
		int cost = kind().upgradeCost(tier);
		if (cost < 0) {
			return kind().title + " is already " + MachineKind.TIER_NAMES[MachineKind.MAX_TIER - 1] + ".";
		}
		if (!dev.rubymod.game.Shop.takeRubies(player, cost)) {
			return "Upgrading to " + MachineKind.TIER_NAMES[tier] + " costs " + cost + " rubies.";
		}
		tier++;
		setChanged();
		level.playSound(null, worldPosition, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.8F, 1.0F);
		level.playSound(null, worldPosition, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.6F, 1.4F);
		if (level instanceof ServerLevel serverLevel) {
			serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5,
					20, 0.4, 0.4, 0.4, 0.1);
		}
		dev.rubymod.game.Goals.reach(player.level().getServer(), player.getUUID(), "mk3", tier);
		return kind().title + " upgraded to " + MachineKind.TIER_NAMES[tier - 1] + "!";
	}

	public void cycleRecipe() {
		cycleRecipe(1);
	}

	public void cycleRecipe(int step) {
		cycleRecipe(step, null);
	}

	public void cycleRecipe(int step, Player player) {
		setRecipe(recipe + step, player);
	}

	/** Pick recipe {@code index}; parts the new recipe can't use go back to {@code player} (if there is one). */
	public void setRecipe(int index, Player player) {
		recipe = Math.floorMod(index, Recipes.recipes(kind()).size());
		progress = 0;
		if (player != null) {
			Recipes.AmmoRecipe r = Recipes.recipe(kind(), recipe);
			for (int slot = 0; slot < 2 && slot < kind().inputs; slot++) {
				ItemStack in = items.get(slot);
				if (!in.isEmpty() && !Recipes.fits(r, slot == 0 ? r.metal() : r.extra(), in)) {
					items.set(slot, ItemStack.EMPTY);
					if (!player.getInventory().add(in)) {
						player.drop(in, false, net.minecraft.util.Prediction.SERVER_ONLY);
					}
				}
			}
		}
		setChanged();
	}

	/** Vault: take whole rubies out of the bank into the player's inventory. */
	public void withdraw(ServerPlayer player, int rubies) {
		long amount = Math.min(rubies, Grid.rubies(owner));
		if (amount <= 0 || !Grid.withdraw(owner, amount)) {
			return;
		}
		ItemStack stack = new ItemStack(ModItems.RUBY, (int) amount);
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
		}
		level.playSound(null, worldPosition, SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 0.6F, 1.2F);
	}

	/** On Battle Day a Vault broken by explosives spills a quarter of its owner's bank. */
	public void raided(ServerLevel level) {
		long loot = Grid.rubies(owner) / 4;
		if (loot <= 0 || !Grid.withdraw(owner, loot)) {
			return;
		}
		Vec3 at = Vec3.atCenterOf(worldPosition);
		while (loot > 0) {
			int n = (int) Math.min(64, loot);
			net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(level, at.x, at.y, at.z,
					new ItemStack(ModItems.RUBY, n));
			drop.setDeltaMovement((level.getRandom().nextDouble() - 0.5) * 0.4, 0.4, (level.getRandom().nextDouble() - 0.5) * 0.4);
			level.addFreshEntity(drop);
			loot -= n;
		}
		level.playSound(null, worldPosition, SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 3.0F, 0.5F);
	}

	// ----- Menu data -----

	/** Large numbers are sent as two 15-bit halves (menu data is sent as shorts). */
	private final ContainerData data = new ContainerData() {
		@Override
		public int get(int index) {
			return switch (index) {
				case 0 -> tier;
				case 1 -> progress;
				case 2 -> kind() == MachineKind.CONSTRUCTOR ? constructorTotal() : Math.max(1, kind().cycle(tier));
				case 3 -> burn;
				case 4 -> Math.max(1, burnMax);
				case 5 -> lo(Grid.charge(power()));
				case 6 -> hi(Grid.charge(power()));
				case 7 -> lo(Grid.capacity(power()));
				case 8 -> hi(Grid.capacity(power()));
				case 9 -> lo(Grid.producedPerSecond(power()));
				case 10 -> lo(Grid.consumedPerSecond(power()));
				case 11 -> recipe;
				case 12 -> lo(Grid.rubies(owner));
				case 13 -> hi(Grid.rubies(owner));
				case 14 -> status;
				case 15 -> kind() == MachineKind.CONSTRUCTOR ? missing : lo(aux / 1000);
				case 16 -> hi(Grid.producedPerSecond(power()));
				case 17 -> hi(Grid.consumedPerSecond(power()));
				// The bank's part-way ruby (0-999), for the Trade Depot's "next ruby" bar.
				case 18 -> (int) Math.floorMod(Grid.milliRubies(owner), 1000L);
				// 31-34: a drill's finds so far (loads, rubies).
				case 31 -> lo(loads);
				case 32 -> hi(loads);
				case 33 -> lo(rubiesFound);
				case 34 -> hi(rubiesFound);
				// 35-40: a drill's focus, stone switch, and the two richest minerals round it (item id + 1, strength in %).
				case 35 -> focus;
				case 36 -> leaveStone ? 1 : 0;
				case 37, 38 -> {
					if (kind() != MachineKind.RUBY_DRILL) {
						yield 0;
					}
					var rich = site().rich();
					int k = index - 37;
					yield k < rich.size() ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(rich.get(k)) + 1 : 0;
				}
				case 39 -> kind() == MachineKind.RUBY_DRILL ? Math.round(1000 * Recipes.rubyChance(tier, worldPosition.getY() < 0, focus, site())) : 0;
				// 40-41: what the Refinery is making (item id + 1, how many).
				case 40 -> kind() == MachineKind.ORE_REFINERY && !refining.isEmpty() ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(refining.getItem()) + 1 : 0;
				case 41 -> kind() == MachineKind.ORE_REFINERY ? refining.getCount() : 0;
				// 42: what it takes from a silo touching it (item id + 1); 43: how many silos a drill fills.
				case 42 -> {
					ItemStack from = level instanceof ServerLevel server ? takingFromSilo(server) : ItemStack.EMPTY;
					yield from.isEmpty() ? 0 : net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(from.getItem()) + 1;
				}
				case 43 -> level instanceof ServerLevel server ? silosFilled(server) : 0;
				// 44: a Constructor's mode (0: its Blueprint can't repair, 1: builds in front, 2: repairs in place).
				case 44 -> kind() != MachineKind.CONSTRUCTOR || !items.get(0).has(dev.rubymod.registry.ModComponents.BLUEPRINT_AT) ? 0 : repair ? 2 : 1;
				default -> {
					// 19-30: the Trade Depot's last four sales, newest first: item id + 1, how many, what it paid (milli-rubies, capped).
					int k = index - 19;
					if (k < 0 || k >= 12) {
						yield 0;
					}
					int[] sale = SALES[k / 3];
					yield sale == null ? 0 : Math.min(32767, sale[k % 3]);
				}
			};
		}

		@Override
		public void set(int index, int value) {
		}

		@Override
		public int getCount() {
			return MachineMenu.DATA_SLOTS;
		}
	};

	private int constructorTotal() {
		Blueprints.Data data = items.get(0).get(dev.rubymod.registry.ModComponents.BLUEPRINT);
		return data == null ? 1 : Math.max(1, data.cells().size());
	}

	private static int lo(long v) {
		return (int) (Math.min(v, (1L << 30) - 1) & 0x7FFF);
	}

	private static int hi(long v) {
		return (int) ((Math.min(v, (1L << 30) - 1) >> 15) & 0x7FFF);
	}

	@Override
	public MachineMenu.Opening getScreenOpeningData(ServerPlayer player) {
		return new MachineMenu.Opening(worldPosition, kind().ordinal());
	}

	@Override
	public Component getDisplayName() {
		return Component.literal(kind().title);
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
		return new MachineMenu(id, inventory, this, data, kind(), worldPosition);
	}

	/** Gone from the world: off the base list (the block's own code drops the contents). */
	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (level instanceof ServerLevel server) {
			BaseMachines.removed(server, pos);
		}
	}

	// ----- Saving -----

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		items.clear();
		ContainerHelper.loadAllItems(input, items);
		owner = input.getString("owner").map(UUID::fromString).orElse(NOBODY);
		tier = Math.max(1, Math.min(MachineKind.MAX_TIER, input.getIntOr("tier", 1)));
		progress = input.getIntOr("progress", 0);
		burn = input.getIntOr("burn", 0);
		burnMax = input.getIntOr("burn_max", 0);
		recipe = input.getIntOr("recipe", 0);
		lastTick = input.getLongOr("last_tick", -1L);
		lastReal = input.getLongOr("last_real", -1L);
		aux = input.getIntOr("aux", 0);
		loads = input.getLongOr("loads", 0L);
		rubiesFound = input.getLongOr("rubies_found", 0L);
		focus = input.getIntOr("focus", 0);
		leaveStone = input.getBooleanOr("leave_stone", false);
		repair = input.getBooleanOr("repair", false);
		fedBy.clear();
		for (String s : input.getString("fed_by").orElse("").split(",")) {
			if (!s.isEmpty()) {
				try {
					fedBy.add(Long.parseLong(s));
				} catch (NumberFormatException e) {
					// skip it
				}
			}
		}
		// A Refinery saved before it had a sand slot: its outputs move up one.
		if (input.getIntOr("layout", 1) < 2 && kind() == MachineKind.ORE_REFINERY && items.size() == 5) {
			for (int i = 4; i >= 2; i--) {
				items.set(i, items.get(i - 1));
			}
			items.set(1, ItemStack.EMPTY);
		}
		Blueprints.load(this, input);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		ContainerHelper.saveAllItems(output, items);
		output.putString("owner", owner.toString());
		output.putInt("tier", tier);
		output.putInt("progress", progress);
		output.putInt("burn", burn);
		output.putInt("burn_max", burnMax);
		output.putInt("recipe", recipe);
		output.putLong("last_tick", lastTick);
		output.putLong("last_real", lastReal);
		output.putInt("aux", aux);
		output.putLong("loads", loads);
		output.putLong("rubies_found", rubiesFound);
		output.putInt("focus", focus);
		output.putBoolean("leave_stone", leaveStone);
		output.putBoolean("repair", repair);
		if (!fedBy.isEmpty()) {
			output.putString("fed_by", fedBy.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
		}
		output.putInt("layout", LAYOUT);
		Blueprints.save(this, output);
	}

	// ----- Container (hoppers) -----

	@Override
	public int[] getSlotsForFace(Direction side) {
		int[] slots = new int[items.size()];
		for (int i = 0; i < slots.length; i++) {
			slots[i] = i;
		}
		return slots;
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		MachineKind kind = kind();
		if (slot >= kind.inputs) {
			return false;
		}
		return switch (kind) {
			case COMBUSTION_GENERATOR -> Recipes.burnTicks(stack) > 0;
			// Slot 0: ore, anything to cook, or coal for gunpowder. Slot 1: sand for gunpowder (sand goes on top to
			// become glass only while no sand is waiting below).
			case ORE_REFINERY -> slot == 1 ? Recipes.isSand(stack)
					: Recipes.isSand(stack) ? items.get(1).isEmpty()
					: Recipes.isGunpowderFuel(stack) || !Recipes.refine(level, stack, tier).isEmpty();
			case AMMO_FACTORY, ASSEMBLER -> {
				Recipes.AmmoRecipe r = Recipes.recipe(kind, recipe);
				yield Recipes.fits(r, slot == 0 ? r.metal() : r.extra(), stack);
			}
			case TRADE_DEPOT -> Recipes.price(stack) > 0;
			case VAULT -> stack.is(ModItems.RUBY);
			case CONSTRUCTOR -> stack.has(dev.rubymod.registry.ModComponents.BLUEPRINT);
			default -> true;
		};
	}

	@Override
	public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
		return canPlaceItem(slot, stack);
	}

	@Override
	public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
		return slot >= kind().inputs || kind() == MachineKind.COMBUSTION_GENERATOR && stack.is(Items.BUCKET);
	}

	@Override
	public int getContainerSize() {
		return items.size();
	}

	@Override
	public boolean isEmpty() {
		return items.stream().allMatch(ItemStack::isEmpty);
	}

	@Override
	public ItemStack getItem(int slot) {
		return items.get(slot);
	}

	@Override
	public ItemStack removeItem(int slot, int count) {
		ItemStack r = ContainerHelper.removeItem(items, slot, count);
		setChanged();
		return r;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return ContainerHelper.takeItem(items, slot);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		items.set(slot, stack);
		setChanged();
	}

	@Override
	public boolean stillValid(Player player) {
		return Container.stillValidBlockEntity(this, player);
	}

	@Override
	public void clearContent() {
		items.clear();
	}

	public NonNullList<ItemStack> items() {
		return items;
	}
}
