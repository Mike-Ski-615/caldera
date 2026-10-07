package com.caldera.shaders.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.caldera.shaders.graph.StorageImageScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin({VulkanGpuTextureView.class})
public abstract class GraphStorageViewMixin {
   @ModifyArg(
      method = {"<init>"},
      at = {@At(
   value = "INVOKE",
   target = "Lorg/lwjgl/vulkan/VkImageViewCreateInfo;viewType(I)Lorg/lwjgl/vulkan/VkImageViewCreateInfo;"
)},
      index = 0
   )
   private int caldera$volumeView(int type) {
      return StorageImageScope.depth() > 1 ? 2 : type;
   }
}
