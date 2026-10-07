package com.caldera.shaders.graph;

import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.runtime.FakeShaderHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一个**外部包**（磁盘上的一份目录）可以被打开。
 * <p>
 * 这个测试在迁移前写不出来：包目录是 {@code ShaderPackScanner.shaderPackDirectory()}
 * 自己拿 {@code FabricLoader.getInstance().getGameDir()} 拼的，谁都指不动它，于是测试里只有
 * classpath 上的内置包可达。现在目录来自端口的 {@code packsRoot()}，而
 * {@link FakeShaderHost#packsRoot} 可以指向一个临时目录。
 * <p>
 * 打开一条外部包**不需要 GPU**：{@code settings(id)} 那条链是
 * {@code packFiles → PackFiles.read(root.resolve(id)) → PackGraph.parse → 选项迁移}，
 * 这正是它能被钉住的原因。至于 {@code prepare(id)}，它无论如何都要真的渲染器，所以不在这里。
 */
class ExternalPackTest {

	private static final String PACK_ID = "MyPack";
	private static final String VALID_MANIFEST = readFixture();

	private FakeShaderHost host;

	@BeforeEach
	void installAFakeHost() {
		this.host = new FakeShaderHost();
		NativePackRuntime.install(this.host);
	}

	@AfterEach
	void leaveNotInstalled() {
		NativePackRuntime.uninstall();
	}

	private static String readFixture() {
		try (InputStream stream = ExternalPackTest.class.getResourceAsStream("/caldera-realistic.json")) {
			assertNotNull(stream, "测试夹具 /caldera-realistic.json 必须存在");
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException failure) {
			throw new AssertionError(failure);
		}
	}

	/** 在给定的包里放一份真清单——它就是内置包那份，只是现在住在磁盘上。 */
	private static Path writeExternalPack(Path packsRoot) throws IOException {
		Path pack = packsRoot.resolve(PACK_ID);
		Files.createDirectories(pack);
		Files.writeString(pack.resolve("caldera.json"), VALID_MANIFEST, StandardCharsets.UTF_8);
		return pack;
	}

	// ------------------------------------------------------------ 打开

	@Test
	void anExternalPackIsOpenedThroughThePackDirectoryThePortGives(@TempDir Path packsRoot) throws IOException {
		writeExternalPack(packsRoot);
		this.host.packsRoot = packsRoot;

		PackGraph graph = NativePackRuntime.settings(PACK_ID);

		assertNotNull(graph);
		assertTrue(graph.optionDefinitions().containsKey("COLOR_GRADE"), "读到的是包自己声明的那份选项定义");
	}

	@Test
	void thePackDirectoryIsReadPerCallSoMovingTheRootMovesWhatIsOpened(@TempDir Path packsRoot, @TempDir Path elsewhere) throws IOException {
		writeExternalPack(elsewhere);
		this.host.packsRoot = packsRoot;

		// 这个根下没有这个包：读盘失败，而不是悄悄读别处的同名包。
		assertThrows(IOException.class, () -> NativePackRuntime.settings(PACK_ID));

		this.host.packsRoot = elsewhere;

		assertNotNull(NativePackRuntime.settings(PACK_ID));
	}

	// ------------------------------------------------------------ 选项迁移

	@Test
	void aStoredLegacyValueIsMigratedForAnExternalPack(@TempDir Path packsRoot) throws IOException {
		writeExternalPack(packsRoot);
		this.host.packsRoot = packsRoot;
		// 0.75 是 COLOR_GRADE 早期四档之前的档位，当时第三档就是现在的 Vibrant（1.0）。
		// 它不在包声明的档位表里，所以必须在读出来的时候迁移——否则 withOptions 会直接抛。
		this.host.storedPackOptions.put(PACK_ID, Map.of("COLOR_GRADE", 0.75));

		PackGraph graph = NativePackRuntime.settings(PACK_ID);

		assertEquals(1.0, graph.options().get("COLOR_GRADE"));
	}

	@Test
	void aStoredValueOutsideTheDefinitionIsDropped(@TempDir Path packsRoot) throws IOException {
		writeExternalPack(packsRoot);
		this.host.packsRoot = packsRoot;
		this.host.storedPackOptions.put(PACK_ID, Map.of("COLOR_GRADE", 0.9));

		PackGraph graph = NativePackRuntime.settings(PACK_ID);

		assertEquals(0.5, graph.options().get("COLOR_GRADE"), "不在档位表里的值退回到包声明的默认值");
	}

	// ------------------------------------------------------------ 纯判断也认这根目录

	@Test
	void selectedChecksTheRootItIsGiven(@TempDir Path packsRoot, @TempDir Path elsewhere) throws IOException {
		writeExternalPack(packsRoot);
		ShaderConfig config = new ShaderConfig(true, PACK_ID);

		assertTrue(NativePackRuntime.selected(config, packsRoot));
		assertFalse(NativePackRuntime.selected(config, elsewhere), "判断用的是传进来的根，不是某个全局目录");
	}

	@Test
	void theBuiltinPackIsSelectedWithoutTouchingTheDisk(@TempDir Path nowhere) {
		assertTrue(NativePackRuntime.selected(new ShaderConfig(true, ShaderConfig.BUILTIN_PACK_ID), nowhere));
	}

	// ------------------------------------------------------------ 缺席时仍然拒绝

	@Test
	void openingAnExternalPackWithoutAnInstanceStillRefuses(@TempDir Path packsRoot) throws IOException {
		writeExternalPack(packsRoot);
		NativePackRuntime.uninstall();

		assertThrows(IllegalStateException.class, () -> NativePackRuntime.settings(PACK_ID));
	}
}
