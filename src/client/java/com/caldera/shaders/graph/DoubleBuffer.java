package com.caldera.shaders.graph;

/**
 * 历史资源的双缓冲：一个名字底下有一份（没有历史）或两份（有历史，要留着上一帧），
 * 哪一份算"当前"由一个奇偶索引决定。
 * <p>
 * 这个选择原先在**两个类里各写了一遍、逐字相同**：{@link GraphRenderer#image(String)} 与
 * {@link GraphBuffers#resolve(String, int)}。两份里只要有一份把 {@code previous} 那一半取反，
 * 表现就是计算着色器读到上一帧的存储缓冲——不抛异常，只有画面看得出。
 * <p>
 * 顺带把索引的归属也理清了：那个奇偶索引原先在 {@code GraphRenderer} 与 {@code GraphBuffers}
 * 里各存一份，由 {@code beginFrame} 拷过去，于是"两份状态必须手动同步"。现在它只有一个持有者
 * （渲染器），这里的两处调用都从参数拿。
 */
final class DoubleBuffer {

   private DoubleBuffer() {
   }

   /**
    * 取当前那一份。
    * <p>
    * 只有一份时永远是它；有两份时，历史资源取的是"上一帧"那一半。
    *
    * @param parity 这一帧的奇偶索引，由它的持有者传进来
    */
   static <T> T current(T[] pair, String name, int parity) {
      return pair[pair.length == 1 ? 0 : (PackGraph.previous(name) ? parity ^ 1 : parity)];
   }
}
