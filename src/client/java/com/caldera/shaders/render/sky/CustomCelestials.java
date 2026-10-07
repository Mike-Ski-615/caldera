package com.caldera.shaders.render.sky;

import org.joml.Vector3f;

public final class CustomCelestials {
   private static final float CELESTIAL_YAW_RADIANS = (float)Math.toRadians((double)-45.0F);
   private static final float COS_YAW;
   private static final float SIN_YAW;

   private CustomCelestials() {
   }

   public static void setCelestialDirection(float angleRadians, Vector3f output) {
      float horizontal = (float)Math.sin((double)angleRadians);
      output.set(horizontal * SIN_YAW, (float)Math.cos((double)angleRadians), horizontal * COS_YAW).normalize();
   }

   static {
      COS_YAW = (float)Math.cos((double)CELESTIAL_YAW_RADIANS);
      SIN_YAW = (float)Math.sin((double)CELESTIAL_YAW_RADIANS);
   }
}
