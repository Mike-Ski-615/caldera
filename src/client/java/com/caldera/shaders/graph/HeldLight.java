package com.caldera.shaders.graph;

import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class HeldLight {
   private final Matrix4f[] matrices = new Matrix4f[6];
   private static final Matrix4f[] FACE_BASES = createFaceBases();
   private Vec3 position;
   private int emission;
   private boolean cool;

   public HeldLight() {
      this.position = Vec3.ZERO;

      for(int i = 0; i < 6; ++i) {
         this.matrices[i] = new Matrix4f();
      }

   }

   public boolean active() {
      return this.emission > 0;
   }

   public Vec3 position() {
      return this.position;
   }

   public Matrix4f matrix(int face) {
      return this.matrices[face];
   }

   private static Matrix4f[] createFaceBases() {
      Vector3f[] directions = new Vector3f[]{new Vector3f(1.0F, 0.0F, 0.0F), new Vector3f(-1.0F, 0.0F, 0.0F), new Vector3f(0.0F, 1.0F, 0.0F), new Vector3f(0.0F, -1.0F, 0.0F), new Vector3f(0.0F, 0.0F, 1.0F), new Vector3f(0.0F, 0.0F, -1.0F)};
      Matrix4f[] result = new Matrix4f[6];

      for(int face = 0; face < 6; ++face) {
         result[face] = (new Matrix4f()).perspective((float)Math.toRadians(90.5F), 1.0F, 0.05F, 12.0F, true).lookAlong(directions[face], face != 2 && face != 3 ? new Vector3f(0.0F, 1.0F, 0.0F) : new Vector3f(0.0F, 0.0F, 1.0F));
      }

      return result;
   }

   public static void faceMatrix(int face, Vec3 origin, Matrix4f destination) {
      destination.set(FACE_BASES[face]).translate(-((float) origin.x), -((float) origin.y), -((float) origin.z));
   }

   static int emission(ItemStack stack) {
      if (stack.isEmpty()) {
         return 0;
      } else {
         Item var2 = stack.getItem();
         if (var2 instanceof BlockItem block) {
             int light = block.getBlock().defaultBlockState().getLightEmission();
            if (light > 0) {
               return light;
            }
         }

         if (stack.is(Items.LAVA_BUCKET)) {
            return 15;
         } else if (!stack.is(Items.BLAZE_ROD) && !stack.is(Items.BLAZE_POWDER)) {
            return !stack.is(Items.GLOW_INK_SAC) && !stack.is(Items.GLOW_BERRIES) ? 0 : 8;
         } else {
            return 10;
         }
      }
   }

   void update(ClientLevel level, float partialTick, boolean enabled) {
      this.emission = 0;
      LocalPlayer player = level != null && enabled ? Minecraft.getInstance().player : null;
      if (player != null && !player.isSpectator()) {
         ItemStack main = player.getMainHandItem();
         ItemStack off = player.getOffhandItem();
         ItemStack selected = emission(main) >= emission(off) ? main : off;
         this.emission = emission(selected);
         if (this.emission != 0) {
            this.cool = selected.is(Items.SOUL_TORCH) || selected.is(Items.SOUL_LANTERN) || selected.is(Items.SOUL_CAMPFIRE);
            float yaw = (float)Math.toRadians(player.getViewYRot(partialTick));
            float side = (float)((selected == main ? 1 : -1) * (player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1));
            this.position = player.getEyePosition(partialTick).add(-Math.cos((double)yaw) * (double)side * 0.45 - Math.sin((double)yaw) * 0.2, -0.35, -Math.sin((double)yaw) * (double)side * 0.45 + Math.cos((double)yaw) * 0.2);
         }
      }
   }

   void write(ByteBuffer bytes, Vec3 camera) {
      bytes.putFloat((float)(this.position.x - camera.x)).putFloat((float)(this.position.y - camera.y)).putFloat((float)(this.position.z - camera.z)).putFloat(this.emission == 0 ? 0.0F : 12.0F);
      float strength = (float)this.emission / 15.0F;
      bytes.putFloat(strength * (this.cool ? 0.25F : 1.35F)).putFloat(strength * (this.cool ? 0.85F : 0.76F)).putFloat(strength * (this.cool ? 1.3F : 0.34F)).putFloat(0.0F);
      Vec3 relative = this.position.subtract(camera);

      for(int face = 0; face < 6; ++face) {
         faceMatrix(face, relative, this.matrices[face]);
         this.matrices[face].get(bytes.position(), bytes);
         bytes.position(bytes.position() + 64);
      }

   }
}
