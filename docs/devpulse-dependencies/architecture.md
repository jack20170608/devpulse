# 外部依赖模块架构设计 / External dependency module architecture

[中文](#中文) | [English](#english)

## 中文

[架构导航](../architecture.md) | [模块 POM](../../devpulse-dependencies/pom.xml)

### 职责与继承

`devpulse-dependencies` 是仅生成 POM 的外部依赖版本管理模块，继承根 `devpulse-parent`。通过属性和 `dependencyManagement` 集中管理 picocli、Typesafe Config、SnakeYAML Engine、终端库、内嵌 Maven 等第三方依赖，供子模块声明实际所需依赖；依赖管理本身不会将所有依赖加入运行时。

core、插件聚合模块、兼容 CLI、发行模块及内部 BOM 通过本地相对父 POM 路径继承它。内部代码依赖仍需根 reactor 的 `--also-make` 构建，不能将父 POM 解析成功视为兄弟 JAR 已经可用。

### 项目与构建版本

根 `pom.xml` 的 `<revision>` 是项目版本的唯一值；根项目及子模块父版本使用 `${revision}`，内部依赖使用 `${project.version}`。外部依赖版本、构建插件版本和配置格式版本有独立语义，不跟随项目版本递增。

构建插件版本由根 POM 的 `pluginManagement` 管理，设置 UTF-8 和 Java `release=25`，默认不启用预览。继承的 Flatten Maven Plugin 保留 `resolveCiFriendliesOnly`，安装和发布时解析 CI-friendly 版本占位符，避免消费者获得未解析的 `${revision}`。

## English

[Architecture index](../architecture.md) | [Module POM](../../devpulse-dependencies/pom.xml)

### Responsibilities and inheritance

`devpulse-dependencies` is a POM-only external dependency version manager inheriting the root `devpulse-parent`. Properties and `dependencyManagement` centralize third-party versions such as picocli, Typesafe Config, SnakeYAML Engine, terminal libraries, and embedded Maven. Child modules declare only the dependencies they need; dependency management alone does not add every dependency to runtime.

Core, the plugin aggregator, compatibility CLI, distribution, and internal BOM inherit it through local relative parent POM paths. Internal code dependencies still require root-reactor builds with `--also-make`; successful parent resolution does not imply sibling JAR availability.

### Project and build versions

The root `pom.xml`'s `<revision>` is the sole project version value. Root and child parent versions use `${revision}`, while internal dependencies use `${project.version}`. External dependency versions, build plugin versions, and configuration schema versions have independent meanings and do not advance with the project version.

The root POM's `pluginManagement` manages build plugin versions, with UTF-8 and Java `release=25` and no preview features by default. The inherited Flatten Maven Plugin retains `resolveCiFriendliesOnly`, resolving CI-friendly placeholders during installation and publishing so consumers do not receive unresolved `${revision}` values.
