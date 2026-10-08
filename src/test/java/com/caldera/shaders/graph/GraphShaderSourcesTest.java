package com.caldera.shaders.graph;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程级注册表的归属。
 * <p>
 * 这四张 static 表按 **pipeline 身份**索引，所以它们必须是进程级的：mixin 在游戏编译管线的那一刻
 * 问"这条是不是 Caldera 的"，那是个与任何渲染器实例无关的问题。这一步没有把它们变成实例状态，
 * 而是给它们加了**所有者**——原先四个 owner 各自维护一份"要注销什么"的清单，漏一个就是静默残留
 * （{@code COMPILED} 里的编译产物与 {@code ORIGINAL} 里的反查会跟着留着）。
 * <p>
 * 能在这里断言的是"注销的范围对不对"：{@code releaseAll(owner)} 只动这个 owner 名下的。
 * <b>不能</b>在这里断言的是"编译产物真的被关掉了"——那需要一个真的 {@code CompiledRenderPipeline}，
 * 而它要 GPU。所以 {@code claim}-only 的那些管线（阴影与 Sodium 地形两张缓存）的释放只能由门禁覆盖。
 */
class GraphShaderSourcesTest {

   @Test
   void releaseAllReleasesOnlyThePipelinesOfThatOwner() {
      GraphShaderSources.Owner first = GraphShaderSources.owner("first");
      GraphShaderSources.Owner second = GraphShaderSources.owner("second");
      RenderPipeline a = pipeline("a");
      RenderPipeline b = pipeline("b");
      RenderPipeline c = pipeline("c");
      GraphShaderSources.put(first, a, "void main() {}", "void main() {}");
      GraphShaderSources.put(first, b, "void main() {}", "void main() {}");
      GraphShaderSources.put(second, c, "void main() {}", "void main() {}");

      GraphShaderSources.releaseAll(first);

      assertFalse(GraphShaderSources.contains(a), "owner 名下的第一条例被注销");
      assertFalse(GraphShaderSources.contains(b));
      assertTrue(GraphShaderSources.contains(c), "另一个 owner 的管线一条都不该被碰");
   }

   /** 两个同名 owner 是两个 owner：身份对象，不做 equals。 */
   @Test
   void ownersWithTheSameNameAreStillDifferentOwners() {
      GraphShaderSources.Owner first = GraphShaderSources.owner("same");
      GraphShaderSources.Owner second = GraphShaderSources.owner("same");
      RenderPipeline p = pipeline("p");
      GraphShaderSources.put(second, p, "void main() {}", "void main() {}");

      GraphShaderSources.releaseAll(first);

      assertTrue(GraphShaderSources.contains(p), "名字只用于诊断，归属按身份算");
   }

   @Test
   void releaseAllOnAnOwnerWithNothingClaimedIsSafe() {
      GraphShaderSources.releaseAll(GraphShaderSources.owner("nothing"));
   }

   @Test
   void releaseAllIsIdempotent() {
      GraphShaderSources.Owner owner = GraphShaderSources.owner("twice");
      GraphShaderSources.put(owner, pipeline("p"), "void main() {}", "void main() {}");

      GraphShaderSources.releaseAll(owner);
      GraphShaderSources.releaseAll(owner);
   }

   /**
    * {@code claim} 只挂归属，不登记源码。阴影与 Sodium 地形那两张缓存走这条：它们的源码来自核心
    * 着色器，本来就不该出现在 {@code SOURCES} 里——那会让 {@code get()} 返回一份不存在的原生源码。
    */
   @Test
   void claimTakesOwnershipWithoutRegisteringSources() {
      GraphShaderSources.Owner owner = GraphShaderSources.owner("claim only");
      RenderPipeline p = pipeline("claimed");
      GraphShaderSources.claim(owner, p);

      assertFalse(GraphShaderSources.contains(p), "claim 不该写进源码表");

      GraphShaderSources.releaseAll(owner);
   }

   /** 运行中单独注销过的那些仍然留在 owner 名单上，收摊时再走一遍必须是空操作。 */
   @Test
   void releaseAllIsSafeAfterAnIndividualRemove() {
      GraphShaderSources.Owner owner = GraphShaderSources.owner("mixed");
      RenderPipeline a = pipeline("a");
      RenderPipeline b = pipeline("b");
      GraphShaderSources.put(owner, a, "void main() {}", "void main() {}");
      GraphShaderSources.put(owner, b, "void main() {}", "void main() {}");

      GraphShaderSources.remove(a);
      assertFalse(GraphShaderSources.contains(a));

      GraphShaderSources.releaseAll(owner);

      assertFalse(GraphShaderSources.contains(a));
      assertFalse(GraphShaderSources.contains(b));
   }

   private static RenderPipeline pipeline(String name) {
      return RenderPipeline.builder(new RenderPipeline.Snippet[0])
            .withLocation(Identifier.fromNamespaceAndPath("caldera_test", name))
            .withVertexShader(Identifier.fromNamespaceAndPath("caldera_test", name + "_vertex"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("caldera_test", name + "_fragment"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();
   }
}
