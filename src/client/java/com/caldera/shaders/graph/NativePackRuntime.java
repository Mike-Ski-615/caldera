package com.caldera.shaders.graph;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.caldera.shaders.config.ShaderConfig;
import com.caldera.shaders.pack.ShaderPackScanner;
import com.caldera.shaders.render.shadow.DirectionalShadowRenderer;
import com.caldera.shaders.render.shadow.HeldLightShadowRenderer;
import com.caldera.shaders.runtime.BackendStatus;
import com.caldera.shaders.runtime.ShaderRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4fc;

public final class NativePackRuntime {
   private static GraphRenderer active;
   private static String activeId;
   private static String failure;
   private static boolean sceneScope;
   private static Exception sceneFailure;
   private static CameraRenderState camera;
   private static Matrix4fc view;
   private static Matrix4fc worldProjection;
   private static boolean geometryRebuildPending;

   public static void captureHandProjection(Matrix4fc projection) {
      if (sceneScope && active != null) {
         active.handProjection(projection);
      }

   }

   public static void captureWorldProjection(Matrix4fc projection) {
      worldProjection = new Matrix4f(projection);
   }

   private NativePackRuntime() {
   }

   public static boolean isNative(Path path) {
      try {
         if (Files.isDirectory(path)) {
            Stream<Path> files = Files.walk(path, 2);

            boolean var10;
            try {
               var10 = files.anyMatch((p) -> p.getFileName().toString().equals("caldera.json") && Files.isRegularFile(p));
            } catch (Throwable var7) {
                try {
                    files.close();
                } catch (Throwable var5) {
                    var7.addSuppressed(var5);
                }

                throw var7;
            }

             files.close();

             return var10;
         } else {
            ZipFile zip = new ZipFile(path.toFile());

            boolean var2;
            try {
               var2 = zip.stream().anyMatch((e) -> !e.isDirectory() && (e.getName().equals("caldera.json") || e.getName().matches("[^/]+/caldera\\.json")));
            } catch (Throwable var6) {
               try {
                  zip.close();
               } catch (Throwable var4) {
                  var6.addSuppressed(var4);
               }

               throw var6;
            }

            zip.close();
            return var2;
         }
      } catch (IOException var8) {
         return false;
      }
   }

   public static boolean selected(ShaderConfig config) {
      return "__builtin__".equals(config.selectedPackId()) || config.selectedPackId() != null && PackFiles.safe(config.selectedPackId()) && !config.selectedPackId().contains("/") && !"__builtin__".equals(config.selectedPackId()) && isNative(ShaderPackScanner.shaderPackDirectory().resolve(config.selectedPackId()));
   }

   private static PackFiles packFiles(String id) throws IOException {
      return "__builtin__".equals(id) ? PackFiles.bundled() : PackFiles.read(ShaderPackScanner.shaderPackDirectory().resolve(id));
   }

   public static GraphRenderer prepare(ShaderConfig config) throws IOException {
      if (config.enabled() && BackendStatus.vulkanActive()) {
         if (!selected(config)) {
            throw new IOException("This pack is not a native Caldera shader pack. Select Caldera Realistic or a pack with caldera.json.");
         } else {
            PackFiles files = packFiles(config.selectedPackId());
            PackGraph graph = loadOptions(PackGraph.parse(files.text("caldera.json")), config.selectedPackId());
            return new GraphRenderer(graph, files, Minecraft.getInstance().gameRenderer.mainRenderTarget());
         }
      } else {
         return null;
      }
   }

   public static PackGraph settings(String id) throws IOException {
      PackFiles files = packFiles(id);
      return loadOptions(PackGraph.parse(files.text("caldera.json")), id);
   }

   private static Path optionFile(String id) {
      try {
         String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));
         return FabricLoader.getInstance().getConfigDir().resolve("caldera-packs").resolve(hash + ".json");
      } catch (NoSuchAlgorithmException impossible) {
         throw new AssertionError(impossible);
      }
   }

   private static PackGraph loadOptions(PackGraph graph, String id) throws IOException {
      Path path = optionFile(id);
      if (!Files.isRegularFile(path)) {
         return graph;
      } else {
         try {
            JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            Map<String, Double> values = new TreeMap<>();
            json.entrySet().forEach((e) -> {
               PackGraph.Option definition = graph.optionDefinitions().get(e.getKey());
               double value = e.getValue().getAsDouble();
               if (e.getKey().equals("COLOR_GRADE") && value == (double)0.75F && definition != null && definition.values().equals(List.of((double)0.0F, (double)0.25F, (double)0.5F, (double)1.0F))) {
                  value = 1.0F;
               }

               if (definition != null && definition.values().contains(value)) {
                  values.put(e.getKey(), value);
               }

            });
            return graph.withOptions(values);
         } catch (RuntimeException malformed) {
            throw new IOException("Invalid saved pack options: " + path, malformed);
         }
      }
   }

   public static void applyOptions(String id, Map<String, Double> values) throws IOException {
      PackFiles files = packFiles(id);
      PackGraph graph = PackGraph.parse(files.text("caldera.json")).withOptions(values);
      ShaderConfig config = ShaderRuntime.config();
      GraphRenderer candidate = config.enabled() && BackendStatus.vulkanActive() && id.equals(config.selectedPackId()) ? new GraphRenderer(graph, files, Minecraft.getInstance().gameRenderer.mainRenderTarget()) : null;
      Path destination = optionFile(id);
      Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");

      try {
         Files.createDirectories(destination.getParent());
         Files.writeString(temporary, (new GsonBuilder()).setPrettyPrinting().create().toJson(graph.options()));

         try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
         } catch (AtomicMoveNotSupportedException var9) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (IOException failure) {
         if (candidate != null) {
            candidate.close();
         }

         throw failure;
      }

      if (candidate != null) {
         activate(candidate, id);
      }

   }

   public static void activate(GraphRenderer next, String id) {
      sceneFailure = null;
      GraphRenderer old = active;
      active = next;
      activeId = next == null ? null : id;
      failure = null;
      if (old != null && old.materials().enabled() || next != null && next.materials().enabled()) {
         rebuildGeometry();
      }

      if (old != null) {
         Objects.requireNonNull(old);
         RenderSystem.queueFencedTask(old::close);
      }

   }

   public static boolean render(ShaderConfig config, CameraRenderState camera, Matrix4fc view) {
      if (config.enabled() && BackendStatus.vulkanActive() && active != null && config.selectedPackId().equals(activeId)) {
         try {
            active.render(Minecraft.getInstance().gameRenderer.mainRenderTarget(), camera, view, Minecraft.getInstance().level);
         } catch (Exception problem) {
            pause(problem);
         }

         return true;
      } else {
         return false;
      }
   }

   public static HeldLightShadowRenderer heldShadows() {
      return shadowFrameReady() && active != null ? active.heldShadows() : null;
   }

   public static boolean usesNativeTransparency() {
      return active != null && !ShaderRuntime.resourceReloading();
   }

   public static void scope(boolean enabled) {
      sceneScope = enabled;
   }

   public static void beginScene(CameraRenderState currentCamera, Matrix4fc currentView, LevelRenderState state, float partialTick) {
      if (Boolean.getBoolean("caldera.environmentSmoke") && FabricLoader.getInstance().isDevelopmentEnvironment()) {
         NativeEnvironmentSmoke.observe(state);
      }

      if (!geometryRebuildPending && GraphFrame.cameraReady(currentCamera, currentView)) {
         camera = currentCamera;
         view = currentView == null ? null : new Matrix4f(currentView);
         if (active != null) {
            try {
               active.projection(worldProjection);
               active.environment(state, Minecraft.getInstance().level, partialTick);
               active.beginScene(Minecraft.getInstance().gameRenderer.mainRenderTarget(), camera, view, Minecraft.getInstance().level);
            } catch (Exception problem) {
               pause(problem);
            }
         }

      } else {
         sceneScope = false;
         camera = null;
         view = null;
         if (active != null) {
            active.invalidateHistory();
         }

      }
   }

   public static void finishScene() {
      boolean ready = sceneScope && camera != null;
      sceneScope = false;
      if (sceneFailure != null) {
         Exception problem = sceneFailure;
         sceneFailure = null;
         pause(problem);
      } else if (ready && !ShaderRuntime.resourceReloading()) {
         render(ShaderRuntime.config(), camera, view);
      }

      camera = null;
      view = null;
   }

   public static RenderPassDescriptor sceneAttachments(RenderPassDescriptor descriptor) {
      return !DirectionalShadowRenderer.isRenderingShadowMap() && sceneScope && active != null && !ShaderRuntime.resourceReloading() ? active.sceneAttachments(descriptor, Minecraft.getInstance().gameRenderer.mainRenderTarget()) : descriptor;
   }

   public static RenderPipeline scenePipeline(RenderPipeline base, List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments) {
      if (!DirectionalShadowRenderer.isRenderingShadowMap() && sceneScope && active != null && !ShaderRuntime.resourceReloading()) {
         boolean targets = active.hasSceneAttachments(attachments);
         if (sceneFailure != null) {
            return active.sceneFallback(base, targets);
         } else {
            try {
               RenderPipeline replacement = active.scenePipeline(base, targets);
               if (ScenePrograms.isReplacement(replacement) && active.shadowQuality() > 0 && !DirectionalShadowRenderer.get().resourcesReady()) {
                  throw new IllegalStateException("Shadow producer did not run before native terrain");
               } else {
                  return replacement;
               }
            } catch (Exception problem) {
               sceneFailure = problem;
               return active.sceneFallback(base, targets);
            }
         }
      } else {
         return base;
      }
   }

   private static void pause(Exception problem) {
      failure = "Shader pack paused: " + problem.getMessage();
      LogUtils.getLogger().error(failure, problem);
      GraphRenderer old = active;
      active = null;
      activeId = null;
      if (old != null && old.materials().enabled()) {
         rebuildGeometry();
      }

      if (old != null) {
         Objects.requireNonNull(old);
         RenderSystem.queueFencedTask(old::close);
      }

   }

   public static void bindSceneUniforms(RenderPass pass, RenderPipeline pipeline) {
      if (sceneScope && active != null) {
         active.bindSceneUniforms(pass, pipeline);
      }

   }

   public static GpuTextureView weatherView() {
      return sceneScope && camera != null && active != null && sceneFailure == null && !ShaderRuntime.resourceReloading() ? active.weatherView() : null;
   }

   public static void captureTerrain() {
      if (sceneScope && camera != null && active != null && sceneFailure == null && !ShaderRuntime.resourceReloading()) {
         try {
            SceneRenderPass.outside(() -> active.captureTerrain(Minecraft.getInstance().gameRenderer.mainRenderTarget()));
         } catch (Exception problem) {
            sceneFailure = problem;
         }

      }
   }

   public static void captureTranslucentDepth() {
      if (sceneScope && camera != null && active != null && sceneFailure == null && !ShaderRuntime.resourceReloading()) {
         RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
          try {
              SceneRenderPass.outside(() -> active.captureWorldDepth(target));
          } catch (Exception problem) {
              sceneFailure = problem;
          }

      }
   }

   public static long terrainCaptures() {
      return active == null ? 0L : active.terrainCaptures();
   }

   public static void captureWorldDepth() {
      if (sceneScope && camera != null && active != null && sceneFailure == null && !ShaderRuntime.resourceReloading()) {
         try {
            active.captureWorldDepth(Minecraft.getInstance().gameRenderer.mainRenderTarget());
         } catch (Exception problem) {
            sceneFailure = problem;
         }

      }
   }

   public static void failScene(Exception problem) {
      sceneFailure = problem;
   }

   public static String failure() {
      return failure;
   }

   public static long renderedFrames() {
      return active == null ? 0L : active.renderedFrames();
   }

   public static long sceneReplacementCount() {
      return active == null ? 0L : active.sceneReplacementCount();
   }

   public static boolean replacesEnvironment(boolean clouds) {
      boolean var10000;
      label33: {
         Minecraft client = Minecraft.getInstance();
         if (active != null && shadowFrameReady() && !ShaderRuntime.resourceReloading() && client.level != null && client.level.dimension().equals(Level.OVERWORLD)) {
            if (clouds) {
               if (active.environment().clouds()) {
                  break label33;
               }
            } else if (active.environment().sky()) {
               break label33;
            }
         }

          return false;
      }

       return true;
   }

   public static int shadowQuality() {
      return active == null ? 0 : active.shadowQuality();
   }

   public static int shadowDistance() {
      return active == null ? 128 : active.shadowDistance();
   }

   public static boolean animatedShadowCasters() {
      return active != null && active.animatedShadowCasters();
   }

   public static boolean shadowFrameReady() {
      return sceneScope && camera != null && !geometryRebuildPending && sceneFailure == null;
   }

   public static MaterialTable materials() {
      return active == null ? null : active.materials();
   }

   private static void rebuildGeometry() {
      requestGeometryRebuild();
   }

   public static void requestGeometryRebuild() {
      geometryRebuildPending = true;
   }

   public static void flushGeometryRebuild() {
      if (geometryRebuildPending && !ShaderRuntime.resourceReloading()) {
         geometryRebuildPending = false;
         Minecraft client = Minecraft.getInstance();
         if (client.level != null) {
            RenderSystem.getDevice().createCommandEncoder().submit();
            client.levelRenderer.invalidateCompiledGeometry(client.level, client.options, client.gameRenderer.mainCamera(), client.getBlockColors());
         }

      }
   }

   public static void close() {
      if (active != null) {
         active.close();
         active = null;
      }

      activeId = null;
   }
}
