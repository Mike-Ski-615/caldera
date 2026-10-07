# CONTEXT.md — Caldera 光影的领域词表

这份文件给"读这个仓库的人与 agent"用：同一个概念只能有一个名字，名字要指向一个真实的模块。
新增模块时若它命名了一个这里没有的概念，把词补进来。决策与取舍记在 `docs/adr/`，不在本文件。

## 光影包与包图

- **光影包（shader pack）** — 用户放进光影包目录的一份资源。**原生包**是其中能解析出 `caldera.json`
  的那些；旧格式的包由 `LegacyPackMigration` 在扫描时摘掉。包"算不算数"由 `ShaderPackScanner`
  与 `NativePackRuntime.isNative` 共同决定。
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
  所以门闸在纯 JVM 里驱动得动。
- **渲染器（renderer）** — 一份包图在 GPU 上的实例，生命周期是 prepare → activate → close。
  模块是 `GraphRenderer`；它的 GPU 资源释放由 `GraphResourceLedger` 登记。
  "这一帧在用哪个渲染器"归 `SceneFrame` 持有，而"什么时候换上／换下"归 `NativePackRuntime` 决定。
- **场景目标（scene target）** — 包图声明为"要接管的原版渲染目标"的那几张图，用于把原版几何
  画进原生 pass。
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
  `MinecraftShaderHost`，测试实现是 `FakeShaderHost`。见 `docs/adr/0003`。
- **PreparedRenderer 句柄** — 一份已准备好、可以被激活或被丢弃的渲染器。**永远不为 null**。
- **装配根（composition root）** — `CompositionRoot.install(host)`：把同一份 `ShaderHost` 装进三个
  需要它的模块（`ShaderRuntime`、`NativePackRuntime`、`DirectionalShadowRenderer`），客户端入口只调
  这一句。`ReloadableResources` 与 `ShadowPassScope` 不需要安装。`ShaderRuntime.init()` 是"第一次
  读盘"，与装配分开、必须在它之后。
- **未安装适配器（not-installed adapter）** — 两个门面（`ShaderRuntime`、`NativePackRuntime`）各有
  两张实现：装好的那一份（`Installed*`）与"什么都没装"的那一份（`NotInstalled*`）。门面手上永不为
  null，所以"缺席时答什么"有一处可读、一处可测，而不是散在 21 个 null 分支里。
- **门禁（screenshot gate）** — 五个 `caldera.*` 系统属性驱动的无人值守截图流程，承载它的 5 个
  smoke 类住在独立的 dev mod（`src/smoke`，id `caldera-dev`）里，不进 jar。跑法见 `build.gradle` 顶部。

## ADR

- `docs/adr/0003-shader-lifecycle-behind-a-host-port.md` — 光影生命周期坐在 `ShaderHost` 端口之后，
  渲染器坐在句柄之后。ADR-0002 在此文件里被引用，但不在本仓库中。
