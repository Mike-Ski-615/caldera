package com.caldera.shaders.runtime;

import com.mojang.logging.LogUtils;
import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.pack.ShaderPackScanner;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;

/**
 * 光影生命周期。
 * <p>
 * 迁移前这是一整片静态字段，且直接向 {@code Minecraft}、{@code FabricLoader} 与
 * {@code NativePackRuntime} 伸手，导致整个生命周期无法被单元测试触碰。
 * 现在状态与流程都在**实例**里，游戏能力全部经由 {@link ShaderHost} 取得。
 * <p>
 * 唯一的静态入口是 {@link #install(ShaderHost)}；其余静态方法都只是门面，
 * 供 mixin 边沿调用，并且在实例尚未安装时给出**安全答案**（不重载、不启用、空包表），
 * 与迁移前那些静态字段的初值语义一致。
 */
public final class ShaderRuntime {
   private static final Logger LOGGER = LogUtils.getLogger();
   /** 实例尚未安装时对外返回的设置，等于迁移前静态字段的初值。 */
   private static final ShaderConfig FALLBACK_CONFIG = new ShaderConfig();
   /** 实例尚未安装时的空扫描结果。 */
   private static final ShaderPackScanner.ScanResult EMPTY_SCAN =
         new ShaderPackScanner.ScanResult(List.of(), List.of());
   private static ShaderRuntime instance;

   private final ShaderHost host;
   private ShaderConfig config = new ShaderConfig();
   /**
    * 最近一次扫描的**完整**结果，包含无法识别的条目。
    * <p>
    * 必须是缓存的：界面在每次窗口尺寸变化时都会重新 {@code init()}，那时它只应该重读这份结果，
    * 而不是再去读一遍盘。
    */
   private ShaderPackScanner.ScanResult scan = EMPTY_SCAN;
   private volatile boolean resourceReloading;

   private ShaderRuntime(ShaderHost host) {
      this.host = host;
   }

   /** 安装唯一实例。必须在 {@link #init()} 之前调用。 */
   public static void install(ShaderHost host) {
      instance = new ShaderRuntime(host);
   }

   /**
    * 卸载当前实例，回到"尚未安装"的初始状态。
    * <p>
    * 包级可见，供测试使用：它让同一个 JVM 里可以反复安装/卸载，
    * 从而能验证"实例未安装时静态门面给出安全答案"这条契约。
    */
   static void uninstall() {
      instance = null;
   }

   public static void init() {
      ShaderRuntime runtime = instance;
      if (runtime != null) {
         runtime.loadState();
      }
   }

   public static void bootstrap() {
      ShaderRuntime runtime = instance;
      if (runtime != null) {
         runtime.start();
      }
   }

   public static ShaderConfig config() {
      ShaderRuntime runtime = instance;
      return runtime == null ? FALLBACK_CONFIG : runtime.config;
   }

   public static List<ShaderPackScanner.AvailableShaderPack> packs() {
      return scanResult().supportedPacks();
   }

   /** 最近一次扫描的完整结果（含被忽略的条目）；界面读它，所以是缓存而不是重新读盘。 */
   public static ShaderPackScanner.ScanResult scanResult() {
      ShaderRuntime runtime = instance;
      return runtime == null ? EMPTY_SCAN : runtime.scan;
   }

   public static boolean shadersEnabled() {
      ShaderRuntime runtime = instance;
      return runtime != null && runtime.config.enabled() && runtime.host.vulkanActive();
   }

   public static boolean resourceReloading() {
      ShaderRuntime runtime = instance;
      return runtime != null && runtime.resourceReloading;
   }

   public static void reloadPacks() {
      ShaderRuntime runtime = instance;
      if (runtime != null) {
         runtime.scan = runtime.host.scanPacks();
      }
   }

   public static CompletableFuture<Void> applySelection(boolean enabled, String selectedPackId) {
      ShaderRuntime runtime = instance;
      if (runtime == null) {
         return notInstalled();
      }

      runtime.scan = runtime.host.scanPacks();
      String normalizedPackId = runtime.normalizePackId(selectedPackId);
      return runtime.apply(runtime.config.withSelection(enabled, normalizedPackId), true);
   }

   public static CompletableFuture<Void> applyConfig(ShaderConfig nextConfig, boolean forcePackRebuild) {
      ShaderRuntime runtime = instance;
      return runtime == null ? notInstalled() : runtime.apply(nextConfig, forcePackRebuild);
   }

   public static void close() {
      ShaderRuntime runtime = instance;
      if (runtime != null) {
         runtime.shutdown();
      }
   }

   /**
    * 重新读盘，并把设置与磁盘对齐。
    * <p>
    * 选中的包若已从磁盘上消失，就回退到内置包并让它真正生效——这正是「刷新」按钮要解决的问题，
    * 否则界面会挂着一个永远应用不成的选择。没有变化时返回的 future 立即完成。
    */
   public static CompletableFuture<Void> refresh() {
      ShaderRuntime runtime = instance;
      return runtime == null ? notInstalled() : runtime.realign();
   }

   /** 渲染器上一次失败的描述，从未失败时为 {@code null}。 */
   public static String rendererFailure() {
      ShaderRuntime runtime = instance;
      return runtime == null ? null : runtime.host.rendererFailure();
   }

   private static CompletableFuture<Void> notInstalled() {
      return CompletableFuture.failedFuture(new IllegalStateException("Caldera shader runtime is not installed"));
   }

   private void loadState() {
      this.config = this.host.loadConfig();
      this.host.ensurePackDirectory();
      this.scan = this.host.scanPacks();
      if (!ShaderPackScanner.isKnownPackId(this.config.selectedPackId(), this.scan.supportedPacks())) {
         this.config = this.config.withSelection(this.config.enabled(), "__builtin__");
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
      reloadRequired |= forcePackRebuild && this.shadersEnabledNow();
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

   /** 与 {@link #shadersEnabled()} 同义，但读的是本实例的设置。 */
   private boolean shadersEnabledNow() {
      return this.config.enabled() && this.host.vulkanActive();
   }

   private void shutdown() {
      this.host.closeRenderer();
      this.host.closeReloadableResources();
      this.scan = EMPTY_SCAN;
   }

   private CompletableFuture<Void> realign() {
      this.scan = this.host.scanPacks();
      if (ShaderPackScanner.isKnownPackId(this.config.selectedPackId(), this.scan.supportedPacks())) {
         return CompletableFuture.completedFuture(null);
      }

      LOGGER.info("Caldera shader pack {} is gone; falling back to the built-in pack", this.config.selectedPackId());
      return this.apply(this.config.withSelection(this.config.enabled(), "__builtin__"), true);
   }

   private String normalizePackId(String selectedPackId) {
      if (selectedPackId != null && !selectedPackId.isBlank() && !"__builtin__".equals(selectedPackId)) {
         return this.findPack(selectedPackId) == null ? "__builtin__" : selectedPackId;
      } else {
         return "__builtin__";
      }
   }

   private ShaderPackScanner.AvailableShaderPack findPack(String selectedPackId) {
      for(ShaderPackScanner.AvailableShaderPack pack : this.scan.supportedPacks()) {
         if (pack.id().equals(selectedPackId)) {
            return pack;
         }
      }

      return null;
   }
}
