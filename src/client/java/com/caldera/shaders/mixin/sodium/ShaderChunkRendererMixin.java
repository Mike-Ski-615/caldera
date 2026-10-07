package com.caldera.shaders.mixin.sodium;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.caldera.shaders.render.shadow.DirectionalShadowRenderer;
import com.caldera.shaders.render.shadow.SodiumTerrainShadowPipelines;
import com.caldera.shaders.runtime.ShaderRuntime;
import java.util.IdentityHashMap;
import java.util.Map;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(
   value = {ShaderChunkRenderer.class},
   remap = false
)
public abstract class ShaderChunkRendererMixin {
   @Unique
   private final Map<TerrainRenderPass, RenderPipeline> caldera$programs = new IdentityHashMap();
   @Shadow
   @Final
   protected VertexFormat vertexFormat;

   @Shadow
   private RenderPipeline createShader(String path, TerrainRenderPass pass) {
      throw new AssertionError();
   }

   @Inject(
      method = {"compileProgram"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void caldera$useTerrainShadowPipeline(TerrainRenderPass pass, OitStage stage, CallbackInfoReturnable<RenderPipeline> cir) {
      if (!ShaderRuntime.resourceReloading()) {
         if (DirectionalShadowRenderer.isRenderingShadowMap()) {
            cir.setReturnValue(SodiumTerrainShadowPipelines.caster(pass, this.vertexFormat));
         } else if (stage == null) {
            cir.setReturnValue((RenderPipeline)(Object)this.caldera$programs.computeIfAbsent(pass, (key) -> this.createShader("blocks/block_layer_opaque", key)));
         }

      }
   }
}
