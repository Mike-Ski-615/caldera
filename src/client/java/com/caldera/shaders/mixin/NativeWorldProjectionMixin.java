package com.caldera.shaders.mixin;

import com.caldera.shaders.graph.NativePackRuntime;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin({GameRenderer.class})
public abstract class NativeWorldProjectionMixin {
   @ModifyArg(
      method = {"renderLevel"},
      at = {@At(
   value = "INVOKE",
   target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
)},
      index = 0
   )
   private Matrix4f caldera$captureWorldProjection(Matrix4f projection) {
      NativePackRuntime.captureWorldProjection(projection);
      return projection;
   }

   @ModifyArg(
      method = {"render3dHud"},
      at = {@At(
   value = "INVOKE",
   target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lnet/minecraft/client/renderer/Projection;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
)},
      index = 0
   )
   private Projection caldera$captureHandProjection(Projection projection) {
      NativePackRuntime.captureHandProjection(projection.getMatrix(new Matrix4f()));
      return projection;
   }
}
