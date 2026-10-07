package com.caldera.shaders.render.shadow;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

/**
 * 阴影关卡的**作用域**：这一小段渲染是不是阴影关卡的一部分、画进哪里、用哪份 UBO，以及 Sodium 这一批
 * shadow pass 是哪些。
 * <p>
 * 迁移前这件事由**六份分散的静态状态**回答，散在两个类里：
 * <ul>
 *    <li>{@code DirectionalShadowRenderer} 上的两个 {@code ThreadLocal}（当前级联、是不是实体关）；</li>
 *    <li>同一个类上的两个普通静态字段（手持光源那一关临时顶上的目标与 UBO）；</li>
 *    <li>{@code SodiumShadowTerrainRenderer} 上的两个普通静态数组（正在构建／正在绘制的 pass 批）。</li>
 * </ul>
 * 它们被 8 个读取点跨 6 个 mixin 与 3 个阴影模块读取，而"进入时要把哪些东西置上、退出时要清哪些"
 * 只存在于调用顺序里。审查记下的那条风险就是它的后果：<b>漏掉一次 {@code endCascade} 会让之后所有
 * 渲染都以为自己还在阴影贴图里</b>，而那不会报错——只会一直画错。
 * <p>
 * 现在进入与退出都在这里，配对是**强制**的：没进就退、嵌套进入都会立刻抛
 * {@link IllegalStateException}。这与帧作用域（{@code SceneFrame.scope}）是同一套做法——把
 * "原本不会发生、但发生就静默画错"的误用变成大声失败。当前调用图里
 * {@code DirectionalShadowPass} 的三个 begin/end 对与 {@code HeldLightShadowRenderer} 的六面配对
 * 都是平衡且不嵌套的，所以这条强制不改变正常路径。
 * <p>
 * <b>级联号与"是不是实体关"不再被记下来。</b>它们原先唯一的读取者是
 * {@code activeTarget()}——它按级联号去渲染器的数组里取目标、按实体关决定取哪一组。现在目标与 UBO
 * 由渲染器在**进入时解析好**交进来，于是那两个标记没有读取者了，手持光源那一关与方向光那一关也就
 * 不再需要区分：两者都只是"一段有目标、有 UBO 的阴影渲染"。这是本次唯一一处状态被删掉而不是搬走。
 * <p>
 * <b>线程模型统一成每线程一份。</b>原件里级联号是 {@code ThreadLocal}，目标／UBO／Sodium 批是普通
 * {@code static}——同一个作用域两种模型。全部 8 个读取点都在渲染线程上，所以统一是等价的；而它换来
 * 的是"这个作用域属于一个线程"这条能一句话说完的性质。
 * <p>
 * <b>Sodium 批的三步</b>（{@link #enterSodiumBatch}／{@link #markBatchPrepared}／
 * {@link #exitSodiumBatch}）对应原件里的三个时刻。原件用两个**永远相等**的数组表达它们，靠赋值与置空
 * 的先后区分"正在构建"与"正在绘制"；这里显式说成三个阶段。两个读取者各只关心其中一个：
 * {@code ShadowChunkPreparationMixin} 只在构建期间问"这批是哪些"，
 * {@code ShadowPassIndexMixin} 只在绘制期间问"这条 pass 在我这批里排第几"。
 */
public final class ShadowPassScope {

   /** 当前作用域；不在阴影关卡里时为 {@code null}。 */
   private static final ThreadLocal<Pass> CURRENT = new ThreadLocal<>();
   /** 当前这一批 Sodium shadow pass；没有时为 {@code null}。 */
   private static final ThreadLocal<Batch> SODIUM = new ThreadLocal<>();

   private ShadowPassScope() {
   }

   // ---------------------------------------------------------------- 进入与退出

   /**
    * 进入阴影关卡。
    * <p>
    * {@code target} 与 {@code uniforms} 是**解析好的值**：谁进入谁负责按级联号、按实体关、或按手持光源
    * 的那张面，把要画进去的目标与要用的级联 UBO 算出来。这样读取的一方不必知道有几种 ShadowTarget。
    */
   public static void enter(RenderTarget target, GpuBufferSlice uniforms) {
      if (CURRENT.get() != null) {
         throw new IllegalStateException("Caldera shadow pass entered twice: a previous enter was never exited");
      }

      CURRENT.set(new Pass(target, uniforms));
   }

   /** 退出阴影关卡。没进就退是 bug，而且原先它是静默的。 */
   public static void exit() {
      if (CURRENT.get() == null) {
         throw new IllegalStateException("Caldera shadow pass exited without an entry: exit() came before enter()");
      }

      CURRENT.remove();
   }

   // ---------------------------------------------------------------- 查询

   /** 是否正在画阴影贴图。渲染侧所有"要不要换成阴影管线／画进别的目标"的问法都以它开头。 */
   public static boolean active() {
      return CURRENT.get() != null;
   }

   /** 正在画进去的那个目标；不在阴影关卡里时为 {@code null}。 */
   public static RenderTarget target() {
      Pass pass = CURRENT.get();
      return pass == null ? null : pass.target;
   }

   /** 当前那一关要用的级联 UBO；不在阴影关卡里时为 {@code null}。 */
   public static GpuBufferSlice uniforms() {
      Pass pass = CURRENT.get();
      return pass == null ? null : pass.uniforms;
   }

   // ---------------------------------------------------------------- Sodium 的 pass 批

   /**
    * 一批 Sodium shadow pass 开始：接下来的构建与绘制都对着这几条 pass。
    * <p>
    * 必须在 {@link #exitSodiumBatch()} 配平；两者之间会经过 {@link #markBatchPrepared()}。
    */
   public static void enterSodiumBatch(TerrainRenderPass[] passes) {
      if (SODIUM.get() != null) {
         throw new IllegalStateException("Caldera shadow pass batch entered twice: a previous enterSodiumBatch was never exited");
      }

      SODIUM.set(new Batch(passes));
   }

   /**
    * 批的**构建**阶段结束，进入绘制阶段。
    * <p>
    * 原件在 {@code renderer.prepare(...)} 之后把构建用的那个数组置空，于是"正在构建"这件事只在那一段
    * 时间里成立。这里保留那个窗口：{@link #preparingPasses()} 之后返回 {@code null}，而
    * {@link #passIndex} 照旧能在这批里找到 pass。
    */
   public static void markBatchPrepared() {
      Batch batch = SODIUM.get();
      if (batch == null) {
         throw new IllegalStateException("Caldera shadow pass batch marked prepared without an entry");
      }

      batch.preparing = false;
   }

   /** 这一批结束，两个阶段都清空。 */
   public static void exitSodiumBatch() {
      if (SODIUM.get() == null) {
         throw new IllegalStateException("Caldera shadow pass batch exited without an entry: exitSodiumBatch() came before enterSodiumBatch()");
      }

      SODIUM.remove();
   }

   /**
    * 正在**构建**的那一批 pass；不在构建中时为 {@code null}。
    * <p>
    * 读取方拿 {@code null} 表示"这批不是我该管的"，于是退回原版行为。
    */
   public static TerrainRenderPass[] preparingPasses() {
      Batch batch = SODIUM.get();
      return batch == null || !batch.preparing ? null : batch.passes;
   }

   /** 这条 pass 在当前这一批里排第几；不在这一批里（或没有批）时为 {@code -1}。 */
   public static int passIndex(TerrainRenderPass pass) {
      Batch batch = SODIUM.get();
      if (batch != null) {
         for(int i = 0; i < batch.passes.length; ++i) {
            if (batch.passes[i] == pass) {
               return i;
            }
         }
      }

      return -1;
   }

   /** 一个阴影关卡里的目标与 UBO。两个都是进入时解析好的值。 */
   private record Pass(RenderTarget target, GpuBufferSlice uniforms) {
   }

   /** 一批 Sodium shadow pass，以及它是在构建阶段还是绘制阶段。 */
   private static final class Batch {
      private final TerrainRenderPass[] passes;
      private boolean preparing = true;

      private Batch(TerrainRenderPass[] passes) {
         this.passes = passes;
      }
   }
}
