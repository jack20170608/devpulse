# Jira 描述模板

### 使用

将已确定规格投影为以下业务描述，保留需求与验收标准 ID。只生成所需语言的模板正文，不上传导航、围栏、使用说明或本地绝对路径；需要双语时组合对应正文。不要复制敏感内部设计。使用标题、段落和列表，不依赖 HTML、相对链接、表格或 Jira v3 不支持的格式。现有 Issue 的无关内容必须保留。

### 模板

```markdown
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
