package com.caldera.shaders.runtime;

import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.pack.ShaderPackScanner;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 光影运行时需要游戏提供的全部东西，用**普通类型**表达。
 * <p>
 * 为什么要这个接缝：0.5.1 原件的 {@code ShaderRuntime} 是一整片静态字段，并且直接向
 * {@code Minecraft}、{@code FabricLoader}、{@code NativePackRuntime} 伸手。结果是整个生命周期
 * 无法被单元测试触碰——连"读设置/写设置"都无法指向临时目录，连"资源重载中"这个标志的
 * 置位时序都测不到。而那个标志有二十个 mixin 站点在依赖它当门闸。
 * <p>
 * 这是一个**真正的接缝**，而不是假想的：生产环境由 {@link MinecraftShaderHost} 实现，
 * 测试里由内存实现填充。跨过它的两端都不需要 {@code Minecraft}，也不需要 GPU。
 */
public interface ShaderHost {

	/** 读取持久化的光影设置。 */
	ShaderConfig loadConfig();

	/** 持久化光影设置。 */
	void saveConfig(ShaderConfig config);

	/**
	 * 读一个包存下来的选项；没有文件时返回空表。
	 * <p>
	 * 原先这条路径**不在端口上**：{@code NativePackRuntime} 自己拼 {@code config/caldera-packs/}
	 * 下的 SHA-256 文件名、自己解析 JSON。于是"包选项怎么落盘"这一整条链在测试里根本走不到，
	 * 而它和 settings 那条链干的是同一件事。
	 * <p>
	 * 返回**已经解析好的值**：文件格式是适配器的事，"哪些值合法、旧格式怎么迁移"是模块的事
	 * （那需要包自己的 {@code optionDefinitions} 才判断得了）。
	 *
	 * @throws java.io.IOException 文件在、但内容不是一份能认的选项表
	 */
	Map<String, Double> loadPackOptions(String packId) throws java.io.IOException;

	/** 写一个包的选项。 */
	void savePackOptions(String packId, Map<String, Double> values) throws java.io.IOException;

	/** 确保光影包目录存在。 */
	void ensurePackDirectory();

	/**
	 * 扫描光影包目录。
	 * <p>
	 * 返回**完整**结果而不是只有受支持的那些：界面需要把"被忽略的条目"数量显示出来，
	 * 而 0.3.1 的界面在窗口尺寸变化时会重读这份结果，所以调用方必须把它缓存起来。
	 */
	ShaderPackScanner.ScanResult scanPacks();

	/** 当前是否跑在 Vulkan 后端上。非 Vulkan 时 Caldera 整体停摆。 */
	boolean vulkanActive();

	/**
	 * 摘掉 0.3.1 时代遗留在 {@code options} 与资源包仓库里的旧记录。
	 *
	 * @return 是否真的改动了什么——它同时也是"这次要不要重载资源"的一个输入
	 */
	boolean detachLegacyPack();

	/** 持久化 {@code options}。 */
	void saveOptions();

	/** 发起一次资源重载。 */
	CompletableFuture<Void> reloadResources();

	/**
	 * 客户端线程执行器（{@code Minecraft} 自身）。
	 * <p>
	 * {@code reloadResources()} 的回调必须回到客户端线程，这一对方法就是那道边界的两个方向。
	 */
	Executor clientThread();

	/**
	 * 为一份设置准备好渲染器。
	 * <p>
	 * <b>必须返回非 null 的句柄</b>，即使这次没有渲染器可建。原因是原件在禁用态或非 Vulkan 后端下
	 * 会拿到 {@code prepare()} 的 null，然后仍然调用 {@code NativePackRuntime.activate(null, id)}——
	 * 而那不是空操作：它会清空当前生效的渲染器，并把旧的渲染器排队关闭。
	 * 那正是"关掉光影"时拆除 GPU 资源的唯一路径，漏掉它就会泄漏。
	 * <p>
	 * 这里刻意不暴露 {@code GraphRenderer} 这个具体类型，否则测试就必须真的建 GPU 资源。
	 *
	 * @throws Exception 与原件保持一致：准备失败即抛出，由调用方回滚设置
	 */
	PreparedRenderer prepareRenderer(ShaderConfig config) throws Exception;

	/** 关闭当前生效的渲染器。 */
	void closeRenderer();

	/** 关闭所有可重载的 GPU 资源（阴影管线与渲染器）。 */
	void closeReloadableResources();

	/** 标脏地形，让它在下一帧重建。 */
	void requestGeometryRebuild();

	/**
	 * 渲染器上一次失败的描述，从未失败时为 {@code null}。
	 * <p>
	 * 界面靠它显示"光影开着、画面却没效果"。走端口而不是让界面直接读渲染器的静态字段，
	 * 是为了让这个状态能在测试里被摆布。
	 */
	String rendererFailure();

	/**
	 * 主渲染目标；游戏尚未建立时可能为 {@code null}。
	 * <p>
	 * 这是一个**不透明转交**：{@code NativePackRuntime} 只把它原样交给
	 * {@code GraphRenderer}，自己不读它的任何属性。所以测试里的内存实现直接返回
	 * {@code null} 就能覆盖"没有渲染器"的那些路径，不需要造一个真的 {@code RenderTarget}。
	 */
	RenderTarget mainRenderTarget();

	/** 当前客户端世界；不在世界里时为 {@code null}。同样是不透明转交。 */
	ClientLevel level();

	/**
	 * 玩家是否在主世界。
	 * <p>
	 * 这是 frame 侧唯一一个**被模块自己解读**的世界属性，所以它单独成一条，
	 * 而不是让模块去解读 {@link #level()}：否则为了这一个 boolean，
	 * 测试就得构造一个真的 {@code ClientLevel}。{@link #level()} 为 {@code null} 时必须返回
	 * {@code false}。
	 */
	boolean inOverworld();

	/** 提交一次命令缓冲。几何重建之前必须先把已排队的命令交出去。 */
	void submitCommands();

	/** 把 GPU 资源的释放排到栅栏之后：当前帧可能还在用它们。 */
	void queueFence(Runnable task);

	/**
	 * 标脏已编译的地形几何。
	 * <p>
	 * 只在 {@link #level()} 非 null 时才允许调用——那个判断由调用方做，
	 * 这是原件的行为，不是可以顺手合并的守卫。
	 */
	void invalidateCompiledGeometry();

	/**
	 * 有效渲染距离（区块数）。
	 * <p>
	 * 阴影覆盖范围取它与阴影距离的较小者，所以要问游戏。
	 */
	int renderDistance();

	/**
	 * 某个格式能申请的纹理边长上限。
	 * <p>
	 * 照抄游戏那一形，而不是在这里合成一个 `maxShadowMapSize()`：后者会把"阴影贴图用
	 * R8_UNORM 与 D32_FLOAT"这条 shadow 侧的知识塞到游戏边界这一头，放错了地方。
	 */
	int maxTextureSizeForFormat(GpuFormat format);

	/**
	 * 一份已经准备好、可以被激活或被丢弃的渲染器。
	 * <p>
	 * 把 {@code activate} 与 {@code close} 一起放在句柄上，是为了让
	 * {@code ShaderRuntime} 的调用顺序与原件逐行对应，同时不必让测试认识 {@code GraphRenderer}。
	 * 两个方法都**不允许抛出受检异常**，与 {@code GraphRenderer} 现有签名一致。
	 * <p>
	 * 句柄内部允许不含渲染器：此时 {@code activate} 等价于 {@code activate(null, id)}（拆除），
	 * {@code close} 是空操作。
	 */
	interface PreparedRenderer {
		/** 让它生效；不含渲染器时即拆除当前生效的那个。 */
		void activate(String packId);

		/** 丢弃它；允许重复调用，也允许句柄内不含渲染器。 */
		void close();
	}
}
