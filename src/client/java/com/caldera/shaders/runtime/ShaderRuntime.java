package com.caldera.shaders.runtime;

import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.pack.ShaderPackScanner;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * 光影生命周期的**门面**：mixin 与界面能摸到的全部入口。
 * <p>
 * 迁移前这是一整片静态字段，且直接向 {@code Minecraft}、{@code FabricLoader} 与
 * {@code NativePackRuntime} 伸手，导致整个生命周期无法被单元测试触碰。第一轮把状态与流程搬进了实例，
 * 这一轮把**实例本身**也搬走了：门面手上拿的是一个 {@link ShaderLifecycle}，"什么都没装"从
 * {@code instance == null} 变成了一个实现了同一张表的适配器 {@link NotInstalledShaderLifecycle}。
 * <p>
 * 于是这个类只剩一行行转发——"未安装时到底答什么"有一处可读、一处可测，而不是散在 6 个 null 分支与
 * 两个哨兵常量里。
 * <p>
 * {@link #install} 由 {@code CompositionRoot} 调用（它同时装上 {@code NativePackRuntime} 与
 * {@code DirectionalShadowRenderer}）；{@link #init} 必须在那之后调用。
 */
public final class ShaderRuntime {

   /**
    * 当前这一份实现。**永不为 null**——未安装是 {@link NotInstalledShaderLifecycle#INSTANCE} 这个
    * 适配器，不是缺失。
    */
   private static ShaderLifecycle current = NotInstalledShaderLifecycle.INSTANCE;

   private ShaderRuntime() {
   }

   /** 安装唯一实例。必须在 {@link #init()} 之前、由 {@code CompositionRoot} 调用。 */
   public static void install(ShaderHost host) {
      current = new InstalledShaderLifecycle(host);
   }

   /**
    * 卸载当前实例，回到"尚未安装"的状态。
    * <p>
    * 包级可见，供测试使用：它让同一个 JVM 里可以反复安装/卸载，
    * 从而能验证"实例未安装时静态门面给出安全答案"这条契约。
    */
   static void uninstall() {
      current = NotInstalledShaderLifecycle.INSTANCE;
   }

   public static void init() {
      current.init();
   }

   public static void bootstrap() {
      current.bootstrap();
   }

   public static ShaderConfig config() {
      return current.config();
   }

   /** 最近一次扫描的完整结果（含被忽略的条目）；界面读它，所以是缓存而不是重新读盘。 */
   public static ShaderPackScanner.ScanResult scanResult() {
      return current.scanResult();
   }

   /** 光影包目录在哪；未安装时为 {@code null}。界面"打开包文件夹"要它。 */
   public static Path packsRoot() {
      return current.packsRoot();
   }

   public static boolean shadersEnabled() {
      return current.shadersEnabled();
   }

   public static boolean resourceReloading() {
      return current.resourceReloading();
   }

   public static CompletableFuture<Void> applyConfig(ShaderConfig nextConfig, boolean forcePackRebuild) {
      return current.applyConfig(nextConfig, forcePackRebuild);
   }

   public static void close() {
      current.close();
   }

   /**
    * 重新读盘，并把设置与磁盘对齐。
    * <p>
    * 选中的包若已从磁盘上消失，就回退到内置包并让它真正生效——这正是「刷新」按钮要解决的问题，
    * 否则界面会挂着一个永远应用不成的选择。没有变化时返回的 future 立即完成。
    */
   public static CompletableFuture<Void> refresh() {
      return current.refresh();
   }

   /** 渲染器上一次失败的描述，从未失败时为 {@code null}。 */
   public static String rendererFailure() {
      return current.rendererFailure();
   }
}
