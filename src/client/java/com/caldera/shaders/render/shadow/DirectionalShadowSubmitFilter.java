package com.caldera.shaders.render.shadow;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;

public final class DirectionalShadowSubmitFilter implements SubmitNodeCollector {
   /**
    * 实体阴影提交距离的额外余量。
    * <p>
    * 它是**唯一**的一处声明，但会被用在三处相加：{@code LevelRendererShadowMixin} 采集时的距离、
    * 那个采集循环里的逐实体上限、以及下面 {@link #acceptsDistance} 的最终判据。那三处原先各自写了
    * 一个字面量 {@code 24.0F}，于是"到底多退了多远"要三处都读一遍才能回答——而它们相加的共同结果
    * 是"比级联远端多退 48 格（再加包围盒的 4 倍）"。那些数字本身可以是有意的，但重复书写不是：
    * 改一处、漏两处会安静地缩小或放大实体的阴影覆盖范围。
    */
   public static final double ENTITY_DISTANCE_PADDING = (double)24.0F;
   private final SubmitNodeStorage storage;
   private final OrderedSubmitNodeCollector delegate;
   private final double distance;

   public DirectionalShadowSubmitFilter(SubmitNodeStorage storage, double distance) {
      this(storage, storage, distance);
   }

   private DirectionalShadowSubmitFilter(SubmitNodeStorage storage, OrderedSubmitNodeCollector delegate, double distance) {
      this.storage = storage;
      this.delegate = delegate;
      this.distance = distance;
   }

   public OrderedSubmitNodeCollector order(int order) {
      return new DirectionalShadowSubmitFilter(this.storage, this.storage.order(order), this.distance);
   }

   public void submitShadow(PoseStack poseStack, float shadowRadius, List<EntityRenderState.ShadowPiece> shadowPieces) {
   }

   public void submitNameTag(PoseStack poseStack, Vec3 offset, int light, Component component, boolean sneaking, int backgroundColor, CameraRenderState cameraRenderState) {
   }

   public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence text, boolean shadow, Font.DisplayMode displayMode, int color, int backgroundColor, int light, int packedOverlay) {
   }

   public void submitFlame(PoseStack poseStack, EntityRenderState entityRenderState, Quaternionf rotation) {
   }

   public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
   }

   public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int light, int overlay, int outlineColor, UvMapping textureAtlasSprite, int packedOverlay) {
      if (DirectionalShadowPipelines.entityDepthPipeline(renderType.pipeline()) != null) {
         if (this.acceptsDistance(state, this.distance)) {
            this.delegate.submitModel(model, state, poseStack, renderType, light, overlay, outlineColor, textureAtlasSprite, packedOverlay);
         }

      }
   }

   public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int light) {
   }

   public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts, int[] tintCache, int light, int overlay, int outlineColor) {
   }

   public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress, boolean isBlockTranslucent) {
   }

   public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color, float alpha, boolean fullBright) {
   }

   /**
    * 物品**不进阴影图**，而这不转发是刻意的——不要"顺手补上"。
    * <p>
    * 物品模型走的是 {@code ITEM_CUTOUT} / {@code ITEM_TRANSLUCENT} 那一族管线，它们用自己的
    * {@code ITEM_SNIPPET} 与 {@code core/item} 着色器，顶点格式与实体不同；而
    * {@link DirectionalShadowPipelines#entityDepthPipeline} 是一张**显式白名单**，里面没有
    * 任何 {@code ITEM_*}。所以即使这里转发出去，绘制也会在 {@code RenderTypeMixin} 那一步因为
    * 映射为 {@code null} 而被取消——**改了等于没改**，只会让人以为掉落物该有影子了。
    * <p>
    * 真要支持掉落物/展示框物品的阴影，前置工作是给物品管线建一套深度管线（新的顶点绑定与
    * 阴影着色器），那是独立功能，不是给这个方法补一行转发。
    * <p>
    * 顺带一提：同一个白名单在 {@link #submitModel} 里也已经查过一遍了，两者是同一个判据。
    */
   public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int light, int overlay, int outlineColor, int[] tints, ItemQuads quads, ItemStackRenderState.FoilType foilType) {
   }

   public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer) {
      if (DirectionalShadowPipelines.entityDepthPipeline(renderType.pipeline()) != null) {
         this.delegate.submitCustomGeometry(poseStack, renderType, renderer);
      }

   }

   public void submitQuadParticleGroup(QuadParticleRenderState quadParticleRenderState) {
   }

   public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState cameraRenderState, boolean fullBright) {
   }

   public void submitTextBackground(PoseStack poseStack, float x0, float y0, float x1, float y1, int color, Font.DisplayMode displayMode, int lightCoords) {
   }

   public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int light, int overlay, int tint, ModelFeatureRenderer.CrumblingOverlay crumbling) {
   }

   private boolean acceptsDistance(Object state, double maxDistance) {
      if (state instanceof EntityRenderState entityState) {
         double sizePadding = (double)Math.max(entityState.boundingBoxWidth, entityState.boundingBoxHeight) * (double)4.0F;
         double limit = maxDistance + ENTITY_DISTANCE_PADDING + sizePadding;
         return entityState.distanceToCameraSq <= limit * limit;
      } else {
         return true;
      }
   }
}
