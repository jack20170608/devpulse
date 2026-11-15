# Jira issue template (metadata + description)

### Usage

`jira-issue.md` provides a complete issue document with metadata and description. Fill the YAML metadata and description in the fenced block below, then save the entire block contents without fences as `work\issue.md` for `--template`. Never upload this document's title, instructions, fences, local absolute paths, or unresolved `{{...}}`.

Use the confirmed project, an actually supported issue type, and summary for project, issuetype, and summary, quoting and escaping YAML strings correctly. Empty priority, epic, and story-point mean omit; populate only when confirmed and supported, using an unquoted number for story-point. Add other editable fields such as labels, components, parent, or assignee only as needed; never guess user identifiers or custom-field IDs. bodyFormat selects description format only, never metadata parsing.

Derive the description from the settled specification, preserving requirement and acceptance-criterion IDs. Generate the required description language; for bilingual output, combine only the bodies after the frontmatter, retaining one metadata section. Do not copy sensitive internal designs. Use headings, paragraphs, and lists without HTML, relative links, tables, or formatting unsupported by Jira v3.

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
