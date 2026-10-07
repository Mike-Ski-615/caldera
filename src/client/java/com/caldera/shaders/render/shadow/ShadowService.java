package com.caldera.shaders.render.shadow;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;

/**
 * 阴影那几张图**在 GPU 侧的名字与绑定**：{@code CalderaShadowData} 与 {@code CalderaShadowMap0..3}
 * 以及两张实体图。
 * <p>
 * <b>它现在只剩这一件事。</b>原先它还转着一组查询——{@code enabled()}、{@code quality()}、
 * {@code distance()}、{@code memoryBytes()}——而那些查询的拥有者不是它：质量与距离是**包声明的**
 * （graph 侧的事实），显存预算是 **sizing 算术的**。一个模块同时是"查询转发表"和"绑定名表"，
 * 读的人就分不清哪一半该改哪里。现在它们各归其主：
 * <ul>
 *    <li>档位与距离 → {@link DirectionalShadowRenderer}（它每帧拿到这两个输入）；</li>
 *    <li>显存预算 → {@link CascadePlanner#shadowMemoryBytes(int)}（sizing 算术本来就在那里）。</li>
 * </ul>
 * 留下 {@link #layout} 与 {@link #bindTerrain} 的理由很具体：那几个 uniform 名字是**阴影自己的知识**，
 * graph 侧的渲染器看不懂也不该懂。
 */
public final class ShadowService {
   private ShadowService() {
   }

   public static void layout(BindGroupLayout.Builder layout) {
      layout.withUniform("CalderaShadowData", UniformType.UNIFORM_BUFFER);

      for(int i = 0; i < 4; ++i) {
         layout.withUniform("CalderaShadowMap" + i, UniformType.COMBINED_IMAGE_SAMPLER);
      }

      for(int i = 0; i < 2; ++i) {
         layout.withUniform("CalderaEntityShadowMap" + i, UniformType.COMBINED_IMAGE_SAMPLER);
      }

   }

   public static void bindTerrain(RenderPass pass) {
      DirectionalShadowRenderer shadows = DirectionalShadowRenderer.get();
      if (!shadows.resourcesReady()) {
         throw new IllegalStateException("Shadow producer did not run before native terrain");
      } else if (shadows.shadowDataSlice().buffer().isClosed()) {
         throw new IllegalStateException("Shadow uniforms expired before their receiver pass");
      } else {
         pass.setUniform("CalderaShadowData", shadows.shadowDataSlice());
         GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

         for(int i = 0; i < 4; ++i) {
            pass.setUniform("CalderaShadowMap" + i, shadows.target(i).getDepthTextureView(), sampler);
         }

         for(int i = 0; i < 2; ++i) {
            pass.setUniform("CalderaEntityShadowMap" + i, shadows.entityTarget(i).getDepthTextureView(), sampler);
         }

      }
   }
}
