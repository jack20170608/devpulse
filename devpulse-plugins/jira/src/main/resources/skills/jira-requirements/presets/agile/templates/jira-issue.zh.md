# 敏捷交付 Issue 模板（metadata + description）

### 使用

`jira-issue.md` 提供包含 metadata 和 description 的完整 Issue 文档。填充下方代码块中的 YAML metadata 和描述正文，将整个代码块内容（不含围栏）保存为 ??????? `issue.md`?? `.jira\requirements\draft-<yyyyMMdd-HHmmss>\issue.md`?，交给 `--template`。不上传本文的标题、使用说明、围栏、本地绝对路径或未替换的 `{{...}}`。

project、issuetype 和 summary 使用确认过的项目、实际支持的类型及标题，并正确引用、转义 YAML 字符串。priority、epic 和 story-point 留空表示省略；仅在用户确认且环境支持时填值，story-point 使用不带引号的数字。其他可编辑字段如 labels、components、parent、assignee 按需添加，不猜测经办人标识或自定义字段编号。bodyFormat 只标记描述格式，不影响 metadata。

描述根据已确认的需求整理，保留需求与验收标准 ID。只生成所需语言的描述，需要双语时只组合 frontmatter 之后的正文，整个文档仍只有一份 metadata。不要复制敏感内部设计。使用标题、段落和列表，不依赖 HTML、相对链接、表格或 Jira v3 不支持的格式。

保留生命周期章节的原有顺序，按以下规则填写：

- 做什么：已确认的变更、需求 ID、范围及明确排除的内容。
- 为什么：业务问题、预期收益，以及已知时可量化的目标。
- 影响：不实施该需求可能导致的后果，例如业务或用户损失、风险、成本或错失的机会。以已确认的信息为依据，标明不确定的后果；不要在此描述实施变更本身的影响。
- 详细描述：业务规则、行为、约束，以及带稳定 ID、关联需求且可观察的验收标准。
- 备注：已确认的假设、受变更影响的系统和用户、依赖、兼容性、运行及发布风险与待确认问题。未知事实明确标记为待确认，不编造；发布依赖这些事实的变更前，先澄清关键问题。
- SIT：系统集成测试。UAT：用户验收测试。PRD：生产环境验证，不是产品需求文档。
- 回归测试：回归范围与执行记录。回归测试方法：可复现的步骤、前置条件及获准使用的测试数据。回归测试依据：所选覆盖如何应对受影响的系统和变更风险，以及排除内容可以接受的原因。
- IT 验证：由负责的技术负责人进行技术验证。业务验证：由负责的业务负责人验证业务结果。
- 误用：手动维护。新建时保持该节为空，除非用户提供内容；更新时原样保留已有标题和内容，不自动填写、翻译或改写。

SIT、UAT、PRD、回归测试、IT 验证与业务验证均记录计划与验收标准 ID、负责人及环境、状态、实际结果和证据链接。状态包括未执行、执行中、通过、失败、阻塞或不适用。新建模板默认未执行；有计划不代表已执行。只有确认原因后才能标记不适用。明确记录阻塞或部分覆盖情况。不虚报通过，不编造负责人、执行记录、时间或证据。除非用户确认有新记录支持的变更，否则保留已有结果和证据。

证据必须引用实际 Jira 附件或获准使用的绝对 URL，不包含凭据、个人数据、本地文件路径或通过内嵌 token 授权访问的链接。证据应能追溯至所验证的标准与实际环境。PRD 计划必须说明生产环境保护措施及回滚或恢复安排。这些章节不构成执行测试、部署、上传附件或写入 Jira 的授权；这些操作需有用户的具体请求或确认。

下方是新建模板，不是编辑基线。编辑必须从严格导出副本开始，保留 key、updated、originalFields、descriptionSha256、mode、apiVersion、bodyFormat 等原始基线，仅应用确认过的可编辑 metadata 和正文差异，不用新建模板覆盖整个文件。v2 Wiki 基线改写为 Markdown 正文时显式使用 `--desc-format markdown`；只改字段时不切换格式，现有 Issue 的无关内容必须保留。

### 模板

```markdown
---
project: "{{project_key}}"
issuetype: "{{issue_type_name}}"
summary: "{{confirmed_summary}}"
priority: ""
epic: ""
story-point: ""
bodyFormat: markdown
---

## 做什么
{{what}}

## 为什么
{{why}}

## 影响
{{impact_if_not_implemented}}

## 详细描述
{{description}}

### 验收标准
{{acceptance_criteria_with_ids_and_requirement_links}}

## 备注
{{notes}}

## SIT（系统集成测试）
- 计划与验收标准：{{sit_plan_and_acceptance_criteria}}
- 负责人及环境：{{sit_owner_and_environment}}
- 状态：未执行。
- 结果：未执行，暂无结果。
- 证据：尚未记录。

## UAT（用户验收测试）
- 计划与验收标准：{{uat_plan_and_acceptance_criteria}}
- 负责人及环境：{{uat_owner_and_environment}}
- 状态：未执行。
- 结果：未执行，暂无结果。
- 证据：尚未记录。

## PRD（生产环境验证）
- 计划与验收标准：{{prd_plan_and_acceptance_criteria}}
- 负责人及环境：{{prd_owner_and_environment}}
- 保护措施及回滚或恢复：{{prd_safeguards_and_recovery}}
- 状态：未执行。
- 结果：未执行，暂无结果。
- 证据：尚未记录。

## 回归测试
- 计划与验收标准：{{regression_test_plan_and_acceptance_criteria}}
- 负责人及环境：{{regression_test_owner_and_environment}}
- 状态：未执行。
- 结果：未执行，暂无结果。
- 证据：尚未记录。

## 回归测试方法
{{regression_test_method}}

## 回归测试依据
{{regression_test_justification}}

## IT 验证
- 计划与验收标准：{{it_verification_plan_and_acceptance_criteria}}
- 负责人及环境：{{it_verification_owner_and_environment}}
- 状态：未执行。
- 结果：未执行，暂无结果。
- 证据：尚未记录。

## 业务验证
- 计划与验收标准：{{business_verification_plan_and_acceptance_criteria}}
- 负责人及环境：{{business_verification_owner_and_environment}}
- 状态：未执行。
- 结果：未执行，暂无结果。
- 证据：尚未记录。

## 误用

```
