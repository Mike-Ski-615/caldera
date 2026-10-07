package com.caldera.shaders.runtime;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

public final class NativeShaderCompiler implements AutoCloseable {
   private static final int VULKAN_1_2 = 4202496;
   private static final String ENTITY_GEOMETRY_SHADER = "entity_shadow_";
   private static final String SODIUM_TERRAIN_SHADER = "sodium_terrain_";
   private final long compiler = Shaderc.shaderc_compiler_initialize();
   private final long options;
   private boolean closed;

   public NativeShaderCompiler() {
      if (this.compiler == 0L) {
         throw new IllegalStateException("Unable to initialize Caldera's native shader compiler");
      } else {
         this.options = Shaderc.shaderc_compile_options_initialize();
         if (this.options == 0L) {
            Shaderc.shaderc_compiler_release(this.compiler);
            throw new IllegalStateException("Unable to allocate Caldera's shader compiler options");
         } else {
            Shaderc.shaderc_compile_options_set_target_env(this.options, 0, 4202496);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(this.options, true);
            Shaderc.shaderc_compile_options_set_auto_map_locations(this.options, true);
            Shaderc.shaderc_compile_options_set_generate_debug_info(this.options);
            Shaderc.shaderc_compile_options_set_preserve_bindings(this.options, true);
            Shaderc.shaderc_compile_options_set_optimization_level(this.options, 2);
         }
      }
   }

   public SPIRVModule compile(String name, String source, ShaderType type) throws ShaderCompileException {
      if (this.closed) {
         throw new IllegalStateException("Shader compiler is closed");
      } else {
         int optimizationLevel = requiresStableGeometryInterface(name) ? 0 : 2;
         Shaderc.shaderc_compile_options_set_optimization_level(this.options, optimizationLevel);
         int kind = type == ShaderType.FRAGMENT ? 1 : 0;
         long result = Shaderc.shaderc_compile_into_spv(this.compiler, source, kind, name, "main", this.options);
         if (result == 0L) {
            throw new ShaderCompileException("Shader compiler could not allocate a result for " + name);
         } else {
            SPIRVModule var11;
            try {
               int status = Shaderc.shaderc_result_get_compilation_status(result);
               if (status != 0) {
                  throw new ShaderCompileException(Shaderc.shaderc_result_get_error_message(result));
               }

               ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
               if (spirv == null || spirv.remaining() == 0) {
                  throw new ShaderCompileException("Shader compiler produced no SPIR-V for " + name);
               }

               ByteBuffer copy = MemoryUtil.memCalloc(spirv.remaining());
               MemoryUtil.memCopy(spirv, copy);

               try {
                  var11 = new SPIRVModule(copy, type);
               } catch (Throwable failure) {
                  MemoryUtil.memFree(copy);
                  throw failure;
               }
            } finally {
               Shaderc.shaderc_result_release(result);
            }

            return var11;
         }
      }
   }

   private static boolean requiresStableGeometryInterface(String name) {
      return name.contains("entity_shadow_") || name.contains("sodium_terrain_") || name.contains("native_geometry");
   }

   public ComputeModule compileCompute(String name, String source) throws ShaderCompileException {
      if (this.closed) {
         throw new IllegalStateException("Shader compiler is closed");
      } else {
         Shaderc.shaderc_compile_options_set_optimization_level(this.options, 2);
         long result = Shaderc.shaderc_compile_into_spv(this.compiler, source, 2, name, "main", this.options);
         if (result == 0L) {
            throw new ShaderCompileException("Could not allocate compute compilation result");
         } else {
            ComputeModule var7;
            try {
               if (Shaderc.shaderc_result_get_compilation_status(result) != 0) {
                  throw new ShaderCompileException(Shaderc.shaderc_result_get_error_message(result));
               }

               ByteBuffer sourceBytes = Shaderc.shaderc_result_get_bytes(result);
               if (sourceBytes == null || !sourceBytes.hasRemaining()) {
                  throw new ShaderCompileException("Empty compute module");
               }

               ByteBuffer copy = MemoryUtil.memAlloc(sourceBytes.remaining());
               copy.put(sourceBytes.duplicate()).flip();
               var7 = new ComputeModule(copy);
            } finally {
               Shaderc.shaderc_result_release(result);
            }

            return var7;
         }
      }
   }

   public void close() {
      if (!this.closed) {
         this.closed = true;
         Shaderc.shaderc_compile_options_release(this.options);
         Shaderc.shaderc_compiler_release(this.compiler);
      }
   }

   public static final class ComputeModule implements AutoCloseable {
      private ByteBuffer code;

      private ComputeModule(ByteBuffer code) {
         this.code = code;
      }

      public ByteBuffer code() {
         if (this.code == null) {
            throw new IllegalStateException("Module is closed");
         } else {
            return this.code;
         }
      }

      public void close() {
         if (this.code != null) {
            MemoryUtil.memFree(this.code);
            this.code = null;
         }

      }
   }
}
