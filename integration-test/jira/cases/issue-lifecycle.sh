#!/bin/bash
# Read back creation, edit the summary, and verify the new value.
set -euo pipefail
run_cli issue view "$issue_key" --json --comments 0
assert_json "$CLI_OUT" '.key == $key and .fields.summary == $summary and (.fields.labels | index($label) != null)' \
	--arg key "$issue_key" --arg summary "Integration $run_id" --arg label "$run_id"
run_cli issue edit "$issue_key" --summary "Integration $run_id edited" --no-input
run_cli issue view "$issue_key" --json --comments 0
assert_json "$CLI_OUT" '.fields.summary == $summary' --arg summary "Integration $run_id edited"
run_cli issue comment add "$issue_key" "Integration comment $run_id" --no-input
run_cli issue view "$issue_key" --json --comments 10
assert_json "$CLI_OUT" '[.fields.comment.comments[].body | .. | strings | select(. == $comment)] | length >= 1' \
	--arg comment "Integration comment $run_id"
