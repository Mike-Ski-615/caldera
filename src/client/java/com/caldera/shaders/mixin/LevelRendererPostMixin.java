package com.caldera.shaders.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.caldera.shaders.graph.NativePackRuntime;
import com.caldera.shaders.runtime.ShaderRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({LevelRenderer.class})
public abstract class LevelRendererPostMixin {
   @Shadow
   @Final
   private LevelRenderState levelRenderState;

   @Inject(
      method = {"render"},
      at = {@At("HEAD")}
   )
   private void caldera$prepareVolumetricCloudFrame(GraphicsResourceAllocator resourceAllocator, boolean renderOutline, CameraRenderState cameraState, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired, CallbackInfo ci) {
      if (!ShaderRuntime.resourceReloading()) {
         NativePackRuntime.beginScene(cameraState, cameraState.viewRotationMatrix, this.levelRenderState, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
      }
   }
}
