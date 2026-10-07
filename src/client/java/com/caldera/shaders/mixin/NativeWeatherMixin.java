package com.caldera.shaders.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.caldera.shaders.graph.NativePackRuntime;
import com.caldera.shaders.graph.SceneRenderPass;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({WeatherEffectRenderer.class})
public abstract class NativeWeatherMixin {
   @Unique
   private boolean caldera$renderingWeather;

   @Inject(
      method = {"render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void caldera$weatherLayer(WeatherRenderState state, RenderPass original, CallbackInfo ci) {
      GpuTextureView weather = NativePackRuntime.weatherView();
      if (weather != null && !this.caldera$renderingWeather) {
         ci.cancel();
         SceneRenderPass.outside(() -> {
            GpuTextureView depth = Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();

            try {
               RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Caldera weather", weather, Optional.empty(), depth, OptionalDouble.empty());

               try {
                  this.caldera$renderingWeather = true;
                  ((WeatherEffectRenderer)(Object)this).render(state, pass);
               } catch (Throwable var12) {
                  if (pass != null) {
                     try {
                        pass.close();
                     } catch (Throwable x2) {
                        var12.addSuppressed(x2);
                     }
                  }

                  throw var12;
               }

               if (pass != null) {
                  pass.close();
               }
            } finally {
               this.caldera$renderingWeather = false;
            }

         });
      }
   }
}
