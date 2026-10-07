package com.caldera.shaders.graph;

import java.util.Map;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder.Vertex;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 材质顶点格式的编码。
 * <p>
 * 这几条断言原先只存在于 {@code GraphGpuSmoke.checkMaterialEncoding} 里——一段要求 GPU、一个真的叫
 * "Caldera QA" 的世界、跑不到就 {@code client.stop()} 的脚手架。而它们其实全在 CPU 上：
 * {@code MaterialChunkVertex} 的编码器往一块 LWJGL 堆外缓冲里写字，然后逐字与
 * {@code CompactChunkVertex} 的输出比对。那段代码里唯一的 GPU 调用是把顶点交给管线，
 * 与**任何一条断言都无关**——所以断言可以搬到这里来。
 * <p>
 * <b>这个文件比原计划小，原因是一条实测出来的事实，记在这里免得下一个人再试一遍：</b>
 * {@code MaterialTable.compile} 按 {@code minecraft:stone} 这样的 id 去查方块注册表，所以在测试里
 * 必须先 bootstrap 注册表。但 {@code net.minecraft.server.Bootstrap.bootStrap()} 在普通 JUnit 的
 * JVM 里**走不完**——它会死在 {@code Items} → {@code EntityTypes} → {@code Util.fetchChoiceType} →
 * {@code BuiltInRegistries} 上，报 {@code IllegalArgumentException: Not bootstrapped (called from
 * registry minecraft:game_event)}；前面加上 {@code SharedConstants.tryDetectVersion()} 也一样。
 * <p>
 * 后果是：**任何碰 {@code Blocks.*} 或方块注册表的东西都够不着 {@code src/test}**。于是
 * {@code MaterialTable} 的状态选择器优先级、四条校验分支、以及 {@code MaterialContext.enter} 都只能
 * 在游戏里验——那正是门禁要留下的理由（见候选 6 的决定）。这里覆盖的是够得着的那部分，而且它恰好
 * 是错一次就让整套地形花屏的那部分：步长与属性逐字保留。
 */
class MaterialChunkVertexTest {

   /** 空定义不会碰注册表，所以它是这里唯一能构造出来的材质表。 */
   private static MaterialTable emptyTable() {
      return MaterialTable.compile(Map.of());
   }

   /** 与 {@code checkMaterialEncoding} 里构造的那个四顶点四边形逐字相同。 */
   private static Vertex[] quad() {
      Vertex[] vertices = Vertex.uninitializedQuad();

      for(int i = 0; i < 4; ++i) {
         vertices[i].x = (float)i;
         vertices[i].y = (float)(i + 1);
         vertices[i].z = (float)(i + 2);
         vertices[i].color = -8359872 + i;
         vertices[i].ao = 1.0F;
         vertices[i].u = (float)i * 0.1F;
         vertices[i].v = (float)i * 0.2F;
         vertices[i].light = 15728880;
      }

      return vertices;
   }

   @Test
   void anEmptyDefinitionMeansTheTableIsDisabled() {
      MaterialTable table = emptyTable();

      assertFalse(table.enabled());
      assertEquals(0, table.id(null), "没有条目时一切都是 0");
   }

   /**
    * 材质顶点是 **24** 字节，不是紧凑格式的 20：多出来的那 4 字节是材质 id。
    * <p>
    * 这条错了的表现是整块地形被按错误的步长解出来——花屏，而在游戏里只有那一次 smoke 会告诉你。
    */
   @Test
   void theMaterialFormatIsTwentyFourBytesPerVertex() {
      long extended = MemoryUtil.nmemCalloc(1L, 104L);

      try {
         assertEquals(extended + 96L, (new MaterialChunkVertex(emptyTable())).getEncoder().write(extended, 3, quad(), 7), "4 个顶点 × 24 字节");
      } finally {
         MemoryUtil.nmemFree(extended);
      }
   }

   /**
    * 材质格式必须**逐字保留**紧凑格式的每一个属性，只在后面追加材质 id。
    * 这是这个格式存在的全部意义；它错了就是整套地形画错。
    */
   @Test
   void theMaterialFormatPreservesEveryCompactAttribute() {
      Vertex[] vertices = quad();
      long compact = MemoryUtil.nmemCalloc(1L, 80L);
      long extended = MemoryUtil.nmemCalloc(1L, 104L);

      try {
         (new CompactChunkVertex()).getEncoder().write(compact, 3, vertices, 7);
         (new MaterialChunkVertex(emptyTable())).getEncoder().write(extended, 3, vertices, 7);

         for(int vertex = 0; vertex < 4; ++vertex) {
            for(int word = 0; word < 5; ++word) {
               assertEquals(MemoryUtil.memGetInt(compact + (long)vertex * 20L + (long)word * 4L),
                     MemoryUtil.memGetInt(extended + (long)vertex * 24L + (long)word * 4L),
                     "顶点 " + vertex + " 的第 " + word + " 个字被改动了");
            }
         }
      } finally {
         MemoryUtil.nmemFree(compact);
         MemoryUtil.nmemFree(extended);
      }
   }

   /**
    * 编码器必须**恰好**停在 4 个顶点之后，不许再写一个字。
    * 用一个哨兵值放在那条边界上：写过去就把它改了。
    */
   @Test
   void theEncoderStopsExactlyAtTheAllocatedEnd() {
      long sentinel = 81985529216486895L;
      long extended = MemoryUtil.nmemCalloc(1L, 104L);

      try {
         MemoryUtil.memPutLong(extended + 96L, sentinel);
         (new MaterialChunkVertex(emptyTable())).getEncoder().write(extended, 3, quad(), 7);

         assertEquals(sentinel, MemoryUtil.memGetLong(extended + 96L), "编码器越界写了");
      } finally {
         MemoryUtil.nmemFree(extended);
      }
   }

   /**
    * 作用域里没有方块状态时写出去的是 0。
    * <p>
    * 这里只能测到 0 那一半：非 0 的那一半要一个真的材质表，而那要方块注册表，见类注释。
    */
   @Test
   void noMaterialInScopeEncodesTheZeroId() {
      long extended = MemoryUtil.nmemCalloc(1L, 104L);

      try {
         (new MaterialChunkVertex(emptyTable())).getEncoder().write(extended, 3, quad(), 7);

         for(int vertex = 0; vertex < 4; ++vertex) {
            assertEquals(0, MemoryUtil.memGetInt(extended + (long)vertex * 24L + 20L), "顶点 " + vertex + " 的材质 id");
         }
      } finally {
         MemoryUtil.nmemFree(extended);
      }
   }
}
