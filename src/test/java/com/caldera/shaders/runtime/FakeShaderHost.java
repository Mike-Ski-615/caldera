package com.caldera.shaders.runtime;

import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.pack.ShaderPackScanner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * {@link ShaderHost} 的内存实现，供测试填充。
 * <p>
 * 它不做任何真实工作，只记录被调用了什么、按什么顺序、以及被传了什么参数——
 * 这样就能在不启动 Minecraft、不碰 GPU 的前提下断言整个生命周期的时序。
 * <p>
 * {@link #clientThread()} 直接同步执行，让重载回调在测试里变成确定性的。
 */
final class FakeShaderHost implements ShaderHost {

	/** 记录所有被调用的事件名，用于断言顺序。 */
	final List<String> events = new ArrayList<>();
	/** 每一次 saveConfig 收到的对象。 */
	final List<ShaderConfig> savedConfigs = new ArrayList<>();
	/** 每一次 prepareRenderer 收到的设置。 */
	final List<ShaderConfig> preparedConfigs = new ArrayList<>();
	/** 每一次 reloResources 返回的 future，由测试决定何时完成。 */
	final List<CompletableFuture<Void>> reloads = new ArrayList<>();
	/** 每一次 prepareRenderer 返回的句柄。 */
	final List<RecordingRenderer> handles = new ArrayList<>();

	/** loadConfig() 返回的内容。 */
	ShaderConfig stored = new ShaderConfig();
	/** scanPacks() 返回的受支持条目。 */
	List<ShaderPackScanner.AvailableShaderPack> scanned = List.of();
	/** scanPacks() 返回的无法识别条目。 */
	List<ShaderPackScanner.UnsupportedShaderPack> unsupported = List.of();
	/** vulkanActive() 的返回值。 */
	boolean vulkan;
	/** detachLegacyPack() 的返回值。 */
	boolean legacyDetached;
	/** 非 null 时 prepareRenderer 抛出它。 */
	Exception prepareFailure;
	/** rendererFailure() 的返回值。 */
	String rendererFailure;
	/** prepareRenderer 是否被调用过。 */
	boolean prepareCalled;

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
