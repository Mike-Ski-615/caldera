package com.caldera.shaders.graph;

import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.runtime.FakeShaderHost;
import java.util.Map;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NativePackRuntime} 的三条契约：门面在**未安装**时的安全答案、渲染器失败的记录语义、
 * 以及 frame 与 scene 的顺序强制。
 * <p>
 * 全部在 {@code active == null} 之下跑（见 candidate 1 第 4 轮的决定）：{@code active} 是具体的
 * {@link GraphRenderer}，要让"有 renderer"的路径也可测，就得造一个接口里带着 Minecraft 类型的
 * seam，而 mixin 最终还是要拿到具体对象。剩下的由 {@code caldera.worldSmokeImage} 的前后截图覆盖。
 * <p>
 * <b>顺序为什么值得单独测：</b>改动前 {@code sceneScope} 把两件事混在一个 boolean 里——
 * "我们在世界渲染里"（{@code scope}）和"相机就绪、可以画"（{@code beginScene}）。
 * 那条依赖只存在于 mixin 的注入顺序里：{@code NativeSceneMixin} 在 {@code renderLevel} 首尾调
 * {@code scope}，{@code LevelRendererPostMixin} 在 {@code LevelRenderer.render} 头部调
 * {@code beginScene}。读代码看不出来，而顺序错了只是安静地画错一帧。
 */
class NativePackRuntimeLifecycleTest {

   /** 每一步都换一个新的，免得一个测试的摆布漏到下一个。 */
   private FakeShaderHost host;

   @BeforeEach
   void resetToNotInstalled() {
      this.host = new FakeShaderHost();
      NativePackRuntime.uninstall();
   }

   @AfterEach
   void leaveNotInstalled() {
      NativePackRuntime.uninstall();
   }

   /** 一个真实可用的相机与视图。探针实测过：新建的 CameraRenderState 就能过 cameraReady。 */
   private static void beginFrameScene() {
      NativePackRuntime.beginScene(new CameraRenderState(), new Matrix4f(), null, 0.0F);
   }

   // ---------------------------------------------------------------- 未安装时的安全答案

   @Test
   void queriesAnswerSafelyWhenNoInstanceIsInstalled() {
      assertNull(NativePackRuntime.failure());
      assertNull(NativePackRuntime.heldShadows());
      assertNull(NativePackRuntime.materials());
      assertNull(NativePackRuntime.weatherView());

      assertFalse(NativePackRuntime.shadowFrameReady());
      assertFalse(NativePackRuntime.usesNativeTransparency());
      assertFalse(NativePackRuntime.animatedShadowCasters());
      assertFalse(NativePackRuntime.replacesEnvironment(true));
      assertFalse(NativePackRuntime.replacesEnvironment(false));

      assertEquals(0, NativePackRuntime.shadowQuality());
      assertEquals(128, NativePackRuntime.shadowDistance());
      assertEquals(0L, NativePackRuntime.renderedFrames());
      assertEquals(0L, NativePackRuntime.sceneReplacementCount());
      assertEquals(0L, NativePackRuntime.terrainCaptures());
   }

   @Test
   void renderReportsThatNothingWasDrawnWhenNoInstanceIsInstalled() {
      // 三个参数都是 null：未安装时门面必须在**触碰它们之前**就返回 false。
      assertFalse(NativePackRuntime.render(null, null, null));
   }

   @Test
   void everyVoidFacadeIsANoOpWhenNoInstanceIsInstalled() {
      assertDoesNotThrow(() -> {
         NativePackRuntime.captureHandProjection(null);
         NativePackRuntime.captureWorldProjection(null);
         NativePackRuntime.scope(true);
         NativePackRuntime.beginScene(null, null, null, 0.0F);
         NativePackRuntime.finishScene();
         NativePackRuntime.bindSceneUniforms(null, null);
         NativePackRuntime.captureTerrain();
         NativePackRuntime.captureTranslucentDepth();
         NativePackRuntime.captureWorldDepth();
         NativePackRuntime.failScene(new IllegalStateException("nobody is listening"));
         NativePackRuntime.flushGeometryRebuild();
         NativePackRuntime.requestGeometryRebuild();
         NativePackRuntime.activate(null, null);
         NativePackRuntime.close();
         // scope(false) 也必须是空操作：顺序强制只在**装好之后**才生效，
         // 否则"未安装"这个安全状态本身就会开始抛。
         NativePackRuntime.scope(false);
      });

      assertNull(NativePackRuntime.failure());
      assertFalse(NativePackRuntime.shadowFrameReady());
   }

   @Test
   void theLifecycleEntriesRefuseToRunWithoutAnInstance() {
      assertThrows(IllegalStateException.class, () -> NativePackRuntime.prepare(null));
      assertThrows(IllegalStateException.class, () -> NativePackRuntime.settings("__builtin__"));
      assertThrows(IllegalStateException.class, () -> NativePackRuntime.applyOptions("__builtin__", java.util.Map.of()));
   }

   // ---------------------------------------------------------------- 失败记录的语义

   @Test
   void aSceneFailureIsRecordedAsTheRendererFailure() {
      NativePackRuntime.install(this.host);
      NativePackRuntime.scope(true);
      NativePackRuntime.failScene(new IllegalStateException("shadow producer did not run"));

      // 相机从未就绪，所以 finishScene 不会去渲染，只会消费这个失败。
      NativePackRuntime.finishScene();

      String failure = NativePackRuntime.failure();
      assertNotNull(failure);
      assertTrue(failure.contains("shadow producer did not run"), failure);
   }

   @Test
   void aSceneFailureIsConsumedExactlyOnce() {
      NativePackRuntime.install(this.host);

      NativePackRuntime.scope(true);
      NativePackRuntime.failScene(new IllegalStateException("first"));
      NativePackRuntime.finishScene();
      String first = NativePackRuntime.failure();
      assertNotNull(first);

      // 下一帧：sceneFailure 已被清空，不许再产生一次失败记录。
      NativePackRuntime.scope(true);
      NativePackRuntime.finishScene();
      assertEquals(first, NativePackRuntime.failure());
   }

   @Test
   void activatingClearsTheRecordedFailure() {
      NativePackRuntime.install(this.host);
      NativePackRuntime.scope(true);
      NativePackRuntime.failScene(new IllegalStateException("boom"));
      NativePackRuntime.finishScene();
      assertNotNull(NativePackRuntime.failure());

      // activate(null, id) 不是空操作：它正是"把光影关掉"时的那条路径。
      NativePackRuntime.activate(null, null);

      assertNull(NativePackRuntime.failure());
   }

   @Test
   void closingDoesNotClearTheRecordedFailure() {
      NativePackRuntime.install(this.host);
      NativePackRuntime.scope(true);
      NativePackRuntime.failScene(new IllegalStateException("boom"));
      NativePackRuntime.finishScene();
      String failure = NativePackRuntime.failure();
      assertNotNull(failure);

      NativePackRuntime.close();

      // 这条不对称是**故意的**：退出时要让界面仍然能显示上一次为什么失败。
      // activate 清、close 不清——两者的差别是行为，不是笔误。
      assertEquals(failure, NativePackRuntime.failure());
   }

   // ---------------------------------------------------------------- frame 与 scene 的顺序

   @Test
   void aFrameSceneBecomesReadyWithoutASeparateSceneOpen() {
      NativePackRuntime.install(this.host);

      NativePackRuntime.scope(true);
      beginFrameScene();

      // beginScene 自己开 scene：不再需要在它之前再手工开一个"scene 作用域"。
      assertTrue(NativePackRuntime.shadowFrameReady());
   }

   @Test
   void finishingASceneClosesIt() {
      NativePackRuntime.install(this.host);
      NativePackRuntime.scope(true);
      beginFrameScene();
      assertTrue(NativePackRuntime.shadowFrameReady());

      NativePackRuntime.finishScene();

      assertFalse(NativePackRuntime.shadowFrameReady());
   }

   @Test
   void aPendingGeometryRebuildKeepsTheSceneClosed() {      NativePackRuntime.install(this.host);
      NativePackRuntime.scope(true);
      NativePackRuntime.requestGeometryRebuild();

      beginFrameScene();

      assertFalse(NativePackRuntime.shadowFrameReady());
   }

   @Test
   void flushingAPendingRebuildClearsItAndLetsTheNextFrameBegin() {
      NativePackRuntime.install(this.host);

      NativePackRuntime.scope(true);
      NativePackRuntime.requestGeometryRebuild();
      beginFrameScene();
      assertFalse(NativePackRuntime.shadowFrameReady());

      // 没有世界（host.level() 为 null）时不许提交命令、也不许动几何——
      // 这两步在原件里是同一个 `client.level != null` 守卫罩着的两件事。
      NativePackRuntime.flushGeometryRebuild();
      assertTrue(this.host.gpuCommands.isEmpty());
      NativePackRuntime.finishScene();

      // 下一帧：pending 已经清掉，scene 就绪。
      NativePackRuntime.scope(true);
      beginFrameScene();
      assertTrue(NativePackRuntime.shadowFrameReady());
   }

   @Test
   void flushingWithoutAPendingRebuildIsANoOp() {
      NativePackRuntime.install(this.host);

      // 只有"确实有待重建"时 flush 才会去碰游戏，所以这一条在纯 JVM 里是安全的。
      assertDoesNotThrow(NativePackRuntime::flushGeometryRebuild);
      assertTrue(this.host.gpuCommands.isEmpty());
   }

   @Test
   void beginningASceneTwiceWithoutFinishingThrows() {
      NativePackRuntime.install(this.host);
      NativePackRuntime.scope(true);
      beginFrameScene();

      assertThrows(IllegalStateException.class, NativePackRuntimeLifecycleTest::beginFrameScene);
   }

   @Test
   void beginningASceneOutsideAFrameScopeThrows() {
      NativePackRuntime.install(this.host);

      // LevelRenderer.render 总是在 GameRenderer.renderLevel 里面，
      // 所以"没有 frame scope 就 begin"一定是注入点被挪错了。
      assertThrows(IllegalStateException.class, NativePackRuntimeLifecycleTest::beginFrameScene);
   }

   @Test
   void openingAFrameScopeTwiceThrows() {
      NativePackRuntime.install(this.host);
      NativePackRuntime.scope(true);

      assertThrows(IllegalStateException.class, () -> NativePackRuntime.scope(true));
   }

   @Test
   void closingAFrameScopeThatWasNeverOpenedThrows() {
      NativePackRuntime.install(this.host);

      assertThrows(IllegalStateException.class, () -> NativePackRuntime.scope(false));
   }

   @Test
   void finishingAFrameThatNeverBeganASceneIsANoOp() {
      NativePackRuntime.install(this.host);
      NativePackRuntime.scope(true);

      // 资源重载期间 LevelRendererPostMixin 会跳过 beginScene，而 scope 与 finishScene 照常发。
      // 那条路径每次重载都会走，它不是 bug——所以"开了 frame 但没开 scene"必须安静地过去。
      assertDoesNotThrow(NativePackRuntime::finishScene);
      assertFalse(NativePackRuntime.shadowFrameReady());
   }

   // ---------------------------------------------------------------- 游戏能力走端口之后

   @Test
   void prepareBuildsNoRendererWhenTheBackendIsNotReady() throws Exception {
      NativePackRuntime.install(this.host);
      this.host.vulkan = false;

      // 开关是开的，但后端不是 Vulkan：原件在这里返回 null，而不是抛。
      assertNull(NativePackRuntime.prepare(new ShaderConfig()));
      assertFalse(this.host.prepareCalled);
   }

   @Test
   void prepareBuildsNoRendererWhenShadersAreDisabled() throws Exception {
      NativePackRuntime.install(this.host);
      this.host.vulkan = true;

      assertNull(NativePackRuntime.prepare(new ShaderConfig(false, "__builtin__")));
   }

   // ---------------------------------------------------------------- 包选项的持久化走端口

   /**
    * 存下来的选项现在通过 {@link FakeShaderHost} 的内存替身进来，于是"读出来的旧值被迁移成什么"
    * 这条路径第一次测得动。夹具用的是真实的内置包，它的 COLOR_GRADE 定义正好是那四档。
    */
   @Test
   void aSavedColorGradeOf075IsMigratedToVibrantWhenItIsRead() throws Exception {
      NativePackRuntime.install(this.host);
      this.host.storedPackOptions.put("__builtin__", Map.of("COLOR_GRADE", 0.75));

      PackGraph graph = NativePackRuntime.settings("__builtin__");

      // 迁移必须发生在 withOptions 之前：0.75 不在定义里，直接并进去会抛 IllegalArgumentException。
      assertEquals(1.0, graph.options().get("COLOR_GRADE"));
      assertTrue(this.host.events.contains("loadPackOptions:__builtin__"), "必须走端口读，而不是自己碰文件");
   }

   /** 定义之外的值得丢掉、回落到包自己声明的 default，而不是让整份选项读失败。 */
   @Test
   void aSavedValueOutsideTheDefinitionsIsDroppedInFavourOfTheDefault() throws Exception {
      NativePackRuntime.install(this.host);
      this.host.storedPackOptions.put("__builtin__", Map.of("COLOR_GRADE", 0.9, "CLOUD_QUALITY", 2.0));

      PackGraph graph = NativePackRuntime.settings("__builtin__");

      assertEquals(0.5, graph.options().get("COLOR_GRADE"), "0.9 不在四档里，回落到 default");
      assertEquals(2.0, graph.options().get("CLOUD_QUALITY"), "同一次读取里合法的值照常生效");
   }

   @Test
   void anUnreadablePackOptionsFileIsReportedAsAnIoFailure() {
      NativePackRuntime.install(this.host);
      this.host.packOptionsFailure = new IllegalStateException("bad json");

      assertThrows(java.io.IOException.class, () -> NativePackRuntime.settings("__builtin__"));
   }
}
