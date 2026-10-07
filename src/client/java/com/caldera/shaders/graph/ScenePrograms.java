package com.caldera.shaders.graph;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.caldera.shaders.render.shadow.HeldLightShadowRenderer;
import com.caldera.shaders.render.shadow.ShadowService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

final class ScenePrograms implements AutoCloseable {
   private final List<Sources> rules = new ArrayList<>();
   private final Map<RenderPipeline, RenderPipeline> cache = new IdentityHashMap<>();
   private final Map<RenderPipeline, RenderPipeline> targetCache = new IdentityHashMap<>();
   private final Map<RenderPipeline, RenderPipeline> fallbackCache = new IdentityHashMap<>();
   private final PackGraph graph;
   /**
    * 这个包注册进 {@link GraphShaderSources} 的那些替换管线归谁。
    * <p>
    * 单独的那几处 {@code remove}（替换失败、恢复失败）保留着：它们是**运行中**丢掉一条，不是收摊。
    * 收摊那一次由这个 owner 一次清空，不必再逐个列举三张缓存里的替换项。
    */
   private final GraphShaderSources.Owner shaderSources = GraphShaderSources.owner("scene programs");
   private static long sequence;

   ScenePrograms(PackGraph graph, PackFiles files) throws IOException {
      this.graph = graph;

      try {
         for(PackGraph.SceneProgram rule : graph.scenePrograms()) {
            this.rules.add(new Sources(rule, files.shader(rule.vertex(), graph.options()), files.shader(rule.fragment(), graph.options())));
         }

         for(RenderPipeline pipeline : Stream.concat(RenderPipelines.requiredPipelines().stream(), RenderPipelines.optionalPipelines().stream()).toList()) {
            if (this.rules.stream().anyMatch((rulex) -> rulex.rule.matches(pipeline.getLocation().toString()))) {
               this.replace(pipeline, !graph.sceneTargets().isEmpty());
            }
         }

      } catch (Exception failure) {
         this.close();
         throw new IOException("Invalid scene program: " + failure.getMessage(), failure);
      }
   }

   RenderPipeline replace(RenderPipeline base, boolean sceneTargets) {
      if (base instanceof ScenePipeline) {
         return base;
      } else {
         Map<RenderPipeline, RenderPipeline> cache = sceneTargets ? this.targetCache : this.cache;
         RenderPipeline cached = cache.get(base);
         if (cached != null) {
            return cached;
         } else {
            Sources selected = null;

            for(Sources rule : this.rules) {
               if (rule.rule.matches(base.getLocation().toString())) {
                  if (selected != null) {
                     throw new IllegalArgumentException("Overlapping scene program selectors for " + String.valueOf(base.getLocation()));
                  }

                  selected = rule;
               }
            }

            if (selected != null && !selected.rule.writes().isEmpty() && !sceneTargets) {
               selected = null;
            }

            if (selected == null && !sceneTargets) {
               cache.put(base, base);
               return base;
            } else {
               Identifier id = replacementLocation(base.getLocation(), sequence++);
               RenderPipeline replacement = new ScenePipeline(base, id, selected != null, this.targets(base, selected, sceneTargets), selected == null ? Map.of() : selected.rule.textures(), this.graph.shadowQuality() > 0, (Double)this.graph.options().getOrDefault("HELD_LIGHTING", (double)0.0F) > (double)0.0F);
               GraphShaderSources.put(this.shaderSources, replacement, selected == null ? null : selected.vertex, selected == null ? null : selected.fragment);

               try {
                  if (RenderSystem.getCompiledPipelineNullable(replacement) == null) {
                     throw new IllegalArgumentException("Scene shader is incompatible with " + String.valueOf(base.getLocation()));
                  }
               } catch (RuntimeException failure) {
                  GraphShaderSources.remove(replacement);
                  throw failure;
               }

               cache.put(base, replacement);
               return replacement;
            }
         }
      }
   }

   private ColorTargetState[] targets(RenderPipeline base, Sources selected, boolean enabled) {
      return !enabled ? base.getColorTargetStates().toArray(ColorTargetState[]::new) : attachmentStates(base, this.graph, selected == null ? List.of() : selected.rule.writes());
   }

   static ColorTargetState[] attachmentStates(RenderPipeline base, PackGraph graph, List<String> writes) {
      ColorTargetState[] targets = new ColorTargetState[graph.sceneTargets().size() + 1];
      targets[0] = base.getColorTargetStates().getFirst();

      for(int i = 1; i < targets.length; ++i) {
         String name = graph.sceneTargets().get(i - 1);
         boolean enabled = writes.contains(name);
         targets[i] = new ColorTargetState(Optional.empty(), graph.resources().get(name).format(), enabled ? 15 : 0);
      }

      return targets;
   }

   RenderPipeline fallback(RenderPipeline base, boolean sceneTargets) {
      return !sceneTargets ? base : this.fallbackCache.computeIfAbsent(base, (original) -> {
         Identifier id = replacementLocation(original.getLocation(), sequence++);
         RenderPipeline pipeline = new ScenePipeline(original, id, false, this.targets(original, null, true), Map.of(), false, false);
         GraphShaderSources.put(this.shaderSources, pipeline, null, null);
         if (RenderSystem.getCompiledPipelineNullable(pipeline) == null) {
            GraphShaderSources.remove(pipeline);
            throw new IllegalStateException("Cannot restore scene pipeline " + base.getLocation());
         } else {
            return pipeline;
         }
      });
   }

   static Identifier replacementLocation(Identifier original, long generation) {
      String namespace = original.getNamespace().contains("sodium") ? "caldera_sodium" : "caldera";
      return Identifier.fromNamespaceAndPath(namespace, "native_geometry/" + generation);
   }

   static boolean isReplacement(RenderPipeline pipeline) {
       if (pipeline instanceof ScenePipeline scene) {
           return scene.nativeShader;
      }

       return false;
   }

   static Map<String, String> textures(RenderPipeline pipeline) {
      Map var10000;
      if (pipeline instanceof ScenePipeline scene) {
         var10000 = scene.textures;
      } else {
         var10000 = Map.of();
      }

      return var10000;
   }

   /**
    * 收摊：清掉三张缓存，并把注册过的替换管线一次注销。
    * <p>
    * 原先这里是自己遍历三张缓存、逐条判 {@code base != replacement} 再 remove。那条规则与注册动作
    * 是两份需要人工同步的东西；现在注册过什么由 owner 记着，这里不必再知道。
    */
   public void close() {
      this.cache.clear();
      this.targetCache.clear();
      this.fallbackCache.clear();
      GraphShaderSources.releaseAll(this.shaderSources);
   }

   private record Sources(PackGraph.SceneProgram rule, String vertex, String fragment) {
   }

   private static final class ScenePipeline extends RenderPipeline {
      private final boolean nativeShader;
      private final Map<String, String> textures;

      ScenePipeline(RenderPipeline base, Identifier id, boolean nativeShader, ColorTargetState[] targets, Map<String, String> textures, boolean shadows, boolean held) {
         super(id, nativeShader ? Map.of(ShaderType.VERTEX, id.withSuffix("_vertex"), ShaderType.FRAGMENT, id.withSuffix("_fragment")) : base.getShaders(), defines(base, nativeShader), nativeShader ? layouts(base, textures.keySet(), shadows, held) : base.getBindGroupLayouts(), targets, base.getDepthStencilState(), base.getPolygonMode(), base.isCull(), (VertexFormat[])base.getVertexFormatBindings().toArray((x$0) -> new VertexFormat[x$0]), base.getPrimitiveTopology(), base.pushConstantSize(), base.getSortKey());
         this.nativeShader = nativeShader;
         this.textures = textures;
      }

      private static ShaderDefines defines(RenderPipeline base, boolean nativeShader) {
         return nativeShader && base.getLocation().getPath().equals("pipeline/translucent_terrain") ? ShaderDefines.builder().define("CALDERA_TRANSLUCENT").build().withOverrides(base.getShaderDefines()) : base.getShaderDefines();
      }

      private static List<BindGroupLayout> layouts(RenderPipeline base, Set<String> textures, boolean shadows, boolean held) {
         List<BindGroupLayout> layouts = new ArrayList<>(base.getBindGroupLayouts());
         BindGroupLayout.Builder extra = BindGroupLayout.builder().withUniform("CalderaFrame", UniformType.UNIFORM_BUFFER).withUniform("CalderaWind", UniformType.UNIFORM_BUFFER);
         if (shadows) {
            ShadowService.layout(extra);
         }

         if (held) {
            HeldLightShadowRenderer.layout(extra);
         }

         for(String texture : textures) {
            if (BindGroupLayout.flattenUniforms(base.getBindGroupLayouts()).stream().anyMatch((uniform) -> uniform.name().equals(texture))) {
               throw new IllegalArgumentException("Scene texture conflicts with Minecraft binding " + texture);
            }

            extra.withUniform(texture, UniformType.COMBINED_IMAGE_SAMPLER);
         }

         layouts.add(extra.build());
         return List.copyOf(layouts);
      }
   }
}
