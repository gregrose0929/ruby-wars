package dev.rubymod.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Gifts, like the sponsors' parachutes in the Hunger Games: on Battle Day, someone who's been knocked out can still
 * help. Every few minutes they can send a small gift (golden apples, ammo, grenades) to anyone still in, who's told
 * who it's from. So nobody knocked out early just sits and watches.
 */
public final class Gifts {
	/** How often each knocked-out player may send one. */
	private static final long EVERY = 3 * 60_000L;
	/** And how often one player may get one (so a few at once don't pile onto the same person). */
	private static final long RECEIVE_EVERY = 60_000L;

	private static final Map<UUID, Long> SENT = new HashMap<>();
	private static final Map<UUID, Long> GOT = new HashMap<>();
	/** Knocked-out players to tell when they can send again. */
	private static final Map<UUID, Long> REMIND = new HashMap<>();
	private static long skew;

	private Gifts() {
	}

	private static long now() {
		return System.currentTimeMillis() + skew;
	}

	/** Everyone still in the battle and online in Ruby Wars. */
	private static List<ServerPlayer> standing(MinecraftServer server) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!Rules.isEliminated(p.getUUID()) && Hub.where(p) == Hub.Where.RUBY_WARS && !p.isSpectator()) {
				out.add(p);
			}
		}
		return out;
	}

	/** Just knocked out (or able to send again): "send a gift" buttons, one per player still in. */
	public static void offer(MinecraftServer server, ServerPlayer out) {
		if (!Rules.phase().isBattle()) {
			return;
		}
		List<ServerPlayer> in = standing(server);
		if (in.isEmpty()) {
			return;
		}
		MutableComponent line = Component.literal("You're out, but you can still help: send a gift (golden apples, ammo, grenades) to someone still in. ")
				.withStyle(ChatFormatting.GOLD);
		for (ServerPlayer p : in.subList(0, Math.min(8, in.size()))) {
			String name = p.getName().getString();
			line.append(Component.literal("[" + name + "] ").withStyle(style -> style.withColor(ChatFormatting.GREEN).withBold(true)
					.withClickEvent(new ClickEvent.RunCommand("/rubywars gift " + name))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Send " + name + " a gift")))));
		}
		out.sendSystemMessage(line);
	}

	/** /rubywars gift <player>. Returns why not, or null. */
	public static String send(MinecraftServer server, ServerPlayer from, ServerPlayer to) {
		if (!Rules.phase().isBattle()) {
			return "Gifts are for Battle Day.";
		}
		if (!Rules.isEliminated(from.getUUID())) {
			return "Gifts are for players who've been knocked out: you're still in!";
		}
		if (Rules.isEliminated(to.getUUID()) || to.isSpectator() || Hub.where(to) != Hub.Where.RUBY_WARS) {
			return to.getName().getString() + " isn't in the battle.";
		}
		long t = now();
		Long last = SENT.get(from.getUUID());
		if (last != null && t - last < EVERY) {
			return "You can send another gift in " + (EVERY - (t - last) + 999) / 1000 + "s.";
		}
		Long got = GOT.get(to.getUUID());
		if (got != null && t - got < RECEIVE_EVERY) {
			return to.getName().getString() + " just got a gift: try again in " + (RECEIVE_EVERY - (t - got) + 999) / 1000 + "s, or pick someone else.";
		}
		SENT.put(from.getUUID(), t);
		GOT.put(to.getUUID(), t);
		REMIND.put(from.getUUID(), t + EVERY);
		List<ItemStack> gift = pick(to.level().getRandom());
		List<String> names = new ArrayList<>();
		for (ItemStack stack : gift) {
			names.add(stack.getCount() + " " + dev.rubymod.item.GeodeItem.plural(stack.getHoverName().getString(), stack.getCount()));
			if (!to.getInventory().add(stack.copy())) {
				to.drop(stack.copy(), false, net.minecraft.util.Prediction.SERVER_ONLY);
			}
		}
		if (to.level() instanceof ServerLevel level) {
			level.sendParticles(ParticleTypes.FIREWORK, to.getX(), to.getY() + 2.5, to.getZ(), 30, 0.4, 0.4, 0.4, 0.08);
			level.playSound(null, to.blockPosition(), SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 0.8F, 1.3F);
		}
		String what = String.join(" and ", names);
		Alerts.toast(to, "minecraft:golden_apple", "A gift from " + from.getName().getString(), what, Alerts.GOLD);
		from.sendSystemMessage(Component.literal("Sent " + to.getName().getString() + " " + what + ". You can send another in "
				+ EVERY / 60_000L + " minutes.").withStyle(ChatFormatting.GREEN));
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p != to && p != from && Hub.inRubyWarsWorld(p)) {
				p.sendSystemMessage(Component.literal(from.getName().getString() + " sent " + to.getName().getString() + " a gift.").withStyle(ChatFormatting.GRAY));
			}
		}
		return null;
	}

	/** Two of: golden apples, a stack of Heavy Ammo, frag grenades, smoke grenades, a rocket launcher's rockets. */
	static List<ItemStack> pick(RandomSource random) {
		List<ItemStack> all = new ArrayList<>(List.of(
				new ItemStack(Items.GOLDEN_APPLE, 2),
				new ItemStack(ModItems.HEAVY_AMMO, 32),
				new ItemStack(ModItems.FRAG_GRENADE, 3),
				new ItemStack(ModItems.SMOKE_GRENADE, 2),
				new ItemStack(ModItems.ROCKET, 3)));
		java.util.Collections.shuffle(all, new java.util.Random(random.nextLong()));
		return all.subList(0, 2);
	}

	/** Once a second: knocked-out players who can send again are told so, with the buttons. */
	public static void tick(MinecraftServer server) {
		if (REMIND.isEmpty() || server.getTickCount() % 20 != 0) {
			return;
		}
		if (!Rules.phase().isBattle()) {
			reset();
			return;
		}
		long t = now();
		var it = REMIND.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			if (t >= e.getValue()) {
				it.remove();
				ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
				if (p != null) {
					offer(server, p);
				}
			}
		}
	}

	// ----- For tests -----

	public static void advance(long ms) {
		skew += ms;
	}

	public static void reset() {
		SENT.clear();
		GOT.clear();
		REMIND.clear();
		skew = 0;
	}
}
