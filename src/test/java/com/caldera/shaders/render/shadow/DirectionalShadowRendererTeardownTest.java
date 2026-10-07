package com.caldera.shaders.render.shadow;

import com.caldera.shaders.config.ShaderQualityPreset;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拆卸路径的契约：{@code close()} **不再换掉实例**、**不**清计划状态（那条不对称是刻意保留的），
 * 以及它现在**把拆除排到栅栏之后**。
 * <p>
 * 最后这一条在纯 JVM 里观测不到差别：没有设备时它退回同步拆除，所以这里看到的仍是"资源确实被拆了"。
 * 真机里那一步会晚一两帧（由 {@code Minecraft} 每帧的 {@code executePendingTasks()} 推进），
 * 而那正是这次修复的目的——见 {@code close()} 的注释。
 * <p>
 * <b>实例只建一次</b>（{@code DirectionalShadowRenderer.get()}），所以每个用例必须自己
 * {@link #installed()}，而收尾要 {@code uninstall()}——否则"装配之前"那个 null 状态会随执行顺序
 * 时有时无。
 */
class DirectionalShadowRendererTeardownTest {

   @AfterEach
   void leaveNoInstanceBehind() {
      DirectionalShadowRenderer.close();
      DirectionalShadowRenderer.uninstall();
   }

   /** 直接建一个可驱动的渲染器。**不碰**那个进程级实例（它由 {@code install} 建）。 */
   private static DirectionalShadowRenderer installed() {
      return new DirectionalShadowRenderer();
   }

   /** 用真实的相机与投影推一帧计划，让计划状态里确实有东西可被清掉。 */
   private static void driveOneFrame(CascadePlanner planner) {
      CameraRenderState camera = new CameraRenderState();
      camera.pos = new Vec3(0.0, 64.0, 0.0);
      camera.projectionMatrix = new Matrix4f().perspective((float)Math.toRadians(70.0), 1.5F, 0.05F, 1000.0F);
      camera.viewRotationMatrix = new Matrix4f().rotateY((float)Math.toRadians(30.0));
      planner.plan(
            ShaderQualityPreset.MEDIUM,
            camera.pos.x,
            camera.pos.y,
            camera.pos.z,
            camera.viewRotationMatrix,
            camera.projectionMatrix,
            true,
            0.9F,
            3.5F,
            12,
            384.0F,
            true,
            false,
            8192,
            0L,
            false);
   }

   /**
    * 没有实例时（装配之前）{@code close()} 是空操作。
    * <p>
    * 这个状态现在是"装好之前"，而不再是"上一个实例刚被 close 掉"——实例只建一次，
    * 见 {@code DirectionalShadowRenderer.get()}。
    */
   @Test
   void closeWithoutAnInstanceIsANoOp() {
      // 装配之前：uninstall 之后那个进程级实例就是 null。
      assertNull(DirectionalShadowRenderer.get(), "装配之前没有实例");

      assertDoesNotThrow(DirectionalShadowRenderer::close);
   }

   /**
    * 同一个实例上 {@code close()} 可以重复调用，而且它**不重置计划状态**。
    * <p>
    * 这条钉的是阶段二改掉的那条行为：以前 {@code close()} 会把 {@code instance} 清掉，
    * 于是"关掉阴影再打开"会拿到一个计划状态从头开始的新实例。现在实例只建一次，
    * 拆的只是 GPU 资源，而重新分配资源会让 {@code ensureResources} 报"资源变过"，计划因此重算。
    */
   @Test
   void closeCanBeCalledTwiceAndDoesNotResetThePlan() {
      DirectionalShadowRenderer shadows = installed();
      driveOneFrame(shadows.planner());
      long churn = shadows.planStats().totalLayoutVersionChurn();
      assertTrue(churn > 0L);

      assertDoesNotThrow(() -> {
         DirectionalShadowRenderer.close();
         DirectionalShadowRenderer.close();
      });

      assertFalse(shadows.resourcesReady(), "资源确实被拆了（没有设备时退回同步拆除）");
      assertEquals(churn, shadows.planStats().totalLayoutVersionChurn(), "重复 close 不该重置计划");
   }

   /**
    * 什么都没分配过时 {@code close()} 安全——没有设备时它退回同步拆除，而
    * {@code destroyTargets} 只遍历四个槽位并检查 null，不碰 {@code RenderSystem}、不碰 GPU。
    */
   @Test
   void closeIsSafeWhenNothingWasAllocated() {
      installed();

      assertDoesNotThrow(DirectionalShadowRenderer::close);
   }

   @Test
   void retireUnusedIsSafeWhenNothingWasEverCreated() {
      // instance 为 null：retireUnused 的第一道条件就不成立，于是它连栅栏都不排。
      assertNull(DirectionalShadowRenderer.get());

      assertDoesNotThrow(DirectionalShadowRenderer::retireUnused);
   }

   /**
    * {@code retireUnused()} 在**有实例、但一个 RenderTarget 都没分配过**时也安全——但这一条在纯 JVM 里
    * <b>测不到</b>，所以这里没有测试，只有这段说明。
    * <p>
    * 原因实测如下：那条路径会调 {@code RenderSystem.queueFencedTask}，而它在没有设备时直接抛
    * {@code IllegalStateException: Can't getDevice() before it was initialized}。
    * 也就是说"有实例"这个前提本身就需要一个真的 {@code RenderSystem}。上一条测试覆盖的是
    * instance 为 null 的那半（真正的"什么都没创建过"），那半确实可测。
    */

   /**
    * {@code close()} 清掉的是 GPU 资源，**不是**计划状态。
    * <p>
    * 这条不对称是迁移前就有的、也是刻意保留的：{@code ensureResources}（经 {@code retireTargets}）
    * 与 {@code retireUnused} 会把上一次的资源尺寸归零，而 {@code close()} 不动帧号、布局版本、
    * 每个级联上次渲染的位置。改动它就不是"行为保持的提取"了。
    * <p>
    * 观察对象是 {@code planner()} 而不是渲染器对外发布的 schedule：后者只由 {@code prepare()} 写，
    * 而 {@code prepare()} 要真的设备（它第一步就是 {@code ensureResources}），在纯 JVM 里到不了。
    * 未使用 {@code shadows.activeCascadeCount()} 正是这个原因——它读的是那份没被写过的快照。
    */
   @Test
   void closeDoesNotClearThePlanStateOfTheInstanceItCloses() {
      DirectionalShadowRenderer shadows = installed();
      CascadePlanner planner = shadows.planner();
      driveOneFrame(planner);
      long churn = planner.stats().totalLayoutVersionChurn();
      assertTrue(churn > 0L);

      DirectionalShadowRenderer.close();

      assertSame(planner, shadows.planner(), "close() 不该换掉计划");
      assertEquals(churn, shadows.planner().stats().totalLayoutVersionChurn(), "close() 不该清掉布局版本的累计");
      // 纹理派生的那两个量是**会被**清掉的（destroyTargets 一直如此），且它们不属于计划历史。
      assertEquals(1L, shadows.planner().stats().terrainUpdates(0), "close() 不该清掉每级联的更新计数");
   }

   /**
    * {@code close()} 之后**同一个实例**还在，它的计划历史也是连续的。
    * <p>
    * 这条取代了旧的 {@code aFreshInstanceAfterCloseStartsFromNothing}：那条钉的是"close 会置空实例，
    * 下一次 {@code get()} 拿到计划状态从头开始的新实例"，而阶段二把这个行为去掉了——实例只建一次，
    * 拆的只是 GPU 资源。之所以不需要靠重建实例来重置计划，是因为
    * {@code CascadePlanner.suspend()} 会在未启用时把生效级联数归零，而重新分配资源会让
    * {@code ensureResources} 报"资源变过"，计划因此重算。
    */
   @Test
   void closeKeepsThePlanHistoryOnTheSameInstance() {
      DirectionalShadowRenderer shadows = installed();
      driveOneFrame(shadows.planner());
      long churn = shadows.planStats().totalLayoutVersionChurn();
      assertTrue(churn > 0L);

      DirectionalShadowRenderer.close();

      assertFalse(shadows.resourcesReady(), "资源确实被拆了（没有设备时退回同步拆除）");
      assertEquals(churn, shadows.planStats().totalLayoutVersionChurn(),
            "计划历史连续——与旧的“close 之后从头开始”相反");
   }

   /**
    * <b>这里故意没有测试：{@code install()} 装的依赖必须活过 {@code close()} 拆解那一刻。</b>
    * <p>
    * 那条不变量是被一次真实崩溃逼出来的：资源重载会走
    * {@code closeReloadableResources()} → {@link DirectionalShadowRenderer#close()}，而它把
    * {@code instance} 置空。曾经把宿主与包状态挂在**实例**上，于是重载之后 {@code get()} 建出来的
    * 就是"没装宿主"的那一个，{@code prepare()} 随即在渲染帧里抛 {@code IllegalStateException}
    * 把游戏崩掉。修法是让这两样留在静态字段上（见 {@code host} 的注释）。
    * <p>
    * <b>为什么钉不住，实测如下。</b>{@code prepare()} 里读到宿主的那条路，前提是质量档位大于零；
    * 而质量来自"有一份生效的包图 → 有一个渲染器"，那条链在纯 JVM 里造不出来（{@code GraphRenderer}
    * 的构造要真的 {@code GpuDevice}，见 {@code SceneFrameTest} 记的同一道界限）。绕开 {@code prepare}
    * 直接断言依赖也**不行**：字段声明一旦是 {@code static}，无论 {@code install} 写的是
    * {@code host} 还是 {@code get().host}，读到的都是同一个值——那样的断言恒真，试过，确实恒真，
    * 所以删掉了，不在这里留一条骗人的绿灯。
    * <p>
    * 因此这条不变量的守卫是：{@code host} 字段上的注释（写明"必须是静态的，以及为什么"），
    * 以及一次客户端内资源重载的手工验收。这是本项目已知的验证缺口之一，与 ADR-0003 记的
    * "渲染结果未被逐像素比对"同类。
    */
}
