package com.caldera.shaders.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.caldera.shaders.graph.StorageImageScope;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 3D storage image 的**视图**那一半：图建成 3D 之后，它的 view 也必须是 3D。
 * <p>
 * 与 {@link GraphStorageTextureMixin} 是一对——那边改 {@code VkImageCreateInfo}，这边改
 * {@code VkImageViewCreateInfo}。两边都靠 {@link StorageImageScope} 那个 ThreadLocal 判断
 * "当前这次分配是不是 3D 的"，所以只作用于作用域内的分配；普通 2D 纹理零影响。
 */
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
      // 原先写作裸字面量 2，看不出是 VK_IMAGE_VIEW_TYPE_3D。
      return StorageImageScope.depth() > 1 ? VK10.VK_IMAGE_VIEW_TYPE_3D : type;
   }
}
