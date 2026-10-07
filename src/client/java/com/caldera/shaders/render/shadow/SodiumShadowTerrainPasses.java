package com.caldera.shaders.render.shadow;

import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

public final class SodiumShadowTerrainPasses {
   private static final TerrainRenderPass[] SOLID = new TerrainRenderPass[4];
   private static final TerrainRenderPass[] CUTOUT = new TerrainRenderPass[4];
   private static final TerrainRenderPass[] COMBINED = new TerrainRenderPass[4];
   public static final TerrainRenderPass LOCAL_SOLID;
   public static final TerrainRenderPass LOCAL_CUTOUT;
   private static final TerrainRenderPass[] EMPTY;

   private SodiumShadowTerrainPasses() {
   }

   public static TerrainRenderPass solid(int cascade) {
      return SOLID[cascade];
   }

   public static TerrainRenderPass cutout(int cascade) {
      return CUTOUT[cascade];
   }

   public static TerrainRenderPass combined(int cascade) {
      return COMBINED[cascade];
   }

   public static boolean isCombined(TerrainRenderPass pass) {
      for(TerrainRenderPass combined : COMBINED) {
         if (pass == combined) {
            return true;
         }
      }

      return false;
   }

   public static TerrainRenderPass[] terrainPasses(int cascade, int activeCascadeCount) {
      return cascade > 0 && cascade == activeCascadeCount - 1 ? new TerrainRenderPass[]{COMBINED[cascade]} : new TerrainRenderPass[]{SOLID[cascade], CUTOUT[cascade]};
   }

   public static TerrainRenderPass source(TerrainRenderPass pass) {
      if (pass == LOCAL_SOLID) {
         return DefaultTerrainRenderPasses.SOLID;
      } else if (pass == LOCAL_CUTOUT) {
         return DefaultTerrainRenderPasses.CUTOUT;
      } else {
         for(int cascade = 0; cascade < 4; ++cascade) {
            if (pass == SOLID[cascade] || pass == COMBINED[cascade]) {
               return DefaultTerrainRenderPasses.SOLID;
            }

            if (pass == CUTOUT[cascade]) {
               return DefaultTerrainRenderPasses.CUTOUT;
            }
         }

         return pass;
      }
   }

   public static TerrainRenderPass[] passesFor(TerrainRenderPass source) {
      if (source == DefaultTerrainRenderPasses.SOLID) {
         return new TerrainRenderPass[]{SOLID[0], SOLID[1], SOLID[2], SOLID[3], LOCAL_SOLID, COMBINED[0], COMBINED[1], COMBINED[2], COMBINED[3]};
      } else {
         return source == DefaultTerrainRenderPasses.CUTOUT ? new TerrainRenderPass[]{CUTOUT[0], CUTOUT[1], CUTOUT[2], CUTOUT[3], LOCAL_CUTOUT, COMBINED[0], COMBINED[1], COMBINED[2], COMBINED[3]} : EMPTY;
      }
   }

   static {
      LOCAL_SOLID = new TerrainRenderPass(ChunkSectionLayer.SOLID, false, false);
      LOCAL_CUTOUT = new TerrainRenderPass(ChunkSectionLayer.CUTOUT, false, true);
      EMPTY = new TerrainRenderPass[0];

      for(int cascade = 0; cascade < 4; ++cascade) {
         SOLID[cascade] = new TerrainRenderPass(ChunkSectionLayer.SOLID, false, false);
         CUTOUT[cascade] = new TerrainRenderPass(ChunkSectionLayer.CUTOUT, false, true);
         COMBINED[cascade] = new TerrainRenderPass(ChunkSectionLayer.SOLID, false, true);
      }

   }
}
