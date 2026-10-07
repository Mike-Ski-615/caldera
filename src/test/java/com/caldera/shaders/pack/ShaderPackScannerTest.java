package com.caldera.shaders.pack;

import com.caldera.shaders.graph.NativePackRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ShaderPackScanner 与 NativePackRuntime 决定「一个光影包算不算数」。
 * scanDirectory(Path) 与 isNative(Path) 都直接收路径、不碰 FabricLoader，
 * 所以可以完全离线测试，不需要 Minecraft、GPU 或窗口。
 *
 * 受支持的门槛比 0.3.1 高：必须同时通过 isNative + PackFiles.read + PackGraph.parse，
 * 任何一步失败都会落到 "not-caldera-compatible"。
 */
class ShaderPackScannerTest {

	private static final String VALID_MANIFEST = readFixture();

	private static String readFixture() {
		try (InputStream stream = ShaderPackScannerTest.class.getResourceAsStream("/caldera-realistic.json")) {
			assertNotNull(stream, "测试夹具 /caldera-realistic.json 必须存在");
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException failure) {
			throw new AssertionError(failure);
		}
	}

	private static void writeValidPack(Path root) throws IOException {
		Files.createDirectories(root);
		Files.writeString(root.resolve("caldera.json"), VALID_MANIFEST, StandardCharsets.UTF_8);
	}

	private static void writeZip(Path zip, String entryName, String content) throws IOException {
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			out.putNextEntry(new ZipEntry(entryName));
			out.write(content.getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
	}

	// ------------------------------------------------------------ scanDirectory

	@Test
	void scanDirectoryAcceptsADirectoryWhoseManifestParses(@TempDir Path dir) throws IOException {
		writeValidPack(dir.resolve("Realistic"));

		ShaderPackScanner.ScanResult result = ShaderPackScanner.scanDirectory(dir);

		assertEquals(1, result.supportedPacks().size());
		assertTrue(result.unsupportedPacks().isEmpty());
		ShaderPackScanner.AvailableShaderPack pack = result.supportedPacks().getFirst();
		assertEquals("Realistic", pack.id());
		assertEquals("Realistic", pack.displayName());
		assertTrue(pack.directory());
	}

	@Test
	void scanDirectoryRejectsADirectoryWhoseManifestDoesNotParse(@TempDir Path dir) throws IOException {
		Path broken = dir.resolve("Broken");
		Files.createDirectories(broken);
		Files.writeString(broken.resolve("caldera.json"), "{\"version\": 2, \"name\": \"x\"}", StandardCharsets.UTF_8);

		ShaderPackScanner.ScanResult result = ShaderPackScanner.scanDirectory(dir);

		assertTrue(result.supportedPacks().isEmpty(), "清单解析失败不得算作受支持");
		assertEquals(1, result.unsupportedPacks().size());
		assertEquals("not-caldera-compatible", result.unsupportedPacks().getFirst().reason());
	}

	@Test
	void scanDirectoryRejectsADirectoryWithoutAManifest(@TempDir Path dir) throws IOException {
		Path empty = dir.resolve("Empty");
		Files.createDirectories(empty);
		Files.writeString(empty.resolve("readme.txt"), "hello", StandardCharsets.UTF_8);

		ShaderPackScanner.ScanResult result = ShaderPackScanner.scanDirectory(dir);

		assertTrue(result.supportedPacks().isEmpty());
		assertEquals("not-caldera-compatible", result.unsupportedPacks().getFirst().reason());
	}

	@Test
	void scanDirectoryRejectsALooseFile(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("notes.txt"), "hello", StandardCharsets.UTF_8);

		ShaderPackScanner.ScanResult result = ShaderPackScanner.scanDirectory(dir);

		assertTrue(result.supportedPacks().isEmpty());
		assertEquals("not-a-shader-pack", result.unsupportedPacks().getFirst().reason());
	}

	@Test
	void scanDirectoryAcceptsAZipAndStripsTheExtensionFromTheDisplayName(@TempDir Path dir) throws IOException {
		writeZip(dir.resolve("Nice.zip"), "caldera.json", VALID_MANIFEST);

		ShaderPackScanner.ScanResult result = ShaderPackScanner.scanDirectory(dir);

		assertEquals(1, result.supportedPacks().size());
		ShaderPackScanner.AvailableShaderPack pack = result.supportedPacks().getFirst();
		assertEquals("Nice.zip", pack.id(), "id 保留完整文件名");
		assertEquals("Nice", pack.displayName(), "显示名去掉 .zip");
		assertFalse(pack.directory());
	}

	@Test
	void scanDirectoryAcceptsAZipWithAContainerFolderInside(@TempDir Path dir) throws IOException {
		writeZip(dir.resolve("Wrapped.zip"), "Caldera-Realistic/caldera.json", VALID_MANIFEST);

		ShaderPackScanner.ScanResult result = ShaderPackScanner.scanDirectory(dir);

		assertEquals(1, result.supportedPacks().size(), "带一层容器目录的 zip 必须被接受");
	}

	@Test
	void scanDirectoryRejectsAZipWithoutAManifest(@TempDir Path dir) throws IOException {
		writeZip(dir.resolve("Plain.zip"), "shaders/a.fsh", "#version 460 core\n");

		ShaderPackScanner.ScanResult result = ShaderPackScanner.scanDirectory(dir);

		assertTrue(result.supportedPacks().isEmpty());
		assertEquals("not-caldera-compatible", result.unsupportedPacks().getFirst().reason());
	}

	@Test
	void scanDirectoryReportsAMissingFolderAsUnreadable(@TempDir Path dir) {
		ShaderPackScanner.ScanResult result = ShaderPackScanner.scanDirectory(dir.resolve("does-not-exist"));

		assertTrue(result.supportedPacks().isEmpty());
		assertEquals(1, result.unsupportedPacks().size());
		assertEquals("unreadable-folder", result.unsupportedPacks().getFirst().reason());
	}

	@Test
	void scanDirectorySortsEntriesCaseInsensitively(@TempDir Path dir) throws IOException {
		writeValidPack(dir.resolve("bPack"));
		writeValidPack(dir.resolve("APack"));
		writeValidPack(dir.resolve("cPack"));

		List<String> ids = ShaderPackScanner.scanDirectory(dir).supportedPacks().stream()
				.map(ShaderPackScanner.AvailableShaderPack::id)
				.toList();

		assertEquals(List.of("APack", "bPack", "cPack"), ids, "必须按文件名做大小写不敏感排序");
	}

	@Test
	void scanDirectorySeparatesSupportedFromUnsupported(@TempDir Path dir) throws IOException {
		writeValidPack(dir.resolve("Good"));
		Files.writeString(dir.resolve("junk.txt"), "x", StandardCharsets.UTF_8);

		ShaderPackScanner.ScanResult result = ShaderPackScanner.scanDirectory(dir);

		assertEquals(1, result.supportedPacks().size());
		assertEquals(1, result.unsupportedPacks().size());
		assertEquals("junk.txt", result.unsupportedPacks().getFirst().displayName());
	}

	// ------------------------------------------------------------ isKnownPackId

	@Test
	void isKnownPackIdAlwaysKnowsTheBuiltinPack() {
		assertTrue(ShaderPackScanner.isKnownPackId("__builtin__", List.of()), "内置包不需要出现在扫描结果里");
	}

	@Test
	void isKnownPackIdMatchesAScannedId() {
		ShaderPackScanner.AvailableShaderPack pack =
				new ShaderPackScanner.AvailableShaderPack("Nice", "Nice", Path.of("/nowhere"), false);

		assertTrue(ShaderPackScanner.isKnownPackId("Nice", List.of(pack)));
	}

	@Test
	void isKnownPackIdRejectsAGhostId() {
		ShaderPackScanner.AvailableShaderPack pack =
				new ShaderPackScanner.AvailableShaderPack("Nice", "Nice", Path.of("/nowhere"), false);

		assertFalse(ShaderPackScanner.isKnownPackId("Vanished", List.of(pack)));
	}

	// ------------------------------------------------------------ isNative

	@Test
	void isNativeAcceptsADirectoryWithARootManifest(@TempDir Path dir) throws IOException {
		writeValidPack(dir);

		assertTrue(NativePackRuntime.isNative(dir));
	}

	@Test
	void isNativeAcceptsADirectoryWithTheManifestInAContainerFolder(@TempDir Path dir) throws IOException {
		writeValidPack(dir.resolve("Container"));

		assertTrue(NativePackRuntime.isNative(dir), "容器目录在一层以内必须被找到");
	}

	@Test
	void isNativeRejectsADirectoryWithoutAManifest(@TempDir Path dir) throws IOException {
		Files.createDirectories(dir.resolve("shaders"));

		assertFalse(NativePackRuntime.isNative(dir));
	}

	@Test
	void isNativeRejectsAManifestBuriedTooDeep(@TempDir Path dir) throws IOException {
		writeValidPack(dir.resolve("a").resolve("b"));

		assertFalse(NativePackRuntime.isNative(dir), "caldera.json 超过两层就不算原生包");
	}

	@Test
	void isNativeAcceptsAZipWithARootManifest(@TempDir Path dir) throws IOException {
		Path zip = dir.resolve("Root.zip");
		writeZip(zip, "caldera.json", VALID_MANIFEST);

		assertTrue(NativePackRuntime.isNative(zip));
	}

	@Test
	void isNativeAcceptsAZipWithAContainerFolder(@TempDir Path dir) throws IOException {
		Path zip = dir.resolve("Wrapped.zip");
		writeZip(zip, "Container/caldera.json", VALID_MANIFEST);

		assertTrue(NativePackRuntime.isNative(zip));
	}

	@Test
	void isNativeRejectsAZipWithoutAManifest(@TempDir Path dir) throws IOException {
		Path zip = dir.resolve("Plain.zip");
		writeZip(zip, "shaders/a.fsh", "#version 460 core\n");

		assertFalse(NativePackRuntime.isNative(zip));
	}

	@Test
	void isNativeRejectsAMissingPath(@TempDir Path dir) {
		assertFalse(NativePackRuntime.isNative(dir.resolve("nope")));
		assertFalse(NativePackRuntime.isNative(dir.resolve("nope.zip")));
	}
}
