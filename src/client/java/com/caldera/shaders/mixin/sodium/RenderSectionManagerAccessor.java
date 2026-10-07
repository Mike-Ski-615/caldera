package com.caldera.shaders.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(
   value = {RenderSectionManager.class},
   remap = false
)
public interface RenderSectionManagerAccessor {
   @Accessor("regions")
   RenderRegionManager caldera$regions();
}
