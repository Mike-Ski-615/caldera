package com.caldera.shaders.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双缓冲里"当前那一份"的选择。
 * <p>
 * 这几行原先在两个类里各写了一遍、逐字相同（{@code GraphRenderer.image()} 与
 * {@code GraphBuffers.resolve()}）。两份里只要有一份把 {@code previous} 那一半取反，表现就是
 * 计算着色器读到上一帧的存储缓冲——不抛异常，只有画面看得出。所以它值得有自己的测试，
 * 哪怕实现只有一行。
 */
class DoubleBufferTest {

   @Test
   void aPairWithOneEntryAlwaysUsesIt() {
      String[] single = {"only"};

      assertEquals("only", DoubleBuffer.current(single, "soil", 0));
      assertEquals("only", DoubleBuffer.current(single, "soil", 1), "没有历史的资源哪一帧都是它");
   }

   @Test
   void aCurrentNameUsesThisFramesHalf() {
      String[] pair = {"even", "odd"};

      assertEquals("even", DoubleBuffer.current(pair, "soil", 0));
      assertEquals("odd", DoubleBuffer.current(pair, "soil", 1));
   }

   @Test
   void aPreviousNameUsesTheOtherHalf() {
      String[] pair = {"even", "odd"};

      assertEquals("odd", DoubleBuffer.current(pair, "soil@previous", 0));
      assertEquals("even", DoubleBuffer.current(pair, "soil@previous", 1));
   }

   /** 上面三条依赖的契约：{@code @previous} 这个名字形态由 {@link PackGraph} 定义。 */
   @Test
   void thePreviousSuffixIsWhatMakesANameHistorical() {
      assertTrue(PackGraph.previous("soil@previous"));
      assertEquals("soil", PackGraph.current("soil@previous"));
      assertEquals("soil", PackGraph.current("soil"));
   }
}
