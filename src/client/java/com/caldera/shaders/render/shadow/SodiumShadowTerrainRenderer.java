package com.caldera.shaders.render.shadow;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.caldera.shaders.mixin.sodium.RenderSectionManagerAccessor;
import com.caldera.shaders.mixin.sodium.SodiumWorldRendererAccessor;
import com.caldera.shaders.runtime.ReloadableResources;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.renderer.oit.OitStage;
import org.joml.Vector3f;

public final class SodiumShadowTerrainRenderer {
   private static final double[] PLAN_MOVEMENT_LIMITS = new double[]{(double)4.0F, (double)8.0F, (double)16.0F, (double)32.0F};
   /**
    * 缓存的 render-list plan 能容忍多少方向变化：光照方向与相机朝向的点积下限。
    * <p>
    * 原先它没被用上——L259 把 0.9925F 写了三遍。
    */
   private static final float PLAN_DIRECTION_DOT = 0.9925F;
   private static final CascadePlan[] CACHED_PLANS = new CascadePlan[4];
   /** 缓存的 render-list plan 跨资源重载存活，释放动作在定义处登记一次。见 {@link ReloadableResources}。 */
   private static final ReloadableResources.Owner RELOADABLE =
         ReloadableResources.owner("sodium shadow terrain plans", SodiumShadowTerrainRenderer::close);

   private SodiumShadowTerrainRenderer() {
   }

   public static void renderCascade(SodiumWorldRenderer renderer, DirectionalShadowRenderer shadows, int cascade, ChunkRenderMatrices matrices, double cameraX, double cameraY, double cameraZ, GpuSampler sampler) {
      SodiumWorldRendererAccessor rendererAccess = (SodiumWorldRendererAccessor)renderer;
      RenderSectionManager sectionManager = rendererAccess.caldera$renderSectionManager();
      if (sectionManager != null) {
         Iterable<RenderRegion> regions = ((RenderSectionManagerAccessor)sectionManager).caldera$regions().getLoadedRegions();
         CascadePlan plan = planFor(regions, shadows, cascade, cameraX, cameraY, cameraZ);
         if (!plan.lists.isEmpty()) {
            UniformBufferManager uniforms = rendererAccess.caldera$uniformBufferManager();
            uniforms.update(matrices, FogParameters.NONE);
            boolean blockFaceCulling = SodiumClientMod.options().performance.useBlockFaceCulling;
            SodiumClientMod.options().performance.useBlockFaceCulling = false;

            try {
               TerrainRenderPass[] passes = SodiumShadowTerrainPasses.terrainPasses(cascade, shadows.activeCascadeCount());
               if (cascade == 0) {
                  for(TerrainRenderPass pass : passes) {
                     renderPass(sectionManager.getChunkRenderer(), matrices, plan.renderLists, pass, cameraX, cameraY, cameraZ, sampler, uniforms.getUniformBuffer(), uniforms.getSectionTimeInfo());
                  }
               } else {
                  renderPasses(sectionManager.getChunkRenderer(), matrices, plan.renderLists, passes, cameraX, cameraY, cameraZ, sampler, uniforms.getUniformBuffer(), uniforms.getSectionTimeInfo());
               }
            } finally {
               SodiumClientMod.options().performance.useBlockFaceCulling = blockFaceCulling;
            }

         }
      }
   }

   public static void close() {
      for(int cascade = 0; cascade < CACHED_PLANS.length; ++cascade) {
         clearPlanBatches(CACHED_PLANS[cascade], cascade);
         CACHED_PLANS[cascade] = null;
      }

      // 地形修订号住在计划模块（CascadePlanner）里：读它的其实是计划。语义一字未改——这里自增一次。
      CascadePlanner.markTerrainDirty();
   }

   private static CascadePlan planFor(Iterable<RenderRegion> regions, DirectionalShadowRenderer shadows, int cascade, double cameraX, double cameraY, double cameraZ) {
      CascadePlan cached = CACHED_PLANS[cascade];
      long layoutVersion = shadows.cascadeLayoutVersion(cascade);
      Vector3f lightDirection = shadows.lightDirection(new Vector3f());
      Vector3f cameraForward = shadows.cameraForward(new Vector3f());
      if (cached != null && cached.layoutVersion == layoutVersion && cached.matches(CascadePlanner.terrainRevision(), cameraX, cameraY, cameraZ, lightDirection, cameraForward, shadows.cascadeEnd(cascade), PLAN_MOVEMENT_LIMITS[cascade])) {
         return cached;
      } else {
         boolean spatiallyReusable = cached != null && cached.layoutVersion == layoutVersion && cached.spatiallyMatches(cameraX, cameraY, cameraZ, lightDirection, cameraForward, shadows.cascadeEnd(cascade), PLAN_MOVEMENT_LIMITS[cascade]);
         PlanContents contents = buildRenderLists(regions, shadows, cascade, cached, spatiallyReusable);
         CascadePlan next = new CascadePlan(contents.lists, new ShadowRenderLists(contents.lists), contents.regions, layoutVersion, CascadePlanner.terrainRevision(), cameraX, cameraY, cameraZ, lightDirection, cameraForward, shadows.cascadeEnd(cascade));
         clearChangedPlanBatches(cached, next, cascade);
         CACHED_PLANS[cascade] = next;
         return next;
      }
   }

   static void renderPass(ChunkRenderer renderer, ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass pass, double cameraX, double cameraY, double cameraZ, GpuSampler sampler, GpuBufferSlice uniformBuffer, GpuBuffer sectionTimeInfo) {
      renderPasses(renderer, matrices, renderLists, new TerrainRenderPass[]{pass}, cameraX, cameraY, cameraZ, sampler, uniformBuffer, sectionTimeInfo);
   }

   private static void renderPasses(ChunkRenderer renderer, ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass[] passes, double cameraX, double cameraY, double cameraZ, GpuSampler sampler, GpuBufferSlice uniformBuffer, GpuBuffer sectionTimeInfo) {
      boolean[] flags = ((ShadowChunkState)renderer).caldera$drawFlags();
      boolean[] savedFlags = (boolean[])flags.clone();

      try {
         ShadowPassScope.enterSodiumBatch(passes);
         renderer.prepare(renderLists, new CameraTransform(cameraX, cameraY, cameraZ), false);
         ShadowPassScope.markBatchPrepared();
         RenderTarget target = ShadowPassScope.target();
         RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Caldera shadow terrain", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty());

         try {
            for(TerrainRenderPass pass : passes) {
               renderer.render(matrices, renderLists, pass, new CameraTransform(cameraX, cameraY, cameraZ), FogParameters.NONE, false, renderPass, sampler, uniformBuffer, sectionTimeInfo, (OitStage)null);
            }
         } catch (Throwable var26) {
            if (renderPass != null) {
               try {
                  renderPass.close();
               } catch (Throwable var25) {
                  var26.addSuppressed(var25);
               }
            }

            throw var26;
         }

         if (renderPass != null) {
            renderPass.close();
         }
      } finally {
         ShadowPassScope.exitSodiumBatch();
         System.arraycopy(savedFlags, 0, flags, 0, flags.length);
      }

   }

   private static PlanContents buildRenderLists(Iterable<RenderRegion> regions, DirectionalShadowRenderer shadows, int cascade, CascadePlan previous, boolean spatiallyReusable) {
      List<ChunkRenderList> result = new ArrayList();
      Map<RenderRegion, RegionPlan> nextRegions = new IdentityHashMap();

      for(RenderRegion region : regions) {
         long revision = ((ShadowRegionState)region).caldera$shadowRevision();
         RegionPlan priorRegion = previous == null ? null : (RegionPlan)previous.regions.get(region);
         RegionPlan nextRegion;
         if (spatiallyReusable && priorRegion != null && priorRegion.revision == revision) {
            nextRegion = priorRegion;
         } else {
            nextRegion = buildRegionPlan(region, shadows, cascade, revision);
         }

         nextRegions.put(region, nextRegion);
         if (!nextRegion.sections.isEmpty()) {
            result.add(nextRegion.list);
         }
      }

      return new PlanContents(List.copyOf(result), nextRegions);
   }

   private static RegionPlan buildRegionPlan(RenderRegion region, DirectionalShadowRenderer shadows, int cascade, long revision) {
      ChunkRenderList list = new ChunkRenderList(region);
      BitSet sections = new BitSet(256);
      list.reset(0);

      for(int index = 0; index < 256; ++index) {
         int flags = region.getSectionFlags(index);
         if ((flags & 1) != 0) {
            int sectionX = region.getChunkX() + LocalSectionIndex.unpackX(index);
            int sectionY = region.getChunkY() + LocalSectionIndex.unpackY(index);
            int sectionZ = region.getChunkZ() + LocalSectionIndex.unpackZ(index);
            if (shadows.sectionIntersectsCascade(cascade, sectionX << 4, sectionY << 4, sectionZ << 4)) {
               list.add(index);
               sections.set(index);
            }
         }
      }

      return new RegionPlan(list, sections, revision);
   }

   private static void clearChangedPlanBatches(CascadePlan previous, CascadePlan next, int cascade) {
      if (previous != null) {
         for(Map.Entry<RenderRegion, RegionPlan> entry : previous.regions.entrySet()) {
            RegionPlan replacement = (RegionPlan)next.regions.get(entry.getKey());
            RegionPlan prior = (RegionPlan)entry.getValue();
            if (replacement == null || prior.revision != replacement.revision || !prior.sections.equals(replacement.sections)) {
               clearRegionBatches((RenderRegion)entry.getKey(), cascade);
            }
         }

         for(Map.Entry<RenderRegion, RegionPlan> entry : next.regions.entrySet()) {
            if (!previous.regions.containsKey(entry.getKey()) && !((RegionPlan)entry.getValue()).sections.isEmpty()) {
               clearRegionBatches((RenderRegion)entry.getKey(), cascade);
            }
         }

      }
   }

   private static void clearRegionBatches(RenderRegion region, int cascade) {
      region.clearCachedBatchFor(SodiumShadowTerrainPasses.solid(cascade));
      region.clearCachedBatchFor(SodiumShadowTerrainPasses.cutout(cascade));
      region.clearCachedBatchFor(SodiumShadowTerrainPasses.combined(cascade));
   }

   private static void clearPlanBatches(CascadePlan plan, int cascade) {
      if (plan != null) {
         for(ChunkRenderList list : plan.lists) {
            RenderRegion region = list.getRegion();
            clearRegionBatches(region, cascade);
         }

      }
   }

   private static record CascadePlan(List<ChunkRenderList> lists, ShadowRenderLists renderLists, Map<RenderRegion, RegionPlan> regions, long layoutVersion, long revision, double cameraX, double cameraY, double cameraZ, Vector3f lightDirection, Vector3f cameraForward, float cascadeEnd) {
      private boolean matches(long currentRevision, double currentCameraX, double currentCameraY, double currentCameraZ, Vector3f currentLightDirection, Vector3f currentCameraForward, float currentCascadeEnd, double movementLimit) {
         return this.revision == currentRevision && this.spatiallyMatches(currentCameraX, currentCameraY, currentCameraZ, currentLightDirection, currentCameraForward, currentCascadeEnd, movementLimit);
      }

      private boolean spatiallyMatches(double currentCameraX, double currentCameraY, double currentCameraZ, Vector3f currentLightDirection, Vector3f currentCameraForward, float currentCascadeEnd, double movementLimit) {
         double dx = currentCameraX - this.cameraX;
         double dy = currentCameraY - this.cameraY;
         double dz = currentCameraZ - this.cameraZ;
         return dx * dx + dy * dy + dz * dz <= movementLimit * movementLimit && this.lightDirection.dot(currentLightDirection) >= PLAN_DIRECTION_DOT && this.cameraForward.dot(currentCameraForward) >= PLAN_DIRECTION_DOT && Math.abs(this.cascadeEnd - currentCascadeEnd) < 0.5F;
      }
   }

   private static record RegionPlan(ChunkRenderList list, BitSet sections, long revision) {
   }

   private static record PlanContents(List<ChunkRenderList> lists, Map<RenderRegion, RegionPlan> regions) {
   }

   private static record ShadowRenderLists(List<ChunkRenderList> lists) implements ChunkRenderListIterable {
      public Iterator<ChunkRenderList> iterator(boolean reverse) {
         if (!reverse) {
            return this.lists.iterator();
         } else {
            final ListIterator<ChunkRenderList> iterator = this.lists.listIterator(this.lists.size());
            return new Iterator<ChunkRenderList>() {
               {
                  Objects.requireNonNull(ShadowRenderLists.this);
               }

               public boolean hasNext() {
                  return iterator.hasPrevious();
               }

               public ChunkRenderList next() {
                  return (ChunkRenderList)iterator.previous();
               }
            };
         }
      }
   }
}
