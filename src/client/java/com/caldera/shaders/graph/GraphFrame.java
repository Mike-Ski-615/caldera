package com.caldera.shaders.graph;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.caldera.shaders.render.sky.CustomCelestials;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

final class GraphFrame {
   private final Matrix4f previousProjection = new Matrix4f();
   private final Matrix4f previousView = new Matrix4f();
   private final ByteBuffer bytes = ByteBuffer.allocateDirect(FrameLayout.size()).order(ByteOrder.nativeOrder());
   /** 那 9 个 vec4 的暂存处：取值发生在 {@code environment()}，落进缓冲发生在 {@code upload()}。 */
   private final float[] environment = new float[FrameLayout.ENVIRONMENT_SLOTS];
   private float partialTick;
   final HeldLight heldLight = new HeldLight();
   boolean heldLighting;
   private Matrix4f worldProjection;
   private Matrix4f inverseHandProjection;
   private int cloudTextureWidth = 256;
   private Object world;
   private double x;
   private double y;
   private double z;
   private long lastTime;
   private long start = System.nanoTime();
   private int frame;
   private boolean valid;
   private boolean frameReset;

   /**
    * 帧 uniform 块的声明，由 {@link FrameLayout} 生成——布局不再有第二处抄写。
    */
   static String declaration() {
      return "layout(std140) uniform CalderaFrame {\n" + FrameLayout.block() + "};\n";
   }

   static boolean cameraReady(CameraRenderState camera, Matrix4fc view) {
      return camera != null && matrixReady(camera.projectionMatrix) && (view == null || matrixReady(view));
   }

   /**
    * {@code environment[]} 里某个 vec4 的起点槽位。
    * <p>
    * 那 36 个 float 就是 GLSL 里连续的 9 个 vec4，所以"哪个 vec4 落在数组的哪一段"由
    * {@link FrameLayout} 说了算，而不是在这一侧再抄一遍下标。
    */
   private static int env(FrameLayout.Field field) {
      return FrameLayout.slot(field);
   }

   private static boolean matrixReady(Matrix4fc matrix) {
      return matrix.isFinite() && Math.abs(matrix.determinant()) >= 1.0E-12F;
   }

   void handProjection(Matrix4fc projection) {
      this.inverseHandProjection = projection != null && matrixReady(projection) ? (new Matrix4f(projection)).invert() : null;
   }

   Matrix4fc inverseHandProjection() {
      return this.inverseHandProjection;
   }

   void projection(Matrix4fc projection) {
      this.inverseHandProjection = null;
      this.worldProjection = projection == null ? null : new Matrix4f(projection);
   }

   Matrix4fc projection(CameraRenderState camera) {
      return this.worldProjection != null ? this.worldProjection : camera.projectionMatrix;
   }

   void cloudTextureWidth(int width) {
      this.cloudTextureWidth = width;
   }

   static float cloudOffset(long ticks, float partialTick) {
      return cloudOffset(ticks, partialTick, 256);
   }

   static float cloudOffset(long ticks, float partialTick, int width) {
      return ((float)(ticks % ((long)width * 400L)) + partialTick) * 0.030000001F;
   }

   void environment(LevelRenderState state, ClientLevel level, float partialTick) {
      Arrays.fill(this.environment, 0.0F);
      this.partialTick = partialTick;
      this.heldLight.update(level, partialTick, this.heldLighting);
      if (level != null) {
         this.environment[env(FrameLayout.Field.WORLD_TIME_WEATHER_DIMENSION) + 0] = (float)Math.floorMod(level.getDefaultClockTime(), 24000L) + partialTick;
         this.environment[env(FrameLayout.Field.WORLD_TIME_WEATHER_DIMENSION) + 1] = level.getRainLevel(partialTick);
         this.environment[env(FrameLayout.Field.WORLD_TIME_WEATHER_DIMENSION) + 2] = level.getThunderLevel(partialTick);
         this.environment[env(FrameLayout.Field.WORLD_TIME_WEATHER_DIMENSION) + 3] = level.dimension().equals(Level.NETHER) ? -1.0F : (level.dimension().equals(Level.END) ? 1.0F : (level.dimension().equals(Level.OVERWORLD) ? 0.0F : 2.0F));
      }

      if (state != null) {
         this.environment[env(FrameLayout.Field.CLOUD_OFFSET_AND_GAME_TIME) + 0] = cloudOffset(state.gameTime, partialTick, this.cloudTextureWidth);
         this.environment[env(FrameLayout.Field.CLOUD_OFFSET_AND_GAME_TIME) + 1] = state.cloudHeight;
         if (level != null) {
            Vec3 eye = state.cameraRenderState.pos;
            double px = eye.x - (double)0.5F;
            double py = eye.y - (double)0.5F;
            double pz = eye.z - (double)0.5F;
            int bx = (int)Math.floor(px);
            int by = (int)Math.floor(py);
            int bz = (int)Math.floor(pz);
            float fx = (float)(px - (double)bx);
            float fy = (float)(py - (double)by);
            float fz = (float)(pz - (double)bz);

            for(int ix = 0; ix < 2; ++ix) {
               for(int iy = 0; iy < 2; ++iy) {
                  for(int iz = 0; iz < 2; ++iz) {
                     float weight = (ix == 0 ? 1.0F - fx : fx) * (iy == 0 ? 1.0F - fy : fy) * (iz == 0 ? 1.0F - fz : fz);
                      this.environment[env(FrameLayout.Field.CLOUD_OFFSET_AND_GAME_TIME) + 2] += weight * (float)level.getBrightness(LightLayer.SKY, new BlockPos(bx + ix, by + iy, bz + iz)) / 15.0F;
                  }
               }
            }
         }
      }

      if (state != null) {
         SkyRenderState sky = state.skyRenderState;
         Vector3f direction = new Vector3f();
         CustomCelestials.setCelestialDirection(sky.sunAngle, direction);
         this.environment[env(FrameLayout.Field.SUN_DIRECTION_AND_RAIN_BRIGHTNESS) + 0] = direction.x;
         this.environment[env(FrameLayout.Field.SUN_DIRECTION_AND_RAIN_BRIGHTNESS) + 1] = direction.y;
         this.environment[env(FrameLayout.Field.SUN_DIRECTION_AND_RAIN_BRIGHTNESS) + 2] = direction.z;
         this.environment[env(FrameLayout.Field.SUN_DIRECTION_AND_RAIN_BRIGHTNESS) + 3] = sky.rainBrightness;
         CustomCelestials.setCelestialDirection(sky.moonAngle, direction);
         this.environment[env(FrameLayout.Field.MOON_DIRECTION_AND_PHASE) + 0] = direction.x;
         this.environment[env(FrameLayout.Field.MOON_DIRECTION_AND_PHASE) + 1] = direction.y;
         this.environment[env(FrameLayout.Field.MOON_DIRECTION_AND_PHASE) + 2] = direction.z;
         this.environment[env(FrameLayout.Field.MOON_DIRECTION_AND_PHASE) + 3] = (float)sky.moonPhase.index();
         this.environment[env(FrameLayout.Field.SKY_COLOR_AND_STAR_BRIGHTNESS) + 0] = sky.skyColor.x();
         this.environment[env(FrameLayout.Field.SKY_COLOR_AND_STAR_BRIGHTNESS) + 1] = sky.skyColor.y();
         this.environment[env(FrameLayout.Field.SKY_COLOR_AND_STAR_BRIGHTNESS) + 2] = sky.skyColor.z();

         this.environment[env(FrameLayout.Field.SKY_COLOR_AND_STAR_BRIGHTNESS) + 3] = sky.starBrightness;
      }

   }

   void reset() {
      this.valid = false;
      this.frame = 0;
      this.start = System.nanoTime();
   }

   boolean discontinuity(CameraRenderState camera, Object currentWorld, Matrix4fc view) {
      return this.globalDiscontinuity(camera, currentWorld) || this.projectionChanged(this.projection(camera)) || view != null && view.m02() * this.previousView.m02() + view.m12() * this.previousView.m12() + view.m22() * this.previousView.m22() < 0.5F;
   }

   boolean globalDiscontinuity(CameraRenderState camera, Object currentWorld) {
      return !this.valid || this.world != currentWorld || camera == null || Math.pow(camera.pos.x - this.x, (double) 2.0F) + Math.pow(camera.pos.y - this.y, (double) 2.0F) + Math.pow(camera.pos.z - this.z, (double) 2.0F) > (double) 256.0F || System.nanoTime() - this.lastTime > 1000000000L;
   }

   GpuBufferSlice upload(CommandEncoder encoder, CameraRenderState camera, Matrix4fc view, int width, int height, boolean reset) {
      this.frameReset = reset;
      Matrix4f projection = camera != null ? new Matrix4f(this.projection(camera)) : new Matrix4f();
      Matrix4f model = view == null ? new Matrix4f() : new Matrix4f(view);
      if (matrixReady(projection) && matrixReady(model)) {
         projection.get(FrameLayout.offset(FrameLayout.Field.PROJECTION), this.bytes);
         model.get(FrameLayout.offset(FrameLayout.Field.VIEW), this.bytes);
         (new Matrix4f(projection)).invert().get(FrameLayout.offset(FrameLayout.Field.INVERSE_PROJECTION), this.bytes);
         (new Matrix4f(model)).invert().get(FrameLayout.offset(FrameLayout.Field.INVERSE_VIEW), this.bytes);
         (reset ? projection : this.previousProjection).get(FrameLayout.offset(FrameLayout.Field.PREVIOUS_PROJECTION), this.bytes);
         (reset ? model : this.previousView).get(FrameLayout.offset(FrameLayout.Field.PREVIOUS_VIEW), this.bytes);
         // 从这里往下四段是**顺序**写的：锚一次起点，之后靠 position 递增。
         this.bytes.position(FrameLayout.offset(FrameLayout.Field.CAMERA_DELTA_AND_HISTORY_VALID));
         this.bytes.putFloat(!reset && camera != null ? (float)(camera.pos.x - this.x) : 0.0F);
         this.bytes.putFloat(!reset && camera != null ? (float)(camera.pos.y - this.y) : 0.0F);
         this.bytes.putFloat(!reset && camera != null ? (float)(camera.pos.z - this.z) : 0.0F);
         this.bytes.putFloat(reset ? 0.0F : 1.0F);
         // TimeDeltaFrame 紧随其后（表里两者相邻，所以这里不锚点）。
         long now = System.nanoTime();
         this.bytes.putFloat((float)(now - this.start) * 1.0E-9F).putFloat(reset ? 0.0F : (float)(now - this.lastTime) * 1.0E-9F).putFloat(reset ? 0.0F : (float)this.frame).putFloat(this.partialTick);
         // ViewSizeAndInverse 同理，然后是那 9 个 vec4 的暂存数组。
         this.bytes.putFloat((float)width).putFloat((float)height).putFloat(1.0F / (float)width).putFloat(1.0F / (float)height);
         if (camera != null) {
             this.environment[env(FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE) + 0] = (float) camera.pos.x;
             this.environment[env(FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE) + 1] = (float)camera.pos.y;
             this.environment[env(FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE) + 2] = (float)camera.pos.z;
             this.environment[env(FrameLayout.Field.CAMERA_POSITION_LOW_AND_FAR_PLANE) + 0] = (float)(camera.pos.x - (double)this.environment[env(FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE) + 0]);
             this.environment[env(FrameLayout.Field.CAMERA_POSITION_LOW_AND_FAR_PLANE) + 1] = (float)(camera.pos.y - (double)this.environment[env(FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE) + 1]);
             this.environment[env(FrameLayout.Field.CAMERA_POSITION_LOW_AND_FAR_PLANE) + 2] = (float)(camera.pos.z - (double)this.environment[env(FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE) + 2]);

             float var10002;
             switch (camera.fogType) {
                 case WATER -> var10002 = 1.0F;
                 case LAVA -> var10002 = 2.0F;
                 case POWDER_SNOW -> var10002 = 3.0F;
                 default -> var10002 = 0.0F;
             }

             this.environment[env(FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE) + 3] = var10002;
            this.environment[env(FrameLayout.Field.CAMERA_POSITION_LOW_AND_FAR_PLANE) + 3] = camera.depthFar;
             FogData fog = camera.fogData;
             this.environment[env(FrameLayout.Field.FOG_COLOR_AND_START) + 0] = fog.color.x;
             this.environment[env(FrameLayout.Field.FOG_COLOR_AND_START) + 1] = fog.color.y;
             this.environment[env(FrameLayout.Field.FOG_COLOR_AND_START) + 2] = fog.color.z;

             this.environment[env(FrameLayout.Field.FOG_COLOR_AND_START) + 3] = fog.environmentalStart;
             this.environment[env(FrameLayout.Field.FOG_DISTANCES) + 0] = fog.environmentalEnd;
             this.environment[env(FrameLayout.Field.FOG_DISTANCES) + 1] = fog.renderDistanceStart;
             this.environment[env(FrameLayout.Field.FOG_DISTANCES) + 2] = fog.renderDistanceEnd;
             this.environment[env(FrameLayout.Field.FOG_DISTANCES) + 3] = fog.skyEnd;
         }

         for(float value : this.environment) {
            this.bytes.putFloat(value);
         }

         (this.inverseHandProjection == null ? new Matrix4f() : this.inverseHandProjection).get(FrameLayout.offset(FrameLayout.Field.INVERSE_HAND_PROJECTION), this.bytes);
         // HeldLight 那三个字段是顺序写的，所以这里只锚它的起点。
         this.bytes.position(FrameLayout.offset(FrameLayout.Field.HAND_PROJECTION_VALID));
         this.bytes.putFloat(this.inverseHandProjection == null ? 0.0F : 1.0F).putFloat(0.0F).putFloat(0.0F).putFloat(0.0F);
         this.heldLight.write(this.bytes, camera != null ? camera.pos : Vec3.ZERO);
         this.bytes.flip();
         return encoder.transientMemory().uploadGpu(this.bytes, (long)RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), 128);
      } else {
         throw new IllegalArgumentException("Invalid frame matrices");
      }
   }

   void commit(CameraRenderState camera, Matrix4fc view, Object currentWorld) {
      if (camera != null) {
         this.previousProjection.set(this.projection(camera));
      } else {
         this.previousProjection.identity();
      }

      if (view != null) {
         this.previousView.set(view);
      } else {
         this.previousView.identity();
      }

      if (camera != null) {
         this.x = camera.pos.x;
         this.y = camera.pos.y;
         this.z = camera.pos.z;
      }

      this.world = currentWorld;
      this.valid = true;
      this.frame = this.frameReset ? 1 : this.frame + 1;
      this.lastTime = System.nanoTime();
   }

   private boolean projectionChanged(Matrix4fc projection) {
      return projection != null && (Math.abs(projection.m00() - this.previousProjection.m00()) > Math.max(0.001F, Math.abs(this.previousProjection.m00()) * 0.05F) || Math.abs(projection.m11() - this.previousProjection.m11()) > Math.max(0.001F, Math.abs(this.previousProjection.m11()) * 0.05F));
   }
}
