package com.caldera.shaders.graph;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PackGraph 是光影包清单（caldera.json）的内存模型，全部是纯函数：
 * 解析、选项覆盖、调度排序、显存预算。不需要 Minecraft、不需要 GPU，也不需要窗口。
 *
 * 正常路径用内置包真实的 caldera.json（src/test/resources/caldera-realistic.json）当夹具，
 * 错误路径用最小清单逐项破坏。
 */
class PackGraphTest {

	private static final String REAL_MANIFEST = readFixture();

	private static String readFixture() {
		try (InputStream stream = PackGraphTest.class.getResourceAsStream("/caldera-realistic.json")) {
			assertNotNull(stream, "测试夹具 /caldera-realistic.json 必须存在");
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException failure) {
			throw new AssertionError(failure);
		}
	}

	/** 只有一条 pass 的最小合法清单；writes 必须由 pass 产出，present 必须有产出者。 */
	private static String minimal(String resources, String passes, String extraRoot) {
		return """
				{
				  "version": 1,
				  "name": "test",
				  "resources": {%s},
				  "passes": [%s],
				  "present": "display"%s
				}
				""".formatted(resources, passes, extraRoot);
	}

	private static String onePassToDisplay() {
		return """
				{
				  "name": "only",
				  "fragment": "shaders/only.fsh",
				  "reads": {},
				  "writes": ["display"]
				}
				""";
	}

	private static String displayResource() {
		return "\"display\": {\"format\": \"RGBA8_UNORM\"}";
	}

	// ---------------------------------------------------------------- 真实清单

	@Test
	void parsesTheRealBuiltinManifest() {
		PackGraph graph = PackGraph.parse(REAL_MANIFEST);

		assertEquals("Caldera Realistic", graph.name());
		assertEquals("display", graph.present());
		assertTrue(graph.shadows(), "内置包声明了阴影");
		assertEquals(List.of("surface.data"), graph.sceneTargets());
		assertTrue(graph.textures().containsKey("vanilla-clouds"), "必须解析出纹理表");
		assertTrue(graph.materials().containsKey("minecraft:redstone_lamp[lit=true]"), "带方块状态的材质选择器必须保留");
		assertTrue(graph.resources().containsKey("exposure.adapted"));
		assertTrue(graph.resources().get("exposure.adapted").history(), "曝光适应资源必须标记为历史");
		assertFalse(graph.resources().get("display").history(), "present 目标不需要历史");
		assertFalse(graph.passes().isEmpty());
	}

	@Test
	void shadowQualityAndDistanceComeFromTheManifestOptions() {
		PackGraph graph = PackGraph.parse(REAL_MANIFEST);

		assertEquals(2, graph.shadowQuality());
		assertEquals(128, graph.shadowDistance());
	}

	@Test
	void shadowQualityFallsBackToTwoWhenShadowsAreOnButTheOptionIsAbsent() {
		PackGraph graph = PackGraph.parse(minimal(displayResource(), onePassToDisplay(), ", \"shadows\": true"));

		assertEquals(2, graph.shadowQuality(), "没有 SHADOW_QUALITY 选项时回退到 2");
		assertEquals(128, graph.shadowDistance(), "没有 SHADOW_DISTANCE 选项时回退到 128");
	}

	@Test
	void shadowQualityIsZeroWhenShadowsAreOff() {
		PackGraph graph = PackGraph.parse(minimal(displayResource(), onePassToDisplay(), ", \"shadows\": false"));

		assertEquals(0, graph.shadowQuality(), "关掉阴影后质量必须是 0，与选项值无关");
	}

	@Test
	void shadowQualityIsZeroWhenTheManifestDoesNotMentionShadows() {
		PackGraph graph = PackGraph.parse(minimal(displayResource(), onePassToDisplay(), ""));

		assertFalse(graph.shadows());
		assertEquals(0, graph.shadowQuality());
	}

	@Test
	void realManifestOptionsResolveToTheirDeclaredDefaults() {
		PackGraph graph = PackGraph.parse(REAL_MANIFEST);

		assertEquals(1.0, graph.options().get("WATER_ENABLED"));
		assertEquals(1.05, graph.options().get("TM_CONTRAST"));
		assertEquals(0.12, graph.options().get("BLOOM_STRENGTH"));
		assertEquals(-64.0, graph.optionDefinitions().get("CLOUD_HEIGHT_OFFSET").values().get(0));
		assertEquals(0.0, graph.options().get("CLOUD_HEIGHT_OFFSET"), "声明 default=0，当前值就应是 0");
		assertEquals(graph.optionDefinitions().size(), graph.options().size(), "每个已声明选项都应有一个当前值");
	}

	// ---------------------------------------------------------------- 选项覆盖

	@Test
	void withOptionsAppliesAValueFromTheDeclaredChoices() {
		PackGraph graph = PackGraph.parse(REAL_MANIFEST);

		PackGraph overridden = graph.withOptions(Map.of("SHADOW_QUALITY", 4.0));

		assertEquals(4, overridden.shadowQuality());
		assertEquals(2, graph.shadowQuality(), "原对象必须保持不变");
	}

	@Test
	void withOptionsRejectsAnUnknownOption() {
		PackGraph graph = PackGraph.parse(REAL_MANIFEST);

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> graph.withOptions(Map.of("NOT_A_REAL_OPTION", 1.0)));

		assertEquals("Unsupported option value NOT_A_REAL_OPTION=1.0", failure.getMessage());
	}

	@Test
	void withOptionsRejectsAValueOutsideTheDeclaredChoices() {
		PackGraph graph = PackGraph.parse(REAL_MANIFEST);

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> graph.withOptions(Map.of("SHADOW_QUALITY", 5.0)));

		assertEquals("Unsupported option value SHADOW_QUALITY=5.0", failure.getMessage());
	}

	@Test
	void withOptionsAcceptsSeveralOverridesAtOnce() {
		PackGraph graph = PackGraph.parse(REAL_MANIFEST);

		PackGraph overridden = graph.withOptions(Map.of("SHADOW_QUALITY", 0.0, "WATER_ENABLED", 0.0));

		assertEquals(0, overridden.shadowQuality());
		assertEquals(0.0, overridden.options().get("WATER_ENABLED"));
		assertEquals(1.0, graph.options().get("WATER_ENABLED"), "原对象不受影响");
	}

	// ---------------------------------------------------------------- 调度

	@Test
	void scheduleEndsWithThePassThatWritesThePresentedResource() {
		PackGraph graph = PackGraph.parse(REAL_MANIFEST);

		List<PackGraph.Pass> order = graph.schedule();

		assertFalse(order.isEmpty());
		assertEquals("final", order.getLast().name(), "present 的产出者必须排在最后");
	}

	@Test
	void scheduleProducesEachPassAtMostOnce() {
		PackGraph graph = PackGraph.parse(REAL_MANIFEST);

		List<String> names = new ArrayList<>();
		graph.schedule().forEach(pass -> names.add(pass.name()));

		assertEquals(names.size(), names.stream().distinct().count(), "重复调度会让 pass 执行两次: " + names);
		assertTrue(names.contains("atmosphere"));
		assertTrue(names.contains("bloom-seed"));
	}

	@Test
	void schedulePlacesEveryProducerBeforeItsConsumer() {
		PackGraph graph = PackGraph.parse(REAL_MANIFEST);
		List<PackGraph.Pass> order = graph.schedule();

		Map<String, Integer> position = new HashMap<>();
		for (int index = 0; index < order.size(); index++) {
			position.put(order.get(index).name(), index);
		}

		for (int index = 0; index < order.size(); index++) {
			for (String input : order.get(index).reads().values()) {
				String producer = PackGraph.current(input);
				if (producer.startsWith("$") && !producer.startsWith("$buffer/")) {
					continue;
				}
				if (graph.sceneTargets().contains(producer)) {
					continue;
				}
				Integer producerIndex = position.get(producer);
				if (producerIndex == null) {
					continue;
				}
				assertTrue(producerIndex < index,
						order.get(index).name() + " 在读取 " + input + "，但它的产出者在第 " + producerIndex + " 位，不是更早");
			}
		}
	}

	@Test
	void scheduleRejectsAnUndeclaredOutput() {
		String manifest = minimal(displayResource(),
				"{\"name\": \"only\", \"fragment\": \"shaders/only.fsh\", \"reads\": {}, \"writes\": [\"display\", \"ghost\"]}",
				"");

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> PackGraph.parse(manifest).schedule());

		assertEquals("Undeclared output ghost", failure.getMessage());
	}

	@Test
	void scheduleRejectsTwoWritersForTheSameResource() {
		String passes = """
				{"name": "one", "fragment": "shaders/one.fsh", "reads": {}, "writes": ["display"]},
				{"name": "two", "fragment": "shaders/two.fsh", "reads": {}, "writes": ["display"]}
				""";
		String manifest = minimal(displayResource(), passes, "");

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> PackGraph.parse(manifest).schedule());

		assertEquals("Multiple writers for display; use a new resource name", failure.getMessage());
	}

	@Test
	void scheduleRejectsAMissingProducerForThePresentedResource() {
		String manifest = minimal("\"display\": {\"format\": \"RGBA8_UNORM\"}, \"side\": {\"format\": \"RGBA8_UNORM\"}",
				"{\"name\": \"only\", \"fragment\": \"shaders/only.fsh\", \"reads\": {}, \"writes\": [\"side\"]}",
				"");

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> PackGraph.parse(manifest).schedule());

		assertEquals("No producer for presented resource display", failure.getMessage());
	}

	@Test
	void scheduleRejectsAVolumeOutputFromANonComputePass() {
		String manifest = minimal(
				"\"display\": {\"format\": \"RGBA8_UNORM\"}, \"vol\": {\"format\": \"RGBA8_UNORM\", \"size\": [4, 4, 8]}",
				"""
				{"name": "flat", "fragment": "shaders/flat.fsh", "reads": {}, "writes": ["display"]},
				{"name": "volume", "fragment": "shaders/volume.fsh", "reads": {}, "writes": ["vol"]}
				""",
				"");

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> PackGraph.parse(manifest).schedule());

		assertEquals("Volume outputs require compute: vol", failure.getMessage());
	}

	@Test
	void scheduleRejectsAPassThatReadsAndWritesTheSameResource() {
		String manifest = minimal(displayResource(),
				"{\"name\": \"only\", \"fragment\": \"shaders/only.fsh\", \"reads\": {\"Self\": \"display\"}, \"writes\": [\"display\"]}",
				"");

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> PackGraph.parse(manifest).schedule());

		assertEquals("Resource feedback in only", failure.getMessage());
	}

	@Test
	void scheduleRejectsAPreviousFrameReadOfAResourceWithoutHistory() {
		String manifest = minimal("\"display\": {\"format\": \"RGBA8_UNORM\"}, \"src\": {\"format\": \"RGBA8_UNORM\"}",
				"""
				{"name": "a", "fragment": "shaders/a.fsh", "reads": {"Src": "src@previous"}, "writes": ["display"]},
				{"name": "b", "fragment": "shaders/b.fsh", "reads": {}, "writes": ["src"]}
				""",
				"");

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> PackGraph.parse(manifest).schedule());

		assertEquals("Previous-frame input is not history: src@previous", failure.getMessage());
	}

	@Test
	void scheduleAcceptsAPreviousFrameReadOfAHistoryResource() {
		String manifest = minimal(
				"\"display\": {\"format\": \"RGBA8_UNORM\"}, \"src\": {\"format\": \"RGBA8_UNORM\", \"history\": true}",
				"""
				{"name": "a", "fragment": "shaders/a.fsh", "reads": {"Src": "src@previous"}, "writes": ["display"]},
				{"name": "b", "fragment": "shaders/b.fsh", "reads": {}, "writes": ["src"]}
				""",
				"");

		List<String> names = new ArrayList<>();
		PackGraph.parse(manifest).schedule().forEach(pass -> names.add(pass.name()));

		// 上一帧数据的产出者不是本帧的前置依赖，所以它被补排在消费者之后。
		// 场景 pass 必须排在 a 之前：a 在本帧消费的是 b 上一帧写下的内容。
		assertTrue(names.contains("a"), names.toString());
		assertTrue(names.contains("b"), "历史读取必须把上一帧的产出者补进调度: " + names);
		assertTrue(names.indexOf("a") < names.indexOf("b"),
				"上一帧产出者应在消费者之后补入: " + names);
	}

	@Test
	void scheduleRejectsADependencyCycle() {
		String manifest = minimal(
				"\"display\": {\"format\": \"RGBA8_UNORM\"}, \"a\": {\"format\": \"RGBA8_UNORM\"}, \"b\": {\"format\": \"RGBA8_UNORM\"}",
				"""
				{"name": "pa", "fragment": "shaders/pa.fsh", "reads": {"B": "b"}, "writes": ["a"]},
				{"name": "pb", "fragment": "shaders/pb.fsh", "reads": {"A": "a"}, "writes": ["b"]},
				{"name": "pc", "fragment": "shaders/pc.fsh", "reads": {"A": "a"}, "writes": ["display"]}
				""",
				"");

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> PackGraph.parse(manifest).schedule());

		assertTrue(failure.getMessage().startsWith("Pass dependency cycle at "), failure.getMessage());
	}

	// ---------------------------------------------------------------- 解析校验

	@Test
	void parseRejectsAVersionItDoesNotUnderstand() {
		String manifest = """
				{"version": 2, "name": "test", "resources": {%s}, "passes": [%s], "present": "display"}
				""".formatted(displayResource(), onePassToDisplay());

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> PackGraph.parse(manifest));

		assertEquals("Unsupported native pack version", failure.getMessage());
	}

	@Test
	void parseRejectsAnUnknownRootField() {
		String manifest = """
				{"version": 1, "name": "test", "bogus": 1, "resources": {%s}, "passes": [%s], "present": "display"}
				""".formatted(displayResource(), onePassToDisplay());

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> PackGraph.parse(manifest));

		assertEquals("Unknown native pack field: bogus", failure.getMessage());
	}

	@Test
	void parseRejectsAMissingRequiredRootField() {
		String manifest = """
				{"version": 1, "name": "test", "resources": {%s}, "passes": [%s]}
				""".formatted(displayResource(), onePassToDisplay());

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> PackGraph.parse(manifest));

		assertEquals("Missing native pack field: present", failure.getMessage());
	}

	@Test
	void parseRejectsAVersionThatIsNotAWholeNumber() {
		String manifest = """
				{"version": 1.5, "name": "test", "resources": {%s}, "passes": [%s], "present": "display"}
				""".formatted(displayResource(), onePassToDisplay());

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> PackGraph.parse(manifest));

		assertEquals("Expected 32-bit integer for version", failure.getMessage());
	}

	@Test
	void parseRejectsAnInvalidResourceName() {
		String manifest = minimal("\"Display\": {\"format\": \"RGBA8_UNORM\"}", onePassToDisplay(), "");

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> PackGraph.parse(manifest));

		assertEquals("Invalid resource/pass name Display", failure.getMessage());
	}

	@Test
	void parseRejectsAnUnknownPassField() {
		String manifest = minimal(displayResource(),
				"{\"name\": \"only\", \"fragment\": \"shaders/only.fsh\", \"reads\": {}, \"writes\": [\"display\"], \"oops\": 1}",
				"");

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> PackGraph.parse(manifest));

		assertEquals("Unknown native pack field: oops", failure.getMessage());
	}

	@Test
	void parseRejectsADuplicatePassName() {
		String passes = """
				{"name": "only", "fragment": "shaders/only.fsh", "reads": {}, "writes": ["display"]},
				{"name": "only", "fragment": "shaders/only.fsh", "reads": {}, "writes": []}
				""";

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> PackGraph.parse(minimal(displayResource(), passes, "")));

		assertEquals("Duplicate pass only", failure.getMessage());
	}

	@Test
	void parseRejectsAnInvalidMaterialSelector() {
		String manifest = minimal(displayResource(), onePassToDisplay(), ", \"materials\": {\"NotNamespaced\": 5}");

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> PackGraph.parse(manifest));

		assertEquals("Invalid material selector or ID: NotNamespaced", failure.getMessage());
	}

	@Test
	void parseRejectsAMaterialIdOutsideTheAllowedRange() {
		String manifest = minimal(displayResource(), onePassToDisplay(), ", \"materials\": {\"minecraft:stone\": 0}");

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> PackGraph.parse(manifest));

		assertEquals("Invalid material selector or ID: minecraft:stone", failure.getMessage());
	}

	@Test
	void parseRejectsAnInvalidResourceSize() {
		String manifest = minimal("\"display\": {\"format\": \"RGBA8_UNORM\", \"size\": [0, 4, 1]}",
				onePassToDisplay(), "");

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> PackGraph.parse(manifest));

		assertEquals("Invalid resource size", failure.getMessage());
	}

	@Test
	void parseRejectsAnUnknownOptionField() {
		String manifest = minimal(displayResource(), onePassToDisplay(),
				", \"options\": {\"X\": {\"default\": 1, \"values\": [1], \"extra\": true}}");

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> PackGraph.parse(manifest));

		assertEquals("Unknown native pack field: extra", failure.getMessage());
	}

	// ---------------------------------------------------------------- 名字与预算

	@Test
	void currentAndPreviousHandleThePreviousFrameSuffix() {
		assertTrue(PackGraph.previous("exposure.adapted@previous"));
		assertFalse(PackGraph.previous("exposure.adapted"));
		assertEquals("exposure.adapted", PackGraph.current("exposure.adapted@previous"));
		assertEquals("exposure.adapted", PackGraph.current("exposure.adapted"));
		assertEquals("", PackGraph.current("@previous"));
	}

	@Test
	void bufferBytesIsZeroWhenTheManifestDeclaresNoBuffers() {
		assertEquals(0L, PackGraph.parse(REAL_MANIFEST).bufferBytes());
	}

	/**
	 * 存储缓冲区只允许 compute pass 绑定，且 $buffer/x 必须真的由某个 pass 写出来，
	 * 所以这里是一个写、一个读的两个 compute pass。
	 */
	private static String computePassesSharingABuffer() {
		return """
				{"name": "cs-write", "compute": "shaders/cs-write.comp", "localSize": [1, 1, 1], "dispatch": [1, 1, 1],
				 "reads": {}, "writes": ["scratch"],
				 "buffers": {"Data": {"resource": "data", "access": "write"}}},
				{"name": "cs-read", "compute": "shaders/cs-read.comp", "localSize": [1, 1, 1], "dispatch": [1, 1, 1],
				 "reads": {"Data": "$buffer/data"}, "writes": ["display"]}
				""";
	}

	private static String displayAndScratch() {
		return "\"display\": {\"format\": \"RGBA8_UNORM\"}, \"scratch\": {\"format\": \"RGBA8_UNORM\"}";
	}

	@Test
	void bufferBytesCountsHistoryBuffersTwice() {
		PackGraph graph = PackGraph.parse(minimal(displayAndScratch(), computePassesSharingABuffer(),
				", \"buffers\": {\"data\": {\"bytes\": 1024, \"history\": true}}"));

		assertEquals(2048L, graph.bufferBytes(), "历史 buffer 必须按两倍占用计算");
	}

	@Test
	void bufferBytesDoesNotDoubleANonHistoryBuffer() {
		PackGraph graph = PackGraph.parse(minimal(displayAndScratch(), computePassesSharingABuffer(),
				", \"buffers\": {\"data\": {\"bytes\": 1024}}"));

		assertEquals(1024L, graph.bufferBytes(), "非历史 buffer 只计一倍");
	}

	@Test
	void scheduleIncludesTheComputePassThatWritesABufferReadByAnotherPass() {
		PackGraph graph = PackGraph.parse(minimal(displayAndScratch(), computePassesSharingABuffer(),
				", \"buffers\": {\"data\": {\"bytes\": 1024}}"));

		List<String> names = new ArrayList<>();
		graph.schedule().forEach(pass -> names.add(pass.name()));

		assertTrue(names.contains("cs-write"), "缓冲区写入者必须被排进来: " + names);
		assertTrue(names.indexOf("cs-write") < names.indexOf("cs-read"), "$buffer 生产者必须排在读取者之前: " + names);
	}

	@Test
	void parseRejectsAStorageBufferBoundByAFragmentPass() {
		String manifest = minimal(displayResource(),
				"""
				{"name": "only", "fragment": "shaders/only.fsh", "reads": {}, "writes": ["display"],
				 "buffers": {"Data": {"resource": "data", "access": "read"}}}
				""",
				", \"buffers\": {\"data\": {\"bytes\": 1024}}");

		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> PackGraph.parse(manifest));

		assertEquals("Storage buffers currently require compute", failure.getMessage());
	}

	@Test
	void allocationBytesIsPositiveAndFitsTheDeclaredBudget() {
		PackGraph graph = PackGraph.parse(REAL_MANIFEST);

		long bytes = graph.allocationBytes(1920, 1080);

		assertTrue(bytes > 0L, "1920x1080 必须占用正数显存");
		assertTrue(bytes < 1024L * 1024L * 1024L, "内置包声明 1024 MiB 预算，必须放得下，实际 " + bytes / 1048576L + " MiB");
	}

	@Test
	void allocationBytesGrowsWithResolution() {
		PackGraph graph = PackGraph.parse(REAL_MANIFEST);

		long small = graph.allocationBytes(640, 360);
		long large = graph.allocationBytes(2560, 1440);

		assertTrue(large > small, "分辨率翻倍必须占用更多显存: " + small + " -> " + large);
	}

	@Test
	void resourceBytesScaleWithResolution() {
		PackGraph graph = PackGraph.parse(REAL_MANIFEST);
		PackGraph.Resource scaled = graph.resources().get("scene.hdr");

		assertEquals(0.0, scaled.scale() - 1.0, 1e-9);
		long atOneK = scaled.bytes(1920, 1080);
		long atTwoK = scaled.bytes(3840, 2160);

		assertEquals(atOneK * 4, atTwoK, "RGBA16_FLOAT 全分辨率资源的字节数必须与像素数成正比");
	}

	@Test
	void resourceBytesHonourAFixedSize() {
		PackGraph graph = PackGraph.parse(REAL_MANIFEST);
		PackGraph.Resource meter = graph.resources().get("exposure.meter");

		assertEquals(meter.bytes(1920, 1080), meter.bytes(3840, 2160), "固定尺寸资源不得随分辨率变化");
	}

	@Test
	void sameShapeComparesDimensionsAndNotFormats() {
		PackGraph graph = PackGraph.parse(REAL_MANIFEST);

		PackGraph.Resource a = graph.resources().get("scene.hdr");
		PackGraph.Resource b = graph.resources().get("bloom.seed");

		assertFalse(a.sameShape(b), "scene.hdr 是全分辨率，bloom.seed 是半分辨率");
		assertTrue(a.sameShape(a));
	}

	@Test
	void mipLevelsFollowTheDeclaredFlag() {
		PackGraph graph = PackGraph.parse(minimal(
				"\"display\": {\"format\": \"RGBA8_UNORM\"},"
						+ " \"plain\": {\"format\": \"RGBA8_UNORM\"},"
						+ " \"mipped\": {\"format\": \"RGBA8_UNORM\", \"mipmaps\": true}",
				onePassToDisplay(),
				""));

		assertEquals(1, graph.resources().get("plain").mipLevels(1024, 1024), "未声明 mipmaps 的资源只有一层");
		assertTrue(graph.resources().get("mipped").mipLevels(1024, 1024) > 1, "声明 mipmaps 后必须生成多级");
	}
}
