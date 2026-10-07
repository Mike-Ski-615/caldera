package com.caldera.shaders.runtime;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.renderer.ShaderDefines;

public abstract class NativeShaderPreprocessor {
   private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*#moj_import\\s+[<\"]([^>\"]+)[>\"]\\s*$");

   public abstract String applyImport(boolean var1, String var2);

   public List<String> process(String source) {
      Matcher matcher = IMPORT.matcher(source);
      StringBuilder result = new StringBuilder();

      while(matcher.find()) {
         String include = this.applyImport(false, matcher.group(1));
         matcher.appendReplacement(result, Matcher.quoteReplacement(include == null ? "" : String.join("", this.process(include))));
      }

      matcher.appendTail(result);
      return List.of(result.toString());
   }

   public static String injectDefines(String source, ShaderDefines defines) {
      StringBuilder macros = new StringBuilder("\n");
      defines.values().forEach((name, value) -> macros.append("#define ").append(name).append(' ').append(value).append('\n'));
      defines.flags().forEach((name) -> macros.append("#define ").append(name).append('\n'));
      int versionEnd = source.indexOf(10);
      String var10000 = source.substring(0, versionEnd + 1);
      return var10000 + String.valueOf(macros) + source.substring(versionEnd + 1);
   }
}
