package com.caldera.shaders.render.shadow;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.RenderTargetDescriptor.TextureProperties;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.caldera.shaders.config.ShaderQualityPreset;
import com.caldera.shaders.graph.NativePackRuntime;
import com.caldera.shaders.render.sky.CustomCelestials;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

public final class DirectionalShadowRenderer {
   private static final int CASCADE_COUNT = 4;
   private static final int ENTITY_CASCADE_COUNT = 1;
   private static final int ENTITY_TARGET_COUNT = 2;
   private static final float CELESTIAL_FADE_START = 0.02F;
   private static final float CELESTIAL_FADE_END = 0.2F;
   private static final float SHADOW_STRENGTH = 1.0F;
   private static final int[] CASCADE_UPDATE_INTERVALS = new int[]{3, 8, 16, 32};
   private static final int[] ENTITY_UPDATE_INTERVALS = new int[]{1, 1, 1, 1};
   private static final double[] CASCADE_MOVEMENT_LIMITS = new double[]{(double)1.5F, (double)4.0F, (double)12.0F, (double)24.0F};
   private static final int CASCADE_UBO_BYTES = 144;
   private static final int SHADOW_DATA_BYTES = 416;
   private static final Vector4f CLEAR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
   private static final ThreadLocal<Integer> ACTIVE_CASCADE = ThreadLocal.withInitial(() -> -1);
   private static final ThreadLocal<Boolean> ACTIVE_ENTITY_PASS = ThreadLocal.withInitial(() -> false);
   private static DirectionalShadowRenderer instance;
   private static RenderTarget localTarget;
   private static GpuBufferSlice localUniforms;
   private final RenderTarget[] targets = new RenderTarget[4];
   private final RenderTarget[] entityTargets = new RenderTarget[4];
   private final Matrix4f[] cascadeMatrices = new Matrix4f[4];
   private final Matrix4f[] renderedCascadeMatrices = new Matrix4f[4];
   private final Vector3f[] frustumCorners = new Vector3f[8];
   private final Vector3f[] renderedLightDirections = new Vector3f[4];
   private final Vector3f[] renderedCameraForwards = new Vector3f[4];
   private final float[] cascadeEnds = new float[4];
   private final float[] cascadeTexelWorldSizes = new float[4];
   private final float[] cascadeDepthRanges = new float[4];
   private final int[] targetSizes = new int[4];
   private final int[] entityTargetSizes = new int[4];
   private final boolean[] cascadeUpdates = new boolean[4];
   private final boolean[] cascadeInitialized = new boolean[4];
   private final long[] cascadeLayoutVersions = new long[4];
   private final boolean[] entityCascadeUpdates = new boolean[4];
   private final boolean[] entityCascadeInitialized = new boolean[4];
   private final double[] renderedCameraX = new double[4];
   private final double[] renderedCameraY = new double[4];
   private final double[] renderedCameraZ = new double[4];
   private final long[] renderedFrameSerial = new long[4];
   private final long[] renderedTerrainRevision = new long[4];
   private final long[] renderedEntityFrameSerial = new long[4];
   private final ByteBuffer cascadeUpload = ByteBuffer.allocateDirect(144).order(ByteOrder.nativeOrder());
   private final ByteBuffer shadowUpload = ByteBuffer.allocateDirect(416).order(ByteOrder.nativeOrder());
   private GpuBufferSlice cascadeSlice;
   private GpuBufferSlice shadowDataSlice;
   private int activeCascadeCount;
   private final Vector3f lightDirection = new Vector3f(0.0F, -1.0F, 0.0F);
   private final Vector3f sunDirection = new Vector3f();
   private final Vector3f moonDirection = new Vector3f();
   private final Vector3f cameraForward = new Vector3f(0.0F, 0.0F, -1.0F);
   private final Matrix4f inverseViewRotation = new Matrix4f();
   private final Matrix4f lastProjection = (new Matrix4f()).zero();
   private long frameSerial;
   private float celestialShadowFade = 1.0F;
   private float lastCoverage = -1.0F;
   private int lastActiveCascadeCount = -1;
   private double cameraX;
   private double cameraY;
   private double cameraZ;

   public static void beginLocal(RenderTarget target, GpuBufferSlice uniforms) {
      ACTIVE_CASCADE.set(0);
      localTarget = target;
      localUniforms = uniforms;
   }

   private DirectionalShadowRenderer() {
      for(int i = 0; i < 4; ++i) {
         this.cascadeMatrices[i] = new Matrix4f();
         this.renderedCascadeMatrices[i] = new Matrix4f();
         this.renderedLightDirections[i] = new Vector3f();
         this.renderedCameraForwards[i] = new Vector3f();
      }

      for(int i = 0; i < this.frustumCorners.length; ++i) {
         this.frustumCorners[i] = new Vector3f();
      }

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

   public void prepare(LevelRenderState levelRenderState) {
      ShaderQualityPreset quality = ShadowService.quality();
      if (quality.enabled() && levelRenderState != null && levelRenderState.cameraRenderState != null) {
         byte var10000;
         switch (quality) {
            case LOW:
               var10000 = 1;
               break;
            case MEDIUM:
               var10000 = 3;
               break;
            case HIGH:
            case ULTRA:
               var10000 = 4;
               break;
            case OFF:
               var10000 = 0;
               break;
            default:
               throw new MatchException((String)null, (Throwable)null);
         }

         int nextActiveCascadeCount = var10000;
         DeviceLimits limits = RenderSystem.getDevice().getDeviceInfo().limits();
         int maxShadowSize = Math.min(limits.maxTextureSizeForFormat(GpuFormat.R8_UNORM), limits.maxTextureSizeForFormat(GpuFormat.D32_FLOAT));
         boolean resourcesChanged = this.ensureResources(targetSizes(quality, maxShadowSize), quality, maxShadowSize);
         this.activeCascadeCount = nextActiveCascadeCount;
         ++this.frameSerial;
         if (levelRenderState.skyRenderState != null) {
            CustomCelestials.setCelestialDirection(levelRenderState.skyRenderState.sunAngle, this.sunDirection);
            CustomCelestials.setCelestialDirection(levelRenderState.skyRenderState.moonAngle, this.moonDirection);
            this.celestialShadowFade = smoothstep(0.02F, 0.2F, Math.abs(this.sunDirection.y - this.moonDirection.y));
            this.lightDirection.set(this.sunDirection.y >= this.moonDirection.y ? this.sunDirection : this.moonDirection);
            if (this.lightDirection.y < 0.08F) {
               this.lightDirection.y = 0.08F;
            }

            this.lightDirection.normalize();
         } else {
            this.lightDirection.set(-0.35F, 0.82F, -0.44F).normalize();
            this.celestialShadowFade = 1.0F;
         }

         if (levelRenderState.cameraRenderState.viewRotationMatrix != null) {
            this.inverseViewRotation.set(levelRenderState.cameraRenderState.viewRotationMatrix).invert();
            this.cameraForward.set(0.0F, 0.0F, -1.0F);
            this.inverseViewRotation.transformDirection(this.cameraForward).normalize();
         } else {
            this.inverseViewRotation.identity();
            this.cameraForward.set(0.0F, 0.0F, -1.0F);
         }

         if (levelRenderState.cameraRenderState.pos != null) {
            this.cameraX = levelRenderState.cameraRenderState.pos.x;
            this.cameraY = levelRenderState.cameraRenderState.pos.y;
            this.cameraZ = levelRenderState.cameraRenderState.pos.z;
         } else {
            this.cameraX = (double)0.0F;
            this.cameraY = (double)0.0F;
            this.cameraZ = (double)0.0F;
         }

         float coverage = Math.min(ShadowService.distance(), Math.max(64.0F, (float)Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0F));
         boolean layoutChanged = resourcesChanged || !this.lastProjection.equals(levelRenderState.cameraRenderState.projectionMatrix, 1.0E-4F) || this.lastActiveCascadeCount != this.activeCascadeCount || Math.abs(this.lastCoverage - coverage) > 0.5F;
         this.lastProjection.set(levelRenderState.cameraRenderState.projectionMatrix);
         this.lastActiveCascadeCount = this.activeCascadeCount;
         this.lastCoverage = coverage;
         float[] splits = splitDistances(coverage, this.activeCascadeCount);
         Matrix4f inverseProjection = (new Matrix4f(levelRenderState.cameraRenderState.projectionMatrix)).invert();

         for(int i = 0; i < 4; ++i) {
            float end = i < this.activeCascadeCount ? splits[i] : 0.0F;
            if (i < this.activeCascadeCount) {
               boolean update = this.shouldUpdateCascade(i, layoutChanged);
               this.cascadeUpdates[i] = needsTerrainRefresh(update, NativePackRuntime.animatedShadowCasters());
               if (update) {
                  float start = i == 0 ? 0.5F : splits[i - 1] * 0.82F;
                  this.cascadeEnds[i] = end;
                  Matrix4f previousFit = (new Matrix4f(this.renderedCascadeMatrices[i])).translate((float)(this.cameraX - this.renderedCameraX[i]), (float)(this.cameraY - this.renderedCameraY[i]), (float)(this.cameraZ - this.renderedCameraZ[i]));
                  this.fitCascadeMatrix(i, inverseProjection, start, end, this.renderedCascadeMatrices[i]);
                  if (!this.cascadeInitialized[i] || !previousFit.equals(this.renderedCascadeMatrices[i], 1.0E-6F)) {
                     this.cascadeLayoutVersions[i]++;
                  }

                  this.renderedCameraX[i] = this.cameraX;
                  this.renderedCameraY[i] = this.cameraY;
                  this.renderedCameraZ[i] = this.cameraZ;
                  this.renderedFrameSerial[i] = this.frameSerial;
                  this.renderedTerrainRevision[i] = SodiumShadowTerrainRenderer.terrainRevision();
                  this.renderedCameraForwards[i].set(this.cameraForward);
                  this.cascadeInitialized[i] = true;
               }

               this.cascadeMatrices[i].set(this.renderedCascadeMatrices[i]).translate((float)(this.cameraX - this.renderedCameraX[i]), (float)(this.cameraY - this.renderedCameraY[i]), (float)(this.cameraZ - this.renderedCameraZ[i]));
               boolean entityUpdate = ShadowService.entities() && i < 1 && (layoutChanged || !this.entityCascadeInitialized[i] || update || this.frameSerial - this.renderedEntityFrameSerial[i] >= (long)ENTITY_UPDATE_INTERVALS[i]);
               this.entityCascadeUpdates[i] = entityUpdate;
               if (entityUpdate) {
                  this.renderedEntityFrameSerial[i] = this.frameSerial;
                  this.entityCascadeInitialized[i] = true;
               }
            } else {
               this.cascadeUpdates[i] = false;
               this.cascadeInitialized[i] = false;
               this.entityCascadeUpdates[i] = false;
               this.entityCascadeInitialized[i] = false;
               this.cascadeEnds[i] = 0.0F;
               this.cascadeMatrices[i].identity();
               this.renderedCascadeMatrices[i].identity();
               this.cascadeTexelWorldSizes[i] = 0.0F;
               this.cascadeDepthRanges[i] = 0.0F;
            }
         }

      } else {
         this.activeCascadeCount = 0;
      }
   }

   public int activeCascadeCount() {
      return this.activeCascadeCount;
   }

   static boolean needsTerrainRefresh(boolean projectionChanged, boolean animatedCasters) {
      return projectionChanged || animatedCasters;
   }

   public long cascadeLayoutVersion(int cascade) {
      return this.cascadeLayoutVersions[cascade];
   }

   public Matrix4fc cascadeMatrix(int cascade) {
      return this.cascadeMatrices[cascade];
   }

   public boolean shouldUpdateCascade(int cascade) {
      return cascade >= 0 && cascade < this.activeCascadeCount && this.cascadeUpdates[cascade];
   }

   public boolean shouldUpdateEntityCascade(int cascade) {
      return cascade >= 0 && cascade < this.activeCascadeCount && this.entityCascadeUpdates[cascade];
   }

   public float cascadeEnd(int cascade) {
      return cascade >= 0 && cascade < this.activeCascadeCount ? this.cascadeEnds[cascade] : 0.0F;
   }

   public Vector3f lightDirection(Vector3f destination) {
      return destination.set(this.lightDirection);
   }

   public Vector3f cameraForward(Vector3f destination) {
      return destination.set(this.cameraForward);
   }

   public float shadowDistance() {
      return this.activeCascadeCount <= 0 ? 0.0F : this.cascadeEnds[this.activeCascadeCount - 1];
   }

   public float entityShadowDistance() {
      return this.activeCascadeCount <= 0 ? 0.0F : this.cascadeEnds[Math.min(1, this.activeCascadeCount) - 1];
   }

   public float entityCascadeEnd(int cascade) {
      return cascade >= 0 && cascade < Math.min(1, this.activeCascadeCount) ? this.cascadeEnds[cascade] : 0.0F;
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
      if (cascade >= 0 && cascade < this.activeCascadeCount) {
         float margin = cascade == 0 ? 0.18F : 0.12F;
         return intersectsSection(this.cascadeMatrices[cascade], (float)((double)originX + (double)8.0F - this.cameraX), (float)((double)originY + (double)8.0F - this.cameraY), (float)((double)originZ + (double)8.0F - this.cameraZ), margin);
      } else {
         return false;
      }
   }

   static boolean intersectsSection(Matrix4fc matrix, float x, float y, float z, float margin) {
      float extent = 8.25F;
      float cx = matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30();
      float cy = matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31();
      float cz = matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32();
      float ex = extent * (Math.abs(matrix.m00()) + Math.abs(matrix.m10()) + Math.abs(matrix.m20()));
      float ey = extent * (Math.abs(matrix.m01()) + Math.abs(matrix.m11()) + Math.abs(matrix.m21()));
      float ez = extent * (Math.abs(matrix.m02()) + Math.abs(matrix.m12()) + Math.abs(matrix.m22()));
      return cx + ex >= -1.0F - margin && cx - ex <= 1.0F + margin && cy + ey >= -1.0F - margin && cy - ey <= 1.0F + margin && cz + ez >= -0.08F && cz - ez <= 1.08F;
   }

   public void uploadCascade(CommandEncoder encoder, int cascade) {
      this.cascadeUpload.clear();
      this.cascadeMatrices[cascade].get(this.cascadeUpload);
      this.cascadeUpload.position(64);
      putVec4(this.lightDirection.x, this.lightDirection.y, this.lightDirection.z, 0.0F, this.cascadeUpload);
      this.cascadeUpload.position(80);
      this.inverseViewRotation.get(this.cascadeUpload);
      this.cascadeUpload.rewind();
      this.cascadeSlice = encoder.transientMemory().uploadGpu(this.cascadeUpload, (long)RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), 128);
   }

   public GpuBufferSlice cascadeSlice() {
      return localUniforms != null ? localUniforms : this.cascadeSlice;
   }

   public void uploadShadowData(CommandEncoder encoder) {
      this.shadowUpload.clear();

      for(int i = 0; i < 4; ++i) {
         this.cascadeMatrices[i].get(this.shadowUpload);
         this.shadowUpload.position((i + 1) * 64);
      }

      for(int i = 0; i < 4; ++i) {
         float depthRange = i < this.activeCascadeCount ? this.cascadeDepthRanges[i] : 0.0F;
         putVec4(this.cascadeTexelWorldSizes[i], i == 0 ? 0.0F : this.cascadeEnds[i - 1], this.cascadeEnds[i], depthRange, this.shadowUpload);
      }

      putVec4(this.lightDirection.x, this.lightDirection.y, this.lightDirection.z, 0.0F, this.shadowUpload);
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
      putVec4(1.0F * this.celestialShadowFade, 0.0F, 0.08F, filterSamples, this.shadowUpload);
      this.inverseViewRotation.get(this.shadowUpload);
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
            sameSizes &= this.entityTargetSizes[i] == Math.min(maxShadowSize, ShadowService.entities() ? entityTargetSize(quality, sizes, i) : 1);
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
                  int entitySize = Math.min(maxShadowSize, ShadowService.entities() ? entityTargetSize(quality, sizes, i) : 1);
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
      if (instance != null && !ShadowService.entities() && NativePackRuntime.shadowQuality() <= 0) {
         DirectionalShadowRenderer old = instance;
         instance = null;
         RenderSystem.queueFencedTask(() -> old.destroyTargets());
      }
   }

   private boolean shouldUpdateCascade(int cascade, boolean force) {
      if (!force && this.cascadeInitialized[cascade] && this.renderedTerrainRevision[cascade] == SodiumShadowTerrainRenderer.terrainRevision()) {
         int interval = CASCADE_UPDATE_INTERVALS[cascade];
         if (this.frameSerial - this.renderedFrameSerial[cascade] >= (long)interval) {
            return true;
         } else {
            double dx = this.cameraX - this.renderedCameraX[cascade];
            double dy = this.cameraY - this.renderedCameraY[cascade];
            double dz = this.cameraZ - this.renderedCameraZ[cascade];
            double movementLimit = CASCADE_MOVEMENT_LIMITS[cascade];
            if (dx * dx + dy * dy + dz * dz >= movementLimit * movementLimit) {
               return true;
            } else {
               return this.renderedLightDirections[cascade].dot(this.lightDirection) < 0.9998F || this.renderedCameraForwards[cascade].dot(this.cameraForward) < 0.9659F;
            }
         }
      } else {
         return true;
      }
   }

   private void fitCascadeMatrix(int cascade, Matrix4f inverseProjection, float start, float end, Matrix4f destination) {
      Vector3f fitLight = this.lightDirection;
      if (this.cascadeInitialized[cascade] && this.renderedLightDirections[cascade].distanceSquared(fitLight) < 1.0E-6F) {
         fitLight = this.renderedLightDirections[cascade];
      } else {
         this.renderedLightDirections[cascade].set(fitLight);
      }

      this.buildFrustumSlice(inverseProjection, start, end);
      Vector3f center = new Vector3f();

      for(Vector3f corner : this.frustumCorners) {
         center.add(corner);
      }

      center.div((float)this.frustumCorners.length);
      float radius = 0.0F;

      for(Vector3f corner : this.frustumCorners) {
         radius = Math.max(radius, center.distance(corner));
      }

      radius += Math.max(2.0F, (end - start) * 0.03F);
      radius = (float)Math.ceil((double)(radius + 0.001F));
      Vector3f up = Math.abs(fitLight.y) > 0.88F ? new Vector3f(0.0F, 0.0F, 1.0F) : new Vector3f(0.0F, 1.0F, 0.0F);
      Vector3f lightRight = (new Vector3f(up)).cross(fitLight).normalize();
      Vector3f lightUp = (new Vector3f(fitLight)).cross(lightRight).normalize();
      float centerX = lightRight.dot(center);
      float centerY = lightUp.dot(center);
      float texelSize = radius * 2.0F / (float)Math.max(1, this.targetSizes[cascade]);
      this.cascadeTexelWorldSizes[cascade] = texelSize;
      double originX = (double)lightRight.x * this.cameraX + (double)lightRight.y * this.cameraY + (double)lightRight.z * this.cameraZ;
      double originY = (double)lightUp.x * this.cameraX + (double)lightUp.y * this.cameraY + (double)lightUp.z * this.cameraZ;
      centerX = snapWorldTexel(centerX, originX, texelSize);
      centerY = snapWorldTexel(centerY, originY, texelSize);
      float depthPadding = Math.max(32.0F, Math.min(128.0F, end * 0.35F));
      float lightwardPadding = cascade < 2 ? Math.max(depthPadding, this.lastCoverage) : depthPadding;
      float lightwardReach = radius + lightwardPadding;
      float oppositeReach = radius + depthPadding;
      float shadowDepth = lightwardReach + oppositeReach;
      this.cascadeDepthRanges[cascade] = shadowDepth;
      Vector3f stableCenter = (new Vector3f(center)).add((new Vector3f(lightRight)).mul(centerX - lightRight.dot(center))).add((new Vector3f(lightUp)).mul(centerY - lightUp.dot(center)));
      Matrix4f lightView = stableLightView(stableCenter, fitLight, lightUp, lightwardReach);
      destination.identity().ortho(-radius, radius, -radius, radius, 0.1F, shadowDepth, true).mul(lightView);
   }

   static Matrix4f stableLightView(Vector3f center, Vector3f direction, Vector3f up, float reach) {
      Vector3f eye = (new Vector3f(direction)).mul(reach).add(center);
      return (new Matrix4f()).lookAlong((new Vector3f(direction)).negate(), up).translate(-eye.x, -eye.y, -eye.z);
   }

   static float snapWorldTexel(float relative, double origin, float texelSize) {
      return (float)(Math.floor((origin + (double)relative) / (double)texelSize + (double)0.5F) * (double)texelSize - origin);
   }

   private void buildFrustumSlice(Matrix4f inverseProjection, float start, float end) {
      int index = 0;

      for(float distance : new float[]{start, end}) {
         for(int y = -1; y <= 1; y += 2) {
            for(int x = -1; x <= 1; x += 2) {
               Vector4f view = inverseProjection.transform(new Vector4f((float)x, (float)y, 1.0F, 1.0F));
               view.div(view.w);
               Vector3f ray = (new Vector3f(view.x, view.y, view.z)).normalize().mul(distance);
               this.inverseViewRotation.transformPosition(ray);
               this.frustumCorners[index++].set(ray);
            }
         }
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
         this.cascadeTexelWorldSizes[i] = 0.0F;
         this.cascadeDepthRanges[i] = 0.0F;
      }

   }

   private static float[] splitDistances(float coverage, int count) {
      float[] result = new float[4];
      float near = 8.0F;

      for(int i = 0; i < count; ++i) {
         float t = (float)(i + 1) / (float)count;
         float logarithmic = near * (float)Math.pow((double)(coverage / near), (double)t);
         float uniform = near + (coverage - near) * t;
         result[i] = uniform * 0.35F + logarithmic * 0.65F;
      }

      return result;
   }

   static int[] targetSizes(ShaderQualityPreset quality, int maxSize) {
      int[] var10000;
      switch (quality) {
         case LOW -> var10000 = new int[]{1536, 1, 1, 1};
         case MEDIUM -> var10000 = new int[]{2048, 1536, 1024, 1};
         case HIGH -> var10000 = new int[]{3072, 2048, 1536, 1024};
         case ULTRA -> var10000 = new int[]{6144, 2048, 2048, 1024};
         case OFF -> var10000 = new int[]{1, 1, 1, 1};
         default -> throw new MatchException((String)null, (Throwable)null);
      }

      int[] sizes = var10000;

      for(int i = 0; i < sizes.length; ++i) {
         sizes[i] = Math.min(sizes[i], Math.max(1, maxSize));
      }

      return sizes;
   }

   static int entityTargetSize(ShaderQualityPreset quality, int[] terrainSizes, int cascade) {
      if (cascade < 1 && terrainSizes[cascade] != 1) {
         int var10000;
         switch (quality) {
            case LOW -> var10000 = Math.max(512, terrainSizes[cascade] / 2);
            case MEDIUM -> var10000 = cascade == 0 ? 1536 : 768;
            case HIGH -> var10000 = cascade == 0 ? 3072 : 1024;
            case ULTRA -> var10000 = cascade == 0 ? 6144 : 2048;
            case OFF -> var10000 = 512;
            default -> throw new MatchException((String)null, (Throwable)null);
         }

         return var10000;
      } else {
         return 1;
      }
   }

   private static float smoothstep(float edge0, float edge1, float value) {
      float t = Math.max(0.0F, Math.min(1.0F, (value - edge0) / (edge1 - edge0)));
      return t * t * (3.0F - 2.0F * t);
   }

   private static void putVec4(float x, float y, float z, float w, ByteBuffer buffer) {
      buffer.putFloat(x);
      buffer.putFloat(y);
      buffer.putFloat(z);
      buffer.putFloat(w);
   }

   public static void close() {
      if (instance != null) {
         instance.destroyTargets();
         instance = null;
      }
   }
}
