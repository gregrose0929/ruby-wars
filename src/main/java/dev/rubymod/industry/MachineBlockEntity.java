package dev.rubymod.industry;

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
	private int status;
	private long lastTick = -1;
	private int aux;
	/** Constructor: raw id + 1 of the block item it's waiting for (0 = none). */
	private int missing;
	/** This machine's own tick counter (schedules don't depend on the world clock). */
	private long ticks;

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

	public int tier() {
		return tier;
	}

	public void setTier(int tier) {
		this.tier = Math.max(1, Math.min(MachineKind.MAX_TIER, tier));
		setChanged();
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
		if (lastTick >= 0 && now - lastTick > 200) {
			catchUp(level, now - lastTick);
		}
		lastTick = now;
		switch (kind) {
			case COMBUSTION_GENERATOR -> tickGenerator(level);
			case SOLAR_PANEL -> tickSolar(level);
			case BATTERY -> {
				Grid.addBattery(owner, kind.batteryCapacity(tier));
				status = WORKING;
			}
			case RUBY_DRILL -> tickDrill(level);
			case ORE_REFINERY -> tickRefinery(level);
			case AMMO_FACTORY -> tickAmmo(level);
			case TRADE_DEPOT -> tickDepot(level);
			case VAULT -> tickVault(level);
			case SHIELD_GENERATOR -> tickShield(level);
			case POINT_DEFENSE -> tickPointDefense(level);
			case CONSTRUCTOR -> Blueprints.tickConstructor(level, this);
			case TESLA_COIL -> tickTesla(level);
		}
		if (kind.outputs > 0 && ticks % 10 == 0) {
			pushOutputs(level);
		}
	}

	/** Back after being unloaded: a drill digs what it would have (at half speed, up to a day, until full). */
	private void catchUp(ServerLevel level, long elapsed) {
		if (kind() != MachineKind.RUBY_DRILL) {
			return;
		}
		long cycles = (long) (Math.min(elapsed, 24000) * OFFLINE_RATE / kind().cycle(tier));
		int made = 0;
		for (long i = 0; i < cycles; i++) {
			if (!insertOutput(Recipes.drill(level.getRandom(), tier, worldPosition.getY() < 0))) {
				break;
			}
			made++;
		}
		if (made > 0 && level.getServer().getPlayerList().getPlayer(owner) instanceof ServerPlayer player) {
			player.sendSystemMessage(Component.literal("Your Ruby Drill dug up " + made + " loads while you were away."));
		}
	}

	private void tickGenerator(ServerLevel level) {
		MachineKind kind = kind();
		if (burn > 0) {
			burn--;
			Grid.produce(owner, kind.output(tier));
			status = WORKING;
			if (level.getGameTime() % 8 == 0) {
				level.sendParticles(ParticleTypes.SMOKE, worldPosition.getX() + 0.5, worldPosition.getY() + 1.05, worldPosition.getZ() + 0.5,
						1, 0.1, 0, 0.1, 0.01);
			}
			return;
		}
		ItemStack fuel = items.get(0);
		int ticks = Recipes.burnTicks(fuel);
		if (ticks > 0 && Grid.charge(owner) < Grid.capacity(owner)) {
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
			Grid.produce(owner, kind().output(tier));
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
		if (!Grid.consume(owner, kind().draw(tier))) {
			status = NO_POWER;
			return;
		}
		status = WORKING;
		if (++progress >= kind().cycle(tier)) {
			progress = 0;
			insertOutput(Recipes.drill(level.getRandom(), tier, worldPosition.getY() < 0));
			level.playSound(null, worldPosition, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 0.6F, 0.8F);
		}
		if (level.getGameTime() % 4 == 0) {
			level.sendParticles(ParticleTypes.CRIT, worldPosition.getX() + 0.5, worldPosition.getY() + 0.1, worldPosition.getZ() + 0.5,
					2, 0.3, 0.05, 0.3, 0.05);
		}
	}

	private void tickRefinery(ServerLevel level) {
		ItemStack input = items.get(0);
		ItemStack result = Recipes.refine(input, tier);
		if (result.isEmpty()) {
			progress = 0;
			status = NO_INPUT;
			return;
		}
		if (!canFit(result)) {
			status = OUTPUT_FULL;
			return;
		}
		if (!Grid.consume(owner, kind().draw(tier))) {
			status = NO_POWER;
			return;
		}
		status = WORKING;
		if (++progress >= kind().cycle(tier)) {
			progress = 0;
			input.shrink(1);
			insertOutput(result);
			level.playSound(null, worldPosition, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.4F, 1.2F);
		}
	}

	private void tickAmmo(ServerLevel level) {
		Recipes.AmmoRecipe r = Recipes.AMMO.get(Math.floorMod(recipe, Recipes.AMMO.size()));
		ItemStack metal = items.get(0);
		ItemStack extra = items.get(1);
		if (!metal.is(r.metal()) || metal.getCount() < r.metalCount() || !extra.is(r.extra()) || extra.getCount() < r.extraCount()) {
			progress = 0;
			status = NO_INPUT;
			return;
		}
		ItemStack result = new ItemStack(r.result(), r.count());
		if (!canFit(result)) {
			status = OUTPUT_FULL;
			return;
		}
		if (!Grid.consume(owner, kind().draw(tier))) {
			status = NO_POWER;
			return;
		}
		status = WORKING;
		if (++progress >= kind().cycle(tier)) {
			progress = 0;
			metal.shrink(r.metalCount());
			extra.shrink(r.extraCount());
			insertOutput(result);
			level.playSound(null, worldPosition, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.3F, 1.6F);
		}
	}

	private void tickDepot(ServerLevel level) {
		if (ticks % kind().cycle(tier) != 0) {
			return;
		}
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
			stack.shrink(sell);
			budget -= sell;
		}
		status = earned > 0 ? WORKING : IDLE;
		if (earned > 0) {
			long before = Grid.rubies(owner);
			Grid.deposit(owner, earned);
			aux = (int) Math.min(Integer.MAX_VALUE, aux + earned);
			if (Grid.rubies(owner) > before) {
				level.playSound(null, worldPosition, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.7F, 1.4F);
			}
			setChanged();
		}
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
					player.sendSystemMessage(Component.literal("Vault interest: +" + interest + " rubies (bank: " + Grid.rubies(owner) + ")")
							.withStyle(net.minecraft.ChatFormatting.GOLD));
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
		SeasonData.Claim claim = SeasonData.get().claimOf(owner);
		return claim != null && claim.canUse(shooter.getUUID());
	}

	private void tickShield(ServerLevel level) {
		MachineKind kind = kind();
		if (!Grid.consume(owner, kind.draw(tier))) {
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
				if (!Grid.consume(owner, cost)) {
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
		if (!Grid.consume(owner, 200)) {
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
			boolean hostile = e instanceof net.minecraft.world.entity.monster.Enemy
					|| e instanceof Player p && claim != null && Rules.phase().pvp() && Rules.isEnemyOf(claim, p);
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
		if (!Grid.consume(owner, 400)) {
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
		target.hurtServer(level, dev.rubymod.registry.ModDamage.source(level, dev.rubymod.registry.ModDamage.TURRET, null, ownerPlayer(level)), 3.0F + tier);
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
		for (int pass = 0; pass < 2; pass++) {
			for (Direction dir : Direction.values()) {
				BlockPos side = worldPosition.relative(dir);
				if (pass == 0 && dir.getAxis().isHorizontal() && ConveyorBlock.leadsAway(level.getBlockState(side), dir)) {
					dropOnBelt(level, side);
					continue;
				}
				BlockEntity neighbor = level.getBlockEntity(side);
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

	/** A few items at a time onto a conveyor belt. */
	private void dropOnBelt(ServerLevel level, BlockPos belt) {
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
		return kind().title + " upgraded to " + MachineKind.TIER_NAMES[tier - 1] + "!";
	}

	public void cycleRecipe() {
		recipe = Math.floorMod(recipe + 1, Recipes.AMMO.size());
		progress = 0;
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
				case 5 -> lo(Grid.charge(owner));
				case 6 -> hi(Grid.charge(owner));
				case 7 -> lo(Grid.capacity(owner));
				case 8 -> hi(Grid.capacity(owner));
				case 9 -> lo(Grid.producedPerSecond(owner));
				case 10 -> lo(Grid.consumedPerSecond(owner));
				case 11 -> recipe;
				case 12 -> lo(Grid.rubies(owner));
				case 13 -> hi(Grid.rubies(owner));
				case 14 -> status;
				case 15 -> kind() == MachineKind.CONSTRUCTOR ? missing : lo(aux / 1000);
				case 16 -> hi(Grid.producedPerSecond(owner));
				case 17 -> hi(Grid.consumedPerSecond(owner));
				default -> 0;
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
		aux = input.getIntOr("aux", 0);
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
		output.putInt("aux", aux);
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
			case ORE_REFINERY -> !Recipes.refine(stack, tier).isEmpty();
			case AMMO_FACTORY -> {
				Recipes.AmmoRecipe r = Recipes.AMMO.get(Math.floorMod(recipe, Recipes.AMMO.size()));
				yield slot == 0 ? stack.is(r.metal()) : stack.is(r.extra());
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
