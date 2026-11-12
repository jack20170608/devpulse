#!/bin/bash
# Upload a synthetic file and verify its metadata on this run's issue.
set -euo pipefail
attachment_name="$run_id.txt"
printf 'Synthetic integration attachment: %s\n' "$run_id" >"$run_dir/$attachment_name"
run_cli attach "$issue_key" --file "$run_dir/$attachment_name"
run_cli issue view "$issue_key" --json --comments 0
assert_json "$CLI_OUT" '[.fields.attachment[] | select(.filename == $name and .size > 0)] | length == 1' \
	--arg name "$attachment_name"
