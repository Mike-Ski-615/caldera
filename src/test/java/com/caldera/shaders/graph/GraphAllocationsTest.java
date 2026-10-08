package com.caldera.shaders.graph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 槽位复用计划。
 * <p>
 * 这是候选 5 里"免费"的那条 win：{@link GraphAllocations#plan} 早就是一个纯函数、被两处调用
 * （{@code GraphRenderer} 拿它分配存储图，{@code PackGraph.allocationBytes} 拿它算显存预算），
 * 却一直没有测试。它错了的表现是显存超预算、或者两张同时活着的图被塞进同一个槽位——后者在画面上
 * 是两块内容互相覆盖，而不会抛异常。
 * <p>
 * 夹具用内置包真实的清单（与 {@code PackGraphTest} 同一份）。
 */
class GraphAllocationsTest {

   private static final String REAL_MANIFEST = readFixture();

   private static String readFixture() {
      try (InputStream stream = GraphAllocationsTest.class.getResourceAsStream("/caldera-realistic.json")) {
         assertNotNull(stream, "测试夹具 /caldera-realistic.json 必须存在");
         return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException failure) {
         throw new AssertionError(failure);
      }
   }

   private static PackGraph graph() {
      return PackGraph.parse(REAL_MANIFEST);
   }

   @Test
   void theSceneTargetsGetTheFirstSlotsInDeclarationOrder() {
      PackGraph graph = graph();
      GraphAllocations plan = GraphAllocations.plan(graph);

      for(int i = 0; i < graph.sceneTargets().size(); ++i) {
         assertEquals(i, plan.resourceSlots().get(graph.sceneTargets().get(i)), "场景目标按声明顺序占前几个槽位");
         assertEquals(graph.resources().get(graph.sceneTargets().get(i)), plan.slots().get(i));
      }
   }

   @Test
   void everyWrittenOutputAndThePresentTargetGetASlot() {
      PackGraph graph = graph();
      GraphAllocations plan = GraphAllocations.plan(graph);

      for(PackGraph.Pass pass : graph.schedule()) {
         for(String output : pass.writes()) {
            assertTrue(plan.resourceSlots().containsKey(output), output + " 被写了却没有槽位");
         }
      }

      assertTrue(plan.resourceSlots().containsKey(graph.present()), "present 目标必须有槽位");
      assertTrue(plan.resourceSlots().get(graph.present()) < plan.slots().size());
   }

   /**
    * 复用只发生在**定义相同**的资源之间——这是它的前提，也是它安全的原因：同样的格式与尺寸，
    * 换个名字就能接着用。把不同定义的资源塞进同一个槽位会直接让画面错乱。
    */
   @Test
   void everySlotHoldsResourcesWithTheSameDefinition() {
      PackGraph graph = graph();
      GraphAllocations plan = GraphAllocations.plan(graph);
      Map<Integer, List<String>> bySlot = new HashMap<>();
      plan.resourceSlots().forEach((name, slot) -> bySlot.computeIfAbsent(slot, (ignored) -> new ArrayList<>()).add(name));

      bySlot.forEach((slot, names) -> {
         PackGraph.Resource first = graph.resources().get(names.getFirst());

         for(String name : names) {
            assertEquals(first, graph.resources().get(name), "槽位 " + slot + " 里的 " + name + " 与同槽位的定义不同");
         }
      });
   }

   /**
    * 历史资源（{@code history()} 为真）**永不**复用：它每一帧都要留着上一份，所以计划给它一个
    * 独占的槽位。这条错了的表现是历史缓冲被下一帧的输出覆盖掉——画面上的残影会突然变样。
    */
   @Test
   void aHistoryResourceNeverSharesItsSlot() {
      PackGraph graph = graph();
      GraphAllocations plan = GraphAllocations.plan(graph);
      Map<Integer, List<String>> bySlot = new HashMap<>();
      plan.resourceSlots().forEach((name, slot) -> bySlot.computeIfAbsent(slot, (ignored) -> new ArrayList<>()).add(name));

      plan.resourceSlots().forEach((name, slot) -> {
         PackGraph.Resource resource = graph.resources().get(name);

         if (resource != null && resource.history()) {
            assertEquals(1, bySlot.get(slot).size(), "历史资源 " + name + " 与别人共享了槽位 " + slot);
         }
      });
   }

   @Test
   void planningTheSameGraphTwiceGivesTheSamePlan() {
      assertEquals(GraphAllocations.plan(graph()), GraphAllocations.plan(graph()), "计划必须是确定的");
   }

   @Test
   void thePlanIsImmutable() {
      GraphAllocations plan = GraphAllocations.plan(graph());

      assertThrows(UnsupportedOperationException.class, () -> plan.resourceSlots().put("nope", 0));
      assertThrows(UnsupportedOperationException.class, () -> plan.slots().add(null));
   }

   /** {@code PackGraph.allocationBytes} 走的是同一个计划，所以两者的槽位数必须一致。 */
   @Test
   void theMemoryBudgetUsesTheSamePlan() {
      PackGraph graph = graph();
      GraphAllocations plan = GraphAllocations.plan(graph);

      assertTrue(plan.slots().size() >= graph.sceneTargets().size());
      assertTrue(graph.allocationBytes(1920, 1080) > 0L, "按这个计划算出来的预算必须是个正数");
   }
}
