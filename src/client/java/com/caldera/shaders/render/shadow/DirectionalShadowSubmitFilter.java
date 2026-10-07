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
   private static final double ENTITY_DISTANCE_PADDING = (double)24.0F;
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
         double limit = maxDistance + (double)24.0F + sizePadding;
         return entityState.distanceToCameraSq <= limit * limit;
      } else {
         return true;
      }
   }
}
