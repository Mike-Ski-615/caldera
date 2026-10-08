package com.caldera.shaders.mixin;

import com.caldera.shaders.graph.NativePackRuntime;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 抓取**第三人称手持物品**的模型→世界变换，供 {@code HeldLight} 当光源位置用。
 * <p>
 * <b>为什么必须在这里抓，而不是自己算常量。</b>这一轮试过三次"按模型几何手算偏移"，
 * 三次结果都不一样——因为要正确复刻 {@code HumanoidModel} 的轴约定、
 * {@code scale(-1,-1,1)}、{@code MODEL_Y_OFFSET}、{@code rotationZYX} 顺序、
 * 以及 {@code ArmPose} 给的那额外 −18°，任何一处猜错都会偏半格。
 * 而原版在 {@code ItemInHandLayer} 的最后一行调 {@code item.submit(poseStack, ...)}，
 * 那一刻 {@code poseStack.last().pose()} **就是**权威变换：它已经含
 * {@code ArmPose.ITEM}（举火把时的 −18°）、已经含走路/潜行/挥砍。
 * 读它，比复刻它可靠。
 * <p>
 * <b>为什么用包围盒中心而不是矩阵平移。</b>矩阵的平移分量是**物品原点**——
 * 火把是手柄底部、灯笼是提手，都不在物品中心。{@code ItemStackRenderState}
 * 上有原版算好的 {@code getModelBoundingBox()}（未定义时退回一个点，不会为 null），
 * 取它的中心再变换，就让火把/灯笼/萤石各自落回自己的中心，不必逐物品写常量。
 * <p>
 * <b>一帧延迟是刻意的。</b>第三人称手臂在实体渲染阶段画，而 {@code HeldLight.update}
 * 跑在 {@code LevelRenderer.render} 的 HEAD——算位置时这一帧的物品还没画，所以抓到的值
 * 留给**下一帧**用。手持光是一个柔和的近距光源，16ms 的滞后不可分辨；而要把
 * {@code environment()} 挪到实体渲染之后，就得动 {@code SceneFrame} 那套门闸时序。
 */
@Mixin({ItemInHandLayer.class})
public abstract class HeldItemTransformMixin {

   /**
    * 在物品被真正提交绘制的那一刻抓下它的世界变换。
    * <p>
    * 参数表必须与 {@code submitArmWithItem} 的真实签名逐个对应（Mixin 要求从头连续）。
    * 注入点是那个 {@code item.submit(...)} 调用——它在方法末尾，此时上面的
    * {@code translateToHand} + 两次 {@code rotateDegrees} + {@code translate} 全都已经乘进来了。
    */
   @Inject(
      method = {"submitArmWithItem"},
      at = {@At(
   value = "INVOKE",
   target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"
)}
   )
   private void caldera$captureHeldItemTransform(
         ArmedEntityRenderState state,
         ItemStackRenderState item,
         ItemStack itemStack,
         HumanoidArm arm,
         PoseStack poseStack,
         SubmitNodeCollector collector,
         int lightCoords,
         CallbackInfo ci) {
      if (poseStack == null || item == null || poseStack.isEmpty()) {
         return;
      }

      // **只认本地玩家。** 这是本次修复的核心：`ItemInHandLayer` 给**所有**带手臂的实体
      // 用——僵尸、盔甲架、其他玩家——而且 Caldera 自己的阴影关卡也会
      // `entityRenderDispatcher.submit(...)` 一遍附近实体，于是每条手臂都会经过这里。
      // 不设这道门，就是"谁最后提交谁赢"：抓到的坐标属于某个随机生物或另一个渲染通道，
      // 表现就是标记跑进自己头里/飘到别处。（实测症状：点落在玩家头部内。）
      //
      // 用 **实体 id** 而不是类型：类型只能排除"不是玩家"，而服务器上可能有多个玩家，
      // 那些玩家同样会经过这里。`AvatarRenderState.id` 就是实体 id，唯一。
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft == null ? null : minecraft.player;
      if (player == null || state == null) {
         return;
      }

      if (!(state instanceof AvatarRenderState avatar) || avatar.id != player.getId()) {
         return;
      }

      // 再用距离筛一次：阴影关卡用的是同一批状态，但那是另一条渲染通道，
      // 这里只要主通道的那一份。本地玩家永远在相机附近。
      if (state.distanceToCameraSq > 64.0) {
         return;
      }

      Matrix4f pose = poseStack.last().pose();
      if (pose == null || !pose.isFinite()) {
         return;
      }

      // 物品中心：包围盒是物品自己的模型空间，原版在提取 render state 时算好。
      AABB box = item.getModelBoundingBox();
      if (box == null) {
         return;
      }

      Vec3 center = box.getCenter();
      if (center == null) {
         return;
      }

      // 中心从物品模型空间 -> **相机相对**坐标。
      Vector3f local = pose.transformPosition(new Vector3f((float)center.x, (float)center.y, (float)center.z));

      // **必须再加回相机位置。** 这一步是本次修复的核心：
      // 实体渲染那条路的 poseStack 是**相机相对**的——`EntityRenderDispatcher.submit`
      // 收的就是 `state.x - camera.pos.x` 这样的差值（本仓库的
      // {@code LevelRendererShadowMixin} 里就写着这个减法），所以 `last().pose()`
      // 给出的平移是"相对相机"，不是世界坐标。
      // 而 {@code HeldLight.write} 自己会做 `position - camera`，也就是说它要的是
      // **世界坐标**。把相机相对值当世界坐标喂进去，光源就落在**相机附近**——
      // 第一人称下相机就在眼睛里，于是标记出现在头部内。这正是实测到的症状。
      Camera camera = minecraft.gameRenderer == null ? null : minecraft.gameRenderer.mainCamera();
      if (camera == null) {
         return;
      }

      Vec3 cameraPos = camera.position();
      if (cameraPos == null) {
         return;
      }

      double worldX = cameraPos.x + (double)local.x;
      double worldY = cameraPos.y + (double)local.y;
      double worldZ = cameraPos.z + (double)local.z;
      // "是不是主手"直接问这条手臂：主手 == 玩家惯用手。用 asArm 而不是自己比符号，
      // 这样左撇子玩家的映射由原版回答（与 HeldLight 里的选法一致）。
      boolean mainHand = arm == InteractionHand.MAIN_HAND.asArm(state.mainArm);
      NativePackRuntime.captureHeldItemWorldPosition((float)worldX, (float)worldY, (float)worldZ, mainHand);
   }
}
