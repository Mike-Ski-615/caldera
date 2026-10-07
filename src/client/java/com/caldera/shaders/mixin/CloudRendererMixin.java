package com.caldera.shaders.mixin;

import com.caldera.shaders.graph.NativePackRuntime;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({CloudRenderer.class})
public abstract class CloudRendererMixin {
   @Inject(
      method = {"render"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void caldera$renderVolumetricClouds(CallbackInfo ci) {
      if (NativePackRuntime.replacesEnvironment(true)) {
         ci.cancel();
      }
   }
}
