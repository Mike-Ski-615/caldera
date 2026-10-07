package com.caldera.shaders.render.shadow;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import java.util.function.Consumer;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/**
 * 一帧的**阴影关卡**：把 {@link CascadeSchedule} 那份计划真正执行出来的那个顺序。
 * <p>
 * 计划（哪几个级联这一帧要重画、每个画到哪里、用哪个矩阵）由 {@link CascadePlanner} 决策，
 * 已经可以单独验证了。剩下的这一半——**按什么顺序执行**——原先只存在于
 * {@code LevelRendererShadowMixin} 里一个 {@code pass.executes(() -> {...})} 的 lambda 体内，
 * 而那里是整个类里最难读、也最难改的一段：它同时混着 UBO 上传、Sodium 的 uniform 刷新、
 * 实体提交的准备与渲染、手持光源的单独一关，以及两处异常处理。
 * <p>
 * 这个顺序里藏着几条**不出错就看不见**的约束，它们才是这里值得存在的理由：
 * <ul>
 *    <li>{@code uploadCascade} 在循环体里**只调一次**：地形与实体两个 pass 共用同一份级联矩阵与
 *        光照方向，上传两遍只会把同一段 UBO 重写一次；而"两个都更新时不小心上传两次"或
 *        "只有一个更新时忘了上传"都不会报错，只会让影子用上一帧的矩阵。</li>
 *    <li>每个 pass 都是 {@code clear → begin → 渲染 → end}，且 {@code end} 必须在
 *        {@code finally} 里：{@code beginCascade} 把级联号写进一个 ThreadLocal，漏掉 {@code end}
 *        会让**之后所有**的渲染都以为自己还在阴影贴图里。</li>
 *    <li>循环之后那几步有严格次序：先刷 Sodium 的 uniform，再上传 shadow data，
 *        最后才是手持光源那一关——shadow data 必须反映本帧所有级联的最终状态。</li>
 *    <li>失败只**报告**、不重抛，而 {@code endFrame()} 无论如何都要跑。</li>
 * </ul>
 * <p>
 * 为了让这些约束能在纯 JVM 里断言，执行需要的东西被切成两半：
 * <ul>
 *    <li>{@link Device}——级联的 GPU 侧动作，生产实现就是 {@link DirectionalShadowRenderer} 本体；</li>
 *    <li>{@link Context}——**这一帧的事实与效果**：模块算不出来的那几个（有没有实体提交、
 *        这一帧有没有人消费 shadow data、手持光源开没开），加上只有调用方做得了的动作
 *        （画地形、刷 uniform、画实体、报告失败、收尾）。</li>
 * </ul>
 * 两边在测试里都有记录式的实现，于是"给定这样一份计划，到底按什么顺序调了什么"变成一条
 * 可断言的序列。这个类因此**不读任何静态状态**：它原先要读的 {@code ShadowService.enabled()}、
 * {@code NativePackRuntime.heldShadows()}、{@code NativePackRuntime.failScene()} 全部改由适配器
 * 提供，否则那几条分支在测试里根本到不了。
 * <p>
 * <b>曾经的一条疑点已经修掉：{@code prepare()} 原在 {@code try} 之外</b>（0.5.1 原件的写法）。
 * 那时准备阶段抛出的 {@code RuntimeException} 不报告、也不走 {@code endFrame()}，而是直接从
 * frame pass 里冒出去——留下脏的 dispatcher 状态，而且渲染帧会崩。现在它与其他任何一步同等对待，
 * 见 {@link #execute}。
 */
public final class DirectionalShadowPass {

   private DirectionalShadowPass() {
   }

   /**
    * 执行本帧的阴影关卡。
    * <p>
    * {@code facts} 是"要不要做"那三条（见 {@link ShadowPassFacts} 里为什么它们值得命名），
    * {@code context} 是"要做的时候怎么做"。分开之后这个方法的两个入参各自只有一个意思。
    * <p>
    * 抛出的 {@code RuntimeException} 一律交给 {@link Context#reportFailure}，不再向外冒——
    * <b>包括 {@link Device#prepare} 那一段</b>（它原先在 {@code try} 之外，是 0.5.1 原件的写法）。
    * 这样的后果是一致而不是更差：准备失败与其他任何失败一样走同一条通道，
    * 于是 {@code endFrame()} 照跑（不再留下脏的 dispatcher 状态），而决定权在适配器那里——
    * 生产侧把它接到 {@code SceneFrame.fail()}，用户看到的是"光影包暂停"而不是渲染帧崩掉。
    */
   public static void execute(Device shadows, ShadowPassFacts facts, Context context) {
      LevelRenderState levelState = context.levelState();
      CommandEncoder encoder = context.encoder();

      try {
         // prepare 也在这段里：失败与后面任何一步同等对待。
         shadows.prepare(levelState, context.packDistance());

         for(int cascade = 0; cascade < shadows.activeCascadeCount(); ++cascade) {
            boolean terrainUpdate = shadows.shouldUpdateCascade(cascade);
            boolean entityUpdate = shadows.shouldUpdateEntityCascade(cascade);
            if (terrainUpdate || entityUpdate) {
               shadows.uploadCascade(encoder, cascade);
               if (terrainUpdate) {
                  shadows.clearCascade(encoder, cascade);
                  shadows.beginCascade(cascade);

                  try {
                     context.renderTerrain(cascade);
                  } finally {
                     shadows.endCascade();
                  }
               }

               if (entityUpdate) {
                  shadows.clearEntityCascade(encoder, cascade);
                  shadows.beginEntityCascade(cascade);

                  try {
                     if (facts.entitySubmits()) {
                        context.renderEntities();
                     }
                  } finally {
                     shadows.endCascade();
                  }
               }
            }
         }

         context.flushSodiumUniforms();
         if (facts.shadowDataConsumed()) {
            shadows.uploadShadowData(encoder);
         }

         if (facts.heldLightActive()) {
            context.renderHeldLight();
         }
      } catch (RuntimeException failure) {
         context.reportFailure(failure);
      } finally {
         context.endFrame();
      }

   }

   /**
    * 级联的 GPU 侧动作。生产实现是 {@link DirectionalShadowRenderer}——这些方法本来就是它的
    * 公开方法，这个端口只是把"执行关卡要用到的那部分"点了名。
    * <p>
    * 它不是一个假想的接缝：测试里由记录式的实现填充，于是上边那几条顺序约束不必真的碰 GPU
    * 就能断言。
    */
   public interface Device {
      /**
       * 收集本帧输入、保证 GPU 资源、交给计划模块决策。
       * <p>
       * {@code packDistance} 是**本帧输入**而不是从别处读来的：它是这个包声明的阴影距离，
       * 而"包声明了什么"归 graph 侧；执行侧不该回头去问它（那一条曾经是
       * {@code DirectionalShadowRenderer → NativePackRuntime} 的反向依赖）。
       */
      void prepare(LevelRenderState levelState, float packDistance);

      int activeCascadeCount();

      boolean shouldUpdateCascade(int cascade);

      boolean shouldUpdateEntityCascade(int cascade);

      void uploadCascade(CommandEncoder encoder, int cascade);

      void uploadShadowData(CommandEncoder encoder);

      void clearCascade(CommandEncoder encoder, int cascade);

      void clearEntityCascade(CommandEncoder encoder, int cascade);

      void beginCascade(int cascade);

      void beginEntityCascade(int cascade);

      void endCascade();
   }

   /**
    * 这一帧的**效果**：该画的时候怎么画。
    * <p>
    * 判断"有没有 Sodium""有没有相机"不在这里：那些是**适配器自己的守卫**，与原件里它们所在的位置
    * 一一对应（画地形那一关要 Sodium 且相机就位，收尾刷 uniform 那一关只要 Sodium）。模块只说
    * "现在该画地形了"，能不能画是适配器的事。
    * <p>
    * "要不要画"也不在这里——那是 {@link ShadowPassFacts}。两者原先混在同一个接口上，于是
    * {@code Frame} 那个 record 同时要装事实与动作；分开之后这个接口只剩动作。
    */
   public interface Context {
      LevelRenderState levelState();

      CommandEncoder encoder();

      /**
       * 这个包声明的阴影距离。
       * <p>
       * 它是**输入**：{@link Device#prepare} 要用它做级联覆盖范围的计算，而"包声明了什么"归调用方
       * （graph 侧）回答——执行侧不回头去问它。
       */
      float packDistance();

      /** 把第 {@code cascade} 个级联的地形画进它的阴影贴图；画不了时是空操作。 */
      void renderTerrain(int cascade);

      /** 所有级联之后刷新 Sodium 的 uniform；没有 Sodium 时是空操作。 */
      void flushSodiumUniforms();

      /** 把收集到的实体画进当前级联的阴影贴图。 */
      void renderEntities();

      /** 画手持光源那一关的阴影；未启用时是空操作。 */
      void renderHeldLight();

      /** 记录一次失败。模块只报告，不重抛。 */
      void reportFailure(RuntimeException failure);

      /** 收尾本帧的 {@code FeatureRenderDispatcher}；还没建起来时是空操作。 */
      void endFrame();
   }

   /**
    * {@link Context} 的组装式实现：一帧装一次，把调用方的 lambda 与值一次装齐。
    * <p>
    * 放在这里而不是让 mixin 写一个匿名类，是为了让所有对 mixin 私有成员的引用都留在
    * **lambda 体**里——那些 lambda 编译成 mixin 自己的合成方法，引用重写一定覆盖得到；
    * 而 mixin 的内部类要另走一套处理，是这个项目里没有先例的一条路。
    * <p>
    * 它**只装效果**：三条"要不要做"的事实现在住在 {@link ShadowPassFacts} 里，由
    * {@code execute} 单独收一个参数（见那个类型的注释）。
    * <p>
    * 组件名与 {@link Context} 的方法名同名的（{@code levelState}/{@code encoder}）由记录自动生成的
    * 访问器直接满足；其余在这里显式转接。
    */
   public record Frame(
          LevelRenderState levelState,
          CommandEncoder encoder,
          float packDistance,
          TerrainRenderer terrain,
          Runnable uniformFlush,
          Runnable entities,
          Runnable heldLightRenderer,
          Consumer<RuntimeException> failureReporter,
          Runnable frameEnd) implements Context {

      @Override
      public void renderTerrain(int cascade) {
         this.terrain.render(cascade);
      }

      @Override
      public void flushSodiumUniforms() {
         this.uniformFlush.run();
      }

      @Override
      public void renderEntities() {
         this.entities.run();
      }

      @Override
      public void renderHeldLight() {
         this.heldLightRenderer.run();
      }

      @Override
      public void reportFailure(RuntimeException failure) {
         this.failureReporter.accept(failure);
      }

      @Override
      public void endFrame() {
         this.frameEnd.run();
      }

      /** 一个级联的地形渲染。单独成形是为了不必为 {@code int} 装箱。 */
      @FunctionalInterface
      public interface TerrainRenderer {
         void render(int cascade);
      }
   }
}
