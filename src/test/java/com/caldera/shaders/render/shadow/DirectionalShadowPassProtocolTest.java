package com.caldera.shaders.render.shadow;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阴影关卡的**执行顺序**。
 * <p>
 * 这套顺序原先只在 {@code LevelRendererShadowMixin} 的一个 lambda 体里，改动它没有任何安全网：
 * 上传漏一次会用上一帧的矩阵，{@code endCascade} 漏一次会让之后所有渲染都以为自己在阴影贴图里，
 * 两者都不抛异常、都只是画面安静地不对。这里把设备侧与帧侧都换成记录式的实现，
 * 于是"给定这样一份计划，到底按什么顺序调了什么"变成一条可以逐字对照的序列。
 * <p>
 * 这些测试不需要 GPU：{@link DirectionalShadowPass.Device} 是记录器，
 * {@link DirectionalShadowPass.Context} 也是。
 */
class DirectionalShadowPassProtocolTest {

   // ---------------------------------------------------------------- 级联循环

   @Test
   void aCascadeThatNeedsBothUploadsOnceThenDrawsTerrainBeforeEntities() {
      Recorder rec = new Recorder();
      rec.terrainUpdate(0, true);
      rec.entityUpdate(0, true);

      rec.run();

      assertEquals(
            List.of("prepare", "upload:0", "clear:0", "begin:0", "terrain:0", "end",
                  "clearEntity:0", "beginEntity:0", "entities", "end",
                  "flush", "shadowData", "heldLight", "endFrame"),
            rec.calls);
   }

   @Test
   void terrainOnlyNeverTouchesTheEntityPass() {
      Recorder rec = new Recorder();
      rec.terrainUpdate(0, true);

      rec.run();

      assertEquals(
            List.of("prepare", "upload:0", "clear:0", "begin:0", "terrain:0", "end", "flush", "shadowData", "heldLight", "endFrame"),
            rec.calls);
   }

   @Test
   void entityOnlyNeverTouchesTheTerrainPass() {
      Recorder rec = new Recorder();
      rec.entityUpdate(0, true);

      rec.run();

      assertEquals(
            List.of("prepare", "upload:0", "clearEntity:0", "beginEntity:0", "entities", "end", "flush", "shadowData", "heldLight", "endFrame"),
            rec.calls);
   }

   /**
    * 这一条是本类存在的第一理由：{@code uploadCascade} 在一次迭代里恰好一次。
    * 地形与实体共用同一份级联矩阵与光照方向，多传一次会把同一段 UBO 重写；
    * 而"两个都更新时传两次"不会报错，只是白做一遍。
    */
   @Test
   void aCascadeThatNeedsBothUploadsExactlyOnce() {
      Recorder rec = new Recorder();
      rec.terrainUpdate(0, true);
      rec.entityUpdate(0, true);

      rec.run();

      assertEquals(1, rec.count("upload:0"));
   }

   @Test
   void aCascadeThatNeedsNothingIsNotEvenUploaded() {
      Recorder rec = new Recorder();

      rec.run();

      assertFalse(rec.calls.contains("upload:0"));
      assertFalse(rec.calls.contains("upload:1"));
   }

   @Test
   void everyActiveCascadeIsVisitedOnceInOrder() {
      Recorder rec = new Recorder();
      rec.activeCascades = 3;
      rec.terrainUpdate(0, true);
      rec.terrainUpdate(1, true);
      rec.entityUpdate(1, true);
      rec.terrainUpdate(2, true);

      rec.run();

      assertEquals(
            List.of("prepare",
                  "upload:0", "clear:0", "begin:0", "terrain:0", "end",
                  "upload:1", "clear:1", "begin:1", "terrain:1", "end", "clearEntity:1", "beginEntity:1", "entities", "end",
                  "upload:2", "clear:2", "begin:2", "terrain:2", "end",
                  "flush", "shadowData", "heldLight", "endFrame"),
            rec.calls);
   }

   /**
    * 有实体级联要更新、但这一帧没收集到任何实体提交时，**清理与作用域照做**——
    * 原件就是这样：那个 {@code hasEntitySubmits} 只罩住渲染那一行，不罩 clear／begin／end。
    * 把守卫上提会改变"上一帧的实体阴影残留"的处理方式。
    */
   @Test
   void anEntityPassWithoutSubmitsStillClearsAndScopesButDoesNotDraw() {
      Recorder rec = new Recorder();
      rec.entityUpdate(0, true);
      rec.entitySubmits = false;

      rec.run();

      assertTrue(rec.calls.contains("clearEntity:0"));
      assertTrue(rec.calls.contains("beginEntity:0"));
      assertFalse(rec.calls.contains("entities"));
      assertEquals(1, rec.count("end"));
   }

   // ---------------------------------------------------------------- 循环之后

   @Test
   void shadowDataGoesUpAfterTheSodiumFlushAndBeforeHeldLight() {
      Recorder rec = new Recorder();

      rec.run();

      int flush = rec.calls.indexOf("flush");
      int shadowData = rec.calls.indexOf("shadowData");
      int heldLight = rec.calls.indexOf("heldLight");
      assertTrue(flush >= 0 && shadowData > flush, "shadow data 必须在刷 uniform 之后：" + rec.calls);
      assertTrue(heldLight > shadowData, "手持光源必须在 shadow data 之后：" + rec.calls);
   }

   @Test
   void shadowDataIsNotUploadedWhenNobodyConsumesIt() {
      Recorder rec = new Recorder();
      rec.shadowDataConsumed = false;

      rec.run();

      assertFalse(rec.calls.contains("shadowData"));
      // 但其余几步照常：这个判断只罩住上传那一行。
      assertTrue(rec.calls.contains("flush"));
      assertTrue(rec.calls.contains("endFrame"));
   }

   @Test
   void heldLightIsSkippedWhenItIsNotActive() {
      Recorder rec = new Recorder();
      rec.heldLightActive = false;

      rec.run();

      assertFalse(rec.calls.contains("heldLight"));
   }

   /**
    * 一个级联都不生效时，循环体一次都不进，但循环之后那几步与收尾照常跑。
    * "阴影开着、这一帧却一个级联都没有"正是门禁那条结构性断言要抓的状态，
    * 所以这条路径本身必须是完整的。
    */
   @Test
   void zeroActiveCascadesStillFlushAndFinish() {
      Recorder rec = new Recorder();
      rec.activeCascades = 0;

      rec.run();

      assertEquals(List.of("prepare", "flush", "shadowData", "heldLight", "endFrame"), rec.calls);
   }

   // ---------------------------------------------------------------- 失败

   @Test
   void aFailureWhileDrawingTerrainIsReportedAndDoesNotEscape() {
      Recorder rec = new Recorder();
      rec.activeCascades = 2;
      rec.terrainUpdate(0, true);
      rec.terrainUpdate(1, true);
      RuntimeException boom = new RuntimeException("boom");
      rec.terrainFailure = boom;

      rec.run();

      assertSame(boom, rec.reported);
      // begin 的作用域必须被 end 收掉，即使渲染抛了。
      assertEquals(1, rec.count("end"));
      // 第二个级联不该被碰。
      assertFalse(rec.calls.contains("upload:1"));
      // 循环之后那几步不再跑，但 endFrame 一定跑。
      assertFalse(rec.calls.contains("flush"));
      assertTrue(rec.calls.contains("endFrame"));
   }

   @Test
   void aFailureWhileRenderingEntitiesIsReportedAndTheScopeIsStillClosed() {
      Recorder rec = new Recorder();
      rec.entityUpdate(0, true);
      RuntimeException boom = new RuntimeException("boom");
      rec.entityFailure = boom;

      rec.run();

      assertSame(boom, rec.reported);
      assertEquals(1, rec.count("end"));
      assertTrue(rec.calls.contains("endFrame"));
   }

   /**
    * {@code prepare()} 的失败与后面任何一步同等对待：**被报告、并且走收尾**。
    * <p>
    * 这条**原先是反过来的**：那时 {@code prepare()} 在 {@code try} 之外（0.5.1 原件的写法），
    * 于是准备阶段的异常不报告、不重抛、{@code endFrame()} 也不跑——脏的 dispatcher 状态
    * 连同"渲染帧崩掉"一起留给用户。测试当时存在的意义是"让把它挪进 try 变成一次有意识的选择"，
    * 现在那个选择已经做了，所以它钉的是新语义。
    * <p>
    * 后果上的一点不对称值得记下：prepare 失败时后面的步骤（循环、刷 uniform、上传 shadow data）
    * 都不会跑——这是自然的，因为设备侧还没准备好；而收尾必须跑。
    */
   @Test
   void aFailureWhilePreparingIsReportedAndStillEndsTheFrame() {
      Recorder rec = new Recorder();
      RuntimeException boom = new RuntimeException("prepare failed");
      rec.prepareFailure = boom;

      assertDoesNotThrow(rec::run);

      assertSame(boom, rec.reported, "准备失败必须走失败通道");
      assertTrue(rec.calls.contains("endFrame"), "准备失败也必须收尾");
      assertFalse(rec.calls.contains("flush"), "设备侧没准备好，后面几步不该跑");
      assertFalse(rec.calls.contains("shadowData"));
   }

   // ---------------------------------------------------------------- 记录器

   /**
    * 设备侧与帧侧合成一个对象：所有调用都写进**同一个** {@code calls} 列表，
    * 所以两边的相对顺序能被逐字断言——分成两个记录器就看不到"先刷 uniform 还是先上传"了。
    */
   private static final class Recorder implements DirectionalShadowPass.Device, DirectionalShadowPass.Context {
      final List<String> calls = new ArrayList<>();
      int activeCascades = 3;
      private final boolean[] terrainUpdates = new boolean[4];
      private final boolean[] entityUpdates = new boolean[4];
      boolean entitySubmits = true;
      boolean shadowDataConsumed = true;
      boolean heldLightActive = true;
      RuntimeException prepareFailure;
      RuntimeException terrainFailure;
      RuntimeException entityFailure;
      RuntimeException reported;

      void terrainUpdate(int cascade, boolean value) {
         this.terrainUpdates[cascade] = value;
      }

      void entityUpdate(int cascade, boolean value) {
         this.entityUpdates[cascade] = value;
      }

      int count(String call) {
         int total = 0;

         for(String entry : this.calls) {
            if (entry.equals(call)) {
               ++total;
            }
         }

         return total;
      }

      void run() {
         DirectionalShadowPass.execute(this, new ShadowPassFacts(this.entitySubmits, this.shadowDataConsumed, this.heldLightActive), this);
      }

      // -------- Device

      public void prepare(LevelRenderState levelState, float packDistance) {
         this.calls.add("prepare");
         if (this.prepareFailure != null) {
            throw this.prepareFailure;
         }
      }

      public int activeCascadeCount() {
         return this.activeCascades;
      }

      public boolean shouldUpdateCascade(int cascade) {
         return this.terrainUpdates[cascade];
      }

      public boolean shouldUpdateEntityCascade(int cascade) {
         return this.entityUpdates[cascade];
      }

      public void uploadCascade(CommandEncoder encoder, int cascade) {
         this.calls.add("upload:" + cascade);
      }

      public void uploadShadowData(CommandEncoder encoder) {
         this.calls.add("shadowData");
      }

      public void clearCascade(CommandEncoder encoder, int cascade) {
         this.calls.add("clear:" + cascade);
      }

      public void clearEntityCascade(CommandEncoder encoder, int cascade) {
         this.calls.add("clearEntity:" + cascade);
      }

      public void beginCascade(int cascade) {
         this.calls.add("begin:" + cascade);
      }

      public void beginEntityCascade(int cascade) {
         this.calls.add("beginEntity:" + cascade);
      }

      public void endCascade() {
         this.calls.add("end");
      }

      // -------- Context

      public LevelRenderState levelState() {
         return null;
      }

      public float packDistance() {
         return 128.0F;
      }

      public CommandEncoder encoder() {
         return null;
      }

      public void renderTerrain(int cascade) {
         this.calls.add("terrain:" + cascade);
         if (this.terrainFailure != null) {
            throw this.terrainFailure;
         }
      }

      public void flushSodiumUniforms() {
         this.calls.add("flush");
      }

      public void renderEntities() {
         this.calls.add("entities");
         if (this.entityFailure != null) {
            throw this.entityFailure;
         }
      }

      public void renderHeldLight() {
         this.calls.add("heldLight");
      }

      public void reportFailure(RuntimeException failure) {
         this.reported = failure;
         this.calls.add("failure");
      }

      public void endFrame() {
         this.calls.add("endFrame");
      }
   }
}
