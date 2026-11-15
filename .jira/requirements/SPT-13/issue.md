---
project: "SPT"
issuetype: "Task"
summary: "调研 AI / LLM 工作流领域的 DAG 开源项目 / Research open-source DAG projects for AI / LLM workflows"
bodyFormat: markdown
---

[中文](#中文) | [English](#english)

## 中文

### What

R-1：调研当前 AI / LLM 工作流领域的 DAG 开源项目，形成候选项目清单、能力对比及推荐结论。不包含实现、部署或性能实测。

### Why

为理解可用方案及后续选型提供可追溯的依据；本任务不预设最终采用的项目。

### Impact

不开展调研可能导致方案比较缺乏依据，遗漏许可证、维护状态或适用场景方面的限制。上述风险为待验证判断。

### Description

基于公开仓库和官方文档筛选候选项目。统一比较 DAG 编排能力、模型与工具集成、可观测性、部署方式、许可证和维护活跃度，区分 DAG 能力与非 DAG 扩展能力。标注资料访问日期、版本或提交依据及未知项，不把文档声明当作实测结果。

#### 验收标准

AC-1（R-1）：交付候选项目清单，包含仓库或官方文档链接及入选理由。

AC-2（R-1）：对每个候选项目覆盖上述六个比较维度，注明依据和未知项；许可证以实际许可证文件为依据，维护活跃度注明观察时间范围。

AC-3（R-1）：给出适用场景、主要限制及有依据的推荐结论，明确哪些结论仍需后续验证。

### Notes

调研范围为 AI / LLM 工作流，不包含通用数据管道项目的全面盘点。负责人、完成日期及特定落地场景待确认；不自动指派负责人、关联 Epic 或加入 Sprint。仅使用公开资料，不上传内部数据或凭据。

### SIT

计划：检查 AC-1、AC-2 的资料引用与比较一致性。负责人待确认；环境为公开仓库与文档。状态：未执行；结果：无；证据：无。

### UAT

计划：由需求方评审 AC-1 至 AC-3，确认报告满足调研范围。负责人待确认；环境为调研报告。状态：未执行；结果：无；证据：无。

### PRD（生产环境验证）

计划：确认交付物仅为报告，不产生生产变更。负责人待确认；不访问生产环境。防护：不部署、不调用生产服务；如后续涉及生产验证，须另行确认防护和回滚方案。状态：未执行；结果：无；证据：无。

### Regression test

计划：报告修订后复核 AC-1 至 AC-3 的引用、比较维度和结论一致性。负责人待确认；环境为调研报告与公开资料。状态：未执行；结果：无；证据：无。

### Regression test method

使用公开资料逐项核对候选项目来源、六个比较维度及推荐理由，检查修订是否导致引用失效、信息遗漏或结论矛盾；不使用生产或个人数据。

### Regression test justification

本任务仅交付调研报告，回归范围集中于证据完整性和结论一致性；实现、部署及性能验证不在本任务范围内。

### IT verification

计划：技术评审 AC-2、AC-3，复核 DAG 能力、集成限制及推荐依据。负责人待确认；环境为公开资料与调研报告。状态：未执行；结果：无；证据：无。

### Business verification

计划：评审 AC-1 至 AC-3，确认报告能够支持方案理解与后续选型。负责人待确认；环境为调研报告。状态：未执行；结果：无；证据：无。

### Used by mistake

## English

### What

R-1: Research current open-source DAG projects for AI / LLM workflows and deliver a candidate list, capability comparison, and recommendations. Implementation, deployment, and measured performance testing are excluded.

### Why

Provide traceable evidence for understanding available options and future selection; this task does not predetermine a project to adopt.

### Impact

Without this research, comparisons may lack evidence and overlook licensing, maintenance, or suitability constraints. These risks are hypotheses to be validated.

### Description

Select candidates using public repositories and official documentation. Compare DAG orchestration, model and tool integration, observability, deployment options, licensing, and maintenance activity consistently, distinguishing DAG capabilities from non-DAG extensions. Record access dates, version or commit references, and unknowns; do not present documentation claims as measured results.

#### Acceptance criteria

AC-1 (R-1): Deliver a candidate list with repository or official documentation links and selection rationale.

AC-2 (R-1): Cover all six comparison dimensions for each candidate, recording evidence and unknowns. Base licensing analysis on actual license files and specify the observation period for maintenance activity.

AC-3 (R-1): Provide suitable scenarios, principal limitations, and evidence-based recommendations, identifying conclusions requiring further validation.

### Notes

The scope is AI / LLM workflows, not an exhaustive survey of general-purpose data pipelines. Owner, deadline, and specific adoption scenario remain unconfirmed. Do not automatically assign an owner, link an Epic, or join a Sprint. Use public sources only; do not upload internal data or credentials.

### SIT

Plan: Check source references and comparison consistency for AC-1 and AC-2. Owner unconfirmed; environment: public repositories and documentation. Status: Not run. Result: None. Evidence: None.

### UAT

Plan: Have the requester review AC-1 through AC-3 against the research scope. Owner unconfirmed; environment: research report. Status: Not run. Result: None. Evidence: None.

### PRD (Production verification)

Plan: Confirm that delivery is report-only with no production changes. Owner unconfirmed; no production access. Safeguards: no deployment or production service calls. Any later production verification requires separate confirmation of safeguards and rollback arrangements. Status: Not run. Result: None. Evidence: None.

### Regression test

Plan: Recheck references, comparison dimensions, and conclusion consistency for AC-1 through AC-3 after report revisions. Owner unconfirmed; environment: research report and public sources. Status: Not run. Result: None. Evidence: None.

### Regression test method

Use public sources to verify candidate origins, all six comparison dimensions, and recommendation rationale. Check revisions for broken references, omissions, or contradictory conclusions. Do not use production or personal data.

### Regression test justification

This task delivers only a research report, so regression coverage focuses on evidence completeness and conclusion consistency. Implementation, deployment, and performance validation are outside scope.

### IT verification

Plan: Technically review AC-2 and AC-3 for DAG capabilities, integration limitations, and recommendation evidence. Owner unconfirmed; environment: public sources and research report. Status: Not run. Result: None. Evidence: None.

### Business verification

Plan: Review AC-1 through AC-3 to confirm that the report supports understanding options and future selection. Owner unconfirmed; environment: research report. Status: Not run. Result: None. Evidence: None.

### Used by mistake

