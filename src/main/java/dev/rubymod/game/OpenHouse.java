package dev.rubymod.game;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Open House: while you build, open your base for visitors and everyone gets a button to come and look round (and a
 * button to go back to where they were). Like a family server's "build your dream house, then tours". Visitors can't
 * build, break or open anything there (it's still your claim), and it's your choice: showing your base shows where it
 * is. Every base closes when Battle Day starts.
 */
public final class OpenHouse {
	private static final Set<UUID> OPEN = new HashSet<>();

	private record Spot(ResourceKey<Level> dim, Vec3 pos, float yaw, float pitch) {
	}

	/** Where each visitor was before they came. */
	private static final Map<UUID, Spot> BACK = new HashMap<>();

	private OpenHouse() {
	}

	public static boolean isOpen(UUID owner) {
		return OPEN.contains(owner);
	}

	/** /rubywars openhouse: open or close your base. Returns the message. */
	public static String toggle(MinecraftServer server, ServerPlayer owner) {
		if (Rules.phase().isBattle() || Rules.phase() == Phase.ENDED) {
			return "Open House is for the build phase.";
		}
		SeasonData.Claim claim = SeasonData.get().claimOf(owner.getUUID());
		if (claim == null || claim.destroyed) {
			return "Place your Ruby Heart first: Open House shows people your base.";
		}
		if (OPEN.remove(owner.getUUID())) {
			return "Your base is closed to visitors again.";
		}
		OPEN.add(owner.getUUID());
		String name = owner.getName().getString();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p != owner) {
				p.sendSystemMessage(Component.literal("🏠 " + name + "'s base is open for visitors! ").withStyle(ChatFormatting.AQUA)
						.append(Component.literal("[Visit]").withStyle(style -> style.withColor(ChatFormatting.GREEN).withBold(true)
								.withClickEvent(new ClickEvent.RunCommand("/rubywars visit " + name))
								.withHoverEvent(new HoverEvent.ShowText(Component.literal("Go and look round " + name + "'s base"))))));
			}
		}
		return "Your base is open for visitors: everyone's been sent a button to come and look. /rubywars openhouse again to close it.";
	}

	/** /rubywars visit <player>. Returns why not, or null. */
	public static String visit(MinecraftServer server, ServerPlayer visitor, ServerPlayer owner) {
		if (Rules.phase().isBattle() || Rules.phase() == Phase.ENDED) {
			return "Visiting is for the build phase.";
		}
		if (!OPEN.contains(owner.getUUID())) {
			return owner.getName().getString() + "'s base isn't open for visitors right now.";
		}
		SeasonData.Claim claim = SeasonData.get().claimOf(owner.getUUID());
		if (claim == null || claim.destroyed) {
			return owner.getName().getString() + " has no base right now.";
		}
		if (Hub.where(visitor) != Hub.Where.RUBY_WARS) {
			return "Go to Ruby Wars first (/play rubywars), then visit.";
		}
		ServerLevel level = server.overworld();
		if (!BACK.containsKey(visitor.getUUID())) {
			BACK.put(visitor.getUUID(), new Spot(visitor.level().dimension(), visitor.position(), visitor.getYRot(), visitor.getXRot()));
		}
		// By the front of the Heart, facing it.
		double x = claim.x + 0.5;
		double z = claim.z - 3.5;
		double y = Hub.safeY(level, x, claim.pos().getY(), z);
		visitor.teleportTo(level, x, y, z, java.util.Set.<Relative>of(), 0, 10, true);
		visitor.sendSystemMessage(Component.literal("Visiting " + owner.getName().getString() + "'s base. Look round! ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal("[Go back]").withStyle(style -> style.withColor(ChatFormatting.GREEN).withBold(true)
						.withClickEvent(new ClickEvent.RunCommand("/rubywars visit back"))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Back to where you were"))))));
		owner.sendSystemMessage(Component.literal(visitor.getName().getString() + " is visiting your base.").withStyle(ChatFormatting.GRAY));
		return null;
	}

	/** /rubywars visit back. Returns why not, or null. */
	public static String back(MinecraftServer server, ServerPlayer visitor) {
		Spot spot = BACK.remove(visitor.getUUID());
		if (spot == null) {
			return "You're not visiting anyone.";
		}
		ServerLevel level = server.getLevel(spot.dim());
		if (level == null) {
			level = server.overworld();
		}
		visitor.teleportTo(level, spot.pos().x, spot.pos().y, spot.pos().z, java.util.Set.<Relative>of(), spot.yaw(), spot.pitch(), true);
		visitor.sendSystemMessage(Component.literal("Back where you were.").withStyle(ChatFormatting.GRAY));
		return null;
	}

	/** Battle Day: every base closes (visitors stay where they are: it's their battle too). */
	public static void tick(MinecraftServer server) {
		if (!OPEN.isEmpty() && (Rules.phase().isBattle() || Rules.phase() == Phase.ENDED)) {
			OPEN.clear();
			BACK.clear();
		}
	}

	public static void reset() {
		OPEN.clear();
		BACK.clear();
	}
}
