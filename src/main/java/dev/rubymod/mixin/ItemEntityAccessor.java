package dev.rubymod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.item.ItemEntity;

/** An item's age (it vanishes at five minutes): conveyor belts keep it young while it rides. */
@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
	@Accessor("age")
	void rubymod$setAge(int age);
}
