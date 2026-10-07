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
      // "该不该投射阴影"是门面上一个有名字的判断（质量与帧就绪的合取），这里不再自己拼。
      if (!ShaderRuntime.resourceReloading() && NativePackRuntime.shadowsEnabled()) {
         ci.cancel();
      }

   }
}
