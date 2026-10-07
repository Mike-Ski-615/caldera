package com.caldera.shaders.graph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
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
      List<String> manifests = entries.keySet().stream().filter((n) -> n.equals("caldera.json") || n.endsWith("/caldera.json")).toList();
      if (manifests.size() != 1) {
         throw new IOException("Native pack needs exactly one caldera.json");
      } else {
         String prefix = manifests.getFirst().substring(0, manifests.getFirst().length() - "caldera.json".length());
         Map<String, byte[]> rooted = new LinkedHashMap<>();
         entries.forEach((name, bytes) -> {
            if (name.startsWith(prefix)) {
               rooted.put(name.substring(prefix.length()), bytes);
            }

         });
         return new PackFiles(rooted);
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
