package com.caldera.shaders.runtime;

import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.graph.NativePackRuntime;
import com.caldera.shaders.render.shadow.DirectionalShadowRenderer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配根：一次装完三个模块，装的是同一份 host。
 * <p>
 * 三条腿各自的**可观测到什么**，界限写在这里：
 * <ul>
 *    <li>{@code ShaderRuntime} —— {@code init()} 会读这份 host，于是在它身上留下事件；</li>
 *    <li>{@code NativePackRuntime} —— 装好之后它的顺序强制是**真的**：未安装时 {@code scope(true)}
 *        是空操作（见 {@code NativePackRuntimeLifecycleTest}），装好之后连开两次会抛；</li>
 *    <li>{@code DirectionalShadowRenderer} —— 装好之后 {@code get()} **不再为 null**
 *        （实例由 {@code install} 创建，见 {@link #installingCreatesTheShadowRendererInstance()}）。
 *        但注入进去的那两条依赖（宿主、包状态）读不到：它们只在"阴影真的启用"时才被触及，
 *        而启用需要真的渲染器 + 真的质量档位，纯 JVM 里造不出来。那一步由装配根本身保证——
 *        那一行与另外两行在同一个方法里，顺序是承重的（{@code shadowsEnabled} 与
 *        {@code animatedCasters} 都是 {@code NativePackRuntime} 的查询）。</li>
 * </ul>
 * 除那条会创建实例的用例外，这些用例刻意不复原全局状态：门面是进程级的，而每一个关心"缺席"的
 * 用例都自己先 {@code uninstall}（见两个生命周期测试的 {@code @BeforeEach}），所以留着装好的
 * 状态不构成干扰。
 */
class CompositionRootTest {

	/**
	 * 装配**创建**那个唯一的阴影渲染器实例：装好之后 {@code get()} 不再为 null。
	 * <p>
	 * 这是本次改动带来的新观测点——原先 {@code get()} 是懒建的，于是"装配到底有没有把这一条腿接上"
	 * 在纯 JVM 里看不出来（谁问谁就建一个）。现在实例只由 {@code install} 创建，所以"装配跑过了"
	 * 这件事第一次有了一个直接可断言的结果。
	 * <p>
	 * 每条用例入口与出口都复原（其余用例刻意不复原，见类注释）：这条用例的前提是"还没装"，
	 * 而同一个类里别的用例装完不收拾，所以不能靠"套件开始时是干净的"。
	 * 复原用的是反射——{@code DirectionalShadowRenderer.uninstall()} 是**包级可见**的，与
	 * {@code NativePackRuntime.uninstall()} 同形（刻意的：生产代码不该能卸载，只有测试需要）。
	 * 测试里用反射碰包级可见的接缝是本项目已有的做法，见 {@code ReloadableResourcesTest}。
	 */
	@Test
	void installingCreatesTheShadowRendererInstance() throws Exception {
		uninstallShadowRenderer();
		assertNull(DirectionalShadowRenderer.get(), "前提：装配之前没有实例");

		CompositionRoot.install(new FakeShaderHost());

		assertNotNull(DirectionalShadowRenderer.get(), "装配必须把阴影渲染器也建起来");

		uninstallShadowRenderer();
		assertNull(DirectionalShadowRenderer.get(), "复原：丢掉实例，别漏给下一个用例");
	}

	/** 见 {@link #installingCreatesTheShadowRendererInstance()} 的注释。 */
	private static void uninstallShadowRenderer() throws Exception {
		Method uninstall = DirectionalShadowRenderer.class.getDeclaredMethod("uninstall");
		uninstall.setAccessible(true);
		uninstall.invoke(null);
	}

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
