package com.caldera.shaders.mixin;

import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Options.class})
public abstract class OptionsBackendMixin {
   @Inject(
      method = {"<init>"},
      at = {@At("RETURN")}
   )
   private void caldera$preferVulkanOnCleanStartup(CallbackInfo ci) {
      Options options = (Options)(Object)this;
      if (options.startedCleanly && options.preferredGraphicsBackend().get() == PreferredGraphicsApi.DEFAULT) {
         options.preferredGraphicsBackend().set(PreferredGraphicsApi.VULKAN);
      }

   }
}
