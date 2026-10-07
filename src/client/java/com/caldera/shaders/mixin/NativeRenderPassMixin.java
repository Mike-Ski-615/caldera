package com.caldera.shaders.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.caldera.shaders.graph.GraphShaderSources;
import com.caldera.shaders.graph.NativePackRuntime;
import java.util.List;
import java.util.Optional;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({FrontendRenderPass.class})
public abstract class NativeRenderPassMixin {
   @Shadow
   @Final
   private List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments;
   @Unique
   private RenderPipeline caldera$pipeline;

   @ModifyVariable(
      method = {"setPipeline"},
      at = {@At("HEAD")},
      argsOnly = true
   )
   private CompiledRenderPipeline caldera$nativeScenePipeline(CompiledRenderPipeline compiled) {
      RenderPipeline original = GraphShaderSources.original(compiled);
      this.caldera$pipeline = original == null ? null : NativePackRuntime.scenePipeline(original, this.colorAttachments);
      return this.caldera$pipeline != null && this.caldera$pipeline != original ? RenderSystem.getCompiledPipeline(this.caldera$pipeline) : compiled;
   }

   @Inject(
      method = {"setPipeline"},
      at = {@At("RETURN")}
   )
   private void caldera$nativeFrameUniforms(CompiledRenderPipeline pipeline, CallbackInfo ci) {
      if (this.caldera$pipeline != null) {
         NativePackRuntime.bindSceneUniforms((RenderPass)(Object)this, this.caldera$pipeline);
      }

   }
}
