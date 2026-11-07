# devpulse-dist 发行与安装 / devpulse-dist distribution and installation

[中文](#中文) | [English](#english)

## 中文

[项目首页](../readme.MD)

### 工具边界

`pulse` 包含任务运行以及 `init`、`review`、`status`、`stop` 子命令；`jira` 是独立工具。`gather` 也有自己的可执行 JAR，但目前不发布安装归档。`devpulse-cli/target/devpulse.jar` 保留旧的 `devpulse <子命令>` 用法，不进入单工具发行包。选择安装 Jira 不会安装 Pulse；选择安装 Pulse 不会安装 Jira 或 Gather 命令。

### 构建和直接运行

在仓库根目录使用 JDK 25+ 和 Maven 构建。`devpulse-dist/target/` 生成各工具独立的 `devpulse-pulse-<版本>.tar.gz`、`devpulse-jira-<版本>.tar.gz` 及对应 `.sha256` 文件。每个归档只含对应工具的可执行 JAR、Bash 启动脚本、版本文件和 JAR 校验文件。

```bash
bash build.sh dist --clean
tar -xzf devpulse-dist/target/devpulse-pulse-0.1.0-SNAPSHOT.tar.gz
bash devpulse-pulse-0.1.0-SNAPSHOT/bin/pulse --help
```

Jira 对应解压 `devpulse-jira-<版本>.tar.gz` 并运行 `bin/jira`。直接解压无需安装器或 Maven；运行需要 Java 25+，脚本需要 Bash（Windows 使用 Git Bash）。Pulse 实际执行任务、`status --tools` 需要 Bash；Jira JAR 本身不依赖 Bash。也可直接运行归档内的 `java -jar devpulse-pulse.jar` 或 `java -jar devpulse-jira.jar`。

### 按需安装、升级和卸载

从可信仓库取得 `devpulse-dist/install.sh`，不要直接执行未经审核的远程脚本。**正式发布渠道暂未配置，当前没有公开可下载的版本。**未来发布时先将根 `pom.xml` 的 `<revision>` 改为非 `SNAPSHOT` 版本，再上传两个归档及各自的 SHA-256 文件到同一可信地址。安装器需 `curl`、`tar`、`sha256sum` 和 Bash；必须通过 `--base-url` 指定存放发行文件的 HTTPS 或本地 `file://` 地址，并校验所选归档及 JAR，不在终端用户机器上运行 Maven。默认前缀为 `$HOME/.local`，应把该目录下的 `bin` 加入 `PATH`。

```bash
RELEASE_URL="https://example.org/devpulse/releases/v0.1.0"
bash devpulse-dist/install.sh --tool pulse --version 0.1.0 --base-url "$RELEASE_URL"
bash devpulse-dist/install.sh --tool jira --version 0.1.0 --base-url "$RELEASE_URL" --prefix "$HOME/.local"
bash devpulse-dist/install.sh --uninstall --tool pulse
```

以上 URL 仅为占位符，需替换为实际发行地址；当前可使用本地构建的归档及其 `file://` 地址。安装另一个版本只切换所选工具；`--uninstall --tool jira` 只移除安装器管理的 Jira 文件。不会删除项目中的 `.devpulse` 运行历史或用户配置。校验文件与归档同源，传输校验不替代对发行来源的信任。安装器拒绝覆盖非自身管理的同名入口或安装目录。

### 安装本地构建的发行包

`devpulse-dist` 生成可单独安装的 Pulse 和 Jira 发行包，不是可执行命令，也不包含 Gather 或组合式 `devpulse` 入口。目前没有公开下载渠道，可先从源码构建，再安装本地发行包。

准备 JDK 25+、Maven、Bash、`curl`、`tar` 和 `sha256sum`，在仓库根目录执行以下命令（Windows 使用 Git Bash）：

```bash
bash build.sh dist --clean

# 本地发行目录；兼容 Linux、macOS 和 Windows Git Bash
DIST_DIR="$(cd devpulse-dist/target && pwd)"
if command -v cygpath >/dev/null 2>&1; then
    DIST_DIR="/$(cygpath -am "$DIST_DIR")"
fi
RELEASE_URL="file://$DIST_DIR"

# 按需安装，也可以只执行其中一条
bash devpulse-dist/install.sh --tool pulse --version 0.1.0-SNAPSHOT --base-url "$RELEASE_URL"
bash devpulse-dist/install.sh --tool jira --version 0.1.0-SNAPSHOT --base-url "$RELEASE_URL"

export PATH="$HOME/.local/bin:$PATH"
pulse --help
jira --help
```

版本号应与根 `pom.xml` 的 `<revision>` 一致；归档和校验文件位于 `devpulse-dist/target/`。默认安装到 `$HOME/.local`，无需管理员权限；将上述 `export PATH=...` 加入 `~/.bashrc` 可供后续 Bash 会话使用。安装后直接执行 `pulse` 或 `jira`，不需要 Maven 或源码目录，但仍需 Java 25+ 和 Bash。

可通过 `--prefix` 自定义安装目录，并将其 `bin` 加入 `PATH`。卸载使用 `bash devpulse-dist/install.sh --uninstall --tool pulse` 或 `--tool jira`；自定义安装目录时需传入相同的 `--prefix`。

[架构设计](../docs/devpulse-dist/architecture.md)

## English

[Project home](../readme.MD)

### Tool boundaries

`pulse` runs tasks and includes the `init`, `review`, `status`, and `stop` subcommands; `jira` is independent. `gather` also has its own executable JAR but currently has no installation archive. `devpulse-cli/target/devpulse.jar` preserves the old `devpulse <subcommand>` syntax and is not included in individual tool archives. Installing Jira does not install Pulse; installing Pulse does not install Jira or the Gather command.

### Build and run without installation

Build from the repository root with JDK 25+ and Maven. `devpulse-dist/target/` produces separate `devpulse-pulse-<version>.tar.gz` and `devpulse-jira-<version>.tar.gz` archives with adjacent `.sha256` files. Each archive contains only its tool's executable JAR, Bash launcher, version file, and JAR checksum.

```bash
bash build.sh dist --clean
tar -xzf devpulse-dist/target/devpulse-pulse-0.1.0-SNAPSHOT.tar.gz
bash devpulse-pulse-0.1.0-SNAPSHOT/bin/pulse --help
```

For Jira, extract `devpulse-jira-<version>.tar.gz` and run `bin/jira`. Direct extraction needs neither the installer nor Maven; running requires Java 25+, and the launcher needs Bash (Git Bash on Windows). Pulse task execution and `status --tools` require Bash; the Jira JAR itself does not. You can also run `java -jar devpulse-pulse.jar` or `java -jar devpulse-jira.jar` directly inside the extracted archive.

### Selective installation, upgrades, and removal

Obtain `devpulse-dist/install.sh` from a trusted checkout; do not execute an unreviewed remote script directly. **No formal publishing channel is configured yet, so no publicly downloadable version exists.** For a future release, change the root `pom.xml`'s `<revision>` to a non-`SNAPSHOT` version and upload both archives and their SHA-256 files to the same trusted location. The installer needs `curl`, `tar`, `sha256sum`, and Bash. You must pass `--base-url` pointing to an HTTPS or local `file://` location hosting the release files; the installer checks the selected archive and JAR without using Maven on the target machine. The default prefix is `$HOME/.local`; add its `bin` directory to `PATH`.

```bash
RELEASE_URL="https://example.org/devpulse/releases/v0.1.0"
bash devpulse-dist/install.sh --tool pulse --version 0.1.0 --base-url "$RELEASE_URL"
bash devpulse-dist/install.sh --tool jira --version 0.1.0 --base-url "$RELEASE_URL" --prefix "$HOME/.local"
bash devpulse-dist/install.sh --uninstall --tool pulse
```

The URL above is a placeholder; replace it with an actual release location. For now, you can use locally built archives and a `file://` URL. Installing another version switches only the selected tool; `--uninstall --tool jira` removes only installer-managed Jira files. It does not delete `.devpulse` run history or user configuration in a project. A checksum served from the same location verifies transfer integrity, not the authenticity of the release source. The installer refuses to overwrite a launcher or installation directory it does not manage.

### Install locally built release archives

`devpulse-dist` produces separately installable Pulse and Jira archives; it is not an executable command and does not include Gather or the combined `devpulse` entry point. No public download channel is available yet, so build from source and install the local archives.

Prepare JDK 25+, Maven, Bash, `curl`, `tar`, and `sha256sum`, then run these commands from the repository root (use Git Bash on Windows):

```bash
bash build.sh dist --clean

# Local release directory; supports Linux, macOS, and Windows Git Bash
DIST_DIR="$(cd devpulse-dist/target && pwd)"
if command -v cygpath >/dev/null 2>&1; then
    DIST_DIR="/$(cygpath -am "$DIST_DIR")"
fi
RELEASE_URL="file://$DIST_DIR"

# Install as needed; either installation command can be used on its own
bash devpulse-dist/install.sh --tool pulse --version 0.1.0-SNAPSHOT --base-url "$RELEASE_URL"
bash devpulse-dist/install.sh --tool jira --version 0.1.0-SNAPSHOT --base-url "$RELEASE_URL"

export PATH="$HOME/.local/bin:$PATH"
pulse --help
jira --help
```

Match the version to `<revision>` in the root `pom.xml`; archives and checksum files are written to `devpulse-dist/target/`. Installation defaults to `$HOME/.local` without administrator access. Add the `export PATH=...` line above to `~/.bashrc` for future Bash sessions. Once installed, run `pulse` or `jira` directly without Maven or the source checkout; Java 25+ and Bash are still required.

Use `--prefix` to customize the installation directory and add its `bin` directory to `PATH`. To uninstall, run `bash devpulse-dist/install.sh --uninstall --tool pulse` or use `--tool jira`; pass the same `--prefix` if you customized it.

[Architecture](../docs/devpulse-dist/architecture.md)
