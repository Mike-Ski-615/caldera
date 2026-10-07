package com.caldera.shaders.graph;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.render.shadow.HeldLightShadowRenderer;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4fc;

/**
 * {@link NativePackRuntime} 那 34 个方法**转发到的表**：一个光影包运行时必须答得出来的全部问题。
 * <p>
 * 为什么要有这个接口：门面的契约里有**一整个状态**是"什么都没装"——它在客户端初始化之前、以及每个测试
 * JVM 里都存在。迁移前那个状态是 `instance == null`，于是它的答案散落在门面的 15 个 null 分支里，
 * 每个方法各自发明一个安全答案，而"未安装到底意味着什么"只能靠通读门面拼出来。
 * <p>
 * 现在它是这张表的一个实现：{@link NotInstalledPackRuntime}。两个适配器是**真的**——生产环境由
 * {@link InstalledPackRuntime} 填充，缺席由那一个类填充，而门面自己不再判断。
 * <p>
 * <b>安全答案逐字未改</b>，包括"非法入参也要给安全答案"的那几条（未安装时
 * {@code render(null, null, null)} 仍然返回 {@code false}，因为它必须在触碰参数之前就回答）。
 * {@code NativePackRuntimeLifecycleTest} 里那四条用例一个字都没改——它们就是这次等价改动的证明。
 * <p>
 * 这张表宽，是因为**门面本来就宽**（mixin 只能访问静态成员，那 37 个名字被 ADR-0003 冻结）；
 * 把它命名出来并没有新增接口面，只是把门面今天在转发的东西写清楚了一次。
 */
interface PackRuntime {

   // ------------------------------------------------------------- 渲染器生命周期

   /** 关闭当前生效的渲染器。 */
   void close();

   /**
    * 为一份设置准备好渲染器。
    *
    * @throws IllegalStateException 未安装——这条路径只在安装之后才会被走到
    * @throws IOException           准备失败；由调用方回滚设置
    */
   GraphRenderer prepare(ShaderConfig config) throws IOException;

   /** 读一个包在磁盘上的设置。 */
   PackGraph settings(String id) throws IOException;

   /** 写入一个包的选项，并在它正是当前包时让它立刻生效。 */
   void applyOptions(String id, Map<String, Double> values) throws IOException;

   /**
    * 换掉当前生效的渲染器。
    * <p>
    * {@code next} 允许为 {@code null}，而那**不是空操作**：它会清空当前生效的渲染器并把旧的排队关闭。
    */
   void activate(GraphRenderer next, String id);

   /** 标脏地形，让它在下一帧重建。 */
   void requestGeometryRebuild();

   /** 渲染器上一次失败的描述，从未失败时为 {@code null}。 */
   String failure();

   // ------------------------------------------------------------- 场景帧

   void captureHandProjection(Matrix4fc projection);

   void captureWorldProjection(Matrix4fc projection);

   void scope(boolean enabled);

   void beginScene(CameraRenderState currentCamera, Matrix4fc currentView, LevelRenderState state, float partialTick);

   void finishScene();

   RenderPassDescriptor sceneAttachments(RenderPassDescriptor descriptor);

   RenderPass wrapScenePass(RenderPass pass, RenderPassDescriptor descriptor);

   RenderPipeline scenePipeline(RenderPipeline base, List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments);

   void bindSceneUniforms(RenderPass pass, RenderPipeline pipeline);

   GpuTextureView weatherView();

   void captureTerrain();

   void captureTranslucentDepth();

   void captureWorldDepth();

   void failScene(Exception problem);

   void flushGeometryRebuild();

   // ------------------------------------------------------------- 查询

   boolean shadowFrameReady();

   HeldLightShadowRenderer heldShadows();

   boolean usesNativeTransparency();

   boolean render(ShaderConfig config, CameraRenderState camera, Matrix4fc view);

   boolean replacesEnvironment(boolean clouds);

   int shadowQuality();

   int shadowDistance();

   boolean animatedShadowCasters();

   MaterialTable materials();

   long terrainCaptures();

   long renderedFrames();

   long sceneReplacementCount();
}
