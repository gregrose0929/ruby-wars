package dev.rubymod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;

/** Lets Ruby Rift's Shadow Clone wear the player's own skin. */
@Mixin(Mannequin.class)
public interface MannequinAccessor {
	@Accessor("DATA_PROFILE")
	static EntityDataAccessor<ResolvableProfile> rubymod$profile() {
		throw new AssertionError();
	}
}
