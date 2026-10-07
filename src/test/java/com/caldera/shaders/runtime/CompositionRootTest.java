package com.caldera.shaders.runtime;

import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.graph.NativePackRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配根：一次装完三个模块，装的是同一份 host。
 * <p>
 * 三条腿里**两条可观测**，第三条观测不到，界限写在这里：
 * <ul>
 *    <li>{@code ShaderRuntime} —— {@code init()} 会读这份 host，于是在它身上留下事件；</li>
 *    <li>{@code NativePackRuntime} —— 装好之后它的顺序强制是**真的**：未安装时 {@code scope(true)}
 *        是空操作（见 {@code NativePackRuntimeLifecycleTest}），装好之后连开两次会抛；</li>
 *    <li>{@code DirectionalShadowRenderer} —— 它的 host 只在"阴影真的启用"时才被触及
 *        （{@code requireHost()} 在 {@code prepare()} 的就绪分支里），而启用需要真的渲染器，
 *        所以在纯 JVM 里观测不到。它由装配根本身保证：那一行与另外两行在同一个方法里。</li>
 * </ul>
 * 这个用例刻意不复原全局状态：门面是进程级的，而每一个关心"缺席"的用例都自己先 {@code uninstall}
 * （见两个生命周期测试的 {@code @BeforeEach}），所以这里留着装好的状态不构成干扰。
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
}
