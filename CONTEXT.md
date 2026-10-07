# CONTEXT.md — Caldera 光影的领域词表

这份文件给"读这个仓库的人与 agent"用：同一个概念只能有一个名字，名字要指向一个真实的模块。
新增模块时若它命名了一个这里没有的概念，把词补进来。决策与取舍记在提交信息里，不在本文件；
工作区里没有 `docs/adr/`（历史 ADR 的落点见文末「已移除」）。

## 光影包与包图

- **光影包（shader pack）** — 用户放进光影包目录的一份资源。**原生包**是其中能解析出 `caldera.json`
  的那些；旧格式的包由 `LegacyPackMigration` 在扫描时摘掉。**包根规则**（清单在包根或一层容器目录里，
  且恰好一份）只有一处：`PackFiles.isNative`。界面列不列它另有一道门槛——清单还得**能解析**
  （`ShaderPackScanner` 走 `PackFiles.read` + `PackGraph.parse`）。**包目录在哪**由端口回答
  （`ShaderHost.packsRoot()`）：扫描器、运行时门面与界面都只认路径，不认 `FabricLoader`，
  也认不得"shaders"这个名字。
- **包图（pack graph）** — 一份 `caldera.json` 解析出来的完整描述：资源、pass、材质、场景程序、
  选项定义、显存预算。模块是 `PackGraph`，深而窄：入口只有 `parse(String)`。
- **包文件（pack files）** — 一个包在磁盘/压缩包里的字节。模块是 `PackFiles`。
- **包选项（pack options）** — 包自己声明的、用户可调的档位。合法性来自包图的 `optionDefinitions`
  （所以判断在 `NativePackRuntime`），落盘字节在 `CalderaConfigFiles`。

## 帧与渲染器

- **pass（通道）** — 包图里的一次全屏绘制或 compute 派发。**pass 调度**是把 pass 按依赖排成顺序，
  由 `PackGraph.schedule()` 产出。
- **场景帧（scene frame）** — 一帧里从 `scope(true)` 到 `finishScene()` 的区间，由 `NativeSceneMixin`
  在 `renderLevel` 的首尾圈出来。模块是 `SceneFrame`：它持有阶段、本帧在用的那个渲染器、以及**门闸**。
- **门闸（gate）** — "这一帧能不能画 / 能不能捕获 / 能不能读 uniform"的那些条件。每条门闸与它护着的
  动作合成**一个方法**（`captureTerrain()`、`weatherView()`、`scenePipeline()`…），调用方不再自己拼合取。
  `"资源重载中"` 与 `"正在画阴影贴图"` 是构造时注入的两个协作者（后者来自 `ShadowPassScope.active()`），
  所以门闸不吃全局状态，驱动它的两个输入都是构造参数。
- **渲染器（renderer）** — 一份包图在 GPU 上的实例，生命周期是 prepare → activate → close。
  模块是 `GraphRenderer`；它的 GPU 资源释放由 `GraphResourceLedger` 登记。
  "这一帧在用哪个渲染器"归 `SceneFrame` 持有，而"什么时候换上／换下"归 `NativePackRuntime` 决定。
- **生效渲染器（active renderer）** — "这一帧生效的是哪一个渲染器"这张表，模块是 `ActiveRenderer`
  （包级可见）。**缺席是一个实现了同一张表的适配器，不是 null**：`AbsentRenderer`（常量、无状态）答
  "没有生效的渲染器"，`InstalledRenderer` 把 `GraphRenderer` 包起来。`SceneFrame.attach` 只接受它，
  所以"有没有生效的渲染器"只有 `ActiveRenderer.present()` 一处可问。
  它与上面那条**渲染器**的区别：那是 GPU 实例，这是"这一帧用哪一个"；与下面的 `PreparedRenderer 句柄`
  的区别：那个承载生命周期**动作**（activate / close），这个承载**查询**。三者共同点只有一条：永不为 null。
  别把缺席的它与 `NotInstalledPackRuntime` 合并——那一个答的是"整份运行时还没装"，两处回答故意不同
  （`shadowFrameReady()` 与 `render(null, …)` 的契约都不一样）。
- **场景目标（scene target）** — 包图声明为"要接管的原版渲染目标"的那几张图，用于把原版几何
  画进原生 pass。
- **帧 uniform（CalderaFrame）** — 每帧一次、全包共用的那段 uniform 块。布局（23 个字段、1072 字节）
  只在 `FrameLayout` 里**声明一次**：GLSL 成员、字节偏移与 `environment[]` 的 36 个槽位都由它生成或
  派生；写入侧不再写魔数。它与 GLSL 的一致性只靠 `FrameLayout` 这一处声明维持（无自动化校验）。
- **可重载资源（reloadable resources）** — 跨**资源重载**存活的进程级 GPU 状态（管线缓存、
  阴影 RenderTarget、Sodium 的 render-list 计划缓存）。**所有者（owner）** 是持有它们的那一个模块；
  每个所有者在自己的定义处把释放动作登记进 `ReloadableResources`，释放只发生在 `closeAll()`。
  条目的契约是"让 owner 变空"，不是"释放一次"——重载会来很多次。
  与它相邻但不同的两个概念：`GraphShaderSources.Owner` 管的是**着色器源码/编译产物的注销**，
  `GraphResourceLedger` 管的是**一个渲染器**持有的资源（随渲染器消亡）。

## 阴影

- **级联（cascade）** — 方向光阴影按距离分的那几层，最多 4 层。
- **级联计划（cascade schedule）** — 这一帧哪几层要重画、每层画到哪、用哪个矩阵。决策模块是
  `CascadePlanner`，结果装在 `CascadeSchedule` 里；`DirectionalShadowRenderer` 只按计划做 GPU 侧的事。
- **阴影关卡（shadow pass）** — 一帧里真正把阴影贴图画出来的那一段，执行顺序在
  `DirectionalShadowPass.execute` 里。
- **阴影关卡作用域（shadow pass scope）** — "这一段渲染是不是阴影关卡的一部分、画进哪个目标、用哪份
  级联 UBO"，以及 Sodium 这一批 shadow pass 是哪些。模块是 `ShadowPassScope`：进出配对是**强制**的
  （没进就退、嵌套进入都抛），状态按线程隔离。级联号与"是不是实体关"不再被记下来——目标与 UBO 在
  进入时就由渲染器解析好交进来。
- **手持光源（hand-held light）** — 玩家手持发光物投出的近距阴影，模块是 `HeldLightShadowRenderer`；
  它嵌在方向光那一关里跑，用的是同一个阴影关卡作用域。
- **地形修订号（terrain revision）** — 地形几何变过没有的进程级计数，由 `CascadePlanner` 持有；
  级联缓存靠它判断还能不能复用。

## 边界与验证

- **ShaderHost 端口** — 光影运行时需要游戏提供的全部能力，用普通类型表达；生产实现是
  `MinecraftShaderHost`。测试实现在 2026-10 的一次清理里连同 `docs/adr/0003` 一起从工作区移除
  （两者都在 git 历史里，见文末「已移除」）。
- **PreparedRenderer 句柄** — 一份已准备好、可以被激活或被丢弃的渲染器。**永远不为 null**。
- **装配根（composition root）** — `CompositionRoot.install(host)`：把同一份 `ShaderHost` 装进三个
  需要它的模块（`ShaderRuntime`、`NativePackRuntime`、`DirectionalShadowRenderer`），客户端入口只调
  这一句。`ReloadableResources` 与 `ShadowPassScope` 不需要安装。`ShaderRuntime.init()` 是"第一次
  读盘"，与装配分开、必须在它之后。
- **未安装适配器（not-installed adapter）** — 两个门面（`ShaderRuntime`、`NativePackRuntime`）各有
  两张实现：装好的那一份（`Installed*`）与"什么都没装"的那一份（`NotInstalled*`）。门面手上永不为
  null，所以"缺席时答什么"只有这一处要回答，而不是散在 21 个 null 分支里。
- **门禁（screenshot gate）** — *已移除，见提交「删除开发期截图门禁（dev 专用 harness）」。*
  它曾是一套由五个 `caldera.*` 系统属性驱动的无人值守截图流程，承载它的 5 个 smoke 类住在独立的
  dev mod（`src/smoke`，id `caldera-dev`）里、不进 jar。移除的理由是范围：一个 dev-only 的测试装置
  却要占用构建接线、一个额外源码集、一个一次性世界和一份"世界必须叫 Caldera QA"的口头约定，
  而它从未在任何自动化里跑过——所有记录在案的跑次都是手动 PASS。（那个世界与 `run/` 一起在文末
  那次清理里删掉了。）
  <br>**方法论留下来，因为下一个手写视觉门禁的人会重新踩一遍：**
  它能可靠抓到的只有"崩了／包被暂停了／什么都没画"，抓不到细微的着色漂移。三条反直觉的读法：
  1. **单张 before/after 对比会骗人**——同一个构建跑两次，画面差异可以到 mean 4.4/255，偶尔还会出现
     一次明显偏灰的跑（mean 11.7）。唯一站得住的做法是每个构建跑 ≥2 次、比分布；抓细微漂移要 ≥3 次。
  2. **有些跑次必须判废而不是解读**——runClient 的窗口一旦失焦，单人世界会自动暂停，截图拍到的就是
     暂停菜单（体积掉到正常图的三分之一左右）。它既不是 PASS 也不是 FAIL：丢掉，不算一次采样。
  3. **画面相位无法对齐**——wind 相位取自 `System.nanoTime()`，云与水面按世界时间走，而截图发生在
     "第 120 帧"而不是"第 120 tick"，每跑一次的动画相位都不同。不要试图做像素级对齐。

## 已移除

2026-10 的一次"让项目变干净"清理移除了三样东西。它们都不在工作区里了；**前两样在 git 历史里**
（本文件所在提交的前一个提交 `27f964c`），第三样本来就只在工作区、从未进过版本控制。

- **`docs/adr/`** — 目录里只有 `0003-shader-lifecycle-behind-a-host-port.md`：光影生命周期坐在
  `ShaderHost` 端口之后、渲染器坐在**永不为 null** 的 `PreparedRenderer` 句柄之后；理由是不能用
  GPU 观察那些状态迁移，而 `resourceReloading` 门闸有二十个 mixin 调用点，顺序错一帧只会静默损坏。
  该文件自己记着一个**未验证**的缺口：重构后的构建在 Vulkan 上跑过全流程无告警，但**没跟参考构建
  做过并排截图对比**，"画出同样的画面"至今未验证；失败模式是静默的（能加载、能跑、什么都不画）。
  ADR-0002 在该文件里被引用，但它从未在本仓库中。
- **`src/test/`** — 31 个文件：单元测试（清单解析与校验、pass 调度、显存预算、路径安全、`#include`
  展开、光影包扫描、帧 uniform、阴影级联与关卡作用域）加两个测试替身 `FakeShaderHost`、
  `RecordingRenderPass`，以及夹具 `src/test/resources/caldera-realistic.json`。
  `build.gradle` 里的 JUnit 依赖、`sourceSets.test` 接线与 `tasks.withType(Test)` 配置同时删除。
- **`run/` 与 `logs/`** — 开发期运行目录（启动器缓存、配置、日志、崩溃报告）与项目根日志。
  从 `run/saves/CalderaQA` 这个名字可以看出它曾是上面那套截图门禁的一次性世界。
  两者都被 `.gitignore` 忽略，删除前共 49.1 MB。

