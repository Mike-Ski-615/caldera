package com.caldera.shaders.graph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public final class PackFiles {
    /** 原生包的清单名。**包根规则**（见 {@link #isNative}）与读取都以它为准。 */
   private static final String MANIFEST = "caldera.json";

    private final Map<String, byte[]> files;

   private PackFiles(Map<String, byte[]> files) {
      this.files = Map.copyOf(files);
   }

   public static PackFiles read(Path path) throws IOException {
      Map<String, byte[]> entries = new LinkedHashMap<>();
      long total = 0L;
      if (Files.isDirectory(path)) {
         Path root = path.toRealPath();
         Stream<Path> paths = Files.walk(root);

         try {
            for(Path file : paths.filter((x$0) -> Files.isRegularFile(x$0, new LinkOption[0])).toList()) {
               if (!file.toRealPath().startsWith(root)) {
                  throw new IOException("Pack symlink escapes its folder");
               }

               InputStream stream = Files.newInputStream(file);

               try {
                  byte[] bytes = stream.readNBytes(8388609);
                  total = add(entries, root.relativize(file).toString().replace('\\', '/'), bytes, total);
               } catch (Throwable var16) {
                   try {
                       stream.close();
                   } catch (Throwable var14) {
                       var16.addSuppressed(var14);
                   }

                   throw var16;
               }

                stream.close();
            }
         } catch (Throwable var17) {
             try {
                 paths.close();
             } catch (Throwable var13) {
                 var17.addSuppressed(var13);
             }

             throw var17;
         }

          paths.close();
      } else {
         ZipFile zip = new ZipFile(path.toFile());

         try {
            Enumeration<? extends ZipEntry> items = zip.entries();

            while(items.hasMoreElements()) {
               ZipEntry entry = items.nextElement();
               if (!entry.isDirectory()) {
                  InputStream stream = zip.getInputStream(entry);

                  try {
                     total = add(entries, entry.getName(), stream.readNBytes(8388609), total);
                  } catch (Throwable var15) {
                     if (stream != null) {
                        try {
                           stream.close();
                        } catch (Throwable var12) {
                           var15.addSuppressed(var12);
                        }
                     }

                     throw var15;
                  }

                   stream.close();
               }
            }
         } catch (Throwable var18) {
            try {
               zip.close();
            } catch (Throwable var11) {
               var18.addSuppressed(var11);
            }

            throw var18;
         }

         zip.close();
      }

      return rooted(entries);
   }

   public static PackFiles bundled() throws IOException {
      InputStream resource = PackFiles.class.getResourceAsStream("/caldera-bundled/Caldera-Realistic.zip");
      if (resource == null) {
         throw new IOException("Caldera Realistic is missing from this installation. Reinstall the Caldera JAR.");
      } else {
         Map<String, byte[]> entries = new LinkedHashMap<>();
         long total = 0L;
         ZipInputStream zip = new ZipInputStream(resource);

         ZipEntry entry;
         try {
            while((entry = zip.getNextEntry()) != null) {
               if (!entry.isDirectory()) {
                  total = add(entries, entry.getName(), zip.readNBytes(8388609), total);
               }
            }
         } catch (Throwable var8) {
            try {
               zip.close();
            } catch (Throwable var7) {
               var8.addSuppressed(var7);
            }

            throw var8;
         }

         zip.close();
         return rooted(entries);
      }
   }

   private static PackFiles rooted(Map<String, byte[]> entries) throws IOException {
      String manifest = singleManifest(manifestEntries(entries));
      String prefix = manifest.substring(0, manifest.length() - MANIFEST.length());
      Map<String, byte[]> rooted = new LinkedHashMap<>();
      entries.forEach((name, bytes) -> {
         if (name.startsWith(prefix)) {
            rooted.put(name.substring(prefix.length()), bytes);
         }

      });
      return new PackFiles(rooted);
   }

   /**
    * 一个包能不能算原生包：它里面**恰好有一份** {@link #MANIFEST}，而且落在包根或包根下**一层**
    * 容器目录里（也就是常说的"zip 里套了一个文件夹"那种）。
    * <p>
    * <b>这条规则只写在这里一处，而且与读取共用同一个谓词。</b>迁移前它有两个实现，而且两边不一样：
    * 目录走 {@code Files.walk(path, 2)}，zip 走单层正则，于是同一个包放进目录时算数、打成 zip 就不算数。
    * 现在扫描器、以及"选中的这个包还成立吗"那一问都问这里，{@link #rooted} 也共用
    * {@link #manifestEntries}。三条路径必须给出同一个答案——"能读进来"与"算原生包"不许是两个答案，
    * 所以 {@code exactly one} 这一条在这里也要成立（{@link #singleManifest} 就是它）。
    * <p>
    * 它读的是"能不能读进来"这一层的真相：解不开的 zip、不存在的路径、连不上的目录，一律
    * {@code false}（而不是抛），因为调用方问的是一个是非题。
    */
   public static boolean isNative(Path path) {
      try {
         if (Files.isDirectory(path)) {
            List<String> manifests = new ArrayList<>();
            collectManifestEntries(path, manifests);
            singleManifest(manifests);
            return true;
         }

         ZipFile zip = new ZipFile(path.toFile());

         try {
            Enumeration<? extends ZipEntry> items = zip.entries();
            List<String> manifests = new ArrayList<>();

            while(items.hasMoreElements()) {
               String name = items.nextElement().getName();
               if (isManifestEntry(name)) {
                  manifests.add(name);
               }
            }

            singleManifest(manifests);
            return true;
         } catch (Throwable var10) {
            try {
               zip.close();
            } catch (Throwable var5) {
               var10.addSuppressed(var5);
            }

            throw var10;
         } finally {
            zip.close();
         }
      } catch (IOException | RuntimeException ignored) {
         // 路径不存在、不是 zip、zip 读不开、清单不是恰好一份：都是"不是原生包"，不是异常。
         return false;
      }
   }

   /**
    * 收集一个目录里清单候选的**名字**，最多下探一层：包根就是这一层，更深处同名的文件不算清单。
    * <p>
    * 它只收集名字、不读字节——问"/是不是原生包"不该把整个包读进内存（旧实现也不读），
    * 而且候选数量不该受 {@link #add} 那些加载器上限的牵连。
    */
   private static void collectManifestEntries(Path root, List<String> into) throws IOException {
      List<Path> candidates = new ArrayList<>();
      collectManifestCandidates(root, candidates);
      Stream<Path> children = Files.list(root);

      try {
         children.filter((child) -> Files.isDirectory(child, new LinkOption[0])).forEach((child) -> collectManifestCandidates(child, candidates));
      } catch (Throwable var7) {
         try {
            children.close();
         } catch (Throwable var6) {
            var7.addSuppressed(var6);
         }

         throw var7;
      }

      children.close();

      for(Path file : candidates) {
         into.add(root.relativize(file).toString().replace('\\', '/'));
      }
   }

   /** 把这一层里的清单候选收进来；只看名字与"是不是普通文件"，与 {@link #isManifestEntry} 同一把尺。 */
   private static void collectManifestCandidates(Path directory, List<Path> into) {
      Stream<Path> children;

      try {
         children = Files.list(directory);
      } catch (IOException unreadable) {
         return;
      }

      try {
         children.filter((child) -> child.getFileName().toString().endsWith(MANIFEST) && Files.isRegularFile(child, new LinkOption[0])).forEach(into::add);
      } catch (Throwable var6) {
         try {
            children.close();
         } catch (Throwable var5) {
            var6.addSuppressed(var5);
         }

         throw var6;
      }

      children.close();
   }

   /**
    * 这批条目里所有清单的条目名；{@link #rooted} 靠它推包根。
    * <p>
    * 它筛的是同一把尺（{@link #isManifestEntry}）：<b>位置无关</b>地把"位于包根或包根下一层"的
    * {@link #MANIFEST} 找出来。读目录那一侧本来就是相对路径，zip 那一侧是整条条目名，两者都必须
    * 得到同一个答案——否则"能读进来"与"算原生包"又会分家。
    */
   private static List<String> manifestEntries(Map<String, byte[]> entries) {
      return entries.keySet().stream().filter(PackFiles::isManifestEntry).toList();
   }

   /**
    * 这份清单条目是不是落在包根或包根下**恰好一层**——也就是"路径深一层以内"。
    * <p>
    * 判据是**数斜杠**，不是"清单名前面有没有 {@code /}"：{@code Container/caldera.json}（一层，算）
    * 与 {@code nested/caldera.json}（也一层，算）都满足后者，而 {@code a/b/caldera.json} 同样满足后者
    * 却深了两层。斜杠超过一个就是更深，不算清单。
    */
   private static boolean isManifestEntry(String name) {
      if (!name.endsWith(MANIFEST)) {
         return false;
      }

      return name.chars().filter((c) -> c == '/').count() <= 1L;
   }

   /** 恰好一份清单，返回它；否则抛。读取与"算不算原生包"共用这一条。 */
   private static String singleManifest(List<String> manifests) throws IOException {
      if (manifests.size() != 1) {
         throw new IOException("Native pack needs exactly one caldera.json");
      } else {
         return manifests.getFirst();
      }
   }

   private static long add(Map<String, byte[]> files, String name, byte[] bytes, long total) throws IOException {
      if (!safe(name)) {
         throw new IOException("Invalid pack path: " + name);
      } else if (bytes.length <= 8388608 && total + (long)bytes.length <= 134217728L && files.size() < 16384) {
         if (files.putIfAbsent(name, bytes) != null) {
            throw new IOException("Duplicate pack entry: " + name);
         } else {
            return total + (long)bytes.length;
         }
      } else {
         throw new IOException("Pack exceeds loader size limits");
      }
   }

   public static boolean safe(String name) {
      return name != null && !name.isBlank() && !name.startsWith("/") && !name.contains("\\") && !name.contains(":") && Arrays.stream(name.split("/", -1)).noneMatch((p) -> p.isEmpty() || p.equals(".") || p.equals(".."));
   }

   public String text(String name) throws IOException {
      if ("caldera/frame.glsl".equals(name)) {
         return GraphFrame.declaration();
      } else if (!"caldera/shadows.glsl".equals(name) && !"caldera/shadow-filter.glsl".equals(name)) {
         if (safe(name) && this.files.containsKey(name)) {
            return new String(this.files.get(name), StandardCharsets.UTF_8);
         } else {
            throw new IOException("Missing pack file: " + name);
         }
      } else {
         String resource = name.equals("caldera/shadows.glsl") ? "/assets/caldera/shaders/include/native_shadows.glsl" : "/assets/caldera/shaders/include/directional_shadow.glsl";
         InputStream stream = PackFiles.class.getResourceAsStream(resource);

         String var4;
         try {
            if (stream == null) {
               throw new IOException("Missing loader include " + resource);
            }

            var4 = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
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

          stream.close();

          return var4;
      }
   }

   public byte[] binary(String name) throws IOException {
      if (safe(name) && this.files.containsKey(name)) {
         return this.files.get(name).clone();
      } else {
         throw new IOException("Missing pack file: " + name);
      }
   }

   public String shader(String name, Map<String, Double> defines) throws IOException {
      String source = this.expand(name, new ArrayDeque<>(), new HashMap<>());
      int end = source.indexOf(10);
      if (source.startsWith("#version ") && end >= 0) {
         StringBuilder header = new StringBuilder();
         defines.forEach((key, value) -> {
            if (key.matches("[A-Z][A-Z0-9_]*") && Double.isFinite(value)) {
               String literal = value == Math.rint(value) && Math.abs(value) <= (double)Integer.MAX_VALUE ? Long.toString(value.longValue()) : value.toString();
               header.append("#define _CALDERA_ENABLED_").append(key).append(value > (double)0.0F ? " 1\n" : " 0\n");
               header.append("#define ").append(key).append(' ').append(literal).append('\n');
            } else {
               throw new IllegalArgumentException("Invalid shader define " + key);
            }
         });
         String var10000 = source.substring(0, end + 1);
         return var10000 + header + "#line 2 0\n" + source.substring(end + 1);
      } else {
         throw new IOException(name + ": shader must start with #version");
      }
   }

   private String expand(String name, Deque<String> stack, Map<String, Integer> ids) throws IOException {
      if (!stack.contains(name) && stack.size() < 32) {
         stack.addLast(name);
         int id = ids.computeIfAbsent(name, (ignored) -> ids.size());
         StringBuilder result = new StringBuilder();
         String[] lines = this.text(name).split("\n", -1);

         for(int line = 0; line < lines.length; ++line) {
            String trimmed = lines[line].trim();
            if (trimmed.startsWith("#include")) {
               Matcher matcher = Pattern.compile("#include\\s+\"([^\"]+)\"\\s*(?://.*)?").matcher(trimmed);
               if (!matcher.matches()) {
                  throw new IOException(name + ":" + (line + 1) + ": expected a quoted pack-root include");
               }

               String included = matcher.group(1);
               int includeId = ids.computeIfAbsent(included, (ignored) -> ids.size());
               result.append("#line 1 ").append(includeId).append('\n').append(this.expand(included, stack, ids));
               result.append("#line ").append(line + 2).append(' ').append(id).append('\n');
            } else {
               result.append(lines[line]).append('\n');
            }

            if (result.length() > 8388608) {
               throw new IOException("Expanded shader too large: " + name);
            }
         }

         stack.removeLast();
         return result.toString();
      } else {
         String var10002 = String.valueOf(stack);
         throw new IOException("Include cycle/depth: " + var10002 + " -> " + name);
      }
   }
}
