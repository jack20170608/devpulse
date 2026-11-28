# devpulse-storage 使用说明 / devpulse-storage usage

[中文](#中文) | [English](#english)

## 中文

[项目首页](../readme.MD) | [存储设计](../docs/design/relational-storage.md)

`devpulse-storage` 提供运行状态的关系型持久化：运行、任务、依赖边、daemon 实例与跨运行复用索引，使用 H2 + JDBI，schema 迁移是按版本编号的 SQL 文件，位于 `src/main/resources/db/migration/`，由 `Schema.java` 按序应用。该模块不注册命令，由 `devpulse-execution` 与 `devpulse-plugin/bash` 使用。

## English

[Project home](../readme.MD) | [Storage design](../docs/design/relational-storage.md)

`devpulse-storage` provides relational persistence for run state: runs, tasks, dependency edges, daemon instances, and the cross-run reuse index, backed by H2 via JDBI. Migrations are numbered SQL files under `src/main/resources/db/migration/`, applied in order by `Schema.java`. The module registers no commands; Pulse and the core Bash adapter use it.
