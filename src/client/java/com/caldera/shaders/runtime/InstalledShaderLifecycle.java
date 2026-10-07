package com.caldera.shaders.runtime;

import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.pack.ShaderPackScanner;
import com.mojang.logging.LogUtils;
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
   public List<ShaderPackScanner.AvailableShaderPack> packs() {
      return this.scan.supportedPacks();
   }

   @Override
   public ShaderPackScanner.ScanResult scanResult() {
      return this.scan;
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
   public void reloadPacks() {
      this.scan = this.host.scanPacks();
   }

   @Override
   public CompletableFuture<Void> applySelection(boolean enabled, String selectedPackId) {
      this.scan = this.host.scanPacks();
      String normalizedPackId = this.normalizePackId(selectedPackId);
      return this.apply(this.config.withSelection(enabled, normalizedPackId), true);
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
    * 选中的包若已从磁盘上消失，就回退到内置包并让它真正生效——这正是「刷新」按钮要解决的问题，
    * 否则界面会挂着一个永远应用不成的选择。没有变化时返回的 future 立即完成。
    */
   private void loadState() {
      this.config = this.host.loadConfig();
      this.host.ensurePackDirectory();
      this.scan = this.host.scanPacks();
      // "选中的包必须还在，否则回退内置"只剩这一个判断了——归一化本身在 ScanResult 上。
      String resolved = this.scan.resolveSelection(this.config.selectedPackId());
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
      this.scan = this.host.scanPacks();
      String resolved = this.scan.resolveSelection(this.config.selectedPackId());
      if (resolved.equals(this.config.selectedPackId())) {
         return CompletableFuture.completedFuture(null);
      }

      LOGGER.info("Caldera shader pack {} is gone; falling back to the built-in pack", this.config.selectedPackId());
      return this.apply(this.config.withSelection(this.config.enabled(), resolved), true);
   }

   /**
    * 把一个包 id 收敛到"当前扫描结果里真实存在的那个，否则内置包"。
    * <p>
    * 判断本身已经收在 {@link ShaderPackScanner.ScanResult#resolveSelection}；这里保留一个私有入口
    * 只是因为它是本类的调用点，读起来比在 {@code applySelection} 里现取扫描结果清楚。
    */
   private String normalizePackId(String selectedPackId) {
      return this.scan.resolveSelection(selectedPackId);
   }
}
