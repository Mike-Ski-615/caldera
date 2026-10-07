package com.caldera.shaders.graph;

public final class StorageImageScope implements AutoCloseable {
   private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
   private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 1);
   private final boolean previous;
   private final int previousDepth;

   public StorageImageScope(boolean active, int depth) {
      this.previous = ACTIVE.get();
      this.previousDepth = DEPTH.get();
      ACTIVE.set(active);
      DEPTH.set(depth);
   }

   public static boolean active() {
      return ACTIVE.get();
   }

   public static int depth() {
      return DEPTH.get();
   }

   public void close() {
      ACTIVE.set(this.previous);
      DEPTH.set(this.previousDepth);
   }
}
