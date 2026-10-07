package com.caldera.shaders.graph;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import org.lwjgl.system.MemoryUtil;

public final class MaterialChunkVertex implements ChunkVertexType {
   private static final VertexFormat FORMAT;
   private final ChunkVertexEncoder encoder;

   public MaterialChunkVertex(MaterialTable materials) {
      ChunkVertexEncoder compact = (new CompactChunkVertex()).getEncoder();
      this.encoder = (pointer, material, vertices, section) -> {
         compact.write(pointer, material, vertices, section);
         int id = materials.id(MaterialContext.state());
         float minY = Float.POSITIVE_INFINITY;
         float maxY = Float.NEGATIVE_INFINITY;

         for(ChunkVertexEncoder.Vertex v : vertices) {
            minY = Math.min(minY, v.y);
            maxY = Math.max(maxY, v.y);
         }

         for(int vertex = 3; vertex >= 0; --vertex) {
            for(int word = 4; word >= 0; --word) {
               MemoryUtil.memPutInt(pointer + (long)vertex * 24L + (long)word * 4L, MemoryUtil.memGetInt(pointer + (long)vertex * 20L + (long)word * 4L));
            }

            int packed = id;
            if (materials.vegetationWind && id >= 2 && id <= 5) {
               float tip = Math.clamp((vertices[vertex].y - minY) / Math.max(maxY - minY, 0.001F), 0.0F, 1.0F);
               float weight = id == 5 ? 1.0F : (id == 3 ? tip * 0.5F : (id == 4 ? 0.5F + tip * 0.5F : tip));
               packed = id | Math.round(weight * 65535.0F) << 16;
            }

            MemoryUtil.memPutInt(pointer + (long)vertex * 24L + 20L, packed);
         }

         return pointer + 96L;
      };
   }

   public VertexFormat getVertexFormat() {
      return FORMAT;
   }

   public ChunkVertexEncoder getEncoder() {
      return this.encoder;
   }

   static {
      FORMAT = VertexFormat.builder(0).addAttribute("a_Position", GpuFormat.RG32_UINT).addAttribute("a_Color", GpuFormat.RGBA8_UNORM).addAttribute("a_TexCoord", GpuFormat.RG16_UINT).addAttribute("a_LightAndData", GpuFormat.RGBA8_UINT).addAttribute("a_CalderaMaterial", GpuFormat.R32_UINT).build();
   }
}
