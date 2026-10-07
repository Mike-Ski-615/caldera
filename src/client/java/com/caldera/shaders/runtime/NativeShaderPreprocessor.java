package com.caldera.shaders.runtime;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.renderer.ShaderDefines;

/**
 * 原生着色器源码在交给编译器之前的两处改写：展开 {@code #moj_import}，以及注入 {@code #define}。
 * <p>
 * 迁移前"展开"长在一个抽象类上：子类只为了提供"这个 include 的内容是什么"而存在，而它的
 * {@code applyImport(boolean, String)} 第一个参数只有一处调用点、恒传 {@code false}，唯一的实现也
 * 从不读它；{@code process} 返回一个 {@code List<String>}，而唯一的消费者立刻把它
 * {@code String.join("", ...)} 成一个字符串。于是那个抽象类、那个布尔与那个列表都只是形状，不是内容。
 * <p>
 * 现在展开是**纯函数**：{@code source} 与"怎么解析一个 include"都由参数给，递归与替换全在这里。
 * 它因此不需要着色器文件、也不需要游戏就能被测——{@code NativeShaderPreprocessorTest} 就是这么做的。
 */
public final class NativeShaderPreprocessor {
   private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*#moj_import\\s+[<\"]([^>\"]+)[>\"]\\s*$");

   private NativeShaderPreprocessor() {
   }

   /**
    * 把每个 {@code #moj_import} 指令整行换成解析出来的内容，并**递归**展开它里面的指令。
    * <p>
    * {@code includeResolver} 返回 {@code null} 表示"这里什么也不放"——生产实现用它表达
    * "同一个 include 只展开一次"（第二次返回 {@code null}）。
    *
    * @param includeResolver 收到指令里写的那个名字，返回它的源码；{@code null} 表示放空
    */
   public static String expand(String source, Function<String, String> includeResolver) {
      Matcher matcher = IMPORT.matcher(source);
      StringBuilder result = new StringBuilder();

      while(matcher.find()) {
         String include = includeResolver.apply(matcher.group(1));
         matcher.appendReplacement(result, Matcher.quoteReplacement(include == null ? "" : expand(include, includeResolver)));
      }

      matcher.appendTail(result);
      return result.toString();
   }

   /**
    * 把 {@code defines} 注入到源码第一行之后（也就是 {@code #version} 之后）。
    * <p>
    * 它只认第一个换行：{@code #version} 必须是第一行，否则编译器会拒绝整份源码。
    */
   public static String injectDefines(String source, ShaderDefines defines) {
      StringBuilder macros = new StringBuilder("\n");
      defines.values().forEach((name, value) -> macros.append("#define ").append(name).append(' ').append(value).append('\n'));
      defines.flags().forEach((name) -> macros.append("#define ").append(name).append('\n'));
      int versionEnd = source.indexOf(10);
      String var10000 = source.substring(0, versionEnd + 1);
      return var10000 + String.valueOf(macros) + source.substring(versionEnd + 1);
   }
}
