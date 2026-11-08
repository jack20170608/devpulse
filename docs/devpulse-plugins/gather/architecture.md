# Gather 架构设计 / Gather architecture

[中文](#中文) | [English](#english)

## 中文

[架构导航](../../architecture.md) | [使用说明](../../../devpulse-plugins/gather/README.md)

### 资产自动盘点模块（`GatherCommand.java`）

**职责**：收集本地系统的客观环境事实，按所选语言组装 AI 提示词（默认中英文对照），输出到终端并默认复制到剪贴板。

**实现步骤**：

- **环境与工具盘点**：记录 OS、架构、Java 版本、Bash 路径，通过 Bash 的 `command -v` 检查固定工具清单，不执行工具版本或健康检查。
- **项目特征嗅探**：仅检查项目根目录中关键指纹是否存在（如 `package.json`、`pom.xml`、`Dockerfile`、`.git`），不读取文件内容、不递归扫描目录。
- **采集边界**：环境变量仅用于 Bash 定位及工具查找，不将环境变量值或凭据加入提示词。提示词包含项目路径和工具位置，用户发送给外部 AI 前应审核。
- **提示词输出**：生成包含本地事实、YAML 示例、Bash 执行约束和命令审核提醒的 Markdown；支持中文、英文或中英双语，双语模式保留同页面语言导航，单语言模式仅包含对应语言章节，不显示双语导航。本地事实中的路径、工具名等数据及 YAML 示例保持原样；信息不足时要求 AI 声明假设，不捏造任务。
- **编码警告**：提示词输出前检查 Java 控制台的 `Console.charset()`；无控制台时检查 `System.out.charset()`。检测到非 UTF-8 时向标准错误输出英文警告，包含检测到的编码和 Git Bash 的 `Options > Text > Character set > UTF-8` 设置提示，不改变语言、输出编码、剪贴板内容或成功退出码。该检查反映 Java 检测到的编码，不能可靠读取 Git Bash 窗口的实际显示字符集；重定向输出时也可能触发，不代表已经确认发生乱码。
- **剪贴板控制**：默认通过 AWT 写入剪贴板；无图形环境、剪贴板忙或不可访问时提示警告，保留标准输出，盘点仍成功。
- **命令选项**：`gather --directory <目录>` 指定盘点位置，`--bash <路径>` 指定 Bash，`--no-clipboard` 禁用剪贴板复制；`--language zh|en|both` 同时控制终端与剪贴板中的提示词语言，默认 `both`，不支持的取值在盘点前拒绝。目录无效、Bash 缺失或扫描失败时返回非零退出码。遇到中文终端显示乱码时可使用 `gather --language en` 避开提示词中的中文；该选项不修复终端编码，也不翻译本地事实数据。

环境事实采集复用 [gatherer 检测库](../gatherer/architecture.md)，该库通过 core 的 `BashShell` 定位与调用 Bash；`GatherPrompt` 负责语言模板，`GatherCommand` 负责 CLI 选项、编码提示和剪贴板输出。Gather 不依赖 Pulse；生成的 YAML 由用户审核后交给 Pulse。

### 原有实施路线图

- 实现可复用的 Bash 探测与调用组件。
- 实现工具查找、项目指纹检测和中英文提示词生成。
- 实现 AWT 剪贴板写入及无图形环境降级，支持仅输出提示词。

## English

[Architecture index](../../architecture.md) | [Usage](../../../devpulse-plugins/gather/README.md)

### Environment inventory (`GatherCommand.java`)

**Responsibility**: Collect local environment facts, generate an AI prompt in the selected language (Chinese/English by default), print it, and copy it to the clipboard by default.

**Implementation steps**:

- **Environment and tool inventory**: Record OS, architecture, Java version, and Bash location. Check a fixed tool list using Bash `command -v`; do not execute version commands or health checks.
- **Project detection**: Check only the presence of root-level markers such as `package.json`, `pom.xml`, `Dockerfile`, and `.git`. Do not read file contents or recursively scan directories.
- **Collection boundaries**: Use environment variables only for Bash discovery and tool lookup; do not include their values or credentials in the prompt. Prompts contain project and tool locations, which users must review before sharing with external AI services.
- **Prompt output**: Generate Markdown containing facts, a YAML example, Bash constraints, and command-review reminders. Support Chinese, English, or both. Bilingual mode retains same-page language navigation; single-language modes include only the corresponding language section without bilingual navigation. Keep local fact data such as paths and tool names, and the YAML example, unchanged. Require AI to state assumptions instead of inventing tasks when facts are insufficient.
- **Encoding warning**: Before printing the prompt, check Java's `Console.charset()`, or `System.out.charset()` when no console is available. If the detected encoding is not UTF-8, print an English warning to standard error with the detected encoding and Git Bash's `Options > Text > Character set > UTF-8` setting. Do not change the language, output encoding, clipboard content, or successful exit code. This check reflects Java's detected encoding and cannot reliably read the Git Bash window's actual display character set. It may also warn when output is redirected and does not establish that garbling has occurred.
- **Clipboard**: Copy through AWT by default. In headless environments or when the clipboard is busy or inaccessible, warn and retain standard output; inventory still succeeds.
- **Options**: `gather --directory <directory>` selects the project, `--bash <path>` selects Bash, and `--no-clipboard` disables clipboard writes. `--language zh|en|both` controls the prompt language for both terminal and clipboard output, defaults to `both`, and rejects unsupported values before inventory. Invalid directories, missing Bash, and scan failures return nonzero exit codes. Use `gather --language en` to avoid Chinese prompt text when the terminal displays it incorrectly; this option does not fix terminal encoding or translate local fact data.

Fact collection reuses the [gatherer detection library](../gatherer/architecture.md), which discovers and invokes Bash through core's `BashShell`. `GatherPrompt` owns language templates; `GatherCommand` owns CLI options, encoding warnings, and clipboard output. Gather does not depend on Pulse; users review generated YAML before using Pulse.

### Original implementation roadmap

- Implement reusable Bash discovery and invocation.
- Implement tool lookup, project marker detection, and bilingual prompt generation.
- Implement AWT clipboard writes with headless fallback and a print-only option.
