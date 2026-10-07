package com.caldera.shaders.render.shadow;

/**
 * 一帧里阴影关卡的**三条事实**：有没有实体要画、这一帧有没有人消费 shadow data、手持光源开没开。
 * <p>
 * 它们是**值**，不是效果——{@link DirectionalShadowPass.Context} 那一侧装的是"该画的时候怎么画"
 * （画地形、刷 uniform、画实体、报告失败、收尾），而这三条是"要不要画"。把两者分开之前，这三条
 * 混在 {@code Context} 里，与十几个动作方法并列，{@code Frame} 那个 record 也就同时承担了两种角色。
 * <p>
 * <b>为什么需要它们作为输入。</b>{@link DirectionalShadowPass} 不读任何静态状态，所以"这一帧有没有人
 * 消费 shadow data"必须由调用方回答——它原来是 {@code ShadowService.enabled()} 那类全局查询，
 * 而那种读法会让"不该上传却上传了"这条分支在测试里到不了。
 * <p>
 * <b>三条的出处不一样，这正是它们值得命名的理由：</b>
 * <ul>
 *   <li>{@code entitySubmits} —— 调用方**这一帧现算的**：谁持有那个 {@code SubmitNodeStorage}，
 *       谁才算得出来（生产侧是 {@code LevelRendererShadowMixin} 的近处提交收集）；</li>
 *   <li>{@code shadowDataConsumed} —— 两个包/帧查询的合取（质量大于零且帧就绪）；</li>
 *   <li>{@code heldLightActive} —— 一个包查询（这个包有没有要画的手持光源阴影）。</li>
 * </ul>
 * 三者都是{@code true} 表示"要做"，所以命名成肯定的说法，而不是"要不要跳过"。
 */
public record ShadowPassFacts(boolean entitySubmits, boolean shadowDataConsumed, boolean heldLightActive) {
}
