package com.caldera.shaders.mixin.sodium;

import com.caldera.shaders.render.shadow.SodiumShadowTerrainRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(
   value = {DefaultTerrainRenderPasses.class},
   remap = false
)
public abstract class ShadowPassIndexMixin {
   @Inject(
      method = {"getPassIndex"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private static void caldera$shadowPassIndex(TerrainRenderPass pass, CallbackInfoReturnable<Integer> ci) {
      int index = SodiumShadowTerrainRenderer.shadowPassIndex(pass);
      if (index >= 0) {
         ci.setReturnValue(index);
      }

   }
}
