package com.caldera.shaders.graph;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class MaterialContext {
   private static final ThreadLocal<BlockState> STATE = new ThreadLocal<>();

   private MaterialContext() {
   }

   public static void enter(BlockState state) {
      STATE.set(state);
   }

   public static void leave() {
      STATE.remove();
   }

   static BlockState state() {
      return (BlockState)STATE.get();
   }

   public static boolean clearIce() {
      BlockState state = (BlockState)STATE.get();
      MaterialTable table = NativePackRuntime.materials();
      if (state != null && table != null) {
         int id = table.id(state);
         return id == 12 && (state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE));
      } else {
         return false;
      }
   }
}
