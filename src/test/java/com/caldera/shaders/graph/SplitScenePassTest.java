package com.caldera.shaders.graph;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 适配器面：转发、录制、重放。
 * <p>
 * 这是这个模块**唯一非平凡的内容**——哪些状态要重做一遍、按什么键去重、什么时候重做。
 * 原先它埋在 24 个公开方法里，只有跑起游戏才碰得到；现在它在一个包私有的类里，
 * 用一个会记录调用的 {@link RecordingRenderPass} 就能把整条链路摆出来。
 * <p>
 * 最值得钉的一条是**转发与录制的分界**：{@code draw} 当场执行、**不**重放——重放会把已经画过
 * 的东西在恢复出来的 pass 上再画一遍。这条错了不会抛异常，只会多画一层。
 */
class SplitScenePassTest {

   private SplitScenePass pass;

   @AfterEach
   void leaveNoActivePass() {
      if (this.pass != null) {
         this.pass.close();
      }
   }

   // ---------------------------------------------------------------- 挂起

   @Test
   void suspendPopsEveryRecordedDebugGroupThenCloses() {
      RecordingRenderPass delegate = new RecordingRenderPass();
      SplitScenePass pass = this.newPass(delegate, null);

      pass.pushDebugGroup(() -> "outer");
      pass.pushDebugGroup(() -> "inner");
      delegate.calls.clear();

      pass.suspend();

      assertEquals(List.of("popDebugGroup", "popDebugGroup", "close"), delegate.calls, "先按相反顺序弹干净，再关");
   }

   @Test
   void aPassWithNoDebugGroupsJustCloses() {
      RecordingRenderPass delegate = new RecordingRenderPass();
      SplitScenePass pass = this.newPass(delegate, null);

      pass.suspend();

      assertEquals(List.of("close"), delegate.calls);
   }

   // ---------------------------------------------------------------- 转发

   /** 画的东西当场就画了，恢复时**不能**再画一遍。 */
   @Test
   void theDrawCallsArePureForwardsAndAreNeverReplayed() {
      RecordingRenderPass delegate = new RecordingRenderPass();
      RecordingRenderPass resumed = new RecordingRenderPass();
      SplitScenePass pass = this.newPass(delegate, resumed);

      pass.draw(3, 1, 0, 0);
      assertTrue(delegate.calls.contains("draw"));

      pass.suspend();
      delegate.calls.clear();
      pass.resume();

      assertFalse(resumed.calls.contains("draw"), "重放会把已经画过的东西再画一遍：" + resumed.calls);
   }

   @Test
   void writeTimestampIsAlsoAPureForward() {
      RecordingRenderPass delegate = new RecordingRenderPass();
      RecordingRenderPass resumed = new RecordingRenderPass();
      SplitScenePass pass = this.newPass(delegate, resumed);

      pass.writeTimestamp(null, 0);
      pass.resume();

      assertTrue(delegate.calls.contains("writeTimestamp"));
      assertFalse(resumed.calls.contains("writeTimestamp"));
   }

   // ---------------------------------------------------------------- 录制与重放

   @Test
   void recordedStateIsReplayedOntoTheNewPassInInsertionOrder() {
      RecordingRenderPass delegate = new RecordingRenderPass();
      RecordingRenderPass resumed = new RecordingRenderPass();
      SplitScenePass pass = this.newPass(delegate, resumed);

      pass.setPipeline(null);
      pass.setUniform("sky", (com.mojang.renderpearl.api.buffers.GpuBufferSlice)null);
      pass.setVertexBuffer(0, null);
      pass.resume();

      assertEquals(List.of("setPipeline", "setUniform:sky", "setVertexBuffer:0"), resumed.calls);
   }

   @Test
   void rewritingTheSameUniformKeyOnlyReplaysTheLastValue() {
      RecordingRenderPass delegate = new RecordingRenderPass();
      RecordingRenderPass resumed = new RecordingRenderPass();
      SplitScenePass pass = this.newPass(delegate, resumed);

      pass.setUniform("sky", (com.mojang.renderpearl.api.buffers.GpuBufferSlice)null);
      pass.setUniform("sky", (com.mojang.renderpearl.api.buffers.GpuBufferSlice)null);
      pass.resume();

      assertEquals(List.of("setUniform:sky"), resumed.calls, "记的是最终状态，不是调用序列");
   }

   /**
    * 两个 scissor 方法**共用同一个键**：中间开开关关只有最后一个算数。
    * 这条错了的表现是恢复出来的 pass 带着一个早已被撤销的裁剪矩形。
    */
   @Test
   void onlyTheLastScissorCallIsReplayed() {
      RecordingRenderPass delegate = new RecordingRenderPass();
      RecordingRenderPass resumed = new RecordingRenderPass();
      SplitScenePass pass = this.newPass(delegate, resumed);

      pass.enableScissor(1, 2, 3, 4);
      pass.disableScissor();
      pass.resume();

      assertEquals(List.of("disableScissor"), resumed.calls);
   }

   @Test
   void theLastScissorCallWinsInEitherDirection() {
      RecordingRenderPass delegate = new RecordingRenderPass();
      RecordingRenderPass resumed = new RecordingRenderPass();
      SplitScenePass pass = this.newPass(delegate, resumed);

      pass.disableScissor();
      pass.enableScissor(1, 2, 3, 4);
      pass.resume();

      assertEquals(List.of("enableScissor"), resumed.calls);
   }

   @Test
   void vertexBuffersAreKeyedPerSlot() {
      RecordingRenderPass delegate = new RecordingRenderPass();
      RecordingRenderPass resumed = new RecordingRenderPass();
      SplitScenePass pass = this.newPass(delegate, resumed);

      pass.setVertexBuffer(0, null);
      pass.setVertexBuffer(1, null);
      pass.setVertexBuffer(0, null);
      pass.resume();

      assertEquals(List.of("setVertexBuffer:0", "setVertexBuffer:1"), resumed.calls, "每个 slot 一个键，顺序按第一次出现的先后");
   }

   @Test
   void debugGroupsComeBackBeforeStateAndConstantsAfterIt() {
      RecordingRenderPass delegate = new RecordingRenderPass();
      RecordingRenderPass resumed = new RecordingRenderPass();
      SplitScenePass pass = this.newPass(delegate, resumed);

      pass.pushDebugGroup(() -> "group");
      pass.setPipeline(null);
      pass.pushConstants(ByteBuffer.allocateDirect(4));
      pass.resume();

      assertEquals(List.of("pushDebugGroup", "setPipeline", "pushConstants"), resumed.calls);
   }

   /**
    * 调用方那份 {@code ByteBuffer} 是复用的，推完就会接着改。模块必须拷**内容**，
    * 否则恢复出来的是"后来那些数"。
    */
   @Test
   void pushedConstantsSurviveLaterMutationOfTheCallersBuffer() {
      RecordingRenderPass delegate = new RecordingRenderPass();
      RecordingRenderPass resumed = new RecordingRenderPass();
      SplitScenePass pass = this.newPass(delegate, resumed);

      ByteBuffer constants = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
      constants.putFloat(1.0F).putFloat(2.0F).putFloat(3.0F).putFloat(4.0F);
      constants.flip();
      pass.pushConstants(constants);

      constants.putFloat(0, 9.0F);
      pass.resume();

      assertEquals(1, resumed.constantSnapshots.size());
      assertArrayEquals(floats(1.0F, 2.0F, 3.0F, 4.0F), resumed.constantSnapshots.getFirst(), "重放的必须是推的那一刻的内容");
   }

   @Test
   void theNewPassIsCreatedFromTheResumeDescriptor() {
      RecordingRenderPass delegate = new RecordingRenderPass();
      RecordingRenderPass resumed = new RecordingRenderPass();
      List<RenderPassDescriptor> created = new ArrayList<>();
      RenderPassDescriptor original = new RenderPassDescriptor(() -> "scene", List.of(), null, new com.mojang.renderpearl.api.commands.RenderPass.RenderArea(0, 0, 1, 1));
      this.pass = new SplitScenePass(delegate, original, (descriptor) -> {
         created.add(descriptor);
         return resumed;
      });

      this.pass.resume();

      assertEquals(1, created.size());
      assertEquals(SceneRenderPass.resumeDescriptor(original), created.getFirst());
   }

   // ---------------------------------------------------------------- 注销

   @Test
   void closeUnregistersAndClosesTheDelegateExactlyOnce() {
      RecordingRenderPass delegate = new RecordingRenderPass();
      SplitScenePass pass = this.newPass(delegate, null);

      pass.close();
      pass.close();

      assertEquals(List.of("close"), delegate.calls, "重复 close 必须是空操作");

      // 已经注销了，所以这一趟不该再挂起谁。
      SceneRenderPass.outside(() -> delegate.calls.add("action"));
      assertEquals(List.of("close", "action"), delegate.calls);
   }

   /** 构造器就把自己登记成活跃的那个，所以 {@code outside} 认得出它。 */
   @Test
   void constructingTheAdapterMakesItTheActiveOne() {
      RecordingRenderPass delegate = new RecordingRenderPass();
      RecordingRenderPass resumed = new RecordingRenderPass();
      this.newPass(delegate, resumed);

      SceneRenderPass.outside(() -> delegate.calls.add("action"));

      assertEquals(List.of("close", "action"), delegate.calls);
      assertEquals(List.of(), resumed.calls, "什么都没录过，所以没什么可重放");
   }

   private SplitScenePass newPass(RecordingRenderPass delegate, RecordingRenderPass resumed) {
      RecordingRenderPass target = resumed == null ? new RecordingRenderPass() : resumed;
      this.pass = new SplitScenePass(delegate, descriptor(), (ignored) -> target);
      return this.pass;
   }

   private static RenderPassDescriptor descriptor() {
      return new RenderPassDescriptor(() -> "scene", List.of(new RenderPassDescriptor.Attachment<>(
            new FakeView(), Optional.of(new org.joml.Vector4f(0.0F, 0.0F, 0.0F, 1.0F)))), null, new com.mojang.renderpearl.api.commands.RenderPass.RenderArea(0, 0, 1, 1));
   }

   private static byte[] floats(float... values) {
      ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.nativeOrder());

      for(float value : values) {
         buffer.putFloat(value);
      }

      return buffer.array();
   }

   private static final class FakeView implements com.mojang.renderpearl.api.textures.GpuTextureView {
      @Override
      public boolean isClosed() {
         return false;
      }

      @Override
      public com.mojang.renderpearl.api.textures.GpuTexture texture() {
         return null;
      }

      @Override
      public int baseMipLevel() {
         return 0;
      }

      @Override
      public int mipLevels() {
         return 1;
      }

      @Override
      public int getWidth(int mipLevel) {
         return 1;
      }

      @Override
      public int getHeight(int mipLevel) {
         return 1;
      }

      @Override
      public void close() {
      }
   }
}
