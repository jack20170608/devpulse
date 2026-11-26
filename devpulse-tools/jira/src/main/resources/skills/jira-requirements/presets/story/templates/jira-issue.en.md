# Story issue template

Use the complete block below as `issue.md` in the requirement folder (such as `.jira\requirements\draft-<yyyyMMdd-HHmmss>\issue.md`). Select an issue type supported by the project; `{{...}}` values must be resolved before submission.

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

## User story
As a {{user_or_role}}, I want {{capability}}, so that {{benefit}}.

## Context and goals
{{business_context_and_goal}}

## Scope
{{in_scope}}

## Non-goals
{{out_of_scope}}

## Business rules and constraints
{{business_rules_and_constraints}}

## Acceptance criteria
- AC-001: {{observable_and_testable_outcome}}
- AC-002: {{observable_and_testable_outcome}}

## Dependencies, risks, and open questions
{{dependencies_risks_and_open_questions}}
```
