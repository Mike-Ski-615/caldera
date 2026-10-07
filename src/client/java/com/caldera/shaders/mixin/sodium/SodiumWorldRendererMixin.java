package com.caldera.shaders.mixin.sodium;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.caldera.shaders.graph.NativePackRuntime;
import com.caldera.shaders.render.shadow.ShadowPassScope;
import com.caldera.shaders.runtime.ShaderRuntime;
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
      if (group == ChunkSectionLayerGroup.TRANSLUCENT && !ShaderRuntime.resourceReloading() && !ShadowPassScope.active()) {
         NativePackRuntime.captureTerrain();
      }
   }

   @Inject(
      method = {"drawChunkLayer"},
      at = {@At("RETURN")}
   )
   private void caldera$captureTranslucentDepth(RenderPass renderPass, ChunkSectionLayerGroup group, ChunkRenderMatrices matrices, double x, double y, double z, GpuSampler sampler, OitStage stage, CallbackInfo ci) {
      if (group == ChunkSectionLayerGroup.TRANSLUCENT && !ShadowPassScope.active()) {
         NativePackRuntime.captureTranslucentDepth();
      }

   }
}