package com.caldera.shaders.runtime;

import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.pack.ShaderPackScanner;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;
import net.minecraft.client.multiplayer.ClientLevel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * {@link ShaderHost} 的内存实现，供测试填充。
 * <p>
 * 它不做任何真实工作，只记录被调用了什么、按什么顺序、以及被传了什么参数——
 * 这样就能在不启动 Minecraft、不碰 GPU 的前提下断言整个生命周期的时序。
 * <p>
 * {@link #clientThread()} 直接同步执行，让重载回调在测试里变成确定性的。
 * <p>
 * 公开可见，因为 {@code NativePackRuntime} 的测试也用它：那个类的测试在
 * {@code com.caldera.shaders.graph} 包，跨不过包私有。这也正是它存在的意义——
 * 一个"游戏能力"的替身，供所有需要它的测试共用，而不是每个包各写一个。
 */
public final class FakeShaderHost implements ShaderHost {

	/** 记录所有被调用的事件名，用于断言顺序。 */
	public final List<String> events = new ArrayList<>();
	/** 每一次 saveConfig 收到的对象。 */
	public final List<ShaderConfig> savedConfigs = new ArrayList<>();
	/** 每一次 prepareRenderer 收到的设置。 */
	public final List<ShaderConfig> preparedConfigs = new ArrayList<>();
	/** 每一次 reloResources 返回的 future，由测试决定何时完成。 */
	public final List<CompletableFuture<Void>> reloads = new ArrayList<>();
	/** 每一次 prepareRenderer 返回的句柄。 */
	public final List<RecordingRenderer> handles = new ArrayList<>();

	/** loadConfig() 返回的内容。 */
	public ShaderConfig stored = new ShaderConfig();
	/**
	 * 每个包存下来的选项，按包 id 分。
	 * <p>
	 * 这就是 pack options 那条链的内存替身：生产侧是
	 * {@code CalderaConfigFiles} 写 {@code config/caldera-packs/<sha256>.json}，
	 * 这里是一个 map。有了它，"读出来的旧值会被迁移成什么"才测得动。
	 */
	public final Map<String, Map<String, Double>> storedPackOptions = new HashMap<>();
	/** 非 null 时 loadPackOptions 抛出它，用来测"文件坏了"那条路径。 */
	public Exception packOptionsFailure;
	/** scanPacks() 返回的受支持条目。 */
	public List<ShaderPackScanner.AvailableShaderPack> scanned = List.of();
	/** scanPacks() 返回的无法识别条目。 */
	public List<ShaderPackScanner.UnsupportedShaderPack> unsupported = List.of();
	/** vulkanActive() 的返回值。 */
	public boolean vulkan;
	/** detachLegacyPack() 的返回值。 */
	public boolean legacyDetached;
	/** 非 null 时 prepareRenderer 抛出它。 */
	public Exception prepareFailure;
	/** rendererFailure() 的返回值。 */
	public String rendererFailure;
	/** prepareRenderer 是否被调用过。 */
	public boolean prepareCalled;

	int saveOptionsCount;
	int geometryRebuildCount;
	int closeRendererCount;
	int closeReloadableResourcesCount;

	static ShaderPackScanner.AvailableShaderPack pack(String id) {
		return new ShaderPackScanner.AvailableShaderPack(id, id, Path.of("/nowhere", id), true);
	}

	@Override
	public ShaderConfig loadConfig() {
		this.events.add("loadConfig");
		return this.stored;
	}

	@Override
	public void saveConfig(ShaderConfig config) {
		this.events.add("saveConfig");
		this.savedConfigs.add(config);
		this.stored = config;
	}

	@Override
	public Map<String, Double> loadPackOptions(String packId) throws java.io.IOException {
		this.events.add("loadPackOptions:" + packId);
		if (this.packOptionsFailure != null) {
			throw new java.io.IOException("Invalid saved pack options: " + packId, this.packOptionsFailure);
		}

		return this.storedPackOptions.getOrDefault(packId, Map.of());
	}

	@Override
	public void savePackOptions(String packId, Map<String, Double> values) throws java.io.IOException {
		this.events.add("savePackOptions:" + packId);
		this.storedPackOptions.put(packId, Map.copyOf(values));
	}

	@Override
	public void ensurePackDirectory() {
		this.events.add("ensurePackDirectory");
	}

	@Override
	public ShaderPackScanner.ScanResult scanPacks() {
		this.events.add("scanPacks");
		return new ShaderPackScanner.ScanResult(this.scanned, this.unsupported);
	}

	@Override
	public boolean vulkanActive() {
		return this.vulkan;
	}

	@Override
	public boolean detachLegacyPack() {
		this.events.add("detachLegacyPack");
		return this.legacyDetached;
	}

	@Override
	public void saveOptions() {
		this.events.add("saveOptions");
		this.saveOptionsCount++;
	}

	@Override
	public CompletableFuture<Void> reloadResources() {
		this.events.add("reloadResources");
		CompletableFuture<Void> pending = new CompletableFuture<>();
		this.reloads.add(pending);
		return pending;
	}

	@Override
	public Executor clientThread() {
		return Runnable::run;
	}

	@Override
	public PreparedRenderer prepareRenderer(ShaderConfig config) throws Exception {
		this.events.add("prepareRenderer");
		this.prepareCalled = true;
		if (this.prepareFailure != null) {
			throw this.prepareFailure;
		}

		this.preparedConfigs.add(config);
		RecordingRenderer handle = new RecordingRenderer();
		this.handles.add(handle);
		return handle;
	}

	@Override
	public void closeRenderer() {
		this.events.add("closeRenderer");
		this.closeRendererCount++;
	}

	@Override
	public void closeReloadableResources() {
		this.events.add("closeReloadableResources");
		this.closeReloadableResourcesCount++;
	}

	@Override
	public void requestGeometryRebuild() {
		this.events.add("requestGeometryRebuild");
		this.geometryRebuildCount++;
	}

	@Override
	public String rendererFailure() {
		return this.rendererFailure;
	}

	// ---------------------------------------------------------------- frame 侧能力

	/** mainRenderTarget() 的返回值；默认 null，即"游戏还没建立渲染目标"。 */
	public RenderTarget mainRenderTarget;
	/** level() 的返回值；默认 null，即"不在世界里"。 */
	public ClientLevel level;
	/** inOverworld() 的返回值。故意与 level 解耦：模块从不解读 level，只问这一句。 */
	public boolean inOverworld;
	/** 每一次 submitCommands() 与 queueFence()。 */
	public final List<String> gpuCommands = new ArrayList<>();

	@Override
	public RenderTarget mainRenderTarget() {
		return this.mainRenderTarget;
	}

	@Override
	public ClientLevel level() {
		return this.level;
	}

	@Override
	public boolean inOverworld() {
		return this.inOverworld;
	}

	@Override
	public void submitCommands() {
		this.events.add("submitCommands");
		this.gpuCommands.add("submit");
	}

	@Override
	public void queueFence(Runnable task) {
		this.events.add("queueFence");
		this.gpuCommands.add("fence");
		// 同步跑，和 clientThread() 一样：让测试不必等到"栅栏之后"。
		task.run();
	}

	@Override
	public void invalidateCompiledGeometry() {
		this.events.add("invalidateCompiledGeometry");
	}

	/** renderDistance() 的返回值。默认 12，一个不会让覆盖范围被截断的值。 */
	public int renderDistance = 12;
	/** maxTextureSizeForFormat() 的返回值；按格式记，未设过的一律 8192。 */
	public final Map<GpuFormat, Integer> textureSizeLimits = new HashMap<>();

	@Override
	public int renderDistance() {
		return this.renderDistance;
	}

	@Override
	public int maxTextureSizeForFormat(GpuFormat format) {
		return this.textureSizeLimits.getOrDefault(format, 8192);
	}

	/** 记录被激活的包 id 与被关闭的次数。 */
	static final class RecordingRenderer implements PreparedRenderer {
		final List<String> activated = new ArrayList<>();
		int closes;

		@Override
		public void activate(String packId) {
			this.activated.add(packId);
		}

		@Override
		public void close() {
			this.closes++;
		}
	}
}
