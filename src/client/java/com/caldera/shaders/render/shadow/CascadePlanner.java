package com.caldera.shaders.render.shadow;

import com.caldera.shaders.config.ShaderQualityPreset;
import com.caldera.shaders.render.sky.CustomCelestials;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * 方向光阴影的**级联调度**：跨帧记账在这里，{@link CascadeSchedule} 是本帧的产出。
 * <p>
 * 从 {@link DirectionalShadowRenderer} 里逐字搬出来的纯决策部分。搬出来的必要性不在于"更整洁"，
 * 而在于它原先**不可验证**：那二十来个字段（每个级联上次渲染时的相机、帧号、地形修订号、光照方向、
 * 布局版本……）夹在 RenderTarget 分配与 GPU 上传之间，于是"第二帧会不会重画级联 1"只能靠跑客户端看。
 * 这类判断错了不会抛异常，只会安静地少画一层影子。
 * <p>
 * 这里没有任何 GPU、没有 {@code Minecraft}、也没有 {@code RenderSystem}：所有游戏状态都由调用方
 * （{@link DirectionalShadowRenderer#prepare}）作为参数递进来。于是同一份决策可以在纯 JVM 里用真实的
 * {@code CameraRenderState}/{@code Matrix4f}/{@code Vector3f} 摆布着测。
 * <p>
 * <b>几何与数学一律逐字搬运，不做"顺手改进"。</b>级联拟合、纹素对齐、光照视图这些地方看起来有冗余，
 * 但每一处的取整、夹取与容差都直接决定影子边界的抖动（peter-panning / shimmering），
 * 而判断它们是否等价的方法只有逐字对照。
 */
public final class CascadePlanner {
   /**
    * 日月分离度超过这个区间就不再淡出影子（smoothstep 的两个边界）。
    * <p>
    * 这两个常量原先根本没被用上——L167 直接写了 0.02F/0.2F。有常量却用字面量比纯死代码更会骗人：
    * 改常量的人会以为生效了，而什么也没发生。
    */
   private static final float CELESTIAL_FADE_START = 0.02F;
   private static final float CELESTIAL_FADE_END = 0.2F;
   private static final int[] CASCADE_UPDATE_INTERVALS = new int[]{3, 8, 16, 32};
   private static final int[] ENTITY_UPDATE_INTERVALS = new int[]{1, 1, 1, 1};
   private static final double[] CASCADE_MOVEMENT_LIMITS = new double[]{(double)1.5F, (double)4.0F, (double)12.0F, (double)24.0F};
   private static final int CASCADE_COUNT = 4;

   /**
    * 地形几何的修订号，进程级。
    * <p>
    * 原先它住在 {@code SodiumShadowTerrainRenderer}，但读它的其实是**计划**（哪个级联的几何过期了），
    * 所以它跟着计划搬到了这里。语义一字未改：{@code markTerrainDirty()} 自增一次；
    * {@code SodiumShadowTerrainRenderer.close()} 也自增一次（清理缓存的批次计划之后）。
    * <p>
    * 静态是必须的：推它的是 {@code RenderRegionMixin} 那些由游戏实例化的 mixin，它们只能摸静态成员。
    */
   private static long terrainRevision;

   private final Matrix4f[] cascadeMatrices = new Matrix4f[CASCADE_COUNT];
   private final Matrix4f[] renderedCascadeMatrices = new Matrix4f[CASCADE_COUNT];
   private final Vector3f[] frustumCorners = new Vector3f[8];
   private final Vector3f[] renderedLightDirections = new Vector3f[CASCADE_COUNT];
   private final Vector3f[] renderedCameraForwards = new Vector3f[CASCADE_COUNT];
   private final float[] cascadeEnds = new float[CASCADE_COUNT];
   private final float[] cascadeTexelWorldSizes = new float[CASCADE_COUNT];
   private final float[] cascadeDepthRanges = new float[CASCADE_COUNT];
   private final boolean[] cascadeUpdates = new boolean[CASCADE_COUNT];
   private final boolean[] cascadeInitialized = new boolean[CASCADE_COUNT];
   private final long[] cascadeLayoutVersions = new long[CASCADE_COUNT];
   private final boolean[] entityCascadeUpdates = new boolean[CASCADE_COUNT];
   private final boolean[] entityCascadeInitialized = new boolean[CASCADE_COUNT];
   private final double[] renderedCameraX = new double[CASCADE_COUNT];
   private final double[] renderedCameraY = new double[CASCADE_COUNT];
   private final double[] renderedCameraZ = new double[CASCADE_COUNT];
   private final long[] renderedFrameSerial = new long[CASCADE_COUNT];
   private final long[] renderedTerrainRevision = new long[CASCADE_COUNT];
   private final long[] renderedEntityFrameSerial = new long[CASCADE_COUNT];
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
   /** 最近一次真正算出来的调度；{@link #suspend()} 靠它保留"未启用"时该留着的那些数。 */
   private CascadeSchedule last = CascadeSchedule.empty();

   public CascadePlanner() {
      for(int i = 0; i < CASCADE_COUNT; ++i) {
         this.cascadeMatrices[i] = new Matrix4f();
         this.renderedCascadeMatrices[i] = new Matrix4f();
         this.renderedLightDirections[i] = new Vector3f();
         this.renderedCameraForwards[i] = new Vector3f();
      }

      for(int i = 0; i < this.frustumCorners.length; ++i) {
         this.frustumCorners[i] = new Vector3f();
      }

   }

   /** 自增地形修订号；每一次区块渲染状态变化都调它。 */
   public static void markTerrainDirty() {
      ++terrainRevision;
   }

   /** 当前地形修订号。调用方必须把它作为参数交给 {@link #plan}，而不是让计划自己去读。 */
   public static long terrainRevision() {
      return terrainRevision;
   }

   /**
    * 算一帧的级联调度。所有输入都是值，没有任何一项来自 {@code Minecraft}/{@code RenderSystem}。
    *
    * @param quality              画质预设，决定生效级联数
    * @param cameraX              相机位置（世界坐标，双精度：它与上次渲染位置相减后才降到 float）
    * @param cameraY              同上
    * @param cameraZ              同上
    * @param viewRotationMatrix   相机的视图旋转矩阵；为 {@code null} 时按单位矩阵处理（原件行为）
    * @param projectionMatrix     相机的投影矩阵，用于反投影出视锥切片
    * @param skyPresent           {@code LevelRenderState.skyRenderState} 是否存在；不存在时光照方向取常量
    * @param sunAngle             太阳角（弧度）
    * @param moonAngle            月亮角（弧度）
    * @param renderDistance       有效渲染距离（区块数），与阴影距离一起夹出覆盖范围
    * @param packShadowDistance   光影包声明的阴影距离
    * @param entityShadowsEnabled 阴影是否真的在渲染（决定实体级联的更新）
    * @param animatedShadowCasters 包是否声明了会动阴影的投射者（植被风）：是则**每一帧每个级联**都要重画
    * @param maxShadowSize        设备对阴影贴图格式的纹理边长上限
    * @param terrainRevision      当前地形修订号（见 {@link #terrainRevision()}）
    * @param resourcesChanged     {@code ensureResources} 是否刚重建过 RenderTarget
    */
   public CascadeSchedule plan(
         ShaderQualityPreset quality,
         double cameraX,
         double cameraY,
         double cameraZ,
         Matrix4fc viewRotationMatrix,
         Matrix4fc projectionMatrix,
         boolean skyPresent,
         float sunAngle,
         float moonAngle,
         int renderDistance,
         float packShadowDistance,
         boolean entityShadowsEnabled,
         boolean animatedShadowCasters,
         int maxShadowSize,
         long terrainRevision,
         boolean resourcesChanged) {
      int activeCascadeCount = activeCascadeCount(quality);
      ++this.frameSerial;
      if (skyPresent) {
         this.updateCelestialDirections(sunAngle, moonAngle);
         this.celestialShadowFade = smoothstep(CELESTIAL_FADE_START, CELESTIAL_FADE_END, Math.abs(this.sunDirection.y - this.moonDirection.y));
         this.lightDirection.set(this.sunDirection.y >= this.moonDirection.y ? this.sunDirection : this.moonDirection);
         if (this.lightDirection.y < 0.08F) {
            this.lightDirection.y = 0.08F;
         }

         this.lightDirection.normalize();
      } else {
         this.lightDirection.set(-0.35F, 0.82F, -0.44F).normalize();
         this.celestialShadowFade = 1.0F;
      }

      if (viewRotationMatrix != null) {
         this.inverseViewRotation.set(viewRotationMatrix).invert();
         this.cameraForward.set(0.0F, 0.0F, -1.0F);
         this.inverseViewRotation.transformDirection(this.cameraForward).normalize();
      } else {
         this.inverseViewRotation.identity();
         this.cameraForward.set(0.0F, 0.0F, -1.0F);
      }

      this.cameraX = cameraX;
      this.cameraY = cameraY;
      this.cameraZ = cameraZ;
      float coverage = Math.min(packShadowDistance, Math.max(64.0F, (float)renderDistance * 16.0F));
      boolean layoutChanged = resourcesChanged || !this.lastProjection.equals(projectionMatrix, 1.0E-4F) || this.lastActiveCascadeCount != activeCascadeCount || Math.abs(this.lastCoverage - coverage) > 0.5F;
      this.lastProjection.set(projectionMatrix);
      this.lastActiveCascadeCount = activeCascadeCount;
      this.lastCoverage = coverage;
      float[] splits = splitDistances(coverage, activeCascadeCount);
      Matrix4f inverseProjection = (new Matrix4f(projectionMatrix)).invert();
      int[] sizes = targetSizes(quality, maxShadowSize);

      for(int i = 0; i < CASCADE_COUNT; ++i) {
         float end = i < activeCascadeCount ? splits[i] : 0.0F;
         if (i < activeCascadeCount) {
            boolean update = this.shouldUpdateCascade(i, layoutChanged, terrainRevision);
            this.cascadeUpdates[i] = needsTerrainRefresh(update, animatedShadowCasters);

            if (update) {
               float start = i == 0 ? 0.5F : splits[i - 1] * 0.82F;
               this.cascadeEnds[i] = end;
               Matrix4f previousFit = (new Matrix4f(this.renderedCascadeMatrices[i])).translate((float)(this.cameraX - this.renderedCameraX[i]), (float)(this.cameraY - this.renderedCameraY[i]), (float)(this.cameraZ - this.renderedCameraZ[i]));
               this.fitCascadeMatrix(i, inverseProjection, start, end, this.renderedCascadeMatrices[i], sizes);
               if (!this.cascadeInitialized[i] || !previousFit.equals(this.renderedCascadeMatrices[i], 1.0E-6F)) {
                  ++this.cascadeLayoutVersions[i];
               }

               this.renderedCameraX[i] = this.cameraX;
               this.renderedCameraY[i] = this.cameraY;
               this.renderedCameraZ[i] = this.cameraZ;
               this.renderedFrameSerial[i] = this.frameSerial;
               this.renderedTerrainRevision[i] = terrainRevision;
               this.renderedCameraForwards[i].set(this.cameraForward);
               this.cascadeInitialized[i] = true;
            }

            this.cascadeMatrices[i].set(this.renderedCascadeMatrices[i]).translate((float)(this.cameraX - this.renderedCameraX[i]), (float)(this.cameraY - this.renderedCameraY[i]), (float)(this.cameraZ - this.renderedCameraZ[i]));
            boolean entityUpdate = entityShadowsEnabled && i < 1 && (layoutChanged || !this.entityCascadeInitialized[i] || update || this.frameSerial - this.renderedEntityFrameSerial[i] >= (long)ENTITY_UPDATE_INTERVALS[i]);
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

      CascadeSchedule schedule = new CascadeSchedule(
            activeCascadeCount,
            this.cascadeUpdates,
            this.entityCascadeUpdates,
            this.cascadeEnds,
            this.cascadeMatrices,
            this.cascadeLayoutVersions,
            this.cascadeTexelWorldSizes,
            this.cascadeDepthRanges,
            this.lightDirection,
            this.cameraForward,
            this.inverseViewRotation,
            this.celestialShadowFade);
      this.last = schedule;
      return schedule;
   }

   /**
    * 从天空状态刷新日、月方向。
    * <p>
    * 它**与阴影质量无关**：只要这一帧拿得到天空状态，日月的方向就是确定的。所以调用点
    * 由 {@code prepare()}（这个类的帧入口）负责，而不是留在这个只在"阴影真的在计划"时才
    * 进得来的方法里——{@code prepare()} 里那道质量判定会把没有阴影的帧全部挡在外面。
    * <p>
    * 这两个方向本来就是阴影的真实输入：光照方向取较高的那个，日月的高度差还决定
    * {@code celestialShadowFade}。所以它们不是为门禁而存在的值，门禁只是**读**它们。
    */
   void updateCelestialDirections(float sunAngle, float moonAngle) {
      CustomCelestials.setCelestialDirection(sunAngle, this.sunDirection);
      CustomCelestials.setCelestialDirection(moonAngle, this.moonDirection);
   }

   /**
    * 这一帧没有计划（未启用，或没有可用的相机）。
    * <p>
    * <b>为什么不是 {@link CascadeSchedule#empty()}：</b>迁移前那条 else 分支只写了一行
    * {@code this.activeCascadeCount = 0;}，矩阵、端点、纹素尺寸、光照方向全都保持上一帧的值。
    * 那些值在带守卫的读取里看不见（守卫是 {@code < activeCascadeCount}），但 {@code uploadShadowData}
    * 会原样把它们填进 UBO。换成 empty() 会在"包开着、这一帧相机却不可用"的窗口里改变上传内容，
    * 所以这里只把生效级联数归零，其余原样传下去。
    * <p>
    * 它同时**不**推进帧号：迁移前那一帧也没有推进，而帧号参与
    * {@code frameSerial - renderedFrameSerial >= interval} 的间隔判定。
    */
   public CascadeSchedule suspend() {
      return this.last.withActiveCascadeCount(0);
   }

   /**
    * 这个档位的阴影图一共要多少显存：地面级联 + 两张实体图。
    * <p>
    * 它原先在 {@code ShadowService.memoryBytes(int)} 里。搬过来是因为它**就是 sizing 算术**——
    * 它逐字用的是本类的 {@code targetSizes} 与 {@code entityTargetSize}，而"档位对应几个多大连级"
    * 的知识本来就归这里。调用方（graph 侧的显存预算）现在问它，而不是问那个绑定名表。
    * <p>
    * {@code quality == 0}（不投射阴影）时是 0，用的是**索引**而不是档位：0 在
    * {@code ShaderQualityPreset} 里就是 {@code OFF}。
    */
   public static long shadowMemoryBytes(int quality) {
      if (quality == 0) {
         return 0L;
      } else {
         long bytes = 560L;
         ShaderQualityPreset preset = ShaderQualityPreset.values()[quality];
         int[] sizes = targetSizes(preset, Integer.MAX_VALUE);

         for(int size : sizes) {
            bytes += (long)size * (long)size * 5L;
         }

         for(int i = 0; i < 2; ++i) {
            int size = entityTargetSize(preset, sizes, i);
            bytes += (long)size * (long)size * 5L;
         }

         return bytes;
      }
   }

   /**
    * 丢掉与阴影贴图分辨率绑定的那两个量：纹素世界尺寸与光照方向深度范围。
    * <p>
    * 由 {@code DirectionalShadowRenderer.destroyTargets()} 调用——纹理拆了，这两个数就没有意义了。
    * 迁移前它们与 RenderTarget 尺寸同住在渲染器实例上，所以 {@code destroyTargets} 顺手把它们归零；
    * 现在它们属于计划，这条归零得显式跨过接缝。
    * <p>
    * <b>它不清计划的历史</b>（帧号、布局版本、上次渲染位置、初始化标志）：那条"close 不清计划状态"
    * 的不对称是迁移前就有的，见 {@code DirectionalShadowRenderer.close()} 的注释。
    */
   void forgetTextureDerivedState() {
      for(int i = 0; i < CASCADE_COUNT; ++i) {
         this.cascadeTexelWorldSizes[i] = 0.0F;
         this.cascadeDepthRanges[i] = 0.0F;
      }

   }

   /**
    * 画质预设到生效级联数的映射：LOW 1、MEDIUM 3、HIGH/ULTRA 4、OFF 0。
    * <p>
    * 与预设的 tier 不是同一个东西，所以不能拿 {@code ordinal()} 顶替：MEDIUM=2 却要 3 个级联。
    */
   static int activeCascadeCount(ShaderQualityPreset quality) {
      return switch (quality) {
         case LOW -> 1;
         case MEDIUM -> 3;
         case HIGH, ULTRA -> 4;
         case OFF -> 0;
      };
   }

   static boolean needsTerrainRefresh(boolean projectionChanged, boolean animatedCasters) {
      return projectionChanged || animatedCasters;
   }

   private boolean shouldUpdateCascade(int cascade, boolean force, long terrainRevision) {
      if (!force && this.cascadeInitialized[cascade] && this.renderedTerrainRevision[cascade] == terrainRevision) {
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

   private void fitCascadeMatrix(int cascade, Matrix4f inverseProjection, float start, float end, Matrix4f destination, int[] sizes) {
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
      float texelSize = radius * 2.0F / (float)Math.max(1, sizes[cascade]);
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

   static Matrix4f stableLightView(Vector3f center, Vector3f direction, Vector3f up, float reach) {
      Vector3f eye = (new Vector3f(direction)).mul(reach).add(center);
      return (new Matrix4f()).lookAlong((new Vector3f(direction)).negate(), up).translate(-eye.x, -eye.y, -eye.z);
   }

   static float snapWorldTexel(float relative, double origin, float texelSize) {
      return (float)(Math.floor((origin + (double)relative) / (double)texelSize + (double)0.5F) * (double)texelSize - origin);
   }

   static float[] splitDistances(float coverage, int count) {
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
}
