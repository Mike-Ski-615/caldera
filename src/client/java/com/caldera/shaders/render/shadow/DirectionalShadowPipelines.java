package com.caldera.shaders.render.shadow;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.caldera.shaders.graph.GraphShaderSources;
import com.caldera.shaders.mixin.RenderPipelinesAccessor;
import com.caldera.shaders.runtime.ReloadableResources;
import com.caldera.shaders.runtime.ShaderResourceIds;
import java.util.Optional;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;

public final class DirectionalShadowPipelines {
   public static RenderPipeline ENTITY_DEPTH_SOLID;
   public static RenderPipeline ENTITY_DEPTH_CUTOUT;
   public static RenderPipeline ENTITY_DEPTH_TRANSLUCENT;
   /**
    * 这三条管线的编译产物归谁。
    * <p>
    * 它们的源码走的是核心着色器那条路，没有原生源码可登记，所以用 {@code claim} 而不是 {@code put}：
    * 但**编译产物同样需要有人注销**，而那个人就是这里。原先 {@code close()} 得自己把三个字段列一遍，
    * 加一条新管线就要记得回来补一次。
    */
   private static final GraphShaderSources.Owner OWNER = GraphShaderSources.owner("directional shadow pipelines");
   /**
    * 这三条管线跨资源重载存活，所以"重载时要把它们放掉"这条动作在这里登记一次，
    * 而不是靠适配器那边的清单记得调一次。见 {@link ReloadableResources}。
    */
   private static final ReloadableResources.Owner RELOADABLE =
         ReloadableResources.owner("directional shadow pipelines", DirectionalShadowPipelines::close);

   private DirectionalShadowPipelines() {
   }

   public static void ensureInitialized() {
      if (ENTITY_DEPTH_SOLID == null) {
         close();
         DepthStencilState depthWrite = new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0.0F, 0.0F);
         ColorTargetState shadowColor = new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, 1);
         ENTITY_DEPTH_SOLID = claim(entity("pipeline/shadow/entity_depth_solid", "entity_shadow_depth").withColorTargetState(shadowColor).withDepthStencilState(depthWrite).withCull(false).withBindGroupLayout(casterLayout()).build());
         ENTITY_DEPTH_CUTOUT = claim(entity("pipeline/shadow/entity_depth_cutout", "entity_shadow_depth").withColorTargetState(shadowColor).withDepthStencilState(depthWrite).withShaderDefine("ALPHA_CUTOUT", 0.1F).withCull(false).withBindGroupLayout(casterLayout()).build());
         ENTITY_DEPTH_TRANSLUCENT = claim(entity("pipeline/shadow/entity_depth_translucent", "entity_shadow_depth").withColorTargetState(shadowColor).withDepthStencilState(depthWrite).withShaderDefine("ALPHA_CUTOUT", 0.1F).withCull(false).withBindGroupLayout(casterLayout()).build());
      }
   }

   public static void close() {
      GraphShaderSources.releaseAll(OWNER);
      ENTITY_DEPTH_SOLID = null;
      ENTITY_DEPTH_CUTOUT = null;
      ENTITY_DEPTH_TRANSLUCENT = null;
   }

   private static RenderPipeline claim(RenderPipeline pipeline) {
      GraphShaderSources.claim(OWNER, pipeline);
      return pipeline;
   }

   public static RenderPipeline entityDepthPipeline(RenderPipeline pipeline) {
      if (pipeline != RenderPipelines.ENTITY_SOLID && pipeline != RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD) {
         if (pipeline != RenderPipelines.ENTITY_CUTOUT && pipeline != RenderPipelines.ENTITY_CUTOUT_CULL && pipeline != RenderPipelines.ENTITY_CUTOUT_Z_OFFSET && pipeline != RenderPipelines.ENTITY_CUTOUT_DISSOLVE && pipeline != RenderPipelines.ARMOR_CUTOUT_NO_CULL && pipeline != RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL) {
            return pipeline != RenderPipelines.ENTITY_TRANSLUCENT && pipeline != RenderPipelines.ENTITY_TRANSLUCENT_CULL ? null : ENTITY_DEPTH_TRANSLUCENT;
         } else {
            return ENTITY_DEPTH_CUTOUT;
         }
      } else {
         return ENTITY_DEPTH_SOLID;
      }
   }

   private static RenderPipeline.Builder entity(String location, String shaderName) {
      return RenderPipeline.builder(new RenderPipeline.Snippet[]{RenderPipelinesAccessor.caldera$entitySnippet()}).withLocation(ShaderResourceIds.id(location)).withVertexShader(ShaderResourceIds.coreShader(shaderName)).withFragmentShader(ShaderResourceIds.coreShader(shaderName)).withBindGroupLayout(BindGroupLayouts.SAMPLER1);
   }

   private static BindGroupLayout casterLayout() {
      return BindGroupLayout.builder().withUniform("CalderaCascade", UniformType.UNIFORM_BUFFER).build();
   }
}
