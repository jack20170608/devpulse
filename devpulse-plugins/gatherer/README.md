# Gatherer 使用说明 / Gatherer usage

[中文](#中文) | [English](#english)

## 中文

[项目首页](../../readme.MD) | [架构设计](../../docs/devpulse-plugins/gatherer/architecture.md)

### 范围与构建

`gatherer` 是环境检测库，不注册 CLI，不生成可执行工具 JAR。`EnvironmentGatherer` 从 `devpulse-core` 移至 `io.github.devpulse.gatherer`，现有 [Gather CLI](../gather/README.md) 与 Pulse 的 `status --tools` 复用它；提示词、剪贴板和终端输出仍由调用方处理。

开发、构建和运行需要 Java 25+，实际检测需要 Bash（Windows 使用 Git Bash）。复用 core 的 `BashShell`，不新增外部依赖。从仓库根目录构建：

```powershell
bash build.sh gatherer
```

产物为 `devpulse-plugins\gatherer\target\devpulse-gatherer.jar`。通过根 reactor 的 `--also-make` 构建依赖，`build.sh` 已包含该逻辑。

### 环境检测

`new EnvironmentGatherer().gather(directory, bashOverride)` 返回 `Facts` record：项目绝对路径、OS、架构、Java 版本、Bash、工具位置和存在的项目标志。`bashOverride` 为 `null` 时自动查找 Bash；目录必须已经存在。`Facts.tools()` 与 `Facts.projectFiles()` 使用不可修改的列表。

工具清单保持原样：`git`、`docker`、`kubectl`、`helm`、`node`、`npm`、`pnpm`、`yarn`、`java`、`mvn`、`gradle`、`python`、`python3`、`go`、`cargo`、`make`、`terraform`。通过 Bash 的 `command -v` 获取位置，缺失工具的 `location()` 为空字符串。

项目标志保持原样：`.git`、`package.json`、`pom.xml`、`build.gradle`、`build.gradle.kts`、`Dockerfile`、`compose.yaml`、`compose.yml`、`docker-compose.yaml`、`docker-compose.yml`、`pyproject.toml`、`requirements.txt`、`go.mod`、`Cargo.toml`、`Makefile`、`.devpulse.yaml`。

```java
import io.github.devpulse.gatherer.EnvironmentGatherer;
import java.nio.file.Path;

public class GathererExample {
    public static void main(String[] args) throws Exception {
        var facts = new EnvironmentGatherer().gather(Path.of("."), null);
        System.out.println(facts.os());
        System.out.println(facts.projectFiles());
        facts.tools().forEach(tool ->
                System.out.println(tool.name() + ": " + tool.location()));
    }
}
```

保存为 `GathererExample.java` 后从仓库根目录运行：

```powershell
java --class-path "devpulse-plugins\gatherer\target\classes;devpulse-core\target\classes" GathererExample.java
```

### 采集边界

本次仅移植，保持工具清单、项目标志、采集范围和异常语义不变，后续环境检测调整集中在该模块。不读取项目内容、不递归扫描、不采集环境变量值，不执行工具版本或服务健康检查，也不调用 AI。工具存在不代表配置正确或服务可用。目录或 Bash 无效、扫描不完整时抛出 `IOException`；中断抛出 `InterruptedException`。结果包含本地路径，共享前需审核。

## English

[Project home](../../readme.MD) | [Architecture](../../docs/devpulse-plugins/gatherer/architecture.md)

### Scope and build

`gatherer` is an environment detection library with no CLI registration or executable tool JAR. `EnvironmentGatherer` moves from `devpulse-core` to `io.github.devpulse.gatherer`; the existing [Gather CLI](../gather/README.md) and Pulse's `status --tools` reuse it. Callers still own prompts, clipboard access, and terminal output.

Development, builds, and runtime require Java 25+; detection requires Bash (Git Bash on Windows). The library reuses core's `BashShell` without adding external dependencies. Build from the repository root:

```powershell
bash build.sh gatherer
```

The artifact is `devpulse-plugins\gatherer\target\devpulse-gatherer.jar`. Build dependencies through the root reactor with `--also-make`, which `build.sh` already handles.

### Environment detection

`new EnvironmentGatherer().gather(directory, bashOverride)` returns a `Facts` record containing the absolute project path, OS, architecture, Java version, Bash, tool locations, and existing project markers. A `null` `bashOverride` enables Bash discovery; the directory must already exist. `Facts.tools()` and `Facts.projectFiles()` are unmodifiable lists.

The tool list is unchanged: `git`, `docker`, `kubectl`, `helm`, `node`, `npm`, `pnpm`, `yarn`, `java`, `mvn`, `gradle`, `python`, `python3`, `go`, `cargo`, `make`, and `terraform`. Bash `command -v` supplies locations; missing tools have an empty `location()`.

Project markers are unchanged: `.git`, `package.json`, `pom.xml`, `build.gradle`, `build.gradle.kts`, `Dockerfile`, `compose.yaml`, `compose.yml`, `docker-compose.yaml`, `docker-compose.yml`, `pyproject.toml`, `requirements.txt`, `go.mod`, `Cargo.toml`, `Makefile`, and `.devpulse.yaml`.

```java
import io.github.devpulse.gatherer.EnvironmentGatherer;
import java.nio.file.Path;

public class GathererExample {
    public static void main(String[] args) throws Exception {
        var facts = new EnvironmentGatherer().gather(Path.of("."), null);
        System.out.println(facts.os());
        System.out.println(facts.projectFiles());
        facts.tools().forEach(tool ->
                System.out.println(tool.name() + ": " + tool.location()));
    }
}
```

Save as `GathererExample.java` and run from the repository root:

```powershell
java --class-path "devpulse-plugins\gatherer\target\classes;devpulse-core\target\classes" GathererExample.java
```

### Collection limits

This change only relocates existing functionality, preserving tool lists, project markers, collection scope, and exception semantics. Future detection changes belong in this module. It does not read project contents, scan recursively, collect environment variable values, run tool versions or service health checks, or call AI. Tool presence does not prove correct configuration or service availability. Invalid directories/Bash or incomplete scans throw `IOException`; interruption throws `InterruptedException`. Results contain local paths and should be reviewed before sharing.
