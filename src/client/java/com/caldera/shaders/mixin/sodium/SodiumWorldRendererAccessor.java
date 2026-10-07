package com.caldera.shaders.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(
   value = {SodiumWorldRenderer.class},
   remap = false
)
public interface SodiumWorldRendererAccessor {
   @Accessor("renderSectionManager")
   RenderSectionManager caldera$renderSectionManager();

   @Accessor("uniformBufferManager")
   UniformBufferManager caldera$uniformBufferManager();
}
