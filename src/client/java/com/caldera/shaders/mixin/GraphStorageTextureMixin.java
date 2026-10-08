package com.caldera.shaders.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.caldera.shaders.graph.StorageImageScope;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 让 Caldera 能分配**3D storage image**（体积雾、3D LUT 一类），办法是在 RenderPearl 的
 * {@code VulkanGpuTexture} 构造过程中改写它交给 Vulkan 的参数。
 * <p>
 * {@code VulkanGpuTexture} 的构造器只按 2D / 2D-array 建图，没有暴露 imageType、depth、arrayLayers
 * 这些参数，所以这里只能挂在它的 LWJGL 调用点上。
 * <p>
 * <b>三个魔法数已换成 {@link VK10} 的真常量。</b>原先它们写作裸字面量 {@code 8}、
 * {@code 2}、{@code 2}，读起来完全看不出是 Vulkan 枚举——而数值抄错是最难发现的一类错
 * （它不会编译失败，只会安静地建出错误的图像）。换常量消掉的是"数值写错"，<b>不解决</b>
 * "注入点失配"：下面五个 {@code @ModifyArg} 绑的是 LWJGL builder 的**调用形态**
 * （{@code target} 里那些 {@code VkImageCreateInfo.xxx(I)} 签名），一旦 RenderPearl 改成
 * 直接字段赋值或 {@code memPut*}，五处会**同时静默失效**。那一层常量救不了，只能靠版本升级时核对。
 */
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
      return StorageImageScope.active() ? usage | VK10.VK_IMAGE_USAGE_STORAGE_BIT : usage;
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
      return StorageImageScope.depth() > 1 ? VK10.VK_IMAGE_TYPE_3D : type;
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
      // 3D 图不能有 array layer：imageType 换成 3D 之后，arrayLayers 必须回到 1。
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
      // 与上面同一条约束：屏障的 layerCount 也必须一起改成 1，否则屏障范围与图像不一致。
      return StorageImageScope.depth() > 1 ? 1 : layers;
   }
}
