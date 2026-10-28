package dev.rubymod.game.rift;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** One of the Rift's bosses (the Juggernaut Mech, the Rift Gunship, the Ruby Titan). */
public interface RiftBoss {
	void tick(ServerLevel level, List<ServerPlayer> targets);

	boolean dead();

	void remove(ServerLevel level);

	String debug();

	/** The boss itself (the mech's heart, the gunship, the Titan), or null while it's gone. */
	net.minecraft.world.entity.LivingEntity body(ServerLevel level);

	/** Hurting this right now is pointless (a shield is up). */
	default boolean shielded(Entity e) {
		return false;
	}

	static boolean isBoss(Entity e) {
		return e != null && e.entityTags().contains(JuggernautMech.BOSS_TAG);
	}
}
