package com.caldera.shaders.config;

import java.util.Locale;
import net.minecraft.network.chat.Component;

public enum ShaderQualityPreset {
   OFF("off", 0),
   LOW("low", 1),
   MEDIUM("medium", 2),
   HIGH("high", 3),
   ULTRA("ultra", 4);

   private final String serializedName;
   private final int tier;

   private ShaderQualityPreset(String serializedName, int tier) {
      this.serializedName = serializedName;
      this.tier = tier;
   }

   public String serializedName() {
      return this.serializedName;
   }

   public int tier() {
      return this.tier;
   }

   public boolean enabled() {
      return this != OFF;
   }

   public Component displayName() {
      String var10000;
      switch (this.ordinal()) {
         case 0 -> var10000 = "Off";
         case 1 -> var10000 = "Low";
         case 2 -> var10000 = "Medium";
         case 3 -> var10000 = "High";
         case 4 -> var10000 = "Ultra";
         default -> throw new MatchException((String)null, (Throwable)null);
      }

      return Component.literal(var10000);
   }

   public static ShaderQualityPreset fromSerializedName(String serializedName) {
      if (serializedName != null && !serializedName.isBlank()) {
         String normalized = serializedName.toLowerCase(Locale.ROOT);

         for(ShaderQualityPreset preset : values()) {
            if (preset.serializedName.equals(normalized)) {
               return preset;
            }
         }

         return MEDIUM;
      } else {
         return MEDIUM;
      }
   }

   // $FF: synthetic method
   private static ShaderQualityPreset[] $values() {
      return new ShaderQualityPreset[]{OFF, LOW, MEDIUM, HIGH, ULTRA};
   }
}
