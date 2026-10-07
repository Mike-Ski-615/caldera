package com.caldera.shaders.graph;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.render.shadow.HeldLightShadowRenderer;
import com.caldera.shaders.render.shadow.ShadowPassScope;
import com.caldera.shaders.runtime.ShaderHost;
import com.caldera.shaders.runtime.ShaderRuntime;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4fc;

/**
 * "装好之后"的那一份实现：一个 {@link ShaderHost} 加一个 {@link SceneFrame}，以及渲染器生命周期与
 * 包选项语义。
 * <p>
 * 它就是把迁移前 {@code NativePackRuntime} 的实例部分整块搬过来，唯一的区别是自己不再兼任门面——
 * 门面那 37 个名字住在 {@link NativePackRuntime} 里，而这里只实现 {@link PackRuntime} 那张表。
 * 于是"实例存在"这件事从"字段不为 null"变成了"门面手上是哪一个适配器"。
 * <p>
 * 场景帧那 28 个方法都是一行转发：门闸与它护着的动作已经收在 {@link SceneFrame} 里（候选 3），这里
 * 不再重新判断任何条件。
 */
final class InstalledPackRuntime implements PackRuntime {

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

   InstalledPackRuntime(ShaderHost host) {
      this.host = host;
      this.frame = new SceneFrame(host, ShaderRuntime::config, ShaderRuntime::resourceReloading, ShadowPassScope::active);
   }

   // ------------------------------------------------------------- 渲染器生命周期

   @Override
   public void close() {
      this.frame.detach(SceneFrame.Disposal.SYNCHRONOUS);
   }

   /**
    * 该不该在这次设置下建渲染器，以及该建哪一个。
    * <p>
    * 与原件逐行对应：开关或后端不满足时返回 {@code null}；满足但包不是原生包时**抛**，
    * 因为那是用户可见的错误，不是"什么都不做"。
    */
   @Override
   public GraphRenderer prepare(ShaderConfig config) throws IOException {
      if (!this.frame.backendReady(config)) {
         return null;
      }

      if (!NativePackRuntime.selected(config, this.host.packsRoot())) {
         throw new IOException("This pack is not a native Caldera shader pack. Select Caldera Realistic or a pack with caldera.json.");
      }

      PackFiles files = packFiles(config.selectedPackId());
      PackGraph graph = loadOptions(PackGraph.parse(files.text("caldera.json")), config.selectedPackId());
      return new GraphRenderer(graph, files, this.host.mainRenderTarget());
   }

   @Override
   public PackGraph settings(String id) throws IOException {
      PackFiles files = packFiles(id);
      return loadOptions(PackGraph.parse(files.text("caldera.json")), id);
   }

   @Override
   public void applyOptions(String id, Map<String, Double> values) throws IOException {
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

   @Override
   public void activate(GraphRenderer next, String id) {
      this.activateRenderer(next, id);
   }

   @Override
   public void requestGeometryRebuild() {
      this.frame.requestGeometryRebuild();
   }

   @Override
   public String failure() {
      return this.frame.failure();
   }

   // ------------------------------------------------------------- 场景帧（一行转发）

   @Override
   public void captureHandProjection(Matrix4fc projection) {
      this.frame.captureHandProjection(projection);
   }

   @Override
   public void captureWorldProjection(Matrix4fc projection) {
      this.frame.captureWorldProjection(projection);
   }

   @Override
   public void scope(boolean enabled) {
      this.frame.scope(enabled);
   }

   @Override
   public void beginScene(CameraRenderState currentCamera, Matrix4fc currentView, LevelRenderState state, float partialTick) {
      this.frame.beginScene(currentCamera, currentView, state, partialTick);
   }

   @Override
   public void finishScene() {
      this.frame.finishScene();
   }

   @Override
   public RenderPassDescriptor sceneAttachments(RenderPassDescriptor descriptor) {
      return this.frame.sceneAttachments(descriptor);
   }

   @Override
   public RenderPass wrapScenePass(RenderPass pass, RenderPassDescriptor descriptor) {
      return this.frame.wrapScenePass(pass, descriptor);
   }

   @Override
   public RenderPipeline scenePipeline(RenderPipeline base, List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments) {
      return this.frame.scenePipeline(base, attachments);
   }

   @Override
   public void bindSceneUniforms(RenderPass pass, RenderPipeline pipeline) {
      this.frame.bindSceneUniforms(pass, pipeline);
   }

   @Override
   public GpuTextureView weatherView() {
      return this.frame.weatherView();
   }

   @Override
   public void captureTerrain() {
      this.frame.captureTerrain();
   }

   @Override
   public void captureTranslucentDepth() {
      this.frame.captureTranslucentDepth();
   }

   @Override
   public void captureWorldDepth() {
      this.frame.captureWorldDepth();
   }

   @Override
   public void failScene(Exception problem) {
      this.frame.fail(problem);
   }

   @Override
   public void flushGeometryRebuild() {
      this.frame.flushGeometryRebuild();
   }

   // ------------------------------------------------------------- 查询（一行转发）

   @Override
   public boolean shadowFrameReady() {
      return this.frame.shadowFrameReady();
   }

   @Override
   public HeldLightShadowRenderer heldShadows() {
      return this.frame.heldShadows();
   }

   @Override
   public boolean usesNativeTransparency() {
      return this.frame.usesNativeTransparency();
   }

   @Override
   public boolean render(ShaderConfig config, CameraRenderState camera, Matrix4fc view) {
      return this.frame.render(config, camera, view);
   }

   @Override
   public boolean replacesEnvironment(boolean clouds) {
      return this.frame.replacesEnvironment(clouds);
   }

   @Override
   public int shadowQuality() {
      return this.frame.shadowQuality();
   }

   @Override
   public int shadowDistance() {
      return this.frame.shadowDistance();
   }

   @Override
   public boolean animatedShadowCasters() {
      return this.frame.animatedShadowCasters();
   }

   @Override
   public MaterialTable materials() {
      return this.frame.materials();
   }

   @Override
   public long terrainCaptures() {
      return this.frame.terrainCaptures();
   }

   @Override
   public long renderedFrames() {
      return this.frame.renderedFrames();
   }

   @Override
   public long sceneReplacementCount() {
      return this.frame.sceneReplacementCount();
   }

   // ------------------------------------------------------------- pack 文件

   private PackFiles packFiles(String id) throws IOException {
      return "__builtin__".equals(id) ? PackFiles.bundled() : PackFiles.read(this.host.packsRoot().resolve(id));
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
