# Caldera

**Minecraft 的原生光影支持。** 直接编译 Vulkan/GLSL 着色器，由 Sodium 驱动地形渲染。

[![build](https://github.com/Mike-Ski-615/caldera/actions/workflows/build.yml/badge.svg)](https://github.com/Mike-Ski-615/caldera/actions/workflows/build.yml)
[![license](https://img.shields.io/badge/license-LGPL--3.0-blue)](LICENSE)

> **纯客户端模组**（`"environment": "client"`）。装到服务器上没有任何作用。

---

## 它和 Iris 有什么不同

Caldera **不是** shaderpack ZIP 加载器。它自带一套清单格式（`caldera.json`），直接编译
**Vulkan + GLSL 450** 着色器，因此可以做的事是 Iris 那套 OpenGL 流程做不到的：

- **原生 Vulkan 管线** —— 不走 GL 兼容层，直接建 GPU 管线与渲染通道
- **接管原版渲染目标** —— 原版几何可以画进原生 pass，而不是只做后处理
- **pack 声明式描述** —— pass 依赖、资源格式与显存预算写在清单里，由运行时校验

代价是 **不支持 Iris/OptiFine 的 shaderpack**。现有第三方光影包无法直接用。

---

## 功能

内置 **Caldera Realistic** 光影包，随模组发布、默认选中：

- **级联阴影** —— 地形、植被与附近实体模型（含第一人称玩家），可调画质与距离
- **体积云** —— 圆润造型、随风移动、内部光衰减、银边、天气响应与昼夜变色
- **天空** —— 白天／日落／夜晚、太阳圆盘、月相、星空与大气眩光
- **大气** —— 随高度变化的雾、天气阴霾、世界空间的太阳光束
- **光照** —— 冷色天光、日月方向光、暖色方块光、接触阴影
- **水面** —— 基于深度的折射、波浪法线、吸收、可见几何与天空的菲涅尔反射
- **后处理** —— HDR、降分辨率 bloom、色调与色彩处理

共 **28 项包选项**（光影设置界面可调），包括彩度、对比、曝光、泛光、雾、水面、
阴影与云画质等。

---

## 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| Minecraft | 26.3 | |
| Fabric Loader | >= 0.19.5 | |
| Fabric API | >= 0.160.5 | |
| Sodium | >= 0.9.2 | **硬依赖** —— 编译期即引用其内部类 |
| Java | >= 25 | |
| 图形后端 | **Vulkan** | 非 Vulkan 时 Caldera 整体停摆（见下） |

### 关于 Vulkan

Caldera 只在 **Vulkan** 后端下工作。若游戏跑在 OpenGL 上，首次进入标题界面会弹出提示，
可直接切到 Vulkan 并重启。

Vulkan 需要较新的显卡驱动。若设备/驱动不支持，游戏会回退到 OpenGL，此时光影无法启用
—— 这时继续用 OpenGL 游玩即可，模组不会影响正常游戏。

---

## 安装

1. 装好 **Fabric Loader**、**Fabric API** 与 **Sodium**
2. 把 `caldera-<version>.jar` 放进 `.minecraft/mods/`
3. 启动游戏，在 **视频设置 → 光影** 里确认已启用

内置包随模组提供，无需单独下载。也可以把额外的原生光影包放进 `.minecraft/shaders/`
后在界面里选择（该目录由模组创建）。

> 想恢复到没有光影的状态：在光影界面关闭开关即可，不需要删模组。

---

## 构建

```bash
./gradlew build          # 编译 + 打包
./gradlew clean build    # 从零构建
./gradlew runClient      # 启动开发环境客户端
```

需要 **JDK 25**，并设置 `JAVA_HOME`（指向 JDK 根目录，不是 `bin`）：

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-25"
```

产物在 `build/libs/`：

```
caldera-<version>.jar           模组本体
caldera-<version>-sources.jar   源码包
```

命名沿用 Gradle 默认规则（`archivesName` 取项目名，版本取 `version`），与官方
fabric-example-mod 模板一致，不带 Minecraft 版本后缀。

---

## 配置在哪

| 内容 | 位置 |
|---|---|
| 光影开关与选中的包 | `.minecraft/config/caldera-shaders.json` |
| 每个包的选项 | `.minecraft/config/caldera-packs/<SHA-256>.json` |
| 外部光影包 | `.minecraft/shaders/` |
| 构建版本号 | [gradle.properties](gradle.properties) |

版本号全部集中在 `gradle.properties`，不需要改构建脚本：

```properties
minecraft_version=26.3
loader_version=0.19.5
loom_version=1.18.2

version=0.0.1
group=com.caldera

fabric_api_version=0.161.0+26.3
sodium_version=0.9.2+mc26.3
mixinextras_version=0.5.5
```

`version` 与 `group` 用 Gradle 标准属性名，Gradle 直接读作 `project.version` /
`project.group`，所以 `build.gradle` 里不需要再赋值。`fabric.mod.json` 的 `"${version}"`
由 `processResources` 替换。

---

## 项目结构

```
src/
├── main/resources/
│   ├── fabric.mod.json                  模组清单（Loader 运行期必需）
│   ├── caldera-shaders.mixins.json
│   ├── assets/caldera/icon.png          模组图标
│   └── caldera-bundled/                 内置光影包（见下）
└── client/java/                         全部代码
    └── com/caldera/shaders/
        ├── graph/        包图、渲染器、帧协议
        ├── runtime/      生命周期与 ShaderHost 端口
        ├── render/shadow/ 阴影级联与手持光源
        ├── pack/         包扫描与旧格式迁移
        ├── screen/       光影与设置界面
        ├── config/       设置读写
        └── mixin/        与原版的接缝
```

`loom { splitEnvironmentSourceSets() }` 把 `main` 与 `client` 拆成两个 source set。
本模组纯客户端，`src/main/java` 为空。

### 内置光影包不进版本控制

`src/main/resources/caldera-bundled/Caldera-Realistic.zip` 是**视觉内容**，由作者单独维护，
已加入 `.gitignore`。**从仓库 clone 后构建需要先把它放到该位置**，否则打出的 jar 不含内置包。

领域词表（同一个概念只有一个名字）见 [CONTEXT.md](CONTEXT.md)。

---

## 网络问题（中国大陆）

几个源在境内很慢，实测数据：

| 源 | 实测 | 处理方式 |
|---|---|---|
| `maven.fabricmc.net`（Loom 插件、Fabric API） | **0.02–0.08 MB/s，且会反复完全卡死** | **必须走代理**，无可用镜像 |
| Gradle 发行包（`services.gradle.org`） | 经代理 64–73 秒；直连 3 次里 2 次 0 字节 | 交给代理，一次性下载后长期缓存 |
| Maven Central | 直连 7.5 MB/s，走代理只有 1.9 MB/s | **加入代理绕过列表** |
| `maven.caffeinemc.net`（Sodium） | 0.5 MB/s | 无镜像，文件小可接受 |

`distributionUrl` 保持官方地址（与官方模板一致）。若哪天需要重下 Gradle，境内可临时改成
`https://mirrors.cloud.tencent.com/gradle/`（实测 26–54 MB/s，3–5 秒），但**不要提交这个改动**
—— 它在 GitHub Actions 的境外 runner 上反而更慢。

如果卡在下载，配一个 HTTP 代理（**放在全局 `~/.gradle/gradle.properties`，不要提交到仓库**）：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7897
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7897
systemProp.http.nonProxyHosts=repo1.maven.org|maven.aliyun.com|localhost|127.0.0.1
systemProp.https.nonProxyHosts=repo1.maven.org|maven.aliyun.com|localhost|127.0.0.1
```

注意 `--refresh-dependencies` 在这类网络下**不可靠**：它会强制放大对 `maven.fabricmc.net`
的请求量，容易撞上连接重置并报出误导性的 `TLS protocol versions` 错误。普通构建不受影响。

---

## 构建配置说明

- **`build.gradle` 的 `repositories` 只声明 CaffeineMC** —— Loom 会自动添加 Minecraft、
  Fabric 与 Maven Central 三个仓库，Sodium 是唯一需要额外声明的依赖。
- **`org.gradle.configuration-cache=true`** 已启用。因此 `processResources` 必须先把
  `project.version` 取到局部变量再用于 `expand`；直接写 `inputs.properties.version` 会在
  configuration cache 下失败（copy-spec 闭包内拿不到 `inputs`）。
- **`.gitattributes`** 固定了换行符：`gradlew` 用 LF（否则在 Git Bash / WSL / Linux 上是
  `bad interpreter: /bin/sh^M`），`*.bat` 用 CRLF。请勿删除。

---

## 已知限制

- **不支持 Iris/OptiFine 的 shaderpack**，只认 `caldera.json` 原生格式
- **仅主世界**替换天空/雾/云；其他维度保留原版环境
- **实体与粒子**保留原版场景光照，只接受最终调色
- **彩色半透明阴影、方块实体与物品几何**未接入阴影
- 水面反射是**屏幕空间**的：视野外、被遮挡、或在快照之后绘制的几何不可见
- 太阳光束是有界世界射线积分，**没有时域累积**
- 地形走原版 LDR 场景目标，HDR 后处理**无法恢复**已削顶的地形高光
- 没有 TAA、PBR、彩色体素光照，也没有可选模组兼容层
- 云画质设为「关」时跳过追踪，但仍保留半分辨率的中间分配
- 显存预算按 4K／高阴影覆盖设定为 **448 MiB**，实际占用随分辨率与画质变化
- **AMD/Intel 设备与长时间游玩未验证**（开发与验证在 NVIDIA 上）
- 尺寸与质量调低时行为已测，但**极端设置组合**（如 4K + 最高阴影 + 最高云）未穷举

---

## 许可证

本项目采用 **GNU Lesser General Public License v3.0**（LGPL-3.0）。

| 文件 | 内容 |
|---|---|
| [LICENSE](LICENSE) | LGPL-3.0 附加许可全文 |
| [COPYING](COPYING) | GPL-3.0 全文（LGPL-3.0 是它的附加许可，必须一并提供） |

两者都会被打进模组 jar（文件名带 `_caldera` 后缀，避免多模组环境下与其他模组的许可证文件冲突）。

简言之：**你可以自由使用、修改、分发本模组，包括用于闭源项目；但若你修改了 Caldera
本身的代码并分发，修改部分必须以 LGPL-3.0 开源。** 详情以两份文本为准。

注意：本许可证只覆盖 **Caldera 自己的代码**。Minecraft 的代码与资源归 Mojang 所有，
Sodium 归其作者所有（同为 LGPL-3.0），均不在本项目授权范围内。
