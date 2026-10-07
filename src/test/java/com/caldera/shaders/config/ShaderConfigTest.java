package com.caldera.shaders.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShaderConfig} 现在是一个**纯 value type**：它只该管"选中的包 id 不能是空的"。
 * <p>
 * 它**不**管"那个包还在不在"——那要看扫描结果。两者原先混在同一个判断里，是候选 3 要拆开的
 * 东西之一：空值归一化在这里，存在性归一化在 {@code ShaderPackScanner.ScanResult} 上。
 * 这个测试文件小得几乎可笑，但它钉住的正是"这个类不该再长出 IO"这条线。
 */
class ShaderConfigTest {

   @Test
   void theDefaultIsEnabledOnTheBuiltinPack() {
      ShaderConfig config = new ShaderConfig();

      assertTrue(config.enabled());
      assertEquals(ShaderConfig.BUILTIN_PACK_ID, config.selectedPackId());
   }

   @Test
   void aBlankSelectedPackIdBecomesTheBuiltin() {
      assertEquals(ShaderConfig.BUILTIN_PACK_ID, new ShaderConfig(true, "").selectedPackId());
      assertEquals(ShaderConfig.BUILTIN_PACK_ID, new ShaderConfig(true, "   ").selectedPackId());
   }

   @Test
   void aNullSelectedPackIdBecomesTheBuiltin() {
      assertEquals(ShaderConfig.BUILTIN_PACK_ID, new ShaderConfig(true, null).selectedPackId());
   }

   /** 它只判空，不判存在：一个不存在的 id 必须原样留着，由扫描结果那边去收敛。 */
   @Test
   void anIdThatMerelyLooksUnknownIsKeptVerbatim() {
      assertEquals("Some Pack That Is Gone", new ShaderConfig(true, "Some Pack That Is Gone").selectedPackId());
   }

   @Test
   void withSelectionKeepsTheFlagAndRunsTheSameNormalisation() {
      ShaderConfig off = new ShaderConfig(true, "Nice").withSelection(false, "Nice");
      assertFalse(off.enabled());
      assertEquals("Nice", off.selectedPackId());

      assertEquals(ShaderConfig.BUILTIN_PACK_ID, new ShaderConfig().withSelection(true, "  ").selectedPackId());
   }
}
