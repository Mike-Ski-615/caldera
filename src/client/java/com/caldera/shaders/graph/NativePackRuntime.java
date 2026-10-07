package com.caldera.shaders.graph;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.pack.ShaderPackScanner;
import com.caldera.shaders.render.shadow.DirectionalShadowRenderer;
import com.caldera.shaders.render.shadow.HeldLightShadowRenderer;
import com.caldera.shaders.runtime.ShaderHost;
import com.caldera.shaders.runtime.ShaderRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4fc;

/**
 * 原生光影的**帧生命周期**与**渲染器生命周期**。
 * <p>
 * 迁移前这是一整片静态字段（9 个可变 static），同时管着四件事：pack 选项持久化、渲染器的
 * 准备／激活／拆除、scene frame 协议，以及给 mixin 读的 shadow／present 查询。后果与
 * ADR-0003 里记的那一份完全相同：整个 scene 时序无法被单元测试触碰，而 6 个 mixin 直接读它的
 * scene 状态——{@code sceneActive}／{@code sceneFailure}／{@code geometryRebuildPending} 的组合错了
 * 不会报错，只会安静地画错一帧。
 * <p>
 * 现在状态与流程都在**实例**里。唯一的静态入口是 {@link #install()}；其余静态方法都只是门面，
 * 供 mixin 与 {@link com.caldera.shaders.runtime.MinecraftShaderHost} 调用，并且在实例尚未安装时
 * 给出**安全答案**（不渲染、不拆除、空查询、{@code false}、{@code 0}），
 * 与迁移前那些静态字段的初值语义一致。
 * <p>
 * <b>门面里那 18 个方法名是 mixin 能摸到的全部接口，不得改名。</b>
 * mixin 由游戏实例化，只能访问静态成员；改名不会有任何编译错误，只会在运行时静默失效。
 * 私有字段与私有方法的名字不在此列，可以改。
 */
public final class NativePackRuntime {
   /**
    * 唯一实例。未安装时为 {@code null}，此时所有门面给出安全答案。
    * <p>
    * 未安装是一个**真实且预期的状态**：它在客户端初始化之前、以及每个测试 JVM 里都存在，
    * 所以门面必须能在它之下正确回答，而不是靠"反正装过了"。
    */
   private static NativePackRuntime instance;

   /**
    * 游戏能力的唯一来源。
    * <p>
    * 迁移前这个类有十几处直接伸手 {@code Minecraft.getInstance()} / {@code RenderSystem}，
    * 于是 scene 时序里每一个"这一帧到底有没有世界、有没有渲染目标"的判断都无法在测试里摆布。
    * 现在这些全部走端口，跨过它的两端都不需要 {@code Minecraft}，也不需要 GPU。
    */
   private final ShaderHost host;

   /** 当前生效的渲染器，没有时为 {@code null}。 */
   private GraphRenderer active;
   /** {@link #active} 对应的包 id；没有渲染器时为 {@code null}。 */
   private String activeId;
   /** 渲染器上一次失败的描述，从未失败时为 {@code null}。 */
   private String failure;
   /**
    * 这一帧是否处在世界渲染里，也就是 {@code renderLevel} 已经进去、还没出来。
    * <p>
    * 由 {@link #scope} 开关。它与 {@link #sceneBegun} 是**两件事**：前者是"我们在世界渲染里"，
    * 后者是"相机已经就绪、可以画原生几何"。混成一个 boolean 会让下面那些
    * "本帧到底能不能画"的判断读不出来。
    */
   private boolean frameScope;
   /**
    * 本帧的 scene 是否已经开过（也就是 {@code beginScene} 走过 ready 分支）。
    * <p>
    * 只用于顺序强制：同一个 frame scope 里开第二次就是 bug。它**不**决定能不能画——
    * 能画是 {@link #sceneActive} 说的。
    */
   private boolean sceneBegun;
   /**
    * 这一帧是否真的可以画原生几何，等于"frame scope 开着，且最近一次 {@code beginScene} 没有说相机不可用"。
    * <p>
    * 名字原先叫 {@code sceneScope}，但它是个 boolean——那个名字会让读者以为它是个作用域对象。
    */
   private boolean sceneActive;
   /** scene 期间累积的失败；由 {@code finishScene()} 消费一次。 */
   private Exception sceneFailure;
   /** 本帧的相机与视图矩阵；不在 scene 里时为 {@code null}。 */
   private CameraRenderState camera;
   private Matrix4fc view;
   /** 世界投影矩阵，由 mixin 在投影建立时推入。 */
   private Matrix4fc worldProjection;
   /** 地形几何是否待重建。 */
   private boolean geometryRebuildPending;

   private NativePackRuntime(ShaderHost host) {
      this.host = host;
   }

   /**
    * 安装唯一实例。必须在客户端初始化之前调用。
    * <p>
    * 由客户端入口点调用，而**不是**由 {@link com.caldera.shaders.runtime.MinecraftShaderHost} 自己调用：
    * 那个 host 已经在调 {@code prepare}／{@code activate}／{@code close}／
    * {@code requestGeometryRebuild}／{@code failure}，若再由它安装自己的依赖，
    * 就成了 host → 静态门面 → instance → host 的构造期循环。
    */
   public static void install(ShaderHost host) {
      instance = new NativePackRuntime(host);
   }

   /**
    * 卸载当前实例，回到"尚未安装"的初始状态。
    * <p>
    * 包级可见，供测试使用：ADR-0003 用同一手法让"实例未安装时门面给出安全答案"这条契约可验证，
    * 即每个门面里的 null 分支。
    */
   static void uninstall() {
      instance = null;
   }

   // ---------------------------------------------------------------- 生命周期门面

   /** 关闭当前生效的渲染器。 */
   public static void close() {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.closeActive();
      }
   }

   /**
    * 为一份设置准备好渲染器。
    * <p>
    * {@link com.caldera.shaders.runtime.ShaderHost#prepareRenderer} 的生产实现直接调它。
    * 未安装时抛 {@link IllegalStateException}，与 {@code ShaderRuntime} 的 {@code notInstalled()} 一致：
    * 这条路径只在安装之后才会被走到，走到这里就说明初始化顺序错了。
    */
   public static GraphRenderer prepare(ShaderConfig config) throws IOException {
      NativePackRuntime runtime = instance;
      if (runtime == null) {
         throw new IllegalStateException("Caldera native pack runtime is not installed");
      }

      return runtime.prepareRenderer(config);
   }

   /**
    * 读一个包在磁盘上的设置。
    * <p>
    * 走的是实例门面，但它不碰实例状态——保留在门面上只是为了与其它入口一致。
    */
   public static PackGraph settings(String id) throws IOException {
      NativePackRuntime runtime = instance;
      if (runtime == null) {
         throw new IllegalStateException("Caldera native pack runtime is not installed");
      }

      return runtime.loadSettings(id);
   }

   /** 写入一个包的选项，并在它正是当前包时让它立刻生效。 */
   public static void applyOptions(String id, Map<String, Double> values) throws IOException {
      NativePackRuntime runtime = instance;
      if (runtime == null) {
         throw new IllegalStateException("Caldera native pack runtime is not installed");
      }

      runtime.applyPackOptions(id, values);
   }

   /**
    * 换掉当前生效的渲染器。
    * <p>
    * {@code next} 允许为 {@code null}，而那**不是空操作**：它会清空当前生效的渲染器并把旧的排队关闭。
    * 那正是"关掉光影"时拆除 GPU 资源的唯一路径，所以调用方不能因为拿不到渲染器就跳过这一步。
    */
   public static void activate(GraphRenderer next, String id) {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.activateRenderer(next, id);
      }
   }

   /** 标脏地形，让它在下一帧重建。 */
   public static void requestGeometryRebuild() {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.geometryRebuildPending = true;
      }
   }

   /** 渲染器上一次失败的描述，从未失败时为 {@code null}。 */
   public static String failure() {
      NativePackRuntime runtime = instance;
      return runtime == null ? null : runtime.failure;
   }

   /**
    * 该不该在这次设置下建渲染器：开关 + 后端。**不含**"这个包是不是原生包"。
    * <p>
    * 走 {@code host.vulkanActive()} 而不是 {@code BackendStatus} 那个静态：生产实现里两者是同一行，
    * 但走端口之后这一问才在测试里摆得动——否则测一个"非 Vulkan 时不建渲染器"都要真的 GPU。
    * <p>
    * 原先这三处（{@code prepare}／{@code applyOptions}／{@code render}）各自内联了同一个两元判断。
    */
   private boolean backendReady(ShaderConfig config) {
      return config.enabled() && this.host.vulkanActive();
   }

   /** 这个包 id 指向的目录/压缩包确实是一个原生包。纯文件系统判断，与实例状态无关。 */
   public static boolean selected(ShaderConfig config) {
      return "__builtin__".equals(config.selectedPackId())
            || config.selectedPackId() != null
                  && PackFiles.safe(config.selectedPackId())
                  && !config.selectedPackId().contains("/")
                  && !"__builtin__".equals(config.selectedPackId())
                  && isNative(ShaderPackScanner.shaderPackDirectory().resolve(config.selectedPackId()));
   }

   /** 这个路径是否是一个原生光影包（目录或压缩包里恰好有一份 caldera.json）。 */
   public static boolean isNative(Path path) {
      try {
         if (Files.isDirectory(path)) {
            Stream<Path> files = Files.walk(path, 2);

            boolean var10;
            try {
               var10 = files.anyMatch((p) -> p.getFileName().toString().equals("caldera.json") && Files.isRegularFile(p));
            } catch (Throwable var7) {
               try {
                  files.close();
               } catch (Throwable var5) {
                  var7.addSuppressed(var5);
               }

               throw var7;
            }

            files.close();
            return var10;
         } else {
            ZipFile zip = new ZipFile(path.toFile());

            boolean var2;
            try {
               var2 = zip.stream().anyMatch((e) -> !e.isDirectory() && (e.getName().equals("caldera.json") || e.getName().matches("[^/]+/caldera\\.json")));
            } catch (Throwable var6) {
               try {
                  zip.close();
               } catch (Throwable var4) {
                  var6.addSuppressed(var4);
               }

               throw var6;
            }

            zip.close();
            return var2;
         }
      } catch (IOException var8) {
         return false;
      }
   }

   // ---------------------------------------------------------------- scene frame 门面

   public static void captureHandProjection(Matrix4fc projection) {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.captureHandProjectionNow(projection);
      }
   }

   public static void captureWorldProjection(Matrix4fc projection) {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.worldProjection = new Matrix4f(projection);
      }
   }

   public static void scope(boolean enabled) {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.setFrameScopeNow(enabled);
      }
   }

   public static void beginScene(CameraRenderState currentCamera, Matrix4fc currentView, LevelRenderState state, float partialTick) {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.beginSceneNow(currentCamera, currentView, state, partialTick);
      }
   }

   public static void finishScene() {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.finishSceneNow();
      }
   }

   public static RenderPassDescriptor sceneAttachments(RenderPassDescriptor descriptor) {
      NativePackRuntime runtime = instance;
      return runtime == null ? descriptor : runtime.sceneAttachmentsNow(descriptor);
   }

   /**
    * 决定要不要接管一个刚建出来的 render pass，要的话包一层。
    * <p>
    * 这一条从 {@code SceneRenderPass} 挪出来，是为了把那里最后一处伸手拿掉：主目标的颜色纹理
    * 原先由它自己去读 {@code Minecraft.getInstance().gameRenderer.mainRenderTarget()}，而
    * {@link ShaderHost#mainRenderTarget()} 就是同一句。归到这里之后，那个模块只剩策略，
    * 游戏能力的取用全在本类**已经**持有的 host 上。
    */
   public static RenderPass wrapScenePass(RenderPass pass, RenderPassDescriptor descriptor) {
      NativePackRuntime runtime = instance;
      return runtime == null ? pass : runtime.wrapScenePassNow(pass, descriptor);
   }

   public static RenderPipeline scenePipeline(RenderPipeline base, List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments) {
      NativePackRuntime runtime = instance;
      return runtime == null ? base : runtime.scenePipelineNow(base, attachments);
   }

   public static void bindSceneUniforms(RenderPass pass, RenderPipeline pipeline) {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.bindSceneUniformsNow(pass, pipeline);
      }
   }

   public static GpuTextureView weatherView() {
      NativePackRuntime runtime = instance;
      return runtime == null ? null : runtime.weatherViewNow();
   }

   public static void captureTerrain() {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.captureTerrainNow();
      }
   }

   public static void captureTranslucentDepth() {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.captureTranslucentDepthNow();
      }
   }

   public static void captureWorldDepth() {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.captureWorldDepthNow();
      }
   }

   public static void failScene(Exception problem) {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.sceneFailure = problem;
      }
   }

   public static void flushGeometryRebuild() {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.flushGeometryRebuildNow();
      }
   }

   // ---------------------------------------------------------------- 查询门面

   /** 是否真的在渲染原生几何；渲染器的查询都先过这一问。 */
   public static boolean shadowFrameReady() {
      NativePackRuntime runtime = instance;
      return runtime != null && runtime.shadowFrameReadyNow();
   }

   public static HeldLightShadowRenderer heldShadows() {
      NativePackRuntime runtime = instance;
      return runtime == null ? null : runtime.heldShadowsNow();
   }

   public static boolean usesNativeTransparency() {
      NativePackRuntime runtime = instance;
      return runtime != null && runtime.usesNativeTransparencyNow();
   }

   public static boolean render(ShaderConfig config, CameraRenderState camera, Matrix4fc view) {
      NativePackRuntime runtime = instance;
      return runtime != null && runtime.renderNow(config, camera, view);
   }

   public static boolean replacesEnvironment(boolean clouds) {
      NativePackRuntime runtime = instance;
      return runtime != null && runtime.replacesEnvironmentNow(clouds);
   }

   public static int shadowQuality() {
      NativePackRuntime runtime = instance;
      return runtime == null ? 0 : runtime.shadowQualityNow();
   }

   public static int shadowDistance() {
      NativePackRuntime runtime = instance;
      return runtime == null ? 128 : runtime.shadowDistanceNow();
   }

   public static boolean animatedShadowCasters() {
      NativePackRuntime runtime = instance;
      return runtime != null && runtime.active != null && runtime.active.animatedShadowCasters();
   }

   public static MaterialTable materials() {
      NativePackRuntime runtime = instance;
      return runtime == null || runtime.active == null ? null : runtime.active.materials();
   }

   public static long terrainCaptures() {
      NativePackRuntime runtime = instance;
      return runtime == null || runtime.active == null ? 0L : runtime.active.terrainCaptures();
   }

   public static long renderedFrames() {
      NativePackRuntime runtime = instance;
      return runtime == null || runtime.active == null ? 0L : runtime.active.renderedFrames();
   }

   public static long sceneReplacementCount() {
      NativePackRuntime runtime = instance;
      return runtime == null || runtime.active == null ? 0L : runtime.active.sceneReplacementCount();
   }

   // ---------------------------------------------------------------- 实例：渲染器生命周期

   /**
    * 该不该在这次设置下建渲染器，以及该建哪一个。
    * <p>
    * 与原件逐行对应：开关或后端不满足时返回 {@code null}；满足但包不是原生包时**抛**，
    * 因为那是用户可见的错误，不是"什么都不做"。
    */
   private GraphRenderer prepareRenderer(ShaderConfig config) throws IOException {
      if (!backendReady(config)) {
         return null;
      }

      if (!selected(config)) {
         throw new IOException("This pack is not a native Caldera shader pack. Select Caldera Realistic or a pack with caldera.json.");
      }

      PackFiles files = packFiles(config.selectedPackId());
      PackGraph graph = loadOptions(PackGraph.parse(files.text("caldera.json")), config.selectedPackId());
      return new GraphRenderer(graph, files, this.host.mainRenderTarget());
   }

   private PackGraph loadSettings(String id) throws IOException {
      PackFiles files = packFiles(id);
      return loadOptions(PackGraph.parse(files.text("caldera.json")), id);
   }

   private void applyPackOptions(String id, Map<String, Double> values) throws IOException {
      PackFiles files = packFiles(id);
      PackGraph graph = PackGraph.parse(files.text("caldera.json")).withOptions(values);
      ShaderConfig config = ShaderRuntime.config();
      // 原件用的是同一个「开关 + 后端」判断，但不含"这个包是不是原生包"——保持一致，不合并。
      GraphRenderer candidate = backendReady(config) && id.equals(config.selectedPackId())
            ? new GraphRenderer(graph, files, this.host.mainRenderTarget())
            : null;
      // 落盘走端口：路径、JSON、原子写都在 CalderaConfigFiles 里，这里只交值。
      // 顺序与原件一致：先写文件，写失败就丢掉候选渲染器并抛出；写成功才激活。
      try {
         this.host.savePackOptions(id, graph.options());
      } catch (IOException failure) {
         if (candidate != null) {
            candidate.close();
         }

         throw failure;
      }

      if (candidate != null) {
         this.activateRenderer(candidate, id);
      }
   }

   /**
    * 把当前渲染器摘下来。
    * <p>
    * 三条拆除路径——{@link #activateRenderer(GraphRenderer, String)}、{@link #pause(Exception)}、
    * {@link #closeActive()}——真正重复的就是这一块：取走 {@code active}、清掉 {@code activeId}、
    * 处置旧的。原先它写了两遍半（pause 与 activate 各一份，closeActive 半个），加一个拆除场景就要
    * 记得把这几行再抄一次。
    * <p>
    * <b>两条故意的差异不在这个方法里，而在调用点上，并且是显式参数：</b>
    * <ul>
    *    <li>{@code failure} 字段：activate <b>清</b>它、pause <b>设</b>它、close <b>不碰</b>它。
    *        那条不对称是原件的行为（界面上显示的就是它），所以留在各自的方法体里。</li>
    *    <li>处置方式：activate 与 pause 排到栅栏之后（{@code QUEUED}），closeActive 是同步关。
    *        这个分歧是原件就有的，这里把它变成一个看得见的参数，而不是改掉它。</li>
    * </ul>
    *
    * @return 被摘下来的那个渲染器，没有就是 {@code null}（调用方还要用它算"要不要重建几何"）
    */
   private GraphRenderer detach(Disposal disposal) {
      GraphRenderer old = this.active;
      this.active = null;
      this.activeId = null;

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
   private enum Disposal {
      /** 排到栅栏之后：当前帧可能还在用它的资源。 */
      QUEUED,
      /** 当场关掉。 */
      SYNCHRONOUS
   }

   private void activateRenderer(GraphRenderer next, String id) {
      this.sceneFailure = null;
      this.failure = null;
      GraphRenderer old = this.detach(Disposal.QUEUED);
      this.active = next;
      this.activeId = next == null ? null : id;
      if (old != null && old.materials().enabled() || next != null && next.materials().enabled()) {
         this.geometryRebuildPending = true;
      }

   }

   private void closeActive() {
      this.detach(Disposal.SYNCHRONOUS);
   }

   /**
    * 暂停：记下失败、丢掉渲染器、排队关闭、请求重建几何。
    * <p>
    * 与 {@link #closeActive()} 的差别是**故意的**：这里设置 {@code failure} 供界面显示，
    * 而 {@code closeActive()} 不清它。这条不对称是原件的行为，不是笔误——改它会改变用户看到的东西。
    */
   private void pause(Exception problem) {
      this.failure = "Shader pack paused: " + problem.getMessage();
      LogUtils.getLogger().error(this.failure, problem);
      GraphRenderer old = this.detach(Disposal.QUEUED);
      if (old != null && old.materials().enabled()) {
         this.geometryRebuildPending = true;
      }

   }

   // ---------------------------------------------------------------- 实例：scene frame

   private boolean renderNow(ShaderConfig config, CameraRenderState camera, Matrix4fc view) {
      if (backendReady(config) && this.active != null && config.selectedPackId().equals(this.activeId)) {
         try {
            this.active.render(this.host.mainRenderTarget(), camera, view, this.host.level());
         } catch (Exception problem) {
            this.pause(problem);
         }

         return true;
      } else {
         return false;
      }
   }

   private boolean usesNativeTransparencyNow() {
      return this.active != null && !ShaderRuntime.resourceReloading();
   }

   /**
    * 见 {@link #wrapScenePass(RenderPass, RenderPassDescriptor)}。
    * <p>
    * 颜色纹理是**惰性**递进去的：每帧都有很多 {@code createRenderPass}，光影没开时前三项就否了，
    * 不该在这一步去问游戏——原件就是这个顺序。
    */
   private RenderPass wrapScenePassNow(RenderPass pass, RenderPassDescriptor descriptor) {
      return SceneRenderPass.wrap(pass, descriptor, this.usesNativeTransparencyNow(), () -> this.host.mainRenderTarget().getColorTexture());
   }

   private boolean shadowFrameReadyNow() {
      return this.sceneActive && this.camera != null && !this.geometryRebuildPending && this.sceneFailure == null;
   }

   private HeldLightShadowRenderer heldShadowsNow() {
      return this.shadowFrameReadyNow() && this.active != null ? this.active.heldShadows() : null;
   }

   /**
    * 开／关本帧的 frame scope，并强制它是**平衡**的。
    * <p>
    * 这一对由 {@code NativeSceneMixin} 在 {@code renderLevel} 的首尾各调一次；开发期门禁
    * （另一个模块，见 {@code src/smoke}）也拿它给一段自建的 pass 圈一个作用域。不配对的调用（重复开、或没开就关）是 bug，
    * 而且原先是一个静默的状态赋值——一帧画错不会有任何提示。
    */
   private void setFrameScopeNow(boolean enabled) {
      if (enabled && this.frameScope) {
         throw new IllegalStateException("Caldera frame scope opened twice: a previous scope(true) was never closed");
      }

      if (!enabled && !this.frameScope) {
         throw new IllegalStateException("Caldera frame scope closed twice: scope(false) without an open scope(true)");
      }

      this.frameScope = enabled;
      this.sceneActive = enabled;
   }

   private void captureHandProjectionNow(Matrix4fc projection) {
      if (this.sceneActive && this.active != null) {
         this.active.handProjection(projection);
      }
   }

   private void beginSceneNow(CameraRenderState currentCamera, Matrix4fc currentView, LevelRenderState state, float partialTick) {
      if (!this.frameScope) {
         throw new IllegalStateException("Caldera beginScene() outside a frame scope: scope(true) must open the frame first");
      }

      if (this.sceneBegun) {
         throw new IllegalStateException("Caldera scene begun twice without an intervening finishScene()");
      }

      if (!this.geometryRebuildPending && GraphFrame.cameraReady(currentCamera, currentView)) {
         this.camera = currentCamera;
         this.view = currentView == null ? null : new Matrix4f(currentView);
         // 第 3 步：beginScene 自己开 scene。原先必须由调用方先用 scope(true) 开好，
         // 而那条依赖只存在于 mixin 的注入顺序里，读代码看不出来。
         this.sceneActive = true;
         this.sceneBegun = true;
         if (this.active != null) {
            try {
               this.active.projection(this.worldProjection);
               this.active.environment(state, this.host.level(), partialTick);
               this.active.beginScene(this.host.mainRenderTarget(), this.camera, this.view, this.host.level());
            } catch (Exception problem) {
               this.pause(problem);
            }
         }
      } else {
         this.sceneActive = false;
         this.sceneBegun = false;
         this.camera = null;
         this.view = null;
         if (this.active != null) {
            this.active.invalidateHistory();
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
   private void finishSceneNow() {
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
      } else if (ready && !ShaderRuntime.resourceReloading()) {
         this.renderNow(ShaderRuntime.config(), this.camera, this.view);
      }

      this.camera = null;
      this.view = null;
   }

   private RenderPassDescriptor sceneAttachmentsNow(RenderPassDescriptor descriptor) {
      return !DirectionalShadowRenderer.isRenderingShadowMap() && this.sceneActive && this.active != null && !ShaderRuntime.resourceReloading()
            ? this.active.sceneAttachments(descriptor, this.host.mainRenderTarget())
            : descriptor;
   }

   private RenderPipeline scenePipelineNow(RenderPipeline base, List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments) {
      if (!DirectionalShadowRenderer.isRenderingShadowMap() && this.sceneActive && this.active != null && !ShaderRuntime.resourceReloading()) {
         boolean targets = this.active.hasSceneAttachments(attachments);
         if (this.sceneFailure != null) {
            return this.active.sceneFallback(base, targets);
         } else {
            try {
               RenderPipeline replacement = this.active.scenePipeline(base, targets);
               if (ScenePrograms.isReplacement(replacement) && this.active.shadowQuality() > 0 && !DirectionalShadowRenderer.get().resourcesReady()) {
                  throw new IllegalStateException("Shadow producer did not run before native terrain");
               } else {
                  return replacement;
               }
            } catch (Exception problem) {
               this.sceneFailure = problem;
               return this.active.sceneFallback(base, targets);
            }
         }
      } else {
         return base;
      }
   }

   private void bindSceneUniformsNow(RenderPass pass, RenderPipeline pipeline) {
      if (this.sceneActive && this.active != null) {
         this.active.bindSceneUniforms(pass, pipeline);
      }
   }

   private GpuTextureView weatherViewNow() {
      return this.sceneActive && this.camera != null && this.active != null && this.sceneFailure == null && !ShaderRuntime.resourceReloading()
            ? this.active.weatherView()
            : null;
   }

   private void captureTerrainNow() {
      if (this.sceneActive && this.camera != null && this.active != null && this.sceneFailure == null && !ShaderRuntime.resourceReloading()) {
         try {
            SceneRenderPass.outside(() -> this.active.captureTerrain(this.host.mainRenderTarget()));
         } catch (Exception problem) {
            this.sceneFailure = problem;
         }
      }
   }

   private void captureTranslucentDepthNow() {
      if (this.sceneActive && this.camera != null && this.active != null && this.sceneFailure == null && !ShaderRuntime.resourceReloading()) {
         RenderTarget target = this.host.mainRenderTarget();
         try {
            SceneRenderPass.outside(() -> this.active.captureWorldDepth(target));
         } catch (Exception problem) {
            this.sceneFailure = problem;
         }
      }
   }

   /**
    * 保留世界深度，供手部渲染之后的 3D HUD 用。
    * <p>
    * 这一条**故意不包** {@code SceneRenderPass.outside}：它的注入点是 {@code GameRenderer.render3dHud}
    * 里 {@code clearDepthTexture} 之前，也就是关卡早就画完、scene pass 已经关掉的手部渲染阶段——
    * 那个时刻本来就没有活跃 pass，包一层会是空转。上面 {@code captureTranslucentDepthNow} 看起来
    * 做着同一件事却必须包，是因为它跑在关卡渲染**之中**，那时 pass 正开着。
    */
   private void captureWorldDepthNow() {
      if (this.sceneActive && this.camera != null && this.active != null && this.sceneFailure == null && !ShaderRuntime.resourceReloading()) {
         try {
            this.active.captureWorldDepth(this.host.mainRenderTarget());
         } catch (Exception problem) {
            this.sceneFailure = problem;
         }
      }
   }

   private void flushGeometryRebuildNow() {
      if (this.geometryRebuildPending && !ShaderRuntime.resourceReloading()) {
         this.geometryRebuildPending = false;
         // 原件用 `client.level != null` 同时守住提交与重建两步，所以这里也守两步：
         // 把 level() 的判断上提到调用方，端口那边才不必为一个不会发生的 null 写防卫代码。
         if (this.host.level() != null) {
            this.host.submitCommands();
            this.host.invalidateCompiledGeometry();
         }
      }
   }

   /**
    * 这个包是否会替换原版的天空或云。
    * <p>
    * 原件用 {@code label33} 跳出嵌套，语义是：只有在主世界、且 scene 就绪、且这个包的
    * {@code environment} 声明了对应那一项时才是 {@code true}；任何一项不成立就落到 {@code false}。
    */
   private boolean replacesEnvironmentNow(boolean clouds) {
      if (this.active != null && this.shadowFrameReadyNow() && !ShaderRuntime.resourceReloading() && this.host.inOverworld()) {
         if (clouds) {
            if (this.active.environment().clouds()) {
               return true;
            }
         } else if (this.active.environment().sky()) {
            return true;
         }
      }

      return false;
   }

   private int shadowQualityNow() {
      return this.active == null ? 0 : this.active.shadowQuality();
   }

   private int shadowDistanceNow() {
      return this.active == null ? 128 : this.active.shadowDistance();
   }

   // ---------------------------------------------------------------- pack 文件（candidate 3 的范围）

   private static PackFiles packFiles(String id) throws IOException {
      return "__builtin__".equals(id) ? PackFiles.bundled() : PackFiles.read(ShaderPackScanner.shaderPackDirectory().resolve(id));
   }

   /**
    * 把存下来的选项并进包自己的图里。
    * <p>
    * 字节部分（路径、JSON、原子写）现在在 {@code CalderaConfigFiles} 里，走
    * {@link ShaderHost#loadPackOptions}；这里只剩**语义**：哪些值在这个包的选项定义里是合法的，
    * 以及旧格式的值怎么迁移。这两件事需要 {@code optionDefinitions}，所以留在这一层。
    */
   private PackGraph loadOptions(PackGraph graph, String id) throws IOException {
      Map<String, Double> stored = this.host.loadPackOptions(id);
      if (stored.isEmpty()) {
         return graph;
      } else {
         Map<String, Double> values = new TreeMap<>();

         for(Map.Entry<String, Double> entry : stored.entrySet()) {
            String key = entry.getKey();
            double value = entry.getValue();
            PackGraph.Option definition = graph.optionDefinitions().get(key);
            // 0.75 是 COLOR_GRADE 早期四档之前的档位，当时的第三档就是现在的 Vibrant。
            // 迁移必须在这里做：PackGraph.withOptions 对不在定义里的值直接抛，0.75 进不去。
            if (key.equals("COLOR_GRADE") && value == (double)0.75F && definition != null && definition.values().equals(List.of((double)0.0F, (double)0.25F, (double)0.5F, (double)1.0F))) {
               value = 1.0F;
            }

            if (definition != null && definition.values().contains(value)) {
               values.put(key, value);
            }
         }

         return graph.withOptions(values);
      }
   }
}
