# Jira description template

### Usage

Project the settled specification into this business description, preserving requirement and acceptance-criterion IDs. Generate only the required language's template body, not navigation, fences, instructions, or local absolute paths; combine corresponding bodies when bilingual output is required. Do not copy sensitive internal designs. Use headings, paragraphs, and lists without HTML, relative links, tables, or formatting unsupported by Jira v3. Preserve unrelated content in existing issues.

### Template

```markdown
## Context and goals
{{business_context_and_goal}}

## Requirement scope
{{requirements_with_ids}}

## Non-goals
{{out_of_scope}}

## Business rules and constraints
{{business_rules_and_constraints}}

## Acceptance criteria
{{acceptance_criteria_with_ids_and_requirement_links}}

## Dependencies, risks, and open questions
{{dependencies_risks_and_open_questions}}
```
