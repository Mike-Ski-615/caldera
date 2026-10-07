package com.caldera.shaders.runtime;

import com.caldera.shaders.graph.NativePackRuntime;
import com.caldera.shaders.render.shadow.DirectionalShadowRenderer;

/**
 * **装配根**：把一份游戏能力装进每一个需要它的模块。
 * <p>
 * 迁移前这是客户端入口里的三行相邻调用，而它们之间那条不变量——"装的是**同一个** host 实例，而且
 * 必须都装完才能开始用"——只存在于三行之间的注释里。加一个新模块要在那里插一行，改一次顺序要靠读。
 * 现在那里只剩一句 {@code CompositionRoot.install(host)}：
 * <ul>
 *    <li>{@link ShaderRuntime} —— 光影生命周期；</li>
 *    <li>{@link NativePackRuntime} —— 光影包运行时的门面；</li>
 *    <li>{@link DirectionalShadowRenderer} —— 阴影关卡要读的"有效渲染距离／设备纹理上限"。</li>
 * </ul>
 * 另外两个模块**不需要安装**，因为它们不持有需要注入的能力：{@code ReloadableResources} 在类初始化时
 * 由各模块自己登记，{@code ShadowPassScope} 只持有本帧的作用域状态。
 * <p>
 * <b>它不构造 host。</b>{@code new MinecraftShaderHost()} 仍在客户端入口——这样这个类在测试里能用
 * {@code FakeShaderHost} 驱动，而"哪一个是生产适配器"这件事留在入口。
 * <p>
 * <b>它也不调用 {@link ShaderRuntime#init()}。</b>装配是"把依赖接上"，而 init 是"第一次读盘"；把它
 * 塞进来会让这个方法做 I/O，也会让只想接线的地方被迫建目录。入口按 install → init 的顺序调用两者。
 */
public final class CompositionRoot {

   private CompositionRoot() {
   }

   /**
    * 把游戏能力装进每一个需要它的模块。可以重复调用，后一次会替换前一次装上的 host。
    * <p>
    * <b>顺序是行为的一部分：</b>{@code NativePackRuntime} 必须在阴影渲染器之前装上，因为后者要拿到
    * 前者的两条查询。它们是门面上的静态成员，所以这里把方法引用当 {@code BooleanSupplier} 交出去
    * ——{@code render.shadow} 因此不必 import {@code graph} 就能拿到"这个包会不会动阴影的投射者"
    * 与"阴影此刻开着没有"。
    */
   public static void install(ShaderHost host) {
      ShaderRuntime.install(host);
      NativePackRuntime.install(host);
      DirectionalShadowRenderer.install(host, NativePackRuntime::animatedShadowCasters,
            NativePackRuntime::shadowsEnabled);
   }
}
