package com.caldera.shaders.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.caldera.shaders.graph.NativePackRuntime;
import com.caldera.shaders.mixin.sodium.SodiumWorldRendererAccessor;
import com.caldera.shaders.render.shadow.DirectionalShadowPipelines;
import com.caldera.shaders.render.shadow.DirectionalShadowRenderer;
import com.caldera.shaders.render.shadow.DirectionalShadowSubmitFilter;
import com.caldera.shaders.render.shadow.HeldLightShadowRenderer;
import com.caldera.shaders.render.shadow.ShadowService;
import com.caldera.shaders.render.shadow.SodiumShadowTerrainRenderer;
import com.caldera.shaders.runtime.ShaderRuntime;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.Optional;
import java.util.OptionalDouble;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({LevelRenderer.class})
public abstract class LevelRendererShadowMixin {
   @Shadow
   private GpuSampler chunkLayerSampler;
   @Shadow
   @Final
   private LevelRenderState levelRenderState;
   @Shadow
   @Final
   private EntityRenderDispatcher entityRenderDispatcher;
   @Shadow
   @Final
   private GameRenderer gameRenderer;
   @Shadow
   @Final
   private ModelManager modelManager;
   @Shadow
   @Final
   private AtlasManager atlasManager;
   @Unique
   private final SubmitNodeStorage caldera$nearShadowSubmitStorage = new SubmitNodeStorage();
   @Unique
   private final SubmitNodeStorage caldera$localShadowStorage = new SubmitNodeStorage();
   @Unique
   private DirectionalShadowSubmitFilter caldera$shadowSubmitFilter;
   @Unique
   private RenderBuffers caldera$shadowRenderBuffers;
   @Unique
   private FeatureRenderDispatcher caldera$shadowFeatureDispatcher;
   @Unique
   private boolean caldera$hasNearShadowEntitySubmits;

   @Inject(
      method = {"render"},
      at = {@At(
   value = "INVOKE",
   target = "Lnet/minecraft/client/renderer/LevelRenderer;submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V"
)}
   )
   private void caldera$collectDirectionalShadowEntities(GraphicsResourceAllocator allocator, boolean renderBlockOutline, CameraRenderState cameraRenderState, GpuBufferSlice fogParameters, Vector4f fogColor, boolean renderOutline, boolean consistentDepthRequired, CallbackInfo ci) {
      DeltaTracker deltaTracker = Minecraft.getInstance().getDeltaTracker();
      this.caldera$localShadowStorage.getSubmitsPerOrder().clear();
      HeldLightShadowRenderer local = NativePackRuntime.heldShadows();
      if (local != null && local.active() && !ShaderRuntime.resourceReloading()) {
         DirectionalShadowPipelines.ensureInitialized();
         DirectionalShadowSubmitFilter filter = new DirectionalShadowSubmitFilter(this.caldera$localShadowStorage, Double.MAX_VALUE);
         ClientLevel level = Minecraft.getInstance().level;
         if (level != null) {
            for(Entity entity : level.entitiesForRendering()) {
               if (!entity.isRemoved() && !entity.isSpectator() && !entity.isInvisible() && !(entity.position().distanceToSqr(local.light().position()) > (double)576.0F)) {
                  EntityRenderState state = this.entityRenderDispatcher.extractEntity(entity, deltaTracker.getGameTimeDeltaPartialTick(false));
                  this.entityRenderDispatcher.submit(state, cameraRenderState, state.x - cameraRenderState.pos.x, state.y - cameraRenderState.pos.y, state.z - cameraRenderState.pos.z, new PoseStack(), filter);
               }
            }
         }
      }

      this.caldera$nearShadowSubmitStorage.getSubmitsPerOrder().clear();
      this.caldera$hasNearShadowEntitySubmits = false;
      if (!ShaderRuntime.resourceReloading() && ShadowService.entities()) {
         DirectionalShadowPipelines.ensureInitialized();
         DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
         this.caldera$shadowSubmitFilter = new DirectionalShadowSubmitFilter(this.caldera$nearShadowSubmitStorage, (double)Math.max(24.0F, shadows.entityCascadeEnd(0)));
         boolean hadEntityOutlines = this.levelRenderState.shouldShowEntityOutlines;
         this.levelRenderState.shouldShowEntityOutlines = false;

         try {
            this.caldera$submitNativeShadowEntities(deltaTracker, cameraRenderState);
            this.caldera$hasNearShadowEntitySubmits = this.caldera$hasShadowSubmits(this.caldera$nearShadowSubmitStorage);
         } finally {
            this.levelRenderState.shouldShowEntityOutlines = hadEntityOutlines;
         }

      }
   }

   @Inject(
      method = {"addMainPass"},
      at = {@At("HEAD")}
   )
   private void caldera$addDirectionalShadowPass(FrameGraphBuilder builder, FeatureRenderDispatcher.PreparedFrame preparedFrame, GpuBufferSlice viewPositions, ChunkSectionsToRender chunkSectionsToRender, boolean consistentDepthRequired, CallbackInfo ci) {
      if (!ShaderRuntime.resourceReloading() && (ShadowService.enabled() || NativePackRuntime.heldShadows() != null) && chunkSectionsToRender != null) {
         FramePass pass = builder.addPass("caldera:directional_shadow_maps");
         pass.disableCulling();
         pass.executes(() -> {
            DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
            shadows.prepare(this.levelRenderState);
            SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
            GpuSampler sampler = this.chunkLayerSampler != null ? this.chunkLayerSampler : RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

            try {
               for(int cascade = 0; cascade < shadows.activeCascadeCount(); ++cascade) {
                  boolean terrainUpdate = shadows.shouldUpdateCascade(cascade);
                  boolean entityUpdate = shadows.shouldUpdateEntityCascade(cascade);
                  if (terrainUpdate || entityUpdate) {
                     shadows.uploadCascade(encoder, cascade);
                     if (terrainUpdate) {
                        shadows.clearCascade(encoder, cascade);
                        DirectionalShadowRenderer.beginCascade(cascade);

                        try {
                           if (sodiumRenderer != null && this.levelRenderState.cameraRenderState != null) {
                              this.caldera$renderSodiumTerrainShadow(sodiumRenderer, shadows, cascade, sampler);
                           }
                        } finally {
                           DirectionalShadowRenderer.endCascade();
                        }
                     }

                     if (entityUpdate) {
                        shadows.clearEntityCascade(encoder, cascade);
                        DirectionalShadowRenderer.beginEntityCascade(cascade);

                        try {
                           if (this.caldera$hasNearShadowEntitySubmits) {
                              this.caldera$prepareAndRenderShadowEntities(this.caldera$nearShadowSubmitStorage);
                           }
                        } finally {
                           DirectionalShadowRenderer.endCascade();
                        }
                     }
                  }
               }

               if (sodiumRenderer != null) {
                  ((SodiumWorldRendererAccessor)sodiumRenderer).caldera$uniformBufferManager().prepareFrame();
               }

               if (ShadowService.enabled()) {
                  shadows.uploadShadowData(encoder);
               }

               HeldLightShadowRenderer local = NativePackRuntime.heldShadows();
               if (local != null && local.active()) {
                  FeatureRenderDispatcher.PreparedFrame frame = this.caldera$shadowFeatureDispatcher().prepareFrame(this.caldera$localShadowStorage);

                  try {
                     local.render(sodiumRenderer, this.levelRenderState.cameraRenderState, sampler, () -> this.caldera$renderShadowEntityFrame(frame));
                  } finally {
                     frame.close();
                  }
               }
            } catch (RuntimeException failure) {
               NativePackRuntime.failScene(failure);
            } finally {
               if (this.caldera$shadowRenderBuffers != null) {
                  this.caldera$shadowRenderBuffers.endFrame();
               }

            }

         });
      }
   }

   @Unique
   private void caldera$renderSodiumTerrainShadow(SodiumWorldRenderer renderer, DirectionalShadowRenderer shadows, int cascade, GpuSampler sampler) {
      Vec3 cameraPos = this.levelRenderState.cameraRenderState.pos;
      if (cameraPos != null) {
         ((SodiumWorldRendererAccessor)renderer).caldera$uniformBufferManager().prepareFrame();
         ChunkRenderMatrices matrices = new ChunkRenderMatrices(new Matrix4f(shadows.cascadeMatrix(cascade)), new Matrix4f());
         SodiumShadowTerrainRenderer.renderCascade(renderer, shadows, cascade, matrices, cameraPos.x, cameraPos.y, cameraPos.z, sampler);
      }
   }

   @Inject(
      method = {"close"},
      at = {@At("HEAD")}
   )
   private void caldera$closeDirectionalShadowFeatureDispatcher(CallbackInfo ci) {
      if (this.caldera$shadowFeatureDispatcher != null) {
         this.caldera$shadowFeatureDispatcher.close();
         this.caldera$shadowFeatureDispatcher = null;
      }

      if (this.caldera$shadowRenderBuffers != null) {
         this.caldera$shadowRenderBuffers.close();
         this.caldera$shadowRenderBuffers = null;
      }

   }

   @Unique
   private void caldera$renderShadowEntityFrame(FeatureRenderDispatcher.PreparedFrame frame) {
      RenderTarget target = DirectionalShadowRenderer.get().activeTarget();
      RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Caldera shadow entities", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty());

      try {
         frame.executeSolid(pass);
         frame.executeTranslucent(pass);
         frame.executeTranslucentAfterTerrain(pass);
         frame.executeAlwaysOnTop(pass);
      } catch (Throwable var7) {
         if (pass != null) {
            try {
               pass.close();
            } catch (Throwable var6) {
               var7.addSuppressed(var6);
            }
         }

         throw var7;
      }

      if (pass != null) {
         pass.close();
      }

   }

   @Unique
   private void caldera$prepareAndRenderShadowEntities(SubmitNodeStorage storage) {
      FeatureRenderDispatcher.PreparedFrame frame = this.caldera$shadowFeatureDispatcher().prepareFrame(storage);

      try {
         this.caldera$renderShadowEntityFrame(frame);
      } finally {
         frame.close();
      }

   }

   @Unique
   private FeatureRenderDispatcher caldera$shadowFeatureDispatcher() {
      if (this.caldera$shadowFeatureDispatcher == null) {
         this.caldera$shadowRenderBuffers = new RenderBuffers(Runtime.getRuntime().availableProcessors());
         this.caldera$shadowFeatureDispatcher = new FeatureRenderDispatcher(this.caldera$shadowRenderBuffers, this.modelManager, this.atlasManager, Minecraft.getInstance().font, this.gameRenderer.gameRenderState());
      }

      return this.caldera$shadowFeatureDispatcher;
   }

   @Unique
   private void caldera$submitNativeShadowEntities(DeltaTracker deltaTracker, CameraRenderState cameraState) {
      ClientLevel level = Minecraft.getInstance().level;
      if (level != null && cameraState != null && cameraState.pos != null) {
         double distance = (double)Math.max(24.0F, DirectionalShadowRenderer.get().entityShadowDistance()) + (double)24.0F;
         float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);

         for(Entity entity : level.entitiesForRendering()) {
            double limit = distance + (double)Math.max(entity.getBbWidth(), entity.getBbHeight()) * (double)4.0F;
            if (!entity.isRemoved() && !entity.isSpectator() && !entity.isInvisible() && !(entity.position().distanceToSqr(cameraState.pos) > limit * limit)) {
               EntityRenderState state = this.entityRenderDispatcher.extractEntity(entity, partialTick);
               this.entityRenderDispatcher.submit(state, cameraState, state.x - cameraState.pos.x, state.y - cameraState.pos.y, state.z - cameraState.pos.z, new PoseStack(), this.caldera$shadowSubmitFilter);
            }
         }

      }
   }

   @Unique
   private boolean caldera$hasShadowSubmits(SubmitNodeStorage storage) {
      ObjectIterator var2 = storage.getSubmitsPerOrder().values().iterator();

      while(var2.hasNext()) {
         SubmitNodeCollection submitNodeCollection = (SubmitNodeCollection)var2.next();

         for(FeatureRenderPhase<?> phase : submitNodeCollection.allPhases()) {
            if (!phase.isEmpty()) {
               return true;
            }
         }
      }

      return false;
   }
}
