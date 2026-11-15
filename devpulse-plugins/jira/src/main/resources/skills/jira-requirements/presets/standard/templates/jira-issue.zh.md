# Jira Issue 模板（metadata + description）

### 使用

`jira-issue.md` 提供包含 metadata 和 description 的完整 Issue 文档。填充下方代码块中的 YAML metadata 和描述正文，将整个代码块内容（不含围栏）保存为 `work\issue.md`，交给 `--template`。不上传本文的标题、使用说明、围栏、本地绝对路径或未替换的 `{{...}}`。

project、issuetype 和 summary 使用确认过的项目、实际支持的类型及标题，并正确引用、转义 YAML 字符串。priority、epic 和 story-point 留空表示省略；仅在用户确认且环境支持时填值，story-point 使用不带引号的数字。其他可编辑字段如 labels、components、parent、assignee 按需添加，不猜测经办人标识或自定义字段编号。bodyFormat 只标记描述格式，不影响 metadata。

描述由已确定规格提炼，保留需求与验收标准 ID。只生成所需语言的描述，需要双语时只组合 frontmatter 之后的正文，整个文档仍只有一份 metadata。不要复制敏感内部设计。使用标题、段落和列表，不依赖 HTML、相对链接、表格或 Jira v3 不支持的格式。

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

## 背景与目标
{{business_context_and_goal}}

## 需求范围
{{requirements_with_ids}}

## 非目标
{{out_of_scope}}

## 业务规则与约束
{{business_rules_and_constraints}}

## 验收标准
{{acceptance_criteria_with_ids_and_requirement_links}}

## 依赖、风险与待确认
{{dependencies_risks_and_open_questions}}
```
