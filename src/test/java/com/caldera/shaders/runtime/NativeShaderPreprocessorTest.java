package com.caldera.shaders.runtime;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code #moj_import} 的展开。
 * <p>
 * 迁移前这一段没有测试，而且**写不出来**：展开住在抽象类上，"include 里是什么"只能靠继承提供，
 * 于是要测它就得先有一份真的着色器源码与一个真的资源栈。现在它是 {@code expand(source, resolver)}，
 * include 的内容由参数给，于是递归、去重与"解析不出来"这三条都能在这里逐条钉住。
 */
class NativeShaderPreprocessorTest {

	private static int occurrences(String text, String needle) {
		int count = 0;
		int at = text.indexOf(needle);
		while (at >= 0) {
			count++;
			at = text.indexOf(needle, at + needle.length());
		}

		return count;
	}

	@Test
	void textWithoutDirectivesIsReturnedUnchangedAndTheResolverIsNeverAsked() {
		String source = "#version 450\nvoid main() {}\n";

		String expanded = NativeShaderPreprocessor.expand(source, name -> {
			throw new AssertionError("没有指令时不该去问 include 的内容：" + name);
		});

		assertEquals(source, expanded);
	}

	@Test
	void anImportIsReplacedByTheBodyItResolvesTo() {
		String source = "#version 450\n#moj_import <caldera:a.glsl>\nvoid main() {}\n";

		String expanded = NativeShaderPreprocessor.expand(source, name -> "BODY(" + name + ")");

		assertFalse(expanded.contains("#moj_import"), "指令本身不该留在结果里");
		assertTrue(expanded.contains("BODY(caldera:a.glsl)"));
		assertTrue(expanded.contains("#version 450"), "指令之外的内容原样保留");
		assertTrue(expanded.contains("void main() {}"));
	}

	@Test
	void quotedAndAngledImportsAreBothRecognised() {
		String source = "#moj_import <a>\n#moj_import \"b\"\n";

		String expanded = NativeShaderPreprocessor.expand(source, name -> "[" + name + "]");

		assertTrue(expanded.contains("[a]"));
		assertTrue(expanded.contains("[b]"));
		assertFalse(expanded.contains("#moj_import"));
	}

	@Test
	void anImportThatResolvesToNothingSimplyDisappears() {
		// 生产实现用它表达"同一个 include 只展开一次"：第二次返回 null。
		Set<String> seen = new HashSet<>();
		String source = "#moj_import <twice>\n#moj_import <twice>\n";

		String expanded = NativeShaderPreprocessor.expand(source, name -> seen.add(name) ? "BODY" : null);

		assertEquals(1, occurrences(expanded, "BODY"), "第二次展开必须什么也不放");
		assertFalse(expanded.contains("#moj_import"), "两条指令都必须被替换掉");
	}

	@Test
	void anImportInsideAnImportIsExpandedToo() {
		String source = "#moj_import <outer>\n";

		String expanded = NativeShaderPreprocessor.expand(source, name -> name.equals("outer") ? "#moj_import <inner>\n" : "INNER");

		assertTrue(expanded.contains("INNER"), "嵌套的那一层必须也展开");
		assertFalse(expanded.contains("#moj_import"));
		assertFalse(expanded.contains("outer"));
	}

	@Test
	void anIndentedDirectiveIsStillADirective() {
		// 展开放进别的文件里之后缩进是常事；`^\s*` 就是为它写的。
		String source = "    #moj_import <indented>\n";

		String expanded = NativeShaderPreprocessor.expand(source, name -> "OK");

		assertTrue(expanded.contains("OK"));
		assertFalse(expanded.contains("#moj_import"));
	}

	@Test
	void anImportMentionedInsideAnotherLineIsNotADirective() {
		// 只有整行才是指令——注释里提到它不该把那一行吃掉。
		String source = "// see #moj_import <x> for details\n";

		String expanded = NativeShaderPreprocessor.expand(source, name -> {
			throw new AssertionError("这一行不是指令：" + name);
		});

		assertEquals(source, expanded);
	}
}
