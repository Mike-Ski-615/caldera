package com.caldera.shaders.graph;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import org.joml.Vector4fc;

/**
 * scene pass 的**接管策略**：什么时候把一个主目标的 render pass 换掉，以及需要临时画到别处时
 * 怎么把它挂起再恢复。
 * <p>
 * <b>它不再是那个被游戏持有的对象。</b>原先这个类自己 {@code implements RenderPass}，于是
 * {@code RenderPass} 的 22 个方法（其中 10 个是纯转发、8 个是先记录再转发）成了它的公开面貌，
 * 而它自己真正提供的只有两件事：{@code wrap} 与 {@code outside}。那 22 个方法现在在
 * {@link SplitScenePass} 里——那是**适配器面**，不是这个模块的 interface。
 * <p>
 * 现在的形状是：公开面 2 个静态方法；内部与适配器的接缝是 {@link SuspendedScenePass} 的 2 个方法；
 * 剩下两件有内容的事情各是一个纯函数——{@link #takeOver}（要不要接管）与
 * {@link #resumeDescriptor}（用什么 descriptor 恢复）。三样都能在纯 JVM 里驱动。
 * <p>
 * 两处伸手都拿掉了：主目标的颜色纹理由调用方**惰性**递进来（原先在这里读
 * {@code Minecraft.getInstance()}），{@code RenderSystem} 只在真的要重建 pass 那一刻才碰。
 */
public final class SceneRenderPass {

   /** 当前被接管的那个 pass；没有就是 {@code null}。 */
   private static SuspendedScenePass active;

   /**
    * 正在恢复中。
    * <p>
    * 恢复要重新走一次 {@code createRenderPass}，而 {@code NativeSceneAttachmentsMixin} 正挂在它的
    * RETURN 上——没有这个标记，恢复出来的 pass 会被再包一层，于是 {@code outside()} 递归。
    */
   private static boolean resuming;

   private SceneRenderPass() {
   }

   /**
    * 决定要不要接管这个 pass，要的话就包一个 {@link SplitScenePass} 交给游戏。
    *
    * @param nativeTransparency 光影包是否在用原生半透明；为 false 时一概不接管
    * @param mainColor          **惰性**取主目标的颜色纹理。必须惰性：每帧都有很多
    *                           {@code createRenderPass}，光影没开时前三项就否了，不该在这一步去
    *                           问游戏——原件就是这个顺序。
    */
   public static RenderPass wrap(RenderPass pass, RenderPassDescriptor descriptor, boolean nativeTransparency, Supplier<GpuTexture> mainColor) {
      return takeOver(resuming, nativeTransparency, descriptor, mainColor) ? new SplitScenePass(pass, descriptor, SceneRenderPass::createOnDevice) : pass;
   }

   /**
    * 跑一段必须画在 scene pass **之外**的动作：把当前 pass 挂起，动作跑完后原样恢复。
    * <p>
    * 没有活跃 pass 时它就是直接跑——这是常见情形，不是退化情形。
    */
   public static void outside(Runnable action) {
      SuspendedScenePass pass = active;
      if (pass == null) {
         action.run();
         return;
      }

      active = null;
      pass.suspend();

      try {
         action.run();
      } finally {
         active = pass;
         pass.resume();
      }

   }

   /**
    * 记下一个被接管的 pass。由 {@link SplitScenePass} 构造时调用——「谁在活跃」这件事归模块管，
    * 适配器不自己记账，否则测试替身就进不来。
    */
   static void begin(SuspendedScenePass pass) {
      active = pass;
   }

   /** 注销一个被接管的 pass；只在这个 pass 正是当前活跃的那个时才生效。 */
   static void end(SuspendedScenePass pass) {
      if (active == pass) {
         active = null;
      }
   }

   // ---------------------------------------------------------------- 两个纯函数

   /**
    * 要不要接管：没在恢复、包在用原生半透明、有颜色附件，且**第一个颜色附件就是主目标**。
    * <p>
    * 最后一项是它值得单独成一个函数的原因：接管错了的表现是整帧被画到另一个目标上，或者
    * 根本没接管——两种都不抛异常。原先这四项挤在一个 {@code return} 的三元表达式里，还夹着一次
    * {@code Minecraft.getInstance()}，于是"什么时候接管"这件事没法单独问。
    */
   static boolean takeOver(boolean resuming, boolean nativeTransparency, RenderPassDescriptor descriptor, Supplier<GpuTexture> mainColor) {
      if (resuming || !nativeTransparency || descriptor.colorAttachments().isEmpty()) {
         return false;
      }

      RenderPassDescriptor.Attachment<Optional<Vector4fc>> first = descriptor.colorAttachments().getFirst();
      return first != null && first.textureView().texture() == mainColor.get();
   }

   /**
    * 恢复时用的 descriptor：颜色与深度的 load-op 都清掉，label 与 renderArea 照旧。
    * <p>
    * 为什么要清 load-op：原来的 pass 已经把内容加载过一次了，恢复出来的是**同一个**目标的续画，
    * 再来一次 load 会把刚画的东西擦掉。
    */
   static RenderPassDescriptor resumeDescriptor(RenderPassDescriptor original) {
      List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = original.colorAttachments().stream()
            .map((attachment) -> attachment == null ? null : new RenderPassDescriptor.Attachment<>(attachment.textureView(), Optional.<Vector4fc>empty()))
            .toList();
      RenderPassDescriptor.Attachment<OptionalDouble> depth = original.depthAttachment();
      RenderPassDescriptor.Attachment<OptionalDouble> resumeDepth = depth == null
            ? null
            : new RenderPassDescriptor.Attachment<>(depth.textureView(), OptionalDouble.empty());
      return new RenderPassDescriptor(original.label(), colors, resumeDepth, original.renderArea());
   }

   /**
    * 在"正在恢复"的标记下建一个新 pass。
    * <p>
    * 标记的置位与复位必须夹住**恰好**那一次创建：夹宽了会把别的 {@code createRenderPass} 也漏过钩子，
    * 夹窄了恢复出来的 pass 会被再包一层。所以它单独成一个方法，而不是让调用方自己 {@code try/finally}。
    */
   static RenderPass createForResume(Supplier<RenderPass> factory) {
      resuming = true;

      try {
         return factory.get();
      } finally {
         resuming = false;
      }
   }

   /** 包可见：给测试用，让"标记确实夹住了创建"这条可断言。 */
   static boolean isResuming() {
      return resuming;
   }

   /** 生产用的重建方式：真的去命令编码器上开一个 pass。调用发生在 {@link #createForResume} 里。 */
   static RenderPass createOnDevice(RenderPassDescriptor descriptor) {
      return RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor);
   }
}
