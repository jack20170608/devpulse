# 规格说明书模板

### 使用

填充以下模板正文，按项目要求选择语言；不要复制外层代码围栏和使用说明。每条功能需求使用 `FR-001` 等 ID，验收标准使用 `AC-001` 并关联需求。未知事项明确列为待确认，不编造结论；Jira 创建前 key/链接标为未创建，成功后记录真实返回值。

### 模板

```markdown
# {{title}}

- 状态：{{draft_or_confirmed_or_synchronized}}
- Jira：{{jira_key_or_not_created}}
- Jira 链接：{{jira_url_or_not_created}}

## 背景与问题
{{context_and_problem}}

## 目标与成功指标
{{goals_and_success_measures}}

## 范围
{{in_scope}}

## 非目标
{{out_of_scope}}

## 用户场景与业务规则
{{scenarios_and_business_rules}}

## 功能需求
{{functional_requirements_with_ids}}

## 非功能约束与依赖
{{constraints_and_dependencies}}

## 验收标准
{{acceptance_criteria_linked_to_requirements}}

## 假设、风险与未决问题
{{assumptions_risks_and_open_questions}}

## Jira 同步记录
{{actual_sync_status_and_reviewed_change_scope}}
```
