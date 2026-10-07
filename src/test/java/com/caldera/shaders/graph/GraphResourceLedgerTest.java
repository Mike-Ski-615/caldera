package com.caldera.shaders.graph;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 释放账本。这是候选 5 第 1 步唯一能在纯 JVM 里测的东西，而它恰好是这一步的全部内容：
 * 「登记了什么就被释放什么，一次，且不会被前一个的失败吞掉」。
 * <p>
 * 这类判断错了的表现是**静默泄漏**——没有异常、没有日志、门禁也看不出来。所以这几条值得单独钉。
 */
class GraphResourceLedgerTest {

   @Test
   void everythingRegisteredIsReleasedInRegistrationOrder() {
      List<String> calls = new ArrayList<>();
      GraphResourceLedger ledger = new GraphResourceLedger();
      ledger.own(new Recording("programs", calls));
      ledger.own(new Recording("buffers", calls));
      ledger.onClose(() -> calls.add("images"));

      ledger.close();

      assertEquals(List.of("programs", "buffers", "images"), calls, "登记顺序就是释放顺序");
   }

   @Test
   void anEmptyLedgerClosesQuietly() {
      GraphResourceLedger ledger = new GraphResourceLedger();

      ledger.close();

      assertEquals(0, ledger.size());
   }

   /** 有些族是按需创建的，创建处会把 null 交上来。 */
   @Test
   void aNullResourceIsIgnored() {
      List<String> calls = new ArrayList<>();
      GraphResourceLedger ledger = new GraphResourceLedger();
      ledger.own((AutoCloseable)null);
      ledger.own(new Recording("only", calls));

      assertEquals(1, ledger.size());
      ledger.close();

      assertEquals(List.of("only"), calls);
   }

   @Test
   void closeIsIdempotent() {
      List<String> calls = new ArrayList<>();
      GraphResourceLedger ledger = new GraphResourceLedger();
      ledger.own(new Recording("programs", calls));

      ledger.close();
      ledger.close();

      assertEquals(List.of("programs"), calls, "第二次必须是空操作");
      assertEquals(0, ledger.size(), "释放过的项不该留在账上");
   }

   /**
    * 原件是第一个抛出去就结束——而那时"已关闭"标记已经置位，于是后面每一族都泄漏且无法重试。
    * 这条钉住新的规则：全部释放，第一个异常抛出去，其余的挂在 suppressed 上。
    */
   @Test
   void oneFailureDoesNotStopTheRest() {
      List<String> calls = new ArrayList<>();
      RuntimeException boom = new RuntimeException("programs failed");
      GraphResourceLedger ledger = new GraphResourceLedger();
      ledger.own(new Recording("programs", calls, boom));
      ledger.own(new Recording("buffers", calls));

      RuntimeException thrown = assertThrows(RuntimeException.class, ledger::close);

      assertSame(boom, thrown, "抛出去的必须是第一个失败本身");
      assertEquals(List.of("programs", "buffers"), calls, "后面的族仍然必须被释放");
   }

   @Test
   void laterFailuresAreSuppressedOntoTheFirst() {
      List<String> calls = new ArrayList<>();
      RuntimeException first = new RuntimeException("first");
      RuntimeException second = new RuntimeException("second");
      GraphResourceLedger ledger = new GraphResourceLedger();
      ledger.own(new Recording("a", calls, first));
      ledger.own(new Recording("b", calls, second));
      ledger.own(new Recording("c", calls));

      RuntimeException thrown = assertThrows(RuntimeException.class, ledger::close);

      assertSame(first, thrown);
      assertEquals(1, thrown.getSuppressed().length);
      assertSame(second, thrown.getSuppressed()[0]);
      assertEquals(List.of("a", "b", "c"), calls);
   }

   /**
    * {@link AutoCloseable#close()} 声明的是受检的 {@code Exception}，而调用方（{@code GraphRenderer.close()}）
    * 什么都不声明。受检异常必须被包起来，否则这一层就得往上传一个它无权改变的签名。
    */
   @Test
   void aCheckedFailureIsWrapped() {
      Exception checked = new Exception("checked");
      GraphResourceLedger ledger = new GraphResourceLedger();
      ledger.own(new Recording("a", new ArrayList<>(), checked));

      RuntimeException thrown = assertThrows(RuntimeException.class, ledger::close);

      assertSame(checked, thrown.getCause());
   }

   private static final class Recording implements AutoCloseable {
      private final String name;
      private final List<String> calls;
      private final Exception failure;

      Recording(String name, List<String> calls) {
         this(name, calls, null);
      }

      Recording(String name, List<String> calls, Exception failure) {
         this.name = name;
         this.calls = calls;
         this.failure = failure;
      }

      @Override
      public void close() throws Exception {
         this.calls.add(this.name);

         if (this.failure != null) {
            throw this.failure;
         }
      }
   }
}
