package com.caldera.shaders.mixin.sodium;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.caldera.shaders.graph.NativePackRuntime;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
   value = {SodiumWorldRenderer.class},
   remap = false
)
public abstract class SodiumWorldRendererMixin {
   @Inject(
      method = {"drawChunkLayer"},
      at = {@At("HEAD")}
   )
   private void caldera$captureSceneBeforeSodiumTranslucent(RenderPass renderPass, ChunkSectionLayerGroup group, ChunkRenderMatrices matrices, double cameraX, double cameraY, double cameraZ, GpuSampler sampler, OitStage stage, CallbackInfo ci) {
      // 只剩游戏侧那一项：`!资源重载中 && !正在画阴影贴图` 原先写在这里，与模块自己的五项门闸隔着
      // 两个文件组成同一个合取。现在它归 SceneFrame.captureTerrain() 一处管——那边条件一字未改。
      if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
         NativePackRuntime.captureTerrain();
      }
   }

   @Inject(
      method = {"drawChunkLayer"},
      at = {@At("RETURN")}
   )
   private void caldera$captureTranslucentDepth(RenderPass renderPass, ChunkSectionLayerGroup group, ChunkRenderMatrices matrices, double x, double y, double z, GpuSampler sampler, OitStage stage, CallbackInfo ci) {
      // 同上。这一处原先连 `!资源重载中` 都没写——那一条一直是由模块兜住的。
      if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
         NativePackRuntime.captureTranslucentDepth();
      }

   }
}
