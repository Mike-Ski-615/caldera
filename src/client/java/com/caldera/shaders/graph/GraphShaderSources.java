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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

public final class GraphShaderSources {
   private static final Map<RenderPipeline, Source> SOURCES = new IdentityHashMap<>();
   private static final Map<RenderPipeline, CompiledRenderPipeline> COMPILED = new IdentityHashMap<>();
   private static final Map<CompiledRenderPipeline, RenderPipeline> ORIGINAL = new WeakHashMap<>();
   /**
    * 每个 owner 名下的管线。
    * <p>
    * 这张表是"注销不再靠记性"的落点：管着若干条管线的人拿一个 {@link Owner}，把这些管线挂上去，
    * 收摊时说一句 {@link #releaseAll(Owner)}，不必自己逐个列举——原先那 4 个 owner 各自维护一份
    * 要注销的清单，漏一个就是静默残留（{@code COMPILED} 里的编译产物与 {@code ORIGINAL} 里的反查
    * 会跟着一起留着）。
    */
   private static final Map<Owner, Set<RenderPipeline>> CLAIMED = new IdentityHashMap<>();

   private GraphShaderSources() {
   }

   /**
    * 一个注册者。同一个 owner 可以跨多次注册使用；{@link #releaseAll(Owner)} 一次清空它名下的全部。
    * <p>
    * 它是个身份对象：不做 {@code equals}，两个同名的 owner 是不同的 owner。
    */
   public static final class Owner {
      private final String name;

      private Owner(String name) {
         this.name = name;
      }

      @Override
      public String toString() {
         return "shader sources of " + this.name;
      }
   }

   /**
    * 造一个 owner；{@code name} 只用于诊断。
    */
   public static Owner owner(String name) {
      return new Owner(name);
   }

   /**
    * 把一条管线挂到 owner 名下，并登记它的原生源码。
    * <p>
    * 与 {@link #claim(Owner, RenderPipeline)} 分开，是因为有些管线（阴影与 Sodium 地形那两张缓存）
    * 的源码走的是核心着色器那条路，它们没有原生源码可登记，但**编译产物同样需要有人负责注销**。
    */
   public static void put(Owner owner, RenderPipeline pipeline, String vertex, String fragment) {
      claim(owner, pipeline);
      SOURCES.put(pipeline, new Source(vertex, fragment));
   }

   /**
    * 只把管线挂到 owner 名下，不登记源码。
    * <p>
    * 用在"源码来自别处、但编译产物归我"的那些管线上。
    */
   public static void claim(Owner owner, RenderPipeline pipeline) {
      CLAIMED.computeIfAbsent(owner, (ignored) -> Collections.newSetFromMap(new IdentityHashMap<>())).add(pipeline);
   }

   /**
    * 注销这个 owner 名下的全部管线。
    * <p>
    * 逐条走 {@link #remove(RenderPipeline)}，所以与单独注销是同一条路径、同样幂等。已经单独注销过的
    * 那些仍然留在名单上，再走一遍是空操作——名单本身在这一次调用里就丢掉了。
    */
   public static void releaseAll(Owner owner) {
      Set<RenderPipeline> claimed = CLAIMED.remove(owner);

      if (claimed != null) {
         claimed.forEach(GraphShaderSources::remove);
      }

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
               return NativeShaderPreprocessor.expand(source, name -> {
                  // 同一个 include 只展开一次：第二次返回 null，展开侧把它换成空。
                  if (!imported.add(name)) {
                     return null;
                  }

                  Identifier include = Identifier.parse(name);
                  String namespace = include.getNamespace();
                  String body = GraphShaderSources.NativeSources.text("assets/" + namespace + "/shaders/include/" + include.getPath());
                  if (body == null) {
                     throw new IllegalArgumentException("Missing shader include " + name);
                  }

                  return body;
               });
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
