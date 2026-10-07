package com.caldera.shaders.runtime;

import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.pack.ShaderPackScanner;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * "什么都没装"这一状态下的答案。
 * <p>
 * 与 {@code NotInstalledPackRuntime} 同一形状：不读盘、不重载、不关任何东西，空包表、两个
 * {@code false}、{@code null}，而三个异步入口返回一个**失败的 future**——那不是"安全答案"里的
 * 空值，而是一句明确的"初始化顺序错了"。
 * <p>
 * 两个哨兵常量（兜底设置与空扫描结果）原先住在门面里，现在住在这里：它们只被缺席这一侧使用，
 * 而"未安装时 {@code config()} 返回什么"是这一侧的事。
 * <p>
 * 这些答案与迁移前那些静态字段的初值逐字一致，钉住它们的用例在
 * {@code ShaderRuntimeLifecycleTest.staticAccessorsAreSafeBeforeAnInstanceIsInstalled} 里，
 * 它一个字都没改。
 */
final class NotInstalledShaderLifecycle implements ShaderLifecycle {

   /** 唯一的实例：它没有状态，谁拿到都一样。 */
   static final NotInstalledShaderLifecycle INSTANCE = new NotInstalledShaderLifecycle();

   /** 实例尚未安装时对外返回的设置，等于迁移前静态字段的初值。 */
   private static final ShaderConfig FALLBACK_CONFIG = new ShaderConfig();
   /** 实例尚未安装时的空扫描结果。 */
   private static final ShaderPackScanner.ScanResult EMPTY_SCAN = new ShaderPackScanner.ScanResult(List.of(), List.of());
   private static final String NOT_INSTALLED = "Caldera shader runtime is not installed";

   private NotInstalledShaderLifecycle() {
   }

   /** 空操作：没有设置可读、没有目录要建。 */
   @Override
   public void init() {
   }

   /** 空操作：没有东西可应用、也没有后端要警告。 */
   @Override
   public void bootstrap() {
   }

   @Override
   public ShaderConfig config() {
      return FALLBACK_CONFIG;
   }

   @Override
   public ShaderPackScanner.ScanResult scanResult() {
      return EMPTY_SCAN;
   }

   /** {@code null}：还没装好，没有"包在哪"这个答案。界面据此把"打开文件夹"当成空操作。 */
   @Override
   public Path packsRoot() {
      return null;
   }

   @Override
   public boolean shadersEnabled() {
      return false;
   }

   /** 二十多个 mixin 站点拿它当门闸，所以未安装时必须答 {@code false} 而不是抛。 */
   @Override
   public boolean resourceReloading() {
      return false;
   }

   /** 空操作：没有扫描结果可刷新。 */
   @Override
   public CompletableFuture<Void> applyConfig(ShaderConfig nextConfig, boolean forcePackRebuild) {
      return notInstalled();
   }

   /** 空操作：没有渲染器也没有可重载资源。 */
   @Override
   public void close() {
   }

   @Override
   public CompletableFuture<Void> refresh() {
      return notInstalled();
   }

   @Override
   public String rendererFailure() {
      return null;
   }

   /** 与迁移前 {@code ShaderRuntime.notInstalled()} 逐字一致，包括那句文案。 */
   private static CompletableFuture<Void> notInstalled() {
      return CompletableFuture.failedFuture(new IllegalStateException(NOT_INSTALLED));
   }
}
