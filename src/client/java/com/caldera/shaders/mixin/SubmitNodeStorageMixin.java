package com.caldera.shaders.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.caldera.shaders.graph.NativePackRuntime;
import com.caldera.shaders.render.shadow.ShadowService;
import com.caldera.shaders.runtime.ShaderRuntime;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({SubmitNodeStorage.class})
public abstract class SubmitNodeStorageMixin {
   @Inject(
      method = {"submitShadow"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void caldera$disableVanillaBlobShadows(PoseStack poseStack, float shadowRadius, List<EntityRenderState.ShadowPiece> shadowPieces, CallbackInfo ci) {
      // 两问相与，顺序与原先那个 ShadowService.enabled() 一致：先质量，后帧就绪。
      if (!ShaderRuntime.resourceReloading() && NativePackRuntime.shadowQuality() > 0 && NativePackRuntime.shadowFrameReady()) {
         ci.cancel();
      }

   }
}
