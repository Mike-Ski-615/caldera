package com.caldera.shaders.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PackFiles 是纯 IO + 纯字符串处理，不需要 Minecraft 运行时、不需要 GPU，也不需要窗口。
 * 这里覆盖三件事：路径安全规则、容器目录剥离、着色器 #include 展开。
 */
class PackFilesTest {

	@Test
	void safeAcceptsOrdinaryPackPaths() {
		assertTrue(PackFiles.safe("caldera.json"));
		assertTrue(PackFiles.safe("shaders/terrain.fsh"));
		assertTrue(PackFiles.safe("shaders/include/lighting.glsl"));
		assertTrue(PackFiles.safe("a.b-c_d/e.f"));
	}

	@Test
	void safeRejectsAbsoluteTraversalAndSeparatorEscapes() {
		assertFalse(PackFiles.safe(null), "null 必须拒绝");
		assertFalse(PackFiles.safe(""), "空串必须拒绝");
		assertFalse(PackFiles.safe("   "), "纯空白必须拒绝");
		assertFalse(PackFiles.safe("/caldera.json"), "绝对路径必须拒绝");
		assertFalse(PackFiles.safe("shaders\\terrain.fsh"), "反斜杠必须拒绝");
		assertFalse(PackFiles.safe("C:/caldera.json"), "盘符必须拒绝");
		assertFalse(PackFiles.safe("../outside.glsl"), "上跳必须拒绝");
		assertFalse(PackFiles.safe("shaders/../../outside.glsl"), "中间上跳必须拒绝");
		assertFalse(PackFiles.safe("./caldera.json"), "单点段必须拒绝");
		assertFalse(PackFiles.safe("shaders//terrain.fsh"), "空段必须拒绝");
		assertFalse(PackFiles.safe("shaders/"), "结尾斜杠会产生空段，必须拒绝");
	}

	@Test
	void readStripsTheContainerDirectoryAboveTheManifest(@TempDir Path dir) throws IOException {
		Path pack = dir.resolve("Caldera-Realistic");
		Files.createDirectories(pack.resolve("shaders/include"));
		Files.writeString(pack.resolve("caldera.json"), "{\"name\":\"x\"}", StandardCharsets.UTF_8);
		Files.writeString(pack.resolve("shaders/terrain.fsh"), "#version 460 core\n", StandardCharsets.UTF_8);
		Files.writeString(pack.resolve("shaders/include/lighting.glsl"), "// l\n", StandardCharsets.UTF_8);

		PackFiles files = PackFiles.read(dir);

		assertEquals("{\"name\":\"x\"}", files.text("caldera.json"), "清单必须落在包根");
		assertEquals("#version 460 core\n", files.text("shaders/terrain.fsh"), "容器目录必须被剥掉");
		assertEquals("// l\n", files.text("shaders/include/lighting.glsl"));
	}

	@Test
	void readAcceptsAPackWhoseManifestIsAlreadyAtTheRoot(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("caldera.json"), "{}", StandardCharsets.UTF_8);
		Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(dir.resolve("shaders/a.fsh"), "x", StandardCharsets.UTF_8);

		PackFiles files = PackFiles.read(dir);

		assertEquals("{}", files.text("caldera.json"));
		assertEquals("x", files.text("shaders/a.fsh"));
	}

	/**
	 * 「一层容器目录」就是一层：{@code nested/caldera.json} 与 {@code Container/caldera.json} 等价，
	 * 两者都算清单。所以"根上一份 + 一层一份"是**两个包根**，两边都必须拒绝——包根不能靠猜。
	 * <p>
	 * 「恰好一份」这条要求 {@link PackFiles#isNative} 与 {@code read} 都成立，这也正是本次要保住的东西：
	 * 同一个包，"算不算原生包"与"能不能读进来"不许是两个答案。
	 */
	@Test
	void aPackWithTwoManifestsAtRootAndOneLevelIsRejectedOnBothPaths(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("caldera.json"), "{}", StandardCharsets.UTF_8);
		Files.createDirectories(dir.resolve("nested"));
		Files.writeString(dir.resolve("nested/caldera.json"), "{\"deep\":true}", StandardCharsets.UTF_8);

		IOException failure = assertThrows(IOException.class, () -> PackFiles.read(dir));

		assertEquals("Native pack needs exactly one caldera.json", failure.getMessage());
		assertFalse(PackFiles.isNative(dir), "两个包根同样不算原生包");
	}

	/**
	 * 深**两**层的那一份不算清单，是包里的普通文件；此时根上那份就是唯一包根。
	 */
	@Test
	void readTreatsAManifestTwoLevelsDeepAsAnOrdinaryFile(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("caldera.json"), "{}", StandardCharsets.UTF_8);
		Files.createDirectories(dir.resolve("Container/nested"));
		Files.writeString(dir.resolve("Container/nested/caldera.json"), "{\"deep\":true}", StandardCharsets.UTF_8);

		PackFiles files = PackFiles.read(dir);

		assertEquals("{}", files.text("caldera.json"), "取的是根上那一份");
		assertEquals("{\"deep\":true}", files.text("Container/nested/caldera.json"), "深两层的那份是普通文件");
		assertTrue(PackFiles.isNative(dir), "读得进来与算原生包必须是同一个答案");
	}

	@Test
	void readRejectsAPackWithoutExactlyOneManifest(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("caldera.json"), "{}", StandardCharsets.UTF_8);
		Files.createDirectories(dir.resolve("Container"));
		Files.writeString(dir.resolve("Container/caldera.json"), "{}", StandardCharsets.UTF_8);

		IOException failure = assertThrows(IOException.class, () -> PackFiles.read(dir));

		assertEquals("Native pack needs exactly one caldera.json", failure.getMessage());
	}

	@Test
	void readRejectsASingleFileRatherThanADirectory(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("not-a-pack.txt");
		Files.writeString(file, "x", StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> PackFiles.read(file));
	}

	@Test
	void textRejectsAPathThatIsNotPresent(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("caldera.json"), "{}", StandardCharsets.UTF_8);
		PackFiles files = PackFiles.read(dir);

		assertThrows(IOException.class, () -> files.text("shaders/missing.fsh"));
	}

	@Test
	void textRejectsAnUnsafePathEvenWhenTheEntryExists(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("caldera.json"), "{}", StandardCharsets.UTF_8);
		PackFiles files = PackFiles.read(dir);

		assertThrows(IOException.class, () -> files.text("../caldera.json"));
		assertThrows(IOException.class, () -> files.text("/caldera.json"));
	}

	@Test
	void shaderEmitsAnEnabledFlagAndTheValueForEveryDefine(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("caldera.json"), "{}", StandardCharsets.UTF_8);
		Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(dir.resolve("shaders/a.fsh"), "#version 460 core\nvoid main(){}\n", StandardCharsets.UTF_8);
		PackFiles files = PackFiles.read(dir);

		String expanded = files.shader("shaders/a.fsh", Map.of("STRENGTH", 0.5));

		assertTrue(expanded.startsWith("#version 460 core\n"), "首行必须原样保留: " + expanded);
		assertTrue(expanded.contains("#define _CALDERA_ENABLED_STRENGTH 1\n"), "非零值必须置启用位: " + expanded);
		assertTrue(expanded.contains("#define STRENGTH 0.5\n"), "浮点值必须原样写出: " + expanded);
		assertTrue(expanded.contains("#line 2 0\n"), "必须插入 #line 以保持报错行号: " + expanded);
		// expand() 用 split("\n", -1) 逐行补换行，尾空行也会补一次，
		// 所以展开后的源码末尾必然比原文多一个换行。对 GLSL 无害，但这是既定行为。
		assertTrue(expanded.endsWith("void main(){}\n\n"), "正文必须保留在头部之后（末尾多一个换行）: " + expanded);
	}

	@Test
	void shaderTreatsZeroAsDisabledAndWritesAnIntegerLiteral(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("caldera.json"), "{}", StandardCharsets.UTF_8);
		Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(dir.resolve("shaders/a.fsh"), "#version 460 core\nx\n", StandardCharsets.UTF_8);
		PackFiles files = PackFiles.read(dir);

		String expanded = files.shader("shaders/a.fsh", Map.of("V", 0.0));

		assertTrue(expanded.contains("#define _CALDERA_ENABLED_V 0\n"), expanded);
		assertTrue(expanded.contains("#define V 0\n"), "整数值不应带小数点: " + expanded);
	}

	@Test
	void shaderWritesNegativeAndFractionalValuesLiterally(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("caldera.json"), "{}", StandardCharsets.UTF_8);
		Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(dir.resolve("shaders/a.fsh"), "#version 460 core\nx\n", StandardCharsets.UTF_8);
		PackFiles files = PackFiles.read(dir);

		String negative = files.shader("shaders/a.fsh", Map.of("OFFSET", -64.0));
		assertTrue(negative.contains("#define _CALDERA_ENABLED_OFFSET 0\n"), negative);
		assertTrue(negative.contains("#define OFFSET -64\n"), negative);

		String fractional = files.shader("shaders/a.fsh", Map.of("RATIO", 1.5));
		assertTrue(fractional.contains("#define RATIO 1.5\n"), fractional);
	}

	@Test
	void shaderRejectsADefineNameThatIsNotScreamingSnakeCase(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("caldera.json"), "{}", StandardCharsets.UTF_8);
		Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(dir.resolve("shaders/a.fsh"), "#version 460 core\nx\n", StandardCharsets.UTF_8);
		PackFiles files = PackFiles.read(dir);

		assertThrows(IllegalArgumentException.class, () -> files.shader("shaders/a.fsh", Map.of("lowercase", 1.0)));
		assertThrows(IllegalArgumentException.class, () -> files.shader("shaders/a.fsh", Map.of("HAS SPACE", 1.0)));
		assertThrows(IllegalArgumentException.class, () -> files.shader("shaders/a.fsh", Map.of("NAN_VALUE", Double.NaN)));
		assertThrows(IllegalArgumentException.class, () -> files.shader("shaders/a.fsh", Map.of("INF_VALUE", Double.POSITIVE_INFINITY)));
	}

	@Test
	void shaderRejectsASourceWithoutAVersionDirective(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("caldera.json"), "{}", StandardCharsets.UTF_8);
		Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(dir.resolve("shaders/a.fsh"), "void main(){}\n", StandardCharsets.UTF_8);
		PackFiles files = PackFiles.read(dir);

		IOException failure = assertThrows(IOException.class, () -> files.shader("shaders/a.fsh", Map.of()));

		assertTrue(failure.getMessage().contains("shader must start with #version"), failure.getMessage());
	}

	@Test
	void shaderInlinesIncludesWithLineDirectives(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("caldera.json"), "{}", StandardCharsets.UTF_8);
		Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(dir.resolve("shaders/main.fsh"),
				"#version 460 core\n#include \"shaders/lib.glsl\"\nvoid main(){}\n", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("shaders/lib.glsl"), "float f(){return 1.0;}\n", StandardCharsets.UTF_8);
		PackFiles files = PackFiles.read(dir);

		String expanded = files.shader("shaders/main.fsh", Map.of());

		assertTrue(expanded.contains("float f(){return 1.0;}\n"), "被包含文件必须内联: " + expanded);
		assertTrue(expanded.contains("#line 1 1\n"), "内含文件前必须切到它的 id: " + expanded);
		assertTrue(expanded.contains("#line 3 0\n"), "内含结束后必须切回主文件的下一行: " + expanded);
		assertFalse(expanded.contains("#include"), "#include 必须全部被消掉: " + expanded);
	}

	@Test
	void shaderRejectsAnIncludeThatIsNotQuoted(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("caldera.json"), "{}", StandardCharsets.UTF_8);
		Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(dir.resolve("shaders/main.fsh"),
				"#version 460 core\n#include shaders/lib.glsl\n", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("shaders/lib.glsl"), "x\n", StandardCharsets.UTF_8);
		PackFiles files = PackFiles.read(dir);

		IOException failure = assertThrows(IOException.class, () -> files.shader("shaders/main.fsh", Map.of()));

		assertTrue(failure.getMessage().contains("expected a quoted pack-root include"), failure.getMessage());
	}

	@Test
	void shaderRejectsACyclicIncludeChain(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("caldera.json"), "{}", StandardCharsets.UTF_8);
		Files.createDirectories(dir.resolve("shaders"));
		Files.writeString(dir.resolve("shaders/a.glsl"), "#include \"shaders/b.glsl\"\n", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("shaders/b.glsl"), "#include \"shaders/a.glsl\"\n", StandardCharsets.UTF_8);
		PackFiles files = PackFiles.read(dir);

		IOException failure = assertThrows(IOException.class, () -> files.shader("shaders/a.glsl", Map.of()));

		assertTrue(failure.getMessage().startsWith("Include cycle/depth: "), failure.getMessage());
	}

	@Test
	void shaderRejectsAnIncludeChainDeeperThanTheStackLimit(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("caldera.json"), "{}", StandardCharsets.UTF_8);
		Files.createDirectories(dir.resolve("shaders"));
		for (int i = 0; i < 40; i++) {
			String body = i == 39 ? "float leaf(){return 0.0;}\n" : "#include \"shaders/l" + (i + 1) + ".glsl\"\n";
			Files.writeString(dir.resolve("shaders/l" + i + ".glsl"), body, StandardCharsets.UTF_8);
		}
		PackFiles files = PackFiles.read(dir);

		IOException failure = assertThrows(IOException.class, () -> files.shader("shaders/l0.glsl", Map.of()));

		assertTrue(failure.getMessage().startsWith("Include cycle/depth: "), failure.getMessage());
	}

	@Test
	void shaderKeepsTheDepthBudgetAtThirtyTwoLevels(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("caldera.json"), "{}", StandardCharsets.UTF_8);
		Files.createDirectories(dir.resolve("shaders"));
		for (int i = 0; i < 32; i++) {
			String body = i == 31
					? "float leaf(){return 0.0;}\n"
					: "#include \"shaders/d" + (i + 1) + ".glsl\"\n";
			if (i == 0) {
				body = "#version 460 core\n" + body;
			}
			Files.writeString(dir.resolve("shaders/d" + i + ".glsl"), body, StandardCharsets.UTF_8);
		}
		PackFiles files = PackFiles.read(dir);

		String expanded = files.shader("shaders/d0.glsl", Map.of());

		assertTrue(expanded.contains("float leaf(){return 0.0;}"), "32 层必须通过: " + expanded);
	}

	@Test
	void binaryReturnsTheRawBytesOfAnEntry(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("caldera.json"), "{}", StandardCharsets.UTF_8);
		byte[] payload = {1, 2, 3, (byte) 255};
		Files.write(dir.resolve("blob.bin"), payload);
		PackFiles files = PackFiles.read(dir);

		assertEquals(4, files.binary("blob.bin").length);
		assertEquals(1, files.binary("blob.bin")[0]);
		assertEquals((byte) 255, files.binary("blob.bin")[3]);
	}
}
