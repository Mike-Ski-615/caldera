package com.caldera.shaders.graph;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.caldera.shaders.mixin.GraphDeviceAccessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageBlit;

final class GraphMipmaps {
   static int filter(GpuFormat format) {
      VulkanDevice device = (VulkanDevice)((GraphDeviceAccessor)RenderSystem.getDevice()).caldera$backend();
      MemoryStack stack = MemoryStack.stackPush();

      int var6;
      try {
         VkFormatProperties properties = VkFormatProperties.calloc(stack);
         VK10.vkGetPhysicalDeviceFormatProperties(device.vkDevice().getPhysicalDevice(), VulkanConst.toVk(format), properties);
         int features = properties.optimalTilingFeatures();
         int required = 3072;
         if ((features & required) != required) {
            throw new IllegalArgumentException("GPU cannot generate mipmaps for " + String.valueOf(format));
         }

         var6 = (features & 4096) != 0 ? 1 : 0;
      } catch (Throwable var8) {
          try {
              stack.close();
          } catch (Throwable var7) {
              var8.addSuppressed(var7);
          }

          throw var8;
      }

       stack.close();

       return var6;
   }

   static void generate(GpuTexture texture, int filter) {
      if (texture.getMipLevels() > 1) {
         VulkanDevice device = (VulkanDevice)((GraphDeviceAccessor)RenderSystem.getDevice()).caldera$backend();
         VulkanCommandEncoder encoder = device.createCommandEncoder();
         MemoryStack stack = MemoryStack.stackPush();

         try {
            VkCommandBuffer commands = encoder.allocateAndBeginTransientCommandBuffer();
            ComputeProgram.barrier(commands, stack, 65536, 4096, 65536, 6144);
            int w = texture.getWidth(0);
            int h = texture.getHeight(0);
            int d = texture.getDepthOrLayers();
            long image = ((VulkanGpuTexture)texture).vkImage();
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);

            for(int mip = 1; mip < texture.getMipLevels(); ++mip) {
               int nextW = Math.max(1, w / 2);
               int nextH = Math.max(1, h / 2);
               int nextD = Math.max(1, d / 2);
               region.srcSubresource().aspectMask(1).mipLevel(mip - 1).baseArrayLayer(0).layerCount(1);
               region.dstSubresource().aspectMask(1).mipLevel(mip).baseArrayLayer(0).layerCount(1);
               region.srcOffsets(1).set(w, h, d);
               region.dstOffsets(1).set(nextW, nextH, nextD);
               VK10.vkCmdBlitImage(commands, image, 1, image, 1, region, filter);
               ComputeProgram.barrier(commands, stack, 4096, 4096, 4096, 6144);
               w = nextW;
               h = nextH;
               d = nextD;
            }

            ComputeProgram.barrier(commands, stack, 4096, 65536, 4096, 98304);
            if (VK10.vkEndCommandBuffer(commands) != 0) {
               throw new IllegalStateException("Cannot finish mip generation commands");
            }

            encoder.execute(commands);
         } catch (Throwable var17) {
             try {
                 stack.close();
             } catch (Throwable var16) {
                 var17.addSuppressed(var16);
             }

             throw var17;
         }

          stack.close();

      }
   }
}
