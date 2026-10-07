package com.caldera.shaders.graph;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

public final class SceneRenderPass implements RenderPass {
   private static SceneRenderPass active;
   private static boolean resuming;
   private RenderPass delegate;
   private final RenderPassDescriptor descriptor;
   private final List<Supplier<String>> debugGroups = new ArrayList<>();
   private final Map<String, Runnable> state = new LinkedHashMap<>();
   private boolean closed;
   private ByteBuffer constants;

   private SceneRenderPass(RenderPass delegate, RenderPassDescriptor descriptor) {
      this.delegate = delegate;
      this.descriptor = descriptor;
      active = this;
   }

   public static RenderPass wrap(RenderPass pass, RenderPassDescriptor descriptor) {
      if (!resuming && NativePackRuntime.usesNativeTransparency() && !descriptor.colorAttachments().isEmpty()) {
         RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
         RenderPassDescriptor.Attachment<Optional<Vector4fc>> first = descriptor.colorAttachments().getFirst();
         return (RenderPass)(first != null && first.textureView().texture() == main.getColorTexture() ? new SceneRenderPass(pass, descriptor) : pass);
      } else {
         return pass;
      }
   }

   public static void outside(Runnable action) {
      SceneRenderPass pass = active;
      if (pass == null) {
         action.run();
      } else {
         active = null;

         for(int i = 0; i < pass.debugGroups.size(); ++i) {
            pass.delegate.popDebugGroup();
         }

         pass.delegate.close();

         try {
            action.run();
         } finally {
            List colors = pass.descriptor.colorAttachments().stream().map((a) -> a == null ? null : new RenderPassDescriptor.Attachment(a.textureView(), Optional.empty())).toList();
            RenderPassDescriptor.Attachment depth = pass.descriptor.depthAttachment();
            RenderPassDescriptor resume = new RenderPassDescriptor(pass.descriptor.label(), colors, depth == null ? null : new RenderPassDescriptor.Attachment(depth.textureView(), OptionalDouble.empty()), pass.descriptor.renderArea());
            resuming = true;

            try {
               pass.delegate = RenderSystem.getDevice().createCommandEncoder().createRenderPass(resume);
            } finally {
               resuming = false;
            }

            active = pass;
            pass.debugGroups.forEach(pass.delegate::pushDebugGroup);
            pass.state.values().forEach(Runnable::run);
            if (pass.constants != null) {
               pass.delegate.pushConstants(pass.constants.duplicate());
            }

         }

      }
   }

   public void close() {
      if (!this.closed) {
         this.closed = true;
         if (active == this) {
            active = null;
         }

         this.delegate.close();
      }

   }

   public void pushDebugGroup(@NonNull Supplier<String> label) {
      this.debugGroups.add(label);
      this.delegate.pushDebugGroup(label);
   }

   public void popDebugGroup() {
      this.debugGroups.removeLast();
      this.delegate.popDebugGroup();
   }

   public void writeTimestamp(@NonNull GpuQueryPool pool, int index) {
      this.delegate.writeTimestamp(pool, index);
   }

   public void setPipeline(@NonNull CompiledRenderPipeline pipeline) {
      this.state.put("setPipeline", () -> this.delegate.setPipeline(pipeline));
      this.delegate.setPipeline(pipeline);
   }

   public void setUniform(@NonNull String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
      this.state.put("uniform:" + name, () -> this.delegate.setUniform(name, textureView, sampler));
      this.delegate.setUniform(name, textureView, sampler);
   }

   public void setUniform(@NonNull String name, @NonNull GpuBuffer value) {
      this.state.put("uniform:" + name, () -> this.delegate.setUniform(name, value));
      this.delegate.setUniform(name, value);
   }

   public void setUniform(@NonNull String name, @NonNull GpuBufferSlice value) {
      this.state.put("uniform:" + name, () -> this.delegate.setUniform(name, value));
      this.delegate.setUniform(name, value);
   }

   public void pushConstants(@NonNull ByteBuffer value) {
      if (this.constants == null || this.constants.capacity() < value.remaining()) {
         this.constants = ByteBuffer.allocateDirect(value.remaining());
      }

      this.constants.clear();
      this.constants.put(value.duplicate()).flip();
      this.delegate.pushConstants(value);
   }

   public void enableScissor(int x, int y, int width, int height) {
      this.state.put("scissor", (Runnable)() -> this.delegate.enableScissor(x, y, width, height));
      this.delegate.enableScissor(x, y, width, height);
   }

   public void disableScissor() {
      this.state.put("scissor", () -> this.delegate.disableScissor());
      this.delegate.disableScissor();
   }

   public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
      this.state.put("vertex:" + slot, () -> this.delegate.setVertexBuffer(slot, vertexBuffer));
      this.delegate.setVertexBuffer(slot, vertexBuffer);
   }

   public void setIndexBuffer(@NonNull GpuBuffer indexBuffer, @NonNull IndexType indexType) {
      this.state.put("setIndexBuffer", () -> this.delegate.setIndexBuffer(indexBuffer, indexType));
      this.delegate.setIndexBuffer(indexBuffer, indexType);
   }

   public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
      this.delegate.drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
   }

   public void multiDrawIndexed(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
      this.delegate.multiDrawIndexed(drawParameters, instanceCount, firstInstance, drawCount);
   }

   public void multiDrawIndexed(@NonNull PointerBuffer firstIndexOffsets, @NonNull IntBuffer indexCounts, @NonNull IntBuffer vertexOffsets, int drawCount) {
      this.delegate.multiDrawIndexed(firstIndexOffsets, indexCounts, vertexOffsets, drawCount);
   }

   public void drawIndexedIndirect(@NonNull GpuBufferSlice commands, int drawCount) {
      this.delegate.drawIndexedIndirect(commands, drawCount);
   }

   public <T> void drawMultipleIndexed(@NonNull Collection<RenderPass.Draw<T>> draws, @Nullable GpuBuffer defaultIndexBuffer, @Nullable IndexType defaultIndexType, @NonNull Collection<String> dynamicUniforms, @NonNull T uniformArgument) {
      this.delegate.drawMultipleIndexed(draws, defaultIndexBuffer, defaultIndexType, dynamicUniforms, uniformArgument);
   }

   public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
      this.delegate.draw(vertexCount, instanceCount, firstVertex, firstInstance);
   }

   public void multiDraw(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
      this.delegate.multiDraw(drawParameters, instanceCount, firstInstance, drawCount);
   }

   public void multiDraw(@NonNull IntBuffer firstVertices, @NonNull IntBuffer vertexCounts, int drawCount) {
      this.delegate.multiDraw(firstVertices, vertexCounts, drawCount);
   }

   public void drawIndirect(@NonNull GpuBufferSlice commands, int drawCount) {
      this.delegate.drawIndirect(commands, drawCount);
   }
}
