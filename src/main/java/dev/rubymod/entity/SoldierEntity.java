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
	/** Duel practice bot: the player it hunts down (null for guards and raiders). */
	private UUID prey;
	/** Horde shield soldier: shots from the front mostly bounce off the shield. */
	private boolean shielded;
	/** Party games: which team this bot fights for (-1: not a party bot), and the weapon it uses (null: its role's). */
	private int partyTeam = -1;
	private net.minecraft.world.item.Item botWeapon;
	/** Practice and party bots fight smarter: they strafe and duck into cover to heal. */
	private boolean smart;
	/** A gentler bot: misses more, hits softer, pauses longer between bursts (Capture the Flag). */
	private boolean easy;
	/** How a softened gunman shoots (Ruby Rift's early floors): see {@link #soften}. */
	private float spreadScale = 1.0F;
	private float damageScale = 1.0F;
	private int burstSize = 3;
	private int restExtra;
	/** Ticks of aiming (with a red laser showing where) before each burst; 0: none. */
	private int aimDelay;
	private int aimTicks;
	private LivingEntity aimTarget;
	private int strafeIn;
	private int coverTicks;
	private int coverCooldown;

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

	/** The duel practice bot: a rifleman that hunts {@code prey} across the arena. */
	public static SoldierEntity duelBot(ServerLevel level, BlockPos at, ServerPlayer prey) {
		SoldierEntity s = dev.rubymod.registry.ModEntities.SOLDIER.create(level, EntitySpawnReason.EVENT);
		if (s == null) {
			return null;
		}
		s.entityData.set(RAIDER, true);
		s.entityData.set(ROLE, RIFLEMAN);
		s.prey = prey == null ? null : prey.getUUID();
		s.smart = true;
		s.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180, 0);
		s.equip();
		s.setCustomName(Component.literal("Practice Bot").withStyle(ChatFormatting.RED));
		return s;
	}

	/** A Co-op Horde enemy: a raider that heads for the nearest player (Horde moves its objective) and loots nobody. */
	public static SoldierEntity horde(ServerLevel level, BlockPos objective, int role) {
		SoldierEntity s = raider(level, objective, objective, role, dev.rubymod.game.Horde.SOLDIER_RAID_ID);
		if (s != null) {
			s.setCustomName(Component.literal(role == AT_GUNNER ? "Enemy AT Gunner" : "Enemy Soldier").withStyle(ChatFormatting.RED));
		}
		return s;
	}

	/** Party games: switch the practice bot to hunting someone else. */
	public void setPrey(UUID prey) {
		this.prey = prey;
		if (getTarget() != null && !getTarget().getUUID().equals(prey)) {
			setTarget(null);
		}
	}

	/** A party-game bot on a team (Capture the Flag): fights the other team's players and bots, goes where the game sends it. */
	public static SoldierEntity partyBot(ServerLevel level, BlockPos at, int team) {
		SoldierEntity s = raider(level, at, at, RIFLEMAN, -3);
		if (s != null) {
			s.partyTeam = team;
			s.smart = true;
		}
		return s;
	}

	/** Give the bot a weapon to fight with (any gun, the rocket launcher or the ruby sword). */
	public void setWeapon(net.minecraft.world.item.Item weapon) {
		botWeapon = weapon;
		setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(weapon));
		setDropChance(EquipmentSlot.MAINHAND, 0.0F);
	}

	public void setSmart() {
		smart = true;
	}

	public void setEasy() {
		easy = true;
	}

	/**
	 * A gentler gunman: shots spread {@code spread} times as wide and hit {@code damage}
	 * times as hard, bursts of {@code burst}, {@code rest} more ticks between them, and
	 * {@code aim} ticks of aiming (a red laser warns the target) before each burst.
	 */
	public void soften(float spread, float damage, int burst, int rest, int aim) {
		spreadScale = spread;
		damageScale = damage;
		burstSize = burst;
		restExtra = rest;
		aimDelay = aim;
	}

	public int partyTeam() {
		return partyTeam;
	}

	public void setObjective(BlockPos objective) {
		this.objective = objective;
	}

	/** Carry a riot shield: hits from the front do a fifth of the damage, so flank them. */
	public void setShielded() {
		shielded = true;
		setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(net.minecraft.world.item.Items.SHIELD));
		setDropChance(EquipmentSlot.OFFHAND, 0.0F);
		setCustomName(Component.literal("Shield Soldier").withStyle(ChatFormatting.RED));
	}

	/** The practice bot and Horde soldiers fight players even in Peaceful (where vanilla stops mobs targeting players). */
	@Override
	public boolean canAttack(LivingEntity target) {
		if (prey != null && target.getUUID().equals(prey) || (raidId == dev.rubymod.game.Horde.SOLDIER_RAID_ID || partyTeam >= 0) && target instanceof Player) {
			return target.canBeSeenAsEnemy();
		}
		return super.canAttack(target);
	}

	private void equip() {
		ItemStack weapon = new ItemStack(role() == AT_GUNNER ? ModItems.ROCKET_LAUNCHER : ModItems.ARR_RIFLE);
		setItemSlot(EquipmentSlot.MAINHAND, weapon);
		setDropChance(EquipmentSlot.MAINHAND, 0.0F);
		float health = isRaider() ? 30.0F : GameConfig.get().guardHealth;
		if (role() == AT_GUNNER) {
			health += 6.0F;
		}
		getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
		setHealth(health);
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
		if (e instanceof Player p && (p.isCreative() || p.isSpectator() || Rules.isEliminated(p.getUUID()) && dev.rubymod.game.Hub.inRubyWarsWorld(p))) {
			return false;
		}
		if (partyTeam >= 0) {
			// Party bots fight the other team (players and bots).
			if (e instanceof SoldierEntity s) {
				return s.partyTeam >= 0 && s.partyTeam != partyTeam;
			}
			if (e instanceof Player p) {
				int team = dev.rubymod.game.party.PartyGames.teamOf(p);
				return team >= 0 && team != partyTeam;
			}
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
		// Guards patch themselves up between fights.
		if (!isRaider() && getTarget() == null && tickCount % 60 == 0 && getHealth() < getMaxHealth()) {
			heal(1.0F);
		}
		LivingEntity target = getTarget();
		if (target != null && (!target.isAlive() || target.distanceTo(this) > Math.max(40, weaponRange() + 10) || !isHostileTo(target))) {
			setTarget(null);
			target = null;
		}
		if (coverCooldown > 0) {
			coverCooldown--;
		}
		if (target != null) {
			getLookControl().setLookAt(target, 30.0F, 30.0F);
			double dist = distanceTo(target);
			if (smart && takeCover(level, target)) {
				return;
			}
			if (botWeapon == ModItems.RUBY_SWORD) {
				// Sword: charge in.
				getNavigation().moveTo(target, 1.25);
				fireAt(level, target);
			} else if (dist > weaponRange() || !hasLineOfSight(target)) {
				// Too far, or behind cover: close in / find an angle instead of shooting the wall.
				getNavigation().moveTo(target, 1.0);
			} else {
				if (smart) {
					strafe(target);
				} else {
					getNavigation().stop();
				}
				fireAt(level, target);
			}
			return;
		}
		coverTicks = 0;
		if (prey != null) {
			// Practice bot: go find the player.
			ServerPlayer hunted = level.getServer().getPlayerList().getPlayer(prey);
			if (hunted == null) {
				hunted = dev.rubymod.game.party.PartyGames.TEST_PLAYERS.get(prey);
			}
			if (hunted != null && hunted.level() == level && tickCount % 20 == 0) {
				if (hasLineOfSight(hunted) && distanceTo(hunted) < Math.max(40, weaponRange()) && isHostileTo(hunted)) {
					setTarget(hunted);
				} else {
					getNavigation().moveTo(hunted, 1.1);
				}
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

	// ----- Smarter fighting (practice and party bots) -----

	/** How far this soldier likes to shoot from. */
	private double weaponRange() {
		if (botWeapon == null) {
			return role() == AT_GUNNER ? 30 : 20;
		}
		if (botWeapon == ModItems.LONGSHOT_SNIPER) {
			return 60;
		} else if (botWeapon == ModItems.COMBAT_SHOTGUN) {
			return 10;
		} else if (botWeapon == ModItems.ROCKET_LAUNCHER) {
			return 30;
		} else if (botWeapon == ModItems.RUBY_SWORD) {
			return 2.5;
		}
		return 22;
	}

	/** Sidestep while shooting (harder to hit than standing still). */
	private void strafe(LivingEntity target) {
		if (--strafeIn > 0) {
			return;
		}
		strafeIn = 20 + getRandom().nextInt(20);
		Vec3 to = target.position().subtract(position()).multiply(1, 0, 1).normalize();
		Vec3 side = new Vec3(-to.z, 0, to.x).scale((getRandom().nextBoolean() ? 1 : -1) * (3 + getRandom().nextInt(3)));
		// Shotguns push in a little; snipers back off a little.
		double push = botWeapon == ModItems.COMBAT_SHOTGUN ? 2 : botWeapon == ModItems.LONGSHOT_SNIPER ? -2 : 0;
		Vec3 spot = position().add(side).add(to.scale(push));
		getNavigation().moveTo(spot.x, spot.y, spot.z, 0.9);
	}

	/** Hurt badly: run somewhere the target can't see, patch up, then come back. True while hiding. */
	private boolean takeCover(ServerLevel level, LivingEntity target) {
		if (coverTicks > 0) {
			coverTicks--;
			if (tickCount % 10 == 0 && getHealth() < getMaxHealth() * 0.8F) {
				heal(1.0F);
			}
			return coverTicks > 0 && getHealth() < getMaxHealth() * 0.8F;
		}
		if (coverCooldown > 0 || getHealth() >= getMaxHealth() * 0.4F) {
			return false;
		}
		coverCooldown = 300;
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (int i = 0; i < 16; i++) {
			BlockPos spot = blockPosition().offset(getRandom().nextInt(21) - 10, 0, getRandom().nextInt(21) - 10);
			if (!level.getBlockState(spot).isAir() || !level.getBlockState(spot.above()).isAir() || level.getBlockState(spot.below()).isAir()) {
				continue;
			}
			Vec3 eye = target.getEyePosition();
			Vec3 at = Vec3.atBottomCenterOf(spot).add(0, 1.5, 0);
			if (level.clip(new ClipContext(eye, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target)).getType() == HitResult.Type.MISS) {
				continue; // they'd still see us there
			}
			double d = spot.distSqr(blockPosition());
			if (d < bestDist) {
				bestDist = d;
				best = spot;
			}
		}
		if (best == null) {
			return false;
		}
		getNavigation().moveTo(best.getX() + 0.5, best.getY(), best.getZ() + 0.5, 1.3);
		coverTicks = 100;
		return true;
	}

	private void pickTarget(ServerLevel level) {
		if (getTarget() != null && hasLineOfSight(getTarget())) {
			return;
		}
		double range = Math.max(role() == AT_GUNNER ? 40 : 28, weaponRange());
		List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(range), this::isHostileTo);
		near.stream().filter(this::hasLineOfSight).min(Comparator.comparingDouble(e -> {
			// AT gunners prefer vehicles and aircraft.
			double d = e.distanceToSqr(this);
			return role() == AT_GUNNER && Armor.isArmored(e) ? d * 0.1 : d;
		})).ifPresent(this::setTarget);
	}

	/** How hard one of this soldier's shots hits. */
	private float shotDamage() {
		if (botWeapon instanceof dev.rubymod.gun.GunItem gun) {
			return gun.stats().damage();
		}
		// The practice bot hits as hard as your own AR-R rifle (raid soldiers are weaker on purpose).
		if (prey != null || partyTeam >= 0) {
			return ((dev.rubymod.gun.GunItem) ModItems.ARR_RIFLE).stats().damage();
		}
		return isRaider() ? GameConfig.get().raiderDamage : GameConfig.get().guardDamage;
	}

	private void fireAt(ServerLevel level, LivingEntity target) {
		if (cooldown > 0) {
			return;
		}
		Vec3 from = getEyePosition().add(getLookAngle().scale(0.6));
		Vec3 aim = target.position().add(0, target.getBbHeight() * 0.6, 0);
		if (level.clip(new ClipContext(from, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() != HitResult.Type.MISS) {
			// Body behind cover: aim for whatever sticks out (the head).
			aim = target.getEyePosition();
		}
		boolean rockets = botWeapon == ModItems.ROCKET_LAUNCHER || botWeapon == null && role() == AT_GUNNER;
		if (rockets) {
			if (!Armor.isArmored(target) && distanceTo(target) < 6) {
				return; // too close for a rocket
			}
			Vec3 dir = aim.subtract(from).normalize();
			level.addFreshEntity(RocketEntity.shell(level, this, from.add(dir.scale(0.8)), dir.scale(2.0), 2.5F));
			level.playSound(null, getX(), getY(), getZ(), dev.rubymod.registry.ModSounds.ROCKET_LAUNCH, SoundSource.HOSTILE, 1.2F, 1.05F);
			cooldown = botWeapon != null ? 70 : 100;
			return;
		}
		if (botWeapon == ModItems.RUBY_SWORD) {
			if (distanceTo(target) < 2.6) {
				target.setInvulnerableTime(0);
				target.hurtServer(level, damageSources().mobAttack(this), 8.0F);
				cooldown = 12;
			}
			return;
		}
		// Aiming first (softened gunmen): a red laser shows who's about to be shot.
		if (aimDelay > 0 && burst == 0) {
			if (aimTarget != target) {
				aimTarget = target;
				aimTicks = 0;
			}
			if (aimTicks++ < aimDelay) {
				if (aimTicks % 2 == 0) {
					laser(level, from, aim);
				}
				return;
			}
		}
		var random = getRandom();
		if (botWeapon == ModItems.COMBAT_SHOTGUN) {
			for (int i = 0; i < 6; i++) {
				shoot(level, target, from, aim, 0.12, shotDamage());
			}
			level.playSound(null, getX(), getY(), getZ(), dev.rubymod.registry.ModSounds.RIFLE_SHOT, SoundSource.HOSTILE, 1.2F, 0.7F);
			cooldown = 24;
			return;
		}
		if (botWeapon == ModItems.LONGSHOT_SNIPER || botWeapon == ModItems.SERVICE_PISTOL) {
			boolean sniper = botWeapon == ModItems.LONGSHOT_SNIPER;
			shoot(level, target, from, aim, sniper ? 0.006 : 0.03, shotDamage());
			level.playSound(null, getX(), getY(), getZ(), dev.rubymod.registry.ModSounds.RIFLE_SHOT, SoundSource.HOSTILE, sniper ? 1.6F : 0.9F,
					sniper ? 0.6F : 1.2F);
			cooldown = sniper ? 55 : 12;
			return;
		}
		// Rifle (and Ruby Blaster): 3-round bursts with some spread.
		shoot(level, target, from, aim, 0.035, shotDamage());
		level.playSound(null, getX(), getY(), getZ(), dev.rubymod.registry.ModSounds.RIFLE_SHOT, SoundSource.HOSTILE, 1.0F, 0.95F + random.nextFloat() * 0.1F);
		burst++;
		if (burst >= burstSize) {
			burst = 0;
			aimTicks = 0;
			cooldown = 26 + random.nextInt(10) + (easy ? 30 : 0) + restExtra;
		} else {
			cooldown = 3;
		}
	}

	/** A thin red line from the muzzle toward the target (stopped by walls): "you're being aimed at". */
	private void laser(ServerLevel level, Vec3 from, Vec3 to) {
		HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		Vec3 end = hit.getType() == HitResult.Type.MISS ? to : hit.getLocation();
		Vec3 step = end.subtract(from);
		// Stops short of the target, so it doesn't fill their screen.
		double len = step.length();
		step = step.scale(Math.max(0, len - 2.0) / Math.max(len, 0.01));
		int n = (int) Math.min(48, step.length() * 2);
		var dust = new net.minecraft.core.particles.DustParticleOptions(0xFF2020, 0.8F);
		for (int i = 1; i <= n; i++) {
			Vec3 p = from.add(step.scale(i / (double) n));
			level.sendParticles(dust, true, false, p.x, p.y, p.z, 1, 0, 0, 0, 0);
		}
	}

	/** One bullet (or pellet): spread grows with distance; blocked by walls; tracers drawn. */
	private void shoot(ServerLevel level, LivingEntity target, Vec3 from, Vec3 aim, double spreadPer10, float damage) {
		if (easy) {
			spreadPer10 *= 2.2;
			damage *= 0.5F;
		}
		spreadPer10 *= spreadScale;
		damage *= damageScale;
		var random = getRandom();
		Vec3 spread = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).scale(spreadPer10 * aim.distanceTo(from) / 10.0);
		Vec3 end = aim.add(spread);
		HitResult blocked = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		Vec3 stop = blocked.getType() == HitResult.Type.MISS ? end : blocked.getLocation();
		if (blocked.getType() == HitResult.Type.MISS && target.getBoundingBox().inflate(0.3).clip(from, end).isPresent()) {
			Entity attackerOwner = isRaider() ? null : level.getServer().getPlayerList().getPlayer(owner);
			DamageSource source = ModDamage.source(level, ModDamage.BULLET, this, attackerOwner != null ? attackerOwner : this);
			if (Armor.isArmored(target)) {
				Armor.hit(level, target, this, attackerOwner != null ? attackerOwner : this, 1.0F);
			} else {
				target.setInvulnerableTime(0);
				target.hurtServer(level, source, damage);
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
		if (partyTeam >= 0) {
			// Party bots: no friendly fire, from bots or players on the same team.
			if (attacker instanceof SoldierEntity s && s.partyTeam == partyTeam
					|| attacker instanceof Player p && dev.rubymod.game.party.PartyGames.teamOf(p) == partyTeam) {
				return false;
			}
		} else if (attacker instanceof SoldierEntity s && s.isRaider() == isRaider() && (isRaider() || Squads.same(s.owner, owner))) {
			return false;
		}
		if (!isRaider() && attacker instanceof Player p && (p.getUUID().equals(owner) || Squads.same(owner, p.getUUID())) && !p.isCreative()) {
			return false;
		}
		if (shielded && source.getSourcePosition() != null && !source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) {
			Vec3 toAttacker = source.getSourcePosition().subtract(position()).multiply(1, 0, 1).normalize();
			Vec3 facing = Vec3.directionFromRotation(0, yBodyRot);
			if (toAttacker.dot(facing) > 0.45) {
				amount *= 0.2F;
				level.playSound(null, getX(), getY(), getZ(), SoundEvents.SHIELD_BLOCK.value(), SoundSource.HOSTILE, 0.8F, 1.0F);
			}
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
		if (prey != null) {
			output.putString("prey", prey.toString());
		}
		output.putBoolean("shielded", shielded);
		output.putInt("party_team", partyTeam);
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
		prey = input.getString("prey").map(UUID::fromString).orElse(null);
		shielded = input.getBooleanOr("shielded", false);
		partyTeam = input.getIntOr("party_team", -1);
		if (isRaider() && prey == null && partyTeam < 0) {
			// Raids don't survive a restart (a duel's practice bot does: its chunk can be saved mid-duel).
			discard();
		}
	}
}
