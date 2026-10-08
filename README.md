# Caldera

Minecraft 的原生光影支持，基于 Vulkan 渲染器，由 Sodium 驱动。

> 本模组是**纯客户端**模组（`"environment": "client"`），装到服务器上没有意义。

## 环境要求

| 项 | 版本 |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | >= 0.19.4 |
| Fabric API | >= 0.160.5 |
| Sodium | >= 0.9.2（**硬依赖**，编译期即引用其内部类） |
| Java | >= 25 |

## 构建

```bash
./gradlew build          # 编译 + 打包
./gradlew clean build    # 从零构建
./gradlew runClient      # 启动开发环境客户端
```

产物在 `build/libs/`：

```
caldera-<mod_version>-<minecraft_version>.jar           模组本体
caldera-<mod_version>-<minecraft_version>-sources.jar   源码包
```

例如 `caldera-0.5.1-26.3.jar`。产物名由 `build.gradle` 里的 `AbstractArchiveTask` 统一设置。

**需要 JDK 25，并设置 `JAVA_HOME`**（指向 JDK 根目录，不是 `bin`）：

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-25"
```

## 配置在哪

版本号全部集中在 [gradle.properties](gradle.properties)，不需要改构建脚本：

```properties
minecraft_version=26.3
loader_version=0.19.5
loom_version=1.18.2

version=0.5.1
group=com.caldera

fabric_api_version=0.161.0+26.3
sodium_version=0.9.2+mc26.3
mixinextras_version=0.5.5
```

`version` 与 `group` 用的是 Gradle 标准属性名，Gradle 会直接读作 `project.version` / `project.group`，
所以 `build.gradle` 里不需要再赋值。`fabric.mod.json` 里的 `"${version}"` 由 `processResources` 替换。

## 目录结构

```
src/
├── main/resources/
│   ├── fabric.mod.json          模组清单（Loader 运行期必需）
│   ├── caldera-shaders.mixins.json
│   └── assets/caldera/icon.png  模组图标
└── client/java/                 全部代码（本模组纯客户端，src/main/java 为空）
```

`loom { splitEnvironmentSourceSets() }` 把 `main` 与 `client` 拆成两个 source set，
客户端专属代码放 `src/client`，服务端不会加载它。

领域词表见 [CONTEXT.md](CONTEXT.md)。

## 网络问题（中国大陆）

这个项目有几个源在境内很慢，实测数据：

| 源 | 直连 | 处理方式 |
|---|---|---|
| `maven.fabricmc.net`（Loom 插件、Fabric API） | **0.02–0.08 MB/s，且会完全卡死** | **必须走代理**，无可用镜像 |
| Gradle 发行包 | 30 MB/s | 已改指腾讯云镜像（49 MB/s） |
| Maven Central | 7.5 MB/s | 走代理反而更慢（1.9 MB/s），已加入代理绕过列表 |
| `maven.caffeinemc.net`（Sodium） | 0.5 MB/s | 无镜像，文件小可接受 |

如果卡在下载，配一个 HTTP 代理（**放在全局 `~/.gradle/gradle.properties`，不要提交到仓库**）：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7897
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7897
systemProp.http.nonProxyHosts=repo1.maven.org|maven.aliyun.com|localhost|127.0.0.1
systemProp.https.nonProxyHosts=repo1.maven.org|maven.aliyun.com|localhost|127.0.0.1
```

注意 `--refresh-dependencies` 在这类网络下**不可靠**：它会强制放大对 `maven.fabricmc.net` 的请求量，
容易撞上连接重置并报出误导性的 `TLS protocol versions` 错误。普通构建不受影响。

## 构建配置说明

- **`build.gradle` 的 `repositories` 只声明 CaffeineMC** —— Loom 会自动添加 Minecraft、Fabric
  与 Maven Central 三个仓库，Sodium 是唯一需要额外声明的依赖。
- **`org.gradle.configuration-cache=true`** 已启用。因此 `processResources` 必须先把
  `project.version` 取到局部变量再用于 `expand`；直接写 `inputs.properties.version` 会在
  configuration cache 下失败（copy-spec 闭包内拿不到 `inputs`）。
- **`.gitattributes`** 固定了换行符：`gradlew` 用 LF（否则在 Git Bash / WSL / Linux 上是
  `bad interpreter: /bin/sh^M`），`*.bat` 用 CRLF。请勿删除。

## 许可证

本项目采用 **GNU Lesser General Public License v3.0**（LGPL-3.0）。

| 文件 | 内容 |
|---|---|
| [LICENSE](LICENSE) | LGPL-3.0 附加许可全文 |
| [COPYING](COPYING) | GPL-3.0 全文（LGPL-3.0 是它的附加许可，必须一并提供） |

两者都会被打进模组 jar（文件名带 `_caldera` 后缀，避免多模组环境下与其他模组的许可证文件冲突）。

简言之：**你可以自由使用、修改、分发本模组，包括用于闭源项目；但若你修改了 Caldera 本身的代码并分发，
修改部分必须以 LGPL-3.0 开源。** 详情以两份文本为准。

注意：本许可证只覆盖 **Caldera 自己的代码**。Minecraft 的代码与资源归 Mojang 所有，
Sodium 归其作者所有（同为 LGPL-3.0），均不在本项目授权范围内。
