package com.caldera.shaders.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin({RenderPipelines.class})
public interface RenderPipelinesAccessor {
   @Accessor("ENTITY_SNIPPET")
   static RenderPipeline.Snippet caldera$entitySnippet() {
      throw new AssertionError();
   }
}
