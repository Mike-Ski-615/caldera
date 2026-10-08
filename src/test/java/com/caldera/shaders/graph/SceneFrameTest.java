package com.caldera.shaders.graph;

import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.runtime.FakeShaderHost;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
 * <b>能钉的与钉不到的，界限写在这里。</b>门闸里"有没有生效的渲染器"那一项曾经观测不到：
 * {@code GraphRenderer} 的构造要真的 {@code GpuDevice}，而它是 final，造不出替身；于是
 * {@code canDraw}/{@code canCaptureScene}/{@code canAttachScene} 在这里恒为 false，
 * "阴影关卡那一项只排除捕获、不排除世界深度"以及"捕获只在阴影关卡之外发生"这两条差异也钉不住
 * ——它们与"有没有渲染器"相与之后观测不到。
 * <p>
 * <b>那条界限现在退后了一步。</b>{@code SceneFrame} 手上的渲染器是 {@link ActiveRenderer}，
 * 而缺席是 {@link AbsentRenderer#INSTANCE} 这个对象——所以"没有生效的渲染器"这件事可以**被摆布**，
 * 而不是只能靠"没调 {@code attach}"来表示。本类钉的是它；"有渲染器时那半边门闸"仍旧钉不住
 * （那需要 {@code GraphRenderer} 的替身，仍然不存在），所以下面每一处都写清它钉的是哪一半。
 */
class SceneFrameTest {

	private final FakeShaderHost host = new FakeShaderHost();
	private final ShaderConfig config = new ShaderConfig();
	private boolean reloading;

	/** 每一步都造一个新的，免得一个用例的摆布漏到下一个。 */
	private SceneFrame frame() {
		return new SceneFrame(this.host, () -> this.config, () -> this.reloading, () -> false,
				() -> true);
	}

	/**
	 * 显式把一个**缺席**的渲染器装上去，而不是靠"没调 attach"。
	 * <p>
	 * 这一行就是这次改动的证明：迁移前"没有渲染器"只能由字段为 null 表示，
	 * 而现在它是与"有渲染器"同一张表上的另一个实现。
	 */
	private SceneFrame frameWithAbsentRenderer() {
		SceneFrame frame = this.frame();
		frame.attach(AbsentRenderer.INSTANCE, null);
		return frame;
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

	// ---------------------------------------------------------------- 缺席渲染器的安全答案

	/**
	 * 显式装上一个**缺席**的渲染器之后，需要渲染器的每一问都答得安全。
	 * <p>
	 * 这一条是迁移前那条同名用例的等价物：断言一条没改，只是"没有渲染器"从一个缺失的字段变成了
	 * {@link AbsentRenderer#INSTANCE} 这个对象。
	 */
	@Test
	void everythingThatNeedsARendererAnswersSafelyWithoutOne() {
		SceneFrame frame = frameWithAbsentRenderer();
		CameraRenderState camera = beginScene(frame);

		assertTrue(frame.shadowFrameReady(), "这一条故意不看有没有渲染器——所以它在这里答 true");
		assertFalse(frame.usesNativeTransparency());
		assertNull(frame.weatherView());
		assertNull(frame.heldShadows());
		assertNull(frame.materials());
		assertEquals(0, frame.shadowQuality());
		// 这一对就是"该不该投射阴影"那两半：帧就绪为真、质量为 0，所以合取为假。
		// 它说明为什么那个判断必须由门面（两侧都够得到）来答，而不能由 shadow 侧自己答。
		assertEquals(128, frame.shadowDistance());
		assertFalse(frame.animatedShadowCasters());
		assertFalse(frame.replacesEnvironment(true));
		assertFalse(frame.replacesEnvironment(false));
		assertFalse(frame.render(this.config, camera, new Matrix4f()));

		RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "probe").build();
		assertSame(descriptor, frame.sceneAttachments(descriptor), "没有渲染器时不许改道");
	}

	/**
	 * 「有没有生效的渲染器」这一问现在是一个可摆布的对象。
	 * <p>
	 * 它取代了原先那条 {@code assertNull(frame.renderer())}：迁移前这个问题只能用"字段是不是 null"
	 * 来回答，于是它既钉不住（造不出有渲染器的那一半），也说不清"缺席"到底意味着什么。
	 */
	@Test
	void absenceIsAnObjectNotAMissingField() {
		assertFalse(AbsentRenderer.INSTANCE.present(), "缺席侧要说自己不在");

		SceneFrame frame = frameWithAbsentRenderer();

		assertFalse(AbsentRenderer.INSTANCE.present(), "装上一个缺席的渲染器不会被当成有渲染器");
		assertFalse(frame.render(this.config, new CameraRenderState(), new Matrix4f()),
				"缺席的渲染器不接管任何一帧");
	}

	/**
	 * 阴影资源没就绪时，**缺席**的渲染器不会触发那条"阴影生产者没跑"的检查。
	 * <p>
	 * <b>这条测试删掉了，原因记在这里：</b>它试图断言的"供应商根本不会被问"在缺席路径上恒真——
	 * {@link SceneFrame#scenePipeline} 的第一道门闸 {@code canAttachScene()} 就返回假，整个
	 * {@code if} 根本不执行。要真的走到那个合取式，需要一个**在场的**渲染器，而那正是
	 * 这个测试类注释里写明的界限（{@code GraphRenderer} 是 final，构造要真的 {@code GpuDevice}）。
	 * 试过、确实恒真，所以不留一条骗人的绿灯；那三位的顺序改为在生产代码里注明。
	 */

	/**
	 * 缺席侧的**转发形状**第一次可以直接断言。
	 * <p>
	 * {@code sceneAttachments} 在缺席时必须把传进来的描述符原样还回去，而这一半原先钉不住：
	 * {@link SceneFrame#sceneAttachments} 在门闸为假时**根本不会**走到渲染器那一侧，
	 * 于是"适配器自己有没有改道"没有观测点。现在它是一个可以单独驱动的对象。
	 */
	@Test
	void theAbsentRendererPassesAttachmentsThroughUntouched() {
		RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "probe").build();

		assertSame(descriptor, AbsentRenderer.INSTANCE.sceneAttachments(descriptor, null));
	}

	/**
	 * 天空状态**没有被提取过**时，帧 uniform 的组装不许抛。
	 * <p>
	 * {@code SkyRenderState.skyColor} 是可空字段：构造器与 {@code reset()} 都不设它，唯一写入者
	 * {@code SkyRenderer.extractRenderState} 在 vanilla 里是被 {@code skyRenderer != null} 守卫着的。
	 * 曾经在这里直接解引用，于是在真客户端上抛 NPE —— 而后果不是画错，是 {@link SceneFrame}
	 * 把整份光影包**暂停**掉且不再恢复（实测日志：{@code Shader pack paused: ... sky.skyColor is null}）。
	 * <p>
	 * 这条钉的是"不抛"，不是"画对"：那份状态本来就无从画对。缺的那三个分量退到 0，
	 * 而这个测试对它们的值不作要求——{@code environment[]} 的初值就是 0。
	 */
	@Test
	void anUnextractedSkyStateDoesNotBreakTheFrameUniforms() {
		LevelRenderState state = new LevelRenderState();

		assertDoesNotThrow(() -> new GraphFrame().environment(state, null, 0.0F));
	}
}
