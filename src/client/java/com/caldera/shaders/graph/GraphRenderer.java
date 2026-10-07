package com.caldera.shaders.graph;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.util.UncheckedAutoCloseable;
import com.caldera.shaders.mixin.GraphDeviceAccessor;
import com.caldera.shaders.render.shadow.HeldLightShadowRenderer;
import com.caldera.shaders.render.shadow.ShadowService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

public final class GraphRenderer implements AutoCloseable {
   private static long generation;
   private static final String VERTEX = "#version 450\nlayout(location=0) out vec2 texCoord;\nvoid main() {\n\ttexCoord = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);\n\tgl_Position = vec4(texCoord * 2.0 - 1.0, 0.0, 1.0);\n}\n";
   private static final String PRESENT = "#version 450\nlayout(location=0) in vec2 texCoord;\nlayout(location=0) out vec4 color;\nuniform sampler2D Source;\nvoid main() { color = texture(Source, texCoord); }\n";
   private final PackGraph graph;
   private final MaterialTable materials;
   private final List<PackGraph.Pass> schedule;
   private final Map<PackGraph.Pass, RenderPipeline> pipelines = new LinkedHashMap<>();
   private final Map<PackGraph.Pass, ComputeProgram> computePipelines = new LinkedHashMap<>();
   /**
    * 这个渲染器注册进 {@link GraphShaderSources} 的那些管线归谁。
    * <p>
    * 原先这里是一个 {@code List<RenderPipeline> ownedPipelines}，创建时 add、close 时 forEach remove。
    * 那张清单现在由 {@code GraphShaderSources} 按 owner 记着，于是字段本身没有了——少一份需要与
    * 注册动作手工同步的东西。
    */
   private final GraphShaderSources.Owner shaderSources;
   private final Map<String, Image[]> images = new LinkedHashMap<>();
   private final GraphFrame frame = new GraphFrame();
   private HeldLightShadowRenderer heldShadows;
   private final GpuSampler sampler;
   private final GpuSampler linearSampler;
   private final RenderPipeline present;
   private int width;
   private int height;
   private int parity;
   private boolean closed;
   private long renderedFrames;
   private final ScenePrograms scenePrograms;
   private GpuBufferSlice windUniforms;
   private final ByteBuffer windBytes = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
   private static final long WIND_START = System.nanoTime();
   private GpuBufferSlice sceneUniforms;
   private final Map<String, Image> customTextures = new LinkedHashMap<>();
   private final Map<String, GpuSampler> textureSamplers = new LinkedHashMap<>();
   private final Map<String, Integer> mipFilters = new HashMap<>();
   private long textureBytes;
   private final GraphBuffers buffers;
   private final GraphSceneCapture sceneCapture;
   private final Set<RenderPipeline> heldPostPipelines = new HashSet<>();
   private final Set<RenderPipeline> shadowPostPipelines = new HashSet<>();
   /**
    * 这个渲染器持有的、需要释放的东西的账本。
    * <p>
    * 登记写在各自的创建处；{@link #close()} 只清账本。它**不**持有资源本身——那些还是上面的字段，
    * 因为它们的读写散布在这个类的 82 行里，把它们搬进访问器是一次与"谁负责释放"无关的大改。
    */
   private final GraphResourceLedger resources = new GraphResourceLedger();

   public MaterialTable materials() {
      return this.materials;
   }

   public HeldLightShadowRenderer heldShadows() {
      return this.heldShadows;
   }

   public long renderedFrames() {
      return this.renderedFrames;
   }

   public long sceneReplacementCount() {
      return this.scenePrograms.replacementCount();
   }

   public void invalidateHistory() {
      this.frame.reset();
   }

   public long terrainCaptures() {
      return this.sceneCapture.captures();
   }

   public void captureTerrain(RenderTarget main) {
      this.sceneCapture.capture(main);
   }

   public void captureWorldDepth(RenderTarget main) {
      this.sceneCapture.captureWorldDepth(main);
   }

   public GraphRenderer(PackGraph graph, PackFiles files, RenderTarget main) throws IOException {
      this.graph = graph;
      this.schedule = graph.schedule();
      this.frame.heldLighting = (Double)graph.options().getOrDefault("HELD_LIGHTING", (double)0.0F) > (double)0.0F;
      this.materials = MaterialTable.compile(graph.materials());
      this.materials.vegetationWind = graph.options().containsKey("VEGETATION_WIND");
      this.shaderSources = GraphShaderSources.owner("graph " + graph.name());
      // 这五族是纯容器，在字段初始化时就存在了，所以它们在这里登记，而不必等到构造器的后面。
      // 释放动作写在创建附近，"新加一族"就不再需要在两个地方各写一次。
      this.resources.onClose(() -> {
         this.computePipelines.values().forEach(ComputeProgram::close);
         this.computePipelines.clear();
      });
      this.resources.onClose(() -> {
         this.customTextures.values().forEach(Image::close);
         this.customTextures.clear();
      });
      this.resources.onClose(() -> {
         this.textureSamplers.values().forEach(UncheckedAutoCloseable::close);
         this.textureSamplers.clear();
      });
      this.resources.onClose(() -> {
         destroy(this.images);
         this.images.clear();
      });
      // 这些管线是构建出来的描述符，真正的资源在 GraphShaderSources 的注册表里；注销就是释放。
      this.resources.onClose(() -> {
         GraphShaderSources.releaseAll(this.shaderSources);
      });
      GpuDevice device = RenderSystem.getDevice();
      this.sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
      this.resources.own(this.sampler);

      try {
         this.linearSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
         this.resources.own(this.linearSampler);
         if (graph.sceneTargets().size() + 1 > device.getDeviceInfo().limits().maxColorAttachments()) {
            throw new IOException("GPU cannot provide the pack's scene attachments");
         } else {
            this.sceneCapture = new GraphSceneCapture(graph, main);
            this.resources.own(this.sceneCapture);
            if (this.frame.heldLighting) {
               this.heldShadows = new HeldLightShadowRenderer(this.frame.heldLight);
               this.resources.own(this.heldShadows);
            }

            this.scenePrograms = new ScenePrograms(graph, files);
            this.resources.own(this.scenePrograms);

            for(PackGraph.Pass pass : this.schedule) {
               if (pass.isCompute()) {
                  this.computePipelines.put(pass, new ComputeProgram(graph, pass, files));
               } else {
                  if (pass.writes().size() > device.getDeviceInfo().limits().maxColorAttachments()) {
                     int var10002 = pass.writes().size();
                     throw new IOException("Device cannot provide " + var10002 + " color attachments for " + pass.name());
                  }

                  List<GpuFormat> formats = pass.writes().stream().map((n) -> graph.resources().get(n).format()).toList();
                  this.pipelines.put(pass, this.pipeline(pass.name(), files.shader(pass.fragment(), graph.options()), pass.reads().keySet(), formats, true));
               }
            }

            this.present = this.pipeline("present", "#version 450\nlayout(location=0) in vec2 texCoord;\nlayout(location=0) out vec4 color;\nuniform sampler2D Source;\nvoid main() { color = texture(Source, texCoord); }\n", Set.of("Source"), List.of(main.getColorTexture().getFormat()), false);
            this.loadTextures(files);
            this.allocate(main.width, main.height);
            this.buffers = new GraphBuffers(graph);
            this.resources.own(this.buffers);
         }
      } catch (Exception failure) {
         this.close();
         throw new IOException("Cannot activate " + graph.name() + ": " + failure.getMessage(), failure);
      }
   }

   private RenderPipeline pipeline(String label, String fragment, Set<String> inputs, List<GpuFormat> formats, boolean frameUniform) throws IOException {
      long var10000 = ++generation;
      String path = "native/g" + var10000 + "/" + label;
      BindGroupLayout.Builder bindings = BindGroupLayout.builder();
      inputs.forEach((name) -> bindings.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER));
      if (frameUniform) {
         bindings.withUniform("CalderaFrame", UniformType.UNIFORM_BUFFER);
      }

      boolean shadows = frameUniform && this.graph.shadowQuality() > 0 && fragment.contains("uniform CalderaShadowData");
      if (shadows) {
         ShadowService.layout(bindings);
      }

      boolean held = this.frame.heldLighting && fragment.contains("uniform sampler2D CalderaHeldShadow0");
      if (held) {
         HeldLightShadowRenderer.layout(bindings);
      }

      RenderPipeline.Builder builder = RenderPipeline.builder(new RenderPipeline.Snippet[]{RenderPipelines.POST_PROCESSING_SNIPPET}).withLocation(Identifier.fromNamespaceAndPath("caldera", path)).withVertexShader(Identifier.fromNamespaceAndPath("caldera", path + "_vertex")).withFragmentShader(Identifier.fromNamespaceAndPath("caldera", path + "_fragment")).withBindGroupLayout(bindings.build());

      for(int i = 0; i < formats.size(); ++i) {
         builder.withColorTargetState(i, new ColorTargetState(Optional.empty(), (GpuFormat)formats.get(i), 15));
      }

      RenderPipeline pipeline = builder.build();
      if (shadows) {
         this.shadowPostPipelines.add(pipeline);
      }

      if (held) {
         this.heldPostPipelines.add(pipeline);
      }

      GraphShaderSources.put(this.shaderSources, pipeline, "#version 450\nlayout(location=0) out vec2 texCoord;\nvoid main() {\n\ttexCoord = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);\n\tgl_Position = vec4(texCoord * 2.0 - 1.0, 0.0, 1.0);\n}\n", fragment);
      if (RenderSystem.getCompiledPipelineNullable(pipeline) == null) {
         throw new IOException("Shader compilation failed in pass " + label + "; see log for source diagnostics");
      } else {
         return pipeline;
      }
   }

   private void allocate(int width, int height) {
      width = Math.max(1, width);
      height = Math.max(1, height);
      if (this.width != width || this.height != height) {
         if (this.graph.allocationBytes(width, height) + this.textureBytes + (this.heldShadows == null ? 0L : 7864320L) + this.sceneCapture.bytes(width, height) + ShadowService.memoryBytes(this.graph.shadowQuality()) > this.graph.budgetBytes()) {
            throw new IllegalArgumentException("Textures and render targets exceed pack memory budget");
         } else {
            Map<String, Image[]> fresh = new LinkedHashMap<>();
            GraphAllocations plan = GraphAllocations.plan(this.graph);
            Set<Integer> storageSlots = new HashSet<>();

            for(PackGraph.Pass pass : this.schedule) {
               if (pass.isCompute()) {
                  for(String output : pass.writes()) {
                     storageSlots.add((Integer)plan.resourceSlots().get(output));
                  }
               }
            }

            Map<Integer, Image[]> physical = new HashMap<>();

            try {
               GpuDevice device = RenderSystem.getDevice();

               for(String name : plan.resourceSlots().keySet()) {
                  PackGraph.Resource r = this.graph.resources().get(name);
                  if (r.mipmaps()) {
                     this.mipFilters.put(name, GraphMipmaps.filter(r.format()));
                  }

                  int slot = (Integer)plan.resourceSlots().get(name);
                  if (!physical.containsKey(slot)) {
                     int w = r.width(width);
                     int h = r.height(height);
                     int limit = device.getDeviceInfo().limits().maxTextureSizeForFormat(r.format());
                     if (w > limit || h > limit) {
                        throw new IllegalArgumentException("Unsupported size/format for " + name + ": " + w + "x" + h);
                     }

                     long size = r.bytes(width, height);
                     if (size > device.getDeviceInfo().limits().maxMemoryAllocationSize()) {
                        throw new IllegalArgumentException("Allocation exceeds device limit: " + name);
                     }

                     Image[] pair = new Image[r.history() ? 2 : 1];
                     fresh.put(name, pair);
                     physical.put(slot, pair);

                      for(int i = 0; i < pair.length; ++i) {
                         pair[i] = createStorageImage(device, "Caldera " + name, (r.depth() == 1 ? 8 : 0) | 4 | (!r.history() && !this.graph.sceneTargets().contains(name) && !r.mipmaps() ? 0 : 1) | (r.mipmaps() ? 2 : 0), r.format(), w, h, r.depth(), r.mipLevels(width, height), storageSlots.contains(slot), r.depth(), r.mipmaps());
                      }
                  } else {
                     fresh.put(name, (Image[])physical.get(slot));
                  }
               }
            } catch (Exception failure) {
               destroy(fresh);
               throw failure;
            }

            try {
               this.sceneCapture.resize(width, height);
            } catch (Exception failure) {
               destroy(fresh);
               throw failure;
            }

            Map<String, Image[]> old = new LinkedHashMap<>(this.images);
            this.images.clear();
            this.images.putAll(fresh);
            if (!old.isEmpty()) {
               RenderSystem.queueFencedTask(() -> destroy(old));
            }

            this.width = width;
            this.height = height;
            this.parity = 0;
            this.frame.reset();
         }
      }
   }

   public void render(RenderTarget main, CameraRenderState camera, Matrix4fc view, Object world) {
      if (this.closed) {
         throw new IllegalStateException("Graph is closed");
      } else {
         this.allocate(main.width, main.height);
         this.sceneCapture.captureWorldDepth(main);
         CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
         boolean reset = this.frame.discontinuity(camera, world, view);
         boolean resetGlobal = this.frame.globalDiscontinuity(camera, world);
         this.buffers.beginFrame(this.parity, reset);
         if (reset) {
            for(Image[] pair : this.images.values()) {
               if (pair.length == 2) {
                  for(Image image : pair) {
                     if (resetGlobal || image.texture.getWidth(0) != 1 || image.texture.getHeight(0) != 1 || image.texture.getDepthOrLayers() != 1) {
                        if (image.texture.getDepthOrLayers() > 1) {
                           ComputeProgram.clearVolume(image.texture);
                        } else {
                           encoder.clearColorTexture(image.texture, new Vector4f(0.0F));
                        }
                     }
                  }
               }
            }
         }

         GpuBufferSlice uniforms = this.frame.upload(encoder, camera, view, this.width, this.height, reset);
          boolean directPresent = false;
          if (main.getColorTexture() != null) {
              directPresent = canPresentDirectly(this.graph, (PackGraph.Pass)this.schedule.getLast(), main.getColorTexture().getFormat(), this.width, this.height);
          }
          this.graph.sceneTargets().forEach(this::generateMipmaps);

         for(PackGraph.Pass node : this.schedule) {
            if (node.isCompute()) {
               ((ComputeProgram)this.computePipelines.get(node)).dispatch(uniforms, (resource) -> node.writes().contains(resource) ? this.image(resource).attachment : this.resolve(resource, main), this::sampler, this.buffers, this.parity);
               node.writes().forEach(this::generateMipmaps);
            } else {
               RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(() -> {
                  String var10000 = this.graph.name();
                  return var10000 + "/" + node.name();
               });
               GpuTexture first = this.image((String)node.writes().getFirst()).texture;
               descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, first.getWidth(0), first.getHeight(0)));
               boolean outputToMain = directPresent && node.equals(this.schedule.getLast());

               for(String output : node.writes()) {
                   if (main.getColorTextureView() != null) {
                       descriptor.withColorAttachment(outputToMain ? main.getColorTextureView() : this.image(output).attachment, Optional.of(new Vector4f(0.0F)));
                   }
               }

               RenderPass pass = encoder.createRenderPass(descriptor.build());

               try {
                  pass.setPipeline(RenderSystem.getCompiledPipeline((RenderPipeline)this.pipelines.get(node)));
                  RenderSystem.bindDefaultUniforms(pass);
                  pass.setUniform("CalderaFrame", uniforms);
                  if (this.heldPostPipelines.contains(this.pipelines.get(node))) {
                     this.heldShadows.bind(pass);
                  }

                  if (this.shadowPostPipelines.contains(this.pipelines.get(node))) {
                     ShadowService.bindTerrain(pass);
                  }

                  node.reads().forEach((binding, resource) -> pass.setUniform(binding, this.resolve(resource, main), node.linearReads().contains(binding) ? this.linearSampler : this.sampler(resource)));
                  pass.draw(3, 1, 0, 0);
               } catch (Throwable var21) {
                   try {
                       pass.close();
                   } catch (Throwable var19) {
                       var21.addSuppressed(var19);
                   }

                   throw var21;
               }

                pass.close();

                node.writes().forEach(this::generateMipmaps);
            }
         }

         if (!directPresent) {
             RenderPass pass = null;
             if (main.getColorTextureView() != null) {
                 pass = encoder.createRenderPass(() -> "Caldera present", main.getColorTextureView(), Optional.empty());
             }

             try {
                 if (pass != null) {
                     pass.setPipeline(RenderSystem.getCompiledPipeline(this.present));
                 }
                 if (pass != null) {
                     RenderSystem.bindDefaultUniforms(pass);
                 }
                 if (pass != null) {
                     pass.setUniform("Source", this.image(this.graph.present()).view, this.sampler);
                 }
                 if (pass != null) {
                     pass.draw(3, 1, 0, 0);
                 }
             } catch (Throwable var20) {
                 try {
                    pass.close();
                 } catch (Throwable var18) {
                    var20.addSuppressed(var18);
                 }

                 throw var20;
            }

             if (pass != null) {
                 pass.close();
             }
         }

         this.parity ^= 1;
         this.frame.commit(camera, view, world);
         ++this.renderedFrames;
      }
   }

   static boolean canPresentDirectly(PackGraph graph, PackGraph.Pass last, GpuFormat format, int width, int height) {
      PackGraph.Resource output = (PackGraph.Resource)graph.resources().get(graph.present());
      return !last.isCompute() && last.writes().equals(List.of(graph.present())) && !last.reads().containsValue("$scene") && !output.history() && !output.mipmaps() && output.depth() == 1 && output.format() == format && output.width(width) == width && output.height(height) == height;
   }

   private Image image(String name) {
      Image[] pair = (Image[])this.images.get(PackGraph.current(name));
      return DoubleBuffer.current(pair, name, this.parity);
   }

   private void generateMipmaps(String name) {
      if (((PackGraph.Resource)this.graph.resources().get(name)).mipmaps()) {
         GraphMipmaps.generate(this.image(name).texture, (Integer)this.mipFilters.get(name));
      }

   }

   private GpuTextureView resolve(String resource, RenderTarget main) {

       return switch (resource) {
           case "$scene" -> main.getColorTextureView();
           case "$weather" -> this.sceneCapture.view("$weather");
           case "$depth" -> this.sceneCapture.view("$depth");
           case "$handDepth" -> main.getDepthTextureView();
           case "$terrainOpaque", "$terrainOpaqueDepth" -> this.sceneCapture.view(resource);
           default ->
                   resource.startsWith("$texture/") ? ((Image) this.customTextures.get(resource.substring(9))).view : this.image(resource).view;
       };
   }

   private GpuSampler sampler(String resource) {
      return resource.startsWith("$texture/") ? (GpuSampler)this.textureSamplers.get(resource.substring(9)) : this.sampler;
   }

   private void loadTextures(PackFiles files) throws IOException {
      Set<String> used = new HashSet<>();
      this.schedule.forEach((pass) -> pass.reads().values().forEach((input) -> {
            if (input.startsWith("$texture/")) {
               used.add(input.substring(9));
            }

         }));
      this.graph.scenePrograms().forEach((program) -> program.textures().values().stream()
            .filter((asset) -> !GraphSceneCapture.isInput(asset))
            .forEach(used::add));
      GpuDevice device = RenderSystem.getDevice();

      for(String name : used) {
         PackGraph.Texture definition = (PackGraph.Texture)this.graph.textures().get(name);
         byte[] png;
         if (definition.source().equals("$minecraft/clouds")) {
            InputStream stream = Minecraft.getInstance().getResourceManager().open(Identifier.withDefaultNamespace("textures/environment/clouds.png"));

            try {
               png = stream.readNBytes(8388609);
               if (png.length > 8388608) {
                  throw new IOException("Cloud texture exceeds 8 MiB");
               }
            } catch (Throwable var21) {
                try {
                    stream.close();
                } catch (Throwable var18) {
                    var21.addSuppressed(var18);
                }

                throw var21;
            }

             stream.close();
         } else {
            png = files.binary(definition.source());
         }

         ByteBuffer header = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN);
         if (png.length >= 24 && header.getLong(0) == -8552249625308161526L && header.getInt(12) == 1229472850) {
            int w = header.getInt(16);
            int h = header.getInt(20);
            int limit = device.getDeviceInfo().limits().maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM);
            long bytes = (long)w * (long)h * 4L;
            if (w > 0 && h > 0 && w <= limit && h <= limit && bytes <= device.getDeviceInfo().limits().maxMemoryAllocationSize() && bytes <= this.graph.budgetBytes() - this.textureBytes) {
               NativeImage image = NativeImage.read(new ByteArrayInputStream(png));

               try {
                  GpuTexture texture = device.createTexture("Caldera texture " + name, 5, GpuFormat.RGBA8_UNORM, w, h, 1, 1);

                  try {
                     this.customTextures.put(name, new Image(texture, device.createTextureView(texture)));
                  } catch (Exception failure) {
                     texture.close();
                     throw failure;
                  }

                  device.createCommandEncoder().writeToTexture(texture, image);
               } catch (Throwable var20) {
                   try {
                       image.close();
                   } catch (Throwable var17) {
                       var20.addSuppressed(var17);
                   }

                   throw var20;
               }

                image.close();

                AddressMode wrap = definition.repeat() ? AddressMode.REPEAT : AddressMode.CLAMP_TO_EDGE;
               FilterMode filter = definition.linear() ? FilterMode.LINEAR : FilterMode.NEAREST;
               this.textureSamplers.put(name, device.createSampler(wrap, wrap, filter, filter, 1, OptionalDouble.empty()));
               this.textureBytes += bytes;
               if (definition.source().equals("$minecraft/clouds")) {
                  this.frame.cloudTextureWidth(w);
               }
               continue;
            }

            throw new IOException("Custom texture exceeds device or pack limits: " + name);
         }

         throw new IOException("Custom textures must be PNG: " + name);
      }

   }

   public void beginScene(RenderTarget target, CameraRenderState camera, Matrix4fc view, Object world) {
      this.allocate(target.width, target.height);
      this.sceneCapture.beginFrame();
      CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

      for(String name : this.graph.sceneTargets()) {
         encoder.clearColorTexture(this.image(name).texture, new Vector4f(0.0F));
      }

      this.sceneUniforms = this.frame.upload(encoder, camera, view, this.width, this.height, this.frame.discontinuity(camera, world, view));
      this.windBytes.clear();
      this.windBytes.putFloat((float)(camera.pos.x - Math.floor(camera.pos.x / (double)4096.0F) * (double)4096.0F)).putFloat((float)(camera.pos.y - Math.floor(camera.pos.y / (double)4096.0F) * (double)4096.0F)).putFloat((float)(camera.pos.z - Math.floor(camera.pos.z / (double)4096.0F) * (double)4096.0F)).putFloat((float)(System.nanoTime() - WIND_START) * 1.0E-9F);
      this.windBytes.putFloat(((Double)this.graph.options().getOrDefault("VEGETATION_WIND", (double)0.0F)).floatValue()).putFloat(0.0F).putFloat(0.0F).putFloat(0.0F).flip();
      this.windUniforms = encoder.transientMemory().uploadGpu(this.windBytes, (long)RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), 128);
   }

   public GpuTextureView weatherView() {
      return this.sceneCapture.weatherView();
   }

   public void handProjection(Matrix4fc projection) {
      this.frame.handProjection(projection);
   }

   public void projection(Matrix4fc projection) {
      this.frame.projection(projection);
   }

   public void environment(LevelRenderState state, ClientLevel level, float partialTick) {
      this.frame.environment(state, level, partialTick);
   }

   public PackGraph.Environment environment() {
      return this.graph.environment();
   }

   public int shadowQuality() {
      return this.graph.shadowQuality();
   }

   public int shadowDistance() {
      return this.graph.shadowDistance();
   }

   public boolean animatedShadowCasters() {
      return (Double)this.graph.options().getOrDefault("VEGETATION_WIND", (double)0.0F) > (double)0.0F;
   }

   public RenderPipeline scenePipeline(RenderPipeline original) {
      return this.scenePrograms.replace(original, !this.graph.sceneTargets().isEmpty());
   }

   public RenderPipeline scenePipeline(RenderPipeline original, boolean attachments) {
      return this.scenePrograms.replace(original, attachments);
   }

   public RenderPipeline sceneFallback(RenderPipeline original, boolean attachments) {
      return this.scenePrograms.fallback(original, attachments);
   }

   public RenderPassDescriptor sceneAttachments(RenderPassDescriptor descriptor, RenderTarget main) {
      if (!this.graph.sceneTargets().isEmpty() && descriptor.colorAttachments().size() == 1 && descriptor.colorAttachments().getFirst() != null) {
         if (descriptor.colorAttachments().getFirst().textureView().texture() != main.getColorTexture()) {
            return descriptor;
         } else {
            ArrayList<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments = new ArrayList<>(descriptor.colorAttachments());

            for(String name : this.graph.sceneTargets()) {
               attachments.add(new RenderPassDescriptor.Attachment<>(this.image(name).attachment, Optional.empty()));
            }

            return new RenderPassDescriptor(descriptor.label(), attachments, descriptor.depthAttachment(), descriptor.renderArea());
         }
      } else {
         return descriptor;
      }
   }

   public boolean hasSceneAttachments(List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments) {
      return !this.graph.sceneTargets().isEmpty() && attachments.size() == this.graph.sceneTargets().size() + 1 && attachments.get(1) != null && attachments.get(1).textureView() == this.image((String)this.graph.sceneTargets().getFirst()).attachment;
   }

   public void bindSceneUniforms(RenderPass pass, RenderPipeline pipeline) {
      if (ScenePrograms.isReplacement(pipeline) && this.sceneUniforms != null && this.sceneUniforms.buffer().isClosed()) {
         throw new IllegalStateException("Scene uniforms expired before terrain rendering");
      } else if (this.windUniforms != null && (ScenePrograms.isReplacement(pipeline) || pipeline.getShaderDefines().flags().contains("CALDERA_VEGETATION")) && this.windUniforms.buffer().isClosed()) {
         throw new IllegalStateException("Wind uniforms expired before geometry rendering");
      } else {
         if (this.windUniforms != null && (ScenePrograms.isReplacement(pipeline) || pipeline.getShaderDefines().flags().contains("CALDERA_VEGETATION"))) {
            pass.setUniform("CalderaWind", this.windUniforms);
         }

         if (ScenePrograms.isReplacement(pipeline) && this.heldShadows != null) {
            this.heldShadows.bind(pass);
         }

         if (ScenePrograms.isReplacement(pipeline) && this.sceneUniforms != null) {
            pass.setUniform("CalderaFrame", this.sceneUniforms);
         }

         if (ScenePrograms.isReplacement(pipeline) && this.graph.shadowQuality() > 0) {
            ShadowService.bindTerrain(pass);
         }

         ScenePrograms.textures(pipeline).forEach((binding, asset) -> pass.setUniform(binding, GraphSceneCapture.isInput(asset) ? this.sceneCapture.view(asset) : ((Image)this.customTextures.get(asset)).view, GraphSceneCapture.isInput(asset) ? this.sampler : (GpuSampler)this.textureSamplers.get(asset)));
      }
   }

   /**
    * 建一张存储图：纹理、采样视图，以及需要时的 mip 视图。
    * <p>
    * <b>这里就是 {@link StorageImageScope} 的所属关系。</b>那个作用域原先以一段裸的
    * try-with-resources 长在 {@code allocate()} 的循环体里，它的全部含义是"必须恰好罩住这条纹理
    * **与它的视图**的创建"——因为 {@code GraphStorageTextureMixin} 与 {@code GraphStorageViewMixin}
    * 在 Vulkan 那边构造纹理与视图时会把它读回来（6 个站点，一字未改）。那份契约原先只存在于
    * 代码形状里：范围一旦被挪到别处，读回来的就是默认值，而表现是纹理的 usage／类型静默不对。
    * <p>
    * 写成方法之后，作用域与它所描述的那两次创建在同一个括号里：要挪也挪不开。
    */
   private static Image createStorageImage(GpuDevice device, String name, int usage, GpuFormat format, int width, int height, int depth, int mipLevels, boolean storage, int scopeDepth, boolean mipView) {
      try (StorageImageScope scope = new StorageImageScope(storage, scopeDepth)) {
         GpuTexture texture = ((GraphDeviceAccessor)device).caldera$backend().createTexture(name, usage, format, width, height, depth, mipLevels);

         try {
            GpuTextureView sampled = device.createTextureView(texture);

            try {
               return new Image(texture, sampled, mipView ? device.createTextureView(texture, 0, 1) : sampled);
            } catch (Exception failure) {
               sampled.close();
               throw failure;
            }
         } catch (Exception failure) {
            texture.close();
            throw failure;
         }
      }
   }

   private static void destroy(Map<String, Image[]> targets) {
      Set<Image[]> unique = Collections.newSetFromMap(new IdentityHashMap<>());
      unique.addAll(targets.values());
      unique.forEach((pair) -> {
         for(Image image : pair) {
            if (image != null) {
               image.close();
            }
         }

      });
   }

   /**
    * 释放这个渲染器持有的全部 GPU 资源。
    * <p>
    * <b>它不再是一张手工清单。</b>释放动作在各个族的创建处登记进 {@link GraphResourceLedger}，
    * 这里只负责把账本清一遍。原先这两个地方要人工同步，而不同步的表现是静默泄漏。
    * <p>
    * 可以重复调用；什么都没分配过时也是安全的。
    */
   public void close() {
      if (!this.closed) {
         this.closed = true;
         this.resources.close();
      }
   }

   private static record Image(GpuTexture texture, GpuTextureView view, GpuTextureView attachment) implements AutoCloseable {
      Image(GpuTexture texture, GpuTextureView view) {
         this(texture, view, view);
      }

      public void close() {
         if (this.attachment != this.view) {
            this.attachment.close();
         }

         this.view.close();
         this.texture.close();
      }
   }
}
