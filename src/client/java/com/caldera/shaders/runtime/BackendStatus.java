package com.caldera.shaders.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.util.Locale;
import net.minecraft.network.chat.Component;

public final class BackendStatus {
   private BackendStatus() {
   }

   public static boolean vulkanActive() {
      GpuDevice device = RenderSystem.tryGetDevice();
      return device != null && device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan");
   }

   public static Component unavailableMessage() {
      return Component.translatable("caldera.backend.unavailable");
   }
}
