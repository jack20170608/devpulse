#!/bin/bash
# Verify persisted Wiki or ADF rather than command success alone.
set -euo pipefail
run_cli issue edit "$issue_key" --template "$SCRIPT_DIR/fixtures/issue.md" --no-input
run_cli issue view "$issue_key" --json --comments 0
if [[ "$api_version" == 2 ]]; then
	assert_json "$CLI_OUT" '.fields.description | type == "string"'
	assert_json "$CLI_OUT" '.fields.description | contains("h3. 中文") and contains("*Integration description*")'
	run_cli issue edit "$issue_key" --template "$SCRIPT_DIR/fixtures/table.md" --no-input
	run_cli issue view "$issue_key" --json --comments 0
	assert_json "$CLI_OUT" '.fields.description | contains("||Environment||Status||") and contains("|SIT|PASS|")'
else
	assert_json "$CLI_OUT" '.fields.description.type == "doc" and .fields.description.version == 1'
	assert_json "$CLI_OUT" '[.fields.description.content[] | select(.type == "heading")] | length == 2'
	assert_json "$CLI_OUT" '[.fields.description.content[] | select(.type == "paragraph") | .content[] | select(.type == "text" and .text == "Integration description") | .marks[] | select(.type == "strong")] | length == 2'
fi
