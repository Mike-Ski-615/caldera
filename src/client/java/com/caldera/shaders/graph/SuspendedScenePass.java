package com.caldera.shaders.graph;

/**
 * 一个**被接管的 scene pass**：模块唯一需要适配器提供的两件事。
 * <p>
 * 为什么要这道接缝：这个模块真正的内容是"在 capture 期间把这个 pass 挂起、之后再恢复"，
 * 而原先这件事没有单一的测试面——模块自己就是 {@code RenderPass}，要驱动 {@code outside()}
 * 就得先造出一个模块实例，而唯一的造法是 {@code wrap()}，它要 Minecraft。
 * <p>
 * 把"怎么挂起、怎么恢复"退给适配器之后，模块只认这两个方法，于是协议（顺序、活跃对象的记帐、
 * 动作抛异常时仍然恢复、没有活跃对象时直接跑）可以用一个两行的替身测完。
 * <p>
 * 两个实现——{@code SplitScenePass} 与测试替身——所以按项目自己的规矩，这是**真接缝**，
 * 不是假想的。
 */
interface SuspendedScenePass {

   /**
    * 收起这个 pass：把它上面录下来的 debug group 逐个弹掉，然后关掉它。
    * <p>
    * debug group 必须弹掉而不能直接关：游戏那边的 pass 记着自己的层级，留下不配对的
    * {@code pushDebugGroup} 会让后续的命令流标签错位。
    */
   void suspend();

   /**
    * 用同一个 descriptor 恢复这个 pass：重建一个，然后把录下来的 debug group、状态、常量重放上去。
    * <p>
    * 调用方必须保证 {@code suspend()} 已经跑过——{@code outside()} 就是那么排的。
    */
   void resume();
}
