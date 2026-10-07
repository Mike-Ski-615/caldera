package com.caldera.shaders.config;

/**
 * 全局光影设置：一个**纯 value type**，不认识磁盘、也不认识 {@code FabricLoader}。
 * <p>
 * 原先它不是这样的：{@code load()}／{@code save()} 与一个静态 GSON 就长在这个类里，
 * 自己拿 {@code FabricLoader.getInstance().getGameDir()} 拼路径。后果是 ADR-0003 开好的
 * {@code ShaderHost.loadConfig/saveConfig} 这个端口**只是转发进一个测不到的类**——
 * 内存实现能替掉整条链，唯独替不掉"设置到底怎么落盘"这一步。
 * <p>
 * 现在读写都归 {@link CalderaConfigFiles}，生产侧由
 * {@code MinecraftShaderHost.loadConfig/saveConfig} 接过去，测试里换成临时目录或内存。
 * 这个类只剩下它该管的那条不变量：**选中的包 id 不能是空的**，空了就回退内置包。
 * <p>
 * 注意它**不**管"那个包还在不在"：那要看扫描结果，属于 {@code ShaderPackScanner.ScanResult}。
 * 两者原先混在同一个判断里，是本候选要拆开的东西之一。
 */
public final class ShaderConfig {
   public static final String BUILTIN_PACK_ID = "__builtin__";
   private boolean enabled = true;
   private String selectedPackId = BUILTIN_PACK_ID;

   /** 默认设置：开启，选中内置包。也是所有"读不出来"路径的落点。 */
   public ShaderConfig() {
   }

   public ShaderConfig(boolean enabled, String selectedPackId) {
      this.enabled = enabled;
      this.selectedPackId = selectedPackId != null && !selectedPackId.isBlank() ? selectedPackId : BUILTIN_PACK_ID;
   }

   public boolean enabled() {
      return this.enabled;
   }

   public String selectedPackId() {
      return this.selectedPackId;
   }

   public ShaderConfig withSelection(boolean enabled, String id) {
      return new ShaderConfig(enabled, id);
   }
}
