package com.caldera.shaders.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 那两个文件的读写。
 * <p>
 * 这个类存在的意义就是"它现在测得动"：改之前 settings 的读写长在 {@code ShaderConfig} 里、
 * 自己拼 {@code FabricLoader} 的路径，pack options 的读写长在 {@code NativePackRuntime} 里，
 * 两条都只有在真实游戏里才跑得到。现在两个目录都是注入的，于是这一个测试文件同时覆盖了
 * 之前**两条都覆盖不到**的路径。
 */
class CalderaConfigFilesTest {

   @TempDir
   Path root;

   private Path gameDirectory() {
      return this.root.resolve("game");
   }

   private Path configDirectory() {
      return this.root.resolve("config");
   }

   private CalderaConfigFiles files() {
      return new CalderaConfigFiles(gameDirectory(), configDirectory());
   }

   private Path settingsFile() {
      return gameDirectory().resolve("config").resolve("caldera-shaders.json");
   }

   private Path packOptionsDirectory() {
      return configDirectory().resolve("caldera-packs");
   }

   private static List<Path> list(Path directory) throws IOException {
      try (Stream<Path> entries = Files.list(directory)) {
         return entries.sorted().toList();
      }
   }

   // ---------------------------------------------------------------- 路径

   /**
    * 两个文件的确切位置是**行为的一部分**：改错地方等于所有人的设置都回到默认。
    * 原件 settings 走 {@code getGameDir().resolve("config")}，pack options 走 {@code getConfigDir()}，
    * 所以这里两个目录分开给，故意不合并。
    */
   @Test
   void settingsGoUnderTheGameDirectoryAndPackOptionsUnderTheConfigDirectory() throws IOException {
      files().saveSettings(new ShaderConfig(false, "Nice"));
      files().savePackOptions("Nice", Map.of("CLOUD_QUALITY", 2.0));

      assertTrue(Files.isRegularFile(settingsFile()), "settings 必须在 <gameDir>/config 下");
      assertEquals(List.of(settingsFile()), list(settingsFile().getParent()), "那个目录里只该有这一个文件");
      assertTrue(Files.isDirectory(packOptionsDirectory()), "pack options 必须在 <configDir>/caldera-packs 下");
      assertEquals(1, list(packOptionsDirectory()).size());
   }

   /** 文件名是包 id 的 SHA-256：包 id 就是包目录名，直接当文件名在有些文件系统上会出问题。 */
   @Test
   void thePackOptionsFileNameIsNotThePackIdItself() throws IOException {
      files().savePackOptions("带空格 的包名", Map.of("CLOUD_QUALITY", 2.0));

      Path stored = list(packOptionsDirectory()).get(0);
      assertFalse(stored.getFileName().toString().contains("带空格"), "文件名必须是哈希，不是 id 原样");
      assertTrue(stored.getFileName().toString().endsWith(".json"));
      assertEquals(Map.of("CLOUD_QUALITY", 2.0), files().loadPackOptions("带空格 的包名"));
   }

   // ---------------------------------------------------------------- settings

   @Test
   void settingsWithoutAFileAreTheDefaults() {
      ShaderConfig config = files().loadSettings();

      assertTrue(config.enabled(), "没有文件时默认开启");
      assertEquals(ShaderConfig.BUILTIN_PACK_ID, config.selectedPackId());
   }

   @Test
   void settingsRoundTripThroughTheFile() {
      files().saveSettings(new ShaderConfig(false, "Nicely Named"));

      ShaderConfig loaded = files().loadSettings();
      assertFalse(loaded.enabled());
      assertEquals("Nicely Named", loaded.selectedPackId(), "带空格的 id 必须原样回来");
   }

   @Test
   void aCorruptSettingsFileFallsBackToTheDefaults() throws IOException {
      Files.createDirectories(settingsFile().getParent());
      Files.writeString(settingsFile(), "{ this is not json at all");

      ShaderConfig loaded = files().loadSettings();
      assertTrue(loaded.enabled());
      assertEquals(ShaderConfig.BUILTIN_PACK_ID, loaded.selectedPackId());
   }

   /**
    * 文件里那个 id 的空值归一化归 {@link ShaderConfig} 管，不归文件层：文件层只管把它读出来。
    * 这条测试把"读出来之后确实经过了那一层"钉住。
    */
   @Test
   void aBlankSelectedPackIdInTheFileComesBackAsTheBuiltin() throws IOException {
      Files.createDirectories(settingsFile().getParent());
      Files.writeString(settingsFile(), "{\"enabled\":false,\"selectedPackId\":\"   \"}");

      ShaderConfig loaded = files().loadSettings();
      assertFalse(loaded.enabled());
      assertEquals(ShaderConfig.BUILTIN_PACK_ID, loaded.selectedPackId());
   }

   // ---------------------------------------------------------------- pack options

   @Test
   void packOptionsWithoutAFileAreEmpty() throws IOException {
      assertTrue(files().loadPackOptions("Nice").isEmpty());
   }

   @Test
   void packOptionsAreStoredPerPackId() throws IOException {
      files().savePackOptions("First", Map.of("CLOUD_QUALITY", 2.0));
      files().savePackOptions("Second", Map.of("CLOUD_QUALITY", 4.0));

      assertEquals(Map.of("CLOUD_QUALITY", 2.0), files().loadPackOptions("First"));
      assertEquals(Map.of("CLOUD_QUALITY", 4.0), files().loadPackOptions("Second"));
      assertEquals(2, list(packOptionsDirectory()).size());
   }

   @Test
   void packOptionsAreWrittenAsAJsonObjectOfNumbers() throws IOException {
      files().savePackOptions("Nice", Map.of("CLOUD_QUALITY", 2.0));

      Path stored = list(packOptionsDirectory()).get(0);
      JsonObject json = JsonParser.parseString(Files.readString(stored)).getAsJsonObject();
      assertEquals(2.0, json.get("CLOUD_QUALITY").getAsDouble());
   }

   /**
    * 原子写失败时的兜底路径要留下**一个**文件，不能留下临时文件——那会越攒越多，
    * 而且下次读的人不知道 `.tmp` 该不该认。
    */
   @Test
   void savingPackOptionsLeavesNoTemporaryFileBehind() throws IOException {
      files().savePackOptions("Nice", Map.of("CLOUD_QUALITY", 2.0));
      files().savePackOptions("Nice", Map.of("CLOUD_QUALITY", 4.0));

      List<String> names = list(packOptionsDirectory()).stream().map((path) -> path.getFileName().toString()).toList();
      assertEquals(1, names.size(), "两次写同一个包只该有一个文件：" + names);
      assertFalse(names.get(0).endsWith(".tmp"), names.toString());
      assertEquals(Map.of("CLOUD_QUALITY", 4.0), files().loadPackOptions("Nice"), "第二次必须覆盖第一次");
   }

   @Test
   void aCorruptPackOptionsFileIsReportedWithItsPath() throws IOException {
      files().savePackOptions("Nice", Map.of("CLOUD_QUALITY", 2.0));
      Path stored = list(packOptionsDirectory()).get(0);
      Files.writeString(stored, "not json at all");

      IOException failure = assertThrows(IOException.class, () -> files().loadPackOptions("Nice"));
      assertTrue(failure.getMessage().contains(stored.toString()), "错误里必须带路径，否则用户不知道是哪个包：" + failure.getMessage());
   }
}
