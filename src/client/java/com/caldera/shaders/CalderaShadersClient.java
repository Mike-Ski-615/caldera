package com.caldera.shaders;

import com.mojang.logging.LogUtils;
import com.caldera.shaders.graph.GraphGpuSmoke;
import com.caldera.shaders.runtime.BackendStatus;
import com.caldera.shaders.runtime.MinecraftShaderHost;
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
      ShaderRuntime.install(new MinecraftShaderHost());
      ShaderRuntime.init();
      ClientLifecycleEvents.CLIENT_STARTED.register(client -> ShaderRuntime.bootstrap());
      ClientLifecycleEvents.CLIENT_STOPPING.register((ClientLifecycleEvents.ClientStopping)(client) -> ShaderRuntime.close());
      ClientTickEvents.END_CLIENT_TICK.register((ClientTickEvents.EndTick)(client) -> {
         GraphGpuSmoke.tick(client);
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
