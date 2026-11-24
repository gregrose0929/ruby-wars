package dev.rubymod.game;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import it.unimi.dsi.fastutil.ints.IntList;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Victory fireworks, in your own colours (a cosmetic, picked on the profile screen): a few go up when you win a duel
 * or a party game, and a big show over the hub (and where you are) when you win a Ruby Wars season. Just for show:
 * they go off high up, well clear of anyone.
 */
public final class Celebrations {
	private record Shot(ResourceKey<Level> level, Vec3 at, ItemStack rocket, int tick) {
	}

	private static final List<Shot> QUEUE = new ArrayList<>();

	private Celebrations() {
	}

	/** The rocket for a firework cosmetic ("" for none). */
	public static ItemStack rocket(String id) {
		List<FireworkExplosion> bursts = switch (id) {
			case "fw_geode" -> List.of(burst(FireworkExplosion.Shape.STAR, ints(0xA060FF, 0xD0A0FF, 0x60E0FF), ints(0xFFFFFF), false, true),
					burst(FireworkExplosion.Shape.SMALL_BALL, ints(0xE0203A, 0xFFD040), ints(0xA060FF), true, true));
			case "fw_ruby" -> List.of(burst(FireworkExplosion.Shape.LARGE_BALL, ints(0xE0203A, 0xFFFFFF), ints(0xFF8090), true, false));
			case "fw_stars" -> List.of(burst(FireworkExplosion.Shape.STAR, ints(0xFFD040, 0xFFF0A0), ints(0xFFFFFF), false, true));
			case "fw_ocean" -> List.of(burst(FireworkExplosion.Shape.BURST, ints(0x2080FF, 0x40E0D0), ints(0xA0F0FF), false, true));
			case "fw_creeper" -> List.of(burst(FireworkExplosion.Shape.CREEPER, ints(0x40C040), ints(0x205020), false, false));
			case "fw_frost" -> List.of(burst(FireworkExplosion.Shape.SMALL_BALL, ints(0xFFFFFF, 0xA0D8FF), ints(0xFFFFFF), true, true));
			case "fw_rainbow" -> List.of(burst(FireworkExplosion.Shape.LARGE_BALL, ints(0xFF3030, 0xFF9020, 0xFFE030, 0x40D040, 0x3070FF, 0xA040FF),
					ints(0xFFFFFF), true, false));
			case "fw_duelist" -> List.of(burst(FireworkExplosion.Shape.BURST, ints(0xFF6020, 0xFFD040), ints(0xFF2000), true, false));
			case "fw_fortress" -> List.of(burst(FireworkExplosion.Shape.STAR, ints(0x40A0FF, 0xFF4040), ints(0xFFD040), true, false),
					burst(FireworkExplosion.Shape.SMALL_BALL, ints(0xFF4D88), ints(0xFFFFFF), false, true));
			case "fw_champion" -> List.of(burst(FireworkExplosion.Shape.LARGE_BALL, ints(0xFFD040, 0xE0203A), ints(0xFFF0A0), true, true),
					burst(FireworkExplosion.Shape.STAR, ints(0xFFFFFF), ints(0xFFD040), false, true));
			default -> List.of();
		};
		if (bursts.isEmpty()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
		stack.set(DataComponents.FIREWORKS, new Fireworks(2, bursts));
		return stack;
	}

	private static FireworkExplosion burst(FireworkExplosion.Shape shape, IntList colors, IntList fades, boolean trail, boolean twinkle) {
		return new FireworkExplosion(shape, colors, fades, trail, twinkle);
	}

	private static IntList ints(int... colors) {
		return IntList.of(colors);
	}

	/** Won a duel or a party game: a few rockets around you. */
	public static void win(MinecraftServer server, UUID id) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		if (p == null) {
			return;
		}
		ItemStack rocket = rocket(Stats.get(p).firework);
		if (rocket.isEmpty()) {
			return;
		}
		int now = server.getTickCount();
		for (int i = 0; i < 3; i++) {
			double a = i * Math.PI * 2 / 3;
			QUEUE.add(new Shot(p.level().dimension(), p.position().add(Math.cos(a) * 4, 0, Math.sin(a) * 4), rocket, now + 10 + i * 12));
		}
	}

	/** Found every mineral for the Rock Collection: a ring of rockets round you. */
	public static void rocks(ServerPlayer p) {
		if (!(p.level() instanceof ServerLevel level)) {
			return;
		}
		if (!level.canSeeSky(p.blockPosition().above())) {
			// Underground a rocket would burst against the ceiling, right on top of you: sparkles instead.
			level.sendParticles(net.minecraft.core.particles.ParticleTypes.FIREWORK, p.getX(), p.getY() + 1.2, p.getZ(), 60, 1.2, 0.8, 1.2, 0.08);
			level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, p.getX(), p.getY() + 1.2, p.getZ(), 25, 1.0, 0.8, 1.0, 0.03);
			level.playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.FIREWORK_ROCKET_TWINKLE, net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 1.2F);
			return;
		}
		ItemStack rocket = rocket("fw_geode");
		int now = p.level().getServer().getTickCount();
		for (int i = 0; i < 6; i++) {
			double a = i * Math.PI * 2 / 6;
			QUEUE.add(new Shot(p.level().dimension(), p.position().add(Math.cos(a) * 5, 0, Math.sin(a) * 5), rocket, now + 10 + i * 10));
		}
	}

	/** Won a Ruby Wars season: a show over the hub plaza, and a few where the winner is. */
	public static void season(MinecraftServer server, UUID id) {
		String chosen = Stats.get(id, null).firework;
		ItemStack rocket = rocket(chosen.isEmpty() ? "fw_champion" : chosen);
		ItemStack crown = rocket("fw_champion");
		int now = server.getTickCount();
		for (int i = 0; i < 24; i++) {
			double a = i * Math.PI * 2 / 8;
			double r = 10 + (i % 3) * 4;
			QUEUE.add(new Shot(Hub.HUB, new Vec3(0.5 + Math.cos(a) * r, Hub.FLOOR + 1, 0.5 + Math.sin(a) * r), i % 4 == 3 ? crown : rocket, now + 20 + i * 8));
		}
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		if (p != null) {
			for (int i = 0; i < 6; i++) {
				double a = i * Math.PI * 2 / 6;
				QUEUE.add(new Shot(p.level().dimension(), p.position().add(Math.cos(a) * 5, 0, Math.sin(a) * 5), rocket, now + 10 + i * 10));
			}
		}
	}

	public static void tick(MinecraftServer server) {
		if (QUEUE.isEmpty()) {
			return;
		}
		int now = server.getTickCount();
		Iterator<Shot> it = QUEUE.iterator();
		while (it.hasNext()) {
			Shot s = it.next();
			if (now < s.tick()) {
				continue;
			}
			it.remove();
			ServerLevel level = server.getLevel(s.level());
			if (level != null && level.isLoaded(net.minecraft.core.BlockPos.containing(s.at()))) {
				level.addFreshEntity(new FireworkRocketEntity(level, s.at().x, s.at().y + 1, s.at().z, s.rocket().copy()));
			}
		}
	}
}
