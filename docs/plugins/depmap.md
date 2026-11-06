# Depmap 依赖盘点 / Depmap dependency inventory

[中文](#中文) | [English](#english)

## 中文

### 用途与边界

独立的 `depmap` CLI 使用内嵌 Maven 3.9.14 的 Java API，将 `dependency:tree` 的项目依赖信息与 `dependency:resolve-plugins -DexcludeTransitive=false` 的插件依赖信息按模块组织成依赖树，而不是拼接 Maven 日志。不启动 `mvn` 或其他外部进程，不执行目标项目的编译、测试、插件目标或生命周期阶段。

默认递归当前配置下的全部子模块，解析父 POM、依赖管理、导入 BOM、属性和激活的 profile。项目树包含 Maven 规则保留的所有 scope，使用冲突仲裁后的版本，不显示因冲突或重复而省略的节点；项目树只需要依赖 POM 元数据，不要求先生成或安装 reactor 内部模块的 JAR。插件树包含直接和传递依赖，使用各自的版本仲裁，不与项目依赖树混合。

插件包括有效模型中声明或继承的构建插件、内嵌 Maven 默认生命周期绑定的插件，以及显式配置的报告插件；仅存在于 `pluginManagement`、没有被引用或绑定的插件不单独扫描。`pluginManagement` 中的配置和 `<plugin><dependencies>` 的依赖追加、覆盖会应用于构建插件。构建插件和报告插件分别列出，避免混淆同一插件的不同版本或依赖配置。不会执行站点插件来发现动态选择的报告。

这不是 Maven Wrapper，也不承诺与任意 Maven 版本的字节级日志或默认插件版本完全一致。Maven 4 专有模型、自定义 packaging 和构建扩展不属于支持范围。不会加载 `.mvn/extensions.xml`、`build.extensions` 或 `extensions=true` 插件的扩展代码；检测到扩展会标记结果不完整。`.mvn/maven.config`、`.mvn/jvm.config`、Wrapper 和环境中的 `MAVEN_ARGS` 不自动应用，需要通过 CLI 显式传入所需配置。

### 构建与运行

构建需要 JDK 25+ 和 Maven。在仓库根目录通过 Git Bash 构建：

```bash
bash build.sh depmap
```

运行只需要 Java 25+，不要求安装 Maven 或 Bash。以下为 PowerShell 示例：

```powershell
java -jar devpulse-plugins\depmap\target\devpulse-depmap.jar --help
java -jar devpulse-plugins\depmap\target\devpulse-depmap.jar D:\workspace\my-project
java -jar devpulse-plugins\depmap\target\devpulse-depmap.jar D:\workspace\my-project\pom.xml -N
java -jar devpulse-plugins\depmap\target\devpulse-depmap.jar D:\workspace\my-project --json dependency-report.json
```

省略项目路径时分析当前目录。JSON 目标必须是现有目录下尚不存在的文件，不覆盖已有文件；同时保留终端依赖树。此工具已接入根 reactor、内部 BOM 和 `build.sh depmap`，暂不加入组合入口 `devpulse-cli`、发行压缩包或安装脚本。

### Maven 配置

| 选项 | 行为 |
|------|------|
| `-N`, `--non-recursive` | 只分析指定模块，默认递归已激活的子模块 |
| `-s`, `--settings FILE` | 指定用户 settings；默认读取 `~/.m2/settings.xml`（存在时） |
| `--global-settings FILE` | 显式合并全局 settings；不自动查找 Maven 安装目录 |
| `--local-repository DIR` | 覆盖本地仓库，优先于 `-Dmaven.repo.local` 和 settings |
| `-P`, `--profiles ID[,ID...]` | 激活 profile，可重复；使用 `!ID` 显式停用 |
| `-DKEY=VALUE` | Maven 用户属性，可重复；省略值等同于 `true` |
| `-o`, `--offline` | 只使用本地仓库；settings 中的离线配置同样生效 |
| `--json FILE` | 额外保存 UTF-8 JSON |

```powershell
java -jar devpulse-plugins\depmap\target\devpulse-depmap.jar D:\workspace\my-project `
  -s D:\config\settings.xml --local-repository D:\cache\maven `
  -Pprod,!local -Dtarget.version=1.2.3 --json dependency-report.json
```

使用 Maven 的镜像、代理、私服认证和 settings 安全文件解密机制。凭据、settings 内容、环境变量及用户属性不直接写入报告，底层异常原文也不会输出；但它们参与模型插值，可能影响最终坐标和路径，因此报告仍应先审核再共享。JDK 或第三方库可能向标准错误输出自身的运行时警告。

联网模式可能下载父 POM、BOM、依赖元数据和插件 JAR 及其依赖，写入本地 Maven 仓库；这不是完全无副作用的文件读取。项目依赖收集不下载项目依赖 JAR，插件解析需要插件及依赖 JAR。不存在的或无效的依赖 POM 会明确标记为失败，不悄悄把无法确认的传递依赖当作空列表。

### 输出与错误

终端按模块分别输出 `Project dependencies` 和 `Plugin dependencies`，插件标记来源 `build`、`lifecycle` 或 `report`。树节点包含坐标、scope 和 optional 标记，插件根节点通常没有 scope。

JSON `schemaVersion` 为 `1`，顶层包含 `mavenVersion`、入口 `project`、整体 `complete`、`modules` 和 `issues`。每个模块包含 POM 路径、`artifact` 坐标、`complete`、`projectDependencies`、`plugins` 和 `issues`。项目依赖和插件依赖均使用 `{complete, root, issues}`；节点使用 `{artifact, scope, optional, dependencies}`，坐标使用 `{groupId, artifactId, type, classifier, version}`。报告不进行全局去重，保留依赖路径和插件归属。

部分解析失败时继续处理可用模块和其他插件，保留已经收集的树，并在终端和 JSON 中标记不完整。`root: null` 或 `[UNAVAILABLE]` 表示无法获取，不能视为“没有依赖”；问题包含阶段、目标和不含底层异常原文的错误说明。模型无效或父 POM/BOM 不可用时，受影响模块可能没有可用依赖树。

| 退出码 | 含义 |
|--------|------|
| `0` | 盘点完成，或正常输出帮助、版本 |
| `1` | 解析或初始化失败、结果不完整，或 JSON 写入失败 |
| `2` | CLI 参数、输入路径或 JSON 目标无效 |

## English

### Purpose and boundaries

The standalone `depmap` CLI uses embedded Maven 3.9.14 Java APIs to organize project dependency information from `dependency:tree` and plugin dependency information from `dependency:resolve-plugins -DexcludeTransitive=false` into per-module trees, rather than concatenating Maven logs. It does not start `mvn` or other external processes, or execute the target project's compilation, tests, plugin goals, or lifecycle phases.

By default, it recursively analyzes all modules active in the current configuration, resolving parent POMs, dependency management, imported BOMs, properties, and active profiles. Project trees include all scopes retained by Maven rules, with conflict-mediated versions and without conflict/duplicate-omitted nodes. Project trees only require dependency POM metadata; reactor module JARs need not be built or installed first. Plugin trees include direct and transitive dependencies, with separate version mediation rather than mixing them with project dependencies.

Plugins include declared or inherited build plugins in the effective model, embedded Maven's default lifecycle bindings, and explicitly configured report plugins. Unreferenced, unbound entries in `pluginManagement` are not independently scanned. Managed configuration and additions or overrides in `<plugin><dependencies>` apply to build plugins. Build and report plugins are listed separately to avoid conflating different versions or dependency configurations of the same plugin. Site plugins are not executed to discover dynamically selected reports.

This is not Maven Wrapper and does not promise byte-for-byte logs or identical default plugin versions across arbitrary Maven versions. Maven 4-specific models, custom packaging, and build extensions are outside the supported scope. Extension code from `.mvn/extensions.xml`, `build.extensions`, or plugins with `extensions=true` is not loaded; detected extensions mark the result incomplete. `.mvn/maven.config`, `.mvn/jvm.config`, Wrapper, and `MAVEN_ARGS` are not automatically applied; pass the required configuration explicitly through CLI options.

### Build and run

Building requires JDK 25+ and Maven. Build from the repository root using Git Bash:

```bash
bash build.sh depmap
```

Running only requires Java 25+, without Maven or Bash installed. These examples use PowerShell:

```powershell
java -jar devpulse-plugins\depmap\target\devpulse-depmap.jar --help
java -jar devpulse-plugins\depmap\target\devpulse-depmap.jar D:\workspace\my-project
java -jar devpulse-plugins\depmap\target\devpulse-depmap.jar D:\workspace\my-project\pom.xml -N
java -jar devpulse-plugins\depmap\target\devpulse-depmap.jar D:\workspace\my-project --json dependency-report.json
```

Omit the project path to analyze the current directory. The JSON destination must be a new file in an existing directory; existing files are not overwritten. Terminal trees are still printed. The tool is registered in the root reactor, internal BOM, and `build.sh depmap`, but not in the combined `devpulse-cli`, distribution archives, or installer.

### Maven configuration

| Option | Behavior |
|--------|----------|
| `-N`, `--non-recursive` | Analyze only the specified module; recurse into active modules by default |
| `-s`, `--settings FILE` | User settings; defaults to `~/.m2/settings.xml` when present |
| `--global-settings FILE` | Explicitly merge global settings; do not discover a Maven installation |
| `--local-repository DIR` | Override the local repository, ahead of `-Dmaven.repo.local` and settings |
| `-P`, `--profiles ID[,ID...]` | Activate profiles; repeat as needed, or use `!ID` to deactivate |
| `-DKEY=VALUE` | Maven user property; repeat as needed; an omitted value means `true` |
| `-o`, `--offline` | Only use the local repository; offline mode in settings also applies |
| `--json FILE` | Additionally save UTF-8 JSON |

```powershell
java -jar devpulse-plugins\depmap\target\devpulse-depmap.jar D:\workspace\my-project `
  -s D:\config\settings.xml --local-repository D:\cache\maven `
  -Pprod,!local -Dtarget.version=1.2.3 --json dependency-report.json
```

Maven handles mirrors, proxies, private repository authentication, and credential decryption using its settings security file. Credentials, settings contents, environment variables, and user properties are not directly included in reports, and raw underlying exception messages are not emitted. However, these inputs participate in model interpolation and may affect resolved coordinates and paths; review reports before sharing. The JDK or third-party libraries may emit their own runtime warnings on standard error.

Online mode may download parent POMs, BOMs, dependency metadata, plugin JARs, and plugin dependency JARs into the local Maven repository; analysis is not a completely side-effect-free file read. Project dependency collection does not download project dependency JARs, while plugin resolution requires plugin and dependency JARs. Missing or invalid dependency POMs are explicitly treated as failures, rather than silently presenting unknown transitive dependencies as empty.

### Output and errors

Terminal output separates `Project dependencies` and `Plugin dependencies` for each module, tagging plugins with `build`, `lifecycle`, or `report` origins. Tree nodes include coordinates, scope, and optional markers; plugin roots normally have no scope.

JSON uses `schemaVersion: 1`, with top-level `mavenVersion`, entry `project`, overall `complete`, `modules`, and `issues`. Each module has a POM path, `artifact` coordinates, `complete`, `projectDependencies`, `plugins`, and `issues`. Both project and plugin dependencies use `{complete, root, issues}`. Nodes use `{artifact, scope, optional, dependencies}`, and coordinates use `{groupId, artifactId, type, classifier, version}`. Reports are not globally deduplicated, preserving dependency paths and plugin ownership.

On partial resolution failures, processing continues for available modules and other plugins, retaining collected trees and marking terminal and JSON output incomplete. `root: null` or `[UNAVAILABLE]` means unavailable, not “no dependencies.” Issues include the stage, target, and an explanation without raw underlying exception messages. Invalid models or unavailable parents/BOMs may prevent dependency trees for affected modules.

| Exit code | Meaning |
|-----------|---------|
| `0` | Inventory complete, or normal help/version output |
| `1` | Resolution/initialization failure, incomplete results, or JSON write failure |
| `2` | Invalid CLI arguments, input path, or JSON destination |
