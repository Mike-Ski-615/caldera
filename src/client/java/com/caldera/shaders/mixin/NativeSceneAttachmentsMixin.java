package com.caldera.shaders.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.caldera.shaders.graph.NativePackRuntime;
import com.caldera.shaders.graph.SceneRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({FrontendCommandEncoder.class})
public abstract class NativeSceneAttachmentsMixin {
   @ModifyVariable(
      method = {"createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;"},
      at = {@At("HEAD")},
      argsOnly = true
   )
   private RenderPassDescriptor caldera$sceneTargets(RenderPassDescriptor descriptor) {
      return NativePackRuntime.sceneAttachments(descriptor);
   }

   @Inject(
      method = {"createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private void caldera$splitScenePass(RenderPassDescriptor descriptor, CallbackInfoReturnable<RenderPass> cir) {
      cir.setReturnValue(SceneRenderPass.wrap((RenderPass)cir.getReturnValue(), descriptor));
   }
}
