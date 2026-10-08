package com.caldera.shaders.runtime;

import com.caldera.shaders.render.shadow.DirectionalShadowRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可重载资源登记表：释放策略，以及"四个持有进程级 GPU 状态的模块确实登记了"这条接线。
 *
 * <p>两类断言，界限刻意划在这里：
 * <ul>
 *   <li><b>策略</b>（顺序、可反复调用、失败不中断）用本类自己造的假 owner 钉死——它们不碰 GPU，
 *       也不需要 Minecraft。每个假 owner 在 {@link #discardClaimed()} 里摘掉，所以生产模块的登记
 *       不受影响，而生产模块的登记**不能**摘：类初始化只发生一次。</li>
 *   <li><b>接线</b>只做到"已登记集合"这一层。{@code Class.forName} 就是生产里第一次碰到某个模块的
 *       那一刻——类初始化跑一遍，owner 就在登记表里了。一个**从未登记**的新模块这里探测不到，
 *       这是设计的诚实上限：能做的只是让新加一族时在写代码的地方看得见。</li>
 * </ul>
 */
class ReloadableResourcesTest {

	/**
	 * 迁移前 MinecraftShaderHost 里那张手工清单的四个所有者。
	 * <p>
	 * 只钉**集合**，不钉顺序：登记顺序是"各模块第一次被碰到的那一刻"，而那个时刻在同一个 JVM 里
	 * 取决于谁先跑（本测试自己就可能被别的测试抢先）。顺序不承载语义——四个 {@code close()} 里没有
	 * 任何一个依赖另一个还活着；顺序契约由 {@link #closeAllRunsEveryEntryInClaimOrder()} 用假 owner 钉。
	 */
	private static final List<String> THE_FOUR = List.of(
			"directional shadow pipelines",
			"directional shadow renderer",
			"sodium shadow terrain plans",
			"sodium terrain shadow pipelines");

	private final List<ReloadableResources.Owner> claimed = new ArrayList<>();

	@AfterEach
	void discardClaimed() {
		this.claimed.forEach(ReloadableResources.Owner::discardForTest);
		this.claimed.clear();
	}

	private ReloadableResources.Owner claim(String name, Runnable release) {
		ReloadableResources.Owner owner = ReloadableResources.owner(name, release);
		this.claimed.add(owner);
		return owner;
	}

	// ------------------------------------------------------------ 释放策略

	@Test
	void closeAllRunsEveryEntryInClaimOrder() {
		List<String> calls = new ArrayList<>();
		this.claim("test order first", () -> calls.add("first"));
		this.claim("test order second", () -> calls.add("second"));

		ReloadableResources.closeAll();

		assertEquals(List.of("first", "second"), calls);
	}

	@Test
	void closeAllKeepsRegistrationsSoTheNextReloadStillCloses() {
		List<String> calls = new ArrayList<>();
		this.claim("test repeat", () -> calls.add("closed"));

		ReloadableResources.closeAll();
		ReloadableResources.closeAll();

		// 关键的一条：第二次重载不许静默地什么都不关。
		assertEquals(List.of("closed", "closed"), calls);
		assertTrue(ReloadableResources.ownerNames().contains("test repeat"));
	}

	@Test
	void aFailingEntryDoesNotStopTheOthers() {
		List<String> calls = new ArrayList<>();
		RuntimeException boom = new RuntimeException("释放失败");
		this.claim("test failing", () -> {
			throw boom;
		});
		this.claim("test surviving", () -> calls.add("survived"));

		RuntimeException thrown = assertThrows(RuntimeException.class, ReloadableResources::closeAll);

		assertSame(boom, thrown);
		assertEquals(List.of("survived"), calls, "一个 owner 失败不许连带泄漏其余的");
	}

	@Test
	void aSecondFailureRidesAlongAsSuppressed() {
		RuntimeException first = new RuntimeException("第一个失败");
		RuntimeException second = new RuntimeException("第二个失败");
		this.claim("test suppressed first", () -> {
			throw first;
		});
		this.claim("test suppressed second", () -> {
			throw second;
		});

		RuntimeException thrown = assertThrows(RuntimeException.class, ReloadableResources::closeAll);

		assertSame(first, thrown);
		assertEquals(1, thrown.getSuppressed().length);
		assertSame(second, thrown.getSuppressed()[0]);
	}

	@Test
	void anOwnerWithoutAReleaseActionIsHarmless() {
		this.claim("test bare", null);

		ReloadableResources.closeAll();

		assertTrue(ReloadableResources.ownerNames().contains("test bare"));
	}

	// ------------------------------------------------------------ 接线

	@Test
	void everyModuleThatOwnsProcessGlobalGpuStateIsRegistered() throws Exception {
		for (String module : List.of(
				"com.caldera.shaders.render.shadow.DirectionalShadowPipelines",
				"com.caldera.shaders.render.shadow.DirectionalShadowRenderer",
				"com.caldera.shaders.render.shadow.SodiumShadowTerrainRenderer",
				"com.caldera.shaders.render.shadow.SodiumTerrainShadowPipelines")) {
			// 载入即类初始化——与生产里第一次碰到它的那一刻同义。
			Class.forName(module);
		}

		List<String> registered = ReloadableResources.ownerNames();

		assertTrue(registered.containsAll(THE_FOUR), "四个所有者必须都登记了，实际登记：" + registered);
		assertEquals(THE_FOUR.size(), registered.stream().filter(THE_FOUR::contains).count());
	}

	/**
	 * {@code closeAll()} 真的跑到阴影渲染器那个持有者的释放动作，而且**不换掉实例**。
	 * <p>
	 * 这条原先靠"拆过之后再取会拿到新实例"（{@code assertNotSame}）来证明释放动作跑过了；
	 * 阶段二把"close 置空实例"去掉了，所以身份断言反过来：**同一个实例**。
	 * <p>
	 * 为什么不能更强：要观测"资源真的被拆了"得先有资源，而分配 RenderTarget 需要真的
	 * {@code GpuDevice}。这里能证明的是"那次释放动作被跑到了、而且是幂等的"——
	 * 阴影渲染器排栅栏时因没有设备而退回同步拆除（见 {@code close()} 的注释），
	 * 所以它在这里也不抛。
	 */
	@Test
	void closeAllReachesTheShadowRendererWithoutReplacingIt() {
		DirectionalShadowRenderer.install(null, () -> false, () -> false);
		DirectionalShadowRenderer before = DirectionalShadowRenderer.get();
		assertNotNull(before, "装配之后必须有实例");
		assertFalse(before.resourcesReady(), "没分配过资源，所以还没就绪");

		assertDoesNotThrow(ReloadableResources::closeAll);

		assertSame(before, DirectionalShadowRenderer.get(), "closeAll 拆资源，但不换实例");
		assertFalse(before.resourcesReady(), "拆过之后仍然没有资源");
	}
}
