package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.game.Alerts;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Sky Rings: Wren lends you a pair of wings and launches you high over the plaza; glide through the twelve big rings
 * round the whole park (the green ones give you a boost) and land back on the plaza. Your time goes on the records board.
 * The wings go back to Wren when you land.
 */
final class SkyRings {
	private SkyRings() {
	}

	private static final Vec3 PAD = new Vec3(-19.5, FLOOR + 1, -3.5);
	private static final double R = 4.5;
	/** The rings: x, z, height above the floor. */
	private static final double[][] RINGS = {{-8, -36, 62}, {5, -70, 57}, {30, -100, 54}, {62, -110, 49}, {95, -80, 50}, {120, -40, 49}, {125, 0, 46},
		{110, 40, 44}, {75, 62, 40}, {40, 52, 35}, {14, 22, 24}, {1, 4, 15}};
	private static final String WINGS = "Sky Rings Wings";

	private static final class Run {
		int next;
		int ticks = -60;
		Vec3 last;
		int grounded;
		ItemStack chest = ItemStack.EMPTY;
	}

	private static final Map<UUID, Run> RUNS = new HashMap<>();

	private static Vec3 ring(int i) {
		return new Vec3(RINGS[i][0] + 0.5, FLOOR + RINGS[i][2], RINGS[i][1] + 0.5);
	}

	/** The way through ring i (from the one before towards the one after). */
	private static Vec3 through(int i) {
		Vec3 a = i == 0 ? PAD : ring(i - 1);
		Vec3 b = i == RINGS.length - 1 ? ring(i).add(ring(i).subtract(ring(i - 1))) : ring(i + 1);
		Vec3 d = new Vec3(b.x - a.x, 0, b.z - a.z);
		return d.normalize();
	}

	private static boolean boost(int i) {
		return i % 3 == 1;
	}

	static void build(ServerLevel level) {
		RUNS.clear();
		for (int i = 0; i < RINGS.length; i++) {
			Vec3 c = ring(i);
			Vec3 t = through(i);
			Vec3 side = new Vec3(-t.z, 0, t.x);
			BlockState a = Blocks.CONCRETE.pick(boost(i) ? DyeColor.LIME : i % 2 == 0 ? DyeColor.ORANGE : DyeColor.WHITE).defaultBlockState();
			for (int deg = 0; deg < 360; deg += 4) {
				double r = Math.toRadians(deg);
				Vec3 p = c.add(side.scale(Math.cos(r) * R)).add(0, Math.sin(r) * R, 0);
				BlockPos pos = BlockPos.containing(p);
				if (level.getBlockState(pos).isAir()) {
					level.setBlock(pos, deg % 30 == 0 ? (boost(i) ? Blocks.EMERALD_BLOCK : Blocks.SEA_LANTERN).defaultBlockState() : a, 2);
				}
			}
			HubPark.label(level, c.x, c.y + R + 1.2, c.z, boost(i) ? (i + 1) + "  BOOST!" : String.valueOf(i + 1), boost(i) ? ChatFormatting.GREEN : ChatFormatting.GOLD,
					2.0F);
		}
		// The launch pad, and Wren.
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				HubPark.set(level, (int) Math.floor(PAD.x) + dx, FLOOR, (int) Math.floor(PAD.z) + dz,
						dx == 0 && dz == 0 ? Blocks.EMERALD_BLOCK.defaultBlockState() : Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState());
			}
		}
		HubPark.label(level, PAD.x, FLOOR + 3.4, PAD.z, "SKY RINGS\ntalk to Wren", ChatFormatting.GREEN, 0.9F);
		HubHosts.add(level, new Vec3(-16.5, FLOOR + 1, -4.5), 90, VillagerProfession.CARTOGRAPHER, VillagerType.TAIGA,
				new HubHosts.Host("rings", "Wren", "Sky Rings", 0x80FF80, "minecraft:elytra",
						"I'll lend you some wings and launch you WAY up high. Glide through all twelve rings round the park: look down to go "
								+ "faster, up to slow down. The green rings give you a boost! Then land back here. How fast can you do it?",
						"Sky Rings: right-click Wren to fly!", HubHosts.play("Launch me!"), (p, key) -> start(p)));
	}

	private static void start(ServerPlayer p) {
		if (RUNS.containsKey(p.getUUID())) {
			return;
		}
		if (p.isPassenger()) {
			p.stopRiding();
		}
		Run run = new Run();
		run.chest = p.getItemBySlot(EquipmentSlot.CHEST).copy();
		ItemStack wings = new ItemStack(Items.ELYTRA);
		wings.set(DataComponents.CUSTOM_NAME, Component.literal(WINGS).withStyle(ChatFormatting.GREEN));
		wings.set(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
		p.setItemSlot(EquipmentSlot.CHEST, wings);
		p.teleportTo(PAD.x, PAD.y, PAD.z);
		p.setYRot(180);
		p.setDeltaMovement(Vec3.ZERO);
		RUNS.put(p.getUUID(), run);
		p.sendOverlayMessage(Component.literal("Wings on! Stand still on the pad...").withStyle(ChatFormatting.GREEN));
	}

	static void tick(ServerLevel level) {
		for (Iterator<Map.Entry<UUID, Run>> it = RUNS.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
			Run run = e.getValue();
			if (p == null || p.level() != level) {
				it.remove();
				continue;
			}
			run.ticks++;
			if (run.ticks < 0) {
				if (run.ticks % 20 == 0) {
					Alerts.title(p, String.valueOf(-run.ticks / 20), "Look where you want to fly!", ChatFormatting.GREEN);
					level.playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.2F, 1.0F + run.ticks / -60F);
				}
				if (p.position().distanceTo(PAD) > 2.5) {
					end(p, run, "You stepped off the pad! Talk to Wren to try again.");
					it.remove();
				}
				continue;
			}
			if (run.ticks == 0) {
				p.setDeltaMovement(new Vec3(0, 3.7, 0));
				p.syncVelocity = true;
				p.addEffect(new MobEffectInstance(MobEffects.SPEED, 60, 4, false, false));
				Alerts.title(p, "FLY!", "Ring 1 is that way: follow the sparkles", ChatFormatting.GREEN);
				level.playSound(null, p.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 2.0F, 0.7F);
				level.sendParticles(ParticleTypes.FIREWORK, p.getX(), p.getY(), p.getZ(), 40, 0.4, 0.2, 0.4, 0.2);
				run.last = p.position();
				continue;
			}
			// Open the wings once you're falling.
			if (!p.onGround() && !p.isFallFlying() && p.getDeltaMovement().y < -0.2) {
				p.startFallFlying();
			}
			Vec3 now = p.position().add(0, 0.9, 0);
			if (run.next < RINGS.length) {
				Vec3 c = ring(run.next);
				Vec3 t = through(run.next);
				double before = run.last.subtract(c).dot(t);
				double after = now.subtract(c).dot(t);
				Vec3 off = now.subtract(c);
				double lateral = off.subtract(t.scale(off.dot(t))).length();
				if (before < 0 && after >= 0 && lateral < R + 1.0) {
					int i = run.next++;
					level.playSound(null, p.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0F, 0.8F + i * 0.06F);
					// Every ring keeps you up a little; the green ones shove you on.
					Vec3 push = boost(i) ? t.scale(1.1).add(0, 0.45, 0) : t.scale(0.25).add(0, 0.2, 0);
					p.setDeltaMovement(p.getDeltaMovement().add(push));
					p.syncVelocity = true;
					if (boost(i)) {
						p.sendOverlayMessage(Component.literal(">>> BOOST! >>>").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
						level.playSound(null, p.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.0F, 1.4F);
					}
					if (run.next >= RINGS.length) {
						Alerts.title(p, "ALL TWELVE!", "Time: " + Speedway.time(run.ticks) + "  Now land!", ChatFormatting.GOLD);
						Records.submit(level, "Sky Rings", p.getName().getString(), run.ticks, true, Speedway::time);
						HubPark.burst(level, c);
					}
				}
				// Sparkles in the next ring, so you can find it.
				if (run.ticks % 4 == 0 && run.next < RINGS.length) {
					Vec3 n = ring(run.next);
					Vec3 nt = through(run.next);
					Vec3 side = new Vec3(-nt.z, 0, nt.x);
					for (int k = 0; k < 6; k++) {
						double a = run.ticks * 0.1 + k * Math.PI / 3;
						Vec3 q = n.add(side.scale(Math.cos(a) * (R - 0.6))).add(0, Math.sin(a) * (R - 0.6), 0);
						p.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket(ParticleTypes.END_ROD, true, true, q.x, q.y, q.z,
								0.05F, 0.05F, 0.05F, 0.01F, 2));
					}
				}
				if (run.ticks % 10 == 0 && run.next < RINGS.length) {
					p.sendOverlayMessage(Component.literal("Ring " + (run.next + 1) + "/" + RINGS.length + "   " + Speedway.time(run.ticks)).withStyle(ChatFormatting.GREEN));
				}
			}
			run.last = now;
			// Landed: done (or, if you fell short, try again).
			if (p.onGround() || p.isInWater()) {
				if (++run.grounded > 10) {
					end(p, run, run.next >= RINGS.length ? "Great flying! Talk to Wren to go again."
							: "You landed after " + run.next + " of " + RINGS.length + " rings. Talk to Wren to try again!");
					it.remove();
				}
			} else {
				run.grounded = 0;
			}
			if (run.ticks > 20 * 120 || p.getY() < FLOOR - 20) {
				end(p, run, "Talk to Wren to fly again!");
				it.remove();
			}
		}
	}

	/** The wings go back. */
	private static void end(ServerPlayer p, Run run, String message) {
		ItemStack worn = p.getItemBySlot(EquipmentSlot.CHEST);
		if (worn.is(Items.ELYTRA)) {
			p.setItemSlot(EquipmentSlot.CHEST, run.chest);
		}
		p.stopFallFlying();
		p.resetFallDistance();
		p.sendOverlayMessage(Component.literal(message).withStyle(ChatFormatting.GREEN));
	}

	/** Anyone still wearing the park's wings (after a restart) hands them back once they're on the ground. */
	static void tidy(ServerPlayer p) {
		if (!RUNS.containsKey(p.getUUID()) && p.onGround()) {
			ItemStack worn = p.getItemBySlot(EquipmentSlot.CHEST);
			if (worn.is(Items.ELYTRA) && worn.getHoverName().getString().equals(WINGS)) {
				p.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
			}
		}
	}

	static boolean busy() {
		return !RUNS.isEmpty();
	}

	static void reset() {
		RUNS.clear();
	}
}
