package com.caldera.shaders.graph;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;

/**
 * 门禁的统一失败出口：按标记串记一条 ERROR，然后让客户端退出。
 * <p>
 * 原先 4 个 smoke 各有一份 {@code fail(Minecraft, Throwable)}，四份里真正相同的就是这两行。
 * 收在这里是因为它们**必须成对**：门禁脚本读的是那条标记串，如果只记了日志却忘了退出，
 * 一次失败的跑会一直挂到超时，给出的是"没结果"而不是"失败了"。
 * <p>
 * 各家的标记串与 {@code finished} 标志没有收进来：前者是每类一个的常量，后者在同一类里
 * 也被"跑通过"的路径写，所以它不只是失败标志。于是 4 个类各自留一个三行的包装，
 * 只为给出自己的标记串——这次合并的收益因此是"那对语句只有一处"，而不是行数。
 */
final class SmokeFailure {
   private SmokeFailure() {
   }

   static void fail(Minecraft client, String marker, Throwable error) {
      LogUtils.getLogger().error(marker, error);
      client.stop();
   }
}