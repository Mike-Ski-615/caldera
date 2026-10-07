package com.caldera.shaders.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.caldera.shaders.graph.StorageImageScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin({VulkanGpuTexture.class})
public abstract class GraphStorageTextureMixin {
   @ModifyArg(
      method = {"<init>"},
      at = {@At(
   value = "INVOKE",
   target = "Lorg/lwjgl/vulkan/VkImageCreateInfo;usage(I)Lorg/lwjgl/vulkan/VkImageCreateInfo;"
)},
      index = 0
   )
   private int caldera$storageUsage(int usage) {
      return StorageImageScope.active() ? usage | 8 : usage;
   }

   @ModifyArg(
      method = {"<init>"},
      at = {@At(
   value = "INVOKE",
   target = "Lorg/lwjgl/vulkan/VkImageCreateInfo;imageType(I)Lorg/lwjgl/vulkan/VkImageCreateInfo;"
)},
      index = 0
   )
   private int caldera$imageDimension(int type) {
      return StorageImageScope.depth() > 1 ? 2 : type;
   }

   @ModifyArg(
      method = {"<init>"},
      at = {@At(
   value = "INVOKE",
   target = "Lorg/lwjgl/vulkan/VkExtent3D;set(III)Lorg/lwjgl/vulkan/VkExtent3D;"
)},
      index = 2
   )
   private int caldera$imageDepth(int depth) {
      return StorageImageScope.depth() > 1 ? StorageImageScope.depth() : depth;
   }

   @ModifyArg(
      method = {"<init>"},
      at = {@At(
   value = "INVOKE",
   target = "Lorg/lwjgl/vulkan/VkImageCreateInfo;arrayLayers(I)Lorg/lwjgl/vulkan/VkImageCreateInfo;"
)},
      index = 0
   )
   private int caldera$volumeLayers(int layers) {
      return StorageImageScope.depth() > 1 ? 1 : layers;
   }

   @ModifyArg(
      method = {"<init>"},
      at = {@At(
   value = "INVOKE",
   target = "Lorg/lwjgl/vulkan/VkImageSubresourceRange;layerCount(I)Lorg/lwjgl/vulkan/VkImageSubresourceRange;"
)},
      index = 0
   )
   private int caldera$volumeBarrierLayers(int layers) {
      return StorageImageScope.depth() > 1 ? 1 : layers;
   }
}
