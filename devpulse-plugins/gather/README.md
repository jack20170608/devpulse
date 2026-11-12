# Gather 使用说明 / Gather usage

[中文](#中文) | [English](#english)

## 中文

[项目首页](../../readme.MD) | [架构设计](../../docs/devpulse-plugins/gather/architecture.md)

### 环境与构建

开发、构建和运行需要 Java 25+；构建使用 Maven 和 Bash（Windows 使用 Git Bash）。盘点需要 Bash；帮助与版本输出不需要 Bash。以下示例从仓库根目录运行：

```powershell
bash build.sh gather
java -jar devpulse-plugins\gather\target\devpulse-gather.jar --help
```

Gather 有独立可执行 JAR，但暂不提供安装归档。提示词审核、配置保存及执行流程见 [Pulse 使用说明](../pulse/README.md)。

环境检测由 [core 环境包](../../devpulse-core/README.md#环境事实采集) 提供，与 Pulse 共享 `EnvironmentGatherer`，不再单独依赖 gatherer 模块；本模块保留提示词生成与 CLI，检测行为和命令选项不变。

### `gather`：生成辅助编写配置的 AI 提示词

**适用场景**：你希望 AI 根据本机可用工具和项目类型，辅助编写 `.devpulse.yaml`，而不是从空白开始。

`gather` 收集以下事实，按所选语言组装 Markdown 提示词并输出到终端，默认使用中英文对照并复制到剪贴板：

- 项目的绝对路径、操作系统、架构、当前 Java 版本和 Bash 路径。
- 固定工具清单的查找结果：`git`、`docker`、`kubectl`、`helm`、`node`、`npm`、`pnpm`、`yarn`、`java`、`mvn`、`gradle`、`python`、`python3`、`go`、`cargo`、`make`、`terraform`。通过 Bash 的 `command -v` 记录位置，未找到的工具标为缺失。
- 项目根目录中的标志是否存在，例如 `.git`、`package.json`、`pom.xml`、Gradle 构建文件、`Dockerfile`、Compose 配置、Python 依赖文件、`go.mod`、`Cargo.toml`、`Makefile` 和 `.devpulse.yaml`。

提示词包含 YAML 示例、任务依赖要求、Bash 执行约束和命令审核提醒，要求 AI 不捏造项目脚本、信息不足时说明假设，并避免默认推荐安装、删除、部署或推送等操作。

**能力边界**：不读取项目文件内容、不递归扫描、不采集环境变量值，也不执行工具版本或健康检查。找到工具不代表工具配置正确或服务可用。`gather` 不会自动调用 AI、生成或写入 `.devpulse.yaml`，也不会执行项目任务。

| 参数 | 作用 |
|------|------|
| `--directory <目录>` | 指定盘点的项目目录，默认为当前目录；目录必须已经存在 |
| `--bash <路径>` | 显式指定 Bash 可执行文件；Windows 应指定 Git Bash |
| `--language zh\|en\|both` | 选择终端及剪贴板中的提示词语言，默认 `both` |
| `--no-clipboard` | 仅输出提示词，不修改剪贴板 |

```powershell
# 盘点当前目录，输出提示词并尝试复制到剪贴板
java -jar devpulse-plugins\gather\target\devpulse-gather.jar

# 盘点指定项目，不修改剪贴板
java -jar devpulse-plugins\gather\target\devpulse-gather.jar --directory "D:\your-project" --no-clipboard

# 显式指定 Git Bash
java -jar devpulse-plugins\gather\target\devpulse-gather.jar --bash "C:\Program Files\Git\bin\bash.exe"

# 中文显示乱码时，仅输出英文提示词
java -jar devpulse-plugins\gather\target\devpulse-gather.jar --language en --no-clipboard
```

成功时返回 `0`。剪贴板不可用时会输出警告，提示词仍保留在终端，不影响盘点成功。目录无效、找不到可用 Bash、扫描失败或被中断时返回 `1`。

提示词包含本地项目路径和工具位置，发送给外部 AI 前应先审核。由于未读取项目文件，通常还需补充实际脚本和命令；AI 生成的配置也必须人工审核，不能直接视为可靠的执行结果。Java 检测到非 UTF-8 控制台或标准输出编码时会给出警告；该检测不能确认终端实际显示编码，`--language en` 也不会修复终端编码。

## English

[Project home](../../readme.MD) | [Architecture](../../docs/devpulse-plugins/gather/architecture.md)

### Requirements and build

Development, builds, and runtime require Java 25+; building uses Maven and Bash (Git Bash on Windows). Inventory requires Bash, while help and version output do not. Run these examples from the repository root:

```powershell
bash build.sh gather
java -jar devpulse-plugins\gather\target\devpulse-gather.jar --help
```

Gather has a standalone executable JAR but no installation archive yet. See the [Pulse guide](../pulse/README.md) for prompt review, configuration saving, and execution.

The [core environment package](../../devpulse-core/README.md#environment-fact-collection) supplies `EnvironmentGatherer`, shared with Pulse without a separate gatherer module dependency. This module retains prompt generation and the CLI, with unchanged detection behavior and command options.

### `gather`: generate an AI prompt for configuration assistance

**When to use it**: You want AI to help write `.devpulse.yaml` based on locally available tools and project markers rather than starting from scratch.

`gather` collects the following facts, formats them as a Markdown prompt in the selected language, and prints it to the terminal. By default, the prompt is bilingual Chinese/English and is also copied to the clipboard:

- The absolute project path, operating system, architecture, current Java version, and Bash path.
- Lookup results for a fixed tool list: `git`, `docker`, `kubectl`, `helm`, `node`, `npm`, `pnpm`, `yarn`, `java`, `mvn`, `gradle`, `python`, `python3`, `go`, `cargo`, `make`, and `terraform`. Bash's `command -v` records their locations; tools that cannot be found are marked as missing.
- Whether project markers exist in the project root, such as `.git`, `package.json`, `pom.xml`, Gradle build files, `Dockerfile`, Compose configurations, Python dependency files, `go.mod`, `Cargo.toml`, `Makefile`, and `.devpulse.yaml`.

The prompt includes a YAML example, task dependency requirements, Bash execution constraints, and command review reminders. It asks AI not to invent project scripts, to state assumptions when information is insufficient, and to avoid recommending installation, deletion, deployment, or pushing by default.

**Limitations**: It does not read project file contents, scan recursively, collect environment variable values, or run tool version or health checks. Finding a tool does not establish correct configuration or service availability. `gather` does not automatically call AI, generate or write `.devpulse.yaml`, or execute project tasks.

| Option | Effect |
|--------|--------|
| `--directory <directory>` | Select the project to inspect; defaults to the current directory, which must already exist |
| `--bash <path>` | Explicitly select the Bash executable; on Windows, select Git Bash |
| `--language zh\|en\|both` | Select the terminal and clipboard prompt language; defaults to `both` |
| `--no-clipboard` | Print the prompt without changing the clipboard |

```powershell
# Inspect the current directory, print the prompt, and try to copy it to the clipboard
java -jar devpulse-plugins\gather\target\devpulse-gather.jar

# Inspect a selected project without changing the clipboard
java -jar devpulse-plugins\gather\target\devpulse-gather.jar --directory "D:\your-project" --no-clipboard

# Explicitly select Git Bash
java -jar devpulse-plugins\gather\target\devpulse-gather.jar --bash "C:\Program Files\Git\bin\bash.exe"

# Print only the English prompt if Chinese text appears garbled
java -jar devpulse-plugins\gather\target\devpulse-gather.jar --language en --no-clipboard
```

Success returns `0`. If the clipboard is unavailable, the command prints a warning and leaves the prompt in the terminal; the inventory still succeeds. An invalid directory, missing working Bash, failed scan, or interruption returns `1`.

The prompt contains local project paths and tool locations; review it before sharing with external AI. Since project files are not read, you will usually need to supply actual scripts and commands as additional context. AI-generated configuration also requires human review and must not be assumed reliable for execution. A warning is printed if Java detects non-UTF-8 console or standard output encoding; this cannot verify the terminal's actual display encoding, and `--language en` does not fix terminal encoding.
