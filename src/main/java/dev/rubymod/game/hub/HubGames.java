package dev.rubymod.game.hub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.game.Alerts;
import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.mixin.InteractionAccessor;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The hub's games and toys: the shooting gallery, mini-golf, fishing, the stage's DJ booth and dance floor, snowball
 * fights, the fireworks button, and the toy chest's confetti cannon, rubber chicken and bouncy ball. Nothing here keeps
 * a score past the moment; toys only work (and only exist) in the hub.
 */
public final class HubGames {
	private static final String TOY = "rubymod_toy";

	private HubGames() {
	}

	// ----- Toys -----

	static ItemStack toy(net.minecraft.world.item.Item item, int count, String name, ChatFormatting color) {
		ItemStack stack = new ItemStack(item, count);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(TOY, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		if (name != null) {
			stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(color));
		}
		return stack;
	}

	public static boolean isToy(ItemStack stack) {
		var data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(TOY);
	}

	private static boolean named(ItemStack stack, String name) {
		return isToy(stack) && stack.getHoverName().getString().equals(name);
	}

	static void hand(ServerPlayer p, ItemStack stack) {
		// One of each: a second click doesn't pile them up.
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack have = p.getInventory().getItem(i);
			if (isToy(have) && have.getItem() == stack.getItem() && have.getHoverName().getString().equals(stack.getHoverName().getString())) {
				have.setCount(Math.max(have.getCount(), stack.getCount()));
				return;
			}
		}
		if (!p.getInventory().add(stack)) {
			p.sendOverlayMessage(Component.literal("Your hands are full!"));
		}
	}

	/** Hand someone a toy (or top up the one they have). */
	static void give(ServerPlayer p, ItemStack stack) {
		hand(p, stack);
		p.inventoryMenu.broadcastChanges();
	}

	/** Put the toy called {@code name} in their hand (swapped into the slot they're holding). */
	static void hold(ServerPlayer p, String name) {
		var inv = p.getInventory();
		int sel = inv.getSelectedSlot();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (named(inv.getItem(i), name)) {
				if (i != sel) {
					ItemStack was = inv.getItem(sel);
					inv.setItem(sel, inv.getItem(i));
					inv.setItem(i, was);
				}
				p.inventoryMenu.broadcastChanges();
				return;
			}
		}
	}

	/** Take back every toy called {@code name}. */
	static void takeAll(ServerPlayer p, String name) {
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			if (named(p.getInventory().getItem(i), name)) {
				p.getInventory().setItem(i, ItemStack.EMPTY);
			}
		}
		p.inventoryMenu.broadcastChanges();
	}

	/** Where each game's things belong (x, z, radius): leave and they're put back. Prizes left over from before go too. */
	private static final java.util.Map<String, double[]> HOME = java.util.Map.of(
			"Putter", new double[] {-44, 44, 22},
			"Gallery Snowball", new double[] {56, 2, 22},
			"Mallet", new double[] {84, 40, 26},
			"Basketball", new double[] {84, 40, 26},
			"Toss Ring", new double[] {84, 40, 26},
			"Teddy Bear (prize!)", new double[] {0, 0, -1},
			"Rubber Duck (Duck Shoot prize!)", new double[] {0, 0, -1});

	/** Take away anything that belongs somewhere else (the putter when you leave Mini Golf, and so on). */
	static void putBack(ServerLevel level) {
		for (ServerPlayer p : level.players()) {
			var inv = p.getInventory();
			boolean changed = false;
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack s = inv.getItem(i);
				if (!isToy(s)) {
					continue;
				}
				double[] home = HOME.get(s.getHoverName().getString());
				if (home != null && (home[2] < 0 || Math.hypot(p.getX() - home[0], p.getZ() - home[1]) > home[2])) {
					inv.setItem(i, ItemStack.EMPTY);
					changed = true;
					if (home[2] > 0) {
						p.sendOverlayMessage(Component.literal("(You left the " + s.getHoverName().getString().toLowerCase() + " where it belongs.)")
								.withStyle(ChatFormatting.GRAY));
					}
				}
			}
			if (changed) {
				p.inventoryMenu.broadcastChanges();
			}
		}
	}

	/** The toy chest on the plaza. */
	static void toyChest(ServerPlayer p) {
		toyChest(p, "classic");
	}

	/** One of Tilly's bundles: the classics, the outdoor toys, or the silly ones. */
	static void toyChest(ServerPlayer p, String key) {
		if ("outdoor".equals(key) || "silly".equals(key) || "gadgets".equals(key)) {
			for (ItemStack s : HubToys.bundle(key)) {
				hand(p, s);
			}
		} else {
			hand(p, toy(Items.FIREWORK_STAR, 1, "Confetti Cannon", ChatFormatting.LIGHT_PURPLE));
			hand(p, toy(Items.CHICKEN, 1, "Rubber Chicken", ChatFormatting.YELLOW));
			hand(p, toy(Items.SLIME_BALL, 1, "Bouncy Ball", ChatFormatting.GREEN));
			hand(p, toy(Items.SNOWBALL, 16, "Snowball", ChatFormatting.WHITE));
		}
		p.inventoryMenu.broadcastChanges();
		p.sendSystemMessage(Component.literal("Toys! Right-click to use them. They stay in the hub.").withStyle(ChatFormatting.LIGHT_PURPLE));
		p.level().playSound(null, p.blockPosition(), SoundEvents.CHEST_OPEN, SoundSource.PLAYERS, 0.8F, 1.3F);
	}

	/** Right-clicking with a toy. True when it was one (and handled). */
	static boolean useToy(ServerPlayer p, ItemStack held) {
		ServerLevel level = (ServerLevel) p.level();
		if (named(held, "Confetti Cannon")) {
			if (p.getCooldowns().isOnCooldown(held)) {
				return true;
			}
			p.getCooldowns().addCooldown(held, 15);
			Vec3 eye = p.getEyePosition();
			Vec3 look = p.getLookAngle();
			int[] colors = {0xFF3050, 0xFFD040, 0x40C8FF, 0x60FF80, 0xC060FF, 0xFFFFFF};
			var random = level.getRandom();
			for (int i = 0; i < 70; i++) {
				double d = 1 + random.nextDouble() * 5;
				Vec3 at = eye.add(look.scale(d)).add((random.nextDouble() - 0.5) * d * 0.5, (random.nextDouble() - 0.5) * d * 0.5,
						(random.nextDouble() - 0.5) * d * 0.5);
				level.sendParticles(new DustParticleOptions(colors[random.nextInt(colors.length)], 1.2F), at.x, at.y, at.z, 1, 0, 0, 0, 0);
			}
			level.playSound(null, p.blockPosition(), SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 1.0F, 1.2F);
			level.playSound(null, p.blockPosition(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 0.6F, 1.6F);
			return true;
		}
		if (named(held, "Rubber Chicken")) {
			squawk(level, p.position());
			return true;
		}
		if (named(held, "Bouncy Ball")) {
			if (p.getCooldowns().isOnCooldown(held)) {
				return true;
			}
			p.getCooldowns().addCooldown(held, 10);
			Ball b = Ball.spawn(level, p.getEyePosition().add(p.getLookAngle().scale(0.6)), Blocks.SLIME_BLOCK.defaultBlockState(), 0.35F, null);
			if (b != null) {
				b.vel = p.getLookAngle().scale(0.85);
				b.gravity = true;
				b.life = 200;
				BALLS.add(b);
			}
			level.playSound(null, p.blockPosition(), SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.8F, 0.6F);
			return true;
		}
		return HubToys.use(p, held);
	}

	static void squawk(ServerLevel level, Vec3 at) {
		level.playSound(null, BlockPos.containing(at), SoundEvents.CHICKEN_HURT_BABY.value(), SoundSource.PLAYERS, 1.4F, 0.6F + level.getRandom().nextFloat() * 0.8F);
		level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 1, at.z, 4, 0.3, 0.3, 0.3, 0.02);
	}

	/** Bonked with the rubber chicken: a squawk and a little shove (no damage: nothing hurts in the hub). */
	static boolean chickenHit(ServerPlayer attacker, Entity target) {
		if (!named(attacker.getMainHandItem(), "Rubber Chicken") || !(target instanceof ServerPlayer victim)) {
			return false;
		}
		ServerLevel level = (ServerLevel) attacker.level();
		squawk(level, victim.position());
		Vec3 push = victim.position().subtract(attacker.position()).multiply(1, 0, 1).normalize().scale(0.6).add(0, 0.35, 0);
		victim.setDeltaMovement(victim.getDeltaMovement().add(push));
		victim.syncVelocity = true;
		return true;
	}

	// ----- Balls (mini-golf, and the bouncy ball) -----

	static final class Ball {
		UUID display;
		UUID hitbox;
		Vec3 pos;
		Vec3 vel = Vec3.ZERO;
		boolean gravity;
		int life = -1;
		/** Mini-golf: whose ball, which hole, how many strokes. */
		UUID owner;
		int hole;
		int strokes;
		int idle;

		static Ball spawn(ServerLevel level, Vec3 at, BlockState look, float size, String hitboxTag) {
			Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (d == null) {
				return null;
			}
			d.snapTo(at.x, at.y, at.z, 0, 0);
			((BlockDisplayAccessor) d).rubymod$setBlockState(look);
			((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(-size / 2, 0, -size / 2), new Quaternionf(),
					new Vector3f(size, size, size), new Quaternionf()));
			((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(1);
			level.addFreshEntity(d);
			d.addTag(HubRides.TEMP);
			HubPark.live(d);
			Ball b = new Ball();
			b.display = d.getUUID();
			b.pos = at;
			if (hitboxTag != null) {
				Interaction hit = EntityTypes.INTERACTION.create(level, EntitySpawnReason.EVENT);
				if (hit != null) {
					((InteractionAccessor) hit).rubymod$setWidth(0.6F);
					((InteractionAccessor) hit).rubymod$setHeight(0.5F);
					hit.snapTo(at.x, at.y, at.z, 0, 0);
					level.addFreshEntity(hit);
					hit.addTag(HubRides.TEMP);
					hit.addTag(hitboxTag);
					HubPark.live(hit);
					b.hitbox = hit.getUUID();
				}
			}
			return b;
		}

		void remove(ServerLevel level) {
			for (UUID id : new UUID[] {display, hitbox}) {
				Entity e = id == null ? null : level.getEntity(id);
				if (e != null) {
					e.discard();
				}
			}
		}

		boolean solid(ServerLevel level, Vec3 at) {
			BlockPos p = BlockPos.containing(at);
			return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
		}

		/** One tick of rolling or bouncing, bouncing off walls. */
		void step(ServerLevel level) {
			int sub = 4;
			for (int s = 0; s < sub; s++) {
				Vec3 v = vel.scale(1.0 / sub);
				Vec3 nx = pos.add(v.x, 0, 0);
				if (solid(level, nx.add(Math.signum(v.x) * 0.12, 0.05, 0))) {
					vel = new Vec3(-vel.x * 0.7, vel.y, vel.z);
					level.playSound(null, BlockPos.containing(pos), SoundEvents.WOOD_HIT, SoundSource.BLOCKS, 0.4F, 1.6F);
				} else {
					pos = nx;
				}
				Vec3 nz = pos.add(0, 0, v.z);
				if (solid(level, nz.add(0, 0.05, Math.signum(v.z) * 0.12))) {
					vel = new Vec3(vel.x, vel.y, -vel.z * 0.7);
					level.playSound(null, BlockPos.containing(pos), SoundEvents.WOOD_HIT, SoundSource.BLOCKS, 0.4F, 1.6F);
				} else {
					pos = nz;
				}
				if (gravity) {
					Vec3 ny = pos.add(0, v.y, 0);
					if (v.y < 0 && solid(level, ny)) {
						vel = new Vec3(vel.x * 0.9, Math.abs(vel.y) > 0.12 ? -vel.y * 0.75 : 0, vel.z * 0.9);
						if (Math.abs(vel.y) > 0.05) {
							level.playSound(null, BlockPos.containing(pos), SoundEvents.SLIME_JUMP_SMALL, SoundSource.PLAYERS, 0.6F, 1.4F);
						}
					} else if (v.y > 0 && solid(level, ny.add(0, 0.35, 0))) {
						vel = new Vec3(vel.x, -vel.y * 0.5, vel.z);
					} else {
						pos = ny;
					}
				}
			}
			if (gravity) {
				vel = vel.add(0, -0.045, 0).scale(0.99);
			} else {
				vel = vel.scale(0.955);
				if (vel.lengthSqr() < 0.0004) {
					vel = Vec3.ZERO;
				}
			}
			for (UUID id : new UUID[] {display, hitbox}) {
				Entity e = id == null ? null : level.getEntity(id);
				if (e != null) {
					e.teleportTo(pos.x, pos.y, pos.z);
				}
			}
		}
	}

	private static final List<Ball> BALLS = new ArrayList<>();
	private static int tick;

	/** Every snowball in the hub: does it splat somebody? */
	private static void tickSnowballs(ServerLevel level) {
		for (Snowball ball : level.getEntities(EntityTypes.SNOWBALL, HubPark.WHOLE_HUB, s -> s.isAlive())) {
			Vec3 at = ball.position();
			Vec3 ahead = at.add(ball.getDeltaMovement().scale(1.2));
			for (ServerPlayer p : level.players()) {
				AABB body = p.getBoundingBox().inflate(0.3);
				if (p != ball.getOwner() && !p.isSpectator() && (body.contains(at) || body.clip(at, ahead).isPresent())) {
					level.sendParticles(ParticleTypes.ITEM_SNOWBALL, at.x, at.y, at.z, 16, 0.3, 0.3, 0.3, 0.1);
					level.playSound(null, p.blockPosition(), SoundEvents.SNOW_BREAK, SoundSource.PLAYERS, 1.0F, 1.2F);
					p.setDeltaMovement(p.getDeltaMovement().add(ball.getDeltaMovement().multiply(0.25, 0, 0.25)).add(0, 0.12, 0));
					p.syncVelocity = true;
					p.sendOverlayMessage(Component.literal("SPLAT!").withStyle(ChatFormatting.WHITE));
					ball.discard();
					break;
				}
			}
		}
	}

	static void snowPile(ServerPlayer p) {
		hand(p, toy(Items.SNOWBALL, 16, null, ChatFormatting.WHITE));
		p.inventoryMenu.broadcastChanges();
		p.level().playSound(null, p.blockPosition(), SoundEvents.SNOW_BREAK, SoundSource.PLAYERS, 0.8F, 1.0F);
	}

	// ----- The stage -----

	private static final SoundEvent[] SONGS = {SoundEvents.MUSIC_DISC_CAT.value(), SoundEvents.MUSIC_DISC_BLOCKS.value(), SoundEvents.MUSIC_DISC_CHIRP.value(),
		SoundEvents.MUSIC_DISC_FAR.value(), SoundEvents.MUSIC_DISC_MALL.value(), SoundEvents.MUSIC_DISC_STRAD.value(), SoundEvents.MUSIC_DISC_PIGSTEP.value(),
		SoundEvents.MUSIC_DISC_OTHERSIDE.value(), SoundEvents.MUSIC_DISC_RELIC.value()};
	static final String[] SONG_NAMES = {"Cat", "Blocks", "Chirp", "Far", "Mall", "Strad", "Pigstep", "Otherside", "Relic"};
	private static final int[] SONG_SECONDS = {185, 345, 185, 174, 197, 188, 149, 195, 218};
	private static int song = -1;
	private static int songEnds;

	/** The stage's music is playing (the plaza's tune waits). */
	public static boolean stageBusy() {
		return song >= 0 && tick < songEnds;
	}

	/** The DJ's pick: song {@code i} (or, for -1, stop the music). */
	static void play(ServerPlayer p, int i) {
		ServerLevel level = (ServerLevel) p.level();
		for (ServerPlayer q : level.players()) {
			q.connection.send(new net.minecraft.network.protocol.game.ClientboundStopSoundPacket(null, SoundSource.RECORDS));
		}
		if (i < 0 || i >= SONGS.length) {
			song = -1;
			HubPark.say(level, HubPark.STAGE_CENTER, "The music stops. Ask DJ Remy for another song!");
			return;
		}
		song = i;
		songEnds = tick + SONG_SECONDS[song] * 20;
		level.playSound(null, BlockPos.containing(HubPark.STAGE_CENTER), SONGS[song], SoundSource.RECORDS, 4.0F, 1.0F);
		for (ServerPlayer q : level.players()) {
			if (q.position().distanceTo(HubPark.STAGE_CENTER) < 40) {
				q.sendOverlayMessage(Component.literal("\u266a Now playing: " + SONG_NAMES[song] + " (picked by " + p.getName().getString() + ")")
						.withStyle(ChatFormatting.LIGHT_PURPLE));
			}
		}
	}

	static int song() {
		return stageBusy() ? song : -1;
	}

	static void nextSong(ServerPlayer p) {
		ServerLevel level = (ServerLevel) p.level();
		for (ServerPlayer q : level.players()) {
			q.connection.send(new net.minecraft.network.protocol.game.ClientboundStopSoundPacket(null, SoundSource.RECORDS));
		}
		song = (song + 1) % (SONGS.length + 1);
		if (song == SONGS.length) {
			// After the last: quiet.
			song = -1;
			HubPark.say(level, HubPark.STAGE_CENTER, "The music stops. Click the jukebox for more!");
			return;
		}
		songEnds = tick + SONG_SECONDS[song] * 20;
		level.playSound(null, BlockPos.containing(HubPark.STAGE_CENTER), SONGS[song], SoundSource.RECORDS, 4.0F, 1.0F);
		for (ServerPlayer q : level.players()) {
			if (q.position().distanceTo(HubPark.STAGE_CENTER) < 40) {
				q.sendOverlayMessage(Component.literal("♪ Now playing: " + SONG_NAMES[song] + " (DJ " + p.getName().getString() + ")").withStyle(ChatFormatting.LIGHT_PURPLE));
			}
		}
	}

	private static final BlockState[] FLOOR_COLORS = {Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState(),
		Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.YELLOW).defaultBlockState(),
		Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.LIME).defaultBlockState(),
		Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.LIGHT_BLUE).defaultBlockState(),
		Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.MAGENTA).defaultBlockState(),
		Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.ORANGE).defaultBlockState()};

	private static void tickStage(ServerLevel level) {
		if (!stageBusy() || tick % 6 != 0) {
			return;
		}
		var random = level.getRandom();
		for (int i = 0; i < 12; i++) {
			BlockPos p = HubPark.DANCE_FLOOR.get(random.nextInt(HubPark.DANCE_FLOOR.size()));
			level.setBlock(p, FLOOR_COLORS[random.nextInt(FLOOR_COLORS.length)], 2);
		}
		if (tick % 18 == 0) {
			Vec3 c = HubPark.STAGE_CENTER;
			level.sendParticles(ParticleTypes.NOTE, c.x + (random.nextDouble() - 0.5) * 8, c.y + 2 + random.nextDouble() * 2, c.z + (random.nextDouble() - 0.5) * 4,
					1, 0, 0, 0, random.nextDouble());
		}
	}

	// ----- Every tick -----

	static void tick(ServerLevel level) {
		tick++;
		if (tick % 20 == 0) {
			putBack(level);
		}
		tickSnowballs(level);
		tickStage(level);
		for (Iterator<Ball> it = BALLS.iterator(); it.hasNext();) {
			Ball b = it.next();
			b.step(level);
			if (--b.life <= 0 || b.pos.y < HubPark.FLOOR - 30) {
				b.remove(level);
				it.remove();
			}
		}
	}

	static void reset() {
		BALLS.clear();
		song = -1;
	}
}
