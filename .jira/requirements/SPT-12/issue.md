---
project: "SPT"
issuetype: "New Feature"
summary: "devpulse-core：支持普通 Java DAG 任务检查点持久化与断点恢复 / Checkpoint persistence and recovery for Java DAG tasks"
epic: "SPT-4"
bodyFormat: markdown
---

[中文](#中文) | [English](#english)

## 中文

### 背景与目标

devpulse-core 已提供本地静态 DAG 的依赖校验、并发调度、重试、超时与取消。通用 Java 任务的运行状态和结果目前保存在内存中；已有 Bash 运行历史不提供通用结果恢复。

本需求增加普通 Java DAG 任务的检查点持久化和进程重启后的断点恢复：复用完整检查点中的成功节点及结果，只重新调度其余节点，避免重复执行已可靠记录的成功任务。

### 需求范围

- R1：提供显式启用的本地运行记录与检查点能力，保存运行标识、记录版本、工作流身份、节点状态、尝试次数、时间及可恢复的成功结果。
- R2：由调用方显式提供结果编解码器，支持恢复后的类型校验和合法的 null 结果；缺少编解码器、编码失败或解码失败必须明确报错。
- R3：支持在进程重启后加载完整检查点，校验工作流一致性，复用成功节点及结果，重新调度所有非成功节点。
- R4：提供检查点完整性保护和同一运行的独占恢复控制，明确处理损坏、版本不兼容、持久化故障和运行所有权不确定。
- R5：同步更新模块中英文使用说明与架构文档，提供调用示例及可重复的集成验证步骤。

### 非目标

不支持 Daemon 恢复、Bash 进程接管、分布式调度、动态改图或从任意 Java 调用栈继续执行。不修改现有 Bash 运行历史与恢复行为，不增加 Pulse CLI 恢复命令，不提供自动回滚、后台恢复服务或 exactly-once 副作用保证。

### 业务规则与约束

- 持久化为显式启用能力；未启用时保持现有 DAG 执行行为和 API 使用方式。
- 仅复用最新完整检查点中记录为 SUCCESS 且结果可正确解码的节点。其他状态，包括 PENDING、RUNNING、RETRYING、FAILED、TIMED_OUT、CANCELLED、SKIPPED，均按原依赖关系重新调度；重试次数按本次恢复重新计数，原执行记录仍保留。
- 恢复必须校验节点身份、依赖、结果类型及影响执行语义的策略。调用方提供显式工作流版本标识，用于识别无法从 Lambda 自动推导的实现变化；不一致时在执行任何任务前拒绝恢复。
- 包含 Daemon 的工作流恢复必须明确拒绝，不能将历史就绪状态当作当前可用状态；不得接管或重启历史 Bash 进程。
- 检查点不可半写入后被当作有效记录；失败更新不得破坏上一个完整检查点。任务启动前记录本次尝试，成功状态与编码结果作为一致的检查点发布，不能先发布 SUCCESS 再补写结果。
- 持久化故障时阻止新任务调度，协作式取消未完成任务并向调用方报告运行级失败；不能静默退化为仅内存运行。
- 同一运行只能有一个活动执行或恢复者；锁定失败、原执行者仍存活或所有权无法确认时拒绝恢复。
- 检查点提交前已经产生副作用的任务可能被重跑；调用方必须确保可重跑任务幂等或自行处理副作用。不得因结果不可解码而静默重跑已记录成功的节点。
- 保持 JDK 25、单 JVM 本地静态 DAG 的模块边界；DAG 调度层不引入 CLI、工具配置或任意对象的默认 Java 原生反序列化。

### 验收标准

- AC1（R1、R2、R3）：构建 A → B → C，A 返回下游使用的类型化结果；在 A 的成功检查点提交后中断进程。新进程恢复时 A 不执行，B、C 读取恢复结果并完成；通过执行计数和最终结果验证。
- AC2（R2）：验证普通值和 null 的恢复；缺少编解码器、编码异常、解码异常或结果类型不匹配均明确报错，不执行依赖无效结果的下游，不静默重跑成功节点。
- AC3（R3）：分别构造各非成功状态的检查点，验证恢复后按依赖和并发上限重新调度，重试次数重新计数；成功分支不重跑，原尝试记录可追溯。
- AC4（R3、R4）：修改节点、依赖、结果类型、执行策略或工作流版本后，恢复在任务启动前失败；包含 Daemon 的工作流同样明确拒绝恢复。
- AC5（R1、R4）：在检查点写入、替换及成功结果提交阶段注入故障；读取只能得到完整记录或明确错误，不能得到半写入状态。任务副作用完成但检查点未提交时允许幂等重跑，不能宣称 exactly-once。
- AC6（R4）：两个进程同时恢复同一运行时至多一个获得执行权；原运行仍活动或所有权不确定时不启动恢复任务。
- AC7（R1、R4）：运行中持久化失败时不再启动新任务，协作式取消未完成任务并报告失败；记录损坏或版本不支持时拒绝加载，不覆盖原记录。
- AC8（R5）：中英文文档给出编解码器、工作流版本、启用持久化及新进程恢复示例，并解释幂等性和中断边界；通过集成验证覆盖 AC1–AC7，确认未启用持久化的普通任务、Daemon 和 Bash 历史行为保持不变。

### 依赖、风险与待定事项

- 调用方需要提供结果编解码器、稳定的工作流版本及幂等任务实现。结果可能包含敏感信息，持久化内容需由调用方审核；不自动采集环境变量或凭据。
- 存储格式、具体公共 API 及记录保留方式由实现设计确定，必须满足上述兼容性、完整性和错误语义；首期不包含自动清理策略。
- 验证计划：在 JDK 25 的本地环境执行跨进程恢复、故障注入、并发恢复和现有行为回归；本仓库不新增单元测试。
- 验证负责人及具体执行环境待安排；状态：未执行。暂无执行结果或证据，不代表验证通过。
- 生产验证与回退安排待使用方确认；本条目不授权部署或生产测试。没有阻碍本需求定义的待确认业务问题。

## English

### Context and goals

devpulse-core already provides dependency validation, concurrent scheduling, retries, timeouts, and cancellation for local static DAGs. Generic Java task states and results currently remain in memory; existing Bash run history does not recover generic results.

Add checkpoint persistence and recovery after process restart for ordinary Java DAG tasks: reuse successful nodes and results from a complete checkpoint and reschedule only the remaining nodes, avoiding repeated execution of reliably recorded successful tasks.

### Requirement scope

- R1: Provide explicitly enabled local run records and checkpoints, storing run identity, record version, workflow identity, node states, attempt counts, timestamps, and recoverable successful results.
- R2: Require explicitly supplied result codecs, with restored type validation and support for valid null results; missing codecs, encoding failures, and decoding failures must be reported explicitly.
- R3: Load a complete checkpoint after process restart, validate workflow consistency, reuse successful nodes and results, and reschedule every non-successful node.
- R4: Protect checkpoint integrity and enforce exclusive recovery ownership, explicitly handling corruption, incompatible versions, persistence failures, and uncertain run ownership.
- R5: Update bilingual module usage and architecture documentation with invocation examples and reproducible integration verification steps.

### Non-goals

No daemon recovery, Bash process adoption, distributed scheduling, dynamic graph changes, or continuation of arbitrary Java call stacks. Do not change existing Bash run-history or recovery behavior, add Pulse CLI recovery commands, or provide automatic rollback, background recovery services, or exactly-once side-effect guarantees.

### Business rules and constraints

- Persistence is explicitly enabled; existing DAG execution behavior and API usage remain unchanged when disabled.
- Reuse only SUCCESS nodes with correctly decodable results in the latest complete checkpoint. Reschedule all other states, including PENDING, RUNNING, RETRYING, FAILED, TIMED_OUT, CANCELLED, and SKIPPED, under the original dependencies. Restart retry counting for this recovery while retaining original execution records.
- Validate node identities, dependencies, result types, and execution-affecting policies before recovery. Require an explicit caller-supplied workflow version to identify implementation changes that cannot be inferred from Lambdas; reject mismatches before executing any task.
- Explicitly reject recovery of workflows containing daemons; historical readiness is not current availability. Do not adopt or restart historical Bash processes.
- Never accept a partially written checkpoint as valid; a failed update must preserve the previous complete checkpoint. Record attempts before task startup and publish successful state with its encoded result consistently, never publishing SUCCESS before its result.
- Persistence failures stop new scheduling, cooperatively cancel unfinished tasks, and report a run-level failure; never silently fall back to memory-only execution.
- Allow only one active executor or recovery owner per run; reject recovery when locking fails, the original executor remains alive, or ownership cannot be confirmed.
- Tasks whose side effects occur before checkpoint commit may run again; callers must ensure idempotence or handle side effects themselves. Never silently rerun recorded successful nodes because results cannot be decoded.
- Preserve the JDK 25, single-JVM, local static-DAG boundaries. Do not introduce CLI concerns, tool configuration, or default native Java deserialization of arbitrary objects into DAG scheduling.

### Acceptance criteria

- AC1 (R1, R2, R3): Build A → B → C with A returning a typed downstream result; interrupt the process after A's successful checkpoint commits. In a new process, recovery does not execute A; B and C consume the restored result and complete. Verify execution counts and final results.
- AC2 (R2): Verify ordinary and null result recovery. Missing codecs, encoding exceptions, decoding exceptions, and type mismatches produce explicit errors; downstream tasks depending on invalid results do not execute and successful nodes are not silently rerun.
- AC3 (R3): Construct checkpoints for each non-successful state and verify dependency-aware rescheduling, concurrency limits, and reset retry counts. Successful branches are not rerun and original attempts remain traceable.
- AC4 (R3, R4): Changes to nodes, dependencies, result types, execution policies, or workflow version cause recovery to fail before task startup. Workflows containing daemons also explicitly reject recovery.
- AC5 (R1, R4): Inject failures during checkpoint writing, replacement, and successful-result commit. Reads return only complete records or explicit errors, never partial state. Idempotent replay is allowed when side effects finish before checkpoint commit; no exactly-once claim is made.
- AC6 (R4): When two processes recover the same run concurrently, at most one gains execution ownership. Recovery starts no tasks while the original run is active or ownership is uncertain.
- AC7 (R1, R4): Runtime persistence failures prevent new task startup, cooperatively cancel unfinished tasks, and report failure. Corrupt or unsupported records are rejected without overwriting the original.
- AC8 (R5): Bilingual documentation demonstrates codecs, workflow versions, persistence enablement, and recovery in a new process, explaining idempotence and interruption boundaries. Integration verification covers AC1–AC7 and confirms unchanged ordinary-task, daemon, and Bash-history behavior when persistence is disabled.

### Dependencies, risks, and open questions

- Callers must supply result codecs, stable workflow versions, and idempotent task implementations. Results may contain sensitive information; callers must review persisted content. Do not automatically collect environment variables or credentials.
- Storage format, concrete public APIs, and record retention are implementation-design decisions subject to the compatibility, integrity, and error contracts above. Automatic cleanup policies are excluded from the first release.
- Verification plan: perform cross-process recovery, fault injection, concurrent recovery, and existing-behavior regression in a local JDK 25 environment; do not add unit tests to this repository.
- Verification owners and concrete execution environments are pending assignment. Status: Not run. No results or evidence are available; verification has not passed.
- Production verification and rollback arrangements require confirmation from adopters; this issue authorizes neither deployment nor production testing. No unresolved business questions block this requirement definition.
