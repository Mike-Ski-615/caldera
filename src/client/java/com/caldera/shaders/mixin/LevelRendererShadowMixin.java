package com.caldera.shaders.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.caldera.shaders.graph.NativePackRuntime;
import com.caldera.shaders.mixin.sodium.SodiumWorldRendererAccessor;
import com.caldera.shaders.render.shadow.DirectionalShadowPass;
import com.caldera.shaders.render.shadow.DirectionalShadowPipelines;
import com.caldera.shaders.render.shadow.DirectionalShadowRenderer;
import com.caldera.shaders.render.shadow.DirectionalShadowSubmitFilter;
import com.caldera.shaders.render.shadow.HeldLightShadowRenderer;
import com.caldera.shaders.render.shadow.ShadowService;
import com.caldera.shaders.render.shadow.ShadowPassScope;
import com.caldera.shaders.render.shadow.SodiumShadowTerrainRenderer;
import com.caldera.shaders.runtime.ShaderRuntime;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.Optional;
import java.util.OptionalDouble;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({LevelRenderer.class})
public abstract class LevelRendererShadowMixin {
   @Shadow
   private GpuSampler chunkLayerSampler;
   @Shadow
   @Final
   private LevelRenderState levelRenderState;
   @Shadow
   @Final
   private EntityRenderDispatcher entityRenderDispatcher;
   @Shadow
   @Final
   private GameRenderer gameRenderer;
   @Shadow
   @Final
   private ModelManager modelManager;
   @Shadow
   @Final
   private AtlasManager atlasManager;
   @Unique
   private final SubmitNodeStorage caldera$nearShadowSubmitStorage = new SubmitNodeStorage();
   @Unique
   private final SubmitNodeStorage caldera$localShadowStorage = new SubmitNodeStorage();
   @Unique
   private DirectionalShadowSubmitFilter caldera$shadowSubmitFilter;
   @Unique
   private RenderBuffers caldera$shadowRenderBuffers;
   @Unique
   private FeatureRenderDispatcher caldera$shadowFeatureDispatcher;
   @Unique
   private boolean caldera$hasNearShadowEntitySubmits;

   @Inject(
      method = {"render"},
      at = {@At(
   value = "INVOKE",
   target = "Lnet/minecraft/client/renderer/LevelRenderer;submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V"
)}
   )
   private void caldera$collectDirectionalShadowEntities(GraphicsResourceAllocator allocator, boolean renderBlockOutline, CameraRenderState cameraRenderState, GpuBufferSlice fogParameters, Vector4f fogColor, boolean renderOutline, boolean consistentDepthRequired, CallbackInfo ci) {
      DeltaTracker deltaTracker = Minecraft.getInstance().getDeltaTracker();
      this.caldera$localShadowStorage.getSubmitsPerOrder().clear();
      HeldLightShadowRenderer local = NativePackRuntime.heldShadows();
      if (local != null && local.active() && !ShaderRuntime.resourceReloading()) {
         DirectionalShadowPipelines.ensureInitialized();
         DirectionalShadowSubmitFilter filter = new DirectionalShadowSubmitFilter(this.caldera$localShadowStorage, Double.MAX_VALUE);
         ClientLevel level = Minecraft.getInstance().level;
         if (level != null) {
            for(Entity entity : level.entitiesForRendering()) {
               if (!entity.isRemoved() && !entity.isSpectator() && !entity.isInvisible() && !(entity.position().distanceToSqr(local.light().position()) > (double)576.0F)) {
                  EntityRenderState state = this.entityRenderDispatcher.extractEntity(entity, deltaTracker.getGameTimeDeltaPartialTick(false));
                  this.entityRenderDispatcher.submit(state, cameraRenderState, state.x - cameraRenderState.pos.x, state.y - cameraRenderState.pos.y, state.z - cameraRenderState.pos.z, new PoseStack(), filter);
               }
            }
         }
      }

      this.caldera$nearShadowSubmitStorage.getSubmitsPerOrder().clear();
      this.caldera$hasNearShadowEntitySubmits = false;
      if (!ShaderRuntime.resourceReloading() && ShadowService.enabled()) {
         DirectionalShadowPipelines.ensureInitialized();
         DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
         this.caldera$shadowSubmitFilter = new DirectionalShadowSubmitFilter(this.caldera$nearShadowSubmitStorage, (double)Math.max(24.0F, shadows.entityCascadeEnd(0)));
         boolean hadEntityOutlines = this.levelRenderState.shouldShowEntityOutlines;
         this.levelRenderState.shouldShowEntityOutlines = false;

         try {
            this.caldera$submitNativeShadowEntities(deltaTracker, cameraRenderState);
            this.caldera$hasNearShadowEntitySubmits = this.caldera$hasShadowSubmits(this.caldera$nearShadowSubmitStorage);
         } finally {
            this.levelRenderState.shouldShowEntityOutlines = hadEntityOutlines;
         }

      }
   }

   @Inject(
      method = {"addMainPass"},
      at = {@At("HEAD")}
   )
   private void caldera$addDirectionalShadowPass(FrameGraphBuilder builder, FeatureRenderDispatcher.PreparedFrame preparedFrame, GpuBufferSlice viewPositions, ChunkSectionsToRender chunkSectionsToRender, boolean consistentDepthRequired, CallbackInfo ci) {
      if (!ShaderRuntime.resourceReloading() && (ShadowService.enabled() || NativePackRuntime.heldShadows() != null) && chunkSectionsToRender != null) {
         FramePass pass = builder.addPass("caldera:directional_shadow_maps");
         pass.disableCulling();
         pass.executes(() -> this.caldera$runDirectionalShadowPass());
      }
   }

   /**
    * 阴影关卡的游戏侧接线：这里只**取东西**（Sodium、采样器、命令编码器）与**接线**
    * （五个回调），顺序全在 {@link DirectionalShadowPass#execute} 里。
    * <p>
    * 五个回调之所以写成 lambda 而不是一个匿名类，是因为它们要摸这个 mixin 的
    * {@code @Shadow}／{@code @Unique} 成员：lambda 体会编译成本类自己的合成方法，对成员的
    * 引用就落在本类里，Mixin 的引用重写必然覆盖得到；换成内部类则要走 Mixin 的另一套处理，
    * 而这个项目里没有先例，出错的时机是运行时而非编译期。
    * <p>
    * 注意回调里那些"有没有 Sodium""有没有相机"的判断：它们是**适配器自己的守卫**，与原件里
    * 它们所在的位置一一对应（地形那一关要 Sodium 且相机就位，收尾刷 uniform 那一关只要 Sodium）。
    * 模块不该知道这些。
    * <p>
    * <b>与原件的一处顺序差异，记录在这里：</b>原件在 {@code prepare()} **之后**才取
    * {@code SodiumWorldRenderer}、采样器与命令编码器，而现在这三样要在装 {@code Frame} 时就取到，
    * 于是它们在 {@code prepare()} 之前。这三样都是纯粹的"取一个句柄"（两个 getter 与一次采样器
    * 缓存查询），谁都不依赖阴影资源是否已经分配；而 {@code prepare()} 本身不用编码器——真正用到
    * 它的是后面 {@code uploadCascade}。所以这个差异在成功路径上不可观测；唯一可观测的是
    * {@code createCommandEncoder()} 自己抛异常时，此时资源还没分配——那已经是一个坏掉的状态。
    */
   @Unique
   private void caldera$runDirectionalShadowPass() {
      DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
      SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
      GpuSampler sampler = this.chunkLayerSampler != null ? this.chunkLayerSampler : RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
      CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
      DirectionalShadowPass.execute(shadows, new DirectionalShadowPass.Frame(
            // 值
            this.levelRenderState,
            encoder,
            this.caldera$hasNearShadowEntitySubmits,
            ShadowService.enabled(),
            this.caldera$activeHeldLight() != null,
            // 动作
            cascade -> {
               if (sodiumRenderer != null && this.levelRenderState.cameraRenderState != null) {
                  this.caldera$renderSodiumTerrainShadow(sodiumRenderer, shadows, cascade, sampler);
               }
            },
            () -> {
               if (sodiumRenderer != null) {
                  ((SodiumWorldRendererAccessor)sodiumRenderer).caldera$uniformBufferManager().prepareFrame();
               }
            },
            () -> this.caldera$prepareAndRenderShadowEntities(this.caldera$nearShadowSubmitStorage),
            () -> this.caldera$renderHeldLightFrame(sodiumRenderer, sampler),
            NativePackRuntime::failScene,
            () -> {
               if (this.caldera$shadowRenderBuffers != null) {
                  this.caldera$shadowRenderBuffers.endFrame();
               }
            }));
   }

   /**
    * 这一帧存活的手持光源阴影渲染器，没有就是 {@code null}。
    * <p>
    * 这个条件在同一关里被问两次（模块问"要不要画"，画的时候再取一次），所以收成一处——
    * {@code heldShadows()} 是个普通 getter，两次读之间什么都没跑，因此与原件里读一次等价。
    */
   @Unique
   private HeldLightShadowRenderer caldera$activeHeldLight() {
      HeldLightShadowRenderer local = NativePackRuntime.heldShadows();
      return local != null && local.active() ? local : null;
   }

   /**
    * 手持光源那一关的实体渲染。"有没有启用"由模块判过，这里再取一次实例。
    */
   @Unique
   private void caldera$renderHeldLightFrame(SodiumWorldRenderer sodiumRenderer, GpuSampler sampler) {
      HeldLightShadowRenderer heldLight = this.caldera$activeHeldLight();
      if (heldLight == null) {
         return;
      }

      FeatureRenderDispatcher.PreparedFrame frame = this.caldera$shadowFeatureDispatcher().prepareFrame(this.caldera$localShadowStorage);

      try {
         heldLight.render(sodiumRenderer, this.levelRenderState.cameraRenderState, sampler, () -> this.caldera$renderShadowEntityFrame(frame));
      } finally {
         frame.close();
      }

   }

   @Unique
   private void caldera$renderSodiumTerrainShadow(SodiumWorldRenderer renderer, DirectionalShadowRenderer shadows, int cascade, GpuSampler sampler) {
      Vec3 cameraPos = this.levelRenderState.cameraRenderState.pos;
      if (cameraPos != null) {
         ((SodiumWorldRendererAccessor)renderer).caldera$uniformBufferManager().prepareFrame();
         ChunkRenderMatrices matrices = new ChunkRenderMatrices(new Matrix4f(shadows.cascadeMatrix(cascade)), new Matrix4f());
         SodiumShadowTerrainRenderer.renderCascade(renderer, shadows, cascade, matrices, cameraPos.x, cameraPos.y, cameraPos.z, sampler);
      }
   }

   @Inject(
      method = {"close"},
      at = {@At("HEAD")}
   )
   private void caldera$closeDirectionalShadowFeatureDispatcher(CallbackInfo ci) {
      if (this.caldera$shadowFeatureDispatcher != null) {
         this.caldera$shadowFeatureDispatcher.close();
         this.caldera$shadowFeatureDispatcher = null;
      }

      if (this.caldera$shadowRenderBuffers != null) {
         this.caldera$shadowRenderBuffers.close();
         this.caldera$shadowRenderBuffers = null;
      }

   }

   @Unique
   private void caldera$renderShadowEntityFrame(FeatureRenderDispatcher.PreparedFrame frame) {
      RenderTarget target = ShadowPassScope.target();
      RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Caldera shadow entities", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty());

      try {
         frame.executeSolid(pass);
         frame.executeTranslucent(pass);
         frame.executeTranslucentAfterTerrain(pass);
         frame.executeAlwaysOnTop(pass);
      } catch (Throwable var7) {
         if (pass != null) {
            try {
               pass.close();
            } catch (Throwable var6) {
               var7.addSuppressed(var6);
            }
         }

         throw var7;
      }

      if (pass != null) {
         pass.close();
      }

   }

   @Unique
   private void caldera$prepareAndRenderShadowEntities(SubmitNodeStorage storage) {
      FeatureRenderDispatcher.PreparedFrame frame = this.caldera$shadowFeatureDispatcher().prepareFrame(storage);

      try {
         this.caldera$renderShadowEntityFrame(frame);
      } finally {
         frame.close();
      }

   }

   @Unique
   private FeatureRenderDispatcher caldera$shadowFeatureDispatcher() {
      if (this.caldera$shadowFeatureDispatcher == null) {
         this.caldera$shadowRenderBuffers = new RenderBuffers(Runtime.getRuntime().availableProcessors());
         this.caldera$shadowFeatureDispatcher = new FeatureRenderDispatcher(this.caldera$shadowRenderBuffers, this.modelManager, this.atlasManager, Minecraft.getInstance().font, this.gameRenderer.gameRenderState());
      }

      return this.caldera$shadowFeatureDispatcher;
   }

   @Unique
   private void caldera$submitNativeShadowEntities(DeltaTracker deltaTracker, CameraRenderState cameraState) {
      ClientLevel level = Minecraft.getInstance().level;
      if (level != null && cameraState != null && cameraState.pos != null) {
         double distance = (double)Math.max(24.0F, DirectionalShadowRenderer.get().entityShadowDistance()) + (double)24.0F;
         float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);

         for(Entity entity : level.entitiesForRendering()) {
            double limit = distance + (double)Math.max(entity.getBbWidth(), entity.getBbHeight()) * (double)4.0F;
            if (!entity.isRemoved() && !entity.isSpectator() && !entity.isInvisible() && !(entity.position().distanceToSqr(cameraState.pos) > limit * limit)) {
               EntityRenderState state = this.entityRenderDispatcher.extractEntity(entity, partialTick);
               this.entityRenderDispatcher.submit(state, cameraState, state.x - cameraState.pos.x, state.y - cameraState.pos.y, state.z - cameraState.pos.z, new PoseStack(), this.caldera$shadowSubmitFilter);
            }
         }

      }
   }

   @Unique
   private boolean caldera$hasShadowSubmits(SubmitNodeStorage storage) {
      ObjectIterator var2 = storage.getSubmitsPerOrder().values().iterator();

      while(var2.hasNext()) {
         SubmitNodeCollection submitNodeCollection = (SubmitNodeCollection)var2.next();

         for(FeatureRenderPhase<?> phase : submitNodeCollection.allPhases()) {
            if (!phase.isEmpty()) {
               return true;
            }
         }
      }

      return false;
   }
}
