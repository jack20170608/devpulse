# DAG 任务执行框架调研 / DAG Task Execution Framework Research

[中文](#中文) | [English](#english)

## 中文

### 1. 核心结论

**不存在适合所有场景的“最佳 DAG 框架”。应先确定任务执行的边界，再比较产品：本地命令、应用内组件、数据平台、Kubernetes 作业和跨服务持久化流程需要的是不同系统。**

| 主要需求 | 优先考察 | 主要取舍 |
|---|---|---|
| 本地脚本、检查、构建任务 | Task（go-task） | 轻量、直接；不提供分布式持久化执行 |
| 容器化构建、本地与 CI 一致性、增量缓存 | Dagger | 环境复现和缓存强；需要引擎及容器运行环境 |
| Python 数据调度、已有大量系统集成 | Apache Airflow | 集成生态丰富；平台运维和调度概念较重 |
| 数据资产依赖、分区补数、资产可观测性 | Dagster | 资产模型贴合数据平台；学习成本高于简单任务列表 |
| Python 动态流程、快速自动化 | Prefect | 普通 Python 控制流友好；不能把运行状态记录等同于任意进程断点恢复 |
| 可视化数据作业、集中运维与多租户 | Apache DolphinScheduler | 平台能力全面；分布式部署和环境治理成本较高 |
| 已有 Kubernetes 的容器批任务 | Argo Workflows | 容器、资源和产物模型自然；不适合免基础设施的本地工具 |
| 科学计算、HPC、文件型可复现流水线 | Nextflow | 执行后端和缓存恢复强；有专用 DSL 与领域模型 |
| 多语言声明式自动化、事件触发 | Kestra | 配置、插件与 UI 集成；企业权限和部分执行后端有版本边界 |
| 跨服务长流程、回调、故障后继续执行 | Temporal | 持久化执行强；确定性约束和工作流升级需要专门设计 |
| JSON 定义流程、多语言 Worker、动态分支 | Conductor OSS | 定义与执行解耦；需要服务端、持久化及 Worker 治理 |
| Java 数据批处理、事务与分块处理 | Spring Batch | 批处理语义成熟；不是开箱即用的通用 DAG 调度平台 |
| Java 应用内组件编排 | LiteFlow | 易嵌入、规则驱动；不能替代分布式持久化工作流平台 |
| AWS 内的托管服务编排 | AWS Step Functions | 减少自运维；受云绑定、配额及使用计费影响 |

**对 devpulse 当前“本地 HOCON + Bash + 单次 DAG 执行”的定位，最值得直接比较的是 Task；值得借鉴的容器化路线是 Dagger。仅为了拓扑排序和受控并行，不值得引入 Airflow、Temporal 或 Kubernetes 平台。** 这是基于项目现有说明的适配判断，不是对当前实现的代码审计。

### 2. 范围与调研方法

调研日期：**2026-11-01**。覆盖 14 个具有代表性的开源项目或商业托管服务，依据官方文档、官方仓库说明及产品版本对照资料进行案头研究，**未部署产品、未进行性能或故障注入测试**。

本文中，DAG 指任务及其无环依赖关系。实际工作流产品还可能支持条件、循环、等待和回调，因此 Temporal、Conductor、Step Functions、LiteFlow 等不是严格意义上仅支持 DAG 的引擎，而是可承载 DAG 场景的更广义流程系统。

评价维度包括：流程表达方式、执行边界、基础设施依赖、并发与资源治理、失败恢复、缓存与重跑、可观测性、开发体验，以及商业功能边界。文中的“轻量”“成本高”等为基于架构的定性判断，不是实测资源消耗或价格排名。

本次不展开 Flyte、Kubeflow Pipelines、Luigi、Azkaban、Dask、Ray、BPMN 引擎及各云厂商同类服务；它们并非没有价值，而是分别偏向 ML 平台、传统数据调度、分布式计算或业务流程建模。也不使用 GitHub Star、厂商宣传吞吐量或未统一条件的跑分作为选型依据。

### 3. 架构与执行模型对比

| 产品 | 编排方式与主要执行单元 | 典型运行依赖 | 定性部署负担 |
|---|---|---|---|
| Task | YAML；本地命令，`deps` 表达依赖 | CLI 与命令所需工具 | 低 |
| Dagger | 模块/API；延迟求值的函数与产物 DAG | Dagger Engine、容器运行环境；可选远程引擎/云服务 | 中 |
| Airflow | Python DAG、Operator/Task；Worker 执行 | Scheduler、DAG Processor、API Server、元数据库；按后端增加 Worker/组件 | 中—高 |
| Dagster | Python asset/op/graph；运行与步骤 | Webserver、Daemon、Code Location、状态存储；按配置增加执行后端 | 中 |
| Prefect | Python `flow`/`task`；运行时控制流 | 本地可直接执行；集中调度使用 Server/Cloud 及对应执行基础设施 | 低—中 |
| DolphinScheduler | Web DAG、API、Python SDK；作业任务 | 单机可体验；生产集群包含 Master、Worker、数据库等组件 | 中—高 |
| Argo Workflows | YAML/SDK；容器、Steps/DAG | Kubernetes、Controller；按需增加 Server、产物存储、归档数据库 | 高；已有 K8s 时增量负担较低 |
| Nextflow | 专用 DSL；Process、Channel 数据流 | JVM、本地工具或 HPC/云执行后端；缓存与工作目录 | 低—中；集群成本另算 |
| Kestra | YAML、插件、显式 DAG 与控制流 | Server、状态存储及任务执行环境；按版本与部署模式扩展 | 中 |
| Temporal | SDK 工作流代码；Workflow/Activity | Temporal Service/Cloud、Worker；自托管需要持久化后端 | 中—高 |
| Conductor OSS | JSON/SDK；系统任务与外部 Worker | Server、持久化、队列、索引与锁；部分职责可共用后端 | 中—高 |
| Spring Batch | Java 配置；Job、Step、Chunk、Flow | JVM/Spring；需要持久化恢复时配置持久化 JobRepository | 低—中；取决于既有 Spring 环境 |
| LiteFlow | EL 规则；Java/脚本组件与上下文 | JVM 与应用依赖；可选外部规则存储 | 低 |
| AWS Step Functions | Amazon States Language；状态机、服务调用 | AWS 托管控制面及被调用的计算/服务 | 自运维低；云依赖高 |

“支持 Java”不等于“可作为 Maven 库直接嵌入”：Temporal 的 Java SDK、Argo 的 Java 客户端和 Conductor 的 Java Worker 仍需要对应服务端；Spring Batch 与 LiteFlow 更接近应用内框架。

### 4. 各框架优缺点

#### 4.1 Task（go-task）

**优势**：CLI 与 YAML 定义简单，适合替代散落的构建脚本；支持依赖并发、顺序任务调用、并发上限、输出前缀、变更检查和清理动作。基本任务执行不需要常驻控制面。[S01]

**不足**：不是分布式调度服务，也不能用变更检查替代持久化断点恢复。共享依赖默认会按调用尝试执行，需配置 `run: once` 或 `run: when_changed` 才能控制重复调用；这一点与“整张 DAG 的同名节点天然只执行一次”不同。跨平台命令及 Shell 仍须审核。[S01]

**适合**：开发者本地检查、构建、发布准备和小型自动化。不适合需要租户隔离、跨机器接管或长流程恢复的系统。

#### 4.2 Dagger

**优势**：将容器、文件、函数和构建产物纳入同一个增量 DAG，适合本地与 CI 共用流水线；内置层、卷和函数调用缓存，减少重复构建与依赖下载。[S02][S03]

**不足**：不是一个只依赖已有本机 Shell 的轻量任务执行器；要承担引擎、容器环境、镜像与缓存治理。外部网络和具有副作用的调用不能仅凭缓存模型判断正确性；第一次运行与缓存命中的表现必须分别评估。

**适合**：容器化构建、可复现 CI、跨语言工程流水线。不以日期补数、业务审批或跨服务长流程为主要选型方向。

#### 4.3 Apache Airflow

**优势**：Python DAG 明确表达任务依赖，Operator/Provider 生态覆盖大量外部系统；UI、历史记录、并发池与不同 Executor 有利于集中调度。适合围绕数据周期组织批任务。[S04]

**不足**：完整部署包含多种组件和元数据库；DAG 解析、调度、执行与版本管理均需维护。不应把其调度开销视为细粒度函数调用的零成本。XCom 适合小型元数据，大产物应放外部存储，且不同任务不保证落在同一机器。[S04]

**适合**：已有 Python 数据工程团队、定时 ETL/ELT、需要多系统集成的平台。不是本地 CLI 或低延迟应用内调用的首选。

#### 4.4 Dagster

**优势**：以数据资产及其上游依赖建模，能把表、文件和物化结果与执行联系起来；支持分区补数和资产代码版本信息，也保留 op/graph 模型。[S05][S06]

**不足**：asset、partition、resource、executor 等概念对简单命令流来说偏重；完整 OSS 部署仍有 Webserver、Daemon、Code Location 和存储配置。官方 OSS 架构目前不支持 Daemon 副本，不能把 Worker 可扩展性等同于每个控制面组件都支持主动多副本。[S07]

**适合**：重视数据资产可观测性、依赖追踪和分区重算的数据平台。若业务只关心“几个脚本按顺序执行”，收益有限。

#### 4.5 Prefect

**优势**：`@flow`、`@task` 接近普通 Python；运行时条件、循环和动态组合自然。任务支持重试、缓存、并发和超时，既能本地直接执行，也能通过部署接入集中触发与观测。[S08][S09]

**不足**：Python 中的控制流灵活，但运行前不一定能得到完整静态 DAG。任务状态更新存在最终一致性；普通函数或进程中未持久化的中间状态，不会因为 UI 有历史就自动获得 Temporal 式恢复能力。集中部署也需要管理执行基础设施。[S08][S09]

**适合**：Python 自动化、动态数据处理与快速迭代团队。严格静态计划审阅或跨语言应用内执行不占优势。

#### 4.6 Apache DolphinScheduler

**优势**：提供拖拽 DAG、Web 管理、API、Python SDK、多种作业类型、补数、工作流版本与多租户管理；多 Master、多 Worker 架构便于构建集中数据调度平台。[S10]

**不足**：完整集群的部署、升级、Worker 环境与权限治理比本地工具复杂。可视化降低上手门槛，但流程仍需要可靠导出、版本审阅与发布约束，不能只靠 UI 修改。官方性能宣传缺少本文统一基准验证，不能据此判定比其他产品更快。

**适合**：需要可视化运维、集中管理和多类型数据作业的团队。不适合以单个可执行 JAR、离线本地运行作为核心要求的工具。

#### 4.7 Argo Workflows

**优势**：直接使用 Kubernetes CRD 和容器执行模型；支持 DAG/Steps、产物与参数、资源约束、重试、超时、并发限制、Cron 和归档。任务隔离与资源调度可复用 Kubernetes。[S11]

**不足**：需要 K8s 运维、RBAC、镜像和产物存储。Pod 启动及控制面交互带来额外开销，应实测后决定是否适合极短任务。工作流对象、归档记录和产物是不同生命周期，不能默认删除后仍可恢复。

**适合**：已有 Kubernetes 的容器批处理、ML 任务和基础设施自动化。没有 K8s 的本地命令场景通常不划算。

#### 4.8 Nextflow

**优势**：Process 与 Channel 将脚本组织为数据流；同一流水线可配置本地、HPC 或云执行后端，适合大规模文件处理。`-resume` 根据任务缓存和输出复用结果，适合昂贵计算的迭代与恢复。[S12][S13]

**不足**：需要学习专用 DSL 和 Channel 语义；不是 JVM 上的普通嵌入式 DAG 库。恢复依赖匹配的任务缓存及仍然存在的工作目录输出，清理其中任一部分都可能导致重算。中心化运营与团队治理还需评估周边平台。

**适合**：科学计算、生物信息、HPC 和可复现文件流水线。一般微服务业务编排不是其主要优势。

#### 4.9 Kestra

**优势**：以 YAML 和插件统一多语言脚本、数据集成及事件驱动自动化，配有 UI；核心 DAG 任务通过 `dependsOn` 声明依赖，通过 `concurrent` 控制并行度。[S14][S15]

**不足**：插件、表达式和复杂 YAML 会形成新的维护成本。根据当前官方版本对照，SSO、RBAC、多租户、部分远程 Task Runner 和治理功能属于企业版；不能把整个产品宣传能力当作 OSS 默认能力。DAG 插件页面还说明该任务暂不支持 UI 低代码表单。[S15][S16]

**适合**：希望用声明式配置集中编排多语言自动化的团队。选型时必须先确认具体插件、执行后端和权限要求是否落在目标版本内。

#### 4.10 Temporal

**优势**：通过事件历史和确定性重放恢复 Workflow 执行，适合长时间等待、回调和跨服务流程；提供 Java 等 SDK。Worker 故障不意味着已记录的流程进度丢失。[S17]

**不足**：Workflow 代码必须遵守确定性约束，外部 I/O 放在 Activity 等受支持的边界中，代码升级还要保持重放兼容性。Activity 完成但未成功报告时可能重试，所以不能宣称外部命令或业务副作用天然 exactly-once；仍要设计幂等。[S17][S18]

**适合**：订单、资源开通等跨服务长流程，以及对故障恢复有明确要求的系统。仅为本地脚本并行引入它通常过重。

#### 4.11 Conductor OSS

**优势**：流程定义与外部 Worker 解耦，适合多语言服务；支持动态任务、分支、子工作流、循环及持久化状态，并提供重试、超时、暂停、恢复和重跑控制。[S19]

**不足**：需要服务端、状态存储、队列、索引与生产级锁配置；Worker 轮询、超时、任务重复执行及版本发布都需治理。当前可用 PostgreSQL 等组合简化后端，不能沿用“必需 Redis + Elasticsearch”的旧印象。[S20]

**适合**：希望用机器可读定义统一编排多语言 Worker 的平台。应区分当前 Conductor OSS 与 Orkes 商业产品；旧 Netflix 仓库状态不代表当前 OSS 项目的维护状态。[S19]

#### 4.12 Spring Batch

**优势**：Job/Step、分块读写、事务、重试、分区和并行处理适合 Java 大批量数据处理；配置持久化 JobRepository 后可管理运行元数据与恢复信息。[S21][S22]

**不足**：不是完整调度平台；日历触发、跨作业通用依赖图、多租户 UI 和分布式治理通常要配套实现。当前文档中的无资源 JobRepository 不保存元数据、不支持恢复且非线程安全，不能默认所有配置都具备持久化恢复。[S22]

**适合**：Spring/Java 体系的导入、导出、清洗与事务批处理。仅包装外部 Bash 命令时，Batch 数据处理抽象可能得不偿失。

#### 4.13 LiteFlow

**优势**：应用内 Java 组件与规则驱动的编排模型，强调组件复用、上下文传递及规则灵活变更；可使用脚本组件和外部规则存储，便于拆解复杂业务逻辑。[S23]

**不足**：组件编排不等于外部进程生命周期管理，更不等于跨机器的持久化恢复。进程日志、退出码、取消、运行记录与隔离需要额外处理；官方定位也不是角色审批流平台。[S23]

**适合**：Java 应用内的定价、校验、业务组件组合等流程。选它之前要明确目标是“拆解业务代码”，还是“执行脚本 DAG”。

#### 4.14 AWS Step Functions

**优势**：托管状态机、可视化执行记录和 AWS 服务集成减少自运维工作；支持分支、并行、Map、重试与错误处理，Standard 可使用特定服务的同步作业和回调模式。[S24][S25]

**不足**：云平台绑定、服务配额与持续使用成本明显。Standard 按状态转换计费；Express 按执行次数、时长和内存计费，还要计入实际计算、日志与存储成本。Standard 最长一年、Express 最长五分钟；Express 不支持 `.sync` 和 `.waitForTaskToken` 模式。[S25]

**适合**：AWS 内的服务编排和 Serverless 流水线。Standard 的 exactly-once 工作流模型并不消除显式重试或外部调用的幂等要求；异步 Express 是 at-least-once，同步 Express 是 at-most-once，不能混为一谈。[S25]

### 5. 最容易混淆的执行语义

| 概念 | 应如何理解 | 选型时应确认 |
|---|---|---|
| DAG 依赖与资源限制 | 图决定就绪条件，资源限制决定何时允许启动 | 全局、单工作流、租户与资源池限制分别如何作用 |
| 并行与分布式 | 单机多任务并行不等于跨机器调度 | Worker 故障由谁接管，任务环境是否一致 |
| 重试与恢复 | 重试通常重新执行任务；恢复可以复用已完成步骤或重放流程进度 | 崩溃前已产生但未记录的副作用会不会重复 |
| 缓存与重启 | 缓存按输入等条件复用结果，不代表恢复运行中的 OS 进程 | 缓存键、产物保留、外部状态变化与失效条件 |
| 运行历史与持久化执行 | 可查历史不代表服务重启后会继续调度未完成任务 | 恢复是否自动，是否需要手动 rerun/resume |
| Fail-fast 与取消 | 不再启动新任务、取消正在运行任务、撤销已完成副作用是三件事 | 独立分支、后代进程、清理步骤与补偿如何处理 |
| Exactly-once 与业务幂等 | 平台的保证有作用范围，外部副作用仍可能受重试和网络不确定性影响 | 是否有幂等键、结果查询、去重或补偿机制 |
| 流程定义与运行快照 | 编辑定义不能等同于安全修改正在运行的流程 | 使用哪个版本，重跑是否保留参数与执行环境 |

对外部命令，必须单独确认 Shell、工作目录、环境变量、日志背压、超时、退出码、取消及子进程回收。Java 虚拟线程可以降低阻塞等待的线程成本，**不能替代外部进程数、CPU、内存和外部 API 配额的限制**。

### 6. 成本、治理与商业边界

| 类型 | 代表产品 | 容易漏算的成本 |
|---|---|---|
| 本地执行工具 | Task、Dagger | 环境兼容、容器引擎、缓存磁盘、开发者排障；Dagger 云服务另行评估 |
| 应用内框架 | Spring Batch、LiteFlow | 接入 Spring/规则模型、自建日志与运维界面、持久化和升级兼容 |
| 自托管数据编排平台 | Airflow、Dagster、DolphinScheduler、Prefect、Kestra | 数据库、控制面、Worker、备份、升级、凭据与权限治理 |
| 容器/HPC 流水线 | Argo、Nextflow | 集群或作业后端、镜像分发、对象存储、网络、工作目录与产物保留 |
| 持久化业务流程平台 | Temporal、Conductor | 历史存储、任务队列、Worker、重试副作用、流程版本兼容 |
| 云托管状态机 | Step Functions | 转换/执行计费、实际计算、日志、存储、网络与供应商绑定 |

开源不等于所有企业功能免费，也不等于没有运营成本。Dagster OSS/Dagster+、Prefect Server/Cloud、Temporal 自托管/Cloud、Kestra OSS/Enterprise、Conductor OSS/Orkes、Nextflow/Seqera、Dagger 本地引擎/云服务，应逐项确认权限、审计、隔离、保留期和支持边界。

本报告不对所有产品给出许可证审计、固定商业报价或 SLA 承诺；正式采购或引入依赖前，应针对**锁定的发行版本及实际使用的插件**核对 LICENSE、商业条款、安全公告和运行时支持范围。“基于 JVM”也不能替代 JDK 25 上的兼容验证。

### 7. 按场景缩小候选集

| 场景 | 建议首轮候选 | 判定重点 |
|---|---|---|
| 免服务端的本地任务执行 | Task；以现有轻量实现为基线 | 依赖去重、执行前审阅、失败策略、跨平台 Shell |
| 容器化本地/CI 工程任务 | Dagger；已有 K8s 时考虑 Argo | 复现性、冷启动与热缓存、容器运行成本 |
| 定时数据平台 | Airflow、Dagster、DolphinScheduler | 集成生态、资产/任务模型、补数、团队运维方式 |
| 动态 Python 自动化 | Prefect、Dagster | 运行时动态性、资产需求与重跑体验 |
| 多语言声明式自动化 | Kestra、Conductor | 插件型任务还是自建 Worker；版本与权限边界 |
| 科学计算和 HPC | Nextflow | 执行后端、文件与缓存语义、可复现性 |
| Java 数据批处理 | Spring Batch | Chunk、事务、重启上下文和数据一致性 |
| Java 应用内业务组件 | LiteFlow | 组件复用、规则复杂度、应用内延迟 |
| 跨服务长流程 | Temporal、Conductor；AWS 内考虑 Step Functions | 故障恢复、回调、幂等、版本升级与治理 |

不要先给 14 个产品打一个总分：基础设施、语言与恢复要求是硬约束。先排除不满足硬约束的候选，再用相同工作负载比较剩下的两三个产品。

### 8. 对 devpulse 的参考意义

项目 README 描述的核心边界是：本地 HOCON 配置、Bash 命令、传递依赖与共享依赖去重、受控并发、失败后停止新任务、执行前审阅，以及本地运行历史。因此，这里不是将大型平台替换进项目的建议，也不代表新增功能已经实现。

| 当前或潜在需求 | 最有参考价值的对象 | 可借鉴的机制 |
|---|---|---|
| 本地命令与依赖体验 | Task | 顺序/并行表达、显式去重、输出组织、清理策略 |
| 容器化和可复现工程任务 | Dagger | 产物依赖、环境封装、缓存键与失效边界 |
| 图形化静态审阅与运行定位 | Airflow、Argo、DolphinScheduler | 任务状态、上游/下游定位、运行版本展示 |
| 缓存复用和部分重跑 | Nextflow、Dagster | 产物存在性、输入/代码版本与重算边界 |
| 未来跨机器执行与故障接管 | Temporal、Conductor | 持久化状态、幂等、Worker 协议和版本兼容 |

**短期判断**：保持本地执行边界，优先参考 Task 的使用体验及明确的任务执行语义。只有实际需要容器复现、跨机器调度或长流程恢复时，再评估 Dagger 或持久化平台；不要仅因为它们“支持 DAG”就引入相应基础设施。

### 9. 最小验证方案与待确认事项

以下是后续选型的建议验证项，**本次没有执行**。只对满足场景硬约束的少量候选进行验证，不要求全部产品部署。

| 验证场景 | 需要观察的结果 |
|---|---|
| 菱形依赖 A → B/C → D，加一条独立分支 | A 按约定只执行一次；B/C 可并行；D 正确等待；独立分支策略明确 |
| 20 个就绪任务，并发上限设为 4 | 真正运行的任务数不超限；区分引擎任务、OS 进程和子进程数量 |
| 一个任务失败，另一个仍在运行 | 是否停止新任务、是否取消在途任务、是否执行清理、退出状态是否一致 |
| 中途停止 Worker 或控制面再恢复 | 已完成步骤是否复用；在途任务如何识别；外部副作用是否重复 |
| 同输入重跑、修改脚本、删除产物后重跑 | 缓存命中与失效符合预期，不把陈旧产物误判为成功 |
| 大量 stdout/stderr、超时、Ctrl+C、子进程 | 日志不阻塞执行，取消和清理边界清晰 |
| 运行中更新定义，然后重跑旧实例 | 定义、参数、Worker 版本与环境快照可解释 |
| 多项目共享资源与访问权限 | 资源池限流、凭据隔离、日志访问、付费功能边界明确 |

性能应至少分开记录冷启动、缓存命中、就绪到启动延迟、端到端耗时及控制面资源消耗，并明确硬件、执行后端、任务粒度和并发量。没有这些条件的“吞吐排名”不能支撑选型。

未验证事项包括：具体发行版在 JDK 25/Windows/Git Bash 上的兼容性、当前插件完整性、故障接管细节、企业版报价和真实资源开销。官方 `stable`/`latest` 页面会变化，正式采用时需要锁定版本并重新核对。

### 10. 官方参考资料

以下引用对应调研当天实际读取的官方资料。架构与功能描述以引用为依据；适用性与取舍结论为本报告分析。产品宣传中的无限扩展、吞吐优越或安全保证不作为独立验证结果。

| 编号 | 官方资料与用途 |
|---|---|
| S01 | [Task：依赖与任务调用](https://taskfile.dev/docs/guide/dependencies)；[功能指南](https://taskfile.dev/docs/guide/)——并发、失败、重复调用与本地执行 |
| S02 | [Dagger：Introduction](https://docs.dagger.io/getting-started/introduction/)——增量 DAG、沙箱函数与执行模型 |
| S03 | [Dagger：Built-In Caching](https://docs.dagger.io/0.21/features/caching/)——层、卷、函数缓存；该参考为版本化页面 |
| S04 | [Airflow：Architecture Overview](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/overview.html)——组件、依赖、Worker、XCom 与资源池 |
| S05 | [Dagster：Defining assets](https://dagster.io/docs/guides/build/assets/defining-assets)——资产依赖、op/graph 与代码版本 |
| S06 | [Dagster：Backfilling data](https://dagster.io/docs/guides/build/partitions-and-backfills/backfilling-data)——分区补数与运行组织 |
| S07 | [Dagster：OSS deployment architecture](https://dagster.io/docs/deployment/oss/oss-deployment-architecture)——服务、存储、执行器与副本限制 |
| S08 | [Prefect：Flows](https://www.prefect.io/docs/v3/concepts/flows)——Python 流程、状态与部署 |
| S09 | [Prefect：Tasks](https://www.prefect.io/docs/v3/concepts/tasks)——并发、缓存、依赖与最终一致性 |
| S10 | [DolphinScheduler：官方 README](https://github.com/apache/dolphinscheduler)；[公开 README API](https://api.github.com/repos/apache/dolphinscheduler/readme)——可视化、部署、多租户与补数；本次通过 API 读取内容 |
| S11 | [Argo Workflows：官方文档首页](https://argo-workflows.readthedocs.io/en/latest/)——Kubernetes、DAG、产物及执行控制 |
| S12 | [Nextflow：Overview](https://docs.seqera.io/nextflow/overview)——Process/Channel 与执行后端 |
| S13 | [Nextflow：Caching and resuming](https://docs.seqera.io/nextflow/cache-and-resume)——缓存键、恢复与工作目录 |
| S14 | [Kestra：官方文档](https://kestra.io/docs)——声明式与事件驱动编排 |
| S15 | [Kestra：Flow Dag](https://kestra.io/plugins/core/flow/io.kestra.plugin.core.flow.dag)——`dependsOn`、`concurrent` 与 UI 边界 |
| S16 | [Kestra：OSS vs. Enterprise](https://kestra.io/docs/oss-vs-paid)——权限、隔离、治理与执行后端的版本区别 |
| S17 | [Temporal：Workflow Execution](https://docs.temporal.io/workflow-execution)——持久化执行与重放 |
| S18 | [Temporal：Activity Definition](https://docs.temporal.io/activity-definition)——Activity、幂等与重试边界 |
| S19 | [Conductor OSS：官方文档](https://conductor-oss.github.io/conductor/)——任务模型、控制能力、当前维护与商业产品区别 |
| S20 | [Conductor OSS：Production Deployment](https://conductor-oss.github.io/conductor/devguide/running/deploy.html)——组件及不同数据库/队列/索引组合 |
| S21 | [Spring Batch：Reference](https://docs.spring.io/spring-batch/reference/index.html)；[Controlling Step Flow](https://docs.spring.io/spring-batch/reference/step/controlling-flow.html)——批处理与流程控制 |
| S22 | [Spring Batch：Configuring a JobRepository](https://docs.spring.io/spring-batch/reference/job/configuring-repository.html)——持久化与无资源模式的限制 |
| S23 | [LiteFlow：框架介绍](https://liteflow.cc/pages/5816c5/)——组件、规则、上下文与适用边界 |
| S24 | [AWS：What is Step Functions?](https://docs.aws.amazon.com/step-functions/latest/dg/welcome.html)——状态机与服务集成 |
| S25 | [AWS：Choosing workflow type](https://docs.aws.amazon.com/step-functions/latest/dg/choosing-workflow-type.html)——Standard/Express 语义、时长、计费与集成差异 |

## English

### 1. Executive conclusions

**There is no universally best DAG framework. Establish the execution boundary first: local commands, in-process components, data platforms, Kubernetes jobs, and durable cross-service workflows require different systems.**

| Primary requirement | First candidates | Main trade-off |
|---|---|---|
| Local scripts, checks, and builds | Task (go-task) | Lightweight and direct; no distributed durable execution |
| Containerized builds, local/CI consistency, incremental caching | Dagger | Strong reproducibility and caching; requires an engine and container environment |
| Python data scheduling with many integrations | Apache Airflow | Broad integration ecosystem; substantial platform and scheduling concepts |
| Data dependencies, partition backfills, asset observability | Dagster | Asset-oriented model fits data platforms; more concepts than a task list |
| Dynamic Python workflows and rapid automation | Prefect | Natural Python control flow; recorded states do not imply arbitrary process recovery |
| Visual data jobs, centralized operations, multi-tenancy | Apache DolphinScheduler | Extensive platform capabilities; distributed deployment and environment governance costs |
| Container batch jobs on existing Kubernetes | Argo Workflows | Natural container, resource, and artifact model; unsuitable for infrastructure-free local tools |
| Scientific computing, HPC, reproducible file pipelines | Nextflow | Strong execution backends and cache-based resumption; specialized DSL and domain model |
| Declarative multi-language automation and events | Kestra | Integrated configuration, plugins, and UI; enterprise access and some runners have edition boundaries |
| Cross-service long-running workflows and callbacks | Temporal | Strong durable execution; determinism and workflow upgrades require deliberate design |
| JSON workflows, multi-language workers, dynamic branches | Conductor OSS | Separates definitions from execution; server, persistence, and worker governance required |
| Java batch data processing, transactions, and chunks | Spring Batch | Mature batch semantics; not a turnkey general DAG scheduling platform |
| In-process Java component orchestration | LiteFlow | Embeddable and rule-driven; not a distributed durable workflow platform |
| Managed service orchestration within AWS | AWS Step Functions | Less self-operation; cloud coupling, quotas, and usage billing apply |

**For devpulse's current “local HOCON + Bash + one-shot DAG execution” positioning, Task is the closest direct comparison, while Dagger is a useful containerization reference. Topological ordering and bounded parallelism alone do not justify introducing Airflow, Temporal, or a Kubernetes platform.** This is a fit assessment based on the existing project documentation, not a code audit.

### 2. Scope and methodology

Research date: **2026-11-01**. This desk study covers 14 representative open-source projects or commercial managed services, using official documentation, repository descriptions, and edition comparisons. **No products were deployed, benchmarked, or subjected to fault injection.**

Here, DAG means tasks connected by acyclic dependencies. Workflow products may also support conditions, loops, waits, and callbacks. Temporal, Conductor, Step Functions, and LiteFlow are broader workflow systems capable of serving DAG use cases, rather than exclusively DAG engines.

Evaluation dimensions include workflow expression, execution boundary, infrastructure dependencies, concurrency and resource control, failure recovery, caching and reruns, observability, developer experience, and commercial feature boundaries. Terms such as “lightweight” and “high cost” are architecture-based qualitative judgments, not measured resource use or pricing rankings.

Flyte, Kubeflow Pipelines, Luigi, Azkaban, Dask, Ray, BPMN engines, and equivalent services from other cloud providers are not examined in detail. They remain relevant but emphasize ML platforms, traditional data scheduling, distributed computation, or business process modeling. GitHub stars, vendor throughput claims, and benchmarks without comparable conditions are not used as selection evidence.

### 3. Architecture and execution model comparison

| Product | Definition and primary execution unit | Typical runtime dependencies | Qualitative deployment burden |
|---|---|---|---|
| Task | YAML; local commands with `deps` | CLI and tools required by commands | Low |
| Dagger | Modules/APIs; lazily evaluated function and artifact DAG | Dagger Engine and container environment; optional remote engine/cloud | Medium |
| Airflow | Python DAGs and Operators/Tasks; worker execution | Scheduler, DAG Processor, API Server, metadata DB; backend-specific workers/components | Medium–high |
| Dagster | Python assets/ops/graphs; runs and steps | Webserver, daemon, code locations, state storage; configurable execution backends | Medium |
| Prefect | Python `flow`/`task`; runtime control flow | Direct local execution; centralized scheduling uses Server/Cloud and execution infrastructure | Low–medium |
| DolphinScheduler | Web DAG, API, Python SDK; job tasks | Standalone evaluation; production clusters include masters, workers, database, and other components | Medium–high |
| Argo Workflows | YAML/SDK; containers and Steps/DAG | Kubernetes and controller; optional server, artifact storage, archive DB | High; lower incremental burden with existing K8s |
| Nextflow | Specialized DSL; processes and channel dataflow | JVM, local tools or HPC/cloud backend; cache and work directories | Low–medium; cluster costs additional |
| Kestra | YAML, plugins, explicit DAG, and control flow | Server, state storage, execution environment; edition/deployment-specific extensions | Medium |
| Temporal | SDK workflow code; workflows and activities | Temporal Service/Cloud and workers; persistence backend for self-hosting | Medium–high |
| Conductor OSS | JSON/SDK; system tasks and external workers | Server, persistence, queue, index, lock; some roles can share a backend | Medium–high |
| Spring Batch | Java configuration; jobs, steps, chunks, flows | JVM/Spring; persistent JobRepository when durable recovery is required | Low–medium; depends on existing Spring adoption |
| LiteFlow | EL rules; Java/script components and context | JVM and application dependencies; optional external rule storage | Low |
| AWS Step Functions | Amazon States Language; states and service calls | AWS-managed control plane and invoked compute/services | Low self-operation; high cloud dependency |

“Supports Java” does not mean “embeddable as a Maven library without a server.” Temporal's Java SDK, Argo's Java client, and Conductor's Java workers still require their respective services. Spring Batch and LiteFlow are closer to in-process frameworks.

### 4. Strengths and limitations by framework

#### 4.1 Task (go-task)

**Strengths:** A straightforward CLI and YAML model suitable for replacing scattered build scripts. It supports concurrent dependencies, sequential task calls, concurrency limits, prefixed output, up-to-date checks, and cleanup. Basic execution needs no persistent control plane.[S01]

**Limitations:** It is not a distributed scheduler, and up-to-date checks are not durable checkpoint recovery. Shared dependencies are attempted on each call by default; configure `run: once` or `run: when_changed` to control repetition. This differs from globally executing each named DAG node once. Commands and shell behavior still need cross-platform review.[S01]

**Best fit:** Local checks, builds, release preparation, and small automation. Not tenant-isolated services, cross-machine failover, or durable long-running workflows.

#### 4.2 Dagger

**Strengths:** Containers, files, functions, and build artifacts share an incremental DAG, allowing local and CI pipelines to use the same model. Layer, volume, and function-call caching reduce repeated builds and dependency downloads.[S02][S03]

**Limitations:** It is not a lightweight executor relying only on an existing host shell. Engine, container environment, image, and cache management add responsibilities. External networks and side-effecting calls cannot be judged correct from caching alone; evaluate first runs and cache hits separately.

**Best fit:** Containerized builds, reproducible CI, and multi-language engineering pipelines. Date-based backfills, approvals, and durable business workflows are not its primary selection drivers.

#### 4.3 Apache Airflow

**Strengths:** Python DAGs explicitly express dependencies, while Operators/Providers integrate many external systems. UI, execution history, pools, and multiple executors support centralized scheduling and data-interval-oriented batch workloads.[S04]

**Limitations:** Full deployment has several components and a metadata database. DAG parsing, scheduling, execution, and version management require operations. Scheduling overhead should not be assumed negligible for fine-grained function calls. XCom is for small metadata; large artifacts belong in external storage, and tasks need not run on the same machine.[S04]

**Best fit:** Python data engineering teams, scheduled ETL/ELT, and integration-heavy platforms. Not the first choice for a local CLI or low-latency in-process execution.

#### 4.4 Dagster

**Strengths:** Models data assets and upstream dependencies, connecting tables, files, and materializations to execution. Supports partition backfills and asset code-version information while retaining op/graph abstractions.[S05][S06]

**Limitations:** Assets, partitions, resources, and executors add concepts beyond simple command flows. Full OSS deployment still requires webserver, daemon, code locations, and storage configuration. The current OSS architecture does not support daemon replicas; scalable workers do not imply active replication of every control-plane component.[S07]

**Best fit:** Data platforms emphasizing asset observability, dependency tracking, and partition recomputation. Limited benefit when the only requirement is ordering a few scripts.

#### 4.5 Prefect

**Strengths:** `@flow` and `@task` remain close to ordinary Python, making runtime conditions, loops, and dynamic composition natural. Tasks support retries, caching, concurrency, and timeouts; local calls and centrally deployed workflows are both available.[S08][S09]

**Limitations:** Flexible Python control flow may not yield a complete static DAG before execution. Task-state updates are eventually consistent. Unpersisted function or process state does not acquire Temporal-style recovery merely because the UI records history. Central deployments also require execution infrastructure management.[S08][S09]

**Best fit:** Python automation, dynamic data workloads, and rapidly iterating teams. Less compelling for strict static plan review or multi-language in-process execution.

#### 4.6 Apache DolphinScheduler

**Strengths:** Drag-and-drop DAGs, web management, API, Python SDK, multiple job types, backfills, workflow versions, and multi-tenancy. Its multi-master/multi-worker architecture supports centralized data scheduling.[S10]

**Limitations:** Cluster deployment, upgrades, worker environments, and permissions are more involved than a local tool. Visual authoring still needs reliable exports, version review, and controlled releases rather than UI-only changes. Vendor performance claims were not independently benchmarked here and do not establish superiority.

**Best fit:** Teams needing visual operations, centralized management, and diverse data jobs. Poor fit when a single executable JAR and offline local operation are primary requirements.

#### 4.7 Argo Workflows

**Strengths:** Native Kubernetes CRDs and container execution. Supports DAG/Steps, artifacts and parameters, resource configuration, retries, timeouts, concurrency limits, Cron, and archival. Isolation and resource scheduling leverage Kubernetes.[S11]

**Limitations:** Requires Kubernetes operations, RBAC, images, and artifact storage. Pod startup and control-plane interactions add overhead; measure suitability for very short tasks. Workflow objects, archive records, and artifacts have separate lifecycles, so deletion must not be assumed recoverable.

**Best fit:** Container batch processing, ML jobs, and infrastructure automation on existing Kubernetes. Usually unattractive for local commands without K8s.

#### 4.8 Nextflow

**Strengths:** Processes and channels compose scripts into dataflows. The same pipeline can target local, HPC, or cloud backends, making it suitable for large file-based workloads. `-resume` reuses task cache and outputs for iterative development and recovery of expensive computation.[S12][S13]

**Limitations:** Requires a specialized DSL and channel semantics; it is not an ordinary embedded JVM DAG library. Resumption requires matching cache entries and retained work-directory outputs. Removing either may cause recomputation. Central operations and team governance require evaluating surrounding platforms.

**Best fit:** Scientific computing, bioinformatics, HPC, and reproducible file pipelines. General microservice business orchestration is not its central strength.

#### 4.9 Kestra

**Strengths:** YAML and plugins unify multi-language scripts, integrations, and event-driven automation with a UI. The core DAG task uses `dependsOn` for dependencies and `concurrent` for parallelism limits.[S14][S15]

**Limitations:** Plugins, expressions, and complex YAML introduce maintenance costs. Current edition documentation places SSO, RBAC, multi-tenancy, some remote task runners, and governance features in Enterprise. Product-wide marketing must not be treated as default OSS capability. The DAG plugin documentation also states that low-code UI forms are currently disabled for this task.[S15][S16]

**Best fit:** Teams seeking centrally managed declarative multi-language automation. Confirm plugin, execution backend, and access requirements against the intended edition.

#### 4.10 Temporal

**Strengths:** Event history and deterministic replay recover workflow execution, supporting long waits, callbacks, and cross-service processes. Java and other SDKs are available. Worker failure need not lose recorded workflow progress.[S17]

**Limitations:** Workflow code must satisfy determinism constraints, external I/O belongs in activities or other supported boundaries, and upgrades must preserve replay compatibility. Activities can retry if completion was not successfully reported. External commands and business side effects therefore are not automatically exactly-once; idempotency remains necessary.[S17][S18]

**Best fit:** Cross-service processes such as orders and resource provisioning, with explicit recovery requirements. Usually excessive for parallelizing local scripts.

#### 4.11 Conductor OSS

**Strengths:** Separates workflow definitions from external workers, supporting multi-language services. Dynamic tasks, branches, subworkflows, loops, persisted state, retries, timeouts, pause, resume, and rerun controls are available.[S19]

**Limitations:** Requires server, state storage, queue, index, and production lock configuration. Worker polling, timeouts, duplicate task execution, and releases need governance. Current PostgreSQL-based configurations can simplify backends; the older assumption that Redis plus Elasticsearch is mandatory is inaccurate.[S20]

**Best fit:** Platforms using machine-readable definitions to orchestrate multi-language workers. Distinguish current Conductor OSS from Orkes commercial offerings; the old Netflix repository's status does not establish the current project's maintenance status.[S19]

#### 4.12 Spring Batch

**Strengths:** Jobs/steps, chunked reading and writing, transactions, retries, partitioning, and parallel processing fit large Java data workloads. A persistent JobRepository manages execution metadata and recovery information.[S21][S22]

**Limitations:** Not a complete scheduling platform. Calendar triggers, general cross-job dependency graphs, multi-tenant UI, and distributed governance typically require companion systems. The resourceless JobRepository documented currently stores no metadata, lacks restartability, and is not thread-safe; persistent recovery is not present in every configuration.[S22]

**Best fit:** Spring/Java imports, exports, cleansing, and transactional batches. Its data-processing abstractions may be unnecessary when merely wrapping external Bash commands.

#### 4.13 LiteFlow

**Strengths:** In-process Java components and rule-driven orchestration emphasize reuse, contextual data sharing, and flexible rules. Script components and external rule storage can help decompose complex business logic.[S23]

**Limitations:** Component orchestration is not external-process lifecycle management or cross-machine durable recovery. Process logs, exit codes, cancellation, execution records, and isolation need additional implementation. Its official positioning is also not role-based approval workflows.[S23]

**Best fit:** Java pricing, validation, and business component composition. First establish whether the objective is decomposing application logic or executing a script DAG.

#### 4.14 AWS Step Functions

**Strengths:** Managed state machines, visual execution records, and AWS integrations reduce self-operation. Supports branches, parallelism, Map, retries, and error handling; Standard supports job synchronization and callback patterns for specific integrations.[S24][S25]

**Limitations:** Cloud coupling, quotas, and ongoing usage costs matter. Standard charges by state transitions; Express by executions, duration, and memory, in addition to compute, logs, and storage. Standard runs for up to one year and Express for five minutes. Express does not support `.sync` or `.waitForTaskToken` patterns.[S25]

**Best fit:** AWS service orchestration and serverless pipelines. Standard's exactly-once workflow model does not remove explicit retries or external-call idempotency requirements. Asynchronous Express is at-least-once; synchronous Express is at-most-once. They must not be conflated.[S25]

### 5. Commonly confused execution semantics

| Concept | Correct interpretation | Selection question |
|---|---|---|
| Dependencies versus resource limits | The graph establishes readiness; resource limits determine admission | How do global, workflow, tenant, and pool limits interact? |
| Parallel versus distributed | Concurrent local execution is not cross-machine scheduling | Who takes over failed workers, and are environments consistent? |
| Retry versus recovery | Retry usually reruns a task; recovery may reuse steps or replay progress | Can side effects produced before recording completion repeat? |
| Cache versus restart | Conditional result reuse is not resumption of a running OS process | What are cache keys, retention, external-state, and invalidation rules? |
| History versus durable execution | Queryable history does not imply rescheduling after service restart | Is recovery automatic or a manual rerun/resume? |
| Fail-fast versus cancellation | Stopping admission, cancelling active work, and undoing side effects differ | How are independent branches, descendants, cleanup, and compensation handled? |
| Exactly-once versus business idempotency | Guarantees have boundaries; retries and network uncertainty can affect external effects | Are idempotency keys, result queries, deduplication, or compensation available? |
| Definition versus execution snapshot | Editing a definition does not safely change an active execution | Which version executes, and are parameters/environment retained for reruns? |

For external commands, verify shell, working directory, environment, log backpressure, timeouts, exit codes, cancellation, and child-process cleanup separately. Java virtual threads reduce the thread cost of blocking waits; **they do not replace limits on external process counts, CPU, memory, or external API quotas**.

### 6. Cost, governance, and commercial boundaries

| Category | Examples | Frequently overlooked costs |
|---|---|---|
| Local execution tools | Task, Dagger | Environment compatibility, container engine, cache disk, developer troubleshooting; Dagger cloud evaluated separately |
| In-process frameworks | Spring Batch, LiteFlow | Spring/rule integration, custom logging and operations UI, persistence, upgrade compatibility |
| Self-hosted data orchestration | Airflow, Dagster, DolphinScheduler, Prefect, Kestra | Database, control plane, workers, backup, upgrades, credentials, access governance |
| Container/HPC pipelines | Argo, Nextflow | Cluster/job backend, images, object storage, networking, work directories, artifact retention |
| Durable business workflow platforms | Temporal, Conductor | History storage, queues, workers, retry side effects, workflow version compatibility |
| Managed cloud state machines | Step Functions | Transition/execution billing, compute, logs, storage, networking, provider coupling |

Open source does not mean every enterprise feature is free, or that operations cost nothing. Check access, auditing, isolation, retention, and support separately for Dagster OSS/Dagster+, Prefect Server/Cloud, Temporal self-hosted/Cloud, Kestra OSS/Enterprise, Conductor OSS/Orkes, Nextflow/Seqera, and Dagger local engine/cloud.

This report does not provide a complete license audit, fixed commercial quotes, or SLA commitments. Before procurement or dependency adoption, check LICENSE files, commercial terms, security advisories, and runtime support for the **pinned release and plugins actually used**. JVM implementation is not a substitute for validating JDK 25 compatibility.

### 7. Narrowing candidates by scenario

| Scenario | Suggested initial shortlist | Deciding factors |
|---|---|---|
| Local task execution without a server | Task; existing lightweight implementation as baseline | Deduplication, pre-execution review, failure policy, cross-platform shell |
| Containerized local/CI engineering tasks | Dagger; Argo with existing K8s | Reproducibility, cold versus cached runs, container overhead |
| Scheduled data platform | Airflow, Dagster, DolphinScheduler | Integrations, asset/task model, backfills, operations style |
| Dynamic Python automation | Prefect, Dagster | Runtime dynamism, asset needs, rerun experience |
| Declarative multi-language automation | Kestra, Conductor | Plugins versus custom workers; edition and access boundaries |
| Scientific/HPC workloads | Nextflow | Backends, file/cache semantics, reproducibility |
| Java batch data processing | Spring Batch | Chunks, transactions, restart context, consistency |
| In-process Java business components | LiteFlow | Reuse, rule complexity, in-process latency |
| Cross-service long-running workflows | Temporal, Conductor; Step Functions within AWS | Recovery, callbacks, idempotency, upgrades, governance |

Do not start with a single overall score for all 14 products. Infrastructure, language, and recovery requirements are hard constraints. Eliminate incompatible candidates first, then compare two or three survivors using the same workload.

### 8. Relevance to devpulse

The project README describes local HOCON configuration, Bash commands, transitive and shared dependency deduplication, bounded concurrency, stopping new tasks after failure, pre-execution review, and local execution history. This section does not propose replacing the project with a large platform or claim that additional features are already implemented.

| Current or potential need | Most useful references | Mechanisms to study |
|---|---|---|
| Local commands and dependencies | Task | Sequential/parallel expression, explicit deduplication, output organization, cleanup policy |
| Containerization and reproducible engineering | Dagger | Artifact dependencies, environment packaging, cache keys and invalidation |
| Static visual review and execution navigation | Airflow, Argo, DolphinScheduler | States, upstream/downstream navigation, execution version display |
| Cached reuse and partial reruns | Nextflow, Dagster | Artifact existence, input/code versions, recomputation boundaries |
| Future cross-machine execution and failover | Temporal, Conductor | Durable state, idempotency, worker protocols, version compatibility |

**Near-term assessment:** Preserve the local execution boundary and prioritize Task's user experience and explicit execution semantics as references. Evaluate Dagger or durable platforms only when container reproducibility, cross-machine scheduling, or long-running recovery becomes a real requirement—not merely because they support DAGs.

### 9. Minimal validation plan and open questions

These are proposed future selection checks, **not tests performed in this study**. Validate only a small shortlist satisfying the scenario's hard constraints, not all products.

| Scenario | Expected observations |
|---|---|
| Diamond A → B/C → D plus an independent branch | A runs once under the selected policy; B/C overlap; D waits correctly; independent-branch policy is explicit |
| Twenty ready tasks, concurrency limit four | Running tasks stay bounded; distinguish engine tasks, OS processes, and subprocesses |
| One task fails while another is running | Admission, active cancellation, cleanup, and final status are consistent |
| Stop and restore worker/control plane mid-run | Completed-step reuse, handling of active tasks, duplicate external effects |
| Rerun identical inputs, change script, delete output | Correct cache hits/invalidation; stale outputs are not mistaken for success |
| Heavy stdout/stderr, timeout, Ctrl+C, child processes | Logs do not block execution; cancellation and cleanup boundaries are clear |
| Update definitions during a run, then rerun old instance | Definition, parameters, worker versions, and environment snapshots are explainable |
| Shared resources and access across projects | Pool limits, secret isolation, log access, and paid feature boundaries are clear |

Measure cold startup, cache hits, ready-to-start latency, end-to-end time, and control-plane resource use separately. State hardware, backend, task granularity, and concurrency. Throughput rankings without these conditions are not selection evidence.

Unverified items include release-specific JDK 25/Windows/Git Bash compatibility, complete plugin availability, failover details, enterprise quotes, and actual resource consumption. Official `stable`/`latest` pages change; pin releases and recheck before adoption.

### 10. Official references

These references were read during this study. Architecture and capability descriptions are source-based; fit and trade-off conclusions are this report's analysis. Vendor claims of unlimited scaling, superior throughput, or safety are not independent validation.

| ID | Official source and purpose |
|---|---|
| S01 | [Task: Dependencies and task calls](https://taskfile.dev/docs/guide/dependencies); [Guide](https://taskfile.dev/docs/guide/)—concurrency, failure, repeated calls, local execution |
| S02 | [Dagger: Introduction](https://docs.dagger.io/getting-started/introduction/)—incremental DAG, sandboxed functions, execution model |
| S03 | [Dagger: Built-In Caching](https://docs.dagger.io/0.21/features/caching/)—layer, volume, function caches; a versioned reference |
| S04 | [Airflow: Architecture Overview](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/overview.html)—components, dependencies, workers, XCom, pools |
| S05 | [Dagster: Defining assets](https://dagster.io/docs/guides/build/assets/defining-assets)—asset dependencies, ops/graphs, code versions |
| S06 | [Dagster: Backfilling data](https://dagster.io/docs/guides/build/partitions-and-backfills/backfilling-data)—partition backfills and runs |
| S07 | [Dagster: OSS deployment architecture](https://dagster.io/docs/deployment/oss/oss-deployment-architecture)—services, storage, executors, replica constraints |
| S08 | [Prefect: Flows](https://www.prefect.io/docs/v3/concepts/flows)—Python flows, states, deployment |
| S09 | [Prefect: Tasks](https://www.prefect.io/docs/v3/concepts/tasks)—concurrency, caching, dependencies, eventual consistency |
| S10 | [DolphinScheduler: Official README](https://github.com/apache/dolphinscheduler); [Public README API](https://api.github.com/repos/apache/dolphinscheduler/readme)—visual authoring, deployment, tenancy, backfills; content retrieved through the API |
| S11 | [Argo Workflows: Official documentation](https://argo-workflows.readthedocs.io/en/latest/)—Kubernetes, DAGs, artifacts, execution controls |
| S12 | [Nextflow: Overview](https://docs.seqera.io/nextflow/overview)—processes/channels and backends |
| S13 | [Nextflow: Caching and resuming](https://docs.seqera.io/nextflow/cache-and-resume)—cache keys, resumption, work directory |
| S14 | [Kestra: Documentation](https://kestra.io/docs)—declarative and event-driven orchestration |
| S15 | [Kestra: Flow Dag](https://kestra.io/plugins/core/flow/io.kestra.plugin.core.flow.dag)—`dependsOn`, `concurrent`, UI boundaries |
| S16 | [Kestra: OSS vs. Enterprise](https://kestra.io/docs/oss-vs-paid)—access, isolation, governance, runner edition differences |
| S17 | [Temporal: Workflow Execution](https://docs.temporal.io/workflow-execution)—durable execution and replay |
| S18 | [Temporal: Activity Definition](https://docs.temporal.io/activity-definition)—activities, idempotency, retry boundaries |
| S19 | [Conductor OSS: Documentation](https://conductor-oss.github.io/conductor/)—task model, controls, current maintenance, commercial distinction |
| S20 | [Conductor OSS: Production Deployment](https://conductor-oss.github.io/conductor/devguide/running/deploy.html)—components and database/queue/index combinations |
| S21 | [Spring Batch: Reference](https://docs.spring.io/spring-batch/reference/index.html); [Controlling Step Flow](https://docs.spring.io/spring-batch/reference/step/controlling-flow.html)—batch processing and flow control |
| S22 | [Spring Batch: Configuring a JobRepository](https://docs.spring.io/spring-batch/reference/job/configuring-repository.html)—persistent and resourceless repository boundaries |
| S23 | [LiteFlow: Introduction](https://liteflow.cc/pages/5816c5/)—components, rules, context, scope |
| S24 | [AWS: What is Step Functions?](https://docs.aws.amazon.com/step-functions/latest/dg/welcome.html)—state machines and integrations |
| S25 | [AWS: Choosing workflow type](https://docs.aws.amazon.com/step-functions/latest/dg/choosing-workflow-type.html)—Standard/Express semantics, duration, billing, integration differences |
