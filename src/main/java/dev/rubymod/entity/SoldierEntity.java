package dev.rubymod.entity;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import dev.rubymod.game.Armor;
import dev.rubymod.game.GameConfig;
import dev.rubymod.game.Raids;
import dev.rubymod.game.Rules;
import dev.rubymod.game.SeasonData;
import dev.rubymod.game.Squads;
import dev.rubymod.registry.ModDamage;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A soldier. As a hired Guard (Armory: Guard Contract) it stands post at your
 * base or follows you, and fights anyone who isn't on your side: monsters any
 * time, enemy players and their guards when PvP is on. As a Raider (practice
 * raids in the build phase) it marches on a base and shoots its defenders.
 *
 * Roles: Rifleman (rifle bursts) and AT Gunner (rockets at vehicles, aircraft and
 * clusters of enemies). Owners right-click a guard to switch between holding a
 * post and following; sneak + right-click packs it back into a contract.
 */
public class SoldierEntity extends PathfinderMob {
	public static final int RIFLEMAN = 0;
	public static final int AT_GUNNER = 1;
	private static final EntityDataAccessor<Integer> ROLE = SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> RAIDER = SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Boolean> FOLLOWING = SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BOOLEAN);

	private UUID owner = new UUID(0, 0);
	private BlockPos post = BlockPos.ZERO;
	private BlockPos objective;
	private int cooldown;
	private int burst;
	private int raidId = -1;

	public SoldierEntity(EntityType<? extends SoldierEntity> type, Level level) {
		super(type, level);
		setPersistenceRequired();
	}

	public static AttributeSupplier.Builder attributes() {
		return createMobAttributes().add(Attributes.MAX_HEALTH, 30.0).add(Attributes.MOVEMENT_SPEED, 0.28).add(Attributes.FOLLOW_RANGE, 40.0)
				.add(Attributes.ARMOR, 6.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(ROLE, RIFLEMAN);
		builder.define(RAIDER, false);
		builder.define(FOLLOWING, false);
	}

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(0, new FloatGoal(this));
		goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.7));
		goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12.0F));
		goalSelector.addGoal(8, new RandomLookAroundGoal(this));
	}

	// ----- Setup -----

	public static SoldierEntity guard(ServerLevel level, ServerPlayer owner, BlockPos at, int role) {
		SoldierEntity s = dev.rubymod.registry.ModEntities.SOLDIER.create(level, EntitySpawnReason.MOB_SUMMONED);
		if (s == null) {
			return null;
		}
		s.owner = owner.getUUID();
		s.entityData.set(ROLE, role);
		s.post = at;
		s.setHomeTo(at, 10);
		s.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, owner.getYRot(), 0);
		s.equip();
		s.setCustomName(Component.literal(owner.getName().getString() + "'s " + (role == AT_GUNNER ? "AT Gunner" : "Rifleman"))
				.withStyle(ChatFormatting.GREEN));
		return s;
	}

	public static SoldierEntity raider(ServerLevel level, BlockPos at, BlockPos objective, int role, int raidId) {
		SoldierEntity s = dev.rubymod.registry.ModEntities.SOLDIER.create(level, EntitySpawnReason.EVENT);
		if (s == null) {
			return null;
		}
		s.entityData.set(RAIDER, true);
		s.entityData.set(ROLE, role);
		s.objective = objective;
		s.raidId = raidId;
		s.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
		s.equip();
		s.setCustomName(Component.literal(role == AT_GUNNER ? "Raider AT Gunner" : "Raider").withStyle(ChatFormatting.RED));
		return s;
	}

	private void equip() {
		ItemStack weapon = new ItemStack(role() == AT_GUNNER ? ModItems.ROCKET_LAUNCHER : ModItems.ARR_RIFLE);
		setItemSlot(EquipmentSlot.MAINHAND, weapon);
		setDropChance(EquipmentSlot.MAINHAND, 0.0F);
		if (role() == AT_GUNNER) {
			getAttribute(Attributes.MAX_HEALTH).setBaseValue(36.0);
			setHealth(36.0F);
		}
	}

	public int role() {
		return entityData.get(ROLE);
	}

	public boolean isRaider() {
		return entityData.get(RAIDER);
	}

	public boolean following() {
		return entityData.get(FOLLOWING);
	}

	public UUID owner() {
		return owner;
	}

	public int raidId() {
		return raidId;
	}

	// ----- Who's an enemy -----

	/** Should this soldier shoot at that entity? */
	public boolean isHostileTo(Entity e) {
		if (!(e instanceof LivingEntity living) || !living.isAlive() || e == this) {
			return false;
		}
		if (e instanceof Player p && (p.isCreative() || p.isSpectator() || Rules.isEliminated(p.getUUID()))) {
			return false;
		}
		if (isRaider()) {
			// Raiders fight players near their objective and anyone's guards.
			return e instanceof Player || e instanceof SoldierEntity s && !s.isRaider();
		}
		if (e instanceof SoldierEntity s) {
			return s.isRaider() || Rules.phase().pvp() && !Squads.same(owner, s.owner) && !Rules.isFriendly(owner, s.owner);
		}
		if (e instanceof Player p) {
			return Rules.phase().pvp() && !Squads.same(owner, p.getUUID()) && !Rules.isFriendly(owner, p.getUUID());
		}
		if (Armor.isArmored(e) && e.getFirstPassenger() instanceof Player driver) {
			return isHostileTo(driver);
		}
		return e instanceof Enemy;
	}

	/** Raiders are enemies of every base; guards are enemies of bases that aren't their owner's side. */
	public boolean isEnemyOfClaim(SeasonData.Claim claim) {
		if (isRaider()) {
			return true;
		}
		return Rules.phase().pvp() && !claim.canUse(owner);
	}

	// ----- Behaviour -----

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel level)) {
			return;
		}
		if (cooldown > 0) {
			cooldown--;
		}
		if (tickCount % 10 == 0) {
			pickTarget(level);
		}
		LivingEntity target = getTarget();
		if (target != null && (!target.isAlive() || target.distanceTo(this) > 40 || !isHostileTo(target))) {
			setTarget(null);
			target = null;
		}
		if (target != null) {
			getLookControl().setLookAt(target, 30.0F, 30.0F);
			double dist = distanceTo(target);
			if (dist > (role() == AT_GUNNER ? 30 : 20)) {
				getNavigation().moveTo(target, 1.0);
			} else {
				getNavigation().stop();
				fireAt(level, target);
			}
			return;
		}
		if (isRaider()) {
			if (objective != null && tickCount % 40 == 0) {
				getNavigation().moveTo(objective.getX() + 0.5, objective.getY(), objective.getZ() + 0.5, 1.0);
			}
			if (objective != null && blockPosition().closerThan(objective, 4.5) && tickCount % 20 == 0) {
				Raids.loot(level, this);
			}
			return;
		}
		if (following()) {
			ServerPlayer leader = level.getServer().getPlayerList().getPlayer(owner);
			if (leader != null && leader.level() == level) {
				double d = distanceTo(leader);
				if (d > 24) {
					snapTo(leader.getX(), leader.getY(), leader.getZ(), getYRot(), 0);
				} else if (d > 5 && tickCount % 10 == 0) {
					getNavigation().moveTo(leader, 1.1);
				}
			}
		}
	}

	private void pickTarget(ServerLevel level) {
		if (getTarget() != null && hasLineOfSight(getTarget())) {
			return;
		}
		double range = role() == AT_GUNNER ? 40 : 28;
		List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(range), this::isHostileTo);
		near.stream().filter(this::hasLineOfSight).min(Comparator.comparingDouble(e -> {
			// AT gunners prefer vehicles and aircraft.
			double d = e.distanceToSqr(this);
			return role() == AT_GUNNER && Armor.isArmored(e) ? d * 0.1 : d;
		})).ifPresent(this::setTarget);
	}

	private void fireAt(ServerLevel level, LivingEntity target) {
		if (cooldown > 0) {
			return;
		}
		Vec3 from = getEyePosition().add(getLookAngle().scale(0.6));
		Vec3 aim = target.position().add(0, target.getBbHeight() * 0.6, 0);
		if (role() == AT_GUNNER) {
			if (!Armor.isArmored(target) && distanceTo(target) < 6) {
				return; // too close for a rocket
			}
			Vec3 dir = aim.subtract(from).normalize();
			level.addFreshEntity(RocketEntity.shell(level, this, from.add(dir.scale(0.8)), dir.scale(2.0), 2.5F));
			level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE, 2.0F, 0.6F);
			cooldown = 100;
			return;
		}
		// Rifle: 3-round bursts with some spread.
		var random = getRandom();
		Vec3 spread = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).scale(0.035 * aim.distanceTo(from) / 10.0);
		Vec3 end = aim.add(spread);
		HitResult blocked = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		Vec3 stop = blocked.getType() == HitResult.Type.MISS ? end : blocked.getLocation();
		if (blocked.getType() == HitResult.Type.MISS && target.getBoundingBox().inflate(0.3).clip(from, end).isPresent()) {
			Entity attackerOwner = isRaider() ? null : level.getServer().getPlayerList().getPlayer(owner);
			DamageSource source = ModDamage.source(level, ModDamage.BULLET, this, attackerOwner != null ? attackerOwner : this);
			if (Armor.isArmored(target)) {
				Armor.hit(level, target, this, attackerOwner != null ? attackerOwner : this, 1.0F);
			} else {
				target.hurtServer(level, source, GameConfig.get().guardDamage);
			}
		}
		Vec3 step = stop.subtract(from);
		double len = step.length();
		step = step.normalize();
		for (double d = 1.0; d < len; d += 2.0) {
			Vec3 p = from.add(step.scale(d));
			level.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 1, 0, 0, 0, 0);
		}
		level.sendParticles(ParticleTypes.SMALL_FLAME, from.x, from.y, from.z, 2, 0.05, 0.05, 0.05, 0.01);
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.HOSTILE, 1.4F, 1.6F + random.nextFloat() * 0.2F);
		burst++;
		if (burst >= 3) {
			burst = 0;
			cooldown = 26 + random.nextInt(10);
		} else {
			cooldown = 3;
		}
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (isRaider() || !player.getUUID().equals(owner) || !player.getItemInHand(hand).isEmpty()) {
			return InteractionResult.PASS;
		}
		if (player instanceof ServerPlayer sp) {
			if (sp.isShiftKeyDown()) {
				if (SeasonData.get().phase.isBattle()) {
					sp.sendOverlayMessage(Component.literal("Guards can't be packed up during Battle Day."));
					return InteractionResult.FAIL;
				}
				ItemStack contract = new ItemStack(role() == AT_GUNNER ? (net.minecraft.world.level.ItemLike) ModItems.AT_GUARD_CONTRACT : ModItems.GUARD_CONTRACT);
				if (!sp.getInventory().add(contract)) {
					sp.drop(contract, false, net.minecraft.util.Prediction.SERVER_ONLY);
				}
				discard();
				return InteractionResult.SUCCESS;
			}
			boolean follow = !following();
			entityData.set(FOLLOWING, follow);
			if (follow) {
				clearHome();
			} else {
				post = blockPosition();
				setHomeTo(post, 10);
			}
			sp.sendOverlayMessage(Component.literal(follow ? "Guard: following you" : "Guard: holding this post").withStyle(ChatFormatting.GREEN));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		// Squadmates and the owner can't hurt their own guards; raiders don't hurt each other.
		Entity attacker = source.getEntity();
		if (attacker instanceof SoldierEntity s && s.isRaider() == isRaider() && (isRaider() || Squads.same(s.owner, owner))) {
			return false;
		}
		if (!isRaider() && attacker instanceof Player p && (p.getUUID().equals(owner) || Squads.same(owner, p.getUUID())) && !p.isCreative()) {
			return false;
		}
		return super.hurtServer(level, source, amount);
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (level() instanceof ServerLevel level && isRaider()) {
			Raids.raiderKilled(level, this, source.getEntity());
		}
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putString("owner", owner.toString());
		output.putLong("post", post.asLong());
		output.putInt("role", role());
		output.putBoolean("raider", isRaider());
		output.putBoolean("following", following());
		output.putInt("raid", raidId);
		if (objective != null) {
			output.putLong("objective", objective.asLong());
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		owner = input.getString("owner").map(UUID::fromString).orElse(new UUID(0, 0));
		post = BlockPos.of(input.getLongOr("post", 0L));
		entityData.set(ROLE, input.getIntOr("role", RIFLEMAN));
		entityData.set(RAIDER, input.getBooleanOr("raider", false));
		entityData.set(FOLLOWING, input.getBooleanOr("following", false));
		raidId = input.getIntOr("raid", -1);
		objective = input.getLong("objective").map(BlockPos::of).orElse(null);
		if (!following() && !isRaider()) {
			setHomeTo(post, 10);
		}
		if (isRaider()) {
			// Raids don't survive a restart.
			discard();
		}
	}
}
