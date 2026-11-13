# devpulse-dist 发行与安装 / devpulse-dist distribution and installation

[中文](#中文) | [English](#english)

## 中文

[项目首页](../readme.MD)

### 快速安装

在仓库根目录先运行 `bash build.sh dist`，然后进入 `devpulse-dist`：

```bash
cd devpulse-dist
./install.sh jira
# 或安装 Pulse
./install.sh pulse
export PATH="$HOME/.local/bin:$PATH"
jira --help
```

默认从安装脚本旁的 `target` 目录安装，无需 `--local`、`--tool` 或 `--base-url`；存在多个版本时自动安装版本号最高的归档，也可使用 `./install.sh jira --version 0.1.2` 明确选择。不自动构建或下载；在 Windows 上使用 Git Bash，也可执行 `bash install.sh jira`。默认安装到 `$HOME/.local`，仍会校验归档和 JAR，且不覆盖非受管文件。

### 工具边界

`pulse` 包含任务运行以及 `init`、`review`、`status`、`stop` 子命令；`jira` 是独立工具。`gather` 也有自己的可执行 JAR，但目前不发布安装归档。`devpulse-cli/target/devpulse.jar` 保留旧的 `devpulse <子命令>` 用法，不进入单工具发行包。选择安装 Jira 不会安装 Pulse；选择安装 Pulse 不会安装 Jira 或 Gather 命令。

### 构建和直接运行

在仓库根目录使用 JDK 25+ 和 Maven 构建。`bash build.sh dist` 默认执行 `clean package`，清理发行模块及其 reactor 依赖的旧构建产物，避免不同版本归档残留。`devpulse-dist/target/` 生成各工具独立的 `devpulse-pulse-<版本>.tar.gz`、`devpulse-jira-<版本>.tar.gz` 及对应 `.sha256` 文件。每个归档只含对应工具的可执行 JAR、Bash 启动脚本、版本文件和 JAR 校验文件。

```bash
bash build.sh dist
tar -xzf devpulse-dist/target/devpulse-pulse-0.1.0-SNAPSHOT.tar.gz
bash devpulse-pulse-0.1.0-SNAPSHOT/bin/pulse --help
```

Jira 对应解压 `devpulse-jira-<版本>.tar.gz` 并运行 `bin/jira`。直接解压无需安装器或 Maven；运行需要 Java 25+，脚本需要 Bash（Windows 使用 Git Bash）。Pulse 实际执行任务、`status --tools` 需要 Bash；Jira JAR 本身不依赖 Bash。也可直接运行归档内的 `java -jar devpulse-pulse.jar` 或 `java -jar devpulse-jira.jar`。

### 按需安装、升级和卸载

从可信仓库取得 `devpulse-dist/install.sh`，不要直接执行未经审核的远程脚本。**正式发布渠道暂未配置，当前没有公开可下载的版本。**未来发布时先将根 `pom.xml` 的 `<revision>` 改为非 `SNAPSHOT` 版本，再上传两个归档及各自的 SHA-256 文件到同一可信地址。安装器需 `tar`、`sha256sum` 和 Bash；HTTPS 下载还需 `curl`。通过 `--base-url` 指定发行文件的 HTTPS 地址、`file://` URL 或普通本地目录，也可直接传入工具名，从默认本地目录安装。安装器校验所选归档及 JAR，不在终端用户机器上运行 Maven。默认前缀为 `$HOME/.local`，应把该目录下的 `bin` 加入 `PATH`。

```bash
RELEASE_URL="https://example.org/devpulse/releases/v0.1.0"
bash devpulse-dist/install.sh --tool pulse --version 0.1.0 --base-url "$RELEASE_URL"
bash devpulse-dist/install.sh --tool jira --version 0.1.0 --base-url "$RELEASE_URL" --prefix "$HOME/.local"
bash devpulse-dist/install.sh --uninstall --tool pulse
```

以上 URL 仅为占位符，需替换为实际发行地址；当前可使用本地构建的归档目录。安装另一个版本只切换所选工具；`--uninstall --tool jira` 只移除安装器管理的 Jira 文件。不会删除项目中的 `.devpulse` 运行历史或用户配置。校验文件与归档同源，传输校验不替代对发行来源的信任。安装器拒绝覆盖非自身管理的同名入口或安装目录。

### 安装本地构建的发行包

`devpulse-dist` 生成可单独安装的 Pulse 和 Jira 发行包，不是可执行命令，也不包含 Gather 或组合式 `devpulse` 入口。目前没有公开下载渠道，可先从源码构建，再安装本地发行包。

准备 JDK 25+、Maven、Bash、`tar` 和 `sha256sum`，在仓库根目录执行以下命令（Windows 使用 Git Bash）：

```bash
bash build.sh dist

# 按需安装；自动定位脚本旁的 target，并选择所选工具的最高版本
bash devpulse-dist/install.sh pulse
bash devpulse-dist/install.sh jira

export PATH="$HOME/.local/bin:$PATH"
pulse --help
jira --help
```

本地安装默认开启，原有 `--local --tool jira` 用法仍兼容。不自动构建；归档和校验文件应先生成在 `devpulse-dist/target/`。默认目录相对于安装脚本定位，与调用时的工作目录无关。未传入 `--version` 时使用 `LC_ALL=C sort -V` 选择最高版本，例如 `0.1.10` 高于 `0.1.9`，不按文件修改时间选择。这是自然版本排序，并非严格的 SemVer 优先级规则；预发布版本也参与排序。需要支持 `-V` 的 GNU 兼容 `sort`（Git Bash 自带；macOS 可安装 GNU coreutils，或显式传入 `--version` 跳过排序）。重复安装同版本时，在归档及 JAR 校验通过后直接覆盖安装器管理的 JAR 和启动入口，无需先卸载；校验失败保留原安装，不覆盖非受管版本目录或软链接。显式版本应与根 `pom.xml` 的 `<revision>` 一致。

本地源推荐直接传目录，无需手工拼接 `file://` 或调用 `cygpath`：

```bash
bash devpulse-dist/install.sh jira --base-url "./devpulse-dist/target"
bash devpulse-dist/install.sh --tool jira --version 0.1.0-SNAPSHOT \
  --base-url 'D:\release files\devpulse'
bash devpulse-dist/install.sh jira --base-url "/opt/devpulse/releases"
```

支持相对路径、POSIX 绝对路径、Git Bash 的 `/d/...` 和 Windows 的 `D:/...`、`D:\...`；Git Bash 与 WSL 会转换常见盘符路径，WSL 使用默认 `/mnt/<盘符>` 挂载布局。Linux/macOS 无法直接访问未挂载的 Windows 盘符，应传实际挂载路径。空格、中文、`#` 和字面 `%` 的普通目录路径无需 URL 编码，但必须正确加引号。保留 `file://` 支持，包括 `file:///D:/...`、`file:///d/...`、`file://localhost/...` 和 `%20` 等百分号编码；URL 中的字面 `%` 必须写为 `%25`。本地源直接复制文件，不经过 curl；目录、归档或校验文件缺失时给出具体路径。

安装脚本固定使用 LF 换行；安装时也兼容 Windows 构建归档中启动脚本及版本文件的 CRLF，不修改 JAR 内容或跳过 SHA-256 校验。

默认安装到 `$HOME/.local`，无需管理员权限；将上述 `export PATH=...` 加入 `~/.bashrc` 可供后续 Bash 会话使用。安装后直接执行 `pulse` 或 `jira`，不需要 Maven 或源码目录，但仍需 Java 25+ 和 Bash。

可通过 `--prefix` 自定义安装目录，并将其 `bin` 加入 `PATH`。卸载使用 `bash devpulse-dist/install.sh --uninstall --tool pulse` 或 `--tool jira`；自定义安装目录时需传入相同的 `--prefix`。

[架构设计](../docs/devpulse-dist/architecture.md)

## English

[Project home](../readme.MD)

### Quick installation

Run `bash build.sh dist` from the repository root first, then enter `devpulse-dist`:

```bash
cd devpulse-dist
./install.sh jira
# Or install Pulse
./install.sh pulse
export PATH="$HOME/.local/bin:$PATH"
jira --help
```

By default, install from `target` beside the installer without `--local`, `--tool`, or `--base-url`; when multiple versions exist, automatically install the highest-version archive, or select one explicitly with `./install.sh jira --version 0.1.2`. No automatic builds or downloads occur. On Windows, use Git Bash; `bash install.sh jira` also works. Installation defaults to `$HOME/.local`, still verifies the archive and JAR, and never overwrites unmanaged files.

### Tool boundaries

`pulse` runs tasks and includes the `init`, `review`, `status`, and `stop` subcommands; `jira` is independent. `gather` also has its own executable JAR but currently has no installation archive. `devpulse-cli/target/devpulse.jar` preserves the old `devpulse <subcommand>` syntax and is not included in individual tool archives. Installing Jira does not install Pulse; installing Pulse does not install Jira or the Gather command.

### Build and run without installation

Build from the repository root with JDK 25+ and Maven. `bash build.sh dist` defaults to `clean package`, removing previous build outputs from the distribution module and its reactor dependencies so archives from different versions do not linger. `devpulse-dist/target/` produces separate `devpulse-pulse-<version>.tar.gz` and `devpulse-jira-<version>.tar.gz` archives with adjacent `.sha256` files. Each archive contains only its tool's executable JAR, Bash launcher, version file, and JAR checksum.

```bash
bash build.sh dist
tar -xzf devpulse-dist/target/devpulse-pulse-0.1.0-SNAPSHOT.tar.gz
bash devpulse-pulse-0.1.0-SNAPSHOT/bin/pulse --help
```

For Jira, extract `devpulse-jira-<version>.tar.gz` and run `bin/jira`. Direct extraction needs neither the installer nor Maven; running requires Java 25+, and the launcher needs Bash (Git Bash on Windows). Pulse task execution and `status --tools` require Bash; the Jira JAR itself does not. You can also run `java -jar devpulse-pulse.jar` or `java -jar devpulse-jira.jar` directly inside the extracted archive.

### Selective installation, upgrades, and removal

Obtain `devpulse-dist/install.sh` from a trusted checkout; do not execute an unreviewed remote script directly. **No formal publishing channel is configured yet, so no publicly downloadable version exists.** For a future release, change the root `pom.xml`'s `<revision>` to a non-`SNAPSHOT` version and upload both archives and their SHA-256 files to the same trusted location. The installer needs `tar`, `sha256sum`, and Bash; HTTPS downloads additionally need `curl`. Pass `--base-url` with an HTTPS address, `file://` URL, or plain local directory, or simply supply the tool name to install from the default local directory. The installer checks the selected archive and JAR without using Maven on the target machine. The default prefix is `$HOME/.local`; add its `bin` directory to `PATH`.

```bash
RELEASE_URL="https://example.org/devpulse/releases/v0.1.0"
bash devpulse-dist/install.sh --tool pulse --version 0.1.0 --base-url "$RELEASE_URL"
bash devpulse-dist/install.sh --tool jira --version 0.1.0 --base-url "$RELEASE_URL" --prefix "$HOME/.local"
bash devpulse-dist/install.sh --uninstall --tool pulse
```

The URL above is a placeholder; replace it with an actual release location. For now, you can use the directory containing locally built archives. Installing another version switches only the selected tool; `--uninstall --tool jira` removes only installer-managed Jira files. It does not delete `.devpulse` run history or user configuration in a project. A checksum served from the same location verifies transfer integrity, not the authenticity of the release source. The installer refuses to overwrite a launcher or installation directory it does not manage.

### Install locally built release archives

`devpulse-dist` produces separately installable Pulse and Jira archives; it is not an executable command and does not include Gather or the combined `devpulse` entry point. No public download channel is available yet, so build from source and install the local archives.

Prepare JDK 25+, Maven, Bash, `tar`, and `sha256sum`, then run these commands from the repository root (use Git Bash on Windows):

```bash
bash build.sh dist

# Install as needed; find target beside the script and select the tool's highest version
bash devpulse-dist/install.sh pulse
bash devpulse-dist/install.sh jira

export PATH="$HOME/.local/bin:$PATH"
pulse --help
jira --help
```

Local installation is enabled by default; the original `--local --tool jira` usage remains compatible. No automatic builds occur; generate archives and checksum files in `devpulse-dist/target/` first. The default directory is resolved relative to the installer, independently of the caller's working directory. Without `--version`, use `LC_ALL=C sort -V` to select the highest version, so `0.1.10` sorts above `0.1.9`, independently of file modification times. This is natural version sorting, not strict SemVer precedence; prerelease versions also participate. A GNU-compatible `sort` supporting `-V` is required (included with Git Bash; on macOS, install GNU coreutils or pass an explicit `--version` to skip sorting). Reinstalling the same version directly replaces its installer-managed JAR and launcher after archive and JAR verification, without uninstalling first. Verification failures preserve the previous installation; unmanaged version directories and symlinks are not overwritten. Match explicit versions to `<revision>` in the root `pom.xml`.

For local sources, pass the directory directly instead of constructing a `file://` URL or calling `cygpath`:

```bash
bash devpulse-dist/install.sh jira --base-url "./devpulse-dist/target"
bash devpulse-dist/install.sh --tool jira --version 0.1.0-SNAPSHOT \
  --base-url 'D:\release files\devpulse'
bash devpulse-dist/install.sh jira --base-url "/opt/devpulse/releases"
```

Supported forms include relative paths, absolute POSIX paths, Git Bash `/d/...`, and Windows `D:/...` or `D:\...`. Git Bash and WSL convert common drive paths; WSL assumes the default `/mnt/<drive>` layout. Linux/macOS cannot directly access an unmounted Windows drive; supply its actual mounted path instead. Plain directory paths containing spaces, Chinese characters, `#`, or literal `%` need quoting but no URL encoding. Existing `file://` support is retained, including `file:///D:/...`, `file:///d/...`, `file://localhost/...`, and percent escapes such as `%20`; a literal `%` in a URL must be `%25`. Local sources are copied directly without curl, with specific path errors for missing directories, archives, or checksum files.

The installer is kept with LF line endings. Installation also handles CRLF launchers and version files in Windows-built archives without changing JAR contents or bypassing SHA-256 checks.

Installation defaults to `$HOME/.local` without administrator access. Add the `export PATH=...` line above to `~/.bashrc` for future Bash sessions. Once installed, run `pulse` or `jira` directly without Maven or the source checkout; Java 25+ and Bash are still required.

Use `--prefix` to customize the installation directory and add its `bin` directory to `PATH`. To uninstall, run `bash devpulse-dist/install.sh --uninstall --tool pulse` or use `--tool jira`; pass the same `--prefix` if you customized it.

[Architecture](../docs/devpulse-dist/architecture.md)
