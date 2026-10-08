package com.caldera.shaders.graph;

import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 手持光源：哪只手拿着会发光的东西、它在哪里、六个面往哪看。
 * <p>
 * <b>位置是读出来的，不是算出来的。</b>{@code HeldItemTransformMixin} 在第三人称
 * {@code ItemInHandLayer} 提交物品绘制的那一刻，把原版自己用的那个变换抓下来
 * （{@link #captureItemWorldPosition}），本类在下一帧直接采用。那条链里含
 * {@code translateToHand}、两次旋转、物品偏移，以及 {@code ArmPose.ITEM}
 * （持物时手臂抬起）与走路/潜行/挥砍——**一个都不必复刻**。
 * <p>
 * 曾经试过按模型几何手算这三个偏移，连续三次得到三个不同答案（轴约定、{@code scale(-1,-1,1)}、
 * {@code MODEL_Y_OFFSET}、{@code rotationZYX} 顺序、以及 {@code ArmPose} 那一项，任一处猜错就偏半格）。
 * 现在那条手算路径**已删除**，抓不到就干脆不点亮。
 * <p>
 * <b>为什么不能用第一人称手部那条路。</b>它由相机驱动：那条路径里 {@code yBodyRot} 出现
 * <b>0 次</b>，而 {@code viewYRot}／{@code viewXRot} 出现 2 次，且 {@code viewYRot} 经
 * {@code LocalPlayer.getViewYRot} → {@code yHeadRot} 就是**头部朝向**。所以它
 * <b>原理上无法区分</b>"只转头"与"转身体"，输出不能当世界位置用。
 * <p>
 * <b>已知未做，记在这里而不是留给读代码的人去猜：</b>
 * <ul>
 *   <li>只有<b>一盏</b>灯：两只手都拿着发光物时，取更亮的那只，另一只不产生光照。
 *       真要做两盏，6 张面要变 12 张，且 {@code HeldLightShadowRenderer.MEMORY_BYTES}
 *       与 {@code GraphRenderer} 里那个写死的显存常量要同步改。</li>
 *   <li>位置**滞后一帧**：第三人称手臂画在实体渲染阶段，而本类的 {@code update} 跑在
 *       {@code LevelRenderer.render} 的 HEAD。滞后 16ms，对柔和近距光源不可分辨；
 *       要消除它得把 {@code environment()} 挪到实体渲染之后，动的是 {@code SceneFrame}
 *       的门闸时序。</li>
 * </ul>
 */
public final class HeldLight {
   private final Matrix4f[] matrices = new Matrix4f[6];
   private static final Matrix4f[] FACE_BASES = createFaceBases();
   private Vec3 position;
   private int emission;
   private boolean cool;
   /** 本帧的关卡渲染状态，用来读挥手进度与装备高度；没有就是 {@code null}。 */
   private LevelRenderState renderState;
   /**
    * 上一帧抓到的**手持物品中心的世界坐标**，按手分开存；还没抓到过时为 {@code null}。
    * <p>
    * 由 {@code HeldItemTransformMixin} 在第三人称物品被提交绘制那一刻写入——那是它唯一的
    * 权威来源（含 {@code ArmPose} 的手臂旋转与全部动画）——本类在下一帧的
    * {@link #place} 里消费。
    * <p>
    * <b>为什么按手分开存。</b>两只手都拿着东西时原版会先后提交主手与副手；而本类一帧只点亮
    * 一盏灯（见类注释），要点亮的是哪只手到 {@link #place} 才知道。共用一个槽位的话，
    * 后一次提交会覆盖前一次，取到的就是错的那只手的位置。
    * <p>
    * <b>为什么是一帧延迟。</b>第三人称手臂画在实体渲染阶段，而本类的 {@code update} 跑在
    * {@code LevelRenderer.render} 的 HEAD——算位置时这一帧的物品还没画。
    */
   private final Vec3[] itemWorldPosition = new Vec3[2];

   public HeldLight() {
      this.position = Vec3.ZERO;

      for(int i = 0; i < 6; ++i) {
         this.matrices[i] = new Matrix4f();
      }

   }

   public boolean active() {
      return this.emission > 0;
   }

   public Vec3 position() {
      return this.position;
   }

   public Matrix4f matrix(int face) {
      return this.matrices[face];
   }

   private static Matrix4f[] createFaceBases() {
      Vector3f[] directions = new Vector3f[]{new Vector3f(1.0F, 0.0F, 0.0F), new Vector3f(-1.0F, 0.0F, 0.0F), new Vector3f(0.0F, 1.0F, 0.0F), new Vector3f(0.0F, -1.0F, 0.0F), new Vector3f(0.0F, 0.0F, 1.0F), new Vector3f(0.0F, 0.0F, -1.0F)};
      Matrix4f[] result = new Matrix4f[6];

      for(int face = 0; face < 6; ++face) {
         result[face] = (new Matrix4f()).perspective((float)Math.toRadians(90.5F), 1.0F, 0.05F, 12.0F, true).lookAlong(directions[face], face != 2 && face != 3 ? new Vector3f(0.0F, 1.0F, 0.0F) : new Vector3f(0.0F, 0.0F, 1.0F));
      }

      return result;
   }

   public static void faceMatrix(int face, Vec3 origin, Matrix4f destination) {
      destination.set(FACE_BASES[face]).translate(-((float) origin.x), -((float) origin.y), -((float) origin.z));
   }

   static int emission(ItemStack stack) {
      if (stack.isEmpty()) {
         return 0;
      } else {
         Item var2 = stack.getItem();
         if (var2 instanceof BlockItem block) {
             int light = block.getBlock().defaultBlockState().getLightEmission();
            if (light > 0) {
               return light;
            }
         }

         if (stack.is(Items.LAVA_BUCKET)) {
            return 15;
         } else if (!stack.is(Items.BLAZE_ROD) && !stack.is(Items.BLAZE_POWDER)) {
            return !stack.is(Items.GLOW_INK_SAC) && !stack.is(Items.GLOW_BERRIES) ? 0 : 8;
         } else {
            return 10;
         }
      }
   }

   void update(ClientLevel level, float partialTick, boolean enabled, LevelRenderState renderState) {
      this.emission = 0;
      this.renderState = renderState;
      LocalPlayer player = level != null && enabled ? Minecraft.getInstance().player : null;
      if (player != null && !player.isSpectator()) {
         // 用渲染状态里的物品栈，而不是玩家手上的：那才是"这一帧真的被画出来的那只手拿着什么"。
         FirstPersonHandsAndItemsRenderState hands = hands(renderState);
         ItemStack main = hands != null ? hands.mainHandItem : player.getMainHandItem();
         ItemStack off = hands != null ? hands.offHandItem : player.getOffhandItem();
         InteractionHand hand = select(hands, main, off);
         if (hand != null) {
            ItemStack selected = hand == InteractionHand.MAIN_HAND ? main : off;
            this.emission = emission(selected);
            this.cool = selected.is(Items.SOUL_TORCH) || selected.is(Items.SOUL_LANTERN) || selected.is(Items.SOUL_CAMPFIRE);
            this.place(hand);
         }
      }
   }

   /**
    * 选哪只手点灯：**先看原版这一帧画了哪只手，再在其中取更亮的**。
    * <p>
    * 为什么要问 {@code HandRenderSelection}：原版会在特定情况下只画一只手
    * （{@code evaluateWhichHandsToRender}：手持弓或弩时按用哪只手/是否上弦收紧到一只手）。
    * 只按亮度选的话，可能出现"光照在副手，但屏幕上副手根本没画出来"——玩家看到一团
    * 凭空出现在自己身边的光。
    * <p>
    * 反过来说，被隐藏的手里即使拿着火把也不算数：那盏灯没有可见的来源。
    * 两只手都被画出来（绝大多数情况）时，行为与"取更亮的"完全一致。
    * <p>
    * 没有渲染状态（比如包刚重载、状态还没提取）时退回"两只都算"，于是行为与加这个判断之前相同。
    */
   private static InteractionHand select(FirstPersonHandsAndItemsRenderState hands, ItemStack main, ItemStack off) {
      boolean renderMain = hands == null || hands.handRenderSelection == null || hands.handRenderSelection.renderMainHand;
      boolean renderOff = hands == null || hands.handRenderSelection == null || hands.handRenderSelection.renderOffHand;
      int mainLight = renderMain ? emission(main) : 0;
      int offLight = renderOff ? emission(off) : 0;
      if (mainLight == 0 && offLight == 0) {
         return null;
      }

      return mainLight >= offLight ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
   }

   private static FirstPersonHandsAndItemsRenderState hands(LevelRenderState state) {
      PlayerRenderState playerState = state == null ? null : state.playerRenderState;
      return playerState == null ? null : playerState.firstPersonHandsAndItems;
   }

   /**
    * 把手持光源放到它在世界里的位置。
    * <p>
    * <b>位置不是算出来的，是读出来的。</b>直接取 {@link #captureItemWorldPosition} 存下的
    * 那一份——它是原版自己在画物品时用的变换，已经含 {@code ArmPose.ITEM}
    * （持物时手臂抬起）与全部动画。手算复刻那条链试过三次、三次结果不同，所以这里**不再留
    * 手算退路**：抓不到时（包刚激活的头一两帧）干脆不点亮，而不是点亮在一个已知不准的位置。
    * <p>
    * 这也让"光源偶尔位置不对"无法再被一个错误的退路掩盖——不亮是看得见的，偏 0.2 格不是。
    */
   private void place(InteractionHand hand) {
      // 上一帧从原版自己的变换里抓到的物品中心（世界坐标）；还没抓到过就是 null。
      Vec3 captured = this.itemWorldPosition[hand == InteractionHand.MAIN_HAND ? 0 : 1];
      if (captured != null) {
         this.position = captured;
      }
   }

   /**
    * 记下这一帧画出来的手持物品**中心的世界坐标**，留给下一帧当光源位置用。
    * <p>
    * 唯一的调用者是 {@code HeldItemTransformMixin}，注入在第三人称
    * {@code ItemInHandLayer.submitArmWithItem} 的 {@code item.submit(...)} 那一刻——
    * 那时 {@code poseStack} 已经含 {@code translateToHand}、两次旋转、物品偏移，
    * 以及 {@code ArmPose} 给的手臂旋转。这是**权威值**，不需要复刻任何几何。
    * <p>
    * 按手分开存：两只手都拿着东西时原版会先后提交主手与副手，共用一个槽位会让后者覆盖
    * 前者，而要点亮哪只手到 {@link #place} 才知道。
    */
   public void captureItemWorldPosition(float x, float y, float z, boolean mainHand) {
      if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
         return;
      }

      this.itemWorldPosition[mainHand ? 0 : 1] = new Vec3((double)x, (double)y, (double)z);
   }

   void write(ByteBuffer bytes, Vec3 camera) {
      bytes.putFloat((float)(this.position.x - camera.x)).putFloat((float)(this.position.y - camera.y)).putFloat((float)(this.position.z - camera.z)).putFloat(this.emission == 0 ? 0.0F : 12.0F);
      float strength = (float)this.emission / 15.0F;
      bytes.putFloat(strength * (this.cool ? 0.25F : 1.35F)).putFloat(strength * (this.cool ? 0.85F : 0.76F)).putFloat(strength * (this.cool ? 1.3F : 0.34F)).putFloat(0.0F);
      Vec3 relative = this.position.subtract(camera);

      for(int face = 0; face < 6; ++face) {
         faceMatrix(face, relative, this.matrices[face]);
         this.matrices[face].get(bytes.position(), bytes);
         bytes.position(bytes.position() + 64);
      }

   }
}
