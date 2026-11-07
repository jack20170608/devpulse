# 内部 BOM 架构设计 / Internal BOM architecture

[中文](#中文) | [English](#english)

## 中文

[架构导航](../architecture.md) | [模块 POM](../../devpulse-bom/pom.xml)

### 职责与消费边界

`devpulse-bom` 是仅生成 POM 的内部产物版本清单，通过 `dependencyManagement` 管理 `devpulse-core`、`pulse`、`gather`、`jira`、`depmap`，版本统一为 `${project.version}`。它不生成可执行 JAR、不聚合命令，也不作为工具的普通运行时依赖。

需要集中管理 devpulse 内部依赖版本的消费者将 BOM 作为 `type=pom`、`scope=import` 导入依赖管理，再声明实际使用的模块。工具仍保持彼此独立，不因同属 BOM 而建立代码依赖。

### 父 POM 与版本

BOM 通过本地相对路径继承 `devpulse-dependencies`，间接继承根项目的 `<revision>` 和构建约定。根 reactor 无需先安装兄弟 BOM 才能解析父 POM；外部消费者需要使用已安装或发布的 POM。Flatten 的 CI-friendly 版本解析设置与其他模块一致。

## English

[Architecture index](../architecture.md) | [Module POM](../../devpulse-bom/pom.xml)

### Responsibilities and consumption boundaries

`devpulse-bom` is a POM-only internal artifact version catalog. Its `dependencyManagement` manages `devpulse-core`, `pulse`, `gather`, `jira`, and `depmap`, all using `${project.version}`. It produces no executable JAR, aggregates no commands, and is not a regular runtime dependency of tools.

Consumers needing centralized devpulse dependency versions import the BOM into dependency management with `type=pom` and `scope=import`, then declare the modules they actually use. Tools remain independent; appearing in the same BOM does not create code dependencies.

### Parent POM and version

The BOM inherits `devpulse-dependencies` through a local relative path, indirectly inheriting the root `<revision>` and build conventions. Root-reactor parent resolution does not require installing a sibling BOM first; external consumers need installed or published POMs. Flatten's CI-friendly version resolution matches the other modules.
