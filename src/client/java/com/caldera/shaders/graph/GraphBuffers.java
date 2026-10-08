package com.caldera.shaders.graph;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.caldera.shaders.mixin.GraphDeviceAccessor;
import java.io.IOException;
import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

final class GraphBuffers implements AutoCloseable {
   private final VulkanDevice device = (VulkanDevice)((GraphDeviceAccessor)RenderSystem.getDevice()).caldera$backend();
   private final Map<String, Buffer[]> buffers = new LinkedHashMap<>();

   GraphBuffers(PackGraph graph) throws IOException {
      Set<String> used = new LinkedHashSet<>();
      graph.schedule().forEach((pass) -> pass.buffers().values().forEach((binding) -> used.add(PackGraph.current(binding.resource()))));

      try {
         MemoryStack stack = MemoryStack.stackPush();

         try {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(this.device.vkDevice().getPhysicalDevice(), properties);

            for(String name : used) {
               PackGraph.Buffer definition = (PackGraph.Buffer)graph.buffers().get(name);
               if (definition.bytes() > Integer.toUnsignedLong(properties.limits().maxStorageBufferRange()) || definition.bytes() > RenderSystem.getDevice().getDeviceInfo().limits().maxMemoryAllocationSize()) {
                  throw new IOException("Storage buffer exceeds GPU limit: " + name);
               }

               Buffer[] pair = new Buffer[definition.history() ? 2 : 1];
               this.buffers.put(name, pair);

               for(int i = 0; i < pair.length; ++i) {
                  LongBuffer handle = stack.mallocLong(1);
                  PointerBuffer allocation = stack.mallocPointer(1);
                  int result = Vma.vmaCreateBuffer(this.device.vma(), VkBufferCreateInfo.calloc(stack).sType$Default().size(definition.bytes()).usage(VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE), VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE), handle, allocation, (VmaAllocationInfo)null);
                  if (result != 0) {
                     throw new IOException("Cannot allocate storage buffer " + name + ": Vulkan " + result);
                  }

                  pair[i] = new Buffer(handle.get(0), allocation.get(0), definition.bytes());
               }
            }
         } catch (Throwable var14) {
             try {
                 stack.close();
             } catch (Throwable var13) {
                 var14.addSuppressed(var13);
             }

             throw var14;
         }

          stack.close();

      } catch (Exception failure) {
         this.close();
         throw new IOException("Storage buffers: " + failure.getMessage(), failure);
      }
   }

   /**
    * 取这个资源这一帧要用的那条缓冲。
    * <p>
    * {@code parity} 由调用方给，这个类**不再自己存一份**：原先它有一个 {@code int parity} 字段，
    * 由 {@link #beginFrame(int, boolean)} 拷进来，于是同一个索引有两份，必须手动保持同步。
    */
   Buffer resolve(String name, int parity) {
      Buffer[] pair = (Buffer[])this.buffers.get(PackGraph.current(name));
      return DoubleBuffer.current(pair, name, parity);
   }

   void beginFrame(int parity, boolean reset) {
      if (!this.buffers.isEmpty()) {
         VulkanCommandEncoder encoder = this.device.createCommandEncoder();
         MemoryStack stack = MemoryStack.stackPush();

         try {
            VkCommandBuffer commands = encoder.allocateAndBeginTransientCommandBuffer();
            ComputeProgram.barrier(commands, stack, 65536, 4096, 98304, 4096);

            for(Buffer[] pair : this.buffers.values()) {
               Buffer current = pair[pair.length == 1 ? 0 : parity];
               VK10.vkCmdFillBuffer(commands, current.handle, 0L, current.bytes, 0);
               if (reset && pair.length == 2) {
                  VK10.vkCmdFillBuffer(commands, pair[parity ^ 1].handle, 0L, pair[parity ^ 1].bytes, 0);
               }
            }

            ComputeProgram.barrier(commands, stack, 4096, 2048, 4096, 96);
            if (VK10.vkEndCommandBuffer(commands) != 0) {
               throw new IllegalStateException("Cannot finish buffer clear commands");
            }

            encoder.execute(commands);
         } catch (Throwable var10) {
            if (stack != null) {
               try {
                  stack.close();
               } catch (Throwable var9) {
                  var10.addSuppressed(var9);
               }
            }

            throw var10;
         }

          stack.close();

      }
   }

   public void close() {
      for(Buffer[] pair : this.buffers.values()) {
         for(Buffer buffer : pair) {
            if (buffer != null) {
               Vma.vmaDestroyBuffer(this.device.vma(), buffer.handle, buffer.allocation);
            }
         }
      }

      this.buffers.clear();
   }

   static record Buffer(long handle, long allocation, long bytes) {
   }
}
