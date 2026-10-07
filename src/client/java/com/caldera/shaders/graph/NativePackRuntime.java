package com.caldera.shaders.graph;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.render.shadow.HeldLightShadowRenderer;
import com.caldera.shaders.runtime.ShaderHost;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4fc;

/**
 * 光影包运行时的**门面**：mixin 能摸到的全部入口，以及几个不需要实例的纯判断。
 * <p>
 * 三轮下来这个类剥掉了三层：状态先搬进实例（候选 1、2 之前那一轮），场景帧再整块搬出去（候选 3），
 * 现在**实例本身**也搬走了（候选 4）——门面手上拿的是一个 {@link PackRuntime}，而"什么都没装"从
 * {@code instance == null} 变成了一个实现了同一张表的适配器 {@link NotInstalledPackRuntime}。
 * <p>
 * 于是这个类只剩两种东西：
 * <ul>
 *    <li>34 个一行转发（名字与方法名一一对应，见 {@link PackRuntime}）；</li>
 *    <li>2 个纯静态判断——{@link #selected} 与 {@link #isNative}，它们只碰文件系统，不需要任何实例。</li>
 * </ul>
 * "装好"与"没装"分别由 {@link InstalledPackRuntime} 与 {@link NotInstalledPackRuntime} 提供，
 * 所以"缺席时到底答什么"有一处可读、一处可测，而不是散在 15 个 {@code runtime == null} 分支里。
 * <p>
 * <b>门面里那 37 个方法名是 mixin 能摸到的全部接口，不得改名。</b>
 * mixin 由游戏实例化，只能访问静态成员；改名不会有任何编译错误，只会在运行时静默失效。
 * <p>
 * {@link #install} 由 {@code CompositionRoot} 调用，而**不是**由
 * {@link com.caldera.shaders.runtime.MinecraftShaderHost} 自己调用：那个 host 已经在调
 * {@code prepare}／{@code activate}／{@code close}／{@code requestGeometryRebuild}／{@code failure}，
 * 若再由它安装自己的依赖，就成了 host → 静态门面 → adapter → host 的构造期循环。
 */
public final class NativePackRuntime {

   /**
    * 当前这一份实现。**永不为 null**——未安装是 {@link NotInstalledPackRuntime#INSTANCE} 这个适配器，
    * 不是缺失。
    * <p>
    * 未安装是一个**真实且预期的状态**：它在客户端初始化之前、以及每个测试 JVM 里都存在，
    * 所以那张表必须能在它之下正确回答，而不是靠"反正装过了"。
    */
   private static PackRuntime current = NotInstalledPackRuntime.INSTANCE;

   private NativePackRuntime() {
   }

   /** 安装唯一实例。必须在客户端初始化之前、{@code ShaderRuntime.init()} 之前调用。 */
   public static void install(ShaderHost host) {
      current = new InstalledPackRuntime(host);
   }

   /**
    * 卸载当前实例，回到"尚未安装"的状态。
    * <p>
    * 包级可见，供测试使用：ADR-0003 用同一手法让"未安装时门面给出安全答案"这条契约可验证。
    */
   static void uninstall() {
      current = NotInstalledPackRuntime.INSTANCE;
   }

   // ---------------------------------------------------------------- 渲染器生命周期

   /** 关闭当前生效的渲染器。 */
   public static void close() {
      current.close();
   }

   /** 为一份设置准备好渲染器。 */
   public static GraphRenderer prepare(ShaderConfig config) throws IOException {
      return current.prepare(config);
   }

   /** 读一个包在磁盘上的设置。 */
   public static PackGraph settings(String id) throws IOException {
      return current.settings(id);
   }

   /** 写入一个包的选项，并在它正是当前包时让它立刻生效。 */
   public static void applyOptions(String id, Map<String, Double> values) throws IOException {
      current.applyOptions(id, values);
   }

   /**
    * 换掉当前生效的渲染器。
    * <p>
    * {@code next} 允许为 {@code null}，而那**不是空操作**：它会清空当前生效的渲染器并把旧的排队关闭。
    * 那正是"关掉光影"时拆除 GPU 资源的唯一路径，所以调用方不能因为拿不到渲染器就跳过这一步。
    */
   public static void activate(GraphRenderer next, String id) {
      current.activate(next, id);
   }

   /** 标脏地形，让它在下一帧重建。 */
   public static void requestGeometryRebuild() {
      current.requestGeometryRebuild();
   }

   /** 渲染器上一次失败的描述，从未失败时为 {@code null}。 */
   public static String failure() {
      return current.failure();
   }

   /**
    * 这个包 id 指向的目录/压缩包确实是一个原生包。纯文件系统判断——只认路径，不认实例，
    * 所以包目录是**参数**：它来自端口的 {@code packsRoot()}，而这里不自己去问"包在哪"。
    */
   public static boolean selected(ShaderConfig config, Path packsRoot) {
      return "__builtin__".equals(config.selectedPackId())
            || config.selectedPackId() != null
                  && PackFiles.safe(config.selectedPackId())
                  && !config.selectedPackId().contains("/")
                  && !"__builtin__".equals(config.selectedPackId())
                  && isNative(packsRoot.resolve(config.selectedPackId()));
   }

   /** 这个路径是否是一个原生光影包（目录或压缩包里恰好有一份 caldera.json）。纯文件系统判断。 */
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

   // ---------------------------------------------------------------- 场景帧

   public static void captureHandProjection(Matrix4fc projection) {
      current.captureHandProjection(projection);
   }

   public static void captureWorldProjection(Matrix4fc projection) {
      current.captureWorldProjection(projection);
   }

   public static void scope(boolean enabled) {
      current.scope(enabled);
   }

   public static void beginScene(CameraRenderState currentCamera, Matrix4fc currentView, LevelRenderState state, float partialTick) {
      current.beginScene(currentCamera, currentView, state, partialTick);
   }

   public static void finishScene() {
      current.finishScene();
   }

   public static RenderPassDescriptor sceneAttachments(RenderPassDescriptor descriptor) {
      return current.sceneAttachments(descriptor);
   }

   /** 决定要不要接管一个刚建出来的 render pass，要的话包一层。 */
   public static RenderPass wrapScenePass(RenderPass pass, RenderPassDescriptor descriptor) {
      return current.wrapScenePass(pass, descriptor);
   }

   public static RenderPipeline scenePipeline(RenderPipeline base, List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments) {
      return current.scenePipeline(base, attachments);
   }

   public static void bindSceneUniforms(RenderPass pass, RenderPipeline pipeline) {
      current.bindSceneUniforms(pass, pipeline);
   }

   public static GpuTextureView weatherView() {
      return current.weatherView();
   }

   public static void captureTerrain() {
      current.captureTerrain();
   }

   public static void captureTranslucentDepth() {
      current.captureTranslucentDepth();
   }

   public static void captureWorldDepth() {
      current.captureWorldDepth();
   }

   public static void failScene(Exception problem) {
      current.failScene(problem);
   }

   public static void flushGeometryRebuild() {
      current.flushGeometryRebuild();
   }

   // ---------------------------------------------------------------- 查询

   /** 是否真的在渲染原生几何；渲染器的查询都先过这一问。 */
   public static boolean shadowFrameReady() {
      return current.shadowFrameReady();
   }

   public static HeldLightShadowRenderer heldShadows() {
      return current.heldShadows();
   }

   public static boolean usesNativeTransparency() {
      return current.usesNativeTransparency();
   }

   public static boolean render(ShaderConfig config, CameraRenderState camera, Matrix4fc view) {
      return current.render(config, camera, view);
   }

   public static boolean replacesEnvironment(boolean clouds) {
      return current.replacesEnvironment(clouds);
   }

   public static int shadowQuality() {
      return current.shadowQuality();
   }

   public static int shadowDistance() {
      return current.shadowDistance();
   }

   public static boolean animatedShadowCasters() {
      return current.animatedShadowCasters();
   }

   public static MaterialTable materials() {
      return current.materials();
   }

   public static long terrainCaptures() {
      return current.terrainCaptures();
   }

   public static long renderedFrames() {
      return current.renderedFrames();
   }

   public static long sceneReplacementCount() {
      return current.sceneReplacementCount();
   }
}
