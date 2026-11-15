# 用户故事 Issue 模板

将下方完整代码块内容保存为 `work\issue.md`。选择项目实际支持的 Issue 类型；提交前必须替换所有 `{{...}}`。

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

## 用户故事
作为{{用户或角色}}，我希望{{能力}}，以便{{收益}}。

## 背景与目标
{{business_context_and_goal}}

## 范围
{{in_scope}}

## 非目标
{{out_of_scope}}

## 业务规则与约束
{{business_rules_and_constraints}}

## 验收标准
- AC-001：{{可观察且可验证的结果}}
- AC-002：{{可观察且可验证的结果}}

## 依赖、风险与待确认问题
{{dependencies_risks_and_open_questions}}
```
