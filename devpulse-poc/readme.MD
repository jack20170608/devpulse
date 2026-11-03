# Markdown 模板实验 / Markdown template experiment

[中文](#中文) | [English](#english)

## 中文

### 范围

在 `devpulse-poc` 中验证本地模板渲染：模板路径 + 参数 → 新 Markdown 文件。使用 FreeMarker 支持变量、条件和循环，不连接 Jira，也不修改现有 `issue template`、`create --template` 或 `edit --template` 的行为。

### 构建和运行

需要 JDK 25+ 和 Maven。在仓库根目录执行以下 PowerShell 命令：

```powershell
mvn --batch-mode --no-transfer-progress --projects :devpulse-poc --also-make package
java -jar devpulse-poc\target\devpulse-poc.jar --help
java -jar devpulse-poc\target\devpulse-poc.jar `
  --template devpulse-poc\examples\issue.md.ftl `
  --params devpulse-poc\examples\issue-params.conf `
  --param "title=Login failure" `
  --param "includeAcceptance=false" `
  --output issue.md
```

输出为 UTF-8 `issue.md`。示例用命令行覆盖标题和验收标准开关；删除 `includeAcceptance` 的覆盖参数即可生成验收标准。生成后可人工检查，并作为现有 Jira 创建命令的 Markdown 描述文件使用；本实验不会提交问题。

### 参数和模板

| 用法 | 说明 |
|------|------|
| `--template FILE` | 必填，读取 UTF-8 FreeMarker 模板 |
| `--params FILE` | 可选，读取 HOCON 参数文件，支持嵌套对象、列表、数字、布尔值 |
| `--param KEY=HOCON` / `-D KEY=HOCON` | 可重复，覆盖文件参数；同一个 key 最后一次指定生效，支持 `issue.title` 等路径 |
| `--output FILE` | 必填，写入新文件；父目录必须存在，不覆盖已有文件 |

命令行值按 HOCON 解析，而非全部视为字符串：`enabled=false` 是布尔值，`count=3` 是数字。包含 HOCON 特殊字符的字符串需遵循 HOCON 引号规则，同时注意终端的引号转义。合并后的参数才解析 `${...}` 引用，不从系统属性或环境变量自动补值。参数文件使用标准 HOCON include 规则，只使用可信的参数文件。

模板语法使用 FreeMarker，而非 `{{...}}`：`${title}` 引用变量，`<#if includeAcceptance>...</#if>` 表达条件，`<#list steps as step>...</#list>` 遍历列表。Markdown 文本不做 HTML 转义，也不自动转义用户传入的 Markdown。可参考 `examples` 中的模板及参数文件。

### 失败和限制

缺少变量、参数格式错误或模板语法错误时返回非零退出码，不创建输出文件；需要可选变量时在模板中显式使用 FreeMarker 的默认值或存在性检查。输出文件已存在时拒绝覆盖。模板先完整渲染到内存，再写入文件，适合小型 issue 文档。

本实验只渲染单个模板，不配置模板加载器，因此不支持 `include` / `import`、模板仓库、版本管理或自动翻译。禁止模板中的 `?new` 和 `?api`，但这不构成执行沙箱；只使用可信模板。

## English

### Scope

Validate local rendering in `devpulse-poc`: template path + parameters → a new Markdown file. FreeMarker provides variables, conditions, and loops. This does not connect to Jira or change existing `issue template`, `create --template`, or `edit --template` behavior.

### Build and run

Require JDK 25+ and Maven. Run these PowerShell commands from the repository root:

```powershell
mvn --batch-mode --no-transfer-progress --projects :devpulse-poc --also-make package
java -jar devpulse-poc\target\devpulse-poc.jar --help
java -jar devpulse-poc\target\devpulse-poc.jar `
  --template devpulse-poc\examples\issue.md.ftl `
  --params devpulse-poc\examples\issue-params.conf `
  --param "title=Login failure" `
  --param "includeAcceptance=false" `
  --output issue.md
```

The output is UTF-8 `issue.md`. The example overrides the title and acceptance-criteria switch on the command line; remove the `includeAcceptance` override to generate acceptance criteria. Review the result manually before using it as a Markdown description file with the existing Jira create command; this experiment does not submit issues.

### Parameters and templates

| Usage | Description |
|-------|-------------|
| `--template FILE` | Required; read a UTF-8 FreeMarker template |
| `--params FILE` | Optional HOCON parameter file supporting nested objects, lists, numbers, and booleans |
| `--param KEY=HOCON` / `-D KEY=HOCON` | Repeat to override file parameters; the last value for the same key wins; paths such as `issue.title` are supported |
| `--output FILE` | Required; write a new file; the parent directory must exist and existing files are never overwritten |

Command-line values are parsed as HOCON, not all treated as strings: `enabled=false` is a boolean and `count=3` is a number. Strings containing HOCON special characters require HOCON quoting as well as appropriate shell escaping. `${...}` references resolve after merging parameters, without automatic system-property or environment-variable fallback. Parameter files follow standard HOCON include rules; use trusted parameter files only.

Templates use FreeMarker syntax, not `{{...}}`: `${title}` references a variable, `<#if includeAcceptance>...</#if>` expresses a condition, and `<#list steps as step>...</#list>` iterates a list. Markdown text is not HTML-escaped, and user-supplied Markdown is not automatically escaped. See the template and parameter file under `examples`.

### Failures and limitations

Missing variables, invalid parameters, or invalid template syntax produce a nonzero exit code without creating an output file. Use explicit FreeMarker defaults or existence checks for optional variables. Existing output files are never overwritten. Rendering completes in memory before writing, making this suitable for small issue documents.

This experiment renders a single template without a template loader, so it does not support `include` / `import`, a template registry, version management, or automatic translation. Template `?new` and `?api` are disabled, but this is not an execution sandbox; use trusted templates only.
