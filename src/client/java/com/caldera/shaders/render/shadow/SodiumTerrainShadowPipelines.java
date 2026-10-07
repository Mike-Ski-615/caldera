package com.caldera.shaders.render.shadow;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.caldera.shaders.graph.GraphShaderSources;
import com.caldera.shaders.runtime.ShaderResourceIds;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.resources.Identifier;

public final class SodiumTerrainShadowPipelines {
   private static final Map<VertexFormat, Map<Integer, RenderPipeline>> CASTERS = new IdentityHashMap();

   private SodiumTerrainShadowPipelines() {
   }

   public static RenderPipeline caster(TerrainRenderPass pass, VertexFormat vertexFormat) {
      int mode = pass.isTranslucent() ? 2 : (pass.supportsFragmentDiscard() ? 1 : 0);
      return (RenderPipeline)((Map)CASTERS.computeIfAbsent(vertexFormat, (ignored) -> new HashMap())).computeIfAbsent(mode, (ignored) -> build(pass, vertexFormat));
   }

   public static void close() {
      CASTERS.values().forEach((pipelines) -> pipelines.values().forEach(GraphShaderSources::remove));
      CASTERS.clear();
   }

   private static RenderPipeline build(TerrainRenderPass pass, VertexFormat vertexFormat) {
      String layer = pass.isTranslucent() ? "translucent" : (pass.supportsFragmentDiscard() ? "cutout" : "solid");
      String shader = "sodium_terrain_shadow";
      RenderPipeline.Builder builder = RenderPipeline.builder(new RenderPipeline.Snippet[0]).withBindGroupLayout(sodiumLayout()).withLocation(Identifier.fromNamespaceAndPath("caldera_sodium", "pipeline/shadow/" + shader + "_" + layer)).withVertexShader(ShaderResourceIds.coreShader(shader)).withFragmentShader(ShaderResourceIds.coreShader(shader)).withPushConstantSize(20).withPrimitiveTopology(PrimitiveTopology.QUADS).withVertexBinding(0, vertexFormat).withShaderDefine("USE_VERTEX_COMPRESSION");
      builder.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0.0F, 0.0F)).withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, 1)).withCull(false);
      if (pass.isTranslucent()) {
         builder.withShaderDefine("ALPHA_CUTOUT", 0.01F);
      } else if (pass.supportsFragmentDiscard()) {
         builder.withShaderDefine("ALPHA_CUTOUT", 0.5F);
      }

      if (vertexFormat.getElements().stream().anyMatch((element) -> element.name().equals("a_CalderaMaterial"))) {
         builder.withShaderDefine("CALDERA_VEGETATION");
      }

      return builder.build();
   }

   private static BindGroupLayout sodiumLayout() {
      return BindGroupLayout.builder().withUniform("u_LightTex", UniformType.COMBINED_IMAGE_SAMPLER).withUniform("u_BlockTex", UniformType.COMBINED_IMAGE_SAMPLER).withUniform("u_Globals", UniformType.UNIFORM_BUFFER).withUniform("CalderaWind", UniformType.UNIFORM_BUFFER).withUniform("u_SectionTimeInfo", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT).build();
   }
}
