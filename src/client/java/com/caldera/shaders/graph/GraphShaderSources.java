package com.caldera.shaders.graph;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.caldera.shaders.runtime.NativeShaderPreprocessor;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

public final class GraphShaderSources {
   private static final Map<RenderPipeline, Source> SOURCES = new IdentityHashMap<>();
   private static final Map<RenderPipeline, CompiledRenderPipeline> COMPILED = new IdentityHashMap<>();
   private static final Map<CompiledRenderPipeline, RenderPipeline> ORIGINAL = new WeakHashMap<>();
   private static final Map<RenderPipeline, Runnable> RETIRE = new IdentityHashMap<>();

   private GraphShaderSources() {
   }

   public static void put(RenderPipeline pipeline, String vertex, String fragment) {
      SOURCES.put(pipeline, new Source(vertex, fragment));
   }

   public static String get(RenderPipeline pipeline, ShaderType type) {
      Source source = SOURCES.get(pipeline);
      return source == null ? null : (type == ShaderType.VERTEX ? source.vertex : source.fragment);
   }

   public static boolean contains(RenderPipeline pipeline) {
      return SOURCES.containsKey(pipeline);
   }

   public static boolean isNative(RenderPipeline pipeline) {
      return contains(pipeline) || pipeline.getShaders().values().stream().anyMatch((id) -> id.getNamespace().equals("caldera"));
   }

   public static void remember(RenderPipeline pipeline, CompiledRenderPipeline compiled) {
      if (compiled != null) {
         ORIGINAL.put(compiled, pipeline);
      }

   }

   public static RenderPipeline original(CompiledRenderPipeline compiled) {
      return ORIGINAL.get(compiled);
   }

   public static CompiledRenderPipeline compile(RenderPipeline pipeline, ShaderSource fallback) {
      CompiledRenderPipeline cached = COMPILED.get(pipeline);
      if (cached != null && !cached.isClosed()) {
         return cached;
      } else {
         NativeSources sources = new NativeSources(pipeline, fallback);

         CompiledRenderPipeline var5;
         try {
            CompiledRenderPipeline compiled = RenderSystem.getDevice().compilePipeline(pipeline, sources, Runnable::run).join().finishCompile();
            if (compiled == null) {
               throw new IllegalStateException("Failed to compile native pipeline " + pipeline.getLocation());
            }

            COMPILED.put(pipeline, compiled);
            remember(pipeline, compiled);
            var5 = compiled;
         } catch (Throwable var7) {
            try {
               sources.close();
            } catch (Throwable var6) {
               var7.addSuppressed(var6);
            }

            throw var7;
         }

         sources.close();
         return var5;
      }
   }

   public static void remove(RenderPipeline pipeline) {
      SOURCES.remove(pipeline);
      CompiledRenderPipeline compiled = COMPILED.remove(pipeline);
      if (compiled != null) {
         ORIGINAL.remove(compiled);
         compiled.close();
      }

      Runnable retire = RETIRE.remove(pipeline);
      if (retire != null) {
         retire.run();
      }

   }

   private record Source(String vertex, String fragment) {
   }

   public static final class NativeSources implements ShaderSource {
      private final RenderPipeline pipeline;
      private final ShaderSource fallback;
      private final Map<Identifier, ShaderSource.CachedIncludeSource> includes = new HashMap<>();

      NativeSources(RenderPipeline pipeline, ShaderSource fallback) {
         this.pipeline = pipeline;
         this.fallback = fallback;
      }

      public boolean isNative(ShaderType type) {
         return GraphShaderSources.get(this.pipeline, type) != null || this.pipeline.getShaders().get(type).getNamespace().equals("caldera");
      }

      public String getShader(@NonNull Identifier id, @NonNull ShaderType type) {
         String custom = GraphShaderSources.get(this.pipeline, type);
         if (custom != null) {
            return custom;
         } else if (!id.getNamespace().equals("caldera")) {
            return this.fallback.getShader(id, type);
         } else {
            String var10000 = id.getNamespace();
            String source = text("assets/" + var10000 + "/shaders/" + id.getPath() + (type == ShaderType.FRAGMENT ? ".fsh" : ".vsh"));
            if (source == null) {
               return null;
            } else {
               final HashSet<String> imported = new HashSet<>();
               return String.join("", (new NativeShaderPreprocessor() {

                   public String applyImport(boolean local, String name) {
                     if (!imported.add(name)) {
                        return null;
                     } else {
                        Identifier include = Identifier.parse(name);
                        String var10000 = include.getNamespace();
                        String body = GraphShaderSources.NativeSources.text("assets/" + var10000 + "/shaders/include/" + include.getPath());
                        if (body == null) {
                           throw new IllegalArgumentException("Missing shader include " + name);
                        } else {
                           return body;
                        }
                     }
                  }
               }).process(source));
            }
         }
      }

      public ShaderSource.CachedIncludeSource getInclude(Identifier id) {
         return !id.getNamespace().equals("caldera") ? this.fallback.getInclude(id) : this.includes.computeIfAbsent(id, (key) -> CachedIncludeSource.create(key, (String)Objects.requireNonNull(text("assets/caldera/shaders/include/" + key.getPath()))));
      }

      public void close() {
         this.includes.values().forEach(ShaderSource.CachedIncludeSource::close);
      }

      private static String text(String path) {
         try {
            InputStream stream = GraphShaderSources.class.getClassLoader().getResourceAsStream(path);

            String var2;
            try {
               var2 = stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Throwable var5) {
                try {
                    stream.close();
                } catch (Throwable var4) {
                    var5.addSuppressed(var4);
                }

                throw var5;
            }

            if (stream != null) {
               stream.close();
            }

            return var2;
         } catch (IOException failure) {
            throw new UncheckedIOException(failure);
         }
      }
   }
}
