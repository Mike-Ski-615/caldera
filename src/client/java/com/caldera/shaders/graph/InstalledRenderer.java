package com.caldera.shaders.graph;

import com.caldera.shaders.render.shadow.HeldLightShadowRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4fc;

/**
 * 「这一帧真有渲染器」的那一个适配器：一层转发，包住 {@link GraphRenderer}。
 * <p>
 * 它是 {@link ActiveRenderer} 的形状说明——<b>二十八行代码里没有一行判断</b>。所有门闸、顺序与失败语义
 * 都在 {@link SceneFrame} 里，这里只负责让那个类不必认识 {@link GraphRenderer} 这个具体类型。
 * 相比之下 {@link AbsentRenderer} 才是有内容的那一半：缺席时到底答什么，写在那里。
 * <p>
 * {@link #present()} 是唯一一条不是转发的回答：这个适配器存在本身就意味着有渲染器。
 */
final class InstalledRenderer implements ActiveRenderer {

   private final GraphRenderer renderer;

   InstalledRenderer(GraphRenderer renderer) {
      this.renderer = renderer;
   }

   @Override
   public boolean present() {
      return true;
   }

   @Override
   public void close() {
      this.renderer.close();
   }

   @Override
   public MaterialTable materials() {
      return this.renderer.materials();
   }

   @Override
   public HeldLightShadowRenderer heldShadows() {
      return this.renderer.heldShadows();
   }

   @Override
   public long renderedFrames() {
      return this.renderer.renderedFrames();
   }

   @Override
   public long sceneReplacementCount() {
      return this.renderer.sceneReplacementCount();
   }

   @Override
   public long terrainCaptures() {
      return this.renderer.terrainCaptures();
   }

   @Override
   public int shadowQuality() {
      return this.renderer.shadowQuality();
   }

   @Override
   public int shadowDistance() {
      return this.renderer.shadowDistance();
   }

   @Override
   public boolean animatedShadowCasters() {
      return this.renderer.animatedShadowCasters();
   }

   @Override
   public PackGraph.Environment environment() {
      return this.renderer.environment();
   }

   @Override
   public void handProjection(Matrix4fc projection) {
      this.renderer.handProjection(projection);
   }

   @Override
   public void projection(Matrix4fc projection) {
      this.renderer.projection(projection);
   }

   @Override
   public void environment(LevelRenderState state, ClientLevel level, float partialTick) {
      this.renderer.environment(state, level, partialTick);
   }

   @Override
   public void beginScene(RenderTarget target, CameraRenderState camera, Matrix4fc view, Object world) {
      this.renderer.beginScene(target, camera, view, world);
   }

   @Override
   public void invalidateHistory() {
      this.renderer.invalidateHistory();
   }

   @Override
   public void render(RenderTarget main, CameraRenderState camera, Matrix4fc view, Object world) {
      this.renderer.render(main, camera, view, world);
   }

   @Override
   public GpuTextureView weatherView() {
      return this.renderer.weatherView();
   }

   @Override
   public void captureTerrain(RenderTarget main) {
      this.renderer.captureTerrain(main);
   }

   @Override
   public void captureWorldDepth(RenderTarget main) {
      this.renderer.captureWorldDepth(main);
   }

   @Override
   public RenderPassDescriptor sceneAttachments(RenderPassDescriptor descriptor, RenderTarget main) {
      return this.renderer.sceneAttachments(descriptor, main);
   }

   @Override
   public boolean hasSceneAttachments(List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments) {
      return this.renderer.hasSceneAttachments(attachments);
   }

   @Override
   public RenderPipeline scenePipeline(RenderPipeline original, boolean attachments) {
      return this.renderer.scenePipeline(original, attachments);
   }

   @Override
   public RenderPipeline sceneFallback(RenderPipeline original, boolean attachments) {
      return this.renderer.sceneFallback(original, attachments);
   }

   @Override
   public void bindSceneUniforms(RenderPass pass, RenderPipeline pipeline) {
      this.renderer.bindSceneUniforms(pass, pipeline);
   }
}
