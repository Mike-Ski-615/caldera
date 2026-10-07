package com.caldera.shaders.render.shadow;

/**
 * {@link CascadePlanner} 的只读统计快照。
 * <p>
 * 存在理由：级联调度错了不会抛异常，只会安静地少画影子或每帧重画。这两种状态在画面上都不好认，
 * 但在计数上很好认——"每个级联各自被要求重画了多少次"和"级联布局一共动了多少次"。
 * 门禁（{@code GraphGpuSmoke}）把它打进 PASS 行，只为**记录**，不参与判定。
 */
public final class CascadePlanStats {
   private final long[] terrainUpdates;
   private final long totalLayoutVersionChurn;

   CascadePlanStats(long[] terrainUpdates, long totalLayoutVersionChurn) {
      this.terrainUpdates = terrainUpdates.clone();
      this.totalLayoutVersionChurn = totalLayoutVersionChurn;
   }

   /** 第 {@code cascade} 个级联被要求重画地形的帧数；越界返回 0。 */
   public long terrainUpdates(int cascade) {
      return cascade >= 0 && cascade < this.terrainUpdates.length ? this.terrainUpdates[cascade] : 0L;
   }

   /**
    * 四个级联的布局版本号之和，也就是"级联的纹素对齐位置一共真正变过多少次"。
    * <p>
    * 它是**累计值**，不是每帧增量：{@code DirectionalShadowRenderer} 退役重建（{@code retireUnused}）
    * 之后重新 {@code get()} 会拿到一个全新的 planner，计数随之归零。
    */
   public long totalLayoutVersionChurn() {
      return this.totalLayoutVersionChurn;
   }

   /** 四个级联的更新计数，逗号分隔。日志字段直接用它，免得在调用点再拼一次。 */
   public String terrainUpdatesSummary() {
      return this.terrainUpdates[0] + ", " + this.terrainUpdates[1] + ", " + this.terrainUpdates[2] + ", " + this.terrainUpdates[3];
   }

   @Override
   public String toString() {
      return "cascades=" + this.terrainUpdates.length
            + " terrainUpdates=[" + this.terrainUpdatesSummary() + "]"
            + " layoutChurn=" + this.totalLayoutVersionChurn;
   }
}
