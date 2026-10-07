package com.caldera.shaders.graph;

import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 帧 uniform **写进去的字节**：{@link GraphFrame#fill} 与 {@link GraphFrame#fillEnvironment}。
 * <p>
 * {@code FrameLayoutTest} 钉的是布局（生成的 GLSL 文本、23 个偏移、总长 1072），而这里钉的是
 * **写**。两者缺一不可：偏移对了但写错位置，或者写对了位置但值算错，画面都会不对，而且都不会抛异常
 * ——着色器安静地读到隔壁字段的值。
 * <p>
 * 这个测试在候选 6 之前写不出来：那时写入与 {@code encoder.transientMemory().uploadGpu(...)} 挤在
 * 同一个方法里，而后者要真的设备。现在输入是普通对象、时间来自注入的时钟，于是整帧协议可以逐字节驱动。
 * <p>
 * 断言用的是 {@link FrameLayout} 的偏移而不是硬编码的数字：偏移本身已经被上面那个金样测试钉住了，
 * 这里要钉的是"哪一栏放了哪个值"。
 */
class FrameUniformsTest {

	/** 固定时钟：时间分量必须可预测，否则金样必然不稳定。 */
	private static final class Clock {
		private long now;

		long read() {
			return this.now;
		}

		void tick(long nanos) {
			this.now += nanos;
		}
	}

	private static CameraRenderState cameraAt(double x, double y, double z) {
		CameraRenderState camera = new CameraRenderState();
		camera.pos = new Vec3(x, y, z);
		camera.depthFar = 512.0F;
		camera.fogType = null;
		FogData fog = new FogData();
		fog.color = new Vector4f(0.25F, 0.5F, 0.75F, 1.0F);
		fog.environmentalStart = 1.0F;
		fog.environmentalEnd = 2.0F;
		fog.renderDistanceStart = 3.0F;
		fog.renderDistanceEnd = 4.0F;
		fog.skyEnd = 5.0F;
		camera.fogData = fog;
		return camera;
	}

	private static float read(byte[] bytes, FrameLayout.Field field) {
		return java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder()).getFloat(FrameLayout.offset(field));
	}

	/** 把 {@link GraphFrame} 写出来的缓冲拷成普通数组，好按偏移读数。 */
	private static byte[] drain(GraphFrame frame) {
		// fill() 之后缓冲处于 flip 状态：position=0、limit=写出长度。
		var buffer = frame.uniformBytes();
		byte[] copy = new byte[buffer.remaining()];
		buffer.duplicate().get(copy);
		return copy;
	}

	@Test
	void theCameraAndViewMatricesLandInTheirDeclaredSlots() {
		GraphFrame frame = new GraphFrame(() -> 0L);
		Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(70.0), 1.5F, 0.05F, 1000.0F);
		Matrix4f view = new Matrix4f().rotateY((float)Math.toRadians(30.0));
		CameraRenderState camera = cameraAt(10.0, 64.0, -10.0);
		camera.projectionMatrix = projection;

		frame.fill(camera, view, 1920, 1080, true);
		byte[] bytes = drain(frame);

		assertEquals(projection.m00(), readMatrix(bytes, FrameLayout.Field.PROJECTION).m00(), 1.0E-6F,
				"投影矩阵必须落在 PROJECTION 那一栏");
		assertEquals(view.m11(), readMatrix(bytes, FrameLayout.Field.VIEW).m11(), 1.0E-6F,
				"视图矩阵必须落在 VIEW 那一栏");
	}

	private static Matrix4f readMatrix(byte[] bytes, FrameLayout.Field field) {
		float[] values = new float[16];
		java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder());
		for (int i = 0; i < 16; i++) {
			values[i] = buffer.getFloat(FrameLayout.offset(field) + i * 4);
		}

		return new Matrix4f().set(values);
	}

	@Test
	void theFrameStateNumericFieldsLandInTheirDeclaredSlots() {
		Clock clock = new Clock();
		GraphFrame frame = new GraphFrame(clock::read);
		CameraRenderState camera = cameraAt(10.0, 64.0, -10.0);
		camera.projectionMatrix = new Matrix4f();

		// 第一帧走 reset：相机增量与时间增量都写 0，帧号也写 0——这是迁移前的写法。
		frame.fill(camera, new Matrix4f(), 1920, 1080, true);

		// 模拟"这一帧画完之后"：commit 会记住相机位置并把帧号推进到 1。
		clock.tick(100000000L);
		frame.commit(camera, new Matrix4f(), new Object());
		clock.tick(500000000L);

		frame.fill(camera, new Matrix4f(), 1920, 1080, false);
		byte[] bytes = drain(frame);

		assertEquals(1.0F, readOffset(bytes, FrameLayout.Field.CAMERA_DELTA_AND_HISTORY_VALID, 3), 1.0E-6F,
				"CAMERA_DELTA_AND_HISTORY_VALID.w 是「历史有效」标志，非 reset 帧必须是 1");
		// 相机没动，所以三个增量都是 0。
		assertEquals(0.0F, read(bytes, FrameLayout.Field.CAMERA_DELTA_AND_HISTORY_VALID), 1.0E-6F);
		assertEquals(0.0F, readOffset(bytes, FrameLayout.Field.CAMERA_DELTA_AND_HISTORY_VALID, 1), 1.0E-6F);
		assertEquals(0.0F, readOffset(bytes, FrameLayout.Field.CAMERA_DELTA_AND_HISTORY_VALID, 2), 1.0E-6F);

		assertEquals(0.6F, read(bytes, FrameLayout.Field.TIME_DELTA_FRAME), 1.0E-4F,
				"距起点 0.6 秒（注入时钟决定）");
		assertEquals(0.5F, readOffset(bytes, FrameLayout.Field.TIME_DELTA_FRAME, 1), 1.0E-4F,
				"距上一帧 0.5 秒");
		assertEquals(1.0F, readOffset(bytes, FrameLayout.Field.TIME_DELTA_FRAME, 2), 1.0E-6F,
				"帧号在 commit 之后是 1");

		assertEquals(1920.0F, read(bytes, FrameLayout.Field.VIEW_SIZE_AND_INVERSE), 1.0E-6F);
		assertEquals(1080.0F, readOffset(bytes, FrameLayout.Field.VIEW_SIZE_AND_INVERSE, 1), 1.0E-6F);
		assertEquals(1.0F / 1920.0F, readOffset(bytes, FrameLayout.Field.VIEW_SIZE_AND_INVERSE, 2), 1.0E-9F);
		assertEquals(1.0F / 1080.0F, readOffset(bytes, FrameLayout.Field.VIEW_SIZE_AND_INVERSE, 3), 1.0E-9F);
	}

	private static float readOffset(byte[] bytes, FrameLayout.Field field, int floatOffset) {
		return java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder())
				.getFloat(FrameLayout.offset(field) + floatOffset * 4);
	}

	@Test
	void theCameraPositionAndFogLandInTheirDeclaredSlots() {
		GraphFrame frame = new GraphFrame(() -> 0L);
		CameraRenderState camera = cameraAt(10.5, 64.25, -10.75);
		camera.projectionMatrix = new Matrix4f();

		frame.fill(camera, new Matrix4f(), 1920, 1080, true);
		byte[] bytes = drain(frame);

		assertEquals(10.5F, read(bytes, FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE), 1.0E-6F);
		assertEquals(64.25F, readOffset(bytes, FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE, 1), 1.0E-6F);
		assertEquals(-10.75F, readOffset(bytes, FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE, 2), 1.0E-6F);

		// 低精度那三个是"精确值减高精度那三个"，所以这里应当精确为 0。
		assertEquals(0.0F, read(bytes, FrameLayout.Field.CAMERA_POSITION_LOW_AND_FAR_PLANE), 1.0E-6F);
		assertEquals(512.0F, readOffset(bytes, FrameLayout.Field.CAMERA_POSITION_LOW_AND_FAR_PLANE, 3), 1.0E-6F);

		assertEquals(0.25F, read(bytes, FrameLayout.Field.FOG_COLOR_AND_START), 1.0E-6F);
		assertEquals(0.5F, readOffset(bytes, FrameLayout.Field.FOG_COLOR_AND_START, 1), 1.0E-6F);
		assertEquals(0.75F, readOffset(bytes, FrameLayout.Field.FOG_COLOR_AND_START, 2), 1.0E-6F);
		assertEquals(1.0F, readOffset(bytes, FrameLayout.Field.FOG_COLOR_AND_START, 3), 1.0E-6F, "environmentalStart");

		assertEquals(2.0F, read(bytes, FrameLayout.Field.FOG_DISTANCES), 1.0E-6F, "environmentalEnd");
		assertEquals(3.0F, readOffset(bytes, FrameLayout.Field.FOG_DISTANCES, 1), 1.0E-6F);
		assertEquals(4.0F, readOffset(bytes, FrameLayout.Field.FOG_DISTANCES, 2), 1.0E-6F);
		assertEquals(5.0F, readOffset(bytes, FrameLayout.Field.FOG_DISTANCES, 3), 1.0E-6F, "skyEnd");
	}

	/**
	 * 一份**半填充**的相机状态不许让帧 uniform 抛。
	 * <p>
	 * {@code fogData} 与 {@code camera.pos} 都是可空字段（vanilla 的构造器不设它们，提取器那边有守卫），
	 * 而这两处原先都会直接解引用。后果与 {@code skyColor} 那次同类：不是画错一帧，
	 * 是 {@code SceneFrame} 把整份光影包暂停掉。
	 */
	@Test
	void anUnextractedCameraStateDoesNotBreakTheFrameUniforms() {
		GraphFrame frame = new GraphFrame(() -> 0L);
		CameraRenderState camera = new CameraRenderState();
		camera.projectionMatrix = new Matrix4f();
		// pos 与 fogData 都留空，正是"还没被提取过"的样子。

		assertDoesNotThrow(() -> frame.fill(camera, new Matrix4f(), 1280, 720, true));
		assertDoesNotThrow(() -> frame.discontinuity(camera, new Object(), new Matrix4f()));
	}

	/**
	 * 一份**半填充**的天空状态不许让环境填充抛，而且缺的那些分量退到 0。
	 * <p>
	 * 这是 {@code fillEnvironment} 变成可测之后第一次能钉住的行为：{@code skyColor} 为空时不该抛
	 * （那一帧没有天空色），而日月方向那些仍然照写。
	 */
	@Test
	void anUnextractedSkyStateFillsNeutralSkyColor() {
		float[] environment = new float[FrameLayout.ENVIRONMENT_SLOTS];
		LevelRenderState state = new LevelRenderState();
		state.skyRenderState.sunAngle = 0.0F;
		state.skyRenderState.moonAngle = 0.0F;
		// skyColor 留空。

		assertDoesNotThrow(() -> GraphFrame.fillEnvironment(environment, state, null, 0.0F, 256, new HeldLight(), false));

		int skySlot = FrameLayout.slot(FrameLayout.Field.SKY_COLOR_AND_STAR_BRIGHTNESS);
		assertEquals(0.0F, environment[skySlot], 1.0E-6F, "天空色缺席时退到 0");
		assertEquals(0.0F, environment[skySlot + 1], 1.0E-6F);
		assertEquals(0.0F, environment[skySlot + 2], 1.0E-6F);
	}

	/** 日月方向与相位：这三组槽位与 {@code fillEnvironment} 的输入一一对应。 */
	@Test
	void theCelestialDirectionsLandInTheirDeclaredSlots() {
		float[] environment = new float[FrameLayout.ENVIRONMENT_SLOTS];
		LevelRenderState state = new LevelRenderState();
		SkyRenderState sky = state.skyRenderState;
		sky.sunAngle = 90.0F;
		sky.moonAngle = 90.0F;
		sky.rainBrightness = 0.4F;
		sky.starBrightness = 0.7F;

		GraphFrame.fillEnvironment(environment, state, null, 0.0F, 256, new HeldLight(), false);

		int sun = FrameLayout.slot(FrameLayout.Field.SUN_DIRECTION_AND_RAIN_BRIGHTNESS);
		assertEquals(0.4F, environment[sun + 3], 1.0E-6F, "雨亮度在日月方向那一组的 w");

		int moon = FrameLayout.slot(FrameLayout.Field.MOON_DIRECTION_AND_PHASE);
		assertEquals((float)sky.moonPhase.index(), environment[moon + 3], 1.0E-6F, "月相在月亮那一组的 w");

		int skyColor = FrameLayout.slot(FrameLayout.Field.SKY_COLOR_AND_STAR_BRIGHTNESS);
		assertEquals(0.7F, environment[skyColor + 3], 1.0E-6F, "星亮度在天空色那一组的 w");

		// 太阳与月亮的方向是由角度算出来的单位向量，这里只要求它落在 ±1 内且长度为 1。
		Vector3f sunDirection = new Vector3f(environment[sun], environment[sun + 1], environment[sun + 2]);
		assertEquals(1.0F, sunDirection.length(), 1.0E-4F, "太阳方向必须是单位向量");
	}
}
