package com.caldera.shaders.pack;

import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.graph.NativePackRuntime;
import com.caldera.shaders.graph.PackFiles;
import com.caldera.shaders.graph.PackGraph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;

public final class ShaderPackScanner {
   private static final String SHADER_DIRECTORY_NAME = "shaders";

   private ShaderPackScanner() {
   }

   public static Path shaderPackDirectory() {
      return FabricLoader.getInstance().getGameDir().resolve("shaders");
   }

   public static void ensureShaderPackDirectory() {
      try {
         Files.createDirectories(shaderPackDirectory());
      } catch (IOException exception) {
         throw new IllegalStateException("Failed to create Caldera shader pack directory", exception);
      }
   }

   public static List<AvailableShaderPack> listAvailablePacks() {
      return scan().supportedPacks();
   }

   public static ScanResult scan() {
      ensureShaderPackDirectory();
      return scanDirectory(shaderPackDirectory());
   }

   static ScanResult scanDirectory(Path directory) {
      List<AvailableShaderPack> supported = new ArrayList();
      List<UnsupportedShaderPack> unsupported = new ArrayList();

      try {
         Stream<Path> stream = Files.list(directory);

         try {
            stream.sorted(Comparator.comparing((path) -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)).forEach((path) -> classify(path, supported, unsupported));
         } catch (Throwable var7) {
            if (stream != null) {
               try {
                  stream.close();
               } catch (Throwable var6) {
                  var7.addSuppressed(var6);
               }
            }

            throw var7;
         }

         if (stream != null) {
            stream.close();
         }
      } catch (IOException var8) {
         unsupported.add(new UnsupportedShaderPack(directory.toString(), "unreadable-folder", directory));
      }

      return new ScanResult(List.copyOf(supported), List.copyOf(unsupported));
   }

   public static boolean isKnownPackId(String packId, List<AvailableShaderPack> packs) {
      if ("__builtin__".equals(packId)) {
         return true;
      } else {
         for(AvailableShaderPack pack : packs) {
            if (pack.id().equals(packId)) {
               return true;
            }
         }

         return false;
      }
   }

   private static void classify(Path path, List<AvailableShaderPack> supported, List<UnsupportedShaderPack> unsupported) {
      String fileName = path.getFileName().toString();
      if (!isCandidatePack(path)) {
         unsupported.add(new UnsupportedShaderPack(fileName, "not-a-shader-pack", path));
      } else {
         if (containsSupportedShaders(path)) {
            supported.add(toAvailablePack(path));
         } else {
            unsupported.add(new UnsupportedShaderPack(fileName, "not-caldera-compatible", path));
         }

      }
   }

   private static boolean isCandidatePack(Path path) {
      if (Files.isDirectory(path, new LinkOption[0])) {
         return true;
      } else {
         String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
         return Files.isRegularFile(path, new LinkOption[0]) && fileName.endsWith(".zip");
      }
   }

   private static AvailableShaderPack toAvailablePack(Path path) {
      String fileName = path.getFileName().toString();
      String displayName = fileName.endsWith(".zip") ? fileName.substring(0, fileName.length() - 4) : fileName;
      return new AvailableShaderPack(fileName, displayName, path, Files.isDirectory(path, new LinkOption[0]));
   }

   private static boolean containsSupportedShaders(Path path) {
      try {
         if (NativePackRuntime.isNative(path)) {
            PackFiles files = PackFiles.read(path);
            PackGraph.parse(files.text("caldera.json"));
            return true;
         } else {
            return false;
         }
      } catch (IllegalArgumentException | IOException var2) {
         return false;
      }
   }

   public static record UnsupportedShaderPack(String displayName, String reason, Path path) {
   }

   public static record ScanResult(List<AvailableShaderPack> supportedPacks, List<UnsupportedShaderPack> unsupportedPacks) {
      /**
       * 把一个选中的包 id 归一化成"要么它还在，要么内置包"。
       * <p>
       * 这条不变量原先在四个地方各写了一遍：{@code ShaderRuntime} 的 loadState、realign、
       * normalizePackId（外加只服务它的一个私有 {@code findPack}），以及
       * {@code ShadersScreen.activePackId}。前三条里有一条是手抄 {@link #isKnownPackId}。
       * 归一化放在扫描结果上，是因为**它**才知道有哪些包；调用方手里都已经有这个对象，
       * 于是不必记得把 {@code supportedPacks()} 传进来，也就误用不了。
       * <p>
       * 它与"包 id 不能为空"是两回事：后者是 {@code ShaderConfig} 自己管的（空 → 内置包），
       * 不需要知道有哪些包。
       */
      public String resolveSelection(String selectedPackId) {
         return isKnownPackId(selectedPackId, this.supportedPacks) ? selectedPackId : ShaderConfig.BUILTIN_PACK_ID;
      }
   }

   public static record AvailableShaderPack(String id, String displayName, Path path, boolean directory) {
   }
}
