package com.caldera.shaders.graph;

import com.caldera.shaders.render.shadow.HeldLightShadowRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4fc;

/**
 * 「这一帧没有生效的渲染器」——缺席侧的唯一实现，把这个状态的含义写成**一个可以通读的类**。
 * <p>
 * 它取代的是 {@link SceneFrame} 里原先散在二十处的 {@code renderer == null} 分支。那些分支的答案
 * 逐字搬到这里，一条都没改：{@code null}、{@code false}、{@code 0}、{@code 0L}、空操作、以及
 * 「传进来的描述符原样返回」。所以这次改动对缺席这一侧是**等价的**，
 * {@code SceneFrameTest.everythingThatNeedsARendererAnswersSafelyWithoutOne} 就是它的证明。
 * <p>
 * <b>它与 {@code NotInstalledPackRuntime} 不是同一件事，别合并。</b>那一个答的是「整份光影运行时还没装」
 * ——客户端初始化之前、以及每个测试 JVM 里都存在；这一个答的是「运行时装好了，但没有包生效」。
 * 两者有两处**故意不同**的回答，正是它们不是同一件事的证据：
 * <ul>
 *   <li>{@code shadowFrameReady()} 在那一边无条件 {@code false}，而这一边根本不看有没有渲染器
 *       （见 {@link SceneFrame#shadowFrameReady()}）；</li>
 *   <li>{@code render(null, null, null)} 在那一边安全返回 {@code false}（它承诺在触碰参数之前就回答），
 *       而 {@link SceneFrame#render} 会照常读 {@code config.enabled()}。</li>
 * </ul>
 * <p>
 * <b>但它确实是"缺席时答什么"的那一份定义。</b>{@code NotInstalledPackRuntime} 里那些答案相同的查询
 * 直接委托 {@link #INSTANCE}——那些常量（{@code 0} / {@code 128} / {@code null} / {@code false} /
 * "原样返回"）因此只写一遍。两者不同的是**语义**（"没装"vs"没有包生效"），相同的是那几个数值。
 * <p>
 * 无状态、可共享，所以是常量而不是每次新建——{@link #INSTANCE} 取代了原先那个 {@code null}。
 */
final class AbsentRenderer implements ActiveRenderer {

   /** 唯一的实例：它没有状态，谁拿到都一样。 */
   static final AbsentRenderer INSTANCE = new AbsentRenderer();

   private AbsentRenderer() {
   }

   /** 这一条就是它存在的理由：缺席要说"我不在"，而不是让调用方去比 {@code null}。 */
   @Override
   public boolean present() {
      return false;
   }

   /** 空操作：没有资源可释放。 */
   @Override
   public void close() {
   }

   /**
    * {@code null}，与迁移前那处 {@code renderer == null ? null : ...} 逐字一致。
    * <p>
    * 契约写在这里而不是在接口上：接口那条注释说明了为什么这个返回值允许为 {@code null}。
    */
   @Override
   public MaterialTable materials() {
      return null;
   }

   @Override
   public HeldLightShadowRenderer heldShadows() {
      return null;
   }

   @Override
   public long renderedFrames() {
      return 0L;
   }

   @Override
   public long sceneReplacementCount() {
      return 0L;
   }

   @Override
   public long terrainCaptures() {
      return 0L;
   }

   @Override
   public int shadowQuality() {
      return 0;
   }

   /**
    * {@code 128}，与包自己的 {@code SHADOW_DISTANCE} 默认值一致。
    * <p>
    * 这条链是**可达**的：门面与门禁都会读它（包没生效时也该答一个合理的距离，
    * 因为 {@link SceneFrame} 还要拿它算"要不要重建几何"那一类的事）。
    * <p>
    * 它曾经与另一个数并列讨论过：{@code ShadowService.distance()} 在质量为零时返回
    * {@code 384.0F}，而那个值不可达（那条链已被收掉，见 {@code DirectionalShadowRenderer} 里
    * "帧输入"的注释）。现在阴影距离只有一个来源，这里不会再与它混淆。
    */
   @Override
   public int shadowDistance() {
      return 128;
   }

   @Override
   public boolean animatedShadowCasters() {
      return false;
   }

   @Override
   public PackGraph.Environment environment() {
      return null;
   }

   /** 空操作：没有渲染器会读这个投影。 */
   @Override
   public void handProjection(Matrix4fc projection) {
   }

   /** 空操作。 */
   @Override
   public void projection(Matrix4fc projection) {
   }

   /** 空操作。 */
   @Override
   public void environment(LevelRenderState state, ClientLevel level, float partialTick) {
   }

   /** 空操作：没有渲染器要开。 */
   @Override
   public void beginScene(RenderTarget target, CameraRenderState camera, Matrix4fc view, Object world) {
   }

   /** 空操作。 */
   @Override
   public void invalidateHistory() {
   }

   /** 空操作：什么都没画。 */
   @Override
   public void render(RenderTarget main, CameraRenderState camera, Matrix4fc view, Object world) {
   }

   @Override
   public GpuTextureView weatherView() {
      return null;
   }

   /** 空操作：没有渲染器要捕获。 */
   @Override
   public void captureTerrain(RenderTarget main) {
   }

   /** 空操作。 */
   @Override
   public void captureWorldDepth(RenderTarget main) {
   }

   /** 原样返回：没有渲染器可以接管这个 pass。 */
   @Override
   public RenderPassDescriptor sceneAttachments(RenderPassDescriptor descriptor, RenderTarget main) {
      return descriptor;
   }

   /** {@code false}：没有声明过任何场景目标。 */
   @Override
   public boolean hasSceneAttachments(List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments) {
      return false;
   }

   /** 原样返回：不替换任何管线。 */
   @Override
   public RenderPipeline scenePipeline(RenderPipeline original, boolean attachments) {
      return original;
   }

   /** 原样返回。 */
   @Override
   public RenderPipeline sceneFallback(RenderPipeline original, boolean attachments) {
      return original;
   }

   /** 空操作。 */
   @Override
   public void bindSceneUniforms(RenderPass pass, RenderPipeline pipeline) {
   }
}
