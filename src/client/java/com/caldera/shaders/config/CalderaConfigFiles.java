package com.caldera.shaders.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;

/**
 * Caldera 写在磁盘上的那两个文件，只有这一个模块认识它们。
 * <p>
 * 为什么值得单独成一个模块：这两份持久化原先分在两个地方，而且是**两套写法**——
 * {@code ShaderConfig} 里一套静态 GSON 加 {@code config/caldera-shaders.json}，
 * {@code NativePackRuntime} 里另一套（每次调用新建一个 {@code GsonBuilder}、文件名取包 id 的
 * SHA-256、临时文件加 {@code ATOMIC_MOVE}）。而 {@code ShaderConfig} 是个 value type，
 * 却自己用 {@code FabricLoader} 拼路径，于是 ADR-0003 开好的那个端口只是转发进一个
 * **无论如何都测不到**的类。
 * <p>
 * 切法按"字节与语义分开"：这个模块只管字节——路径、JSON、原子写、坏文件怎么报。
 * "哪些选项值合法、旧格式的值怎么迁移"留在 {@code NativePackRuntime}，因为那需要
 * {@code optionDefinitions} 才判断得了。
 * <p>
 * 两个目录都是构造时注入的，于是测试可以把它指向一个临时目录，
 * 这两条读写路径第一次能在纯 JVM 里跑。
 */
public final class CalderaConfigFiles {
   private static final Logger LOGGER = LogUtils.getLogger();
   /** settings 与 pack options 都是 pretty-printing 的 JSON，原先却是两个各建各的实例。 */
   private static final Gson GSON = (new GsonBuilder()).setPrettyPrinting().create();
   private static final String SETTINGS_FILE_NAME = "caldera-shaders.json";
   private static final String PACK_OPTIONS_DIRECTORY_NAME = "caldera-packs";
   private static final String JSON_SUFFIX = ".json";
   private static final String TEMPORARY_SUFFIX = ".tmp";

   private final Path settingsFile;
   private final Path packOptionsDirectory;

   /**
    * @param gameDirectory   游戏目录；settings 写在它下面的 {@code config/} 里（原件用的就是
    *                        {@code getGameDir().resolve("config")}）
    * @param configDirectory 配置目录；pack options 写在它下面的 {@code caldera-packs/} 里
    *                        （原件用的是 {@code getConfigDir()}）。两者通常是同一个目录，
    *                        但原件确实分别取了这两个 API，所以这里也分别注入，不擅自合并。
    */
   public CalderaConfigFiles(Path gameDirectory, Path configDirectory) {
      this.settingsFile = gameDirectory.resolve("config").resolve(SETTINGS_FILE_NAME);
      this.packOptionsDirectory = configDirectory.resolve(PACK_OPTIONS_DIRECTORY_NAME);
   }

   /** 读全局设置；没有文件、读不动或解析失败时都给一份默认设置。 */
   public ShaderConfig loadSettings() {
      if (!Files.isRegularFile(this.settingsFile)) {
         return new ShaderConfig();
      } else {
         try (Reader reader = Files.newBufferedReader(this.settingsFile)) {
            ShaderConfig loaded = (ShaderConfig)GSON.fromJson(reader, ShaderConfig.class);
            return loaded == null ? new ShaderConfig() : new ShaderConfig(loaded.enabled(), loaded.selectedPackId());
         } catch (Exception exception) {
            LOGGER.error("Failed to load Caldera shader settings from {}", this.settingsFile, exception);
            return new ShaderConfig();
         }
      }
   }

   /**
    * 写全局设置。
    * <p>
    * 这里**没有**原子写：原件也没有。pack options 那边有，是因为它是用户逐个改出来的、
    * 丢了会心疼；settings 这个文件从 0.5.1 起就是直接覆盖。本次是行为保持的搬运，不顺手加。
    */
   public void saveSettings(ShaderConfig config) {
      try {
         Files.createDirectories(this.settingsFile.getParent());

         try (Writer writer = Files.newBufferedWriter(this.settingsFile)) {
            GSON.toJson(config, writer);
         }
      } catch (IOException exception) {
         LOGGER.error("Failed to save Caldera shader settings to {}", this.settingsFile, exception);
      }
   }

   /**
    * 读一个包存下来的选项；没有文件时返回空表。
    * <p>
    * 返回**已经解析好的值**而不是文件内容：调用方要用它去对照包自己的 {@code optionDefinitions}
    * 才知道哪些能用、哪些要迁移。JSON 的形状是这个模块的事，值的含义不是。
    *
    * @throws IOException 文件在、但内容不是一份能认的选项表——这条错误带着路径，与原件一致
    */
   public Map<String, Double> loadPackOptions(String packId) throws IOException {
      Path path = this.packOptionsFile(packId);
      if (!Files.isRegularFile(path)) {
         return Map.of();
      } else {
         try {
            JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            Map<String, Double> values = new TreeMap<>();

            for(Map.Entry<String, JsonElement> entry : json.entrySet()) {
               values.put(entry.getKey(), entry.getValue().getAsDouble());
            }

            return values;
         } catch (RuntimeException malformed) {
            throw new IOException("Invalid saved pack options: " + path, malformed);
         }
      }
   }

   /**
    * 写一个包的选项，走临时文件 + {@code ATOMIC_MOVE}。
    * <p>
    * 文件名是包 id 的 SHA-256：包 id 就是包目录名，可能带空格与中文，直接当文件名在某些
    * 文件系统上会出问题；哈希之后一律是安全的十六进制。
    */
   public void savePackOptions(String packId, Map<String, Double> values) throws IOException {
      Path destination = this.packOptionsFile(packId);
      Path temporary = destination.resolveSibling(destination.getFileName() + TEMPORARY_SUFFIX);
      Files.createDirectories(destination.getParent());
      Files.writeString(temporary, GSON.toJson(values));

      try {
         Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupportedAtomicMove) {
         Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
      }

   }

   private Path packOptionsFile(String packId) {
      try {
         String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(packId.getBytes(StandardCharsets.UTF_8)));
         return this.packOptionsDirectory.resolve(hash + JSON_SUFFIX);
      } catch (NoSuchAlgorithmException impossible) {
         throw new AssertionError(impossible);
      }
   }
}
