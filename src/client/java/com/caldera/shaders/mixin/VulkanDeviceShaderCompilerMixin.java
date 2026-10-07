package com.caldera.shaders.mixin;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.util.ShaderCompileException;
import com.caldera.shaders.graph.GraphShaderSources;
import com.caldera.shaders.runtime.NativeShaderCompiler;
import com.caldera.shaders.runtime.NativeShaderPreprocessor;
import net.minecraft.client.renderer.ShaderDefines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({GlslCompiler.class})
public abstract class VulkanDeviceShaderCompilerMixin {
   @Inject(
      method = {"compileToSpv"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void caldera$compileNative(String name, String source, ShaderType type, ShaderDefines defines, ShaderSource sources, CallbackInfoReturnable<SpvModule> cir) throws ShaderCompileException {
      if (sources instanceof GraphShaderSources.NativeSources nativeSources) {
         if (nativeSources.isNative(type)) {
            try (NativeShaderCompiler compiler = new NativeShaderCompiler()) {
               cir.setReturnValue(compiler.compile(name, NativeShaderPreprocessor.injectDefines(source, defines), type));
            }

            return;
         }
      }

   }
}
