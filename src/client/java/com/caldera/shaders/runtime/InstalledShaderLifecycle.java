package com.caldera.shaders.runtime;

import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.pack.ShaderPackScanner;
import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;

/**
 * "装好之后"的那一份实现：光影生命周期的全部状态与流程。
 * <p>
 * 它就是把迁移前 {@code ShaderRuntime} 的实例部分整块搬过来（第一轮从静态字段搬进实例、这一轮从门面
 * 搬到这里），唯一的区别是自己不再兼任门面。游戏能力仍然全部经由 {@link ShaderHost} 取得，跨过它的
 * 两端都不需要 {@code Minecraft}、不需要 GPU。
 */
final class InstalledShaderLifecycle implements ShaderLifecycle {
   private static final Logger LOGGER = LogUtils.getLogger();

   private final ShaderHost host;
   private ShaderConfig config = new ShaderConfig();
   /**
    * 最近一次扫描的**完整**结果，包含无法识别的条目。
    * <p>
    * 必须是缓存的：界面在每次窗口尺寸变化时都会重新 {@code init()}，那时它只应该重读这份结果，
    * 而不是再去读一遍盘。初值是空结果——未安装时那一份由 {@link NotInstalledShaderLifecycle} 提供，
    * 空结果的**身份**没有任何地方依赖。
    */
   private ShaderPackScanner.ScanResult scan = new ShaderPackScanner.ScanResult(List.of(), List.of());
   private volatile boolean resourceReloading;

   InstalledShaderLifecycle(ShaderHost host) {
      this.host = host;
   }

   @Override
   public void init() {
      this.loadState();
   }

   @Override
   public void bootstrap() {
      this.start();
   }

   @Override
   public ShaderConfig config() {
      return this.config;
   }

   @Override
   public ShaderPackScanner.ScanResult scanResult() {
      return this.scan;
   }

   @Override
   public Path packsRoot() {
      return this.host.packsRoot();
   }

   @Override
   public boolean shadersEnabled() {
      return this.config.enabled() && this.host.vulkanActive();
   }

   @Override
   public boolean resourceReloading() {
      return this.resourceReloading;
   }

   @Override
   public CompletableFuture<Void> applyConfig(ShaderConfig nextConfig, boolean forcePackRebuild) {
      return this.apply(nextConfig, forcePackRebuild);
   }

   @Override
   public void close() {
      this.shutdown();
   }

   @Override
   public CompletableFuture<Void> refresh() {
      return this.realign();
   }

   @Override
   public String rendererFailure() {
      return this.host.rendererFailure();
   }

   // ---------------------------------------------------------------- 流程

   /**
    * 重新读盘，并把设置与磁盘对齐。
    * <p>
    * 它**总是**把归一化后的选择写回磁盘——那是启动时的语义，即使没有变化也写一次。
    * 原来的行为如此，这里不加判断也不记日志：启动时回退到内置包是**正常路径**，不是需要用户知道的事件
    * （换包时才有 {@link #realign} 的那条日志）。
    */
   private void loadState() {
      this.config = this.host.loadConfig();
      this.host.ensurePackDirectory();
      String resolved = this.rescan();
      if (!resolved.equals(this.config.selectedPackId())) {
         this.config = this.config.withSelection(this.config.enabled(), resolved);
         this.host.saveConfig(this.config);
      }
   }

   private void start() {
      this.apply(this.config, true).whenComplete((ignored, failure) -> {
         if (failure != null) {
            LOGGER.error("Failed to load the selected Caldera shader pack", failure);
         }

      });
      if (!this.host.vulkanActive()) {
         LOGGER.warn("Caldera shaders paused: Minecraft is using a non-Vulkan backend. See Video Settings to select Vulkan and restart; unsupported devices can continue with OpenGL.");
      }

      LOGGER.info("Caldera Shaders bootstrap complete: enabled={}, selectedPack={}, externalPacks={}", new Object[]{this.config.enabled(), this.config.selectedPackId(), this.scan.supportedPacks().size()});
   }

   private CompletableFuture<Void> apply(ShaderConfig nextConfig, boolean forcePackRebuild) {
      if (this.resourceReloading) {
         return CompletableFuture.failedFuture(new IllegalStateException("A shader reload is already in progress"));
      }

      ShaderConfig previousConfig = this.config;
      ShaderHost.PreparedRenderer candidate = null;

      try {
         candidate = this.host.prepareRenderer(nextConfig);
         ShaderHost.PreparedRenderer prepared = candidate;
         return this.commit(nextConfig, forcePackRebuild).whenComplete((ignored, failurex) -> {
            if (failurex == null) {
               prepared.activate(nextConfig.selectedPackId());
            } else {
               prepared.close();
               this.config = previousConfig;
               this.host.saveConfig(this.config);
            }

         });
      } catch (Exception failure) {
         if (candidate != null) {
            candidate.close();
         }

         this.resourceReloading = false;
         this.config = previousConfig;
         LOGGER.error("Failed to apply Caldera shader settings", failure);
         return CompletableFuture.failedFuture(failure);
      }
   }

   private CompletableFuture<Void> commit(ShaderConfig nextConfig, boolean forcePackRebuild) {
      this.config = nextConfig;
      boolean reloadRequired = this.host.detachLegacyPack();
      reloadRequired |= forcePackRebuild && this.shadersEnabled();
      this.host.saveConfig(this.config);
      this.host.saveOptions();
      if (!reloadRequired) {
         this.host.requestGeometryRebuild();
         return CompletableFuture.completedFuture(null);
      }

      this.resourceReloading = true;
      this.host.closeReloadableResources();
      return this.host.reloadResources().handleAsync((ignored, throwable) -> {
         Void result;
         try {
            this.host.requestGeometryRebuild();
            if (throwable != null) {
               throw new CompletionException(throwable);
            }

            result = (Void)null;
         } finally {
            this.resourceReloading = false;
         }

         return result;
      }, this.host.clientThread());
   }

   private void shutdown() {
      this.host.closeRenderer();
      this.host.closeReloadableResources();
      this.scan = new ShaderPackScanner.ScanResult(List.of(), List.of());
   }

   private CompletableFuture<Void> realign() {
      String resolved = this.rescan();
      if (resolved.equals(this.config.selectedPackId())) {
         return CompletableFuture.completedFuture(null);
      }

      LOGGER.info("Caldera shader pack {} is gone; falling back to the built-in pack", this.config.selectedPackId());
      return this.apply(this.config.withSelection(this.config.enabled(), resolved), true);
   }

   /**
    * 重新扫一遍包目录，并把选中的包 id 归一化成"要么它还在，要么内置包"。**返回归一化后的 id。**
    * <p>
    * <b>这是启动与刷新共用的那一段。</b>两者原先各写一遍"扫盘 → {@code resolveSelection}"，
    * 差别只在"变了之后做什么"：启动静默写盘、刷新记日志并让它生效。现在那个差别留在各自的调用方里
    * （它们是两条不同的政策，不是同一个判断的两种写法），而这前两步只写一遍。
    * <p>
    * 归一化本身仍在 {@link ShaderPackScanner.ScanResult#resolveSelection}（它才知道有哪些包）；
    * 这里负责的是"重新读盘"与"把结果落到 {@link #scan}"。
    */
   private String rescan() {
      this.scan = this.host.scanPacks();
      return this.scan.resolveSelection(this.config.selectedPackId());
   }
}
