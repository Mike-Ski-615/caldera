package com.caldera.shaders.mixin;

import com.caldera.shaders.graph.NativePackRuntime;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({SkyRenderer.class})
public abstract class SkyRendererMixin {
   @Inject(
      method = {"renderSunMoonAndStars"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void caldera$renderCustomCelestialOrbit(CallbackInfo ci) {
      if (NativePackRuntime.replacesEnvironment(false)) {
         ci.cancel();
      }
   }
}
