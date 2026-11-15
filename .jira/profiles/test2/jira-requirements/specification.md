# Specification template

### Usage

Fill the template body in the language required by the project, without outer fences or instructions. Give functional requirements IDs such as `FR-001` and link `AC-001` acceptance criteria to them. Mark unknowns as questions rather than inventing answers. Mark Jira key/URL as not created initially, then record actual returned values after creation.

### Template

```markdown
# {{title}}

- Status: {{draft_or_confirmed_or_synchronized}}
- Jira: {{jira_key_or_not_created}}
- Jira URL: {{jira_url_or_not_created}}

## Context and problem
{{context_and_problem}}

## Goals and success measures
{{goals_and_success_measures}}

## Scope
{{in_scope}}

## Non-goals
{{out_of_scope}}

## User scenarios and business rules
{{scenarios_and_business_rules}}

## Functional requirements
{{functional_requirements_with_ids}}

## Nonfunctional constraints and dependencies
{{constraints_and_dependencies}}

## Acceptance criteria
{{acceptance_criteria_linked_to_requirements}}

## Assumptions, risks, and open questions
{{assumptions_risks_and_open_questions}}

## Jira synchronization record
{{actual_sync_status_and_reviewed_change_scope}}
```
