package com.caldera.shaders.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.caldera.shaders.render.shadow.DirectionalShadowPipelines;
import com.caldera.shaders.render.shadow.ShadowPassScope;
import com.caldera.shaders.runtime.ShaderRuntime;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({PreparedRenderType.class})
public abstract class RenderTypeMixin {
   @Inject(
      method = {"draw"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void caldera$drawEntityShadowCaster(StagedVertexBuffer.ExecuteInfo info, RenderPass pass, RenderPipeline pipeline, CallbackInfo ci) {
      if (!ShaderRuntime.resourceReloading() && ShadowPassScope.active()) {
         PreparedRenderType prepared = (PreparedRenderType)(Object)this;
         RenderPipeline caster = DirectionalShadowPipelines.entityDepthPipeline(pipeline);
         ci.cancel();
         if (caster != null) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(caster));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", prepared.dynamicTransforms());
            pass.setUniform("CalderaCascade", ShadowPassScope.uniforms());
            pass.setVertexBuffer(0, info.vertexBuffer().slice());

            for(PreparedRenderType.Texture texture : prepared.textures()) {
               if ("Sampler0".equals(texture.name())) {
                  pass.setUniform(texture.name(), texture.textureView(), texture.sampler());
               }
            }

            pass.setIndexBuffer(info.indexBuffer(), info.indexType());
            pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
         }
      }
   }
}
