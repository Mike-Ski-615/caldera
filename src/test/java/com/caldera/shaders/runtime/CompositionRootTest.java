package com.caldera.shaders.runtime;

import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.graph.NativePackRuntime;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配根：一次装完三个模块，装的是同一份 host。
 * <p>
 * 三条腿各自的**可观测到什么**，界限写在这里：
 * <ul>
 *    <li>{@code ShaderRuntime} —— {@code init()} 会读这份 host，于是在它身上留下事件；</li>
 *    <li>{@code NativePackRuntime} —— 装好之后它的顺序强制是**真的**：连开两次会抛
 *        （见 {@code NativePackRuntimeLifecycleTest} 里"错了会抛"那一组）；</li>
 *    <li>{@code DirectionalShadowRenderer} —— 实例由 {@code install} 创建，所以装配跑过之后
 *        {@code get()} 不再为 null。但注入进去的那两条依赖（宿主、包状态）读不到：它们只在
 *        "阴影真的启用"时才被触及，而启用需要真的渲染器 + 真的质量档位，纯 JVM 里造不出来。
 *        那一步由装配根本身保证——那一行与另外两行在同一个方法里，顺序是承重的
 *        （{@code shadowsEnabled} 与 {@code animatedCasters} 都是 {@code NativePackRuntime}
 *        的查询）。</li>
 * </ul>
 * 这些用例刻意不复原全局状态：门面是进程级的，而这里每一条都自己 {@code install} 一份新的 host，
 * 所以留着装好的状态不构成干扰。原先还有一条用例靠反射调 {@code DirectionalShadowRenderer.uninstall()}
 * 来复原"装配之前"那个 null 状态——那个接缝随运行时观察面一起收回了，没有它就无法确定性地摆出
 * 那个前提，于是那条用例也随之删除。
 */
class CompositionRootTest {

	@Test
	void installingWiresTheShaderLifecycleToThisHost() {
		FakeShaderHost host = new FakeShaderHost();
		host.stored = new ShaderConfig(true, ShaderConfig.BUILTIN_PACK_ID);

		CompositionRoot.install(host);
		ShaderRuntime.init();

		assertEquals(List.of("loadConfig", "ensurePackDirectory", "scanPacks"), host.events,
				"init 必须读的是这一份 host");
	}

	@Test
	void installingWiresThePackRuntimeSoThatOrderingIsEnforced() {
		CompositionRoot.install(new FakeShaderHost());

		NativePackRuntime.scope(true);
		try {
			// 未安装时这一句是空操作；装好之后它是真的强制。
			assertThrows(IllegalStateException.class, () -> NativePackRuntime.scope(true));
		} finally {
			NativePackRuntime.scope(false);
		}
	}

	@Test
	void installingAgainRebindsEveryModuleToTheNewHost() {
		FakeShaderHost first = new FakeShaderHost();
		FakeShaderHost second = new FakeShaderHost();
		second.stored = new ShaderConfig(true, ShaderConfig.BUILTIN_PACK_ID);

		CompositionRoot.install(first);
		CompositionRoot.install(second);
		ShaderRuntime.init();

		assertTrue(first.events.isEmpty(), "换过之后旧的 host 不该再被读到");
		assertEquals(List.of("loadConfig", "ensurePackDirectory", "scanPacks"), second.events);
	}

	@Test
	void thePackDirectoryIsAnsweredFromTheInstalledHost() {
		FakeShaderHost host = new FakeShaderHost();
		Path root = Path.of("somewhere", "shaders");
		host.packsRoot = root;

		CompositionRoot.install(host);

		assertEquals(root, ShaderRuntime.packsRoot(), "界面打开包文件夹用的就是这个答案");

		ShaderRuntime.uninstall();

		assertNull(ShaderRuntime.packsRoot(), "还没装好时没有路径可给——界面据此把它当成空操作");
	}
}
