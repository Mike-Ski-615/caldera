package com.caldera.shaders.graph;

import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.runtime.FakeShaderHost;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景帧的阶段与门闸。
 * <p>
 * 这个测试在迁移前是写不出来的：那时"资源重载中"与"正在画阴影贴图"是这个模块**静态伸手**的两个
 * 单例（{@code ShaderRuntime.resourceReloading()}、{@code DirectionalShadowRenderer.isRenderingShadowMap()}），
 * 摆布它们要动另外两个模块的全局状态。现在两者都是构造时注入的，于是重载闸门可以在这里逐格钉住。
 * <p>
 * <b>能钉的与钉不到的，界限写在这里。</b>门闸里"有没有渲染器"那一项观测不到：
 * {@code GraphRenderer} 的构造要真的 {@code GpuDevice}，而它是 final，造不出替身。于是
 * {@code canDraw}/{@code canCaptureScene}/{@code canAttachScene} 在这里恒为 false，
 * "阴影关卡那一项只排除捕获、不排除世界深度"以及"捕获只在阴影关卡之外发生"这两条差异也钉不住——
 * 它们与"有没有渲染器"相与之后观测不到。这里钉的是不依赖渲染器的那几项：阶段与顺序强制、
 * 重载闸门、几何重建闸门、失败语义，以及 {@code shadowFrameReady()} **故意不看渲染器**这条差异。
 */
class SceneFrameTest {

	private final FakeShaderHost host = new FakeShaderHost();
	private final ShaderConfig config = new ShaderConfig();
	private boolean reloading;

	/** 每一步都造一个新的，免得一个用例的摆布漏到下一个。 */
	private SceneFrame frame() {
		return new SceneFrame(this.host, () -> this.config, () -> this.reloading, () -> false);
	}

	/** 一个真实可用的相机与视图（与 NativePackRuntimeLifecycleTest 用的是同一份探针结论）。 */
	private static CameraRenderState beginScene(SceneFrame frame) {
		CameraRenderState camera = new CameraRenderState();
		frame.scope(true);
		frame.beginScene(camera, new Matrix4f(), null, 0.0F);
		return camera;
	}

	// ---------------------------------------------------------------- 阶段

	@Test
	void beginSceneOpensTheSceneByItselfAndFinishClosesIt() {
		SceneFrame frame = frame();

		beginScene(frame);

		// beginScene 自己开 scene：不再需要调用方先手工开一个"scene 作用域"。
		assertTrue(frame.shadowFrameReady());

		frame.finishScene();

		assertFalse(frame.shadowFrameReady());
	}

	@Test
	void anUnusableViewKeepsTheSceneClosed() {
		SceneFrame frame = frame();
		frame.scope(true);

		// 投影退化（行列式为 0）时 cameraReady 为假：这一帧不该开 scene。
		frame.beginScene(new CameraRenderState(), new Matrix4f().zero(), null, 0.0F);

		assertFalse(frame.shadowFrameReady());
	}

	@Test
	void aPendingGeometryRebuildKeepsTheSceneClosed() {
		SceneFrame frame = frame();
		frame.scope(true);
		frame.requestGeometryRebuild();

		frame.beginScene(new CameraRenderState(), new Matrix4f(), null, 0.0F);

		assertFalse(frame.shadowFrameReady());
	}

	// ---------------------------------------------------------------- 重载闸门（注入之后才钉得住）

	@Test
	void aReloadKeepsThePendingGeometryRebuild() {
		SceneFrame frame = frame();
		beginScene(frame);
		frame.requestGeometryRebuild();
		assertFalse(frame.shadowFrameReady(), "标脏之后本帧不能再画");

		this.reloading = true;
		frame.flushGeometryRebuild();

		assertFalse(frame.shadowFrameReady(), "重载中不许把待重建清掉");

		this.reloading = false;
		frame.flushGeometryRebuild();

		assertTrue(frame.shadowFrameReady(), "不在重载中就该清掉，下一帧照常开");
		assertTrue(this.host.gpuCommands.isEmpty(), "level() 为 null 时不许提交命令或重建几何");
	}

	// ---------------------------------------------------------------- 顺序强制

	@Test
	void theFrameScopeMustBeBalanced() {
		SceneFrame frame = frame();
		frame.scope(true);

		assertThrows(IllegalStateException.class, () -> frame.scope(true), "同一个 scope 开两次必须大声失败");

		frame.scope(false);

		assertThrows(IllegalStateException.class, () -> frame.scope(false), "没开就关必须大声失败");
	}

	@Test
	void beginSceneWithoutAScopeIsLoud() {
		SceneFrame frame = frame();

		assertThrows(IllegalStateException.class,
				() -> frame.beginScene(new CameraRenderState(), new Matrix4f(), null, 0.0F));
	}

	@Test
	void beginSceneTwiceWithoutFinishingIsLoud() {
		SceneFrame frame = frame();
		beginScene(frame);

		assertThrows(IllegalStateException.class,
				() -> frame.beginScene(new CameraRenderState(), new Matrix4f(), null, 0.0F));
	}

	@Test
	void finishSceneWithoutAScopeIsLoud() {
		SceneFrame frame = frame();

		assertThrows(IllegalStateException.class, frame::finishScene);
	}

	// ---------------------------------------------------------------- 失败语义

	@Test
	void aSceneFailureBecomesThePersistentFailureWhenTheSceneEnds() {
		SceneFrame frame = frame();
		beginScene(frame);
		frame.fail(new IllegalStateException("shadow producer did not run"));

		assertNull(frame.failure(), "本帧的失败在 finish 之前还不算数");

		frame.finishScene();

		assertEquals("Shader pack paused: shadow producer did not run", frame.failure());
	}

	@Test
	void aLaterHealthyFrameDoesNotClearTheRecordedFailure() {
		SceneFrame frame = frame();
		beginScene(frame);
		frame.fail(new IllegalStateException("boom"));
		frame.finishScene();
		String recorded = frame.failure();

		beginScene(frame);
		frame.finishScene();

		// 失败文案是给界面看的，它活到下一次 activate 为止——不是每帧重算。
		assertEquals(recorded, frame.failure());
	}

	// ---------------------------------------------------------------- 没有渲染器时的安全答案

	@Test
	void everythingThatNeedsARendererAnswersSafelyWithoutOne() {
		SceneFrame frame = frame();
		CameraRenderState camera = beginScene(frame);

		assertTrue(frame.shadowFrameReady(), "这一条故意不看有没有渲染器：ShadowService 拿它与质量相与");
		assertFalse(frame.usesNativeTransparency());
		assertNull(frame.weatherView());
		assertNull(frame.heldShadows());
		assertNull(frame.materials());
		assertNull(frame.renderer());
		assertEquals(0, frame.shadowQuality());
		assertEquals(128, frame.shadowDistance());
		assertEquals(0L, frame.renderedFrames());
		assertEquals(0L, frame.terrainCaptures());
		assertEquals(0L, frame.sceneReplacementCount());
		assertFalse(frame.animatedShadowCasters());
		assertFalse(frame.replacesEnvironment(true));
		assertFalse(frame.replacesEnvironment(false));
		assertFalse(frame.render(this.config, camera, new Matrix4f()));

		RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "probe").build();
		assertSame(descriptor, frame.sceneAttachments(descriptor), "没有渲染器时不许改道");
	}
}
