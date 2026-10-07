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
 * **这一帧生效的渲染器**——{@link SceneFrame} 的门闸与查询所面对的那张表。
 * <p>
 * 它存在的理由是「没有生效的渲染器」在 {@link SceneFrame} 里是一个**真实且预期的状态**：客户端初始化之后、
 * 用户选定一个包之前，以及每一次 {@code activate(null, id)} 拆除之后，都是它。迁移前那个状态是字段为
 * {@code null}，于是它的答案散在 {@link SceneFrame} 的二十处 {@code renderer == null} 分支里，每个方法
 * 各自发明一个安全答案；而「有没有生效的渲染器」这一问也因此**观测不到**——
 * {@code GraphRenderer} 是 {@code final}，构造要真的 {@code GpuDevice}，测试造不出替身。
 * <p>
 * 现在它是这张表的一个实现：{@link InstalledRenderer} 把 {@link GraphRenderer} 包起来，
 * {@link AbsentRenderer} 是「什么都没有」的那一个（常量、无状态）。{@link SceneFrame#attach} 只接受
 * 本接口，不接受 {@code null}，所以「缺席」在它内部从此是一个对象而不是缺失。
 * <p>
 * <b>两个方法的名字不是随手取的。</b>{@link #present()} 回答的是「这个对象包着真东西吗」，
 * {@link #materials()} 在缺席侧返回 {@code null}（见它自己的注释）；除此之外，缺席侧的回答全是常量
 * —— {@code null} / {@code false} / {@code 0} / {@code 0L} / 空操作，与迁移前那些分支逐字一致。
 * <p>
 * <b>它与 {@code ShaderHost.PreparedRenderer} 是两件事，别合并。</b>那个句柄承载的是生命周期**动作**
 * （{@code activate} / {@code close}），本接口承载的是**查询**；把它俩合成一个会造出一个同时是
 * 「可激活的句柄」和「可查询的渲染器」的混合体。两者的共同点只有一条：**永不为 null**。
 * <p>
 * 包级可见：接口、两个适配器、{@link SceneFrame}、{@link GraphRenderer} 与 {@link InstalledPackRuntime}
 * 都在这个包里，包外没有任何类需要认识它。
 */
interface ActiveRenderer {

   // ---------------------------------------------------------------- 拆除

   /**
    * 释放这个渲染器占的 GPU 资源。允许重复调用；缺席侧是空操作。
    * <p>
    * {@link SceneFrame#detach} 用它算「旧的怎么退役」，生产侧的调用发生在
    * {@code ShaderHost.queueFence} 之后。
    */
   void close();

   // ---------------------------------------------------------------- 是否是"有东西"的那一个

   /**
    * 这个对象背后是不是真的有一个渲染器。
    * <p>
    * 它是这次改动新增的唯一一个**不是转发**的成员：门闸里原先那两三处 {@code renderer != null}
    * 需要它。{@link SceneFrame#attach} 之后这个字段永不为 null，所以「有没有生效的渲染器」这一问
    * 从此只能这样问——不能再退回字段比较，否则「有没有」又会有两个来源。
    */
   boolean present();

   // ---------------------------------------------------------------- 渲染器查询

   /**
    * 这个包的材质表；缺席侧是 {@code null}。
    * <p>
    * <b>允许为 {@code null} 是既有契约，不是这里新开的。</b>生产侧的值来自
    * {@link GraphRenderer#materials()}，而它只在构造器后半段才被赋上：构造器在
    * {@link GraphRenderer#materials()} 为 null 的那一段抛出时，{@code close()} 会经由
    * {@code GraphShaderSources.Owner} 回到这里。因此 {@link SceneFrame#pause} 里的
    * {@code old.materials().enabled()} 仍要守 null，那一处是**唯一**的消费点。
    */
   MaterialTable materials();

   /** 手持光源的阴影渲染器；这个包没有声明 {@code HELD_LIGHTING} 时为 {@code null}。 */
   HeldLightShadowRenderer heldShadows();

   /** 已经渲染了多少帧。门禁用它记录。 */
   long renderedFrames();

   /** 场景被替换了多少次。门禁用它记录。 */
   long sceneReplacementCount();

   /** 捕获过多少次地形。门禁用它记录。 */
   long terrainCaptures();

   /** 本包声明的方向光阴影质量档位；0 表示不投射阴影。 */
   int shadowQuality();

   /** 本包声明的阴影距离。 */
   int shadowDistance();

   /** 本包是否声明了会动阴影的投射者（植被风）。 */
   boolean animatedShadowCasters();

   /** 这个包是否会替换原版的天空或云。 */
   PackGraph.Environment environment();

   // ---------------------------------------------------------------- 场景帧协议

   /** 手表投影的逆矩阵；{@code null} 或退化矩阵都表示"没有"。 */
   void handProjection(Matrix4fc projection);

   /** 本帧的世界投影矩阵。 */
   void projection(Matrix4fc projection);

   /** 用本帧的环境状态刷新帧 uniform 的来源；落进缓冲发生在 {@code beginScene} 里。 */
   void environment(LevelRenderState state, ClientLevel level, float partialTick);

   /** 开始本帧的原生渲染。 */
   void beginScene(RenderTarget target, CameraRenderState camera, Matrix4fc view, Object world);

   /** 丢掉历史（前一次投影与视图），下一帧按"不连续"处理。 */
   void invalidateHistory();

   /** 把本帧画出来。 */
   void render(RenderTarget main, CameraRenderState camera, Matrix4fc view, Object world);

   /** 原版主场景的颜色视图，供天气那一段用；不接管时为 {@code null}。 */
   GpuTextureView weatherView();

   /** 把原版已经画好的主场景捕获下来，供原生 pass 采样。 */
   void captureTerrain(RenderTarget main);

   /** 捕获主场景的世界深度。 */
   void captureWorldDepth(RenderTarget main);

   /** 本包想接管的附件描述；不接管时返回传进来的那一个。 */
   RenderPassDescriptor sceneAttachments(RenderPassDescriptor descriptor, RenderTarget main);

   /** 这批附件是不是正好是声明过的那些场景目标。 */
   boolean hasSceneAttachments(List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments);

   /** 把一条原版管线换成这个包的；不接管时返回原样。 */
   RenderPipeline scenePipeline(RenderPipeline original, boolean attachments);

   /** 换不了的时候退回什么。 */
   RenderPipeline sceneFallback(RenderPipeline original, boolean attachments);

   /** 绑定这个包声明的帧 uniform 与贴图。 */
   void bindSceneUniforms(RenderPass pass, RenderPipeline pipeline);
}
