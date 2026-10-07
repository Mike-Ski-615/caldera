package com.caldera.shaders.smoke;

import com.caldera.shaders.graph.GraphGpuSmoke;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;

/**
 * 开发期门禁的入口点。这个类只做一件事：把 {@link GraphGpuSmoke} 挂到客户端 tick 上。
 * <p>
 * 它之所以存在，是为了让**生产代码不再需要知道门禁的存在**。在 0.5.1 里这一行是
 * {@code CalderaShadersClient} 里的一条无条件调用，于是那 5 个 smoke 类必须留在
 * {@code src/client/java} 里、跟着 jar 一起发出去。现在调用方向反过来：
 * 门禁这个**独立的 mod**（id {@code caldera-dev}）在自己的入口点里注册自己，
 * 生产侧一个字都不提它。
 * <p>
 * 顺带的收益：shipped 构建里再也没有那 5 次 {@code System.getProperty} 的每 tick 查询
 * ——{@code GraphGpuSmoke.tick} 的每个分支都要先读一次系统属性，而那些分支只在开发期才可能为真。
 * <p>
 * 这个 mod 不在 jar 里：Loom 的 jar 只装 main + client 两个源码集的输出
 * （见 {@code MinecraftSourceSets.Split.evaluate}）。
 */
public final class CalderaDevHarness implements ClientModInitializer {
   private static final Logger LOGGER = LogUtils.getLogger();

   public void onInitializeClient() {
      ClientTickEvents.END_CLIENT_TICK.register((ClientTickEvents.EndTick)(client) -> GraphGpuSmoke.tick(client));
      LOGGER.info("Caldera dev harness installed");
   }
}