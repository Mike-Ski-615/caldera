package com.caldera.shaders;

import com.mojang.logging.LogUtils;
import com.caldera.shaders.runtime.BackendStatus;
import com.caldera.shaders.runtime.CompositionRoot;
import com.caldera.shaders.runtime.MinecraftShaderHost;
import com.caldera.shaders.runtime.ShaderHost;
import com.caldera.shaders.runtime.ShaderRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

public final class CalderaShadersClient implements ClientModInitializer {
   private static final Logger LOGGER = LogUtils.getLogger();
   private boolean backendNoticeShown;

   public void onInitializeClient() {
      ShaderHost host = new MinecraftShaderHost();
      // 装配只有这一处：三个模块装的是**同一份** host，而"谁需要装、按什么顺序"收在 CompositionRoot 里。
      // host 由这里构造、不由装配根自己造，所以装配根本身在测试里可以用假 host 驱动。
      CompositionRoot.install(host);
      // init 是"第一次读盘"，与装配分开：它必须在装配之后。
      ShaderRuntime.init();
      ClientLifecycleEvents.CLIENT_STARTED.register(client -> ShaderRuntime.bootstrap());
      ClientLifecycleEvents.CLIENT_STOPPING.register((ClientLifecycleEvents.ClientStopping)(client) -> ShaderRuntime.close());
      ClientTickEvents.END_CLIENT_TICK.register((ClientTickEvents.EndTick)(client) -> {
         if (!this.backendNoticeShown && client.gui.overlay() == null && client.gui.screen() instanceof TitleScreen) {
            this.backendNoticeShown = true;
            if (!BackendStatus.vulkanActive()) {
               Screen parent = client.gui.screen();
               client.setScreenAndShow(new ConfirmScreen((retry) -> {
                  if (retry) {
                     client.options.preferredGraphicsBackend().set(PreferredGraphicsApi.VULKAN);
                     client.options.save();
                  }

                  client.setScreenAndShow(parent);
               }, Component.translatable("caldera.backend.title"), BackendStatus.unavailableMessage(), Component.translatable("caldera.backend.retry"), Component.translatable("caldera.backend.continue")));
            }

         }
      });
      LOGGER.info("Caldera Shaders initialized");
   }
}
