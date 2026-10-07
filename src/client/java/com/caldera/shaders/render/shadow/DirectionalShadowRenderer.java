package com.caldera.shaders.render.shadow;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.RenderTargetDescriptor.TextureProperties;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.caldera.shaders.config.ShaderQualityPreset;
import com.caldera.shaders.runtime.ReloadableResources;
import com.caldera.shaders.runtime.ShaderHost;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BooleanSupplier;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * 方向光阴影的 GPU 侧：RenderTarget 的分配与退役、UBO 上传、绑定。
 * <p>
 * <b>级联调度已经不在这里了。</b>哪几个级联这一帧要重画、每个级联画到哪里、用哪个矩阵，
 * 全部由 {@link CascadePlanner} 决策、装在 {@link CascadeSchedule} 里。这个类只负责：
 * 把游戏状态（相机、日月角、渲染距离、设备纹理上限、地形修订号）**作为参数**递给计划，
 * 然后按计划的结果做 GPU 侧的事。
 * <p>
 * 拆分的动机与 ADR-0003 一致：那套决策原先与 {@code RenderSystem}、{@code Minecraft} 混在一起，
 * 于是"第二帧会不会重画级联 1"这类问题无法在纯 JVM 里验证，而它错了只会安静地少画影子。
 * <p>
 * {@code beginCascade}/{@code endCascade} 那套配对已经搬进 {@link ShadowPassScope}：状态在那里，
 * 配对由它强制，这里只剩"进入时把要画进去的目标与要用的级联 UBO 解析出来"。
 */
public final class DirectionalShadowRenderer implements DirectionalShadowPass.Device {
   private static final int CASCADE_UBO_BYTES = 144;
   private static final int SHADOW_DATA_BYTES = 416;
   /**
    * "阴影开着"时用的档位。
    * <p>
    * 这是一个**断言**，不是配置：包只声明"质量大于零"这一件事（质量的 0..4 档位事实上恒为 2），
    * 所以没有第二条信息能说明该用哪一档。迁移前这个值来自 {@code ShadowQualityPreset.values()[2]}
    * ——也就是同一件事的另一种写法（当时的 {@code ShadowService.quality()} 把包声明的质量当天花板用，
    * 而实数只会是 2）。写成常量是为了把"事实如此"摆在明处；它同时消掉了那个未经上界检查的
    * {@code values()[quality]} 索引。
    */
   private static final ShaderQualityPreset enabledQuality = ShaderQualityPreset.MEDIUM;
   private static final Vector4f CLEAR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
   /**
    * 唯一实例。**由 {@link #install} 建出来，此后不再被置空**（{@link #close()} 与
    * {@link #retireUnused()} 拆的是 GPU 资源，不是这个壳）。见 {@link #get()} 与 {@link #install}。
    */
   private static DirectionalShadowRenderer instance;
   /**
    * 这几个 RenderTarget 跨资源重载存活（{@link #close()} 只拆纹理，不动计划状态），
    * 所以释放动作在这里登记一次。见 {@link ReloadableResources}。
    */
   private static final ReloadableResources.Owner RELOADABLE =
         ReloadableResources.owner("directional shadow renderer", DirectionalShadowRenderer::close);
   /**
    * 游戏能力的来源。**必须是静态的，不能是实例字段**——这一条是被一次真实崩溃教会的。
    * <p>
    * <b>历史：</b>它一度是实例字段，而那时 {@link #close()} 与 {@link #retireUnused()} 都会把
    * {@code instance} 置空（资源重载时 {@code close()} 一定会跑）。于是重载之后 {@link #get()}
    * 建出来的新实例手上没有宿主，{@link #prepare} 随即以 {@code IllegalStateException} 在渲染帧里
    * 崩掉——实测就是这么崩的。
    * <p>
    * <b>现在：</b>那个具体的崩溃路径已经不存在了（实例只由 {@link #install} 创建、此后不再被置空），
    * 所以这条约束不再靠"会不会崩"来维持。它仍然是**对的**约束，因为这两样东西的生命周期是**进程级**，
    * 而这个类的实例的生命周期是"一次 GPU 资源分配"——把一个进程级依赖挂在比它短的载体上，
    * 只是暂时没踩到而已。
    * <p>
    * 这个端口本身是必须的：计划要用"有效渲染距离"与"设备纹理边长上限"，而它们原先分别来自
    * {@code Minecraft.getInstance().options} 与 {@code RenderSystem.getDevice().getDeviceInfo().limits()}。
    * 计划自己不许再去够这两处。
    * <p>
    * <b>已知的验证缺口：</b>这条"必须是静态的"没有任何测试守卫得住——能观测到它的那条路要质量档位
    * 大于零，而那需要一个真的渲染器；直接断言字段则恒真（见
    * {@code DirectionalShadowRendererTeardownTest} 里那段说明）。守卫只有这里的文字与一次客户端内
    * 资源重载的手工验收。
    */
   private static ShaderHost host;
   /**
    * 这个包是否声明了会动阴影的投射者（植被风）。生命周期与 {@link #host} 相同，理由也相同：
    * 它是**包状态**，不属于某一次 GPU 资源分配。
    * <p>
    * 它由 {@code CompositionRoot} 从 {@code NativePackRuntime} 注入，而不是这里回头去读那个门面——
    * 那一条是 {@code render.shadow → graph} 的反向依赖。没装之前是 {@code false}，
    * 与"没有包生效"时的答案一致。
    */
   private static BooleanSupplier animatedCasters = () -> false;
   /**
    * "现在该不该投射阴影"：包开着阴影**且**这一帧的场景就绪（门面上的 {@code shadowsEnabled()}）。
    * <p>
    * 它是**包状态与帧状态的合取**，与 {@link #animatedCasters} 同类、同样的注入理由。两者都是门面方法的
    * 引用——这里不需要在装配点收紧成别的形状，因为那条判断本来就已经是一个是非题，它属于门面
    * （合取的两半拥有者不同：质量是包声明的，帧就绪只有 {@code SceneFrame} 答得出来）。
    * <p>
    * 它唯一的读取者是 {@link #retireUnused()}——"用户把阴影关掉之后退掉 GPU 资源"。没装之前是
    * {@code false}，而那正是"资源不该留着"的答案。
    */
   private static BooleanSupplier shadowsEnabled = () -> false;
   /**
    * 本帧生效的质量档位。由 {@link #prepare} 每帧从 {@link #shadowsEnabled} 与恒定档位
    * {@link #enabledQuality} 定出来。
    * <p>
    * 它是本帧状态（计划与 {@code uploadShadowData} 都读它），所以放在实例上；初值是 {@code OFF}，
    * 与"还没跑过 {@code prepare}"时的答案一致。
    */
   private ShaderQualityPreset activeQuality = ShaderQualityPreset.OFF;
   private final RenderTarget[] targets = new RenderTarget[4];
   private final RenderTarget[] entityTargets = new RenderTarget[4];
   private final int[] targetSizes = new int[4];
   private final int[] entityTargetSizes = new int[4];
   private final ByteBuffer cascadeUpload = ByteBuffer.allocateDirect(CASCADE_UBO_BYTES).order(ByteOrder.nativeOrder());
   private final ByteBuffer shadowUpload = ByteBuffer.allocateDirect(SHADOW_DATA_BYTES).order(ByteOrder.nativeOrder());
   private final CascadePlanner planner = new CascadePlanner();
   /**
    * 本帧的计划。初值是"什么都没有"，与迁移前那些字段的初值一致。
    * <p>
    * 注意它**不在** {@link #close()} 里被清掉：那条不对称是迁移前就有的（见 {@code close()} 的注释）。
    */
   private CascadeSchedule schedule = CascadeSchedule.empty();
   private GpuBufferSlice cascadeSlice;
   private GpuBufferSlice shadowDataSlice;
   /**
    * 本帧的相机位置，只给 {@link #sectionIntersectsCascade} 用。
    * <p>
    * 它必须活到本帧的 Sodium 渲染之后，所以存在实例上而不是随着 {@code prepare} 的局部变量消失。
    */
   private double cameraX;
   private double cameraY;
   private double cameraZ;

   /**
    * 包级可见而不是 private：{@link #get()} 的那个实例由 {@link #install} 建，而测试需要另建实例
    * 来验证拆除语义（不碰那个进程级实例）。构造器本身没有副作用（只初始化容器与 planner），
    * 所以放给同包没有风险。生产代码里唯一的构造点是 {@link #install}。
    */
   DirectionalShadowRenderer() {
   }

   /**
    * 装上游戏能力端口与包状态，必须在第一次 {@link #prepare} 之前调用。
    * <p>
    * 它写的是**静态**字段，因为那两样东西的生命周期是进程级；渲染器实例只建一次，依赖与它同寿
    * （见 {@link #host} 的注释——把它们挂到"会被拆掉重建"的实例上，曾经让资源重载之后的下一帧直接崩掉）。
    * <p>
    * 它同时**创建那个唯一的实例**：装配之后 {@link #get()} 手上永远有东西，不再需要"第一次问的时候
    * 建出来"这件事（见 {@link #get()}）。因此它也是"这一个进程里有没有阴影渲染器"的那一次决定。
    * <p>
    * 必须在 {@code NativePackRuntime.install()} **之后**调用：{@code animatedCasters} 是那个门面
    * 的一条查询。
    */
   public static void install(ShaderHost shaderHost, BooleanSupplier animatedCasters, BooleanSupplier shadowsEnabled) {
      host = shaderHost;
      DirectionalShadowRenderer.animatedCasters = animatedCasters;
      DirectionalShadowRenderer.shadowsEnabled = shadowsEnabled;
      instance = new DirectionalShadowRenderer();
   }

   /**
    * 这一个进程里唯一的方向光阴影渲染器。
    * <p>
    * <b>它不再懒建。</b>{@link #install} 在装配时就把它建出来，而 {@link #close()} 与
    * {@link #retireUnused()} **不再把它置空**——它们拆的是 GPU 资源，不是这个壳。
    * 于是"关掉阴影再打开"之间，实例与它的计划状态都是同一个（计划状态由
    * {@code CascadePlanner.suspend()} 与 {@code ensureResources} 的"资源变过没有"兜住，
    * 不需要靠重建实例来重置）。
    * <p>
    * 代价是它**可能为 {@code null}**：只能出现在 {@code install} 之前，也就是客户端初始化之前，
    * 而那条路径上根本不会有东西来问它。这是与 {@code SceneFrame} 同一套做法——"装好之前"
    * 是一个真实但预期不会用到的状态，靠装配顺序排除，而不是靠懒建掩盖。
    */
   public static DirectionalShadowRenderer get() {
      return instance;
   }

   @Override
   public void beginCascade(int cascade) {
      ShadowPassScope.enter(this.target(cascade), this.cascadeSlice);
   }

   @Override
   public void beginEntityCascade(int cascade) {
      ShadowPassScope.enter(this.entityTarget(cascade), this.cascadeSlice);
   }

   @Override
   public void endCascade() {
      ShadowPassScope.exit();
   }

   /**
    * 一帧的入口：收集输入 → 保证 GPU 资源 → 交给 {@link CascadePlanner} 决策 → 发布本帧的计划。
    * <p>
    * 顺序是行为的一部分，不能重排：{@code maxShadowSize} 必须先由设备上限求得，
    * {@code ensureResources} 必须先用它做分配并把"资源变过没有"作为计划的一个输入，
    * 计划才允许跑。反过来会让级联布局在资源重建的那一帧与纹理实际尺寸对不上。
    */
   @Override
   public void prepare(LevelRenderState levelRenderState, float packDistance) {
      boolean shadowsOn = shadowsEnabled.getAsBoolean();
      // 本帧档位：包只在"质量大于零"这一个恒定档位上（见 enabledQuality）。
      this.activeQuality = shadowsOn ? enabledQuality : ShaderQualityPreset.OFF;
      // 日月方向必须先于本帧的 plan() 刷新：plan() 按这两个方向挑光照方向（见 CascadePlanner）。
      // 刷新留在帧入口，下面那条路径上的 plan() 读到的就一定是本帧的天体状态。
      if (levelRenderState != null && levelRenderState.skyRenderState != null) {
         this.planner.updateCelestialDirections(levelRenderState.skyRenderState.sunAngle, levelRenderState.skyRenderState.moonAngle);
      }

      if (shadowsOn && levelRenderState != null && levelRenderState.cameraRenderState != null) {
         ShaderHost installed = host;
         if (installed == null) {
            throw new IllegalStateException("Caldera shadow renderer has no installed ShaderHost: DirectionalShadowRenderer.install() must run during client initialization");
         }

         ShaderQualityPreset quality = this.activeQuality;
         int maxShadowSize = Math.min(installed.maxTextureSizeForFormat(GpuFormat.R8_UNORM), installed.maxTextureSizeForFormat(GpuFormat.D32_FLOAT));
         boolean resourcesChanged = this.ensureResources(CascadePlanner.targetSizes(quality, maxShadowSize), quality, maxShadowSize);
         CameraRenderState camera = levelRenderState.cameraRenderState;
         if (camera.pos != null) {
            this.cameraX = camera.pos.x;
            this.cameraY = camera.pos.y;
            this.cameraZ = camera.pos.z;
         } else {
            this.cameraX = (double)0.0F;
            this.cameraY = (double)0.0F;
            this.cameraZ = (double)0.0F;
         }

         SkyRenderState sky = levelRenderState.skyRenderState;
         this.schedule = this.planner.plan(
               quality,
               this.cameraX,
               this.cameraY,
               this.cameraZ,
               camera.viewRotationMatrix,
               camera.projectionMatrix,
               sky != null,
               sky == null ? 0.0F : sky.sunAngle,
               sky == null ? 0.0F : sky.moonAngle,
               installed.renderDistance(),
               packDistance,
               true,
               animatedCasters.getAsBoolean(),
               maxShadowSize,
               CascadePlanner.terrainRevision(),
               resourcesChanged);
      } else {
         this.schedule = this.planner.suspend();
      }
   }

   @Override
   public int activeCascadeCount() {
      return this.schedule.activeCascadeCount();
   }

   public long cascadeLayoutVersion(int cascade) {
      return this.schedule.layoutVersion(cascade);
   }

   public Matrix4fc cascadeMatrix(int cascade) {
      return this.schedule.cascadeMatrix(cascade);
   }

   @Override
   public boolean shouldUpdateCascade(int cascade) {
      return this.schedule.cascadeUpdate(cascade);
   }

   @Override
   public boolean shouldUpdateEntityCascade(int cascade) {
      return this.schedule.entityCascadeUpdate(cascade);
   }

   public float cascadeEnd(int cascade) {
      return this.schedule.cascadeEnd(cascade);
   }

   public Vector3f lightDirection(Vector3f destination) {
      return destination.set(this.schedule.lightDirection());
   }

   public Vector3f cameraForward(Vector3f destination) {
      return destination.set(this.schedule.cameraForward());
   }

   public float entityShadowDistance() {
      return this.schedule.entityShadowDistance();
   }

   public float entityCascadeEnd(int cascade) {
      return this.schedule.entityCascadeEnd(cascade);
   }

   public RenderTarget target(int cascade) {
      return cascade >= 0 && cascade < 4 ? this.targets[cascade] : null;
   }

   public RenderTarget entityTarget(int cascade) {
      return cascade >= 0 && cascade < 4 ? this.entityTargets[cascade] : null;
   }

   public boolean sectionIntersectsCascade(int cascade, int originX, int originY, int originZ) {
      return this.schedule.sectionIntersectsCascade(cascade, originX, originY, originZ, this.cameraX, this.cameraY, this.cameraZ);
   }

   @Override
   public void uploadCascade(CommandEncoder encoder, int cascade) {
      this.cascadeUpload.clear();
      this.schedule.cascadeMatrix(cascade).get(this.cascadeUpload);
      this.cascadeUpload.position(64);
      Vector3fc light = this.schedule.lightDirection();
      putVec4(light.x(), light.y(), light.z(), 0.0F, this.cascadeUpload);
      this.cascadeUpload.position(80);
      this.schedule.inverseViewRotation().get(this.cascadeUpload);
      this.cascadeUpload.rewind();
      this.cascadeSlice = encoder.transientMemory().uploadGpu(this.cascadeUpload, (long)RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), 128);
   }

   @Override
   public void uploadShadowData(CommandEncoder encoder) {
      this.shadowUpload.clear();
      int activeCascadeCount = this.schedule.activeCascadeCount();

      for(int i = 0; i < 4; ++i) {
         this.schedule.cascadeMatrix(i).get(this.shadowUpload);
         this.shadowUpload.position((i + 1) * 64);
      }

      for(int i = 0; i < 4; ++i) {
         float depthRange = i < activeCascadeCount ? this.schedule.depthRange(i) : 0.0F;
         putVec4(this.schedule.texelWorldSize(i), i == 0 ? 0.0F : this.schedule.cascadeEndSlot(i - 1), this.schedule.cascadeEndSlot(i), depthRange, this.shadowUpload);
      }

      Vector3fc light = this.schedule.lightDirection();
      putVec4(light.x(), light.y(), light.z(), 0.0F, this.shadowUpload);
      putVec4(1.0F * this.schedule.celestialShadowFade(), 0.0F, 0.08F, filterSamples(this.activeQuality), this.shadowUpload);
      this.schedule.inverseViewRotation().get(this.shadowUpload);
      this.shadowUpload.rewind();
      this.shadowDataSlice = encoder.transientMemory().uploadGpu(this.shadowUpload, (long)RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), 128);
   }

   /**
    * 档位对应的阴影滤波采样数。
    * <p>
    * 迁移前它是 {@code uploadShadowData} 里的一个 {@code switch (ShadowService.quality())}，
    * 而那个查询现在归这里（见 {@code prepare}）。有一处**与原来不完全一样，是刻意的**：
    * 原来的 switch 在质量为零时也答 {@code 1.0F}（它落进 {@code OFF} 分支），而这里会对
    * {@code OFF} 抛。理由是这条路径的前提——{@code uploadShadowData} 只在阴影关卡真的执行时被调用，
    * 也就是"质量大于零"；答一个"关着时的采样数"没有意义，安静地答错不如大声。
    */
   private static float filterSamples(ShaderQualityPreset quality) {
      return switch (quality) {
         case LOW -> 1.0F;
         case MEDIUM -> 4.0F;
         case HIGH -> 9.0F;
         case ULTRA -> 16.0F;
         case OFF -> throw new IllegalStateException("Shadow filter samples asked for while shadows are off");
      };
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

   @Override
   public void clearCascade(CommandEncoder encoder, int cascade) {
      RenderTarget target = this.target(cascade);
      if (target != null && target.getColorTexture() != null && target.getDepthTexture() != null) {
         encoder.clearColorAndDepthTextures(target.getColorTexture(), CLEAR, target.getDepthTexture(), (double)1.0F);
      }

   }

   @Override
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
            sameSizes &= this.entityTargetSizes[i] == Math.min(maxShadowSize, quality.enabled() ? CascadePlanner.entityTargetSize(quality, sizes, i) : 1);
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
                  int entitySize = Math.min(maxShadowSize, quality.enabled() ? CascadePlanner.entityTargetSize(quality, sizes, i) : 1);
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

   /**
    * 用户把阴影关掉之后，把这张渲染器占的 GPU 资源退掉（排到栅栏之后，因为本帧可能还在用）。
    * <p>
    * <b>判据是"质量是否为零"。</b>原先它还问过 {@code ShadowService.enabled()}，而那等于
    * {@code shadowQuality() > 0 && shadowFrameReady()}，于是整个条件是
    * {@code instance != null && (!quality || !frameReady) && quality <= 0}——按短路求值，
    * 只有 {@code quality <= 0} 时才可能为真，{@code frameReady} 那一项从来看不到。所以这里直接问
    * 质量，行为不变，而且这个类不必再知道"帧就绪"是 shadow 侧原理上答不了的那件事。
    * <p>
    * <b>它不再把实例置空。</b>退掉的是 GPU 资源；那个壳与它的计划状态留着，
    * 下一次启用时接着用（计划状态由 {@link CascadePlanner#suspend()} 与
    * {@code ensureResources} 的"资源变过没有"兜住）。见 {@link #get()}。
    */
   public static void retireUnused() {
      DirectionalShadowRenderer live = instance;
      if (live != null && !shadowsEnabled.getAsBoolean()) {
         RenderSystem.queueFencedTask(live::destroyTargets);
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
      }

      // 迁移前这两行在同一个循环里：纹素世界尺寸与深度范围是与贴图分辨率绑定的，纹理拆了就该归零。
      // 它们现在住在计划模块里，所以这一步显式跨过接缝。
      this.planner.forgetTextureDerivedState();
   }

   private static void putVec4(float x, float y, float z, float w, ByteBuffer buffer) {
      buffer.putFloat(x);
      buffer.putFloat(y);
      buffer.putFloat(z);
      buffer.putFloat(w);
   }

   /**
    * 拆除 GPU 资源。可以重复调用；什么都没分配过时也是安全的。
    * <p>
    * <b>它不清理计划状态。</b>这条不对称是**故意的**、也是迁移前就有的：
    * {@link #ensureResources}（经由 {@code retireTargets}）与 {@link #retireUnused} 都会在重建资源时
    * 让"上一次的尺寸/资源"归零，而 {@code close()} 只拆 RenderTarget，不动帧号、布局版本、
    * 每个级联上次渲染的位置。换句话说：拿着同一个实例继续用的人，看到的计划历史是连续的。
    * <p>
    * <b>它也不再置空实例。</b>这两个渲染器方法以前都会把 {@code instance} 清掉，于是 {@code close()}
    * 之后的下一次 {@code get()} 会给出一个计划状态从头开始的新实例；现在实例只建一次（见 {@link #get()}），
    * 拆的只是资源。实测确认这不需要靠重建实例来重置：{@link CascadePlanner#suspend()} 会在未启用时
    * 把生效级联数归零，而重新分配资源会让 {@code ensureResources} 报"资源变过"，计划因此重算。
    * <p>
    * <b>拆除本身排到栅栏之后</b>（{@code RenderSystem.queueFencedTask}），与
    * {@link #ensureResources} 经由 {@code retireTargets} 的做法、以及 {@link #retireUnused} 一致。
    * 这条曾经是**同步**的，而那是同一模块里两种做法并存：
    * <ul>
    *   <li>调用场景：{@code MinecraftShaderHost.closeReloadableResources()} 的调用方是
    *       {@code InstalledShaderLifecycle} 的 {@code commit()} 与 {@code shutdown()}，
    *       而前者在 {@code reloadResources()} 之前**紧挨着**调它——也就是说上一帧的 GPU 命令
    *       可能仍在使用这些纹理，同步销毁疑似 use-after-free；</li>
    *   <li>同一个代码库里另一条销毁路径（{@code GraphRenderer} 重建自己的资源时）用的是
    *       {@code RenderSystem.queueFencedTask}，{@code SceneFrame.detach} 的排队处置也明写
    *       "当前帧可能还在用它的资源"。</li>
    * </ul>
    * 排到栅栏之后是这两条的统一，代价是销毁晚一两帧（由 {@code Minecraft} 每帧的
    * {@code executePendingTasks()} 推进）。{@code destroyTargets} 会把四个槽位置空，
    * 所以重复排队是安全的——{@link ReloadableResources} 的契约本来就允许反复调用。
    * <p>
    * <b>没有设备时退回同步拆除。</b>那个栅栏要求设备已经初始化，而 {@code close()} 是一个**收尾**
    * 动作——它不该在"设备还没建起来"的路径上抛（纯 JVM 测试就是一例；{@code ReloadableResources}
    * 会把它当成一次释放失败一并向调用方报）。真机里那一步永远走不到。
    */
   public static void close() {
      DirectionalShadowRenderer live = instance;
      if (live != null) {
         // 没有设备时（纯 JVM、或初始化之前）退回同步拆除。真机里设备一定在，
         // 而测试与任何"还没建起设备"的路径不该因为一个收尾动作而抛。
         try {
            RenderSystem.queueFencedTask(live::destroyTargets);
         } catch (IllegalStateException noDevice) {
            live.destroyTargets();
         }
      }
   }
}
