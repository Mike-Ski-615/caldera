package com.caldera.shaders.runtime;

import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.pack.ShaderPackScanner;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * {@link ShaderRuntime} 那 13 个方法**转发到的表**：光影生命周期必须答得出来的全部问题。
 * <p>
 * 与 {@code PackRuntime} 同一个理由：门面的契约里有**一整个状态**是"什么都没装"——客户端初始化之前、
 * 以及每个测试 JVM 里都存在。迁移前那个状态是 {@code instance == null}，它的答案散在门面的 6 个 null
 * 分支与两个哨兵常量里；现在它是这张表的一个实现 {@link NotInstalledShaderLifecycle}。
 * <p>
 * 两个适配器是**真的**：生产由 {@link InstalledShaderLifecycle} 填充，缺席由那一个类填充。
 * <p>
 * <b>安全答案逐字未改</b>，包括那三个异步入口返回的失败 future 与它的报错文案
 * （{@code "Caldera shader runtime is not installed"}）。{@code ShaderRuntimeLifecycleTest} 里
 * 那条用例一个字都没改。
 */
interface ShaderLifecycle {

   /**
    * 读盘并把设置与磁盘对齐。必须在 {@link #install} 之后调用。
    * <p>
    * <b>完整契约：</b>读一次设置、确保包目录存在、扫一遍包目录；选中的包若已不在磁盘上，
    * 就**回退到内置包并把它写回磁盘**（不重载资源——启动时的那次应用由 {@link #bootstrap()} 负责）。
    * 回退是正常路径，因此不记日志。
    * <p>
    * 缺席实现（{@code NotInstalledShaderLifecycle}）里它是空操作——没有盘可读，也没有设置可写。
    */
   void init();

   /** 启动：应用当前设置，并在后端不是 Vulkan 时留下一条警告。 */
   void bootstrap();

   /** 当前生效的设置。 */
   ShaderConfig config();

   /** 最近一次扫描的**完整**结果（含被忽略的条目）；界面读它，所以是缓存而不是重新读盘。 */
   ShaderPackScanner.ScanResult scanResult();

   /**
    * 光影包目录在哪。
    * <p>
    * 界面"打开包文件夹"要它；没有第二个读者。未安装时是 {@code null}——那意味着"还没有装好，
    * 没有路径可开"，与 {@link #rendererFailure()} 的 {@code null} 同一风格。
    */
   Path packsRoot();

   /** 光影是否开着：开关 + 后端。 */
   boolean shadersEnabled();

   /**
    * 我们自己发起的那次资源重载是否尚未结束。
    * <p>
    * 二十多个 mixin 站点拿它当门闸，所以它在未安装时必须答 {@code false} 而不是抛。
    */
   boolean resourceReloading();

   /** 提交一份完整设置。 */
   CompletableFuture<Void> applyConfig(ShaderConfig nextConfig, boolean forcePackRebuild);

   /** 关掉一切：渲染器与可重载资源。 */
   void close();

   /**
    * 重新读盘；选中的包若已消失就回退到内置包并让它生效。
    * <p>
    * <b>完整契约，三件事都有外面看得见的差别：</b>
    * <ul>
    *   <li><b>没有变化时</b>：返回的 future **立即完成**，不写盘、不重载资源
    *       （这是「刷新」按钮最常见的路径，界面据此不必等）；</li>
    *   <li><b>有变化时</b>：记一条日志说明原来的包没了，然后回退到内置包、写盘、并**真正应用**
    *       （返回的 future 在资源重载结束时才完成）；</li>
    *   <li><b>正在重载时</b>：{@code apply} 会拒绝并返回一个**失败**的 future
    *       （"A shader reload is already in progress"）。</li>
    * </ul>
    * 缺席实现里它返回一个失败 future（"Caldera shader runtime is not installed"），
    * 它与上面第三条并列时容易读混：一个是"忙"，一个是"没有东西可刷"。
    */
   CompletableFuture<Void> refresh();

   /** 渲染器上一次失败的描述，从未失败时为 {@code null}。 */
   String rendererFailure();
}
