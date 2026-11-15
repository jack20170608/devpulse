# Agile delivery issue template (metadata + description)

### Usage

`jira-issue.md` provides a complete issue document with metadata and description. Fill the YAML metadata and description in the fenced block below, then save the entire block contents without fences as `issue.md` in the requirement folder (such as `.jira\requirements\draft-<yyyyMMdd-HHmmss>\issue.md`) for `--template`. Never upload this document's title, instructions, fences, local absolute paths, or unresolved `{{...}}`.

Use the confirmed project, an actually supported issue type, and summary for project, issuetype, and summary, quoting and escaping YAML strings correctly. Empty priority, epic, and story-point mean omit; populate only when confirmed and supported, using an unquoted number for story-point. Add other editable fields such as labels, components, parent, or assignee only as needed; never guess user identifiers or custom-field IDs. bodyFormat selects description format only, never metadata parsing.

Derive the description from confirmed requirements, preserving requirement and acceptance-criterion IDs. Generate the required description language; for bilingual output, combine only the bodies after the frontmatter, retaining one metadata section. Do not copy sensitive internal designs. Use headings, paragraphs, and lists without HTML, relative links, tables, or formatting unsupported by Jira v3.

Keep the lifecycle sections in their original order. Populate them as follows:

- What: the confirmed change, requirement IDs, scope, and explicit exclusions.
- Why: the business problem, intended benefit, and measurable goal when known.
- Impact: consequences if the requirement is not implemented, such as business or user harm, risks, costs, or missed opportunities. Base these on confirmed information and label uncertain outcomes; do not describe the impact of implementing the change here.
- Description: business rules, behavior, constraints, and observable acceptance criteria with stable IDs linked to requirements.
- Notes: confirmed assumptions, systems and users affected by the change, dependencies, compatibility, operational and rollout risks, and open questions. Mark unknown facts as pending confirmation rather than inventing them; clarify critical questions before publishing dependent changes.
- SIT: system integration testing. UAT: user acceptance testing. PRD: production-environment verification, not a product requirements document.
- Regression test: regression scope and execution record. Regression test method: reproducible steps, prerequisites, and approved test data. Regression test justification: why the selected coverage addresses the affected systems and change risks, and why any exclusions are acceptable.
- IT verification: technical verification by the responsible technical owner. Business verification: business-outcome verification by the responsible business owner.
- Used by mistake: manually maintained. Leave the section empty on creation unless the user supplies its content. During updates, preserve its existing heading and content verbatim; do not automatically populate, translate, or rewrite it.

For SIT, UAT, PRD, Regression test, IT verification, and Business verification, record the plan and acceptance-criterion IDs, owner and environment, status, actual result, and evidence links. Statuses are Not run, In progress, Passed, Failed, Blocked, or Not applicable. New templates default to Not run; a plan is not an execution result. Use Not applicable only with a confirmed reason. Record blockers or partial coverage explicitly. Never claim a pass or fabricate owners, executions, timestamps, or evidence. Preserve existing results and evidence unless the user confirms a change supported by new records.

Evidence must reference actual Jira attachments or approved absolute URLs; do not include credentials, personal data, local filesystem paths, or links granting access through embedded tokens. Keep evidence traceable to the verified criteria and actual environment. PRD plans must identify production safeguards and rollback or recovery arrangements. These sections do not authorize tests, deployments, attachment uploads, or Jira writes; such actions require the user's concrete request or confirmation.

The following is a creation template, not an edit baseline. Editing starts from a strict export copy, retaining original key, updated, originalFields, descriptionSha256, mode, apiVersion, bodyFormat, and other baseline metadata. Apply only confirmed editable metadata and body changes instead of replacing the file with a creation template. When rewriting a v2 Wiki baseline body as Markdown, explicitly use `--desc-format markdown`; never switch formats for fields-only edits, and preserve unrelated existing content.

### Template

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

## What
{{what}}

## Why
{{why}}

## Impact
{{impact_if_not_implemented}}

## Description
{{description}}

### Acceptance criteria
{{acceptance_criteria_with_ids_and_requirement_links}}

## Notes
{{notes}}

## SIT
- Plan and acceptance criteria: {{sit_plan_and_acceptance_criteria}}
- Owner and environment: {{sit_owner_and_environment}}
- Status: Not run.
- Result: Not run; no result available.
- Evidence: None recorded.

## UAT
- Plan and acceptance criteria: {{uat_plan_and_acceptance_criteria}}
- Owner and environment: {{uat_owner_and_environment}}
- Status: Not run.
- Result: Not run; no result available.
- Evidence: None recorded.

## PRD (Production verification)
- Plan and acceptance criteria: {{prd_plan_and_acceptance_criteria}}
- Owner and environment: {{prd_owner_and_environment}}
- Safeguards and rollback or recovery: {{prd_safeguards_and_recovery}}
- Status: Not run.
- Result: Not run; no result available.
- Evidence: None recorded.

## Regression test
- Plan and acceptance criteria: {{regression_test_plan_and_acceptance_criteria}}
- Owner and environment: {{regression_test_owner_and_environment}}
- Status: Not run.
- Result: Not run; no result available.
- Evidence: None recorded.

## Regression test method
{{regression_test_method}}

## Regression test justification
{{regression_test_justification}}

## IT verification
- Plan and acceptance criteria: {{it_verification_plan_and_acceptance_criteria}}
- Owner and environment: {{it_verification_owner_and_environment}}
- Status: Not run.
- Result: Not run; no result available.
- Evidence: None recorded.

## Business verification
- Plan and acceptance criteria: {{business_verification_plan_and_acceptance_criteria}}
- Owner and environment: {{business_verification_owner_and_environment}}
- Status: Not run.
- Result: Not run; no result available.
- Evidence: None recorded.

## Used by mistake

```
