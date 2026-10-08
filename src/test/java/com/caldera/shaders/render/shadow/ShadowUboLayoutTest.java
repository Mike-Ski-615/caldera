package com.caldera.shaders.render.shadow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两个阴影 uniform 块的字节布局：Java 写进去的，必须与 GLSL 声明的是同一件事。
 * <p>
 * <b>为什么需要它。</b>这两个块此前**没有任何东西**在核对——{@code FrameLayoutTest} 只管帧 uniform，
 * 而阴影那两个块是手写的 Java 写入序对着手写的 GLSL 成员表。这一类错没有运行时症状：着色器安静地
 * 读到隔壁字段的值，或者读到一个根本不存在的成员，画面只是不对。
 * <p>
 * <b>它抓到的第一个真错。</b>{@code CalderaShadowData} 曾经声明成 416 字节：Java 在末尾多写了一个
 * {@code mat4 InverseViewRotation}，而 GLSL 那个块从来没有这个成员——它是从隔壁 {@code CalderaCascade}
 * 抄过来的（后者**确实**有，见 {@code entity_shadow_depth.vsh}）。那段尾巴写到显存、也被绑给着色器，
 * 但越出了声明范围因此永远读不到。当时没有造成渲染错误（反编译的 {@code VulkanRenderPass} 确认描述符
 * range 取自 slice 长度、只要求 ≥ 块尺寸），是纯粹的浪费加一个说谎的常量。
 * <p>
 * <b>这里钉的是 GLSL 那一侧。</b>缓冲区的分配量（{@code DirectionalShadowRenderer.SHADOW_DATA_BYTES}）
 * 是私有常量、且构造那个渲染器需要真的 Vulkan 设备，所以拿不到纯 JVM 的观测点；能拿到的是 GLSL 文本。
 * 两侧的相等由那条 {@code #define} 注释与这个测试共同维持：**改这里的成员就要同步改那个常量**。
 */
class ShadowUboLayoutTest {

	/** {@code caldera:sodium_globals.glsl} 那种手抄块——只关心最外层声明，不解析嵌套。 */
	private static final Pattern BLOCK = Pattern.compile(
			"layout\\(std140\\)\\s+uniform\\s+(\\w+)\\s*\\{(.*?)\\};", Pattern.DOTALL);

	/** std140 下这些类型的对齐与占用（本文件只用到这三类）。 */
	private static int bytesOf(String type) {
		return switch (type) {
			case "mat4" -> 64;
			case "vec4" -> 16;
			case "float", "int", "uint", "bool" -> 4;
			case "vec2" -> 8;
			default -> throw new IllegalArgumentException("Unexpected std140 type in a shadow block: " + type);
		};
	}

	/**
	 * 按 std140 算出成员表的字节数，并在过程中断言每个成员的起始偏移都是它对齐要求的倍数。
	 * <p>
	 * 只处理"没有隐式填充"的成员形状——两个阴影块都用 {@code mat4}/{@code vec4} 与它们的数组组成，
	 * 全部 16 字节对齐，所以这个简化是安全的；一旦有人加进一个会引入填充的成员，这里会大声失败而不是
	 * 悄悄算错。
	 */
	private static int std140Size(String blockName, String body) {
		int cursor = 0;
		int alignment = 16;
		for (Member member : members(blockName, body)) {
			int size = bytesOf(member.type()) * member.elements();

			assertEquals(0, cursor % alignment,
					blockName + " 的成员 " + member.type() + " " + member.name() + " 落在 " + cursor
							+ "，不是 " + alignment + " 的倍数——std140 会对齐到 16，也就是说这个成员表里有"
							+ "隐式填充，本测试的简化算法不再成立");
			cursor += size;
		}

		return cursor;
	}

	/** 一个成员：类型、名字、数组长度（非数组为 1）。 */
	private record Member(String type, String name, int elements) {
	}

	/**
	 * 解析成员表，忽略行注释与块注释。
	 * <p>
	 * 注释必须先剥掉再按 {@code ;} 切分：{@code native_shadows.glsl} 里就在成员之间写了说明，
	 * 而那些说明自身带分号与花括号，先切分会把成员切碎。这一条是踩过的——第一版没剥注释，
	 * 结果把 {@code mat4 ShadowViewProjection[4]} 判成"解析不了"。
	 */
	private static List<Member> members(String blockName, String body) {
		String stripped = body.replaceAll("//[^\\n]*", " ").replaceAll("/\\*.*?\\*/", " ");
		List<Member> result = new java.util.ArrayList<>();

		for (String raw : stripped.split(";")) {
			String declaration = raw.trim();
			if (declaration.isEmpty()) {
				continue;
			}

			// 数组括号跟在**名字**后面（`mat4 ShadowViewProjection[4]`），不是类型后面。
			Matcher parsed = Pattern.compile("^(\\w+)\\s+(\\w+)(\\[\\d+\\])?$").matcher(declaration);
			assertTrue(parsed.matches(), blockName + " 里有解析不了的成员：" + declaration);

			String count = parsed.group(3);
			result.add(new Member(parsed.group(1), parsed.group(2),
					count == null ? 1 : Integer.parseInt(count.substring(1, count.length() - 1))));
		}

		return result;
	}

	private static String readInclude(String name) throws Exception {
		try (var stream = ShadowUboLayoutTest.class.getResourceAsStream(
				"/assets/caldera/shaders/include/" + name)) {
			assertTrue(stream != null, "找不到 " + name + "——它必须在客户端源码集的资源里");
			return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
	}

	private static String blockBody(String source, String blockName) {
		Matcher matcher = BLOCK.matcher(source);
		while (matcher.find()) {
			if (matcher.group(1).equals(blockName)) {
				return matcher.group(2);
			}
		}

		throw new AssertionError("在 GLSL 里找不到 uniform 块 " + blockName);
	}

	@Test
	void theShadowDataBlockIsThreeHundredFiftyTwoBytes() throws Exception {
		String body = blockBody(readInclude("native_shadows.glsl"), "CalderaShadowData");

		assertEquals(352, std140Size("CalderaShadowData", body),
				"CalderaShadowData 的 std140 尺寸变了——它必须等于 Java 侧的 SHADOW_DATA_BYTES");
	}

	@Test
	void theShadowDataBlockDeclaresExactlyTheFourExpectedMembers() throws Exception {
		String body = blockBody(readInclude("native_shadows.glsl"), "CalderaShadowData");

		List<String> names = members("CalderaShadowData", body).stream().map(Member::name).toList();

		assertEquals(List.of("ShadowViewProjection", "CascadeParams", "LightDirection", "ShadowParams"), names,
				"成员表变了：Java 侧的写入序要跟着看一遍，而且 352 这个尺寸也要重算");
	}

	/**
	 * 那个被删掉的尾巴必须**不再出现**。
	 * <p>
	 * 这一条是本次修复的直接回归网：只要有人再从 {@code uploadCascade} 抄一次
	 * {@code InverseViewRotation} 过来，或者把它加回 GLSL 声明，两组断言里必有一组红。
	 */
	@Test
	void theShadowDataBlockDoesNotDeclareTheInverseViewRotationTail() throws Exception {
		String source = readInclude("native_shadows.glsl");
		String body = blockBody(source, "CalderaShadowData");

		assertTrue(!body.contains("InverseViewRotation"),
				"CalderaShadowData 不该有 InverseViewRotation——那个成员属于 CalderaCascade。"
						+ "加上它就要把 Java 的 SHADOW_DATA_BYTES 一起改成 416，否则两侧又不相等");
	}

	/**
	 * {@code CalderaCascade} 是隔壁那个块，它**确实**有 {@code InverseViewRotation}（顶点着色器要用它
	 * 把相机相对方向转回世界朝向）。这一条把两者的差别钉在测试里，免得下一个做同样核对的人把
	 * "多出来的那个 mat4"当成 bug 又删一遍。
	 */
	@Test
	void theCascadeBlockKeepsItsInverseViewRotationBecauseTheVertexShaderNeedsIt() throws Exception {
		String source;
		try (var stream = ShadowUboLayoutTest.class.getResourceAsStream(
				"/assets/caldera/shaders/core/entity_shadow_depth.vsh")) {
			assertTrue(stream != null, "找不到 entity_shadow_depth.vsh");
			source = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}

		String body = blockBody(source, "CalderaCascade");

		assertEquals(144, std140Size("CalderaCascade", body),
				"CalderaCascade 必须是 144 字节，与 Java 的 CASCADE_UBO_BYTES 相等");
		assertTrue(body.contains("InverseViewRotation"),
				"CalderaCascade 需要 InverseViewRotation：顶点着色器用它做 InverseViewRotation * vec4(v, 0.0)");
	}
}
