package com.caldera.shaders.render.shadow;

import com.caldera.shaders.config.ShaderQualityPreset;
import com.caldera.shaders.render.sky.CustomCelestials;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日月方向的**只读访问器**的契约：{@link CascadePlanner} 上的两个包可见访问器，以及
 * {@link DirectionalShadowRenderer} 上把它们转成公开方法的那两个。
 * <p>
 * 为什么值得钉：开发期门禁要用它把相机对准天上的那个天体（见 {@code src/smoke} 里
 * {@code NativeEnvironmentSmoke} 的 sun/moon 两关）。这两个方向在生产侧本来就是算阴影光照方向的
 * **中间量**，所以门禁读它们并不算新开一条接缝；但也正因为它们是中间量，很容易被误当成
 * "光照方向"——那一个被刻意夹到 y ≥ 0.08 并归一化了，**夜里指着它会把相机抬向错误的方向**。
 * 下面第二条断言钉的就是这一点。
 * <p>
 * 第四条钉的是门禁真正用的那个面：{@code prepare()} 每帧刷新这两个值，而它刷新的是
 * planner 的状态而不是 {@code schedule}，所以公开访问器必须读 planner。
 */
class CelestialDirectionAccessTest {
   /** 太阳角 0.9：{@code CustomCelestials} 的 y = cos(角度) ≈ 0.62，在地平线之上。 */
   private static final float DAY_ANGLE = 0.9F;
   /** 太阳角 3.5：y = cos(3.5) ≈ −0.94，在地平线之下。 */
   private static final float NIGHT_ANGLE = 3.5F;

   @AfterEach
   void leaveNoInstanceBehind() {
      DirectionalShadowRenderer.close();
      DirectionalShadowRenderer.uninstall();
   }

   /** 与生产同一条公式算出来的期望值——不在这里重写三角学，直接问那个纯函数。 */
   private static Vector3f rawDirection(float angle) {
      Vector3f out = new Vector3f();
      CustomCelestials.setCelestialDirection(angle, out);
      return out;
   }

   private static void assertDirection(Vector3f expected, Vector3f actual, String what) {
      assertEquals(expected.x, actual.x, 1.0E-6F, what + ".x");
      assertEquals(expected.y, actual.y, 1.0E-6F, what + ".y");
      assertEquals(expected.z, actual.z, 1.0E-6F, what + ".z");
   }

   /** 推一帧计划。{@code skyPresent=false} 用来模拟"这一帧拿不到天空状态"。 */
   private static CascadeSchedule plan(CascadePlanner planner, boolean skyPresent, float sunAngle, float moonAngle) {
      CameraRenderState camera = new CameraRenderState();
      camera.pos = new Vec3(0.0, 64.0, 0.0);
      camera.projectionMatrix = new Matrix4f().perspective((float)Math.toRadians(70.0), 1.5F, 0.05F, 1000.0F);
      camera.viewRotationMatrix = new Matrix4f().rotateY((float)Math.toRadians(30.0));
      return planner.plan(
            ShaderQualityPreset.MEDIUM,
            camera.pos.x,
            camera.pos.y,
            camera.pos.z,
            camera.viewRotationMatrix,
            camera.projectionMatrix,
            skyPresent,
            sunAngle,
            moonAngle,
            12,
            384.0F,
            true,
            false,
            8192,
            0L,
            false);
   }

   @Test
   void theAccessorsGiveTheRawCelestialDirections() {
      CascadePlanner planner = new CascadePlanner();
      planner.updateCelestialDirections(DAY_ANGLE, NIGHT_ANGLE);

      assertDirection(rawDirection(DAY_ANGLE), planner.sunDirection(), "太阳");
      assertDirection(rawDirection(NIGHT_ANGLE), planner.moonDirection(), "月亮");
   }

   @Test
   void theRawDirectionIsNotTheClampedShadowLightDirection() {
      CascadePlanner planner = new CascadePlanner();
      planner.updateCelestialDirections(NIGHT_ANGLE, DAY_ANGLE);
      CascadeSchedule schedule = plan(planner, true, NIGHT_ANGLE, DAY_ANGLE);

      assertTrue(planner.sunDirection().y < 0.0F, "3.5 弧度时太阳应当在地平线以下");
      assertTrue(
            schedule.lightDirection().y() >= 0.08F,
            "阴影光照方向被夹到最低仰角，所以它与原始日月方向不是同一个东西");
   }

   @Test
   void aFrameWithoutASkyStateCannotOverwriteTheCelestialDirections() {
      CascadePlanner planner = new CascadePlanner();
      planner.updateCelestialDirections(DAY_ANGLE, NIGHT_ANGLE);
      // 拿不到天空状态的那一帧：这两个参数是占位符，不许被当成真的角度用。
      plan(planner, false, NIGHT_ANGLE, DAY_ANGLE);

      assertDirection(rawDirection(DAY_ANGLE), planner.sunDirection(), "太阳");
      assertDirection(rawDirection(NIGHT_ANGLE), planner.moonDirection(), "月亮");
   }

   @Test
   void thePublicRendererAccessorsReadTheCurrentCelestialState() {
      // 直接建一个：实例只建一次（装配时由 install 建），而这里要的只是一个可驱动的渲染器，
      // 不必去动那个进程级实例。
      DirectionalShadowRenderer renderer = new DirectionalShadowRenderer();
      renderer.planner().updateCelestialDirections(DAY_ANGLE, NIGHT_ANGLE);

      Vector3f sun = new Vector3f();
      Vector3f moon = new Vector3f();
      assertDirection(rawDirection(DAY_ANGLE), renderer.sunDirection(sun), "太阳");
      assertDirection(rawDirection(NIGHT_ANGLE), renderer.moonDirection(moon), "月亮");
   }
}
