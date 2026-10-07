package com.caldera.shaders.runtime;

import net.minecraft.resources.Identifier;

public final class ShaderResourceIds {
   private static final String NAMESPACE = "caldera";

   private ShaderResourceIds() {
   }

   public static Identifier id(String path) {
      return Identifier.fromNamespaceAndPath("caldera", path);
   }

   public static Identifier coreShader(String name) {
      return id("core/" + name);
   }
}
