package com.caldera.shaders.render.shadow;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * 一帧的阴影级联决策：哪些级联要重画、每个级联画到哪里、用哪个矩阵。
 * <p>
 * 这是从 {@link DirectionalShadowRenderer} 里抽出来的**纯值对象**。抽出来的理由与 ADR-0003 记的
 * 其它 seam 相同：原来这套决策是渲染器实例里的二十来个字段，夹在 RenderTarget 分配与 GPU 上传之间，
 * 于是"第二帧到底会不会重画级联 1"这种问题只能在跑起来的客户端里看，而它错了不会报错，
 * 只会安静地少画一层影子。
 * <p>
 * 构造器是包可见的：只有 {@link CascadePlanner} 造它。对外只读——所有数组都在构造时复制，
 * 拿到的 {@link Matrix4fc} 不会被后续帧改写。这正是"一帧的决策"该有的语义：渲染器在本帧稍后
 * （上传 UBO、Sodium 渲染地形）读到的东西，必须与 planner 当时算出来的完全一致。
 * <p>
 * 名字不叫 {@code CascadePlan}：那个名字已经被 {@code SodiumShadowTerrainRenderer} 内部那个
 * "render-list 缓存计划"占了，是**另一个概念**（缓存哪些区块的批次），不是这里的级联调度。
 */
public final class CascadeSchedule {
   /** 与 {@code DirectionalShadowRenderer.lightDirection} 的字段初值一致：没有天空状态时的默认光照。 */
   private static final Vector3f DEFAULT_LIGHT_DIRECTION = new Vector3f(0.0F, -1.0F, 0.0F);
   private static final Vector3f DEFAULT_CAMERA_FORWARD = new Vector3f(0.0F, 0.0F, -1.0F);

   private final int activeCascadeCount;
   private final boolean[] cascadeUpdates;
   private final boolean[] entityCascadeUpdates;
   private final float[] cascadeEnds;
   private final Matrix4f[] cascadeMatrices;
   private final long[] layoutVersions;
   private final float[] texelWorldSizes;
   private final float[] depthRanges;
   private final Vector3f lightDirection;
   private final Vector3f cameraForward;
   private final Matrix4f inverseViewRotation;
   private final float celestialShadowFade;

   CascadeSchedule(
         int activeCascadeCount,
         boolean[] cascadeUpdates,
         boolean[] entityCascadeUpdates,
         float[] cascadeEnds,
         Matrix4f[] cascadeMatrices,
         long[] layoutVersions,
         float[] texelWorldSizes,
         float[] depthRanges,
         Vector3fc lightDirection,
         Vector3fc cameraForward,
         Matrix4fc inverseViewRotation,
         float celestialShadowFade) {
      this.activeCascadeCount = activeCascadeCount;
      this.cascadeUpdates = cascadeUpdates.clone();
      this.entityCascadeUpdates = entityCascadeUpdates.clone();
      this.cascadeEnds = cascadeEnds.clone();
      this.layoutVersions = layoutVersions.clone();
      this.texelWorldSizes = texelWorldSizes.clone();
      this.depthRanges = depthRanges.clone();
      this.cascadeMatrices = new Matrix4f[cascadeMatrices.length];

      for(int i = 0; i < cascadeMatrices.length; ++i) {
         this.cascadeMatrices[i] = new Matrix4f(cascadeMatrices[i]);
      }

      this.lightDirection = new Vector3f(lightDirection);
      this.cameraForward = new Vector3f(cameraForward);
      this.inverseViewRotation = new Matrix4f(inverseViewRotation);
      this.celestialShadowFade = celestialShadowFade;
   }

   /**
    * 什么都没发生过的那一帧：零个生效级联。
    * <p>
    * 光照方向与相机朝向取的是迁移前渲染器**字段初值**，不是零向量——{@code close()} 之后、
    * 第一次 {@code prepare()} 之前读到的就是这个状态，而 {@code uploadShadowData} 在那个窗口里
    * 仍然可能被调用（shader 已启用但本帧没有相机）。
    */
   public static CascadeSchedule empty() {
      return new CascadeSchedule(
            0,
            new boolean[4],
            new boolean[4],
            new float[4],
            identityMatrices(),
            new long[4],
            new float[4],
            new float[4],
            DEFAULT_LIGHT_DIRECTION,
            DEFAULT_CAMERA_FORWARD,
            new Matrix4f(),
            1.0F);
   }

   private static Matrix4f[] identityMatrices() {
      Matrix4f[] matrices = new Matrix4f[4];

      for(int i = 0; i < 4; ++i) {
         matrices[i] = new Matrix4f();
      }

      return matrices;
   }

   /**
    * 同一份决策，但生效级联数换成 {@code count}。
    * <p>
    * 用于"这一帧不该有计划"的那条路径（未启用、没有相机）。迁移前那里**只**写了
    * {@code activeCascadeCount = 0}，其余字段原样留着；所以这里也只改这一个数，
    * 不动矩阵、端点与更新标志——它们对外的读取全部带 {@code < activeCascadeCount} 守卫，
    * 归零之後本来就不可见，但 uploadShadowData 会原样读它们。
    */
   CascadeSchedule withActiveCascadeCount(int count) {
      return new CascadeSchedule(
            count,
            this.cascadeUpdates,
            this.entityCascadeUpdates,
            this.cascadeEnds,
            this.cascadeMatrices,
            this.layoutVersions,
            this.texelWorldSizes,
            this.depthRanges,
            this.lightDirection,
            this.cameraForward,
            this.inverseViewRotation,
            this.celestialShadowFade);
   }

   public int activeCascadeCount() {
      return this.activeCascadeCount;
   }

   /** 本帧这个级联要不要重画地形；级联不在生效范围内时为 {@code false}。 */
   public boolean cascadeUpdate(int cascade) {
      return cascade >= 0 && cascade < this.activeCascadeCount && this.cascadeUpdates[cascade];
   }

   /** 本帧这个级联要不要重画实体；级联不在生效范围内时为 {@code false}。 */
   public boolean entityCascadeUpdate(int cascade) {
      return cascade >= 0 && cascade < this.activeCascadeCount && this.entityCascadeUpdates[cascade];
   }

   /** 这个级联的远端距离；级联不在生效范围内时为 0。 */
   public float cascadeEnd(int cascade) {
      return cascade >= 0 && cascade < this.activeCascadeCount ? this.cascadeEnds[cascade] : 0.0F;
   }

   /**
    * 端点数组的**原始槽位**，不带生效范围守卫。
    * <p>
    * {@code uploadShadowData} 就是这么读的：它按固定 4 个槽位填 UBO，不生效的那些槽位在
    * planner 里已经写成 0，但 {@code cascadeEnds[i - 1]} 可能指向上一个生效级联的旧值。
    * 换成带守卫的读取会改变上传内容。
    */
   public float cascadeEndSlot(int cascade) {
      return this.cascadeEnds[cascade];
   }

   /** 实体阴影的远端距离，只取第 0 个级联。 */
   public float entityShadowDistance() {
      return this.activeCascadeCount <= 0 ? 0.0F : this.cascadeEnds[Math.min(1, this.activeCascadeCount) - 1];
   }

   /** 实体阴影级联的远端距离；只有第 0 个级联有实体阴影。 */
   public float entityCascadeEnd(int cascade) {
      return cascade >= 0 && cascade < Math.min(1, this.activeCascadeCount) ? this.cascadeEnds[cascade] : 0.0F;
   }

   /** 最远的生效级联端点，也就是阴影距离。 */
   public float shadowDistance() {
      return this.activeCascadeCount <= 0 ? 0.0F : this.cascadeEnds[this.activeCascadeCount - 1];
   }

   public Matrix4fc cascadeMatrix(int cascade) {
      return this.cascadeMatrices[cascade];
   }

   public long layoutVersion(int cascade) {
      return this.layoutVersions[cascade];
   }

   /** 一个纹素在世界空间里的边长；不生效的槽位是 0，但不带守卫（见 {@link #cascadeEndSlot}）。 */
   public float texelWorldSize(int cascade) {
      return this.texelWorldSizes[cascade];
   }

   /** 光照方向上的深度范围；不生效的槽位是 0，但不带守卫（见 {@link #cascadeEndSlot}）。 */
   public float depthRange(int cascade) {
      return this.depthRanges[cascade];
   }

   public Vector3fc lightDirection() {
      return this.lightDirection;
   }

   public Vector3fc cameraForward() {
      return this.cameraForward;
   }

   /**
    * 相机视图旋转矩阵的逆。
    * <p>
    * 它同时被两处用：planner 把视锥切片的射线转到世界空间（否则级联拟合的朝向不对），
    * 渲染器把它填进 shadow UBO（偏移 80 起）。所以它必须是**同一份**计算——两处各算一次
    * 不会有浮点差异，但会有"改了一处忘了另一处"的差异，而那种差异只表现为影子方向整体错一点。
    */
   public Matrix4fc inverseViewRotation() {
      return this.inverseViewRotation;
   }

   /** 日月分离带来的影子淡出系数，直接进 UBO。 */
   public float celestialShadowFade() {
      return this.celestialShadowFade;
   }

   /**
    * 一个 16×16×16 的区块段是否与这个级联的裁剪矩阵相交。
    * <p>
    * 判定用的相机位置必须由调用方传入：它是**这一帧**的相机，与本对象里那份级联矩阵同源，
    * 而值对象不该在构造之后再去读一个会变的世界状态。
    */
   public boolean sectionIntersectsCascade(int cascade, int originX, int originY, int originZ, double cameraX, double cameraY, double cameraZ) {
      if (cascade >= 0 && cascade < this.activeCascadeCount) {
         float margin = cascade == 0 ? 0.18F : 0.12F;
         return intersectsSection(
               this.cascadeMatrices[cascade],
               (float)((double)originX + (double)8.0F - cameraX),
               (float)((double)originY + (double)8.0F - cameraY),
               (float)((double)originZ + (double)8.0F - cameraZ),
               margin);
      } else {
         return false;
      }
   }

   /** 迁移前 {@code DirectionalShadowRenderer.intersectsSection} 的逐字搬运。 */
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
}
