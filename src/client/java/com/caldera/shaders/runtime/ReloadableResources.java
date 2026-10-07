package com.caldera.shaders.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * 可重载资源的**所有者登记表**：谁持有跨资源重载存活的进程级 GPU 状态，谁在自己的定义处登记一条
 * 释放动作；释放只发生在 {@link #closeAll()} 一处。
 * <p>
 * 解决的是一件具体的事：原先"哪些模块必须在重载时关掉"是一张写在**适配器**里的手工清单——
 * {@code MinecraftShaderHost.closeReloadableResources()} 里并排四行 {@code X.close()}。那张清单
 * 长在错的地方，而那个适配器自己的契约是"每个方法只做一行转发"。清单与真实的所有者是两份需要人工
 * 同步的东西，而它们不同步的表现是**静默泄漏**：新增第五个持有进程级 GPU 状态的模块、忘了往适配器
 * 那四行里补一行，于是每次资源重载都漏一份资源，没有异常、没有日志。
 * <p>
 * 现在登记动作就写在持有状态的那个类里，{@link #closeAll()} 退化成"把登记表跑一遍"。这与仓库里
 * 已有的两个前身同形：{@code GraphShaderSources.Owner} 解决的是着色器源码的注销，{@code GraphResourceLedger}
 * 解决的是**一个渲染器**持有的资源。本模块管的是第三种生命周期：**进程级、跨重载存活**的那些。
 * <p>
 * <b>条目的契约是"让 owner 变空"，不是"释放一次"。</b>{@link #closeAll()} 会跑释放动作但
 * **保留登记**，因此可以被同一份登记反复调用。这不是顺手的选择：{@code closeReloadableResources()}
 * 在一次 JVM 里本来就会被调用多次（每次资源重载一次，进程退出时再一次），而清空登记的写法会让第二次
 * 重载静默地什么都不关。四个现有 {@code close()} 的实现本来就满足这条契约——它们都是"清空自己的
 * 字段/缓存"。
 * <p>
 * <b>两处刻意的行为差异，都记在这里：</b>
 * <ol>
 *    <li><b>释放顺序是登记顺序，而登记顺序是各模块第一次被碰到的那一刻。</b>迁移前那张手工清单钉的
 *        是另一个顺序（管线 → 渲染器 → 计划缓存）。逐个核对过四个 {@code close()} 之后可以确定：
 *        **没有任何一个的释放依赖另一个还活着**——管线的释放只动 {@code GraphShaderSources} 里按管线
 *        身份记的映射，渲染器只拆自己的 RenderTarget，Sodium 那边只清自己的计划缓存并自增地形修订号。
 *        所以顺序不承载语义，改动它是安全的。</li>
 *    <li><b>某一个 owner 释放失败不再中断其余的。</b>迁移前是第一个抛出去就结束，于是后面三个静默泄漏
 *        且无法重试。这里改成全部释放、抛出第一个、其余的挂在 {@code suppressed} 上——与
 *        {@code GraphResourceLedger} 已经定下的策略一致。</li>
 * </ol>
 * <p>
 * <b>诚实的上限：</b>一个**从未登记**的新模块，这里探测不到。能做的只是让"新加一族"这件事在写代码的
 * 地方看得见（登记写在状态旁边），并用测试钉住已登记的集合。
 * <p>
 * 只在客户端渲染线程上使用，与它所描述的四个模块一致。
 */
public final class ReloadableResources {

   private static final List<Owner> OWNERS = new ArrayList<>();

   private ReloadableResources() {
   }

   /**
    * 声明一个所有者。{@code name} 只用于诊断与测试，不参与判定。
    * <p>
    * 登记从一开始就存在（哪怕还没有释放动作），因为"这个模块是不是所有者"本身就是要能问出来的信息。
    */
   public static Owner owner(String name) {
      Owner owner = new Owner(name);
      OWNERS.add(owner);
      return owner;
   }

   /** 声明一个所有者并登记它唯一的释放动作——各模块用这一条，一行写完。 */
   public static Owner owner(String name, Runnable release) {
      Owner owner = owner(name);
      owner.onClose(release);
      return owner;
   }

   /**
    * 把每个所有者的释放动作跑一遍，**保留登记**，因此可以反复调用。
    * <p>
    * 释放动作里如果再触发某个尚未载入的模块登记一个新的 owner，那一项从下一次调用起才被跑到——
    * 遍历的是快照。这条是为了不抛 {@code ConcurrentModificationException}，不是语义要求。
    *
    * @throws RuntimeException 第一个失败；其余的挂在它的 {@code suppressed} 上
    */
   public static void closeAll() {
      RuntimeException failure = null;

      for (Owner owner : List.copyOf(OWNERS)) {
         for (Runnable release : List.copyOf(owner.releases)) {
            try {
               release.run();
            } catch (Exception problem) {
               RuntimeException wrapped = problem instanceof RuntimeException runtime ? runtime : new RuntimeException(problem);

               if (failure == null) {
                  failure = wrapped;
               } else {
                  failure.addSuppressed(wrapped);
               }
            }
         }
      }

      if (failure != null) {
         throw failure;
      }
   }

   /** 已登记的所有者名字，按登记顺序；包可见，给测试用。 */
   static List<String> ownerNames() {
      return OWNERS.stream().map((owner) -> owner.name).toList();
   }

   /**
    * 一个持有可重载资源的所有者。
    * <p>
    * 它是身份对象：不做 {@code equals}，两个同名的 owner 是不同的 owner——与
    * {@code GraphShaderSources.Owner} 同一个理由。
    */
   public static final class Owner {
      private final String name;
      private final List<Runnable> releases = new ArrayList<>();

      private Owner(String name) {
         this.name = name;
      }

      /**
       * 登记一条释放动作。{@code null} 被忽略——有些族是按需创建的。
       * <p>
       * 这条动作会被调用多次（每次 {@link #closeAll()} 一次），所以它必须写成"让本 owner 变空"，
       * 而不是"释放一次"。
       */
      public void onClose(Runnable release) {
         if (release != null) {
            this.releases.add(release);
         }
      }

      /**
       * 把自己从登记表里摘掉；包可见，只给测试用来清理自己造的假 owner。
       * <p>
       * 生产模块的登记**不能**这样清：类初始化只发生一次，清掉之后就再也回不来了。
       */
      void discardForTest() {
         OWNERS.remove(this);
      }

      @Override
      public String toString() {
         return "reloadable resources of " + this.name;
      }
   }
}
