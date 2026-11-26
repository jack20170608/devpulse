# Bug issue template

Use the complete block below as `issue.md` in the requirement folder (such as `.jira\requirements\draft-<yyyyMMdd-HHmmss>\issue.md`). Select an issue type supported by the project and replace all `{{...}}` values before submission.

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

## Problem
{{concise_observed_problem}}

## Expected behavior
{{expected_behavior}}

## Actual behavior
{{actual_behavior}}

## Reproduction steps
1. {{reproduction_step}}
2. {{reproduction_step}}

## Impact and scope
{{affected_users_workflows_and_frequency}}

## Environment
{{relevant_versions_and_conditions}}

## Acceptance criteria
- AC-001: {{observable_and_testable_fix}}
- AC-002: {{regression_prevention}}

## Evidence, dependencies, and open questions
{{evidence_dependencies_and_open_questions}}
```
