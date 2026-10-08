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
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

final class GraphFrame {
   private final Matrix4f previousProjection = new Matrix4f();
   private final Matrix4f previousView = new Matrix4f();
   private final ByteBuffer bytes = ByteBuffer.allocateDirect(FrameLayout.size()).order(ByteOrder.nativeOrder());
   /** 那 9 个 vec4 的暂存处：取值发生在 {@code environment()}，落进缓冲发生在 {@code fill()}。 */
   private final float[] environment = new float[FrameLayout.ENVIRONMENT_SLOTS];
   /**
    * 时间来源。**注入而不是直接调 {@code System.nanoTime()}**，理由有两条。
    * <p>
    * 一是可测：{@code TimeDeltaFrame} 那一段写的是"距起点多久、距上一帧多久"，而帧号与
    * {@code partialTick} 也在同一段里；不把时钟交出来，帧 uniform 的字节就无法与金样比较
    * （这是候选 6 的"不可测中心"里最难缠的一处）。
    * <p>
    * 二是它本来就是一个**可变的输入**：{@code discontinuity} 的超时判定、
    * {@code commit} 记下的"上一帧时刻"都用它。生产用它做基准没有变，只是现在说得出它是什么。
    */
   private final java.util.function.LongSupplier clock;
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
   private long start;
   private int frame;
   private boolean valid;
   private boolean frameReset;

   GraphFrame() {
      this(System::nanoTime);
   }

   /** 包级可见：测试用固定时钟驱动，好让帧 uniform 的字节可以逐字比对。 */
   GraphFrame(java.util.function.LongSupplier clock) {
      this.clock = clock;
      this.start = this.nanoTime();
   }

   private long nanoTime() {
      return this.clock.getAsLong();
   }

   /**
    * 刚被 {@link #fill} 写好的那个缓冲（{@code fill} 之后它处于 flip 状态）。
    * <p>
    * 包级可见**只**为让测试读它——帧 uniform 写进去的字节是候选 6 要钉的东西，而它是私有字段。
    * 生产侧没有任何读取者：{@link #upload} 拿到它之后立刻交给编码器。
    */
   java.nio.ByteBuffer uniformBytes() {
      return this.bytes;
   }

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

   /** 转发手持物品的世界位置给 {@link HeldLight}——它是这个值的所有者与唯一消费者。 */
   void captureHeldItemWorldPosition(float x, float y, float z, boolean mainHand) {
      this.heldLight.captureItemWorldPosition(x, y, z, mainHand);
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
      this.partialTick = partialTick;
      fillEnvironment(this.environment, state, level, partialTick, this.cloudTextureWidth, this.heldLight, this.heldLighting);
   }

   /**
    * 把本帧的环境状态填进那 9 个 vec4。
    * <p>
    * <b>它是静态纯函数，这是候选 6 的核心收益。</b>它本来就是纯逻辑（不碰 GPU、不读静态状态），
    * 只是原先住在 {@link GraphFrame} 里——而那个类的 {@code upload()} 要真的设备，于是这条路径
    * 在纯 JVM 里根本驱动不了。代价已经付过一次：{@code sky.skyColor} 为 null 时这里抛过 NPE，
    * 而它的后果不是画错，是 {@link SceneFrame} 把整份光影包**暂停**掉且此后不再恢复。
    * 现在这份状态可以造出来喂给它，于是"半填充的状态"第一次有守卫。
    * <p>
    * {@code environment} 是等长的调用方数组：取值与落进缓冲因此分成两步，
    * 见 {@code GraphFrame.fill()} 与门禁里那次"同一帧的两次采样"。
    */
   static void fillEnvironment(float[] environment, LevelRenderState state, ClientLevel level, float partialTick, int cloudTextureWidth, HeldLight heldLight, boolean heldLighting) {
      Arrays.fill(environment, 0.0F);
      heldLight.update(level, partialTick, heldLighting, state);
      if (level != null) {
         environment[env(FrameLayout.Field.WORLD_TIME_WEATHER_DIMENSION) + 0] = (float)Math.floorMod(level.getDefaultClockTime(), 24000L) + partialTick;
         environment[env(FrameLayout.Field.WORLD_TIME_WEATHER_DIMENSION) + 1] = level.getRainLevel(partialTick);
         environment[env(FrameLayout.Field.WORLD_TIME_WEATHER_DIMENSION) + 2] = level.getThunderLevel(partialTick);
         environment[env(FrameLayout.Field.WORLD_TIME_WEATHER_DIMENSION) + 3] = level.dimension().equals(Level.NETHER) ? -1.0F : (level.dimension().equals(Level.END) ? 1.0F : (level.dimension().equals(Level.OVERWORLD) ? 0.0F : 2.0F));
      }

      if (state != null) {
         environment[env(FrameLayout.Field.CLOUD_OFFSET_AND_GAME_TIME) + 0] = cloudOffset(state.gameTime, partialTick, cloudTextureWidth);
         environment[env(FrameLayout.Field.CLOUD_OFFSET_AND_GAME_TIME) + 1] = state.cloudHeight;
         CameraRenderState camera = state.cameraRenderState;
         // cameraRenderState 与它的 pos 都可空：vanilla 的提取器有守卫，我们这里也得有。
         // 与 skyColor 那处同类——一帧少了环境数据，远好过整份光影包停摆。
         if (level != null && camera != null && camera.pos != null) {
            Vec3 eye = camera.pos;
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
                     environment[env(FrameLayout.Field.CLOUD_OFFSET_AND_GAME_TIME) + 2] += weight * (float)level.getBrightness(LightLayer.SKY, new BlockPos(bx + ix, by + iy, bz + iz)) / 15.0F;
                  }
               }
            }
         }
      }

      if (state != null) {
         SkyRenderState sky = state.skyRenderState;
         Vector3f direction = new Vector3f();
         CustomCelestials.setCelestialDirection(sky.sunAngle, direction);
         environment[env(FrameLayout.Field.SUN_DIRECTION_AND_RAIN_BRIGHTNESS) + 0] = direction.x;
         environment[env(FrameLayout.Field.SUN_DIRECTION_AND_RAIN_BRIGHTNESS) + 1] = direction.y;
         environment[env(FrameLayout.Field.SUN_DIRECTION_AND_RAIN_BRIGHTNESS) + 2] = direction.z;
         environment[env(FrameLayout.Field.SUN_DIRECTION_AND_RAIN_BRIGHTNESS) + 3] = sky.rainBrightness;
         CustomCelestials.setCelestialDirection(sky.moonAngle, direction);
         environment[env(FrameLayout.Field.MOON_DIRECTION_AND_PHASE) + 0] = direction.x;
         environment[env(FrameLayout.Field.MOON_DIRECTION_AND_PHASE) + 1] = direction.y;
         environment[env(FrameLayout.Field.MOON_DIRECTION_AND_PHASE) + 2] = direction.z;
         environment[env(FrameLayout.Field.MOON_DIRECTION_AND_PHASE) + 3] = (float)sky.moonPhase.index();
         // 天空色是**可空**的：SkyRenderState 的构造器与 reset() 都不设它，唯一的写入者是
         // SkyRenderer.extractRenderState，而那一步在 vanilla 里是有守卫的（skyRenderer != null）。
         // 也就是说我们可能拿到一份"还没被提取过"的状态，此时解引用会抛 NPE——而它的后果不是画错，
         // 是 SceneFrame 把整份光影包**暂停**掉，且此后不再恢复。所以这里退回中性值 0（那几组槽位
         // 本来就由 environment[] 的 Arrays.fill(0) 预置），一帧没有天空色远好过整个包停摆。
         if (sky.skyColor != null) {
            environment[env(FrameLayout.Field.SKY_COLOR_AND_STAR_BRIGHTNESS) + 0] = sky.skyColor.x();
            environment[env(FrameLayout.Field.SKY_COLOR_AND_STAR_BRIGHTNESS) + 1] = sky.skyColor.y();
            environment[env(FrameLayout.Field.SKY_COLOR_AND_STAR_BRIGHTNESS) + 2] = sky.skyColor.z();
         }

         environment[env(FrameLayout.Field.SKY_COLOR_AND_STAR_BRIGHTNESS) + 3] = sky.starBrightness;
      }

   }

   void reset() {
      this.valid = false;
      this.frame = 0;
      this.start = this.nanoTime();
   }

   boolean discontinuity(CameraRenderState camera, Object currentWorld, Matrix4fc view) {
      return this.globalDiscontinuity(camera, currentWorld) || this.projectionChanged(this.projection(camera)) || view != null && view.m02() * this.previousView.m02() + view.m12() * this.previousView.m12() + view.m22() * this.previousView.m22() < 0.5F;
   }

   boolean globalDiscontinuity(CameraRenderState camera, Object currentWorld) {
      // camera.pos 可空（见 fillEnvironment 里同一条注释）：没有位置时谈不上"瞬移了多远"。
      return !this.valid || this.world != currentWorld || camera == null || camera.pos == null || Math.pow(camera.pos.x - this.x, (double) 2.0F) + Math.pow(camera.pos.y - this.y, (double) 2.0F) + Math.pow(camera.pos.z - this.z, (double) 2.0F) > (double) 256.0F || this.nanoTime() - this.lastTime > 1000000000L;
   }

   GpuBufferSlice upload(CommandEncoder encoder, CameraRenderState camera, Matrix4fc view, int width, int height, boolean reset) {
      this.fill(camera, view, width, height, reset);
      return encoder.transientMemory().uploadGpu(this.bytes, (long)RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), 128);
   }

   /**
    * 把本帧协议写进 {@link #bytes}。**不碰编码器、不碰 {@code RenderSystem}**——这是候选 6 拆出来的那一半。
    * <p>
    * 它原先与那次 {@code uploadGpu} 挤在同一个方法里，于是"写进去的字节对不对"在纯 JVM 里没有观测点：
    * {@code FrameLayoutTest} 只能钉住布局（生成文本、偏移、总长），钉不住**写**。而这一类错不会抛异常——
    * 着色器安静地读到隔壁字段的值，画出一帧不对的画面。
    * <p>
    * 写入顺序是协议的一部分，不能重排：前四段是**顺序**写的（锚一次起点，之后靠 position 递增），
    * 相邻字段之间靠那个假设连接。
    */
   void fill(CameraRenderState camera, Matrix4fc view, int width, int height, boolean reset) {
      this.frameReset = reset;
      Matrix4f projection = camera != null ? new Matrix4f(this.projection(camera)) : new Matrix4f();
      Matrix4f model = view == null ? new Matrix4f() : new Matrix4f(view);
      if (!matrixReady(projection) || !matrixReady(model)) {
         throw new IllegalArgumentException("Invalid frame matrices");
      }

      boolean hasCamera = camera != null && camera.pos != null;
      projection.get(FrameLayout.offset(FrameLayout.Field.PROJECTION), this.bytes);
      model.get(FrameLayout.offset(FrameLayout.Field.VIEW), this.bytes);
      (new Matrix4f(projection)).invert().get(FrameLayout.offset(FrameLayout.Field.INVERSE_PROJECTION), this.bytes);
      (new Matrix4f(model)).invert().get(FrameLayout.offset(FrameLayout.Field.INVERSE_VIEW), this.bytes);
      (reset ? projection : this.previousProjection).get(FrameLayout.offset(FrameLayout.Field.PREVIOUS_PROJECTION), this.bytes);
      (reset ? model : this.previousView).get(FrameLayout.offset(FrameLayout.Field.PREVIOUS_VIEW), this.bytes);
      // 从这里往下四段是**顺序**写的：锚一次起点，之后靠 position 递增。
      this.bytes.position(FrameLayout.offset(FrameLayout.Field.CAMERA_DELTA_AND_HISTORY_VALID));
      this.bytes.putFloat(!reset && hasCamera ? (float)(camera.pos.x - this.x) : 0.0F);
      this.bytes.putFloat(!reset && hasCamera ? (float)(camera.pos.y - this.y) : 0.0F);
      this.bytes.putFloat(!reset && hasCamera ? (float)(camera.pos.z - this.z) : 0.0F);
      this.bytes.putFloat(reset ? 0.0F : 1.0F);
      // TimeDeltaFrame 紧随其后（表里两者相邻，所以这里不锚点）。
      long now = this.nanoTime();
      this.bytes.putFloat((float)(now - this.start) * 1.0E-9F).putFloat(reset ? 0.0F : (float)(now - this.lastTime) * 1.0E-9F).putFloat(reset ? 0.0F : (float)this.frame).putFloat(this.partialTick);
      // ViewSizeAndInverse 同理，然后是那 9 个 vec4 的暂存数组。
      this.bytes.putFloat((float)width).putFloat((float)height).putFloat(1.0F / (float)width).putFloat(1.0F / (float)height);
      if (hasCamera) {
         this.environment[env(FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE) + 0] = (float)camera.pos.x;
         this.environment[env(FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE) + 1] = (float)camera.pos.y;
         this.environment[env(FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE) + 2] = (float)camera.pos.z;
         this.environment[env(FrameLayout.Field.CAMERA_POSITION_LOW_AND_FAR_PLANE) + 0] = (float)(camera.pos.x - (double)this.environment[env(FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE) + 0]);
         this.environment[env(FrameLayout.Field.CAMERA_POSITION_LOW_AND_FAR_PLANE) + 1] = (float)(camera.pos.y - (double)this.environment[env(FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE) + 1]);
         this.environment[env(FrameLayout.Field.CAMERA_POSITION_LOW_AND_FAR_PLANE) + 2] = (float)(camera.pos.z - (double)this.environment[env(FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE) + 2]);

         // fogType 也可空（构造器不设它），空值走 default 分支也就是"没有特殊雾"。
         // 这是同一类防御的第三处：pos、fogData、fogType 都是"vanilla 提取器负责填、我们不假设它填过"
         // 的字段，而任一处的 NPE 都会让 SceneFrame 暂停整份光影包。
         float fogType;
         switch (camera.fogType == null ? FogType.NONE : camera.fogType) {
             case WATER -> fogType = 1.0F;
             case LAVA -> fogType = 2.0F;
             case POWDER_SNOW -> fogType = 3.0F;
             default -> fogType = 0.0F;
         }

         this.environment[env(FrameLayout.Field.CAMERA_POSITION_HIGH_AND_FOG_TYPE) + 3] = fogType;
         this.environment[env(FrameLayout.Field.CAMERA_POSITION_LOW_AND_FAR_PLANE) + 3] = camera.depthFar;
         // fogData 与它里面的 color 都可空（构造器不设它们），与 skyColor 同类。防御是必要的：
         // 这里的 NPE 会让 SceneFrame 把整份光影包暂停掉，而不是画错一帧。
         FogData fog = camera.fogData;
         if (fog != null && fog.color != null) {
            this.environment[env(FrameLayout.Field.FOG_COLOR_AND_START) + 0] = fog.color.x;
            this.environment[env(FrameLayout.Field.FOG_COLOR_AND_START) + 1] = fog.color.y;
            this.environment[env(FrameLayout.Field.FOG_COLOR_AND_START) + 2] = fog.color.z;
         }

         if (fog != null) {
            this.environment[env(FrameLayout.Field.FOG_COLOR_AND_START) + 3] = fog.environmentalStart;
            this.environment[env(FrameLayout.Field.FOG_DISTANCES) + 0] = fog.environmentalEnd;
            this.environment[env(FrameLayout.Field.FOG_DISTANCES) + 1] = fog.renderDistanceStart;
            this.environment[env(FrameLayout.Field.FOG_DISTANCES) + 2] = fog.renderDistanceEnd;
            this.environment[env(FrameLayout.Field.FOG_DISTANCES) + 3] = fog.skyEnd;
         }
      }

      for(float value : this.environment) {
         this.bytes.putFloat(value);
      }

      (this.inverseHandProjection == null ? new Matrix4f() : this.inverseHandProjection).get(FrameLayout.offset(FrameLayout.Field.INVERSE_HAND_PROJECTION), this.bytes);
      // HeldLight 那三个字段是顺序写的，所以这里只锚它的起点。
      this.bytes.position(FrameLayout.offset(FrameLayout.Field.HAND_PROJECTION_VALID));
      this.bytes.putFloat(this.inverseHandProjection == null ? 0.0F : 1.0F).putFloat(0.0F).putFloat(0.0F).putFloat(0.0F);
      this.heldLight.write(this.bytes, camera != null && camera.pos != null ? camera.pos : Vec3.ZERO);
      this.bytes.flip();
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

      if (camera != null && camera.pos != null) {
         this.x = camera.pos.x;
         this.y = camera.pos.y;
         this.z = camera.pos.z;
      }

      this.world = currentWorld;
      this.valid = true;
      this.frame = this.frameReset ? 1 : this.frame + 1;
      this.lastTime = this.nanoTime();
   }

   private boolean projectionChanged(Matrix4fc projection) {
      return projection != null && (Math.abs(projection.m00() - this.previousProjection.m00()) > Math.max(0.001F, Math.abs(this.previousProjection.m00()) * 0.05F) || Math.abs(projection.m11() - this.previousProjection.m11()) > Math.max(0.001F, Math.abs(this.previousProjection.m11()) * 0.05F));
   }
}
