package com.caldera.shaders.mixin.sodium;

import com.caldera.shaders.graph.MaterialContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
   value = {BlockRenderer.class},
   remap = false
)
public abstract class BlockMaterialMixin {
   @ModifyArg(
      method = {"processQuad"},
      at = {@At(
   value = "INVOKE",
   target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/DefaultMaterials;forChunkLayer(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;"
)},
      index = 0
   )
   private ChunkSectionLayer caldera$clearIceLayer(ChunkSectionLayer original) {
      return MaterialContext.clearIce() ? ChunkSectionLayer.TRANSLUCENT : original;
   }

   @Inject(
      method = {"renderModel"},
      at = {@At("HEAD")}
   )
   private void caldera$material(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
      MaterialContext.enter(state);
   }

   @Inject(
      method = {"renderModel", "release"},
      at = {@At("RETURN")}
   )
   private void caldera$clearMaterial(CallbackInfo ci) {
      MaterialContext.leave();
   }
}
