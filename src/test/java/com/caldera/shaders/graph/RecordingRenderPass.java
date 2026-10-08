package com.caldera.shaders.graph;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

/**
 * {@link RenderPass} 的测试替身：把每一次调用按名字记下来。
 * <p>
 * 这是候选 4 那份"要测就得先实现 24 个方法"的代价落到实处的地方——它一次性写在测试源码里，
 * 之后的两个测试类都不用再碰：{@link SceneRenderPassTest} 只用它当 {@code createForResume} 的返回值，
 * {@link SplitScenePassTest} 用它当真正的 delegate 与恢复出来的那个 pass。
 * <p>
 * 22 个方法全部实现、全部记录，而不是"用到的实现、其余抛"。原因是**模块确实会驱动全部 22 个**：
 * 其中 10 个 draw 与 {@code writeTimestamp} 是纯转发，模块必须把它们转出去。真正值得断言的不是
 * "模块有没有碰不该碰的方法"，而是"哪些调用**不该**被重放"——转发是即时的，重放只在恢复时发生。
 * 所以这个替身记下全部，测试再去分"谁在什么时刻收到了什么"。
 */
final class RecordingRenderPass implements RenderPass {

   /** 按发生顺序记下每一次调用。 */
   final List<String> calls = new ArrayList<>();

   /**
    * 每次 {@code pushConstants} 收到的字节的**快照**。
    * <p>
    * 必须快照：模块的重放会把内部那份拷贝再交出去，而"拷的是内容不是引用"正是要断言的东西——
    * 只记次数看不出调用方之后改了原缓冲会发生什么。
    */
   final List<byte[]> constantSnapshots = new ArrayList<>();

   @Override
   public void pushDebugGroup(@NonNull Supplier<String> label) {
      this.calls.add("pushDebugGroup");
   }

   @Override
   public void popDebugGroup() {
      this.calls.add("popDebugGroup");
   }

   @Override
   public void writeTimestamp(@NonNull GpuQueryPool pool, int index) {
      this.calls.add("writeTimestamp");
   }

   @Override
   public void setPipeline(@NonNull CompiledRenderPipeline pipeline) {
      this.calls.add("setPipeline");
   }

   @Override
   public void setUniform(@NonNull String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
      this.calls.add("setUniform:" + name);
   }

   @Override
   public void setUniform(@NonNull String name, @NonNull GpuBuffer value) {
      this.calls.add("setUniform:" + name);
   }

   @Override
   public void setUniform(@NonNull String name, @NonNull GpuBufferSlice value) {
      this.calls.add("setUniform:" + name);
   }

   @Override
   public void pushConstants(@NonNull ByteBuffer value) {
      this.calls.add("pushConstants");
      byte[] snapshot = new byte[value.remaining()];
      value.duplicate().get(snapshot);
      this.constantSnapshots.add(snapshot);
   }

   @Override
   public void enableScissor(int x, int y, int width, int height) {
      this.calls.add("enableScissor");
   }

   @Override
   public void disableScissor() {
      this.calls.add("disableScissor");
   }

   @Override
   public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
      this.calls.add("setVertexBuffer:" + slot);
   }

   @Override
   public void setIndexBuffer(@NonNull GpuBuffer indexBuffer, @NonNull IndexType indexType) {
      this.calls.add("setIndexBuffer");
   }

   @Override
   public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
      this.calls.add("drawIndexed");
   }

   @Override
   public void multiDrawIndexed(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
      this.calls.add("multiDrawIndexed");
   }

   @Override
   public void multiDrawIndexed(@NonNull PointerBuffer firstIndexOffsets, @NonNull IntBuffer indexCounts, @NonNull IntBuffer vertexOffsets, int drawCount) {
      this.calls.add("multiDrawIndexed");
   }

   @Override
   public void drawIndexedIndirect(@NonNull GpuBufferSlice commands, int drawCount) {
      this.calls.add("drawIndexedIndirect");
   }

   @Override
   public <T> void drawMultipleIndexed(@NonNull Collection<RenderPass.Draw<T>> draws, @Nullable GpuBuffer defaultIndexBuffer, @Nullable IndexType defaultIndexType, @NonNull Collection<String> dynamicUniforms, @NonNull T uniformArgument) {
      this.calls.add("drawMultipleIndexed");
   }

   @Override
   public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
      this.calls.add("draw");
   }

   @Override
   public void multiDraw(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
      this.calls.add("multiDraw");
   }

   @Override
   public void multiDraw(@NonNull IntBuffer firstVertices, @NonNull IntBuffer vertexCounts, int drawCount) {
      this.calls.add("multiDraw");
   }

   @Override
   public void drawIndirect(@NonNull GpuBufferSlice commands, int drawCount) {
      this.calls.add("drawIndirect");
   }

   @Override
   public void close() {
      this.calls.add("close");
   }
}
