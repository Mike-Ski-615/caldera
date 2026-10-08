package com.caldera.shaders.graph;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.render.shadow.HeldLightShadowRenderer;
import com.caldera.shaders.runtime.ShaderHost;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4fc;

/**
 * "什么都没装"这一状态下的答案。
 * <p>
 * 它把这个状态的含义写成**一个可以通读的类**：不渲染、不拆除、不记失败、空查询、{@code false}、
 * {@code 0}、{@code 128}，而那三个生命周期入口**抛**（它们只在安装之后才会被走到，走到这里就说明
 * 初始化顺序错了）。
 * <p>
 * 这些答案与迁移前那些静态字段的初值逐字一致，也与第一轮那道 {@code instance == null} 分支逐字一致
 * ——它们是 ADR-0003 记下的契约，不是这里新定的。钉住它们的用例在
 * {@code NativePackRuntimeLifecycleTest} 里，那四条用例一个字都不用改。
 * <p>
 * 无状态、可共享，所以是常量而不是每次新建。
 */
final class NotInstalledPackRuntime implements PackRuntime {

   /** 唯一的实例：它没有状态，谁拿到都一样。 */
   static final NotInstalledPackRuntime INSTANCE = new NotInstalledPackRuntime();

   private static final String NOT_INSTALLED = "Caldera native pack runtime is not installed";

   private NotInstalledPackRuntime() {
   }

   // ------------------------------------------------------------- 渲染器生命周期

   /** 空操作：没有渲染器可关。 */
   @Override
   public void close() {
   }

   @Override
   public GraphRenderer prepare(ShaderConfig config) {
      throw new IllegalStateException(NOT_INSTALLED);
   }

   @Override
   public PackGraph settings(String id) {
      throw new IllegalStateException(NOT_INSTALLED);
   }

   @Override
   public void applyOptions(String id, Map<String, Double> values) {
      throw new IllegalStateException(NOT_INSTALLED);
   }

   /** 空操作：没有当前渲染器，也就没有"换掉"这回事。 */
   @Override
   public void activate(GraphRenderer next, String id) {
   }

   /** 空操作——注意它**不**记账：未安装时标脏地形是没有意义的，而记下来会让安装后的第一帧拒绝开场景。 */
   @Override
   public void requestGeometryRebuild() {
   }

   @Override
   public String failure() {
      return null;
   }

   // ------------------------------------------------------------- 场景帧

   /** 以下都是空操作：未安装时没有场景帧，`scope(false)` 也必须是空操作，否则"未安装"本身会开始抛。 */
   @Override
   public void captureHandProjection(Matrix4fc projection) {
   }

   /** 空操作：没有渲染器会消费这个值。 */
   @Override
   public void captureHeldItemWorldPosition(float x, float y, float z, boolean mainHand) {
   }

   @Override
   public void captureWorldProjection(Matrix4fc projection) {
   }

   @Override
   public void scope(boolean enabled) {
   }

   @Override
   public void beginScene(CameraRenderState currentCamera, Matrix4fc currentView, LevelRenderState state, float partialTick) {
   }

   @Override
   public void finishScene() {
   }

   /** 原样返回：没有渲染器可以接管这个 pass。 */
   @Override
   public RenderPassDescriptor sceneAttachments(RenderPassDescriptor descriptor) {
      return descriptor;
   }

   /** 原样返回：不包装任何 pass。 */
   @Override
   public RenderPass wrapScenePass(RenderPass pass, RenderPassDescriptor descriptor) {
      return pass;
   }

   /** 原样返回：不替换任何管线。 */
   @Override
   public RenderPipeline scenePipeline(RenderPipeline base, List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments) {
      return base;
   }

   @Override
   public void bindSceneUniforms(RenderPass pass, RenderPipeline pipeline) {
   }

   @Override
   public GpuTextureView weatherView() {
      return null;
   }

   @Override
   public void captureTerrain() {
   }

   @Override
   public void captureTranslucentDepth() {
   }

   @Override
   public void captureWorldDepth() {
   }

   /** 空操作：没有渲染器会读这条失败记录。 */
   @Override
   public void failScene(Exception problem) {
   }

   @Override
   public void flushGeometryRebuild() {
   }

   // ------------------------------------------------------------- 查询

   /** {@code false}：没有生效的包，也就没有"该不该投射阴影"这回事（与质量为零同一个答案）。 */
   @Override
   public boolean shadowsEnabled() {
      return false;
   }

   /**
    * 这些查询与 {@link AbsentRenderer}（"运行时装好了、没有包生效"那一份）**答案相同**，
    * 所以直接委托它——"缺席时答什么"在渲染器那道缝上已经有一份可读、可测的定义，这里不再抄第二遍。
    * <p>
    * 不委托的是那六个入口（未安装时要抛，见它们各自的注释）。
    */
   @Override
   public HeldLightShadowRenderer heldShadows() {
      return AbsentRenderer.INSTANCE.heldShadows();
   }

   /**
    * 这一问与 {@link #replacesEnvironment} 都**不能**委托给 {@link AbsentRenderer}：它们是
    * {@link SceneFrame} 的**帧状态**门闸（"有渲染器且不在重载中" / "有渲染器且场景就绪"），
    * 而不是渲染器对自己的回答——渲染器那一侧没有这两个方法。
    */
   @Override
   public boolean usesNativeTransparency() {
      return false;
   }

   /** 见 {@link #usesNativeTransparency()} 上那条注释：这也是帧状态门闸，不在渲染器那道缝上。 */
   @Override
   public boolean replacesEnvironment(boolean clouds) {
      return false;
   }

   @Override
   public int shadowDistance() {
      return AbsentRenderer.INSTANCE.shadowDistance();
   }

   @Override
   public boolean animatedShadowCasters() {
      return AbsentRenderer.INSTANCE.animatedShadowCasters();
   }

   @Override
   public MaterialTable materials() {
      return AbsentRenderer.INSTANCE.materials();
   }
}
