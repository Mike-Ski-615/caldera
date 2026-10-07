package com.caldera.shaders.graph;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.render.shadow.DirectionalShadowRenderer;
import com.caldera.shaders.render.shadow.HeldLightShadowRenderer;
import com.caldera.shaders.runtime.ShaderHost;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4fc;

/**
 * 本帧的**场景帧**：阶段、门闸，以及被门闸护着的那些操作。
 * <p>
 * 迁移前这些全都在 {@code NativePackRuntime} 里，与渲染器生命周期、包选项读写挤在同一个类；而门闸是
 * 逐处**重写的合取**——同一件事（"这一帧能不能画 / 能不能捕获 / 能不能读 uniform"）有八处调用点各自
 * 拼一遍，其中三处还故意少一两项条件，差异只存在于那些行本身里。加上 mixin 那十五处对"资源重载中"
 * 的重写，读一遍"这一帧到底在发生什么"要在两个模块和十七个文件之间来回跳。
 * <p>
 * 现在：**门闸与它护着的动作合成一个方法**。调用方不再问许可（{@code if (frame.canX()) frame.doX();}），
 * 而是直接要结果（{@code frame.captureTerrain();}），条件在哪里成立只此一处。这不是把谓词搬个家——
 * 纯谓词版的接口会几乎等于实现，那正是要消灭的形状。
 * <p>
 * <b>它持有"这一帧在用哪个渲染器"</b>（{@link #attach}/{@link #detach}），因为六个门闸里都有
 * {@code active != null} 这一项。渲染器的**生命周期政策**（prepare、activate、close、包选项、
 * 失败文案的持久化时机）仍在 {@code NativePackRuntime}：它决定什么时候换上/换下，
 * 这里只负责"换掉之后把旧的怎么退役"（{@link Disposal}）与"换上去之后这一帧怎么答门闸"。
 * <p>
 * <b>三个协作者都是注入的，不是静态伸手：</b>{@code resourceReloading}（"资源重载中"）、
 * {@code shadowPassActive}（"正在画阴影贴图"）、{@code config}（本帧生效的设置）。
 * 迁移前它们分别是 {@code ShaderRuntime.resourceReloading()}、{@code DirectionalShadowRenderer.isRenderingShadowMap()}
 * 与 {@code ShaderRuntime.config()} 的直接静态调用。注入之后这个模块可以完全脱离那三个单例被驱动——
 * 这正是它能在纯 JVM 里逐格钉死的原因。
 * <p>
 * {@code ScenePrograms} 与 {@code SceneRenderPass} 是同一个包里的既有模块：前者提供
 * "这条管线是不是被我替换过"，后者提供"把一段动作画在 scene pass 之外"。
 */
final class SceneFrame {

   private final ShaderHost host;
   private final Supplier<ShaderConfig> config;
   private final BooleanSupplier resourceReloading;
   private final BooleanSupplier shadowPassActive;

   /**
    * 当前生效的渲染器，没有时为 {@code null}。
    * <p>
    * 六个门闸读它；{@code NativePackRuntime} 通过 {@link #attach}/{@link #detach} 改它。
    */
   private GraphRenderer renderer;
   /** {@link #renderer} 对应的包 id；没有渲染器时为 {@code null}。 */
   private String rendererPackId;
   /** 渲染器上一次失败的描述，从未失败时为 {@code null}。 */
   private String failure;
   /**
    * 这一帧是否处在世界渲染里，也就是 {@code renderLevel} 已经进去、还没出来。
    * <p>
    * 由 {@link #scope} 开关。它与 {@link #sceneBegun} 是**两件事**：前者是"我们在世界渲染里"，
    * 后者是"相机已经就绪、可以画原生几何"。混成一个 boolean 会让下面那些"本帧到底能不能画"
    * 的判断读不出来。
    */
   private boolean frameScope;
   /**
    * 本帧的 scene 是否已经开过（也就是 {@link #beginScene} 走过就绪分支）。
    * <p>
    * 只用于顺序强制：同一个 frame scope 里开第二次就是 bug。它**不**决定能不能画——
    * 能画是 {@link #canCaptureScene()} 那一组说的。
    */
   private boolean sceneBegun;
   /**
    * 这一帧是否真的可以画原生几何，等于"frame scope 开着，且最近一次 {@code beginScene}
    * 没有说相机不可用"。
    */
   private boolean sceneActive;
   /** scene 期间累积的失败；由 {@link #finishScene} 消费一次。 */
   private Exception sceneFailure;
   /** 本帧的相机与视图矩阵；不在 scene 里时为 {@code null}。 */
   private CameraRenderState camera;
   private Matrix4fc view;
   /** 世界投影矩阵，由 mixin 在投影建立时推入。 */
   private Matrix4fc worldProjection;
   /** 地形几何是否待重建。 */
   private boolean geometryRebuildPending;

   SceneFrame(ShaderHost host, Supplier<ShaderConfig> config, BooleanSupplier resourceReloading, BooleanSupplier shadowPassActive) {
      this.host = host;
      this.config = config;
      this.resourceReloading = resourceReloading;
      this.shadowPassActive = shadowPassActive;
   }

   // ---------------------------------------------------------------- 渲染器的接入与退役

   /**
    * 换上一个渲染器，并清掉上一帧留下的失败记录。
    * <p>
    * 清失败这件事原本在 {@code activateRenderer} 里、换渲染器之前：那时序在这里没有可观测差别
    * （两次调用之间没有任何东西读它），所以并成一条。
    */
   void attach(GraphRenderer next, String packId) {
      this.sceneFailure = null;
      this.failure = null;
      this.renderer = next;
      this.rendererPackId = next == null ? null : packId;
   }

   /**
    * 把当前渲染器摘下来并处置旧的。
    * <p>
    * <b>三条拆除路径</b>——{@code activate}、{@link #pause(Exception)}、{@code closeActive}——
    * 真正重复的就是这一块：取走 {@code renderer}、清掉 {@code rendererPackId}、处置旧的。原先它
    * 写了两遍半，加一个拆除场景就要记得把这几行再抄一次。
    * <p>
    * <b>两条故意的差异不在这个方法里，而在调用点上，并且是显式参数：</b>
    * <ul>
    *    <li>{@code failure} 字段：attach <b>清</b>它、pause <b>设</b>它、close <b>不碰</b>它。
    *        那条不对称是原件的行为（界面上显示的就是它），所以留在各自的方法体里。</li>
    *    <li>处置方式：attach 与 pause 排到栅栏之后（{@link Disposal#QUEUED}），
    *        {@code closeActive} 是同步关。这个分歧是原件就有的，这里把它变成一个看得见的参数。</li>
    * </ul>
    *
    * @return 被摘下来的那个渲染器，没有就是 {@code null}（调用方还要用它算"要不要重建几何"）
    */
   GraphRenderer detach(Disposal disposal) {
      GraphRenderer old = this.renderer;
      this.renderer = null;
      this.rendererPackId = null;

      if (old != null) {
         if (disposal == Disposal.QUEUED) {
            this.host.queueFence(old::close);
         } else {
            old.close();
         }
      }

      return old;
   }

   /** 被摘下来的渲染器怎么处置。 */
   enum Disposal {
      /** 排到栅栏之后：当前帧可能还在用它的资源。 */
      QUEUED,
      /** 当场关掉。 */
      SYNCHRONOUS
   }

   /**
    * 暂停：记下失败、丢掉渲染器、排队关闭、请求重建几何。
    * <p>
    * 与 {@code closeActive()} 的差别是**故意的**：这里设置 {@code failure} 供界面显示，
    * 而 {@code closeActive()} 不清它。这条不对称是原件的行为，不是笔误——改它会改变用户看到的东西。
    */
   void pause(Exception problem) {
      this.failure = "Shader pack paused: " + problem.getMessage();
      LogUtils.getLogger().error(this.failure, problem);
      GraphRenderer old = this.detach(Disposal.QUEUED);
      if (old != null && old.materials().enabled()) {
         this.geometryRebuildPending = true;
      }
   }

   /** 渲染器上一次失败的描述，从未失败时为 {@code null}。 */
   String failure() {
      return this.failure;
   }

   /**
    * 这一帧该不该在这种设置下动手：开关 + 后端。**不含**"这个包是不是原生包"。
    * <p>
    * 走 {@code host.vulkanActive()} 而不是 {@code BackendStatus} 那个静态：生产实现里两者是同一行，
    * 但走端口之后这一问才在测试里摆得动——否则测一个"非 Vulkan 时不建渲染器"都要真的 GPU。
    * <p>
    * 原先 {@code prepare}／{@code applyOptions}／{@code render} 三处各自内联了同一个两元判断。
    * 现在渲染动作在本模块里，而生命周期那两处走这里，所以它仍然只有一份。
    */
   boolean backendReady(ShaderConfig config) {
      return config.enabled() && this.host.vulkanActive();
   }

   // ---------------------------------------------------------------- 门闸：三种形态，各一处

   /**
    * 最严的那一问：本帧的场景就绪、有渲染器、没失败、不在重载中。捕获与天气视图都用它。
    * <p>
    * 五项里少任何一项的表现都是**安静地画错或什么都不画**，所以它必须只有一个来源。
    */
   private boolean canDraw() {
      return this.sceneActive
            && this.camera != null
            && this.renderer != null
            && this.sceneFailure == null
            && !this.resourceReloading.getAsBoolean();
   }

   /**
    * 捕获主场景：在 {@link #canDraw()} 之上再排除"正在画阴影贴图"那一关。
    * <p>
    * 最后那一项原先**不在**这里，而在 {@code SodiumWorldRendererMixin} 的两处调用点上——也就是说
    * "要不要捕获场景"是 mixin 一侧与模块一侧的合取，谁都不能单独读明白。现在它回到同一处：
    * 那两个 mixin 只剩游戏侧的 {@code group == TRANSLUCENT}。合取一字未变，只是求值位置挪进了
    * 同一次调用的更深处。
    */
   private boolean canCaptureScene() {
      return this.canDraw() && !this.shadowPassActive.getAsBoolean();
   }

   /**
    * 接管 scene pass：比 {@link #canDraw()} 弱——**不看相机、不看本帧失败**。
    * <p>
    * 那两项不在这里不是漏写：接管发生在 {@code createRenderPass} 的那一刻，那时相机状态与本帧
    * 累积的失败都还没有意义；而失败恰恰要**走这条路径**才能落到 fallback 上（见
    * {@link #scenePipeline}）。加上它们会让失败的那一帧连 fallback 都拿不到。
    */
   private boolean canAttachScene() {
      return !this.shadowPassActive.getAsBoolean()
            && this.sceneActive
            && this.renderer != null
            && !this.resourceReloading.getAsBoolean();
   }

   // ---------------------------------------------------------------- frame 协议

   /**
    * 开／关本帧的 frame scope，并强制它是**平衡**的。
    * <p>
    * 这一对由 {@code NativeSceneMixin} 在 {@code renderLevel} 的首尾各调一次；开发期门禁也拿它给一段
    * 自建的 pass 圈一个作用域。不配对的调用（重复开、或没开就关）是 bug，而且原先是一个静默的状态
    * 赋值——一帧画错不会有任何提示。
    */
   void scope(boolean enabled) {
      if (enabled && this.frameScope) {
         throw new IllegalStateException("Caldera frame scope opened twice: a previous scope(true) was never closed");
      }

      if (!enabled && !this.frameScope) {
         throw new IllegalStateException("Caldera frame scope closed twice: scope(false) without an open scope(true)");
      }

      this.frameScope = enabled;
      this.sceneActive = enabled;
   }

   void captureWorldProjection(Matrix4fc projection) {
      this.worldProjection = new Matrix4f(projection);
   }

   void captureHandProjection(Matrix4fc projection) {
      if (this.sceneActive && this.renderer != null) {
         this.renderer.handProjection(projection);
      }
   }

   void beginScene(CameraRenderState currentCamera, Matrix4fc currentView, LevelRenderState state, float partialTick) {
      if (!this.frameScope) {
         throw new IllegalStateException("Caldera beginScene() outside a frame scope: scope(true) must open the frame first");
      }

      if (this.sceneBegun) {
         throw new IllegalStateException("Caldera scene begun twice without an intervening finishScene()");
      }

      if (!this.geometryRebuildPending && GraphFrame.cameraReady(currentCamera, currentView)) {
         this.camera = currentCamera;
         this.view = currentView == null ? null : new Matrix4f(currentView);
         // beginScene 自己开 scene。原先必须由调用方先用 scope(true) 开好，而那条依赖只存在于
         // mixin 的注入顺序里，读代码看不出来。
         this.sceneActive = true;
         this.sceneBegun = true;
         if (this.renderer != null) {
            try {
               this.renderer.projection(this.worldProjection);
               this.renderer.environment(state, this.host.level(), partialTick);
               this.renderer.beginScene(this.host.mainRenderTarget(), this.camera, this.view, this.host.level());
            } catch (Exception problem) {
               this.pause(problem);
            }
         }
      } else {
         this.sceneActive = false;
         this.sceneBegun = false;
         this.camera = null;
         this.view = null;
         if (this.renderer != null) {
            this.renderer.invalidateHistory();
         }
      }
   }

   /**
    * 关掉本帧的 scene。
    * <p>
    * <b>为什么"开过 frame scope 但没开过 scene"必须是空操作：</b>资源重载期间
    * {@code LevelRendererPostMixin} 会跳过 {@code beginScene}，而 {@code scope(true)} 与
    * {@code finishScene()} 照常发。那条路径每次重载都会走，它不是 bug。
    * 真正的 bug 是"没有任何 frame scope 就 finish"——那才是重复关闭。
    */
   void finishScene() {
      if (!this.frameScope) {
         throw new IllegalStateException("Caldera finishScene() without an open frame scope: scope(true) was never called for this frame");
      }

      boolean ready = this.sceneActive && this.camera != null;
      this.frameScope = false;
      this.sceneActive = false;
      this.sceneBegun = false;
      if (this.sceneFailure != null) {
         Exception problem = this.sceneFailure;
         this.sceneFailure = null;
         this.pause(problem);
      } else if (ready && !this.resourceReloading.getAsBoolean()) {
         this.render(this.config.get(), this.camera, this.view);
      }

      this.camera = null;
      this.view = null;
   }

   /** 记录一次 scene 期间的失败；由 {@link #finishScene()} 消费一次。 */
   void fail(Exception problem) {
      this.sceneFailure = problem;
   }

   /**
    * 按这份设置把本帧交出去。
    * <p>
    * 三项都要成立：后端可用、有渲染器、而且当前选中的包**就是**这个渲染器所对应的那个。
    * 最后一项是"用户刚换了包、新渲染器还没激活"那一帧的守卫。
    */
   boolean render(ShaderConfig config, CameraRenderState camera, Matrix4fc view) {
      if (this.backendReady(config) && this.renderer != null && config.selectedPackId().equals(this.rendererPackId)) {
         try {
            this.renderer.render(this.host.mainRenderTarget(), camera, view, this.host.level());
         } catch (Exception problem) {
            this.pause(problem);
         }

         return true;
      } else {
         return false;
      }
   }

   /** 标脏地形，让它在下一帧重建。 */
   void requestGeometryRebuild() {
      this.geometryRebuildPending = true;
   }

   void flushGeometryRebuild() {
      if (this.geometryRebuildPending && !this.resourceReloading.getAsBoolean()) {
         this.geometryRebuildPending = false;
         // 原件用 `client.level != null` 同时守住提交与重建两步，所以这里也守两步：
         // 把 level() 的判断上提到调用方，端口那边才不必为一个不会发生的 null 写防卫代码。
         if (this.host.level() != null) {
            this.host.submitCommands();
            this.host.invalidateCompiledGeometry();
         }
      }
   }

   // ---------------------------------------------------------------- 被门闸护着的操作

   /**
    * 是否真的在渲染原生几何；渲染器的查询都先过这一问。
    * <p>
    * 它<b>不</b>看有没有渲染器，也不看重载——这与原件一致，别顺手"补齐"：{@code ShadowService} 拿它
    * 与 {@code shadowQuality() > 0} 相与，而质量在没有渲染器时就是 0。
    */
   boolean shadowFrameReady() {
      return this.sceneActive && this.camera != null && !this.geometryRebuildPending && this.sceneFailure == null;
   }

   HeldLightShadowRenderer heldShadows() {
      return this.shadowFrameReady() && this.renderer != null ? this.renderer.heldShadows() : null;
   }

   boolean usesNativeTransparency() {
      return this.renderer != null && !this.resourceReloading.getAsBoolean();
   }

   /**
    * 见 {@code NativePackRuntime.wrapScenePass}。
    * <p>
    * 颜色纹理是**惰性**递进去的：每帧都有很多 {@code createRenderPass}，光影没开时前三项就否了，
    * 不该在这一步去问游戏——原件就是这个顺序。
    */
   RenderPass wrapScenePass(RenderPass pass, RenderPassDescriptor descriptor) {
      return SceneRenderPass.wrap(pass, descriptor, this.usesNativeTransparency(), () -> this.host.mainRenderTarget().getColorTexture());
   }

   GpuTextureView weatherView() {
      return this.canDraw() ? this.renderer.weatherView() : null;
   }

   /**
    * 捕获主场景给原生 pass 用。
    * <p>
    * 必须包在 {@link SceneRenderPass#outside} 里：这段动作跑在关卡渲染**之中**，那时 scene pass
    * 正开着，不挂起就会把原版的绘制录进我们的 pass。
    */
   void captureTerrain() {
      if (this.canCaptureScene()) {
         try {
            SceneRenderPass.outside(() -> this.renderer.captureTerrain(this.host.mainRenderTarget()));
         } catch (Exception problem) {
            this.sceneFailure = problem;
         }
      }
   }

   /** 同上，捕获的是半透明那一遍之后的深度。 */
   void captureTranslucentDepth() {
      if (this.canCaptureScene()) {
         RenderTarget target = this.host.mainRenderTarget();
         try {
            SceneRenderPass.outside(() -> this.renderer.captureWorldDepth(target));
         } catch (Exception problem) {
            this.sceneFailure = problem;
         }
      }
   }

   /**
    * 保留世界深度，供手部渲染之后的 3D HUD 用。
    * <p>
    * 这一条**故意不包** {@link SceneRenderPass#outside}，也**故意不排除阴影关卡**：它的注入点是
    * {@code GameRenderer.render3dHud} 里 {@code clearDepthTexture} 之前，也就是关卡早就画完、
    * scene pass 已经关掉、阴影关卡早就结束的手部渲染阶段——那个时刻本来就没有活跃 pass，
    * 而"正在画阴影贴图"也不成立，包一层或加一项都是空转。
    * 上面两条看起来做着同一件事却必须包、必须排除，是因为它们跑在关卡渲染**之中**。
    */
   void captureWorldDepth() {
      if (this.canDraw()) {
         try {
            this.renderer.captureWorldDepth(this.host.mainRenderTarget());
         } catch (Exception problem) {
            this.sceneFailure = problem;
         }
      }
   }

   RenderPassDescriptor sceneAttachments(RenderPassDescriptor descriptor) {
      return this.canAttachScene() ? this.renderer.sceneAttachments(descriptor, this.host.mainRenderTarget()) : descriptor;
   }

   RenderPipeline scenePipeline(RenderPipeline base, List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments) {
      if (!this.canAttachScene()) {
         return base;
      }

      boolean targets = this.renderer.hasSceneAttachments(attachments);
      if (this.sceneFailure != null) {
         return this.renderer.sceneFallback(base, targets);
      }

      try {
         RenderPipeline replacement = this.renderer.scenePipeline(base, targets);
         // 阴影生产者必须在本帧的地形之前跑过：级联没画就直接采，画面是错的，而错法不会抛异常。
         // 这条查询仍然直接伸手 DirectionalShadowRenderer 的单例——它要的是"资源就绪没有"，
         // 属于候选 2（阴影关卡作用域）的范围，不在本次改动内。
         if (ScenePrograms.isReplacement(replacement) && this.renderer.shadowQuality() > 0 && !DirectionalShadowRenderer.get().resourcesReady()) {
            throw new IllegalStateException("Shadow producer did not run before native terrain");
         }

         return replacement;
      } catch (Exception problem) {
         this.sceneFailure = problem;
         return this.renderer.sceneFallback(base, targets);
      }
   }

   /**
    * 绑定本帧的帧 uniform 与包自己声明的贴图。
    * <p>
    * 门闸只有"scene 开着 + 有渲染器"这一条，**故意不含资源重载**：重载中 {@link #scenePipeline}
    * 会把管线退回原版，而 {@code NativeRenderPassMixin} 仍然会带着那条管线调到这里——原件就是这个
    * 行为，收紧它会改变重载那一帧的画面。里面的分支自己认得"这条管线不是我替换的"。
    */
   void bindSceneUniforms(RenderPass pass, RenderPipeline pipeline) {
      if (this.sceneActive && this.renderer != null) {
         this.renderer.bindSceneUniforms(pass, pipeline);
      }
   }

   /**
    * 这个包是否会替换原版的天空或云。
    * <p>
    * 语义是：只有在主世界、且 scene 就绪、且这个包的 {@code environment} 声明了对应那一项时才是
    * {@code true}；任何一项不成立就落到 {@code false}。
    */
   boolean replacesEnvironment(boolean clouds) {
      if (this.renderer != null && this.shadowFrameReady() && !this.resourceReloading.getAsBoolean() && this.host.inOverworld()) {
         if (clouds) {
            return this.renderer.environment().clouds();
         }

         return this.renderer.environment().sky();
      }

      return false;
   }

   // ---------------------------------------------------------------- 渲染器查询

   /** 当前生效的渲染器；没有时 {@code null}。生命周期与包选项那一侧也要读它。 */
   GraphRenderer renderer() {
      return this.renderer;
   }

   boolean animatedShadowCasters() {
      return this.renderer != null && this.renderer.animatedShadowCasters();
   }

   MaterialTable materials() {
      return this.renderer == null ? null : this.renderer.materials();
   }

   long terrainCaptures() {
      return this.renderer == null ? 0L : this.renderer.terrainCaptures();
   }

   long renderedFrames() {
      return this.renderer == null ? 0L : this.renderer.renderedFrames();
   }

   long sceneReplacementCount() {
      return this.renderer == null ? 0L : this.renderer.sceneReplacementCount();
   }

   int shadowQuality() {
      return this.renderer == null ? 0 : this.renderer.shadowQuality();
   }

   int shadowDistance() {
      return this.renderer == null ? 128 : this.renderer.shadowDistance();
   }
}
