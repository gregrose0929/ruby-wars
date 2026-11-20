package dev.rubymod.entity;

import dev.rubymod.game.Hub;
import dev.rubymod.game.Rules;
import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
 * The Tunnel Borer: a slow one-seat digging machine. Drive forward and its drill cuts a tunnel three blocks wide and
 * three high; look down to dig downwards, up to dig upwards. It digs wherever a pickaxe could: claims only protect
 * twelve blocks under their Heart, so it can tunnel in under an enemy base (and it's noisy: a Seismic Sensor hears it
 * coming). Only in the Ruby Wars world.
 */
public class TunnelBorerEntity extends VehicleEntity {
	/** Game time it last cut rock (sensors listen for that). */
	private long lastDrill = -1000;

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
			sp.sendSystemMessage(Component.literal("Tunnel Borer: W drives and drills. Look down to dig down, up to dig up. "
					+ "Claims are only safe twelve blocks under their Heart.").withStyle(ChatFormatting.GOLD));
		}
		return r;
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		return new Vec3(0, dimensions.height() * 0.55, -0.5).yRot((float) Math.toRadians(-getYRot()));
	}

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel level) || !(getControllingPassenger() instanceof ServerPlayer driver)) {
			return;
		}
		// (The driver's client moves the machine; the server knows the keys from their last input.)
		if (!driver.getLastClientInput().forward() || tickCount % 4 != 0) {
			return;
		}
		if (!Hub.inRubyWarsWorld(driver)) {
			if (tickCount % 40 == 0) {
				driver.sendOverlayMessage(Component.literal("The Tunnel Borer only digs in the Ruby Wars world.").withStyle(ChatFormatting.YELLOW));
			}
			return;
		}
		drill(level, driver, driver.getXRot());
	}

	/** For the self-test: one bite of the drill, as this driver, looking this far down. How many blocks it cut. */
	public int testDrill(ServerPlayer driver, float pitch) {
		return drill((ServerLevel) level(), driver, pitch);
	}

	/** Cut the rock in front: three wide, three high, a block lower (or higher) if the driver's looking down (or up). */
	private int drill(ServerLevel level, ServerPlayer driver, float pitch) {
		double yaw = Math.toRadians(getYRot());
		Vec3 ahead = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		Vec3 side = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
		int lift = pitch > 25 ? -1 : pitch < -25 ? 1 : 0;
		Vec3 face = position().add(ahead.scale(getBbWidth() / 2 + 0.8));
		int cut = 0;
		boolean blocked = false;
		for (int s = -1; s <= 1; s++) {
			for (int h = 0; h <= 2; h++) {
				BlockPos p = BlockPos.containing(face.add(side.scale(s)).add(0, h + lift + 0.2, 0));
				BlockState state = level.getBlockState(p);
				if (state.isAir() || !state.getFluidState().isEmpty() && state.getBlock() == state.getFluidState().createLegacyBlock().getBlock()) {
					continue;
				}
				float hardness = state.getDestroySpeed(level, p);
				if (hardness < 0 || hardness > 30 || state.is(Blocks.BEDROCK)) {
					blocked = true;
					continue;
				}
				// The same rule as a pickaxe: a claim's rock is safe (above its depth) unless it's yours, or it's Battle Day.
				if (!Rules.canBuild(driver, level, p) || state.is(dev.rubymod.registry.ModBlocks.RUBY_HEART)) {
					blocked = true;
					continue;
				}
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.1);
				level.destroyBlock(p, false, this);
				cut++;
			}
		}
		if (cut > 0) {
			dev.rubymod.game.Goals.add(level.getServer(), driver.getUUID(), "dig", cut);
			lastDrill = level.getGameTime();
			if (tickCount % 8 == 0) {
				level.playSound(null, getX(), getY(), getZ(), SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 2.5F, 0.5F);
				level.playSound(null, getX(), getY(), getZ(), SoundEvents.DEEPSLATE_BREAK, SoundSource.BLOCKS, 2.5F, 0.6F);
			}
		}
		if (blocked && tickCount % 40 == 0) {
			driver.sendOverlayMessage(Component.literal("The drill can't get through here: a claim's protected rock, or something too hard.")
					.withStyle(ChatFormatting.YELLOW));
		}
		return cut;
	}
}
