package com.caldera.shaders.render.shadow;

import com.caldera.shaders.config.ShaderQualityPreset;
import com.caldera.shaders.render.sky.CustomCelestials;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CascadePlanner} 的纯决策契约。
 * <p>
 * 这些断言以前**不可能存在**：那套决策与 RenderTarget 分配、GPU 上传住在同一个类里，要碰它就得先有
 * 一个真的客户端与设备。现在输入全是值，所以这里能用真实的 {@link CameraRenderState}、
 * {@link Matrix4f} 与 {@code Vec3} 把每一帧摆出来，逐帧断言"谁该重画"。
 * <p>
 * 为什么值得这样测：级联调度错了不抛异常。它只会安静地少画一层影子（抖动的边界、缺失的远景影子）
 * 或者每帧重画（帧率掉一半），两者在截图上都很难认。
 */
class CascadePlannerTest {

   /** 相机与投影用真实类型：planner 的输入就是这些值，测试不该比生产更"假"。 */
   private static CameraRenderState camera(double x, double y, double z) {
      CameraRenderState camera = new CameraRenderState();
      camera.pos = new Vec3(x, y, z);
      camera.projectionMatrix = new Matrix4f().perspective((float)Math.toRadians(70.0), 1.5F, 0.05F, 1000.0F);
      camera.viewRotationMatrix = new Matrix4f().rotateY((float)Math.toRadians(30.0));
      return camera;
   }

   /**
    * 一帧的输入。字段可改，{@link #plan} 按当前字段算一帧——测试里改哪一项，读起来就是那一项。
    */
   private static final class Frame {
      ShaderQualityPreset quality = ShaderQualityPreset.MEDIUM;
      CameraRenderState camera = camera(0.0, 64.0, 0.0);
      boolean skyPresent = true;
      float sunAngle = 0.9F;
      float moonAngle = 3.5F;
      int renderDistance = 12;
      float shadowDistance = 384.0F;
      boolean entityShadows = true;
      boolean animatedCasters = false;
      int maxShadowSize = 8192;
      long revision = 0L;
      boolean resourcesChanged = false;

      CascadeSchedule plan(CascadePlanner planner) {
         return planner.plan(
               this.quality,
               this.camera.pos.x,
               this.camera.pos.y,
               this.camera.pos.z,
               this.camera.viewRotationMatrix,
               this.camera.projectionMatrix,
               this.skyPresent,
               this.sunAngle,
               this.moonAngle,
               this.renderDistance,
               this.shadowDistance,
               this.entityShadows,
               this.animatedCasters,
               this.maxShadowSize,
               this.revision,
               this.resourcesChanged);
      }
   }

   // ---------------------------------------------------------------- 生效级联数

   @Test
   void theActiveCascadeCountFollowsTheQualityPreset() {
      assertEquals(0, CascadePlanner.activeCascadeCount(ShaderQualityPreset.OFF));
      assertEquals(1, CascadePlanner.activeCascadeCount(ShaderQualityPreset.LOW));
      assertEquals(3, CascadePlanner.activeCascadeCount(ShaderQualityPreset.MEDIUM));
      assertEquals(4, CascadePlanner.activeCascadeCount(ShaderQualityPreset.HIGH));
      assertEquals(4, CascadePlanner.activeCascadeCount(ShaderQualityPreset.ULTRA));
   }

   @Test
   void theScheduleReportsTheCascadeCountOfItsPreset() {
      for(ShaderQualityPreset preset : ShaderQualityPreset.values()) {
         Frame frame = new Frame();
         frame.quality = preset;
         assertEquals(CascadePlanner.activeCascadeCount(preset), frame.plan(new CascadePlanner()).activeCascadeCount(), preset.name());
      }
   }

   @Test
   void cascadesOutsideTheActiveCountAreNeverScheduled() {
      Frame frame = new Frame();
      frame.quality = ShaderQualityPreset.LOW;
      CascadeSchedule schedule = frame.plan(new CascadePlanner());

      assertTrue(schedule.cascadeUpdate(0));
      for(int cascade = 1; cascade < 4; ++cascade) {
         assertFalse(schedule.cascadeUpdate(cascade));
         assertFalse(schedule.entityCascadeUpdate(cascade));
         assertEquals(0.0F, schedule.cascadeEnd(cascade));
      }
   }

   // ---------------------------------------------------------------- 第一帧与后续帧

   @Test
   void aFreshPlannerMarksEveryActiveCascadeForUpdate() {
      Frame frame = new Frame();
      CascadeSchedule first = frame.plan(new CascadePlanner());

      for(int cascade = 0; cascade < first.activeCascadeCount(); ++cascade) {
         assertTrue(first.cascadeUpdate(cascade), "cascade " + cascade);
      }

      assertTrue(first.entityCascadeUpdate(0));
   }

   @Test
   void aSecondIdenticalFrameUpdatesNothingBecauseTheIntervalHasNotElapsed() {
      CascadePlanner planner = new CascadePlanner();
      Frame frame = new Frame();
      frame.plan(planner);

      CascadeSchedule second = frame.plan(planner);

      // 帧号差 1，而级联 0 的间隔是 3；相机没动、光照没变、投影没变。所以一个都不该重画。
      for(int cascade = 0; cascade < second.activeCascadeCount(); ++cascade) {
         assertFalse(second.cascadeUpdate(cascade), "cascade " + cascade);
      }
   }

   @Test
   void theUpdateIntervalsFollowTheCascadeTable() {
      CascadePlanner planner = new CascadePlanner();
      Frame frame = new Frame();
      frame.plan(planner);
      frame.plan(planner);
      frame.plan(planner);
      CascadeSchedule fourth = frame.plan(planner);

      // 级联 0 的间隔是 3，级联 1 是 8：第四帧（帧号差正好 3）只有级联 0 到期。
      assertTrue(fourth.cascadeUpdate(0));
      assertFalse(fourth.cascadeUpdate(1));
      assertFalse(fourth.cascadeUpdate(2));
   }

   @Test
   void movingTheCameraEnoughAlsoForcesAnUpdate() {
      CascadePlanner planner = new CascadePlanner();
      Frame frame = new Frame();
      frame.plan(planner);

      // 级联 0 的移动阈值是 1.5 格；探针取 2 格，稳稳越过。
      frame.camera = camera(2.0, 64.0, 0.0);
      CascadeSchedule moved = frame.plan(planner);

      assertTrue(moved.cascadeUpdate(0));
      assertFalse(moved.cascadeUpdate(1));
      assertFalse(moved.cascadeUpdate(2));
   }

   @Test
   void turningTheCameraEnoughAlsoForcesAnUpdate() {
      CascadePlanner planner = new CascadePlanner();
      Frame frame = new Frame();
      frame.plan(planner);

      // 相机朝向变了 90 度：点积远低于 0.9659 的门槛。
      CameraRenderState turned = camera(0.0, 64.0, 0.0);
      turned.viewRotationMatrix = new Matrix4f().rotateY((float)Math.toRadians(-60.0));
      frame.camera = turned;
      CascadeSchedule after = frame.plan(planner);

      assertTrue(after.cascadeUpdate(0));
      assertTrue(after.cascadeUpdate(1));
      assertTrue(after.cascadeUpdate(2));
   }

   // ---------------------------------------------------------------- 地形修订号

   @Test
   void aChangedTerrainRevisionForcesEveryCascadeToUpdate() {
      CascadePlanner planner = new CascadePlanner();
      Frame frame = new Frame();
      frame.plan(planner);

      // 第二帧：相机、投影、光照都没动，唯一变的是地形修订号。
      frame.revision = 1L;
      CascadeSchedule afterTerrainChange = frame.plan(planner);

      for(int cascade = 0; cascade < afterTerrainChange.activeCascadeCount(); ++cascade) {
         assertTrue(afterTerrainChange.cascadeUpdate(cascade), "cascade " + cascade);
      }
   }

   @Test
   void anUnchangedTerrainRevisionDoesNotForceAnUpdate() {
      CascadePlanner planner = new CascadePlanner();
      Frame frame = new Frame();
      frame.revision = 7L;
      frame.plan(planner);

      // 修订号一样，所以这条不构成理由——第二帧仍然不该重画。
      CascadeSchedule second = frame.plan(planner);
      assertFalse(second.cascadeUpdate(0));
   }

   // ---------------------------------------------------------------- 动画投射者

   @Test
   void animatedCastersForceEveryCascadeToUpdateEveryFrame() {
      CascadePlanner still = new CascadePlanner();
      Frame stillFrame = new Frame();
      stillFrame.plan(still);
      CascadeSchedule stillSecond = stillFrame.plan(still);
      assertFalse(stillSecond.cascadeUpdate(0), "对照组：没有会动的投射者时第二帧不该重画");

      CascadePlanner wind = new CascadePlanner();
      Frame windFrame = new Frame();
      windFrame.animatedCasters = true;
      windFrame.plan(wind);
      CascadeSchedule windSecond = windFrame.plan(wind);

      // 包声明了会动的投射者：级联矩阵的 update 是 false，但**地形更新**仍必须是 true——
      // 影子内容每帧都在动，缓存的地形阴影贴图就每一帧都过期。
      for(int cascade = 0; cascade < windSecond.activeCascadeCount(); ++cascade) {
         assertTrue(windSecond.cascadeUpdate(cascade), "cascade " + cascade);
      }
   }

   // ---------------------------------------------------------------- 布局变化

   @Test
   void aChangedProjectionCountsAsALayoutChangeAndForcesAnUpdate() {
      CascadePlanner planner = new CascadePlanner();
      Frame frame = new Frame();
      frame.plan(planner);

      CameraRenderState zoomed = camera(0.0, 64.0, 0.0);
      zoomed.projectionMatrix = new Matrix4f().perspective((float)Math.toRadians(45.0), 1.5F, 0.05F, 1000.0F);
      frame.camera = zoomed;
      CascadeSchedule afterZoom = frame.plan(planner);

      for(int cascade = 0; cascade < afterZoom.activeCascadeCount(); ++cascade) {
         assertTrue(afterZoom.cascadeUpdate(cascade), "cascade " + cascade);
      }

      // 投影变了还会强制实体阴影级联重画（layoutChanged 是 entityUpdate 的一个条件）。
      assertTrue(afterZoom.entityCascadeUpdate(0));
   }

   @Test
   void rebuiltResourcesCountAsALayoutChange() {
      CascadePlanner planner = new CascadePlanner();
      Frame frame = new Frame();
      frame.plan(planner);

      frame.resourcesChanged = true;
      CascadeSchedule afterRebuild = frame.plan(planner);

      for(int cascade = 0; cascade < afterRebuild.activeCascadeCount(); ++cascade) {
         assertTrue(afterRebuild.cascadeUpdate(cascade), "cascade " + cascade);
      }
   }

   @Test
   void aChangingLayoutBumpsTheLayoutVersionAndIdenticalFramesDoNot() {
      CascadePlanner planner = new CascadePlanner();
      Frame frame = new Frame();
      CascadeSchedule first = frame.plan(planner);
      assertEquals(1L, first.layoutVersion(0), "第一帧初始化即 bump 一次");

      // 一模一样的第二帧：不该再 bump。
      CascadeSchedule second = frame.plan(planner);
      assertEquals(1L, second.layoutVersion(0));
   }

   // ---------------------------------------------------------------- 覆盖范围

   @Test
   void coverageIsClampedByTheRenderDistance() {
      // 渲染距离 4 区块 = 64 格：正好等于下限，于是覆盖范围被夹到 64，而不是包声明的 384。
      Frame near = new Frame();
      near.renderDistance = 4;
      near.shadowDistance = 384.0F;
      assertEquals(64.0F, near.plan(new CascadePlanner()).shadowDistance(), 0.01F);

      // 渲染距离够远：这时才轮到包声明的阴影距离生效。
      Frame far = new Frame();
      far.renderDistance = 100;
      far.shadowDistance = 384.0F;
      assertEquals(384.0F, far.plan(new CascadePlanner()).shadowDistance(), 0.01F);

      // 包自己声明的距离比 64 还小：取包的值，下限不参与。
      Frame small = new Frame();
      small.renderDistance = 12;
      small.shadowDistance = 48.0F;
      assertEquals(48.0F, small.plan(new CascadePlanner()).shadowDistance(), 0.01F);
   }

   @Test
   void coverageIsCoverageTimesSixteenBlocksPerChunk() {
      Frame frame = new Frame();
      frame.renderDistance = 20;
      frame.shadowDistance = 384.0F;

      // 20 区块 = 320 格，仍小于 384。
      assertEquals(320.0F, frame.plan(new CascadePlanner()).shadowDistance(), 0.05F);
   }

   // ---------------------------------------------------------------- 实体级联

   @Test
   void entityCascadesOnlyExistForTheFirstCascade() {
      Frame frame = new Frame();
      CascadeSchedule schedule = frame.plan(new CascadePlanner());
      assertTrue(schedule.activeCascadeCount() > 1);

      assertTrue(schedule.entityCascadeEnd(0) > 0.0F);
      assertEquals(0.0F, schedule.entityCascadeEnd(1));
      assertEquals(schedule.cascadeEnd(0), schedule.entityCascadeEnd(0));
   }

   @Test
   void entityCascadesAreNotScheduledWhenEntityShadowsAreDisabled() {
      Frame frame = new Frame();
      frame.entityShadows = false;
      CascadeSchedule schedule = frame.plan(new CascadePlanner());

      for(int cascade = 0; cascade < 4; ++cascade) {
         assertFalse(schedule.entityCascadeUpdate(cascade));
      }
   }

   // ---------------------------------------------------------------- 暂停

   @Test
   void aSuspendedFrameKeepsTheLastPlanStateButReportsNoCascades() {
      CascadePlanner planner = new CascadePlanner();
      Frame frame = new Frame();
      CascadeSchedule scheduled = frame.plan(planner);
      assertTrue(scheduled.activeCascadeCount() > 0);

      CascadeSchedule suspended = planner.suspend();

      // 迁移前那条 else 分支只把 activeCascadeCount 归零：矩阵与端点原样留着，
      // 因为 uploadShadowData 会照读它们。带守卫的读取（cascadeEnd / shadowDistance）
      // 因此看到 0——那也是迁移前的结果，因为它们的守卫就是这个数。
      assertEquals(0, suspended.activeCascadeCount());
      assertEquals(0.0F, suspended.shadowDistance());
      assertEquals(0.0F, suspended.cascadeEnd(0));
      assertEquals(scheduled.cascadeEnd(0), suspended.cascadeEndSlot(0));
      assertFalse(suspended.cascadeUpdate(0));
   }

   @Test
   void suspendingAFreshPlannerIsSafe() {
      CascadeSchedule suspended = new CascadePlanner().suspend();

      assertEquals(0, suspended.activeCascadeCount());
      assertEquals(0.0F, suspended.shadowDistance());
      assertFalse(suspended.cascadeUpdate(0));
   }

   // ---------------------------------------------------------------- 光照方向与相机朝向

   @Test
   void theLightDirectionIsTheHigherCelestialAndStaysNormalized() {
      Frame frame = new Frame();
      frame.sunAngle = 0.9F;
      frame.moonAngle = 3.5F;
      CascadeSchedule daytime = frame.plan(new CascadePlanner());

      // 太阳角 0.9 的 y 是正的、月亮角 3.5 的 y 是负的，所以取太阳。
      Vector3f expected = new Vector3f();
      CustomCelestials.setCelestialDirection(0.9F, expected);
      assertTrue(daytime.lightDirection().y() > 0.0F);
      assertEquals(1.0F, daytime.lightDirection().length(), 1.0E-4F);
      assertEquals(expected.x, daytime.lightDirection().x(), 1.0E-4F);
      assertEquals(expected.y, daytime.lightDirection().y(), 1.0E-4F);
      assertEquals(expected.z, daytime.lightDirection().z(), 1.0E-4F);
   }

   @Test
   void aCelestialBelowTheHorizonIsLiftedToTheMinimumElevation() {
      Frame frame = new Frame();
      // 两个都在地平线以下（cos 为负），所以无论如何都会走到那条 0.08 的夹取。
      frame.sunAngle = 3.0F;
      frame.moonAngle = 4.0F;
      CascadeSchedule night = frame.plan(new CascadePlanner());

      assertTrue(night.lightDirection().y() > 0.0F, "光照方向不允许朝下：那会让整张阴影贴图退化");
      assertEquals(1.0F, night.lightDirection().length(), 1.0E-4F);
   }

   @Test
   void withoutASkyStateTheLightDirectionIsTheConstantFallback() {
      Frame frame = new Frame();
      frame.skyPresent = false;
      CascadeSchedule schedule = frame.plan(new CascadePlanner());

      Vector3f expected = (new Vector3f(-0.35F, 0.82F, -0.44F)).normalize();
      assertEquals(expected.x, schedule.lightDirection().x(), 1.0E-5F);
      assertEquals(expected.y, schedule.lightDirection().y(), 1.0E-5F);
      assertEquals(expected.z, schedule.lightDirection().z(), 1.0E-5F);
      assertEquals(1.0F, schedule.celestialShadowFade(), 0.0F);
   }

   @Test
   void theCameraForwardFollowsTheViewRotationAndFallsBackWhenThereIsNone() {
      Frame frame = new Frame();
      CascadeSchedule rotated = frame.plan(new CascadePlanner());

      // 视图旋转是绕 Y 轴 30 度：相机朝向由 (0,0,-1) 转过去，仍然是单位向量。
      assertEquals(1.0F, rotated.cameraForward().length(), 1.0E-4F);
      assertTrue(Math.abs(rotated.cameraForward().x()) > 0.01F, "转了 30 度之后 x 不该还是 0");

      frame.camera.viewRotationMatrix = null;
      CascadeSchedule identity = frame.plan(new CascadePlanner());

      assertEquals(0.0F, identity.cameraForward().x(), 1.0E-6F);
      assertEquals(0.0F, identity.cameraForward().y(), 1.0E-6F);
      assertEquals(-1.0F, identity.cameraForward().z(), 1.0E-6F);
   }
}
