package com.caldera.shaders.graph;

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
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

/**
 * {@link RenderPass} 的**适配器面**：游戏拿到的就是它。
 * <p>
 * 它做两件事，而且只有两件事：
 * <ol>
 *    <li><b>转发</b>。{@code RenderPass} 的 22 个方法里有 10 个（全部 draw 与 {@code writeTimestamp}）
 *        只是把调用原样交给真正的那个 pass。</li>
 *    <li><b>录制</b>。另外 8 个改状态的方法（{@code setPipeline}、3 个 {@code setUniform}、
 *        两个 scissor、{@code setVertexBuffer}、{@code setIndexBuffer}）在转发的同时把"怎么重做一遍"
 *        记下来，等恢复的时候贴回新 pass 上。{@code pushDebugGroup}/{@code pushConstants} 同理。</li>
 * </ol>
 * <p>
 * <b>为什么键要这么取：</b>记的是**最终状态**而不是调用序列，所以同一个键后写的覆盖先写的。
 * 两个 scissor 方法共用键 {@code "scissor"}（中间开开关关只有最后一个算数），顶点缓冲按 slot 分键，
 * uniform 按名字分键。{@link LinkedHashMap} 保证重放顺序稳定。
 * <p>
 * <b>为什么重放的 lambda 读的是 {@code this.delegate} 而不是捕获一个局部变量：</b>
 * 恢复时先把这个字段换成新建的 pass，再重放，于是同一批 lambda 自然贴到新 pass 上。
 * 捕获局部变量会把状态贴回已经关掉的那个。
 */
final class SplitScenePass implements RenderPass, SuspendedScenePass {

   private final RenderPassDescriptor descriptor;
   private final Function<RenderPassDescriptor, RenderPass> recreate;
   private final List<Supplier<String>> debugGroups = new ArrayList<>();
   private final Map<String, Runnable> state = new LinkedHashMap<>();
   private RenderPass delegate;
   private ByteBuffer constants;
   private boolean closed;

   SplitScenePass(RenderPass delegate, RenderPassDescriptor descriptor, Function<RenderPassDescriptor, RenderPass> recreate) {
      this.delegate = delegate;
      this.descriptor = descriptor;
      this.recreate = recreate;
      SceneRenderPass.begin(this);
   }

   // ---------------------------------------------------------------- 挂起与恢复

   @Override
   public void suspend() {
      for(int i = 0; i < this.debugGroups.size(); ++i) {
         this.delegate.popDebugGroup();
      }

      this.delegate.close();
   }

   @Override
   public void resume() {
      // 先换 delegate 再重放：state 里的 lambda 读的是这个字段，所以它们会贴到新 pass 上。
      this.delegate = SceneRenderPass.createForResume(() -> this.recreate.apply(SceneRenderPass.resumeDescriptor(this.descriptor)));
      this.debugGroups.forEach(this.delegate::pushDebugGroup);
      this.state.values().forEach(Runnable::run);

      if (this.constants != null) {
         this.delegate.pushConstants(this.constants.duplicate());
      }

   }

   // ---------------------------------------------------------------- 有真实内容的那几个

   @Override
   public void close() {
      if (!this.closed) {
         this.closed = true;
         SceneRenderPass.end(this);
         this.delegate.close();
      }
   }

   @Override
   public void pushDebugGroup(@NonNull Supplier<String> label) {
      this.debugGroups.add(label);
      this.delegate.pushDebugGroup(label);
   }

   @Override
   public void popDebugGroup() {
      this.debugGroups.removeLast();
      this.delegate.popDebugGroup();
   }

   /**
    * 常量要先拷一份再转发：调用方给的那个 {@code ByteBuffer} 是复用的，重放时它早被改过了。
    * 容量不够才重新分配，否则原地覆盖。
    */
   @Override
   public void pushConstants(@NonNull ByteBuffer value) {
      if (this.constants == null || this.constants.capacity() < value.remaining()) {
         this.constants = ByteBuffer.allocateDirect(value.remaining());
      }

      this.constants.clear();
      this.constants.put(value.duplicate()).flip();
      this.delegate.pushConstants(value);
   }

   // ---------------------------------------------------------------- 录制 + 转发

   @Override
   public void setPipeline(@NonNull CompiledRenderPipeline pipeline) {
      this.state.put("setPipeline", () -> this.delegate.setPipeline(pipeline));
      this.delegate.setPipeline(pipeline);
   }

   @Override
   public void setUniform(@NonNull String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
      this.state.put("uniform:" + name, () -> this.delegate.setUniform(name, textureView, sampler));
      this.delegate.setUniform(name, textureView, sampler);
   }

   @Override
   public void setUniform(@NonNull String name, @NonNull GpuBuffer value) {
      this.state.put("uniform:" + name, () -> this.delegate.setUniform(name, value));
      this.delegate.setUniform(name, value);
   }

   @Override
   public void setUniform(@NonNull String name, @NonNull GpuBufferSlice value) {
      this.state.put("uniform:" + name, () -> this.delegate.setUniform(name, value));
      this.delegate.setUniform(name, value);
   }

   /** 与 {@link #disableScissor()} 共用键：只有最后那次算数。 */
   @Override
   public void enableScissor(int x, int y, int width, int height) {
      this.state.put("scissor", () -> this.delegate.enableScissor(x, y, width, height));
      this.delegate.enableScissor(x, y, width, height);
   }

   /** 与 {@link #enableScissor(int, int, int, int)} 共用键：只有最后那次算数。 */
   @Override
   public void disableScissor() {
      this.state.put("scissor", () -> this.delegate.disableScissor());
      this.delegate.disableScissor();
   }

   @Override
   public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
      this.state.put("vertex:" + slot, () -> this.delegate.setVertexBuffer(slot, vertexBuffer));
      this.delegate.setVertexBuffer(slot, vertexBuffer);
   }

   @Override
   public void setIndexBuffer(@NonNull GpuBuffer indexBuffer, @NonNull IndexType indexType) {
      this.state.put("setIndexBuffer", () -> this.delegate.setIndexBuffer(indexBuffer, indexType));
      this.delegate.setIndexBuffer(indexBuffer, indexType);
   }

   // ---------------------------------------------------------------- 纯转发

   @Override
   public void writeTimestamp(@NonNull GpuQueryPool pool, int index) {
      this.delegate.writeTimestamp(pool, index);
   }

   @Override
   public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
      this.delegate.drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
   }

   @Override
   public void multiDrawIndexed(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
      this.delegate.multiDrawIndexed(drawParameters, instanceCount, firstInstance, drawCount);
   }

   @Override
   public void multiDrawIndexed(@NonNull PointerBuffer firstIndexOffsets, @NonNull IntBuffer indexCounts, @NonNull IntBuffer vertexOffsets, int drawCount) {
      this.delegate.multiDrawIndexed(firstIndexOffsets, indexCounts, vertexOffsets, drawCount);
   }

   @Override
   public void drawIndexedIndirect(@NonNull GpuBufferSlice commands, int drawCount) {
      this.delegate.drawIndexedIndirect(commands, drawCount);
   }

   @Override
   public <T> void drawMultipleIndexed(@NonNull Collection<RenderPass.Draw<T>> draws, @Nullable GpuBuffer defaultIndexBuffer, @Nullable IndexType defaultIndexType, @NonNull Collection<String> dynamicUniforms, @NonNull T uniformArgument) {
      this.delegate.drawMultipleIndexed(draws, defaultIndexBuffer, defaultIndexType, dynamicUniforms, uniformArgument);
   }

   @Override
   public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
      this.delegate.draw(vertexCount, instanceCount, firstVertex, firstInstance);
   }

   @Override
   public void multiDraw(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
      this.delegate.multiDraw(drawParameters, instanceCount, firstInstance, drawCount);
   }

   @Override
   public void multiDraw(@NonNull IntBuffer firstVertices, @NonNull IntBuffer vertexCounts, int drawCount) {
      this.delegate.multiDraw(firstVertices, vertexCounts, drawCount);
   }

   @Override
   public void drawIndirect(@NonNull GpuBufferSlice commands, int drawCount) {
      this.delegate.drawIndirect(commands, drawCount);
   }
}
