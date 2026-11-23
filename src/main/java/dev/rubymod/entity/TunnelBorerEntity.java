package dev.rubymod.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.rubymod.game.Hub;
import dev.rubymod.game.Rules;
import dev.rubymod.net.Net;
import dev.rubymod.net.Payloads;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Tunnel Borer: a slow one-seat digging machine on tracks. Hold W and its drill spins up, then grinds into the rock
 * ahead (you can see it cracking), three blocks wide and three high: soft dirt goes in a moment, stone takes a little
 * longer, deep rock longer still, and the whole machine shudders while it works. Look down to dig downwards, up to dig
 * upwards. It digs wherever a pickaxe could: claims only protect twelve blocks under their Heart, so it can tunnel in
 * under an enemy base (and it's noisy: a Seismic Sensor hears it coming). Only in the Ruby Wars world.
 */
public class TunnelBorerEntity extends VehicleEntity {
	private static final EntityDataAccessor<Float> SPIN = SynchedEntityData.defineId(TunnelBorerEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Boolean> GRINDING = SynchedEntityData.defineId(TunnelBorerEntity.class, EntityDataSerializers.BOOLEAN);

	/** How much of a block (at hardness 1) the drill grinds away in a tick at full speed. */
	private static final float POWER = 0.22F;

	/** Game time it last ground rock (sensors listen for that). */
	private long lastDrill = -1000;
	/** How far through each block ahead the drill has got (0 to 1). */
	private final Map<BlockPos, Float> progress = new HashMap<>();
	/** Where each of the nine bits of the drill face is showing its cracks. */
	private final BlockPos[] cracks = new BlockPos[9];
	/** The tracks: 0 stopped, 1 full ahead, negative backing up. Eases, like a heavy machine. */
	private float drive;

	public TunnelBorerEntity(EntityType<? extends TunnelBorerEntity> type, Level level) {
		super(type, level);
	}

	public static AttributeSupplier.Builder attributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 100.0)
				.add(Attributes.MOVEMENT_SPEED, 0.07)
				.add(Attributes.STEP_HEIGHT, 1.1)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
				.add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(SPIN, 0.0F);
		builder.define(GRINDING, false);
	}

	/** How fast the drill's turning, 0 to 1 (for the model). */
	public float spin() {
		return entityData.get(SPIN);
	}

	/** Is it biting into rock right now (for the model's shudder)? */
	public boolean grinding() {
		return entityData.get(GRINDING);
	}

	@Override
	public String vehicleName() {
		return "Tunnel Borer";
	}

	@Override
	public ItemStack asItem() {
		return new ItemStack(ModItems.TUNNEL_BORER);
	}

	/** For the self-test (its stand-in players can't ride): who counts as driving. */
	public Player testDriver;

	/** Who's driving (null: nobody). */
	public Player driver() {
		return getControllingPassenger() instanceof Player p ? p : testDriver;
	}

	/** Has it been drilling in the last couple of seconds? */
	public boolean drilling() {
		return level().getGameTime() - lastDrill < 40;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return getPassengers().isEmpty();
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		InteractionResult r = super.mobInteract(player, hand);
		if (r.consumesAction() && player instanceof ServerPlayer sp) {
			sp.sendSystemMessage(Component.literal("Tunnel Borer: hold W and the drill spins up and grinds through the rock ahead (harder rock takes longer). "
					+ "Look down to dig down, up to dig up. Claims are only safe twelve blocks under their Heart.").withStyle(ChatFormatting.GOLD));
		}
		return r;
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		return new Vec3(0, dimensions.height() * 0.55, -0.5).yRot((float) Math.toRadians(-getYRot()));
	}

	// ----- Driving: heavy, on tracks -----

	@Override
	protected Vec3 getRiddenInput(Player controller, Vec3 selfInput) {
		float want = controller.zza > 0 ? 1.0F : controller.zza < 0 ? -0.5F : 0.0F;
		// The tracks take a moment to get going, and stop quickly.
		drive = want == 0 ? Mth.approach(drive, 0, 0.12F) : Mth.approach(drive, want, Math.signum(want) != Math.signum(drive) && drive != 0 ? 0.12F : 0.05F);
		return new Vec3(0, 0, drive == 0 ? 0 : Math.signum(drive));
	}

	@Override
	protected float getRiddenSpeed(Player controller) {
		return (float) getAttributeValue(Attributes.MOVEMENT_SPEED) * Math.abs(drive);
	}

	@Override
	protected void tickRidden(Player controller, Vec3 riddenInput) {
		// Tracks turn it on the spot, slowly (it's a heavy thing).
		float yaw = getYRot() - Mth.clamp(controller.xxa, -1, 1) * 2.5F;
		setYRot(yaw);
		yBodyRot = yaw;
		yHeadRot = yaw;
	}

	// ----- The drill -----

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel level)) {
			return;
		}
		ServerPlayer driver = getControllingPassenger() instanceof ServerPlayer p ? p : null;
		if (driver == null) {
			drive = 0;
		}
		boolean forward = driver != null && driver.getLastClientInput().forward();
		boolean here = driver != null && Hub.inRubyWarsWorld(driver);
		// The drill spins up while W's held (about a second) and winds down when it's let go.
		float spin = Mth.approach(spin(), forward && here ? 1.0F : 0.0F, forward && here ? 0.04F : 0.025F);
		if (spin != spin()) {
			entityData.set(SPIN, spin);
		}
		boolean bit = false;
		if (forward && !here) {
			if (tickCount % 40 == 0) {
				driver.sendOverlayMessage(Component.literal("The Tunnel Borer only digs in the Ruby Wars world.").withStyle(ChatFormatting.YELLOW));
			}
		} else if (forward && spin > 0.35F) {
			bit = grind(level, driver, driver.getXRot(), spin) >= 0;
		}
		if (!bit) {
			clearCracks(level);
			progress.clear();
		}
		if (bit != grinding()) {
			entityData.set(GRINDING, bit);
		}
	}

	/** For the self-test: the drill grinding away at full speed, as this driver, looking this far down, until the rock ahead is through. How many blocks it cut. */
	public int testDrill(ServerPlayer driver, float pitch) {
		ServerLevel level = (ServerLevel) level();
		int total = 0;
		for (int i = 0; i < 200; i++) {
			int cut = grind(level, driver, pitch, 1.0F);
			if (cut < 0) {
				break;
			}
			total += cut;
		}
		clearCracks(level);
		progress.clear();
		return total;
	}

	/** The nine blocks of rock in front: three wide, three high, a block lower (or higher) if the driver's looking down (or up). */
	private List<BlockPos> face(float pitch) {
		double yaw = Math.toRadians(getYRot());
		Vec3 ahead = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		Vec3 side = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
		int lift = pitch > 25 ? -1 : pitch < -25 ? 1 : 0;
		Vec3 front = position().add(ahead.scale(getBbWidth() / 2 + 0.8));
		List<BlockPos> out = new ArrayList<>(9);
		for (int s = -1; s <= 1; s++) {
			for (int h = 0; h <= 2; h++) {
				out.add(BlockPos.containing(front.add(side.scale(s)).add(0, h + lift + 0.2, 0)));
			}
		}
		return out;
	}

	/**
	 * One tick of the drill biting into the rock ahead: each block it can cut gets ground a bit further (by how hard it
	 * is) and breaks when it's through. How many broke this tick, or -1 if there was nothing it could get its teeth into.
	 */
	private int grind(ServerLevel level, ServerPlayer driver, float pitch, float spin) {
		List<BlockPos> face = face(pitch);
		progress.keySet().retainAll(face);
		int cut = 0;
		boolean working = false;
		boolean blocked = false;
		BlockState dust = null;
		for (int i = 0; i < face.size(); i++) {
			BlockPos p = face.get(i);
			BlockState state = level.getBlockState(p);
			if (state.isAir() || !state.getFluidState().isEmpty() && state.getBlock() == state.getFluidState().createLegacyBlock().getBlock()) {
				crack(level, i, null, 0);
				continue;
			}
			float hardness = state.getDestroySpeed(level, p);
			// The same rule as a pickaxe: a claim's rock is safe (above its depth) unless it's yours, or it's Battle Day.
			if (hardness < 0 || hardness > 30 || state.is(Blocks.BEDROCK) || !Rules.canBuild(driver, level, p)
					|| state.is(dev.rubymod.registry.ModBlocks.RUBY_HEART)) {
				blocked = true;
				crack(level, i, null, 0);
				continue;
			}
			working = true;
			dust = state;
			float done = progress.getOrDefault(p, 0.0F) + POWER * spin / Mth.clamp(hardness, 0.4F, 6.0F);
			if (done >= 1.0F) {
				progress.remove(p);
				crack(level, i, null, 0);
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0.1);
				level.destroyBlock(p, false, this);
				cut++;
			} else {
				progress.put(p, done);
				crack(level, i, p, (int) (done * 10));
			}
		}
		if (working) {
			lastDrill = level.getGameTime();
			// Grit spraying off the drill, the grinding, and the machine shaking under its driver.
			if (dust != null && tickCount % 2 == 0) {
				Vec3 at = Vec3.atCenterOf(face.get(4));
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, dust), at.x, at.y, at.z, 4, 0.8, 0.8, 0.8, 0.15);
			}
			if (tickCount % 8 == 0) {
				level.playSound(null, getX(), getY(), getZ(), SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 2.5F, 0.5F);
			}
			if (cut > 0 && tickCount % 3 == 0) {
				level.playSound(null, getX(), getY(), getZ(), SoundEvents.DEEPSLATE_BREAK, SoundSource.BLOCKS, 2.0F, 0.6F);
			}
			if (getControllingPassenger() == driver && tickCount % 10 == 0) {
				Net.send(driver, new Payloads.Shake(0.3F, 12));
			}
		}
		if (cut > 0) {
			dev.rubymod.game.Goals.add(level.getServer(), driver.getUUID(), "dig", cut);
		}
		if (blocked && !working && tickCount % 40 == 0) {
			driver.sendOverlayMessage(Component.literal("The drill can't get through here: a claim's protected rock, or something too hard.")
					.withStyle(ChatFormatting.YELLOW));
		}
		return working ? cut : -1;
	}

	/** Show (or with null, take away) the cracks on one bit of the face. */
	private void crack(ServerLevel level, int slot, BlockPos at, int stage) {
		int id = -1000 - getId() * 9 - slot;
		if (cracks[slot] != null && !cracks[slot].equals(at)) {
			level.destroyBlockProgress(id, cracks[slot], -1);
		}
		cracks[slot] = at;
		if (at != null) {
			level.destroyBlockProgress(id, at, Mth.clamp(stage, 0, 9));
		}
	}

	private void clearCracks(ServerLevel level) {
		for (int i = 0; i < cracks.length; i++) {
			if (cracks[i] != null) {
				crack(level, i, null, 0);
			}
		}
	}

	@Override
	public void remove(RemovalReason reason) {
		if (level() instanceof ServerLevel level) {
			clearCracks(level);
		}
		super.remove(reason);
	}
}
