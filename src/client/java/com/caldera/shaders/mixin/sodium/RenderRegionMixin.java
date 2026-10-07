package com.caldera.shaders.mixin.sodium;

import com.caldera.shaders.render.shadow.ShadowRegionState;
import com.caldera.shaders.render.shadow.SodiumShadowTerrainPasses;
import com.caldera.shaders.render.shadow.SodiumShadowTerrainRenderer;
import java.util.IdentityHashMap;
import java.util.Map;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(
   value = {RenderRegion.class},
   remap = false
)
public abstract class RenderRegionMixin implements ShadowRegionState {
   @Unique
   private final Map<TerrainRenderPass, MultiDrawBatch> caldera$shadowBatches = new IdentityHashMap();
   @Unique
   private long caldera$shadowRevision;

   @Inject(
      method = {"getStorage"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void caldera$shareSourceMeshStorage(TerrainRenderPass pass, CallbackInfoReturnable<SectionRenderDataStorage> cir) {
      if (SodiumShadowTerrainPasses.isCombined(pass)) {
         RenderRegion region = (RenderRegion)(Object)this;
         SectionRenderDataStorage solid = region.getStorage(DefaultTerrainRenderPasses.SOLID);
         cir.setReturnValue(solid != null ? solid : region.getStorage(DefaultTerrainRenderPasses.CUTOUT));
      } else {
         TerrainRenderPass source = SodiumShadowTerrainPasses.source(pass);
         if (source != pass) {
            cir.setReturnValue(((RenderRegion)(Object)this).getStorage(source));
         }

      }
   }

   @Inject(
      method = {"getCachedBatch"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void caldera$useDedicatedShadowBatch(TerrainRenderPass pass, CallbackInfoReturnable<MultiDrawBatch> cir) {
      if (SodiumShadowTerrainPasses.source(pass) != pass) {
         cir.setReturnValue((MultiDrawBatch)(Object)this.caldera$shadowBatches.computeIfAbsent(pass, (ignored) -> MultiDrawBatch.newBatch(ModelQuadFacing.COUNT * 256 * (SodiumShadowTerrainPasses.isCombined(pass) ? 2 : 1) + 1)));
      }

   }

   @Inject(
      method = {"clearCachedBatchFor"},
      at = {@At("TAIL")}
   )
   private void caldera$invalidateShadowBatches(TerrainRenderPass pass, CallbackInfo ci) {
      MultiDrawBatch directBatch = (MultiDrawBatch)(Object)this.caldera$shadowBatches.get(pass);
      if (directBatch != null) {
         directBatch.clear();
      }

      for(TerrainRenderPass shadowPass : SodiumShadowTerrainPasses.passesFor(pass)) {
         MultiDrawBatch shadowBatch = (MultiDrawBatch)(Object)this.caldera$shadowBatches.get(shadowPass);
         if (shadowBatch != null) {
            shadowBatch.clear();
         }
      }

   }

   @Inject(
      method = {"setSectionRenderState"},
      at = {@At("TAIL")}
   )
   private void caldera$markShadowPlansDirty(int sectionIndex, BuiltSectionInfo info, CallbackInfo ci) {
      this.caldera$clearShadowBatches();
      ++this.caldera$shadowRevision;
      SodiumShadowTerrainRenderer.markTerrainDirty();
   }

   @Inject(
      method = {"clearSectionRenderState"},
      at = {@At("TAIL")}
   )
   private void caldera$markClearedShadowPlansDirty(int sectionIndex, CallbackInfo ci) {
      this.caldera$clearShadowBatches();
      ++this.caldera$shadowRevision;
      SodiumShadowTerrainRenderer.markTerrainDirty();
   }

   @Inject(
      method = {"onGeometrySegmentChange", "onIndexSegmentChange"},
      at = {@At("HEAD")}
   )
   private void caldera$clearResizedShadowBatches(CallbackInfo ci) {
      this.caldera$clearShadowBatches();
   }

   @Inject(
      method = {"delete"},
      at = {@At("HEAD")}
   )
   private void caldera$deleteShadowBatches(CallbackInfo ci) {
      SodiumShadowTerrainRenderer.markTerrainDirty();

      for(MultiDrawBatch batch : this.caldera$shadowBatches.values()) {
         batch.delete();
      }

      this.caldera$shadowBatches.clear();
   }

   @Unique
   private void caldera$clearShadowBatches() {
      for(MultiDrawBatch batch : this.caldera$shadowBatches.values()) {
         batch.clear();
      }

   }

   public long caldera$shadowRevision() {
      return this.caldera$shadowRevision;
   }
}
