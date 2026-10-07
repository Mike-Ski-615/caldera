package com.caldera.shaders.runtime;

import com.caldera.shaders.config.ShaderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 光影生命周期。这些断言在迁移前是写不出来的：原件把状态放在静态字段里、
 * 并且直接向 Minecraft 与 NativePackRuntime 伸手，所以连"读设置"都无法指向一份内存实现。
 *
 * 现在跨过 {@link ShaderHost} 端口即可，不需要 Minecraft、GPU 或窗口。
 * 每个用例都重新 install 一份 {@link FakeShaderHost}，因此同一个 JVM 里可以跑任意多次。
 */
class ShaderRuntimeLifecycleTest {

	private FakeShaderHost host;

	@BeforeEach
	void setUp() {
		this.host = new FakeShaderHost();
		ShaderRuntime.install(this.host);
	}

	@AfterEach
	void tearDown() {
		ShaderRuntime.uninstall();
	}

	private static String messageOf(CompletableFuture<Void> future) {
		try {
			future.join();
			return null;
		} catch (CompletionException failure) {
			return failure.getCause() == null ? null : failure.getCause().getMessage();
		}
	}

	// ------------------------------------------------------------ 未安装时的安全答案

	@Test
	void staticAccessorsAreSafeBeforeAnInstanceIsInstalled() {
		ShaderRuntime.uninstall();

		assertFalse(ShaderRuntime.resourceReloading());
		assertFalse(ShaderRuntime.shadersEnabled());
		assertTrue(ShaderRuntime.scanResult().supportedPacks().isEmpty());

		ShaderConfig fallback = ShaderRuntime.config();
		assertNotNull(fallback);
		assertTrue(fallback.enabled(), "未安装时的兜底设置等于迁移前静态字段的初值");

		CompletableFuture<Void> apply = ShaderRuntime.applyConfig(new ShaderConfig(false, "__builtin__"), false);
		assertTrue(apply.isCompletedExceptionally());
		assertEquals("Caldera shader runtime is not installed", messageOf(apply));

		CompletableFuture<Void> refresh = ShaderRuntime.refresh();
		assertTrue(refresh.isCompletedExceptionally());
		assertEquals("Caldera shader runtime is not installed", messageOf(refresh));
	}

	// ------------------------------------------------------------ init

	@Test
	void initFallsBackToTheBuiltinPackWhenTheSavedPackVanished() {
		this.host.stored = new ShaderConfig(true, "Vanished");
		this.host.scanned = List.of();

		ShaderRuntime.init();

		assertEquals("__builtin__", ShaderRuntime.config().selectedPackId());
		assertTrue(ShaderRuntime.config().enabled(), "回退只改包 id，不该动开关");
		assertEquals(1, this.host.savedConfigs.size(), "回退必须被持久化");
		assertEquals("__builtin__", this.host.savedConfigs.getLast().selectedPackId());
	}

	@Test
	void initKeepsASavedPackThatStillExists() {
		this.host.stored = new ShaderConfig(true, "Nice");
		this.host.scanned = List.of(FakeShaderHost.pack("Nice"));

		ShaderRuntime.init();

		assertEquals("Nice", ShaderRuntime.config().selectedPackId());
		assertTrue(this.host.savedConfigs.isEmpty(), "没有变化就不该写盘");
		assertEquals(List.of("loadConfig", "ensurePackDirectory", "scanPacks"), this.host.events);
	}

	@Test
	void initAlwaysCreatesTheDirectoryBeforeScanning() {
		this.host.stored = new ShaderConfig(false, "__builtin__");

		ShaderRuntime.init();

		int ensure = this.host.events.indexOf("ensurePackDirectory");
		int scan = this.host.events.indexOf("scanPacks");
		assertTrue(ensure >= 0 && scan > ensure, "必须先建目录再扫描: " + this.host.events);
	}

	// ------------------------------------------------------------ bootstrap

	@Test
	void bootstrapAppliesTheLoadedConfig() {
		this.host.stored = new ShaderConfig(false, "__builtin__");
		ShaderRuntime.init();
		this.host.events.clear();

		ShaderRuntime.bootstrap();

		assertTrue(this.host.prepareCalled, "bootstrap 必须走一次 applyConfig");
		assertEquals(List.of("__builtin__"), this.host.handles.getFirst().activated);
		assertEquals(1, this.host.saveOptionsCount);
		assertEquals(1, this.host.geometryRebuildCount, "不需要重载时仍要标脏地形");
		assertTrue(this.host.reloads.isEmpty(), "关闭状态下的引导不该发起重载");
	}

	// ------------------------------------------------------------ applyConfig

	@Test
	void applyingWithoutAReloadStillActivatesAndSavesOptions() {
		this.host.stored = new ShaderConfig(false, "__builtin__");
		ShaderRuntime.init();

		ShaderRuntime.applyConfig(new ShaderConfig(false, "__builtin__"), false);

		assertFalse(ShaderRuntime.resourceReloading());
		assertEquals(List.of("__builtin__"), this.host.handles.getFirst().activated);
		assertEquals(1, this.host.saveOptionsCount);
		assertTrue(this.host.reloads.isEmpty());
	}

	@Test
	void aForcedRebuildSetsTheReloadingFlagUntilTheReloadCompletes() {
		this.host.vulkan = true;
		this.host.stored = new ShaderConfig(true, "__builtin__");
		ShaderRuntime.init();

		CompletableFuture<Void> apply = ShaderRuntime.applyConfig(new ShaderConfig(true, "__builtin__"), true);

		assertTrue(ShaderRuntime.resourceReloading(), "重载进行中标志必须已置位——二十个 mixin 站点靠它当门闸");
		assertEquals(1, this.host.reloads.size());
		assertEquals(1, this.host.closeReloadableResourcesCount, "发起重载前必须先关掉可重载资源");
		assertTrue(this.host.handles.getFirst().activated.isEmpty(), "重载未完成前不得激活");
		assertEquals(0, this.host.geometryRebuildCount, "重载路径只在回调里标脏地形一次，发起前不该标");

		this.host.reloads.getFirst().complete(null);
		apply.join();

		assertFalse(ShaderRuntime.resourceReloading(), "重载结束后标志必须复位");
		assertEquals(List.of("__builtin__"), this.host.handles.getFirst().activated);
		assertEquals(1, this.host.geometryRebuildCount, "重载完成后标脏地形，且只标一次");
	}

	@Test
	void aDetachedLegacyPackForcesAReloadEvenWithoutAForcedRebuild() {
		this.host.vulkan = false;
		this.host.legacyDetached = true;
		this.host.stored = new ShaderConfig(false, "__builtin__");
		ShaderRuntime.init();

		ShaderRuntime.applyConfig(new ShaderConfig(false, "__builtin__"), false);

		assertEquals(1, this.host.reloads.size(), "摘掉旧包就必须重载资源");
		assertTrue(ShaderRuntime.resourceReloading());
	}

	@Test
	void applyingAgainWhileAReloadIsInFlightFailsFast() {
		this.host.vulkan = true;
		this.host.stored = new ShaderConfig(true, "__builtin__");
		ShaderRuntime.init();

		ShaderRuntime.applyConfig(new ShaderConfig(true, "__builtin__"), true);
		CompletableFuture<Void> second = ShaderRuntime.applyConfig(new ShaderConfig(true, "__builtin__"), true);

		assertTrue(second.isCompletedExceptionally());
		assertEquals("A shader reload is already in progress", messageOf(second));
		assertEquals(1, this.host.reloads.size(), "第二次不得再发起重载");
		assertEquals(1, this.host.preparedConfigs.size(), "第二次不得再准备渲染器");
	}

	@Test
	void aFailedReloadRestoresThePreviousConfigAndDiscardsTheCandidate() {
		this.host.vulkan = true;
		this.host.stored = new ShaderConfig(true, "__builtin__");
		ShaderRuntime.init();

		CompletableFuture<Void> apply = ShaderRuntime.applyConfig(new ShaderConfig(true, "Next"), true);
		this.host.reloads.getFirst().completeExceptionally(new IllegalStateException("reload blew up"));

		assertTrue(apply.isCompletedExceptionally());
		assertEquals("__builtin__", ShaderRuntime.config().selectedPackId(), "必须回滚到之前的设置");
		assertFalse(ShaderRuntime.resourceReloading(), "失败后标志必须复位，否则后续永远无法再应用");
		assertEquals(1, this.host.handles.getFirst().closes, "候选渲染器必须被丢弃");
		assertTrue(this.host.handles.getFirst().activated.isEmpty(), "失败不得激活");
		assertEquals("__builtin__", this.host.savedConfigs.getLast().selectedPackId(), "回滚后的设置必须写回");
	}

	@Test
	void aPreparationFailureRestoresThePreviousConfigAndReportsIt() {
		this.host.stored = new ShaderConfig(false, "__builtin__");
		ShaderRuntime.init();
		this.host.prepareFailure = new IllegalStateException("no descriptor pool");

		CompletableFuture<Void> apply = ShaderRuntime.applyConfig(new ShaderConfig(true, "Next"), true);

		assertTrue(apply.isCompletedExceptionally());
		assertEquals("no descriptor pool", messageOf(apply));
		assertEquals("__builtin__", ShaderRuntime.config().selectedPackId());
		assertFalse(ShaderRuntime.resourceReloading(), "准备阶段抛异常时标志也必须保持干净");
		assertTrue(this.host.reloads.isEmpty(), "准备失败时不该发起重载");
	}

	// ------------------------------------------------------------ 选择与关闭

	@Test
	void closeDiscardsPacksAndClosesGpuResources() {
		this.host.stored = new ShaderConfig(true, "__builtin__");
		this.host.scanned = List.of(FakeShaderHost.pack("Nice"));
		ShaderRuntime.init();

		ShaderRuntime.close();

		assertTrue(ShaderRuntime.scanResult().supportedPacks().isEmpty());
		assertEquals(1, this.host.closeRendererCount);
		assertEquals(1, this.host.closeReloadableResourcesCount);
	}

	@Test
	void shadersEnabledRequiresBothTheSwitchAndTheVulkanBackend() {
		this.host.vulkan = false;
		this.host.stored = new ShaderConfig(true, "__builtin__");
		ShaderRuntime.init();

		assertFalse(ShaderRuntime.shadersEnabled(), "非 Vulkan 后端下即使开关打开也不算启用");

		this.host.vulkan = true;
		assertTrue(ShaderRuntime.shadersEnabled());
	}

	// ------------------------------------------------------------ 刷新与渲染器失败

	@Test
	void refreshOnlyRescansWhenTheSelectedPackIsStillThere() {
		this.host.stored = new ShaderConfig(true, "Nice");
		this.host.scanned = List.of(FakeShaderHost.pack("Nice"));
		ShaderRuntime.init();
		this.host.events.clear();

		CompletableFuture<Void> refresh = ShaderRuntime.refresh();

		assertTrue(refresh.isDone());
		assertFalse(refresh.isCompletedExceptionally());
		assertEquals(List.of("scanPacks"), this.host.events, "没有变化时只重扫，不该准备渲染器或重载");
		assertEquals("Nice", ShaderRuntime.config().selectedPackId());
		assertTrue(this.host.preparedConfigs.isEmpty());
	}

	@Test
	void refreshFallsBackToTheBuiltinPackWhenTheSelectedOneWasDeleted() {
		this.host.stored = new ShaderConfig(true, "Nice");
		this.host.scanned = List.of(FakeShaderHost.pack("Nice"));
		ShaderRuntime.init();

		// 用户在界面打开期间把那个包删了，然后按「刷新」
		this.host.scanned = List.of();
		ShaderRuntime.refresh();

		assertEquals("__builtin__", ShaderRuntime.config().selectedPackId());
		assertEquals("__builtin__", this.host.preparedConfigs.getLast().selectedPackId(),
				"回退必须真的生效，而不是只改配置");
		assertTrue(this.host.savedConfigs.stream().anyMatch(saved -> "__builtin__".equals(saved.selectedPackId())),
				"回退必须被持久化，否则重启又会指向消失的包");
	}

	@Test
	void refreshIsSafeBeforeAnInstanceIsInstalled() {
		ShaderRuntime.uninstall();

		CompletableFuture<Void> refresh = ShaderRuntime.refresh();

		assertTrue(refresh.isCompletedExceptionally());
		assertEquals("Caldera shader runtime is not installed", messageOf(refresh));
	}

	@Test
	void rendererFailureIsExposedOnlyWhenThereIsOne() {
		this.host.stored = new ShaderConfig(true, "__builtin__");
		ShaderRuntime.init();

		assertNull(ShaderRuntime.rendererFailure(), "没有失败时必须返回 null，界面靠它决定要不要报『着色器不可用』");

		this.host.rendererFailure = "Couldn't compile pipeline caldera:core/terrain";
		assertEquals("Couldn't compile pipeline caldera:core/terrain", ShaderRuntime.rendererFailure());

		ShaderRuntime.uninstall();
		assertNull(ShaderRuntime.rendererFailure(), "未安装时也必须给安全答案");
	}
}
