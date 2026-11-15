# 缺陷 Issue 模板

将下方完整代码块内容保存为 `work\issue.md`。选择项目实际支持的 Issue 类型，并在提交前替换所有 `{{...}}`。

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

## 问题描述
{{观察到的问题}}

## 预期行为
{{预期行为}}

## 实际行为
{{实际行为}}

## 复现步骤
1. {{复现步骤}}
2. {{复现步骤}}

## 影响范围
{{受影响的用户、流程和发生频率}}

## 环境信息
{{相关版本和条件}}

## 验收标准
- AC-001：{{可观察且可验证的修复结果}}
- AC-002：{{防止问题回归的验证}}

## 证据、依赖与待确认问题
{{证据、依赖和待确认问题}}
```
