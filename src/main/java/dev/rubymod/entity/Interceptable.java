package dev.rubymod.entity;

import dev.rubymod.game.SeasonData;

import net.minecraft.server.level.ServerLevel;

/** Incoming fire that a Missile Interceptor can shoot down. */
public interface Interceptable {
	/** True if this projectile is headed into the claim and wasn't fired by its owner or a trusted player. */
	boolean threatens(SeasonData.Claim claim);

	/** Blow up harmlessly in mid-air. */
	void intercept(ServerLevel level);
}
