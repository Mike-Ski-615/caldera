package com.caldera.shaders.graph;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帧 uniform 的布局：生成出来的那份必须与冻结的那份对得上。
 * <p>
 * 这就是让"偏移算出来"变安全的东西。std140 算错了不会有任何运行时症状——着色器安静地读到隔壁字段的
 * 值，画出一帧不对的画面——所以这里把三件事都钉住：**GLSL 文本**（忽略空白后与迁移前逐字相同）、
 * **23 个偏移**（与 {@code upload()} 写的那些数逐一对上）、**总大小 1072**（缓冲区的分配量）。
 * <p>
 * 冻结的期望是**抄进来**的，不是从生产代码取的：金样的价值就在于它不会跟着实现一起漂。
 */
class FrameLayoutTest {

	/** 迁移前手写的那段成员声明，一字未改（除了这里不再关心换行与缩进）。 */
	private static final String FROZEN_BLOCK = """
			mat4 Projection; mat4 View; mat4 InverseProjection; mat4 InverseView;
			mat4 PreviousProjection; mat4 PreviousView;
			vec4 CameraDeltaAndHistoryValid; vec4 TimeDeltaFrame; vec4 ViewSizeAndInverse;
			vec4 WorldTimeWeatherDimension; vec4 SunDirectionAndRainBrightness; vec4 MoonDirectionAndPhase;
			vec4 CameraPositionHighAndFogType; vec4 CameraPositionLowAndFarPlane;
			vec4 FogColorAndStart; vec4 FogDistances; vec4 SkyColorAndStarBrightness;
			vec4 CloudOffsetAndGameTime;
			mat4 InverseHandProjection; vec4 HandProjectionValid;
			vec4 HeldLightPositionRadius; vec4 HeldLightColor;
			mat4 HeldLightViewProjection[6];
			""";

	/** 迁移前 {@code upload()} 里手写的那串字节偏移，按字段顺序。 */
	private static final List<Integer> FROZEN_OFFSETS = List.of(
			0, 64, 128, 192, 256, 320,
			384, 400, 416,
			432, 448, 464, 480, 496, 512, 528, 544, 560,
			576, 640, 656, 672, 688);

	private static String withoutWhitespaceRuns(String text) {
		return text.trim().replaceAll("\\s+", " ");
	}

	@Test
	void theGeneratedBlockSaysTheSameThingAsTheFrozenDeclaration() {
		assertEquals(withoutWhitespaceRuns(FROZEN_BLOCK), withoutWhitespaceRuns(FrameLayout.block()));
	}

	@Test
	void theDeclarationWrapsTheBlockInTheUniform() {
		assertEquals("layout(std140) uniform CalderaFrame {\n" + FrameLayout.block() + "};\n", GraphFrame.declaration());
	}

	@Test
	void everyFieldKeepsTheOffsetTheUploadWritesTo() {
		FrameLayout.Field[] fields = FrameLayout.Field.values();
		List<Integer> offsets = new ArrayList<>();
		for (FrameLayout.Field field : fields) {
			offsets.add(FrameLayout.offset(field));
		}

		assertEquals(FROZEN_OFFSETS.size(), offsets.size(), "字段个数变了：偏移表要跟着看一遍");
		assertEquals(FROZEN_OFFSETS, offsets);
	}

	@Test
	void theLayoutIsExactlyAsLongAsTheBufferItIsWrittenInto() {
		assertEquals(1072, FrameLayout.size());

		FrameLayout.Field last = FrameLayout.Field.values()[FrameLayout.Field.values().length - 1];
		assertEquals(384, FrameLayout.size() - FrameLayout.offset(last), "mat4[6] 是 384 字节");
	}

	@Test
	void consecutiveOffsetsAgreeWithTheTypesTheDeclarationsName() {
		FrameLayout.Field[] fields = FrameLayout.Field.values();
		List<String> declarations = List.of(FrameLayout.block().split("\n"));
		assertEquals(fields.length, declarations.size(), "每个字段恰好一行声明");

		for (int i = 0; i < fields.length - 1; i++) {
			int stride = FrameLayout.offset(fields[i + 1]) - FrameLayout.offset(fields[i]);
			assertEquals(bytesNamedBy(declarations.get(i)), stride, declarations.get(i) + " 的步长与它声明的类型对不上");
		}
	}

	/** 从声明文本读出这个字段占多少字节——不从实现里取，于是它能独立地校验偏移。 */
	private static int bytesNamedBy(String declaration) {
		assertTrue(declaration.startsWith("mat4") || declaration.startsWith("vec4"), declaration);

		if (declaration.startsWith("mat4")) {
			return declaration.contains("[6]") ? 384 : 64;
		}

		return 16;
	}

	@Test
	void theEnvironmentSlotsAreTheNineConsecutiveVec4s() {
		assertEquals(36, FrameLayout.ENVIRONMENT_SLOTS);

		assertEquals(0, FrameLayout.slot(FrameLayout.Field.WORLD_TIME_WEATHER_DIMENSION));
		assertEquals(4, FrameLayout.slot(FrameLayout.Field.SUN_DIRECTION_AND_RAIN_BRIGHTNESS));
		assertEquals(8, FrameLayout.slot(FrameLayout.Field.MOON_DIRECTION_AND_PHASE));
		assertEquals(12, FrameLayout.slot(FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE));
		assertEquals(16, FrameLayout.slot(FrameLayout.Field.CAMERA_POSITION_LOW_AND_FAR_PLANE));
		assertEquals(20, FrameLayout.slot(FrameLayout.Field.FOG_COLOR_AND_START));
		assertEquals(24, FrameLayout.slot(FrameLayout.Field.FOG_DISTANCES));
		assertEquals(28, FrameLayout.slot(FrameLayout.Field.SKY_COLOR_AND_STAR_BRIGHTNESS));
		assertEquals(32, FrameLayout.slot(FrameLayout.Field.CLOUD_OFFSET_AND_GAME_TIME));

		// 最后一个 vec4 的 4 个分量正好填满那 36 个槽。
		assertEquals(36, FrameLayout.slot(FrameLayout.Field.CLOUD_OFFSET_AND_GAME_TIME) + 4);
	}

	@Test
	void askingForASlotOutsideTheEnvironmentBlockIsLoud() {
		// 把 mat4 的偏移当成槽位用是一类安静的错，所以这里必须抛而不是给个数。
		assertThrows(IllegalArgumentException.class, () -> FrameLayout.slot(FrameLayout.Field.PROJECTION));
		assertThrows(IllegalArgumentException.class, () -> FrameLayout.slot(FrameLayout.Field.INVERSE_HAND_PROJECTION));
	}
}
