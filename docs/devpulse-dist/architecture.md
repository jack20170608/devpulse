# 发行模块架构设计 / Distribution module architecture

[中文](#中文) | [English](#english)

## 中文

[架构导航](../architecture.md) | [使用说明](../../devpulse-dist/README.md)

### 模块与产物

`devpulse-dist` 是 `packaging=pom` 的发行模块，直接依赖 Pulse 和 Jira 产物，不提供 Java 命令入口。工具不反向依赖发行模块；Gather、Depmap 和兼容 CLI 不进入当前发行包。

构建通过根 reactor 同时完成所需工具。`prepare-package` 阶段先生成各 JAR 的 SHA-256，再由 Assembly 按 `src/assembly/pulse.xml` 和 `jira.xml` 组装独立归档；`package` 阶段生成归档的 SHA-256。每个归档包含对应的可执行 JAR、Bash 入口、版本及校验文件，版本使用 `${project.version}`。

### 安装与所有权

`install.sh` 从显式指定的 HTTPS 或本地 `file://` 位置获取所选工具，校验归档和 JAR，不在用户机器执行 Maven。默认使用用户级安装前缀，升级和卸载只操作所选工具的受管文件，不覆盖不属于安装器的同名入口，不删除项目配置、运行历史或业务数据。

归档和校验文件同源，校验只保证传输完整性，不替代来源信任。启动脚本需要 Bash，JAR 需要 Java 25+；Jira JAR 本身不依赖 Bash。当前没有正式公开发行渠道；命令、安装步骤和本地发行示例统一维护在模块使用说明中。

## English

[Architecture index](../architecture.md) | [Usage](../../devpulse-dist/README.md)

### Module and artifacts

`devpulse-dist` is a `packaging=pom` distribution module that directly depends on Pulse and Jira artifacts and provides no Java command entry point. Tools never depend on distribution; Gather, Depmap, and the compatibility CLI are excluded from current archives.

The root reactor builds the required tools together. During `prepare-package`, JAR SHA-256 checksums are generated before Assembly creates separate archives using `src/assembly/pulse.xml` and `jira.xml`; `package` generates archive SHA-256 checksums. Each archive contains its executable JAR, Bash launcher, version, and checksum files, using `${project.version}`.

### Installation and ownership

`install.sh` retrieves the selected tool from an explicit HTTPS or local `file://` location and checks the archive and JAR without running Maven on the target machine. Installation defaults to a user-level prefix. Upgrades and removal touch only installer-managed files for the selected tool, never overwrite unmanaged launchers, and do not remove project configuration, run history, or business data.

Archives and checksums share a source, so checksums establish transfer integrity rather than source authenticity. Launchers require Bash and JARs require Java 25+; the Jira JAR itself does not depend on Bash. No formal public release channel exists yet. Commands, installation steps, and local-release examples live in the module usage guide.
