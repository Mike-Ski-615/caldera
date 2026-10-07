package com.caldera.shaders.graph;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.joml.Vector4f;

final class GraphSceneCapture implements AutoCloseable {
   static final String COLOR = "$terrainOpaque";
   static final String DEPTH = "$terrainOpaqueDepth";
   static final String WORLD_DEPTH = "$depth";
   static final String WEATHER = "$weather";
   private final Map<String, GpuFormat> formats = new LinkedHashMap<>();
   private Map<String, Image> images = new LinkedHashMap<>();
   private int width;
   private int height;
   private boolean captured;
   private boolean worldDepthCaptured;

   static boolean isInput(String name) {
      return "$terrainOpaque".equals(name) || "$terrainOpaqueDepth".equals(name);
   }

   GraphSceneCapture(PackGraph graph, RenderTarget main) {
      Set<String> used = new HashSet<>();
      graph.schedule().forEach((pass) -> used.addAll(pass.reads().values()));
      graph.scenePrograms().forEach((program) -> used.addAll(program.textures().values()));
      if (used.contains("$weather")) {
          if (main.getColorTexture() != null) {
              this.formats.put("$weather", main.getColorTexture().getFormat());
          }
      }

      if (used.contains("$terrainOpaque")) {
          if (main.getColorTexture() != null) {
              this.formats.put("$terrainOpaque", main.getColorTexture().getFormat());
          }
      }

      if (used.contains("$terrainOpaqueDepth")) {
         if (main.getDepthTexture() == null) {
            throw new IllegalArgumentException("Terrain depth capture requires a depth attachment");
         }

         this.formats.put("$terrainOpaqueDepth", main.getDepthTexture().getFormat());
      }

      if (used.contains("$depth")) {
         if (main.getDepthTexture() == null) {
            throw new IllegalArgumentException("World depth capture requires a depth attachment");
         }

         this.formats.put("$depth", main.getDepthTexture().getFormat());
      }

   }

   long bytes(int width, int height) {
      return this.formats.values().stream().mapToLong((format) -> (long)width * (long)height * (long)format.blockSize()).sum();
   }

   void resize(int width, int height) {
      if (this.width != width || this.height != height) {
         Map<String, Image> fresh = new LinkedHashMap<>();
         GpuDevice device = RenderSystem.getDevice();

         try {
            for(Map.Entry<String, GpuFormat> entry : this.formats.entrySet()) {
               GpuFormat format = (GpuFormat)entry.getValue();
               if (Math.max(width, height) > device.getDeviceInfo().limits().maxTextureSizeForFormat(format) || (long)width * (long)height * (long)format.blockSize() > device.getDeviceInfo().limits().maxMemoryAllocationSize()) {
                  throw new IllegalArgumentException("Terrain snapshot exceeds device limits: " + (String)entry.getKey());
               }

               GpuTexture texture = device.createTexture("Caldera " + (String)entry.getKey(), 13, format, width, height, 1, 1);

               try {
                  fresh.put((String)entry.getKey(), new Image(texture, device.createTextureView(texture)));
               } catch (Exception failure) {
                  texture.close();
                  throw failure;
               }
            }
         } catch (Exception failure) {
            fresh.values().forEach(Image::close);
            throw failure;
         }

         Map<String, Image> old = this.images;
         this.images = fresh;
         if (!old.isEmpty()) {
            RenderSystem.queueFencedTask(() -> old.values().forEach(Image::close));
         }

         this.width = width;
         this.height = height;
         this.captured = false;
         this.worldDepthCaptured = false;
      }
   }

   void beginFrame() {
      this.captured = false;
      this.worldDepthCaptured = false;
      CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
      this.images.forEach((name, image) -> {
         if (name.equals("$depth")) {
            encoder.clearDepthTexture(image.texture, (double)0.0F);
         } else if (name.equals("$terrainOpaqueDepth")) {
            encoder.clearDepthTexture(image.texture, (double)1.0F);
         } else {
            encoder.clearColorTexture(image.texture, new Vector4f(0.0F));
         }

      });
   }

   void capture(RenderTarget source) {
      if (!this.captured && (this.images.containsKey("$terrainOpaque") || this.images.containsKey("$terrainOpaqueDepth"))) {
         if (source.width == this.width && source.height == this.height) {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            this.images.forEach((name, image) -> {
               if (name.equals("$terrainOpaque") || name.equals("$terrainOpaqueDepth")) {
                   if ((name.equals("$terrainOpaqueDepth") ? source.getDepthTexture() : source.getColorTexture()) != null) {
                       encoder.copyTextureToTexture(name.equals("$terrainOpaqueDepth") ? source.getDepthTexture() : source.getColorTexture(), image.texture, 0, 0, 0, 0, 0, this.width, this.height);
                   }
               }

            });
            this.captured = true;
         } else {
            throw new IllegalStateException("Terrain snapshot size changed during scene rendering");
         }
      }
   }

   void captureWorldDepth(RenderTarget source) {
      Image image = (Image)this.images.get("$depth");
      if (!this.worldDepthCaptured && image != null) {
         if (source.width == this.width && source.height == this.height) {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(source.getDepthTexture(), image.texture, 0, 0, 0, 0, 0, this.width, this.height);
            this.worldDepthCaptured = true;
         } else {
            throw new IllegalStateException("World depth size changed during scene rendering");
         }
      }
   }

   GpuTextureView weatherView() {
      Image image = (Image)this.images.get("$weather");
      return image == null ? null : image.view;
   }

   GpuTextureView view(String name) {
      return ((Image)this.images.get(name)).view;
   }

   public void close() {
      this.images.values().forEach(Image::close);
      this.images.clear();
   }

   private static record Image(GpuTexture texture, GpuTextureView view) implements AutoCloseable {
      public void close() {
         this.view.close();
         this.texture.close();
      }
   }
}
