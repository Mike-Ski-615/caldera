package com.caldera.shaders.render.shadow;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.caldera.shaders.config.ShaderQualityPreset;
import com.caldera.shaders.graph.NativePackRuntime;

public final class ShadowService {
   private ShadowService() {
   }

   public static boolean enabled() {
      return NativePackRuntime.shadowQuality() > 0 && NativePackRuntime.shadowFrameReady();
   }

   public static ShaderQualityPreset quality() {
      return NativePackRuntime.shadowQuality() > 0 ? ShaderQualityPreset.values()[NativePackRuntime.shadowQuality()] : ShaderQualityPreset.OFF;
   }

   public static float distance() {
      return NativePackRuntime.shadowQuality() > 0 ? (float)NativePackRuntime.shadowDistance() : 384.0F;
   }

   public static long memoryBytes(int quality) {
      if (quality == 0) {
         return 0L;
      } else {
         long bytes = 560L;
         ShaderQualityPreset preset = ShaderQualityPreset.values()[quality];
         int[] sizes = DirectionalShadowRenderer.targetSizes(preset, Integer.MAX_VALUE);

         for(int size : sizes) {
            bytes += (long)size * (long)size * 5L;
         }

         for(int i = 0; i < 2; ++i) {
            int size = DirectionalShadowRenderer.entityTargetSize(preset, sizes, i);
            bytes += (long)size * (long)size * 5L;
         }

         return bytes;
      }
   }

   public static void layout(BindGroupLayout.Builder layout) {
      layout.withUniform("CalderaShadowData", UniformType.UNIFORM_BUFFER);

      for(int i = 0; i < 4; ++i) {
         layout.withUniform("CalderaShadowMap" + i, UniformType.COMBINED_IMAGE_SAMPLER);
      }

      for(int i = 0; i < 2; ++i) {
         layout.withUniform("CalderaEntityShadowMap" + i, UniformType.COMBINED_IMAGE_SAMPLER);
      }

   }

   public static void bindTerrain(RenderPass pass) {
      DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
      if (!shadows.resourcesReady()) {
         throw new IllegalStateException("Shadow producer did not run before native terrain");
      } else if (shadows.shadowDataSlice().buffer().isClosed()) {
         throw new IllegalStateException("Shadow uniforms expired before their receiver pass");
      } else {
         pass.setUniform("CalderaShadowData", shadows.shadowDataSlice());
         GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

         for(int i = 0; i < 4; ++i) {
            pass.setUniform("CalderaShadowMap" + i, shadows.target(i).getDepthTextureView(), sampler);
         }

         for(int i = 0; i < 2; ++i) {
            pass.setUniform("CalderaEntityShadowMap" + i, shadows.entityTarget(i).getDepthTextureView(), sampler);
         }

      }
   }
}
