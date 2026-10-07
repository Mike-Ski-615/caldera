package com.caldera.shaders.mixin.sodium;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.caldera.shaders.render.shadow.ShadowPassScope;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(
   value = {TerrainRenderPass.class},
   remap = false
)
public abstract class TerrainRenderPassMixin {
   @Inject(
      method = {"getTarget"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void caldera$useShadowTarget(CallbackInfoReturnable<RenderTarget> cir) {
      if (ShadowPassScope.active()) {
         RenderTarget target = ShadowPassScope.target();
         if (target != null) {
            cir.setReturnValue(target);
         }

      }
   }
}
