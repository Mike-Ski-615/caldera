package com.caldera.shaders.graph;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 这个模块现在真正提供的两件事：{@code outside()} 的挂起／恢复协议，以及两个纯判断
 * （要不要接管、用什么 descriptor 恢复）。
 * <p>
 * 改之前这三样都没有单一测试面：模块自己就是 {@code RenderPass}，唯一的造法是
 * {@code wrap()}，而它要 Minecraft。现在协议只需要一个两行的 {@link SuspendedScenePass} 替身，
 * 两个判断是纯函数，重建时那个"正在恢复"的标记也能单独问。
 */
class SceneRenderPassTest {

   // ---------------------------------------------------------------- outside 的协议

   @Test
   void outsideWithoutAnActivePassJustRunsTheAction() {
      List<String> calls = new ArrayList<>();

      SceneRenderPass.outside(() -> calls.add("action"));

      assertEquals(List.of("action"), calls, "没有活跃 pass 是常见情形，不是退化情形");
   }

   @Test
   void outsideSuspendsThenRunsThenResumes() {
      List<String> calls = new ArrayList<>();
      SuspendedScenePass pass = new FakeSuspendedPass(calls);
      SceneRenderPass.begin(pass);

      try {
         SceneRenderPass.outside(() -> calls.add("action"));
      } finally {
         SceneRenderPass.end(pass);
      }

      assertEquals(List.of("suspend", "action", "resume"), calls);
   }

   @Test
   void aThrowingActionStillResumes() {
      List<String> calls = new ArrayList<>();
      SuspendedScenePass pass = new FakeSuspendedPass(calls);
      SceneRenderPass.begin(pass);

      try {
         RuntimeException boom = new RuntimeException("capture failed");
         RuntimeException thrown = assertThrows(RuntimeException.class, () -> SceneRenderPass.outside(() -> {
            calls.add("action");
            throw boom;
         }));

         assertSame(boom, thrown, "异常本身必须原样冒出去");
         assertEquals(List.of("suspend", "action", "resume"), calls, "恢复必须走 finally");
      } finally {
         SceneRenderPass.end(pass);
      }
   }

   /**
    * 嵌套的 {@code outside} 不该再挂起一次：动作跑的那段时间里活跃的是"外面那个世界"，
    * 内层看到的就是没有活跃 pass。这条错了的表现是把已经关掉的 pass 再关一次。
    */
   @Test
   void anOutsideInsideAnOutsideDoesNotSuspendTwice() {
      List<String> calls = new ArrayList<>();
      SuspendedScenePass pass = new FakeSuspendedPass(calls);
      SceneRenderPass.begin(pass);

      try {
         SceneRenderPass.outside(() -> {
            calls.add("outer");
            SceneRenderPass.outside(() -> calls.add("inner"));
         });
      } finally {
         SceneRenderPass.end(pass);
      }

      assertEquals(List.of("suspend", "outer", "inner", "resume"), calls);
   }

   /** 注销只对自己生效：拿别的 pass 来注销不该把当前那个清掉。 */
   @Test
   void endOnlyClearsThePassItWasGiven() {
      List<String> calls = new ArrayList<>();
      SuspendedScenePass active = new FakeSuspendedPass(calls);
      SuspendedScenePass other = new FakeSuspendedPass(new ArrayList<>());
      SceneRenderPass.begin(active);

      try {
         SceneRenderPass.end(other);
         SceneRenderPass.outside(() -> calls.add("action"));

         assertEquals(List.of("suspend", "action", "resume"), calls, "active 仍然在，所以这一趟必须走完整协议");
      } finally {
         SceneRenderPass.end(active);
      }
   }

   // ---------------------------------------------------------------- 要不要接管

   @Test
   void takeOverRejectsWhileResuming() {
      assertFalse(takeOver(true, true, descriptor(view(main()), null), () -> main()), "恢复出来的 pass 不能再被包一层，否则 outside 递归");
   }

   @Test
   void takeOverRejectsWhenNativeTransparencyIsOff() {
      assertFalse(takeOver(false, false, descriptor(view(main()), null), () -> main()));
   }

   @Test
   void takeOverRejectsWithoutColorAttachments() {
      RenderPassDescriptor noColors = new RenderPassDescriptor(() -> "test", List.of(), null, area());

      assertFalse(takeOver(false, true, noColors, () -> main()));
   }

   @Test
   void takeOverRejectsWhenTheFirstAttachmentIsNull() {
      List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = Arrays.asList((RenderPassDescriptor.Attachment<Optional<Vector4fc>>)null);
      RenderPassDescriptor nullFirst = new RenderPassDescriptor(() -> "test", colors, null, area());

      assertFalse(takeOver(false, true, nullFirst, () -> main()));
   }

   @Test
   void takeOverRejectsAPassThatTargetsAnotherTexture() {
      assertFalse(takeOver(false, true, descriptor(view(new FakeTexture()), null), () -> main()), "别的目标的 pass 不归这里管");
   }

   @Test
   void takeOverAcceptsAPassOnTheMainColorTexture() {
      assertTrue(takeOver(false, true, descriptor(view(main()), null), () -> main()));
   }

   /**
    * 主目标那张纹理必须**惰性**取。每帧都有很多 {@code createRenderPass}，光影没开时前三项就否了——
    * 原件就是在那个顺序上写的 {@code Minecraft.getInstance()}，随手改成提前求值会让每次
    * {@code createRenderPass} 都去问一次游戏。
    */
   @Test
   void takeOverDoesNotAskForTheMainTextureUnlessItHasTo() {
      AtomicBoolean asked = new AtomicBoolean();
      Supplier<GpuTexture> lazy = () -> {
         asked.set(true);
         return main();
      };

      takeOver(true, true, descriptor(view(main()), null), lazy);
      assertFalse(asked.get(), "正在恢复：不必问");
      takeOver(false, false, descriptor(view(main()), null), lazy);
      assertFalse(asked.get(), "光影没开：不必问");
      takeOver(false, true, new RenderPassDescriptor(() -> "test", List.of(), null, area()), lazy);
      assertFalse(asked.get(), "没有颜色附件：不必问");

      takeOver(false, true, descriptor(view(main()), null), lazy);
      assertTrue(asked.get(), "真要判断时才问");
   }

   // ---------------------------------------------------------------- 用什么 descriptor 恢复

   @Test
   void resumeDescriptorDropsBothLoadOperationsAndKeepsEverythingElse() {
      GpuTextureView color = view(main());
      GpuTextureView depth = view(new FakeTexture());
      Supplier<String> label = () -> "Caldera main";
      RenderPass.RenderArea area = new RenderPass.RenderArea(3, 5, 640, 360);
      RenderPassDescriptor original = new RenderPassDescriptor(
            label,
            List.of(new RenderPassDescriptor.Attachment<>(color, Optional.of(new Vector4f(0.1F, 0.2F, 0.3F, 1.0F)))),
            new RenderPassDescriptor.Attachment<>(depth, OptionalDouble.of(1.0)),
            area);

      RenderPassDescriptor resume = SceneRenderPass.resumeDescriptor(original);

      assertTrue(resume.colorAttachments().getFirst().clearValue().isEmpty(), "再 load 一次会把刚画的东西擦掉");
      assertTrue(resume.depthAttachment().clearValue().isEmpty());
      assertSame(color, resume.colorAttachments().getFirst().textureView(), "目标不变");
      assertSame(depth, resume.depthAttachment().textureView());
      assertSame(label, resume.label());
      assertSame(area, resume.renderArea());
   }

   @Test
   void resumeDescriptorKeepsANullColorSlotAndANullDepth() {
      List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = Arrays.asList((RenderPassDescriptor.Attachment<Optional<Vector4fc>>)null);
      RenderPassDescriptor original = new RenderPassDescriptor(() -> "test", colors, null, area());

      RenderPassDescriptor resume = SceneRenderPass.resumeDescriptor(original);

      assertEquals(1, resume.colorAttachments().size());
      assertEquals(null, resume.colorAttachments().getFirst(), "空槽位必须还是空槽位");
      assertEquals(null, resume.depthAttachment());
   }

   // ---------------------------------------------------------------- 恢复时的重入标记

   @Test
   void createForResumeHoldsTheFlagAcrossExactlyTheCreation() {
      AtomicBoolean duringCreation = new AtomicBoolean();
      RecordingRenderPass created = new RecordingRenderPass();

      RenderPass returned = SceneRenderPass.createForResume(() -> {
         duringCreation.set(SceneRenderPass.isResuming());
         return created;
      });

      assertSame(created, returned);
      assertTrue(duringCreation.get(), "创建那一刻必须带着标记，否则钩子会把新 pass 再包一层");
      assertFalse(SceneRenderPass.isResuming(), "创建之后必须复位");
   }

   @Test
   void createForResumeClearsTheFlagEvenWhenTheCreationThrows() {
      assertThrows(IllegalStateException.class, () -> SceneRenderPass.createForResume(() -> {
         throw new IllegalStateException("device lost");
      }));

      assertFalse(SceneRenderPass.isResuming(), "抛了也必须复位，否则以后所有 pass 都不再被接管");
   }

   // ---------------------------------------------------------------- 夹具

   private static final class FakeSuspendedPass implements SuspendedScenePass {
      private final List<String> calls;

      FakeSuspendedPass(List<String> calls) {
         this.calls = calls;
      }

      @Override
      public void suspend() {
         this.calls.add("suspend");
      }

      @Override
      public void resume() {
         this.calls.add("resume");
      }
   }

   private static final FakeTexture MAIN = new FakeTexture();

   private static GpuTexture main() {
      return MAIN;
   }

   private static GpuTextureView view(GpuTexture texture) {
      return new FakeTextureView(texture);
   }

   private static RenderPass.RenderArea area() {
      return new RenderPass.RenderArea(0, 0, 854, 480);
   }

   private static RenderPassDescriptor descriptor(GpuTextureView color, GpuTextureView depth) {
      return new RenderPassDescriptor(
            () -> "test",
            List.of(new RenderPassDescriptor.Attachment<>(color, Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)))),
            depth == null ? null : new RenderPassDescriptor.Attachment<>(depth, OptionalDouble.of(1.0)),
            area());
   }

   private static boolean takeOver(boolean resuming, boolean nativeTransparency, RenderPassDescriptor descriptor, Supplier<GpuTexture> mainColor) {
      return SceneRenderPass.takeOver(resuming, nativeTransparency, descriptor, mainColor);
   }

   private static final class FakeTexture implements GpuTexture {
      @Override
      public int getWidth(int mipLevel) {
         return 854;
      }

      @Override
      public int getHeight(int mipLevel) {
         return 480;
      }

      @Override
      public int getDepthOrLayers() {
         return 1;
      }

      @Override
      public int getMipLevels() {
         return 1;
      }

      @Override
      public GpuFormat getFormat() {
         return GpuFormat.R8_UNORM;
      }

      @Override
      public int usage() {
         return 0;
      }

      @Override
      public String getLabel() {
         return "fake";
      }

      @Override
      public boolean isClosed() {
         return false;
      }

      @Override
      public void close() {
      }
   }

   private static final class FakeTextureView implements GpuTextureView {
      private final GpuTexture texture;

      FakeTextureView(GpuTexture texture) {
         this.texture = texture;
      }

      @Override
      public boolean isClosed() {
         return false;
      }

      @Override
      public GpuTexture texture() {
         return this.texture;
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
         return 854;
      }

      @Override
      public int getHeight(int mipLevel) {
         return 480;
      }

      @Override
      public void close() {
      }
   }
}
