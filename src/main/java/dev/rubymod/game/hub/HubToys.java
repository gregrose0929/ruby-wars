package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * More toys from Tilly: a kite that flies behind you, a bubble wand, a pogo stick, a frisbee, water balloons, a
 * sparkler, a whoopee cushion and a beach ball. Like the other toys, they stay in the hub, and nothing ever hurts.
 */
final class HubToys {
	private HubToys() {
	}

	static final String KITE = "Kite";
	static final String BUBBLES = "Bubble Wand";
	static final String POGO = "Pogo Stick";
	static final String FRISBEE = "Frisbee";
	static final String BALLOON = "Water Balloon";
	static final String SPARKLER = "Sparkler";
	static final String CUSHION = "Whoopee Cushion";
	static final String BEACH_BALL = "Beach Ball";
	static final String GRAPPLE = "Grappling Hook";
	static final String PAINT = "Paint Splat Gun";
	static final String BOOTS = "Bouncy Boots";
	static final String MORPH = "Morph Wand";

	/** Tilly's three bundles. */
	static List<ItemStack> bundle(String key) {
		List<ItemStack> out = new ArrayList<>();
		switch (key) {
			case "outdoor" -> {
				out.add(HubGames.toy(Items.LEAD, 1, KITE, ChatFormatting.AQUA));
				out.add(HubGames.toy(Items.BOWL, 1, FRISBEE, ChatFormatting.RED));
				out.add(HubGames.toy(Items.HEART_OF_THE_SEA, 1, BEACH_BALL, ChatFormatting.YELLOW));
				out.add(HubGames.toy(Items.BAMBOO, 1, POGO, ChatFormatting.GREEN));
			}
			case "silly" -> {
				out.add(HubGames.toy(Items.AMETHYST_SHARD, 1, BUBBLES, ChatFormatting.LIGHT_PURPLE));
				out.add(HubGames.toy(Items.DYE.pick(DyeColor.BLUE), 16, BALLOON, ChatFormatting.BLUE));
				out.add(HubGames.toy(Items.BLAZE_ROD, 1, SPARKLER, ChatFormatting.GOLD));
				out.add(HubGames.toy(Items.DYE.pick(DyeColor.PINK), 1, CUSHION, ChatFormatting.LIGHT_PURPLE));
			}
			case "gadgets" -> {
				out.add(HubGames.toy(Items.TRIPWIRE_HOOK, 1, GRAPPLE, ChatFormatting.GRAY));
				out.add(HubGames.toy(Items.GLOW_INK_SAC, 1, PAINT, ChatFormatting.LIGHT_PURPLE));
				out.add(HubGames.toy(Items.RABBIT_FOOT, 1, BOOTS, ChatFormatting.GREEN));
				out.add(HubGames.toy(Items.BREEZE_ROD, 1, MORPH, ChatFormatting.AQUA));
				out.add(HubGames.toy(Items.SPYGLASS, 1, Photos.CAMERA, ChatFormatting.GOLD));
			}
			default -> {
			}
		}
		return out;
	}

	private static boolean is(ItemStack s, String name) {
		return HubGames.isToy(s) && s.getHoverName().getString().equals(name);
	}

	// ----- Things in the air -----

	/** A kite flying behind someone: the kite, and the dots of its string. */
	private record Kite(UUID kite, List<UUID> string) {
	}

	private static final Map<UUID, Kite> KITES = new HashMap<>();

	private static final class Thing {
		UUID display;
		Vec3 pos;
		Vec3 vel;
		int life;
		UUID owner;
		String kind;
		float spin;
	}

	private static final List<Thing> THINGS = new ArrayList<>();
	private static final Map<UUID, Vec3> BALLOONS = new HashMap<>();
	private static int tick;

	private static Display.BlockDisplay block(ServerLevel level, Vec3 at, BlockState state, float sx, float sy, float sz) {
		Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d == null) {
			return null;
		}
		d.snapTo(at.x, at.y, at.z, 0, 0);
		((BlockDisplayAccessor) d).rubymod$setBlockState(state);
		((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(-sx / 2, -sy / 2, -sz / 2), new Quaternionf(), new Vector3f(sx, sy, sz),
				new Quaternionf()));
		((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(2);
		d.addTag(HubRides.TEMP);
		HubPark.live(d);
		level.addFreshEntity(d);
		return d;
	}

	/** Right-click with one of these toys. True if it was one. */
	static boolean use(ServerPlayer p, ItemStack held) {
		if (Butterflies.swish(p, held) || Photos.snap(p, held)) {
			return true;
		}
		ServerLevel level = (ServerLevel) p.level();
		if (is(held, KITE)) {
			Kite k = KITES.remove(p.getUUID());
			if (k != null) {
				HubPark.discard(level, k.kite());
				for (UUID id : k.string()) {
					HubPark.discard(level, id);
				}
				p.sendOverlayMessage(Component.literal("You reel the kite in.").withStyle(ChatFormatting.AQUA));
				return true;
			}
			Display.BlockDisplay kite = block(level, p.position().add(0, 6, 0), Blocks.WOOL.pick(DyeColor.RED).defaultBlockState(), 1.3F, 1.3F, 0.08F);
			if (kite == null) {
				return true;
			}
			List<UUID> string = new ArrayList<>();
			for (int i = 0; i < 10; i++) {
				Display.BlockDisplay dot = block(level, p.position(), i % 3 == 2 ? Blocks.WOOL.pick(DyeColor.YELLOW).defaultBlockState()
						: Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState(), 0.07F, 0.07F, 0.07F);
				if (dot != null) {
					string.add(dot.getUUID());
				}
			}
			KITES.put(p.getUUID(), new Kite(kite.getUUID(), string));
			p.sendOverlayMessage(Component.literal("Up it goes! Run about and it follows. (Right-click again to reel it in.)").withStyle(ChatFormatting.AQUA));
			level.playSound(null, p.blockPosition(), SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, 0.4F, 1.6F);
			return true;
		}
		if (is(held, BUBBLES)) {
			if (p.getCooldowns().isOnCooldown(held)) {
				return true;
			}
			p.getCooldowns().addCooldown(held, 6);
			var random = level.getRandom();
			for (int i = 0; i < 5; i++) {
				Vec3 at = p.getEyePosition().add(p.getLookAngle().scale(1.2));
				float size = 0.25F + random.nextFloat() * 0.3F;
				Display.BlockDisplay b = block(level, at, Blocks.STAINED_GLASS.pick(i % 2 == 0 ? DyeColor.LIGHT_BLUE : DyeColor.PINK).defaultBlockState(), size, size,
						size);
				if (b != null) {
					((DisplayAccessor) b).rubymod$setBrightness(new net.minecraft.util.Brightness(15, 15));
					Thing t = new Thing();
					t.display = b.getUUID();
					t.pos = at;
					t.vel = p.getLookAngle().scale(0.08 + random.nextDouble() * 0.06).add((random.nextDouble() - 0.5) * 0.06, 0.02, (random.nextDouble() - 0.5) * 0.06);
					t.life = 50 + random.nextInt(60);
					t.kind = "bubble";
					THINGS.add(t);
				}
			}
			level.playSound(null, p.blockPosition(), SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT, SoundSource.PLAYERS, 0.8F, 1.6F);
			return true;
		}
		if (is(held, FRISBEE) || is(held, BEACH_BALL)) {
			boolean ball = is(held, BEACH_BALL);
			for (Thing t : THINGS) {
				if (p.getUUID().equals(t.owner) && t.kind.equals(ball ? "beachball" : "frisbee")) {
					return true; // one in the air at a time
				}
			}
			Vec3 at = p.getEyePosition().add(p.getLookAngle().scale(0.8));
			Display.BlockDisplay d = ball ? block(level, at, Blocks.WOOL.pick(DyeColor.YELLOW).defaultBlockState(), 0.9F, 0.9F, 0.9F)
					: block(level, at, Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState(), 0.8F, 0.12F, 0.8F);
			if (d == null) {
				return true;
			}
			Thing t = new Thing();
			t.display = d.getUUID();
			t.pos = at;
			t.vel = ball ? p.getLookAngle().scale(0.6).add(0, 0.25, 0) : p.getLookAngle().multiply(1, 0.3, 1).normalize().scale(0.75).add(0, 0.05, 0);
			t.life = ball ? 600 : 300;
			t.owner = p.getUUID();
			t.kind = ball ? "beachball" : "frisbee";
			THINGS.add(t);
			level.playSound(null, p.blockPosition(), SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.8F, ball ? 0.6F : 1.2F);
			return true;
		}
		if (is(held, BALLOON)) {
			Snowball s = new Snowball(level, p, new ItemStack(Items.DYE.pick(DyeColor.BLUE)));
			s.shootFromRotation(p, p.getXRot(), p.getYRot(), 0, 1.1F, 1.0F);
			level.addFreshEntity(s);
			BALLOONS.put(s.getUUID(), s.position());
			held.shrink(1);
			level.playSound(null, p.blockPosition(), SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.8F, 0.8F);
			return true;
		}
		if (is(held, CUSHION)) {
			for (Thing t : THINGS) {
				if (p.getUUID().equals(t.owner) && t.kind.equals("cushion")) {
					p.sendOverlayMessage(Component.literal("Your cushion's already down somewhere... sneaky!").withStyle(ChatFormatting.LIGHT_PURPLE));
					return true;
				}
			}
			Vec3 at = p.position().add(p.getLookAngle().multiply(1, 0, 1).normalize().scale(1.5)).add(0, 0.12, 0);
			Display.BlockDisplay d = block(level, at, Blocks.WOOL.pick(DyeColor.PINK).defaultBlockState(), 0.7F, 0.18F, 0.7F);
			if (d != null) {
				Thing t = new Thing();
				t.display = d.getUUID();
				t.pos = at;
				t.vel = Vec3.ZERO;
				t.life = 20 * 60;
				t.owner = p.getUUID();
				t.kind = "cushion";
				THINGS.add(t);
				p.sendOverlayMessage(Component.literal("Cushion down. Now wait for someone to sit on it...").withStyle(ChatFormatting.LIGHT_PURPLE));
			}
			return true;
		}
		if (is(held, GRAPPLE)) {
			return grapple(p, held);
		}
		if (is(held, PAINT)) {
			return paint(p, held);
		}
		if (is(held, MORPH)) {
			return morph(p);
		}
		return is(held, POGO) || is(held, SPARKLER) || is(held, BOOTS);
	}

	// ----- Gadgets -----

	/** Grappling hook: hook onto whatever you're looking at (up to 32 blocks) and get pulled to it. */
	private static boolean grapple(ServerPlayer p, ItemStack held) {
		if (p.getCooldowns().isOnCooldown(held) || p.isPassenger()) {
			return true;
		}
		ServerLevel level = (ServerLevel) p.level();
		Vec3 eye = p.getEyePosition();
		var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, eye.add(p.getLookAngle().scale(32)), net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, p));
		if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
			p.sendOverlayMessage(Component.literal("Nothing to hook onto there!").withStyle(ChatFormatting.GRAY));
			return true;
		}
		p.getCooldowns().addCooldown(held, 20);
		Vec3 to = hit.getLocation().subtract(p.position());
		double dist = to.length();
		Vec3 pull = to.normalize().scale(Math.min(2.4, 0.9 + dist * 0.08)).add(0, 0.45, 0);
		p.setDeltaMovement(pull);
		p.syncVelocity = true;
		GRAPPLED.put(p.getUUID(), 0);
		for (double d = 0; d < dist; d += 0.7) {
			Vec3 q = eye.add(to.normalize().scale(d));
			level.sendParticles(new DustParticleOptions(0xFF9A9AA6, 0.6F), q.x, q.y - 0.3, q.z, 1, 0, 0, 0, 0);
		}
		level.playSound(null, p.blockPosition(), SoundEvents.FISHING_BOBBER_THROW, SoundSource.PLAYERS, 1.0F, 0.7F);
		level.playSound(null, BlockPos.containing(hit.getLocation()), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1.0F, 1.4F);
		return true;
	}

	private static final Map<UUID, Integer> GRAPPLED = new HashMap<>();

	/** Painted blocks (painted only for everyone's eyes: the real block is still there), and when they go back. */
	private record Paint(BlockPos pos, int until) {
	}

	private static final List<Paint> PAINTED = new ArrayList<>();

	/** Paint splat gun: splat! A blob of bright paint where it lands (for a few seconds: it washes off). */
	private static boolean paint(ServerPlayer p, ItemStack held) {
		if (p.getCooldowns().isOnCooldown(held)) {
			return true;
		}
		p.getCooldowns().addCooldown(held, 8);
		ServerLevel level = (ServerLevel) p.level();
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getLookAngle();
		var random = level.getRandom();
		DyeColor[] colors = {DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.LIME, DyeColor.LIGHT_BLUE, DyeColor.MAGENTA, DyeColor.PINK, DyeColor.PURPLE};
		DyeColor c = colors[random.nextInt(colors.length)];
		int rgb = 0xFF000000 | c.getTextureDiffuseColor();
		// A friend in the way gets splatted instead.
		for (ServerPlayer q : level.players()) {
			if (q == p) {
				continue;
			}
			Vec3 to = q.position().add(0, 1, 0).subtract(eye);
			double along = to.dot(look);
			if (along > 0 && along < 24 && to.subtract(look.scale(along)).length() < 0.8) {
				level.sendParticles(new DustParticleOptions(rgb, 1.5F), q.getX(), q.getY() + 1, q.getZ(), 30, 0.3, 0.6, 0.3, 0);
				q.sendOverlayMessage(Component.literal("SPLAT! " + p.getName().getString() + " got you!").withStyle(ChatFormatting.LIGHT_PURPLE));
				level.playSound(null, q.blockPosition(), SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 1.0F, 1.2F);
				return true;
			}
		}
		var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, eye.add(look.scale(24)), net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, p));
		level.playSound(null, p.blockPosition(), SoundEvents.SLIME_SQUISH_SMALL, SoundSource.PLAYERS, 0.8F, 1.6F);
		if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
			return true;
		}
		BlockPos centre = ((net.minecraft.world.phys.BlockHitResult) hit).getBlockPos();
		BlockState paintState = Blocks.CONCRETE.pick(c).defaultBlockState();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 2 || random.nextInt(5) == 0) {
						continue;
					}
					BlockPos b = centre.offset(dx, dy, dz);
					if (!level.getBlockState(b).isSolidRender()) {
						continue;
					}
					for (ServerPlayer q : level.players()) {
						if (q.position().distanceTo(Vec3.atCenterOf(b)) < 96) {
							q.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(b, paintState));
						}
					}
					PAINTED.add(new Paint(b, tick + 120));
				}
			}
		}
		Vec3 at = hit.getLocation();
		level.sendParticles(new DustParticleOptions(rgb, 1.4F), at.x, at.y, at.z, 24, 0.4, 0.4, 0.4, 0);
		level.playSound(null, centre, SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 1.0F, 1.0F);
		return true;
	}

	/** Who's morphed into what (the animal goes where they go; they're invisible), and until when. */
	private record Morph(UUID mob, int until) {
	}

	private static final Map<UUID, Morph> MORPHS = new HashMap<>();
	private static final net.minecraft.world.entity.EntityType<?>[] SHAPES = {EntityTypes.CHICKEN, EntityTypes.PIG, EntityTypes.CAT, EntityTypes.SLIME,
		EntityTypes.SHEEP, EntityTypes.FOX, EntityTypes.RABBIT, EntityTypes.FROG, EntityTypes.PANDA, EntityTypes.PARROT};

	/** Morph wand: turn into an animal for a minute (right-click again to change back). */
	private static boolean morph(ServerPlayer p) {
		ServerLevel level = (ServerLevel) p.level();
		if (unmorph(level, p)) {
			p.sendOverlayMessage(Component.literal("Poof! You're you again.").withStyle(ChatFormatting.AQUA));
			return true;
		}
		var type = SHAPES[level.getRandom().nextInt(SHAPES.length)];
		Entity e = type.create(level, EntitySpawnReason.EVENT);
		if (!(e instanceof net.minecraft.world.entity.Mob m)) {
			return true;
		}
		m.snapTo(p.getX(), p.getY(), p.getZ(), p.getYRot(), 0);
		m.setNoAi(true);
		m.setSilent(true);
		m.setPermanentlyInvulnerable(true);
		m.noPhysics = true;
		m.setNoGravity(true);
		if (m instanceof net.minecraft.world.entity.monster.cubemob.Slime s) {
			s.setSize(2, false);
		}
		// Named after you (not shown): your own game hides your animal while you look out through its eyes.
		m.setCustomName(Component.literal(p.getName().getString()));
		m.setCustomNameVisible(false);
		m.addTag(HubRides.TEMP);
		HubPark.live(m);
		level.addFreshEntity(m);
		MORPHS.put(p.getUUID(), new Morph(m.getUUID(), tick + 60 * 20));
		p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY, 60 * 20, 0, false, false));
		level.sendParticles(ParticleTypes.POOF, p.getX(), p.getY() + 1, p.getZ(), 20, 0.4, 0.6, 0.4, 0.02);
		level.playSound(null, p.blockPosition(), SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 0.8F, 1.4F);
		p.sendOverlayMessage(Component.literal("Poof! You're a " + type.getDescription().getString().toLowerCase() + "! (F5 to see yourself)")
				.withStyle(ChatFormatting.AQUA));
		return true;
	}

	private static boolean unmorph(ServerLevel level, ServerPlayer p) {
		Morph m = MORPHS.remove(p.getUUID());
		if (m == null) {
			return false;
		}
		HubPark.discard(level, m.mob());
		p.removeEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY);
		level.sendParticles(ParticleTypes.POOF, p.getX(), p.getY() + 1, p.getZ(), 20, 0.4, 0.6, 0.4, 0.02);
		return true;
	}

	private static final Map<UUID, Boolean> JUMP_HELD = new HashMap<>();
	private static final java.util.Set<UUID> DOUBLE_USED = new java.util.HashSet<>();

	private static boolean has(ServerPlayer p, String name) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (is(inv.getItem(i), name)) {
				return true;
			}
		}
		return false;
	}

	private static void tickGadgets(ServerLevel level) {
		for (ServerPlayer p : level.players()) {
			// Bouncy boots: jump again in mid-air.
			boolean jump = p.getLastClientInput().jump();
			boolean was = JUMP_HELD.getOrDefault(p.getUUID(), false);
			JUMP_HELD.put(p.getUUID(), jump);
			if (p.onGround()) {
				DOUBLE_USED.remove(p.getUUID());
			} else if (jump && !was && !p.isPassenger() && !p.isFallFlying() && !DOUBLE_USED.contains(p.getUUID()) && has(p, BOOTS)) {
				DOUBLE_USED.add(p.getUUID());
				Vec3 f = p.getLookAngle().multiply(1, 0, 1);
				f = f.lengthSqr() < 1e-4 ? Vec3.ZERO : f.normalize().scale(0.35);
				p.setDeltaMovement(p.getDeltaMovement().x + f.x, 0.75, p.getDeltaMovement().z + f.z);
				p.syncVelocity = true;
				p.resetFallDistance();
				level.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY(), p.getZ(), 8, 0.3, 0.05, 0.3, 0.02);
				level.playSound(null, p.blockPosition(), SoundEvents.SLIME_JUMP, SoundSource.PLAYERS, 0.7F, 1.6F);
			}
			if (!p.onGround() && has(p, BOOTS)) {
				p.resetFallDistance();
			}
		}
		// The grappling hook: no bump when you land.
		for (var it = GRAPPLED.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
			e.setValue(e.getValue() + 1);
			if (p == null || e.getValue() > 200 || p.onGround() && e.getValue() > 5) {
				it.remove();
			} else {
				p.resetFallDistance();
			}
		}
		// Paint washes off.
		for (var it = PAINTED.iterator(); it.hasNext();) {
			Paint pt = it.next();
			if (pt.until() <= tick) {
				it.remove();
				BlockState real = level.getBlockState(pt.pos());
				for (ServerPlayer q : level.players()) {
					if (q.position().distanceTo(Vec3.atCenterOf(pt.pos())) < 110) {
						q.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(pt.pos(), real));
					}
				}
			}
		}
		// Morphed: your animal goes where you go.
		for (var it = MORPHS.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
			Entity m = level.getEntity(e.getValue().mob());
			if (p == null || p.level() != level || m == null || e.getValue().until() <= tick) {
				it.remove();
				HubPark.discard(level, e.getValue().mob());
				if (p != null) {
					p.removeEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY);
					if (p.level() == level) {
						p.sendOverlayMessage(Component.literal("Poof! The magic's worn off.").withStyle(ChatFormatting.AQUA));
					}
				}
				continue;
			}
			m.teleportTo(p.getX(), p.getY(), p.getZ());
			m.setYRot(p.getYRot());
			m.setYHeadRot(p.getYHeadRot());
			if (m instanceof net.minecraft.world.entity.LivingEntity le) {
				le.yBodyRot = p.yBodyRot;
			}
			m.setXRot(p.getXRot());
		}
	}


	static void tick(ServerLevel level) {
		tick++;
		tickGadgets(level);
		// Toys you just hold: the pogo stick bounces you, the sparkler sparkles.
		for (ServerPlayer p : level.players()) {
			ItemStack held = p.getMainHandItem();
			if (is(held, POGO) && p.onGround() && !p.isPassenger() && !p.isShiftKeyDown()) {
				p.setDeltaMovement(p.getDeltaMovement().x * 1.3, 0.72, p.getDeltaMovement().z * 1.3);
				p.syncVelocity = true;
				p.resetFallDistance();
				level.playSound(null, p.blockPosition(), SoundEvents.SLIME_JUMP, SoundSource.PLAYERS, 0.5F, 1.4F);
			}
			if (is(held, SPARKLER) && tick % 2 == 0) {
				Vec3 tip = p.getEyePosition().add(p.getLookAngle().scale(0.7)).add(p.getLookAngle().cross(new Vec3(0, 1, 0)).normalize().scale(-0.35)).add(0, -0.25, 0);
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, tip.x, tip.y, tip.z, 4, 0.08, 0.08, 0.08, 0.15);
				level.sendParticles(new DustParticleOptions(0xFFFFE070, 0.6F), tip.x, tip.y, tip.z, 2, 0.1, 0.1, 0.1, 0);
				if (tick % 20 == 0) {
					level.playSound(null, BlockPos.containing(tip), SoundEvents.FIREWORK_ROCKET_TWINKLE_FAR, SoundSource.PLAYERS, 0.3F, 1.8F);
				}
			}
		}
		// Kites: up behind you and swaying, on a string from your hand.
		for (Iterator<Map.Entry<UUID, Kite>> it = KITES.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
			Kite k = e.getValue();
			boolean holding = p != null && p.level() == level && (is(p.getMainHandItem(), KITE) || is(p.getOffhandItem(), KITE));
			if (!holding) {
				HubPark.discard(level, k.kite());
				for (UUID id : k.string()) {
					HubPark.discard(level, id);
				}
				it.remove();
				continue;
			}
			Vec3 back = p.getLookAngle().multiply(1, 0, 1);
			back = back.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : back.normalize().scale(-1);
			double sway = Math.sin(tick * 0.07 + p.getId()) * 1.6;
			Vec3 side = new Vec3(-back.z, 0, back.x);
			Vec3 hand = p.position().add(0, 1.2, 0);
			Vec3 at = hand.add(back.scale(7)).add(side.scale(sway)).add(0, 8 + Math.sin(tick * 0.05) * 0.8, 0);
			Entity kite = level.getEntity(k.kite());
			if (kite != null) {
				kite.teleportTo(at.x, at.y, at.z);
				kite.setYRot((float) Math.toDegrees(Math.atan2(-back.x, back.z)));
				kite.setXRot(-30);
			}
			for (int i = 0; i < k.string().size(); i++) {
				double t = (i + 1) / (double) (k.string().size() + 1);
				Vec3 q = hand.lerp(at, t).add(0, -Math.sin(Math.PI * t) * 1.2, 0);
				Entity dot = level.getEntity(k.string().get(i));
				if (dot != null) {
					dot.teleportTo(q.x, q.y, q.z);
				}
			}
			if (tick % 10 == 0) {
				level.sendParticles(new DustParticleOptions(0xFFFF60A0, 0.8F), at.x, at.y - 1, at.z, 2, 0.2, 0.2, 0.2, 0);
			}
		}
		// Bubbles, frisbees, beach balls, cushions.
		for (Iterator<Thing> it = THINGS.iterator(); it.hasNext();) {
			Thing t = it.next();
			Entity d = level.getEntity(t.display);
			if (d == null || --t.life <= 0) {
				if (t.kind.equals("bubble") && d != null) {
					level.sendParticles(ParticleTypes.BUBBLE_POP, t.pos.x, t.pos.y, t.pos.z, 4, 0.1, 0.1, 0.1, 0.02);
				}
				HubPark.discard(level, t.display);
				it.remove();
				continue;
			}
			switch (t.kind) {
				case "bubble" -> {
					t.vel = t.vel.scale(0.97).add(Math.sin(tick * 0.2 + t.life) * 0.004, 0.003, Math.cos(tick * 0.2 + t.life) * 0.004);
					t.pos = t.pos.add(t.vel);
					if (t.life == 1) {
						level.playSound(null, BlockPos.containing(t.pos), SoundEvents.BUBBLE_POP, SoundSource.PLAYERS, 0.6F, 1.4F);
					}
				}
				case "frisbee" -> {
					// Glides, curving a little, sinking slowly; whoever's near when it comes down catches it.
					t.vel = t.vel.scale(0.985).add(0, -0.006, 0);
					t.vel = t.vel.add(new Vec3(-t.vel.z, 0, t.vel.x).scale(0.012));
					Vec3 next = t.pos.add(t.vel);
					if (!level.getBlockState(BlockPos.containing(next)).isAir()) {
						t.vel = Vec3.ZERO;
					} else {
						t.pos = next;
					}
					t.spin += 30;
					for (ServerPlayer q : level.players()) {
						if (q.getUUID().equals(t.owner) && t.life > 280) {
							continue;
						}
						if (q.getEyePosition().distanceTo(t.pos) < 1.6 || q.position().distanceTo(t.pos) < 1.4) {
							t.life = 0;
							HubGames.hand(q, HubGames.toy(Items.BOWL, 1, FRISBEE, ChatFormatting.RED));
							q.sendOverlayMessage(Component.literal(q.getUUID().equals(t.owner) ? "Got it back!" : "Nice catch!").withStyle(ChatFormatting.GREEN));
							level.playSound(null, q.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8F, 1.0F);
							if (!q.getUUID().equals(t.owner)) {
								ServerPlayer o = level.getServer().getPlayerList().getPlayer(t.owner);
								if (o != null) {
									takeOne(o, FRISBEE);
								}
							}
							break;
						}
					}
				}
				case "beachball" -> {
					t.vel = t.vel.add(0, -0.03, 0).scale(0.985);
					Vec3 next = t.pos.add(t.vel);
					if (!level.getBlockState(BlockPos.containing(next.x, next.y - 0.45, next.z)).isAir()) {
						t.vel = new Vec3(t.vel.x * 0.85, Math.abs(t.vel.y) > 0.08 ? -t.vel.y * 0.7 : 0, t.vel.z * 0.85);
						if (Math.abs(t.vel.y) > 0.1) {
							level.playSound(null, BlockPos.containing(t.pos), SoundEvents.SLIME_JUMP_SMALL, SoundSource.PLAYERS, 0.6F, 1.2F);
						}
					} else if (!level.getBlockState(BlockPos.containing(next)).isAir()) {
						t.vel = new Vec3(-t.vel.x * 0.6, t.vel.y, -t.vel.z * 0.6);
					} else {
						t.pos = next;
					}
					// Anyone who runs into it knocks it on (and up).
					for (ServerPlayer q : level.players()) {
						Vec3 off = t.pos.subtract(q.position().add(0, 0.9, 0));
						if (off.length() < 1.3 && q.getDeltaMovement().horizontalDistance() > 0.01) {
							Vec3 push = new Vec3(off.x, 0, off.z).normalize().scale(0.45).add(0, 0.35, 0);
							t.vel = push.add(q.getDeltaMovement().scale(0.5));
							level.playSound(null, BlockPos.containing(t.pos), SoundEvents.WOOL_HIT, SoundSource.PLAYERS, 1.0F, 1.3F);
						}
					}
					if (t.pos.y < FLOOR - 30) {
						t.life = 0;
					}
					t.spin += (float) t.vel.horizontalDistance() * 40;
				}
				case "cushion" -> {
					for (ServerPlayer q : level.players()) {
						if (!q.getUUID().equals(t.owner) && q.position().distanceTo(t.pos) < 0.9) {
							t.life = 0;
							level.playSound(null, BlockPos.containing(t.pos), SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), SoundSource.PLAYERS, 1.5F, 0.55F);
							level.playSound(null, BlockPos.containing(t.pos), SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), SoundSource.PLAYERS, 1.2F, 0.5F);
							level.sendParticles(ParticleTypes.CLOUD, t.pos.x, t.pos.y + 0.2, t.pos.z, 12, 0.3, 0.1, 0.3, 0.05);
							q.sendOverlayMessage(Component.literal("PFFFFFRRRT! Oops!").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
							ServerPlayer o = level.getServer().getPlayerList().getPlayer(t.owner);
							if (o != null) {
								o.sendOverlayMessage(Component.literal(q.getName().getString() + " sat on your whoopee cushion!").withStyle(ChatFormatting.LIGHT_PURPLE));
							}
							break;
						}
					}
				}
				default -> {
				}
			}
			d.teleportTo(t.pos.x, t.pos.y, t.pos.z);
			if (!t.kind.equals("cushion")) {
				d.setYRot(t.spin % 360);
			}
		}
		// Water balloons: SPLOOSH where they land.
		for (Iterator<Map.Entry<UUID, Vec3>> it = BALLOONS.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			Entity s = level.getEntity(e.getKey());
			if (s != null && s.isAlive()) {
				e.setValue(s.position());
				continue;
			}
			Vec3 at = e.getValue();
			level.sendParticles(ParticleTypes.SPLASH, at.x, at.y + 0.3, at.z, 40, 0.5, 0.3, 0.5, 0.3);
			level.sendParticles(ParticleTypes.FALLING_WATER, at.x, at.y + 1, at.z, 20, 0.6, 0.4, 0.6, 0.05);
			level.playSound(null, BlockPos.containing(at), SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 1.0F, 1.3F);
			for (ServerPlayer q : level.players()) {
				if (q.position().distanceTo(at) < 2.2) {
					q.sendOverlayMessage(Component.literal("SPLOOSH! You're soaked!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
				}
			}
			it.remove();
		}
	}

	private static void takeOne(ServerPlayer p, String name) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (is(inv.getItem(i), name)) {
				inv.getItem(i).shrink(1);
				p.inventoryMenu.broadcastChanges();
				return;
			}
		}
	}

	static void reset() {
		KITES.clear();
		THINGS.clear();
		BALLOONS.clear();
	}
}
