# 数据库层设计与实现资料汇编 / Database Layer Design Resources

[中文](#中文) | [English](#english)

## 中文

### 1. 这份文档是什么

一份按"能解决什么问题"整理的阅读清单，面向 `devpulse-storage` 这类**小规模、单机、以记录为中心的持久层**。它不是数据库理论综述，也不假设你有 DBA 团队。

所有链接在 2026-11-25 校验可访问。其中 Fowler 的演进式数据库设计、Jdbi 官方文档、SQL Style Guide、Use The Index Luke 四篇我实际读过正文；其余只校验了可达性。

### 2. 必读三篇

| 资料 | 为什么读 | 解决的问题 |
|------|----------|-----------|
| [Evolutionary Database Design](https://martinfowler.com/articles/evodb.html)（Fowler & Sadalage） | 数据库演进实践的奠基文章：变更即迁移、访问代码集中、**数据库重构是三件事同时做**（schema + 数据 + 访问代码）、破坏性变更需要过渡期 | 迁移怎么组织、改表怎么不把现有库搞坏 |
| [Jdbi 3 Developer Guide](https://jdbi.org/) | 本项目的持久层库。第 22.1 节是官方 Best Practices；第 9.3 节讲 `reduceRows` / `JoinRowReducer`；第 21.13 节讲 GraalVM | 父子行如何一次取回并装配、原生镜像下的注意事项 |
| [SQL Style Guide](https://www.sqlstyle.guide/)（Simon Holywell，[简体中文](https://www.sqlstyle.guide/zh/)） | 命名、约束、数据类型，并**明确列出该避免的设计** | 缺 CHECK 约束、命名不一致、表里混入多个实体 |

### 3. 按主题分类

#### 3.1 Schema 与约束

- [SQL Style Guide · Constraints and keys](https://www.sqlstyle.guide/#constraints-and-keys)：范围已知的数值必须写成 `CHECK()`，否则错误值会静默入库。
- [SQL Style Guide · Designs to avoid](https://www.sqlstyle.guide/#designs-to-avoid)：反对 EAV、反对把一个实体的数据拆到多张表、反对把面向对象设计直接套到关系模型。
- [PostgreSQL 文档 · Constraints](https://www.postgresql.org/docs/current/ddl-constraints.html)：CHECK / FK / UNIQUE 的权威语义，H2 语法基本兼容。

#### 3.2 迁移与演进

- [EDD · All database changes are migrations](https://martinfowler.com/articles/evodb.html#AllDatabaseChangesAreMigrations)：每个变更一个编号脚本，配一张 changelog 表，变更要小。
- [EDD · Transition phase](https://martinfowler.com/articles/evodb.html#TransitionPhase)：改名、删列这类破坏性变更用视图或触发器做并行期，让新旧访问方式共存。
- [Flyway · Migrations-based approach](https://documentation.red-gate.com/fd/migrations-based-approach-168984769.html)：迁移工具的通用实践（校验、修复、baseline），可用来对照自研启动器缺什么。
- [Refactoring Databases](https://martinfowler.com/books/refactoringDatabases.html)（Ambler & Sadalage）：拆列、拆表、改约束的步骤清单。

#### 3.3 索引与查询性能

- [Use The Index, Luke](https://use-the-index-luke.com/)（Markus Winand）：写给开发者的索引教程，不需要 DBA 背景。
  - [Nested Loops 与 ORM 的 N+1](https://use-the-index-luke.com/sql/join/nested-loops-join-n1-problem)
  - [分页：offset 与 seek](https://use-the-index-luke.com/sql/partial-results/fetch-next-page)
- [SQL Performance Explained](https://sql-performance-explained.com/)：同作者的 200 页书，作为案头参考。

#### 3.4 Java 持久层

- [Jdbi · Best Practices](https://jdbi.org/#_best_practices)
- [Jdbi · Joins 与 JoinRowReducer](https://jdbi.org/#_joins)：一次查询取回父子行并在 mapper 内装配，避免"半对象"。
- [Jdbi · Testing with H2](https://jdbi.org/#_testing_with_h2)：与本项目测试方式一致，可对照约定。
- [Jdbi · GraalVM Native Image](https://jdbi.org/#_graalvm_native_image)：原生构建相关。
- [Repository 模式](https://martinfowler.com/eaaCatalog/repository.html)（PoEAA）：界定持久化接口该返回领域对象还是数据库行。

#### 3.5 模型边界

- [Effective Aggregate Design](https://www.dddcommunity.org/wp-content/uploads/files/pdf_articles/Vernon_2011_1.pdf)（Vaughn Vernon）：聚合边界如何划分。
- [Designing Data-Intensive Applications](https://dataintensive.net/)（Kleppmann）：第 2 章讲数据模型，不必通读。

#### 3.6 反模式速查

- [SQL Antipatterns](https://pragprog.com/titles/bksqla/sql-antipatterns/)（Bill Karwin）：多值列、EAV、元数据分裂等，读目录即可辨认。

### 4. 与 devpulse-storage 的对应关系

| 现状问题 | 对应资料 | 可采取的动作 |
|----------|----------|--------------|
| `task` 表混了身份、结果、日志路径、复用、daemon 五类信息 | SQL Style Guide「Designs to avoid」+ Vernon 聚合设计 | 拆 `TaskRow`（表行）与 `Task`（领域对象） |
| `TaskRecord.needs` 在 DAO 路径为空、在 store 路径有值 | Jdbi Joins | 用 `JoinRowReducer` 或专用类型装配，消除半对象 |
| `task_id` 与 `name` 双标识，`daemon_instance` 用前者且无外键 | SQL Style Guide「Choosing keys」 | 统一键，补 `(run_id, task_id)` 外键 |
| 状态列是裸 `VARCHAR(32)`，无 CHECK | SQL Style Guide「Validation」 | schema v3 加 CHECK，Java 侧边界转枚举 |
| `project` 在 `run` 与 `daemon_instance` 各存一份，且不参与查询 | EDD「Transition phase」 | 降级为诊断字段并删过滤，或全链路多项目，二选一 |
| `task_result` 缺项目维度 | 同上 | 与上一条一起决策 |
| 时间戳 NULL 语义未定义 | SQL Style Guide「Constraints」 | 定义状态机到时间戳的映射并写进设计文档 |
| `error` 只存 message，异常类型丢失 | SQL Antipatterns | 增加错误分类字段，或把异常映射为稳定枚举 |

### 5. 两小时阅读路径

1. **40 分钟**：Jdbi Best Practices + Joins 两节，当天即可改善代码。
2. **30 分钟**：SQL Style Guide 的命名与约束两节（有中文版）。
3. **40 分钟**：Fowler EDD 的 Practices 部分，跳过开头的场景叙事。
4. **10 分钟**：翻一遍 Use The Index Luke 目录，建立检索路径。

### 6. 落地清单（按收益排序）

1. `needs` 装配改用 `JoinRowReducer`，消除"同一类型两种语义"。
2. `daemon_instance` 补 `(run_id, task_id)` 外键，堵住引用完整性漏洞。
3. 状态列加 `CHECK` 约束（迁移前先清洗存量数据）。
4. 拆 `TaskRow` 与 `Task`，把领域对象与表行分开。
5. 定义"状态 → 必填时间戳"的映射表，写入 `docs/design/relational-storage.md`。
6. 对 `project` 做出取舍：降级为诊断字段，或全链路多项目。

## English

### 1. What this is

A reading list organised by the problem it solves, aimed at small, single-machine, record-centric persistence layers such as `devpulse-storage`. It is neither a database theory survey nor written for teams with a DBA.

Every link was checked on 2026-11-25. I read the full text of the Fowler EDD article, the Jdbi guide, the SQL Style Guide, and Use The Index, Luke; the rest were checked for reachability only.

### 2. Three must-reads

| Resource | Why | Problem it solves |
|----------|-----|-------------------|
| [Evolutionary Database Design](https://martinfowler.com/articles/evodb.html) (Fowler & Sadalage) | The foundational text: every change is a migration, database access code is centralised, a database refactoring changes schema, data, and access code together, and destructive changes need a transition phase | How to organise migrations without breaking existing stores |
| [Jdbi 3 Developer Guide](https://jdbi.org/) | The persistence library this project uses. 22.1 is the official best practices, 9.3 covers `reduceRows` and `JoinRowReducer`, 21.13 covers GraalVM | Fetching parent and child rows in one pass; native-image caveats |
| [SQL Style Guide](https://www.sqlstyle.guide/) (Simon Holywell, [Simplified Chinese](https://www.sqlstyle.guide/zh/)) | Naming, constraints, data types, and an explicit list of designs to avoid | Missing CHECK constraints, inconsistent naming, tables mixing several entities |

### 3. By topic

#### 3.1 Schema and constraints

- [SQL Style Guide · Constraints and keys](https://www.sqlstyle.guide/#constraints-and-keys): a value with a known range belongs in a `CHECK()` constraint, otherwise bad values enter silently.
- [SQL Style Guide · Designs to avoid](https://www.sqlstyle.guide/#designs-to-avoid): against EAV, against spreading one entity across tables, against applying object-oriented design to relational models.
- [PostgreSQL · Constraints](https://www.postgresql.org/docs/current/ddl-constraints.html): authoritative semantics for CHECK, foreign keys, and uniqueness; H2 syntax is close enough.

#### 3.2 Migrations and evolution

- [EDD · All database changes are migrations](https://martinfowler.com/articles/evodb.html#AllDatabaseChangesAreMigrations): one numbered script per change, a changelog table, small steps.
- [EDD · Transition phase](https://martinfowler.com/articles/evodb.html#TransitionPhase): renames and drops get a compatibility period through views or triggers.
- [Flyway · Migrations-based approach](https://documentation.red-gate.com/fd/migrations-based-approach-168984769.html): general migration practice (validate, repair, baseline); useful as a checklist against a hand-rolled migrator.
- [Refactoring Databases](https://martinfowler.com/books/refactoringDatabases.html) (Ambler & Sadalage): step-by-step catalogues for splitting columns and tables.

#### 3.3 Indexing and query performance

- [Use The Index, Luke](https://use-the-index-luke.com/) (Markus Winand): indexing for developers.
  - [Nested loops and the ORM N+1 problem](https://use-the-index-luke.com/sql/join/nested-loops-join-n1-problem)
  - [Pagination: offset versus seek](https://use-the-index-luke.com/sql/partial-results/fetch-next-page)
- [SQL Performance Explained](https://sql-performance-explained.com/): the same author's 200-page reference.

#### 3.4 Java persistence

- [Jdbi · Best Practices](https://jdbi.org/#_best_practices)
- [Jdbi · Joins and JoinRowReducer](https://jdbi.org/#_joins): load parent and child rows once and assemble them in the mapper.
- [Jdbi · Testing with H2](https://jdbi.org/#_testing_with_h2): matches how this project tests.
- [Jdbi · GraalVM Native Image](https://jdbi.org/#_graalvm_native_image)
- [Repository pattern](https://martinfowler.com/eaaCatalog/repository.html) (PoEAA): decides whether a persistence interface returns domain objects or table rows.

#### 3.5 Model boundaries

- [Effective Aggregate Design](https://www.dddcommunity.org/wp-content/uploads/files/pdf_articles/Vernon_2011_1.pdf) (Vaughn Vernon): how to draw aggregate boundaries.
- [Designing Data-Intensive Applications](https://dataintensive.net/) (Kleppmann): chapter 2 on data models is enough.

#### 3.6 Antipatterns

- [SQL Antipatterns](https://pragprog.com/titles/bksqla/sql-antipatterns/) (Bill Karwin): multi-valued columns, EAV, metadata tribbles; the table of contents alone is diagnostic.

### 4. How it maps onto devpulse-storage

| Current problem | Resource | Action |
|-----------------|----------|--------|
| The `task` table mixes identity, outcome, log path, reuse, and daemon state | Style Guide "Designs to avoid" + Vernon | Separate `TaskRow` from `Task` |
| `TaskRecord.needs` is empty on the DAO path and populated on the store path | Jdbi Joins | Assemble with `JoinRowReducer` or a dedicated type |
| `task_id` and `name` both identify a node; `daemon_instance` uses the former without a foreign key | Style Guide "Choosing keys" | Pick one key and add the `(run_id, task_id)` foreign key |
| State is a bare `VARCHAR(32)` | Style Guide "Validation" | Add CHECK in schema v3; map to enums at the boundary |
| `project` is stored twice and never queried | EDD "Transition phase" | Either demote it to a diagnostic field or support multiple projects end to end |
| `task_result` has no project dimension | same | Decide together with the previous row |
| Timestamp nullability is undefined | Style Guide "Constraints" | Define the state-to-timestamp contract in the design document |
| `error` holds only a message | SQL Antipatterns | Add an error category, or map exceptions to a stable enum |

### 5. Two-hour reading path

1. **40 min**: Jdbi Best Practices and Joins.
2. **30 min**: SQL Style Guide, naming and constraints (Chinese version available).
3. **40 min**: Fowler EDD, the Practices section only.
4. **10 min**: skim the Use The Index, Luke table of contents for later lookups.

### 6. Action list, by payoff

1. Replace the `needs` assembly with `JoinRowReducer`.
2. Add the `(run_id, task_id)` foreign key to `daemon_instance`.
3. Add CHECK constraints for the state columns, cleaning existing data first.
4. Separate `TaskRow` from `Task`.
5. Write the state-to-timestamp contract into `docs/design/relational-storage.md`.
6. Decide what `project` means: diagnostic field, or full multi-project support.
