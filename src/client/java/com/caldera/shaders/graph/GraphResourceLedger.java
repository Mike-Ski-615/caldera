package com.caldera.shaders.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * 释放账本：一个渲染器持有的东西在**创建处**登记，释放只发生在 {@link #close()} 一处。
 * <p>
 * 解决的是一件具体的事：原先"谁会释放 GPU 资源"没有 owner。{@code GraphRenderer.close()} 是一张
 * 手工维护的清单（11 个族，35 行），而同一个类里还有若干族它**根本不释放**。清单与字段是两份
 * 需要人工同步的东西，而它们不同步的表现是**静默泄漏**——没有异常、没有日志、门禁也看不出来。
 * <p>
 * 现在登记动作就写在创建那一行旁边，{@code close()} 退化成"把账本清一遍"。清单与字段不再是两份：
 * 想知道这个渲染器持有什么，读账本；想知道新加的东西要不要释放，看它是怎么写进来的。
 * <p>
 * <b>名字为什么不叫 {@code GraphResources}：</b>它现在**不持有**资源，只持有释放动作。等资源族真的
 * 搬进来再用那个名字——名字撒谎比少一个类型更贵。
 * <p>
 * <b>两处刻意的行为差异，都记在这里：</b>
 * <ol>
 *    <li><b>释放顺序不再是原来 {@code close()} 的顺序</b>，而是登记顺序（也就是创建顺序）。
 *        这条是安全的，而且不是靠推测：逐个核对过每个族的 {@code close()}——{@code ScenePrograms}
 *        只动自己的三张缓存与 {@code GraphShaderSources} 的注销，{@code ComputeProgram} 只动自己的
 *        描述符池与管线，{@code GraphSceneCapture}/{@code HeldLightShadowRenderer}/{@code GraphBuffers}
 *        各自只碰自己分配的东西。**没有任何一族的释放依赖另一族还活着**，所以顺序不承载语义，
 *        选登记顺序只是因为它与代码里的出现顺序一致，读的时候不用在脑子里翻一次。</li>
 *    <li><b>某一族释放失败不再中断其余的。</b>原件是第一个抛出去就结束，而 {@code closed} 那时已经
 *        置位，于是后面每一族都泄漏且无法重试。这里改成全部释放、把第一个异常抛出去、其余的挂在
 *        {@code suppressed} 上。</li>
 * </ol>
 * <p>
 * 没有登记的东西就是不会被释放的东西，这一点没有编译期保障——但登记写在创建处，
 * 而 {@code close()} 再也不可能与账本脱节。
 */
final class GraphResourceLedger implements AutoCloseable {

   private final List<AutoCloseable> releases = new ArrayList<>();
   private boolean closed;

   /**
    * 登记一个能自己释放的东西。{@code null} 被忽略——有些族是按需创建的。
    */
   void own(AutoCloseable resource) {
      if (resource != null) {
         this.releases.add(resource);
      }
   }

   /**
    * 登记一段释放动作。map 族走这条：它们不是 {@code AutoCloseable}，而是"逐个关掉再清空"。
    * <p>
    * 名字不叫 {@code own(Runnable)}：块状 lambda 同时满足 {@code Runnable} 与
    * {@code AutoCloseable}，两个重载会一起匹配，编译期就是一句"对 own 的引用不明确"。
    */
   void onClose(Runnable release) {
      this.releases.add(() -> release.run());
   }

   /** 账本上还有几项没释放；包可见，给测试用。 */
   int size() {
      return this.releases.size();
   }

   @Override
   public void close() {
      if (this.closed) {
         return;
      }

      this.closed = true;
      RuntimeException failure = null;

      for(AutoCloseable release : this.releases) {
         try {
            release.close();
         } catch (Exception problem) {
            RuntimeException wrapped = problem instanceof RuntimeException runtime ? runtime : new RuntimeException(problem);

            if (failure == null) {
               failure = wrapped;
            } else {
               failure.addSuppressed(wrapped);
            }
         }
      }

      this.releases.clear();

      if (failure != null) {
         throw failure;
      }
   }
}
