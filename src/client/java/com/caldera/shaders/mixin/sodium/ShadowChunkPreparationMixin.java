package com.caldera.shaders.mixin.sodium;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.caldera.shaders.render.shadow.ShadowChunkState;
import com.caldera.shaders.render.shadow.SodiumShadowTerrainPasses;
import com.caldera.shaders.render.shadow.SodiumShadowTerrainRenderer;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(
   value = {DefaultChunkRenderer.class},
   remap = false
)
public abstract class ShadowChunkPreparationMixin implements ShadowChunkState {
   @Shadow
   @Final
   private boolean[] shouldDraw;

   public boolean[] caldera$drawFlags() {
      return this.shouldDraw;
   }

   @ModifyExpressionValue(
      method = {"prepare"},
      at = {@At(
   value = "FIELD",
   target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/DefaultTerrainRenderPasses;ALL:[Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;"
)}
   )
   private TerrainRenderPass[] caldera$prepareShadowBatch(TerrainRenderPass[] original) {
      TerrainRenderPass[] passes = SodiumShadowTerrainRenderer.preparingPasses();
      return passes == null ? original : passes;
   }

   @WrapOperation(
      method = {"prepare"},
      at = {@At(
   value = "INVOKE",
   target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer;fillCommandBuffer(Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch;Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Lnet/caffeinemc/mods/sodium/client/render/chunk/data/SectionRenderDataStorage;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderList;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;ZZ)V"
)}
   )
   private void caldera$combineOpaqueShadowCommands(MultiDrawBatch batch, RenderRegion region, SectionRenderDataStorage storage, ChunkRenderList list, CameraTransform camera, TerrainRenderPass pass, boolean cull, boolean indexed, Operation<Void> original) {
      if (!SodiumShadowTerrainPasses.isCombined(pass)) {
         original.call(new Object[]{batch, region, storage, list, camera, pass, cull, indexed});
      } else {
         SectionRenderDataStorage solid = region.getStorage(DefaultTerrainRenderPasses.SOLID);
         SectionRenderDataStorage cutout = region.getStorage(DefaultTerrainRenderPasses.CUTOUT);
         if (solid != null) {
            original.call(new Object[]{batch, region, solid, list, camera, pass, cull, indexed});
         }

         if (cutout != null) {
            original.call(new Object[]{batch, region, cutout, list, camera, pass, cull, indexed});
         }

      }
   }
}
