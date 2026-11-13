package dev.rubymod.game.party;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.game.Duels;
import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/**
 * Tank Battle: Red against Blue, everyone in a tank, on the vehicle arena (hills,
 * ruins, trees). Knocked out? A fresh tank in 5 seconds. First team to 10 kills wins.
 */
public class TankBattle extends PartyGame {
	private static final int WIN = 10;
	private static final int ROUND_TICKS = 8 * 60 * 20;
	private final int[] kills = new int[2];
	/** Each player's tank, and ticks until they climb in. */
	private final Map<UUID, UUID> tanks = new HashMap<>();
	private final Map<UUID, Integer> boardIn = new HashMap<>();

	public TankBattle() {
		super("tanks", "Tank Battle", "Red vs Blue in tanks. First team to 10 knockouts wins!", "rubymod:tank", 1, true, true, true);
	}

	@Override
	protected boolean hasBots() {
		return true;
	}

	@Override
	protected boolean hasPickups() {
		return true;
	}

	// ----- Bot tanks: on your own (or with bots switched on) everyone is Red, against Blue tanks that drive themselves -----

	/** This round is against the bots. */
	private boolean versusBots;
	/** Each bot tank: its model, and ticks until it fires again. */
	private final Map<UUID, Object[]> botTanks = new HashMap<>();
	/** When each lost bot tank comes back (round ticks). */
	private final java.util.List<Integer> botReturns = new java.util.ArrayList<>();

	private void spawnBotTank() {
		ServerLevel level = level();
		BlockPos c = center();
		var random = level.getRandom();
		double x = c.getX() + random.nextInt(141) - 70 + 0.5;
		double z = c.getZ() + 92 + random.nextInt(10) + 0.5;
		Mob base = net.minecraft.world.entity.EntityTypes.RAVAGER.create(level, EntitySpawnReason.EVENT);
		if (base == null) {
			return;
		}
		base.snapTo(x, dev.rubymod.game.Hub.safeY(level, x, c.getY(), z), z, 180, 0);
		setAttr(base, net.minecraft.world.entity.ai.attributes.Attributes.SCALE, 1.25);
		setAttr(base, net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH, 120);
		setAttr(base, net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED, 0.14);
		setAttr(base, net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE, 200);
		setAttr(base, net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE, 1.0);
		setAttr(base, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE, 0);
		base.setHealth(base.getMaxHealth());
		base.setSilent(true);
		base.setPersistenceRequired();
		base.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY, -1, 0, false, false));
		base.setCustomName(Component.literal("Blue Bot Tank").withStyle(ChatFormatting.BLUE, ChatFormatting.BOLD));
		base.addTag(tag());
		base.addTag("rubymod_horde"); // allowed even with hostile mobs off
		level.addFreshEntity(base);
		spawned.add(base.getUUID());
		var model = dev.rubymod.game.Props.model(level, base.position(), 180, "legion_tank", dev.rubymod.game.convoy.ConvoyModels.LEGION_TANK_SIZE,
				dev.rubymod.game.convoy.ConvoyModels.LEGION_TANK_Y, dev.rubymod.game.convoy.ConvoyModels.LEGION_TANK_Z, tag());
		botTanks.put(base.getUUID(), new Object[] {model == null ? null : model.getUUID(), new int[] {80 + random.nextInt(60)}});
	}

	private static void setAttr(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr, double value) {
		var a = mob.getAttribute(attr);
		if (a != null) {
			a.setBaseValue(value);
		}
	}

	/** Every tick: each bot tank rolls toward the nearest player, keeps its model with it, and shells them when it has a clear shot. */
	private void tickBotTanks() {
		ServerLevel level = level();
		for (var it = botReturns.iterator(); it.hasNext();) {
			if (roundTicks >= it.next()) {
				it.remove();
				spawnBotTank();
			}
		}
		for (var e : botTanks.entrySet()) {
			if (!(level.getEntity(e.getKey()) instanceof Mob mob) || !mob.isAlive()) {
				continue;
			}
			Entity model = e.getValue()[0] == null ? null : level.getEntity((UUID) e.getValue()[0]);
			if (model != null) {
				model.teleportTo(mob.getX(), mob.getY(), mob.getZ());
				model.setYRot(mob.yBodyRot);
			}
			ServerPlayer target = null;
			for (ServerPlayer p : online()) {
				Part part = players.get(p.getUUID());
				if (part != null && !part.watching && part.respawnIn <= 0 && !p.isSpectator() && (target == null || p.distanceToSqr(mob) < target.distanceToSqr(mob))) {
					target = p;
				}
			}
			if (target == null) {
				mob.getNavigation().stop();
				continue;
			}
			double far = target.distanceTo(mob);
			if (roundTicks % 20 == 0) {
				if (far > 34) {
					mob.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), 1.0);
				} else {
					mob.getNavigation().stop();
				}
			}
			mob.setTarget(null);
			mob.getLookControl().setLookAt(target.getX(), target.getEyeY(), target.getZ());
			int[] cooldown = (int[]) e.getValue()[1];
			if (--cooldown[0] > 0 || far > 90) {
				continue;
			}
			cooldown[0] = 90 + level.getRandom().nextInt(50);
			// A shell at where the player is, a little off: it has to be dodged, not just survived.
			double yaw = Math.toRadians(mob.yBodyRot);
			net.minecraft.world.phys.Vec3 muzzle = mob.position().add(-Math.sin(yaw) * 3.2, 2.0, Math.cos(yaw) * 3.2);
			net.minecraft.world.phys.Vec3 aim = target.position().add((level.getRandom().nextDouble() - 0.5) * far * 0.08, 1.0,
					(level.getRandom().nextDouble() - 0.5) * far * 0.08);
			net.minecraft.world.phys.Vec3 way = aim.subtract(muzzle).normalize();
			level.addFreshEntity(dev.rubymod.entity.RocketEntity.tankShell(level, mob, muzzle, way.scale(2.4), 3.0F));
			level.playSound(null, BlockPos.containing(muzzle), dev.rubymod.registry.ModSounds.TANK_CANNON, net.minecraft.sounds.SoundSource.HOSTILE, 3.0F, 1.1F);
			level.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, muzzle.x, muzzle.y, muzzle.z, 10, 0.3, 0.3, 0.3, 0.05);
		}
	}

	@Override
	protected void onEntityDied(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.damagesource.DamageSource source) {
		Object[] bot = botTanks.remove(entity.getUUID());
		if (bot == null || phase != Phase.PLAYING) {
			return;
		}
		ServerLevel level = level();
		if (bot[0] != null && level.getEntity((UUID) bot[0]) instanceof Entity model) {
			model.discard();
		}
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER, entity.getX(), entity.getY() + 1, entity.getZ(), 1, 0, 0, 0, 0);
		level.playSound(null, entity.blockPosition(), net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.HOSTILE, 3.0F, 0.7F);
		kills[0]++;
		if (source.getEntity() instanceof ServerPlayer k && players.containsKey(k.getUUID())) {
			players.get(k.getUUID()).kills++;
			k.sendOverlayMessage(Component.literal("Knocked out a bot tank!").withStyle(ChatFormatting.GREEN));
		}
		tell(Component.literal("A Blue bot tank is knocked out (" + kills[0] + " - " + kills[1] + ")").withStyle(ChatFormatting.BLUE));
		botReturns.add(roundTicks + 120);
		if (kills[0] >= WIN) {
			endRound("RED WINS!", "Red " + kills[0] + " - " + kills[1] + " Blue bots", ChatFormatting.RED, teamIds(0));
		}
	}

	@Override
	protected BlockPos center() {
		return new BlockPos(-65000, GROUND + 1, 0);
	}

	@Override
	protected int reach() {
		return 150;
	}

	/** A fresh arena every round (craters filled, walls back up), built a little at a time. */
	@Override
	protected void build(ServerLevel level) {
		map = built++ % MAPS.length;
		Duels.ArenaBuilder.buildQueued(level, Duels.Mode.VEHICLES, center(), this::later);
		if (map == 1) {
			// A town in the middle: the shells of sixteen buildings, with streets between them (tanks can drive through the walls).
			var wall = net.minecraft.world.level.block.Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.LIGHT_GRAY).defaultBlockState();
			var trim = net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState();
			var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
			for (int gx : new int[] {-66, -24, 24, 66}) {
				for (int gz : new int[] {-62, -22, 22, 62}) {
					box(level, gx - 7, 1, gz - 7, gx + 7, 5, gz + 7, air);
					box(level, gx - 6, 1, gz - 6, gx + 6, 4, gz - 6, wall);
					box(level, gx - 6, 1, gz + 6, gx + 6, 4, gz + 6, wall);
					box(level, gx - 6, 1, gz - 6, gx - 6, 4, gz + 6, wall);
					box(level, gx + 6, 1, gz - 6, gx + 6, 4, gz + 6, wall);
					box(level, gx - 6, 5, gz - 6, gx + 6, 5, gz + 6, trim);
					box(level, gx - 5, 5, gz - 5, gx + 5, 5, gz + 5, air);
					// Doorways and windows.
					box(level, gx - 1, 1, gz - 6, gx + 1, 3, gz - 6, air);
					box(level, gx - 1, 1, gz + 6, gx + 1, 3, gz + 6, air);
					box(level, gx - 6, 2, gz - 2, gx - 6, 3, gz + 2, air);
					box(level, gx + 6, 2, gz - 2, gx + 6, 3, gz + 2, air);
				}
			}
		} else if (map == 2) {
			// Two long ridges across the field, with gaps to push through.
			var dirt = net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
			var grass = net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState();
			var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
			for (int rz : new int[] {-38, 38}) {
				box(level, -104, 1, rz - 2, 104, 3, rz + 2, dirt);
				box(level, -104, 4, rz - 1, 104, 4, rz + 1, grass);
				for (int gap : new int[] {-72, -30, 30, 72}) {
					box(level, gap - 5, 1, rz - 2, gap + 5, 4, rz + 2, air);
				}
				box(level, -5, 1, rz - 2, 5, 4, rz + 2, air);
			}
		}
	}

	/** Which map this round is on: the same stadium with different things in the middle. */
	private int map;
	private int built;
	private static final String[] MAPS = {"The Proving Ground", "Ghost Town", "The Ridges"};

	@Override
	protected String roundStatus() {
		int left = Math.max(0, ROUND_TICKS - roundTicks) / 20;
		return "Red " + kills[0] + " - " + kills[1] + " Blue  •  " + left / 60 + ":" + String.format("%02d", left % 60);
	}

	@Override
	protected float barProgress() {
		return Math.max(kills[0], kills[1]) / (float) WIN;
	}

	@Override
	protected void startRound() {
		kills[0] = 0;
		kills[1] = 0;
		tanks.clear();
		boardIn.clear();
		minesLeft.clear();
		botTanks.clear();
		botReturns.clear();
		versusBots = useBots();
		if (versusBots) {
			// Everyone on Red, against the Blue bot tanks.
			for (ServerPlayer p : online()) {
				Part part = players.get(p.getUUID());
				part.team = 0;
				joinTeam(p, 0);
			}
			int bots = Math.max(2, Math.min(4, activeCount() + 1));
			for (int i = 0; i < bots; i++) {
				spawnBotTank();
			}
			tell(Component.literal("You're all Red, against " + bots + " Blue bot tanks.").withStyle(ChatFormatting.RED));
		}
		tell(Component.literal("Map: " + MAPS[map]).withStyle(ChatFormatting.AQUA));
		// Pick-ups out in the open, where it's dangerous to go and get them.
		BlockPos c = center();
		Object[][] spots = {{"rapid", 0, 0}, {"repair", -55, 0}, {"repair", 55, 0}, {"mines", 0, -48}, {"mines", 0, 48}, {"repair", 0, -85}, {"repair", 0, 85}};
		for (Object[] spot : pickupsOn ? spots : new Object[0][]) {
			String kind = (String) spot[0];
			double x = c.getX() + (int) spot[1] + 0.5;
			double z = c.getZ() + (int) spot[2] + 0.5;
			net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(x, dev.rubymod.game.Hub.safeY(level(), x, c.getY() + 6, z) + 1.5, z);
			switch (kind) {
				case "rapid" -> addPickup(kind, at, new ItemStack(net.minecraft.world.item.Items.BLAZE_POWDER), "RAPID FIRE", ChatFormatting.GOLD, 45 * 20, 4.5);
				case "mines" -> addPickup(kind, at, new ItemStack(ModItems.TANK), "MINES", ChatFormatting.RED, 40 * 20, 4.5);
				default -> addPickup(kind, at, new ItemStack(net.minecraft.world.item.Items.ANVIL), "REPAIR", ChatFormatting.GREEN, 30 * 20, 4.5);
			}
		}
		for (ServerPlayer p : online()) {
			Part part = players.get(p.getUUID());
			spawn(p, part);
			dev.rubymod.game.Alerts.title(p, "YOU'RE " + teamName(part.team).toUpperCase(), pickupsOn ? "First team to 10 knockouts. Drive over the pick-ups!"
					: "First team to 10 knockouts wins",
					part.team == 0 ? ChatFormatting.RED : ChatFormatting.BLUE);
		}
	}

	@Override
	protected void spawn(ServerPlayer player, Part part) {
		ServerLevel level = level();
		BlockPos c = center();
		int dir = part.team == 0 ? 1 : -1;
		// Along your own back line, at the spot farthest from the other team (and your own tanks), with 5 seconds of protection.
		double x = c.getX() + 0.5;
		double z = c.getZ() - dir * (96 + player.getRandom().nextInt(10)) + 0.5;
		double best = -1;
		for (int tries = 0; tries < 10; tries++) {
			double tx = c.getX() + 0.5 + player.getRandom().nextInt(161) - 80;
			double nearest = 999;
			for (ServerPlayer o : online()) {
				if (o != player && !o.isSpectator()) {
					nearest = Math.min(nearest, Math.hypot(o.getX() - tx, o.getZ() - z) * (players.get(o.getUUID()) != null && players.get(o.getUUID()).team == part.team
							? 2.5 : 1.0));
				}
			}
			if (nearest > best) {
				best = nearest;
				x = tx;
			}
		}
		float yaw = part.team == 0 ? 0 : 180;
		UUID old = tanks.remove(player.getUUID());
		if (old != null && level.getEntity(old) instanceof Entity e && !e.isVehicle()) {
			e.discard();
		}
		teleport(player, x, c.getY(), z, yaw);
		ready(player);
		clearPlayer(player);
		give(player, new ItemStack(ModItems.LANCER), new ItemStack(ModItems.AT_MISSILE, 4), gun(ModItems.ARR_RIFLE), new ItemStack(ModItems.HEAVY_AMMO, 64));
		Mob tank = ModEntities.TANK.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
		if (tank != null) {
			tank.snapTo(x, dev.rubymod.game.Hub.safeY(level, x, c.getY(), z + dir * 3), z + dir * 3, yaw, 0);
			if (tank instanceof dev.rubymod.entity.VehicleEntity v) {
				v.shield(dev.rubymod.game.SpawnShield.TICKS);
			}
			tank.yBodyRot = yaw;
			tank.yHeadRot = yaw;
			tank.addTag(tag());
			level.addFreshEntity(tank);
			spawned.add(tank.getUUID());
			tanks.put(player.getUUID(), tank.getUUID());
			// Climb in a moment later, once the client has the ground loaded.
			boardIn.put(player.getUUID(), 30);
		}
	}

	/** Mines still to drop behind each player's tank (one every second and a half). */
	private final Map<UUID, Integer> minesLeft = new HashMap<>();

	@Override
	protected boolean onPickup(ServerPlayer player, Pickup pickup) {
		if (!(player.getVehicle() instanceof dev.rubymod.entity.VehicleEntity tank)) {
			return false;
		}
		switch (pickup.kind) {
			case "rapid" -> {
				tank.rapidFire(15 * 20);
				dev.rubymod.game.Alerts.title(player, "RAPID FIRE", "Your gun reloads 3x faster for 15 seconds", ChatFormatting.GOLD);
			}
			case "mines" -> {
				minesLeft.put(player.getUUID(), 3);
				dev.rubymod.game.Alerts.title(player, "MINES", "Dropping 3 mines behind you: keep driving!", ChatFormatting.RED);
			}
			default -> {
				if (tank.getHealth() >= tank.getMaxHealth() && tank.smokeCharges() >= dev.rubymod.entity.VehicleEntity.SMOKE_CHARGES) {
					return false;
				}
				tank.setHealth(Math.min(tank.getMaxHealth(), tank.getHealth() + tank.getMaxHealth() * 0.6F));
				tank.refillSmoke();
				dev.rubymod.game.Alerts.title(player, "REPAIRED", "Armor patched up, smoke reloaded", ChatFormatting.GREEN);
			}
		}
		Part part = players.get(player.getUUID());
		tell(Component.literal(part.name + " grabbed " + pickup.kind.toUpperCase().replace("RAPID", "RAPID FIRE") + "!")
				.withStyle(part.team == 0 ? ChatFormatting.RED : ChatFormatting.BLUE));
		return true;
	}

	/** Tanks plough through whatever isn't the stadium wall: ruins, trees, anything taller than they can climb. */
	private void crush() {
		ServerLevel level = level();
		BlockPos c = center();
		for (UUID id : tanks.values()) {
			if (!(level.getEntity(id) instanceof dev.rubymod.entity.VehicleEntity tank) || !tank.isVehicle()
					|| tank.getDeltaMovement().horizontalDistanceSqr() < 0.0025) {
				continue;
			}
			net.minecraft.world.phys.AABB box = tank.getBoundingBox().inflate(0.6, 0, 0.6).move(tank.getDeltaMovement().multiply(3, 0, 3));
			int y0 = (int) Math.floor(tank.getY() + 1.05);
			boolean broke = false;
			for (BlockPos pos : BlockPos.betweenClosed((int) Math.floor(box.minX), y0, (int) Math.floor(box.minZ), (int) Math.floor(box.maxX), y0 + 2,
					(int) Math.floor(box.maxZ))) {
				var state = level.getBlockState(pos);
				if (state.isAir() || state.is(net.minecraft.world.level.block.Blocks.BARRIER) || state.getDestroySpeed(level, pos) < 0
						|| wallAt(pos.getX() - c.getX(), pos.getY() - c.getY(), pos.getZ() - c.getZ())) {
					continue;
				}
				level.destroyBlock(pos, false);
				broke = true;
			}
			if (broke) {
				tank.setDeltaMovement(tank.getDeltaMovement().multiply(0.8, 1, 0.8));
			}
		}
	}

	private void dropMines() {
		ServerLevel level = level();
		for (var it = minesLeft.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			ServerPlayer p = PartyGames.player(server, e.getKey());
			if (p == null || !(p.getVehicle() instanceof dev.rubymod.entity.VehicleEntity tank)) {
				it.remove();
				continue;
			}
			net.minecraft.world.phys.Vec3 back = tank.position().subtract(net.minecraft.world.phys.Vec3.directionFromRotation(0, tank.getYRot()).scale(4.5));
			BlockPos at = BlockPos.containing(back.x, dev.rubymod.game.Hub.safeY(level, back.x, tank.getY() + 2, back.z), back.z);
			if (level.getBlockState(at).isAir() && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), net.minecraft.core.Direction.UP)) {
				level.setBlock(at, dev.rubymod.registry.ModBlocks.AT_MINE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
				level.playSound(null, at, net.minecraft.sounds.SoundEvents.IRON_TRAPDOOR_CLOSE, net.minecraft.sounds.SoundSource.PLAYERS, 1.5F, 0.7F);
				if (e.getValue() <= 1) {
					it.remove();
				} else {
					e.setValue(e.getValue() - 1);
				}
			}
		}
	}

	/** The stadium's perimeter wall (and a little either side of it). */
	@Override
	protected boolean wallAt(int dx, int dy, int dz) {
		int h = dev.rubymod.game.Duels.Mode.VEHICLES.half;
		return Math.max(Math.abs(dx), Math.abs(dz)) >= h - 1 && dy >= -2;
	}

	/** Out past the wall (through a gap, over it, anyhow): back inside, tank and all. */
	private void keepInside() {
		BlockPos c = center();
		int h = dev.rubymod.game.Duels.Mode.VEHICLES.half;
		for (ServerPlayer p : online()) {
			if (p.isSpectator()) {
				continue;
			}
			Entity body = p.getVehicle() != null ? p.getVehicle() : p;
			double dx = body.getX() - c.getX();
			double dz = body.getZ() - c.getZ();
			if (Math.abs(dx) <= h - 2 && Math.abs(dz) <= h - 2 && body.getY() < c.getY() + 30) {
				continue;
			}
			double x = c.getX() + Math.max(-(h - 8), Math.min(h - 8, dx));
			double z = c.getZ() + Math.max(-(h - 8), Math.min(h - 8, dz));
			double y = dev.rubymod.game.Hub.safeY(level(), x, c.getY(), z);
			body.teleportTo(x, y, z);
			body.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
			p.sendOverlayMessage(Component.literal("Stay inside the arena!").withStyle(ChatFormatting.RED));
		}
	}

	@Override
	protected void onDowned(ServerPlayer victim, Part part, Entity killer) {
		part.respawnIn = 100;
		int other = 1 - part.team;
		if (killer instanceof ServerPlayer k && players.containsKey(k.getUUID()) && players.get(k.getUUID()).team == other) {
			kills[other]++;
			k.sendOverlayMessage(Component.literal("Knocked out " + part.name + "!").withStyle(ChatFormatting.GREEN));
		} else {
			kills[other]++; // a crash or your own shell still counts for the other side
		}
		tell(Component.literal(part.name + " is knocked out (" + kills[0] + " - " + kills[1] + ")")
				.withStyle(part.team == 0 ? ChatFormatting.RED : ChatFormatting.BLUE));
		if (kills[other] >= WIN) {
			endRound(teamName(other).toUpperCase() + " WINS!", "Red " + kills[0] + " - " + kills[1] + " Blue", other == 0 ? ChatFormatting.RED : ChatFormatting.BLUE,
					teamIds(other));
		}
	}

	@Override
	protected void tickRound() {
		if (roundTicks % 10 == 0) {
			keepInside();
		}
		if (roundTicks % 2 == 0) {
			crush();
		}
		if (versusBots) {
			tickBotTanks();
		}
		if (roundTicks % 30 == 0) {
			dropMines();
		}
		ServerLevel level = level();
		for (var it = boardIn.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			int left = e.getValue() - 1;
			if (left > 0) {
				e.setValue(left);
				continue;
			}
			it.remove();
			ServerPlayer p = PartyGames.player(server, e.getKey());
			UUID tankId = tanks.get(e.getKey());
			if (p != null && tankId != null && level.getEntity(tankId) instanceof Entity tank && tank.isAlive() && !tank.isVehicle()
					&& p.getVehicle() == null && p.distanceTo(tank) < 12) {
				p.startRiding(tank, true, true);
			}
		}
		if (roundTicks >= ROUND_TICKS) {
			String head = kills[0] == kills[1] ? "DRAW" : (kills[0] > kills[1] ? "RED" : "BLUE") + " WINS!";
			endRound(head, "Time's up: Red " + kills[0] + " - " + kills[1] + " Blue", ChatFormatting.GOLD,
					kills[0] == kills[1] ? java.util.List.<UUID>of() : teamIds(kills[0] > kills[1] ? 0 : 1));
		}
	}

	@Override
	public boolean allowsBreak(ServerPlayer player, BlockPos pos) {
		return false;
	}
}
