package com.caldera.shaders.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.caldera.shaders.graph.GraphShaderSources;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({PipelineCache.class})
public abstract class NativePipelineCacheMixin {
   @Shadow
   @Final
   private ShaderSource shaderSource;

   @Inject(
      method = {"get"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void caldera$nativePipeline(RenderPipeline pipeline, CallbackInfoReturnable<CompiledRenderPipeline> cir) {
      if (GraphShaderSources.isNative(pipeline)) {
         cir.setReturnValue(GraphShaderSources.compile(pipeline, this.shaderSource));
      }

   }

   @Inject(
      method = {"get"},
      at = {@At("RETURN")}
   )
   private void caldera$rememberPipeline(RenderPipeline pipeline, CallbackInfoReturnable<CompiledRenderPipeline> cir) {
      GraphShaderSources.remember(pipeline, (CompiledRenderPipeline)cir.getReturnValue());
   }

   @Inject(
      method = {"insert"},
      at = {@At("HEAD")}
   )
   private void caldera$rememberPrecompiled(RenderPipeline pipeline, CompiledRenderPipeline compiled, CallbackInfo ci) {
      GraphShaderSources.remember(pipeline, compiled);
   }
}
