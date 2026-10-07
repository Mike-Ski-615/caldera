package com.caldera.shaders.render.shadow;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.RenderTargetDescriptor.TextureProperties;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.caldera.shaders.config.ShaderQualityPreset;
import com.caldera.shaders.graph.NativePackRuntime;
import com.caldera.shaders.runtime.ShaderHost;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * 方向光阴影的 GPU 侧：RenderTarget 的分配与退役、UBO 上传、绑定。
 * <p>
 * <b>级联调度已经不在这里了。</b>哪几个级联这一帧要重画、每个级联画到哪里、用哪个矩阵，
 * 全部由 {@link CascadePlanner} 决策、装在 {@link CascadeSchedule} 里。这个类只负责：
 * 把游戏状态（相机、日月角、渲染距离、设备纹理上限、地形修订号）**作为参数**递给计划，
 * 然后按计划的结果做 GPU 侧的事。
 * <p>
 * 拆分的动机与 ADR-0003 一致：那套决策原先与 {@code RenderSystem}、{@code Minecraft} 混在一起，
 * 于是"第二帧会不会重画级联 1"这类问题无法在纯 JVM 里验证，而它错了只会安静地少画影子。
 * <p>
 * {@code beginCascade}/{@code endCascade} 那套重入协议**不在**本次改动范围内，原样保留。
 */
public final class DirectionalShadowRenderer {
   private static final int CASCADE_UBO_BYTES = 144;
   private static final int SHADOW_DATA_BYTES = 416;
   private static final Vector4f CLEAR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
   private static final ThreadLocal<Integer> ACTIVE_CASCADE = ThreadLocal.withInitial(() -> -1);
   private static final ThreadLocal<Boolean> ACTIVE_ENTITY_PASS = ThreadLocal.withInitial(() -> false);
   private static DirectionalShadowRenderer instance;
   /**
    * 游戏能力的来源，与 {@code NativePackRuntime}/{@code ShaderRuntime} 同一个 host 实例，
    * 由 {@code CalderaShadersClient} 在客户端初始化时装上。
    * <p>
    * 这一条端口是本次改动加的：计划要用"有效渲染距离"和"设备纹理边长上限"，
    * 而它们原先分别来自 {@code Minecraft.getInstance().options} 与
    * {@code RenderSystem.getDevice().getDeviceInfo().limits()}。计划本身不许再去够这两处。
    */
   private static ShaderHost host;
   private static RenderTarget localTarget;
   private static GpuBufferSlice localUniforms;
   private final RenderTarget[] targets = new RenderTarget[4];
   private final RenderTarget[] entityTargets = new RenderTarget[4];
   private final int[] targetSizes = new int[4];
   private final int[] entityTargetSizes = new int[4];
   private final ByteBuffer cascadeUpload = ByteBuffer.allocateDirect(CASCADE_UBO_BYTES).order(ByteOrder.nativeOrder());
   private final ByteBuffer shadowUpload = ByteBuffer.allocateDirect(SHADOW_DATA_BYTES).order(ByteOrder.nativeOrder());
   private final CascadePlanner planner = new CascadePlanner();
   /**
    * 本帧的计划。初值是"什么都没有"，与迁移前那些字段的初值一致。
    * <p>
    * 注意它**不在** {@link #close()} 里被清掉：那条不对称是迁移前就有的（见 {@code close()} 的注释）。
    */
   private CascadeSchedule schedule = CascadeSchedule.empty();
   private GpuBufferSlice cascadeSlice;
   private GpuBufferSlice shadowDataSlice;
   /**
    * 本帧的相机位置，只给 {@link #sectionIntersectsCascade} 用。
    * <p>
    * 它必须活到本帧的 Sodium 渲染之后，所以存在实例上而不是随着 {@code prepare} 的局部变量消失。
    */
   private double cameraX;
   private double cameraY;
   private double cameraZ;

   public static void beginLocal(RenderTarget target, GpuBufferSlice uniforms) {
      ACTIVE_CASCADE.set(0);
      localTarget = target;
      localUniforms = uniforms;
   }

   private DirectionalShadowRenderer() {
   }

   /**
    * 安装游戏能力端口。必须在第一次 {@link #prepare} 之前调用，由 {@code CalderaShadersClient} 与
    * {@code NativePackRuntime.install()} 并排调用——装的是**同一个** host 实例。
    */
   public static void install(ShaderHost shaderHost) {
      host = shaderHost;
   }

   private static ShaderHost requireHost() {
      ShaderHost installed = host;
      if (installed == null) {
         throw new IllegalStateException("Caldera shadow renderer has no installed ShaderHost: DirectionalShadowRenderer.install() must run during client initialization");
      }

      return installed;
   }

   public static DirectionalShadowRenderer get() {
      if (instance == null) {
         instance = new DirectionalShadowRenderer();
      }

      return instance;
   }

   public static boolean isRenderingShadowMap() {
      return (Integer)ACTIVE_CASCADE.get() >= 0;
   }

   public static int activeCascade() {
      return (Integer)ACTIVE_CASCADE.get();
   }

   public static void beginCascade(int cascade) {
      ACTIVE_CASCADE.set(cascade);
      ACTIVE_ENTITY_PASS.set(false);
   }

   public static void beginEntityCascade(int cascade) {
      ACTIVE_CASCADE.set(cascade);
      ACTIVE_ENTITY_PASS.set(true);
   }

   public static void endCascade() {
      ACTIVE_CASCADE.set(-1);
      localTarget = null;
      localUniforms = null;
      ACTIVE_ENTITY_PASS.set(false);
   }

   /**
    * 一帧的入口：收集输入 → 保证 GPU 资源 → 交给 {@link CascadePlanner} 决策 → 发布本帧的计划。
    * <p>
    * 顺序是行为的一部分，不能重排：{@code maxShadowSize} 必须先由设备上限求得，
    * {@code ensureResources} 必须先用它做分配并把"资源变过没有"作为计划的一个输入，
    * 计划才允许跑。反过来会让级联布局在资源重建的那一帧与纹理实际尺寸对不上。
    */
   public void prepare(LevelRenderState levelRenderState) {
      ShaderQualityPreset quality = ShadowService.quality();
      if (quality.enabled() && levelRenderState != null && levelRenderState.cameraRenderState != null) {
         ShaderHost installed = requireHost();
         int maxShadowSize = Math.min(installed.maxTextureSizeForFormat(GpuFormat.R8_UNORM), installed.maxTextureSizeForFormat(GpuFormat.D32_FLOAT));
         boolean resourcesChanged = this.ensureResources(CascadePlanner.targetSizes(quality, maxShadowSize), quality, maxShadowSize);
         CameraRenderState camera = levelRenderState.cameraRenderState;
         if (camera.pos != null) {
            this.cameraX = camera.pos.x;
            this.cameraY = camera.pos.y;
            this.cameraZ = camera.pos.z;
         } else {
            this.cameraX = (double)0.0F;
            this.cameraY = (double)0.0F;
            this.cameraZ = (double)0.0F;
         }

         SkyRenderState sky = levelRenderState.skyRenderState;
         this.schedule = this.planner.plan(
               quality,
               this.cameraX,
               this.cameraY,
               this.cameraZ,
               camera.viewRotationMatrix,
               camera.projectionMatrix,
               sky != null,
               sky == null ? 0.0F : sky.sunAngle,
               sky == null ? 0.0F : sky.moonAngle,
               installed.renderDistance(),
               ShadowService.distance(),
               ShadowService.enabled(),
               NativePackRuntime.animatedShadowCasters(),
               maxShadowSize,
               CascadePlanner.terrainRevision(),
               resourcesChanged);
      } else {
         this.schedule = this.planner.suspend();
      }
   }

   public int activeCascadeCount() {
      return this.schedule.activeCascadeCount();
   }

   /** 计划模块的只读统计；{@link ShadowService} 把它转给门禁记录用。 */
   public CascadePlanStats planStats() {
      return this.planner.stats();
   }

   /** 包可见：让测试能直接摆布计划状态，见 {@code DirectionalShadowRendererTeardownTest}。 */
   CascadePlanner planner() {
      return this.planner;
   }

   public long cascadeLayoutVersion(int cascade) {
      return this.schedule.layoutVersion(cascade);
   }

   public Matrix4fc cascadeMatrix(int cascade) {
      return this.schedule.cascadeMatrix(cascade);
   }

   public boolean shouldUpdateCascade(int cascade) {
      return this.schedule.cascadeUpdate(cascade);
   }

   public boolean shouldUpdateEntityCascade(int cascade) {
      return this.schedule.entityCascadeUpdate(cascade);
   }

   public float cascadeEnd(int cascade) {
      return this.schedule.cascadeEnd(cascade);
   }

   public Vector3f lightDirection(Vector3f destination) {
      return destination.set(this.schedule.lightDirection());
   }

   public Vector3f cameraForward(Vector3f destination) {
      return destination.set(this.schedule.cameraForward());
   }

   public float shadowDistance() {
      return this.schedule.shadowDistance();
   }

   public float entityShadowDistance() {
      return this.schedule.entityShadowDistance();
   }

   public float entityCascadeEnd(int cascade) {
      return this.schedule.entityCascadeEnd(cascade);
   }

   public RenderTarget target(int cascade) {
      return cascade >= 0 && cascade < 4 ? this.targets[cascade] : null;
   }

   public RenderTarget entityTarget(int cascade) {
      return cascade >= 0 && cascade < 4 ? this.entityTargets[cascade] : null;
   }

   public RenderTarget activeTarget() {
      if (localTarget != null) {
         return localTarget;
      } else {
         return (Boolean)ACTIVE_ENTITY_PASS.get() ? this.entityTarget(activeCascade()) : this.target(activeCascade());
      }
   }

   public boolean sectionIntersectsCascade(int cascade, int originX, int originY, int originZ) {
      return this.schedule.sectionIntersectsCascade(cascade, originX, originY, originZ, this.cameraX, this.cameraY, this.cameraZ);
   }

   public void uploadCascade(CommandEncoder encoder, int cascade) {
      this.cascadeUpload.clear();
      this.schedule.cascadeMatrix(cascade).get(this.cascadeUpload);
      this.cascadeUpload.position(64);
      Vector3fc light = this.schedule.lightDirection();
      putVec4(light.x(), light.y(), light.z(), 0.0F, this.cascadeUpload);
      this.cascadeUpload.position(80);
      this.schedule.inverseViewRotation().get(this.cascadeUpload);
      this.cascadeUpload.rewind();
      this.cascadeSlice = encoder.transientMemory().uploadGpu(this.cascadeUpload, (long)RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), 128);
   }

   public GpuBufferSlice cascadeSlice() {
      return localUniforms != null ? localUniforms : this.cascadeSlice;
   }

   public void uploadShadowData(CommandEncoder encoder) {
      this.shadowUpload.clear();
      int activeCascadeCount = this.schedule.activeCascadeCount();

      for(int i = 0; i < 4; ++i) {
         this.schedule.cascadeMatrix(i).get(this.shadowUpload);
         this.shadowUpload.position((i + 1) * 64);
      }

      for(int i = 0; i < 4; ++i) {
         float depthRange = i < activeCascadeCount ? this.schedule.depthRange(i) : 0.0F;
         putVec4(this.schedule.texelWorldSize(i), i == 0 ? 0.0F : this.schedule.cascadeEndSlot(i - 1), this.schedule.cascadeEndSlot(i), depthRange, this.shadowUpload);
      }

      Vector3fc light = this.schedule.lightDirection();
      putVec4(light.x(), light.y(), light.z(), 0.0F, this.shadowUpload);
      float var10000;
      switch (ShadowService.quality()) {
         case LOW -> var10000 = 1.0F;
         case MEDIUM -> var10000 = 4.0F;
         case HIGH -> var10000 = 9.0F;
         case ULTRA -> var10000 = 16.0F;
         case OFF -> var10000 = 1.0F;
         default -> throw new MatchException((String)null, (Throwable)null);
      }

      float filterSamples = var10000;
      putVec4(1.0F * this.schedule.celestialShadowFade(), 0.0F, 0.08F, filterSamples, this.shadowUpload);
      this.schedule.inverseViewRotation().get(this.shadowUpload);
      this.shadowUpload.rewind();
      this.shadowDataSlice = encoder.transientMemory().uploadGpu(this.shadowUpload, (long)RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), 128);
   }

   public GpuBufferSlice shadowDataSlice() {
      return this.shadowDataSlice;
   }

   public boolean resourcesReady() {
      if (this.shadowDataSlice == null) {
         return false;
      } else {
         for(RenderTarget target : this.targets) {
            if (target == null || target.getDepthTextureView() == null || target.getColorTextureView() == null) {
               return false;
            }
         }

         for(int cascade = 0; cascade < 2; ++cascade) {
            RenderTarget target = this.entityTargets[cascade];
            if (target == null || target.getDepthTextureView() == null || target.getColorTextureView() == null) {
               return false;
            }
         }

         return true;
      }
   }

   public void clearCascade(CommandEncoder encoder, int cascade) {
      RenderTarget target = this.target(cascade);
      if (target != null && target.getColorTexture() != null && target.getDepthTexture() != null) {
         encoder.clearColorAndDepthTextures(target.getColorTexture(), CLEAR, target.getDepthTexture(), (double)1.0F);
      }

   }

   public void clearEntityCascade(CommandEncoder encoder, int cascade) {
      RenderTarget target = this.entityTarget(cascade);
      if (target != null && target.getColorTexture() != null && target.getDepthTexture() != null) {
         encoder.clearColorAndDepthTextures(target.getColorTexture(), CLEAR, target.getDepthTexture(), (double)1.0F);
      }

   }

   private boolean ensureResources(int[] sizes, ShaderQualityPreset quality, int maxShadowSize) {
      boolean sameSizes = this.targets[0] != null;

      for(int i = 0; i < 4; ++i) {
         sameSizes &= this.targetSizes[i] == sizes[i];
         if (i < 2) {
            sameSizes &= this.entityTargetSizes[i] == Math.min(maxShadowSize, ShadowService.enabled() ? CascadePlanner.entityTargetSize(quality, sizes, i) : 1);
         }
      }

      if (sameSizes) {
         return false;
      } else {
         RenderTarget[] nextTerrain = new RenderTarget[4];
         RenderTarget[] nextEntities = new RenderTarget[4];
         int[] nextEntitySizes = new int[4];

         try {
            for(int i = 0; i < 4; ++i) {
               RenderTargetDescriptor descriptor = new RenderTargetDescriptor(sizes[i], sizes[i], new RenderTargetDescriptor.TextureProperties(CLEAR, GpuFormat.R8_UNORM), TextureProperties.DEFAULT_DEPTH);
               nextTerrain[i] = descriptor.allocate();
               descriptor.prepare(nextTerrain[i]);
               if (i < 2) {
                  int entitySize = Math.min(maxShadowSize, ShadowService.enabled() ? CascadePlanner.entityTargetSize(quality, sizes, i) : 1);
                  nextEntitySizes[i] = entitySize;
                  RenderTargetDescriptor entityDescriptor = new RenderTargetDescriptor(entitySize, entitySize, new RenderTargetDescriptor.TextureProperties(CLEAR, GpuFormat.R8_UNORM), TextureProperties.DEFAULT_DEPTH);
                  nextEntities[i] = entityDescriptor.allocate();
                  entityDescriptor.prepare(nextEntities[i]);
               }
            }
         } catch (RuntimeException failure) {
            retireTargets(nextTerrain, nextEntities);
            throw failure;
         }

         retireTargets((RenderTarget[])this.targets.clone(), (RenderTarget[])this.entityTargets.clone());
         System.arraycopy(nextTerrain, 0, this.targets, 0, 4);
         System.arraycopy(nextEntities, 0, this.entityTargets, 0, 4);
         System.arraycopy(sizes, 0, this.targetSizes, 0, 4);
         System.arraycopy(nextEntitySizes, 0, this.entityTargetSizes, 0, 4);
         return true;
      }
   }

   private static void retireTargets(RenderTarget[] terrain, RenderTarget[] entities) {
      RenderSystem.queueFencedTask(() -> {
         for(RenderTarget target : terrain) {
            if (target != null) {
               target.destroyBuffers();
            }
         }

         for(RenderTarget target : entities) {
            if (target != null) {
               target.destroyBuffers();
            }
         }

      });
   }

   public static void retireUnused() {
      if (instance != null && !ShadowService.enabled() && NativePackRuntime.shadowQuality() <= 0) {
         DirectionalShadowRenderer old = instance;
         instance = null;
         RenderSystem.queueFencedTask(() -> old.destroyTargets());
      }
   }

   private void destroyTargets() {
      for(int i = 0; i < 4; ++i) {
         if (this.targets[i] != null) {
            this.targets[i].destroyBuffers();
            this.targets[i] = null;
         }

         if (this.entityTargets[i] != null) {
            this.entityTargets[i].destroyBuffers();
            this.entityTargets[i] = null;
         }

         this.targetSizes[i] = 0;
         this.entityTargetSizes[i] = 0;
      }

      // 迁移前这两行在同一个循环里：纹素世界尺寸与深度范围是与贴图分辨率绑定的，纹理拆了就该归零。
      // 它们现在住在计划模块里，所以这一步显式跨过接缝。
      this.planner.forgetTextureDerivedState();
   }

   private static void putVec4(float x, float y, float z, float w, ByteBuffer buffer) {
      buffer.putFloat(x);
      buffer.putFloat(y);
      buffer.putFloat(z);
      buffer.putFloat(w);
   }

   /**
    * 拆除 GPU 资源。可以重复调用；什么都没分配过时也是安全的。
    * <p>
    * <b>它不清理计划状态。</b>这条不对称是**故意的**、也是迁移前就有的：
    * {@link #ensureResources}（经由 {@code retireTargets}）与 {@link #retireUnused} 都会在重建资源时
    * 让"上一次的尺寸/资源"归零，而 {@code close()} 只拆 RenderTarget，不动帧号、布局版本、
    * 每个级联上次渲染的位置。换句话说：拿着同一个实例继续用的人，看到的计划历史是连续的。
    * 这里不改它——改它就不是行为保持的提取了。
    * <p>
    * <b>另一处也刻意不动的不一致：</b>这里 {@code destroyBuffers()} 是**同步**调用的，而
    * {@code ensureResources}/{@code retireUnused} 走 {@code RenderSystem.queueFencedTask} 排到栅栏之后。
    * 这个分歧是迁移前就存在的，没有被本次提取引入，也**故意不在本次修复**：`close()` 的调用方是
    * {@code MinecraftShaderHost.closeReloadableResources()}，而 {@code ShaderRuntime} 在
    * {@code reloadResources()} **之前一步**就调它——也就是说当前帧的 GPU 命令可能仍在使用这些纹理，
    * 同步销毁疑似 use-after-free。修它要动的是拆资源的时序，属于另一个改动，不在本次范围。
    */
   public static void close() {
      if (instance != null) {
         instance.destroyTargets();
         instance = null;
      }
   }
}
