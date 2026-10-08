package com.caldera.shaders.render.shadow;

import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阴影关卡作用域：进出配对强制、阶段、以及"它属于一个线程"。
 * <p>
 * 这个测试在迁移前是写不出来的：那时"正在画阴影贴图"由两个类上的六份静态状态回答，其中两处还是
 * {@code ThreadLocal}，而配对全靠调用顺序。现在进出都在一个模块里，配对是强制的，于是那些误用可以在
 * 纯 JVM 里逐条钉住。
 * <p>
 * <b>钉不到的那一半：</b>目标与 UBO 是 GPU 类型（{@code RenderTarget}／{@code GpuBufferSlice}），
 * 测试里造不出来，所以"进入时存下来、退出时清掉"这条只能靠类型与调用点保证，这里传 {@code null}
 * 只用来驱动阶段。Sodium 那一批 pass 用的是真对象——{@link SodiumShadowTerrainPasses} 的表构造
 * 不需要 GPU。
 */
class ShadowPassScopeTest {

	private static final TerrainRenderPass[] BATCH = SodiumShadowTerrainPasses.terrainPasses(0, 1);

	/** 无论用例怎么失败，都不能把一个开着的作用域留给同一个线程上的下一个用例。 */
	@AfterEach
	void leaveTheScope() {
		while (ShadowPassScope.active()) {
			ShadowPassScope.exit();
		}

		// 批那一侧没有"当前是不是开着"的查询，只能按已知状态清：多清一次会抛，所以只在可能开着时清。
		try {
			ShadowPassScope.exitSodiumBatch();
		} catch (IllegalStateException expected) {
			// 本来就没开着。
		}
	}

	// ---------------------------------------------------------------- 进出配对

	@Test
	void enteringIsReflectedByActiveAndExitingClearsIt() {
		assertFalse(ShadowPassScope.active());
		assertNull(ShadowPassScope.target(), "不在阴影关卡里就没有目标");
		assertNull(ShadowPassScope.uniforms(), "不在阴影关卡里就没有级联 UBO");

		ShadowPassScope.enter(null, null);

		assertTrue(ShadowPassScope.active());

		ShadowPassScope.exit();

		assertFalse(ShadowPassScope.active());
	}

	@Test
	void enteringTwiceIsLoud() {
		ShadowPassScope.enter(null, null);

		// 原件里嵌套进入会静默改写级联号，而之后的一次 endCascade 会让两个作用域一起消失。
		assertThrows(IllegalStateException.class, () -> ShadowPassScope.enter(null, null));
	}

	@Test
	void exitingWithoutEnteringIsLoud() {
		// 原件里这是一次静默赋值：它会把"还在阴影关卡里"这件事抹掉，或者什么也不做——
		// 两种都不会报错。
		assertThrows(IllegalStateException.class, ShadowPassScope::exit);
	}

	// ---------------------------------------------------------------- Sodium 的批

	@Test
	void theBatchIsVisibleWhilePreparingAndIndexableWhileDrawing() {
		ShadowPassScope.enterSodiumBatch(BATCH);

		assertSame(BATCH, ShadowPassScope.preparingPasses(), "构建期间读取方要拿到这一批");

		ShadowPassScope.markBatchPrepared();

		assertNull(ShadowPassScope.preparingPasses(), "构建结束后读取方要退回原版行为");
		assertEquals(0, ShadowPassScope.passIndex(BATCH[0]), "绘制期间仍要能按身份找到序号");
		assertEquals(1, ShadowPassScope.passIndex(BATCH[1]));

		ShadowPassScope.exitSodiumBatch();

		assertNull(ShadowPassScope.preparingPasses());
		assertEquals(-1, ShadowPassScope.passIndex(BATCH[0]), "批结束之后不再是阴影 pass");
	}

	@Test
	void theBatchStepsArePaired() {
		assertThrows(IllegalStateException.class, ShadowPassScope::exitSodiumBatch);
		assertThrows(IllegalStateException.class, ShadowPassScope::markBatchPrepared);

		ShadowPassScope.enterSodiumBatch(BATCH);

		assertThrows(IllegalStateException.class, () -> ShadowPassScope.enterSodiumBatch(BATCH));

		ShadowPassScope.exitSodiumBatch();
	}

	@Test
	void passIndexComparesByIdentityNotByEquality() {
		ShadowPassScope.enterSodiumBatch(BATCH);

		// 一个"看起来一样"的新 pass 不是这一批里的那个。
		TerrainRenderPass lookalike = new TerrainRenderPass(ChunkSectionLayer.SOLID, false, false);
		assertEquals(-1, ShadowPassScope.passIndex(lookalike));
	}

	// ---------------------------------------------------------------- 线程

	@Test
	void theScopeBelongsToOneThread() throws InterruptedException {
		ShadowPassScope.enter(null, null);
		AtomicBoolean seenOnWorker = new AtomicBoolean(true);
		Thread worker = new Thread(() -> seenOnWorker.set(ShadowPassScope.active()));

		worker.start();
		worker.join();

		assertFalse(seenOnWorker.get(), "阴影关卡是当前线程的事，别的线程不该看见它");
		assertTrue(ShadowPassScope.active(), "而且它在本线程上照旧成立");
	}
}
