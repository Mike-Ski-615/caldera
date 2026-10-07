package com.caldera.shaders.graph;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.pack.ShaderPackScanner;
import com.caldera.shaders.render.shadow.HeldLightShadowRenderer;
import com.caldera.shaders.render.shadow.ShadowPassScope;
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
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4fc;

/**
 * 原生光影的**渲染器生命周期**：准备／激活／拆除，以及包选项的读写语义。
 * <p>
 * 迁移前这是一整片静态字段（9 个可变 static），同时管着四件事：pack 选项持久化、渲染器的
 * 准备／激活／拆除、scene frame 协议，以及给 mixin 读的 shadow／present 查询。第一轮把状态搬进了
 * 实例（本类的私有字段），第二轮把 **scene frame** 整块搬了出去——阶段、门闸与它们护着的动作现在都在
 * {@link SceneFrame} 里。所以剩下的是三件：渲染器生命周期、包选项语义、以及给 mixin 的静态门面。
 * <p>
 * 唯一的静态入口是 {@link #install()}；其余静态方法都只是门面，供 mixin 与
 * {@link com.caldera.shaders.runtime.MinecraftShaderHost} 调用，并且在实例尚未安装时
 * 给出**安全答案**（不渲染、不拆除、空查询、{@code false}、{@code 0}），
 * 与迁移前那些静态字段的初值语义一致。门面里那些属于场景帧的方法只是一行转发，不再自己判断。
 * <p>
 * <b>门面里那 37 个方法名是 mixin 能摸到的全部接口，不得改名。</b>
 * mixin 由游戏实例化，只能访问静态成员；改名不会有任何编译错误，只会在运行时静默失效。
 * 私有字段与私有方法的名字不在此列，可以改。
 * <p>
 * {@link SceneFrame} 是由 {@link #install(ShaderHost)} 一起装上的：它需要同一份 host，以及
 * "资源重载中／正在画阴影贴图／本帧生效的设置"这三个来源。三个都作为协作者注入，而不是在这个类里
 * 静态伸手——见那个类的说明。
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

   /** 本帧的场景帧：阶段、门闸、以及本帧在用的那个渲染器。 */
   private final SceneFrame frame;

   private NativePackRuntime(ShaderHost host) {
      this.host = host;
      this.frame = new SceneFrame(host, ShaderRuntime::config, ShaderRuntime::resourceReloading, ShadowPassScope::active);
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

   // ---------------------------------------------------------------- 渲染器生命周期门面

   /** 关闭当前生效的渲染器。 */
   public static void close() {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.frame.detach(SceneFrame.Disposal.SYNCHRONOUS);
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
         runtime.frame.requestGeometryRebuild();
      }
   }

   /** 渲染器上一次失败的描述，从未失败时为 {@code null}。 */
   public static String failure() {
      NativePackRuntime runtime = instance;
      return runtime == null ? null : runtime.frame.failure();
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
         runtime.frame.captureHandProjection(projection);
      }
   }

   public static void captureWorldProjection(Matrix4fc projection) {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.frame.captureWorldProjection(projection);
      }
   }

   public static void scope(boolean enabled) {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.frame.scope(enabled);
      }
   }

   public static void beginScene(CameraRenderState currentCamera, Matrix4fc currentView, LevelRenderState state, float partialTick) {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.frame.beginScene(currentCamera, currentView, state, partialTick);
      }
   }

   public static void finishScene() {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.frame.finishScene();
      }
   }

   public static RenderPassDescriptor sceneAttachments(RenderPassDescriptor descriptor) {
      NativePackRuntime runtime = instance;
      return runtime == null ? descriptor : runtime.frame.sceneAttachments(descriptor);
   }

   /**
    * 决定要不要接管一个刚建出来的 render pass，要的话包一层。
    * <p>
    * 主目标的颜色纹理由 {@link SceneFrame} 从 host 惰性取，所以这里只剩转发。
    */
   public static RenderPass wrapScenePass(RenderPass pass, RenderPassDescriptor descriptor) {
      NativePackRuntime runtime = instance;
      return runtime == null ? pass : runtime.frame.wrapScenePass(pass, descriptor);
   }

   public static RenderPipeline scenePipeline(RenderPipeline base, List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments) {
      NativePackRuntime runtime = instance;
      return runtime == null ? base : runtime.frame.scenePipeline(base, attachments);
   }

   public static void bindSceneUniforms(RenderPass pass, RenderPipeline pipeline) {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.frame.bindSceneUniforms(pass, pipeline);
      }
   }

   public static GpuTextureView weatherView() {
      NativePackRuntime runtime = instance;
      return runtime == null ? null : runtime.frame.weatherView();
   }

   public static void captureTerrain() {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.frame.captureTerrain();
      }
   }

   public static void captureTranslucentDepth() {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.frame.captureTranslucentDepth();
      }
   }

   public static void captureWorldDepth() {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.frame.captureWorldDepth();
      }
   }

   public static void failScene(Exception problem) {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.frame.fail(problem);
      }
   }

   public static void flushGeometryRebuild() {
      NativePackRuntime runtime = instance;
      if (runtime != null) {
         runtime.frame.flushGeometryRebuild();
      }
   }

   // ---------------------------------------------------------------- 查询门面

   /** 是否真的在渲染原生几何；渲染器的查询都先过这一问。 */
   public static boolean shadowFrameReady() {
      NativePackRuntime runtime = instance;
      return runtime != null && runtime.frame.shadowFrameReady();
   }

   public static HeldLightShadowRenderer heldShadows() {
      NativePackRuntime runtime = instance;
      return runtime == null ? null : runtime.frame.heldShadows();
   }

   public static boolean usesNativeTransparency() {
      NativePackRuntime runtime = instance;
      return runtime != null && runtime.frame.usesNativeTransparency();
   }

   public static boolean render(ShaderConfig config, CameraRenderState camera, Matrix4fc view) {
      NativePackRuntime runtime = instance;
      return runtime != null && runtime.frame.render(config, camera, view);
   }

   public static boolean replacesEnvironment(boolean clouds) {
      NativePackRuntime runtime = instance;
      return runtime != null && runtime.frame.replacesEnvironment(clouds);
   }

   public static int shadowQuality() {
      NativePackRuntime runtime = instance;
      return runtime == null ? 0 : runtime.frame.shadowQuality();
   }

   public static int shadowDistance() {
      NativePackRuntime runtime = instance;
      return runtime == null ? 128 : runtime.frame.shadowDistance();
   }

   public static boolean animatedShadowCasters() {
      NativePackRuntime runtime = instance;
      return runtime != null && runtime.frame.animatedShadowCasters();
   }

   public static MaterialTable materials() {
      NativePackRuntime runtime = instance;
      return runtime == null ? null : runtime.frame.materials();
   }

   public static long terrainCaptures() {
      NativePackRuntime runtime = instance;
      return runtime == null ? 0L : runtime.frame.terrainCaptures();
   }

   public static long renderedFrames() {
      NativePackRuntime runtime = instance;
      return runtime == null ? 0L : runtime.frame.renderedFrames();
   }

   public static long sceneReplacementCount() {
      NativePackRuntime runtime = instance;
      return runtime == null ? 0L : runtime.frame.sceneReplacementCount();
   }

   // ---------------------------------------------------------------- 实例：渲染器生命周期

   /**
    * 该不该在这次设置下建渲染器，以及该建哪一个。
    * <p>
    * 与原件逐行对应：开关或后端不满足时返回 {@code null}；满足但包不是原生包时**抛**，
    * 因为那是用户可见的错误，不是"什么都不做"。
    */
   private GraphRenderer prepareRenderer(ShaderConfig config) throws IOException {
      if (!this.frame.backendReady(config)) {
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
      GraphRenderer candidate = this.frame.backendReady(config) && id.equals(config.selectedPackId())
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
    * 换上一个渲染器。
    * <p>
    * 拆除（摘下来、清 {@code failure}/{@code sceneFailure}、把旧的排队关掉）都在
    * {@link SceneFrame#attach}／{@link SceneFrame#detach} 里；这里只剩政策：换完之后要不要重建几何。
    * 那一问看的是**新旧两侧**的材质表——旧的那份失效了、或者新的那份需要材质编码，都要重建。
    */
   private void activateRenderer(GraphRenderer next, String id) {
      GraphRenderer old = this.frame.detach(SceneFrame.Disposal.QUEUED);
      this.frame.attach(next, id);
      if (old != null && old.materials().enabled() || next != null && next.materials().enabled()) {
         this.frame.requestGeometryRebuild();
      }
   }

   // ---------------------------------------------------------------- pack 文件

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
