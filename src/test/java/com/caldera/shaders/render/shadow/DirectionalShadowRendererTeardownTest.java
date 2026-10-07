package com.caldera.shaders.render.shadow;

import com.caldera.shaders.config.ShaderQualityPreset;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拆卸路径的三条契约：{@code close()} 在没有分配过任何东西时安全、可以重复调用，
 * 以及它**不**清计划状态——最后这条是刻意保留的不对称。
 * <p>
 * 这些事实原先只存在于 {@code destroyTargets} 的循环体里，而且其中一条
 * （{@code close()} 同步拆纹理、{@code ensureResources}/{@code retireUnused} 走栅栏）根本读不出来。
 * 这里能钉住的部分就钉住；钉不住的部分在下面写清楚为什么。
 */
class DirectionalShadowRendererTeardownTest {

   @AfterEach
   void leaveNoInstanceBehind() {
      DirectionalShadowRenderer.close();
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

   @Test
   void closeIsSafeWhenNothingWasAllocated() {
      DirectionalShadowRenderer.get();

      // destroyTargets 只是遍历四个槽位并检查 null：没有 RenderTarget 时不碰 RenderSystem、不碰 GPU。
      assertDoesNotThrow(DirectionalShadowRenderer::close);
   }

   @Test
   void closeCanBeCalledTwice() {
      DirectionalShadowRenderer.get();

      assertDoesNotThrow(() -> {
         DirectionalShadowRenderer.close();
         // 第二次是同一条路径，但 instance 已经是 null——也就是"什么都没分配过"的那种安全情形。
         DirectionalShadowRenderer.close();
      });
   }

   @Test
   void closeWithoutAnInstanceIsANoOp() {
      DirectionalShadowRenderer.close();

      assertDoesNotThrow(DirectionalShadowRenderer::close);
   }

   @Test
   void retireUnusedIsSafeWhenNothingWasEverCreated() {
      // instance 为 null：retireUnused 的第一道条件就不成立，于是它连栅栏都不排。
      DirectionalShadowRenderer.close();

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
      DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
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

   @Test
   void aFreshInstanceAfterCloseStartsFromNothing() {
      DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
      driveOneFrame(shadows.planner());
      assertTrue(shadows.planner().stats().totalLayoutVersionChurn() > 0L);

      DirectionalShadowRenderer.close();
      DirectionalShadowRenderer fresh = DirectionalShadowRenderer.get();

      // 计划状态活在实例上，所以 get() 重建实例时它自然从头开始：这条与上一条并不矛盾，
      // 上一条说的是"close() 没有主动去清那个实例的计划"。
      assertNotSame(shadows, fresh);
      assertEquals(0, fresh.activeCascadeCount());
      assertEquals(0L, fresh.planStats().totalLayoutVersionChurn());
   }
}
