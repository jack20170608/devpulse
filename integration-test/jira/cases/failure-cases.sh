#!/bin/bash
# Invalid arguments must fail before configuration lookup or network access.
set -euo pipefail
capture_cli --definitely-invalid-option
[[ "$CLI_STATUS" == 2 ]] || fail "Unknown option must return picocli exit code 2"
assert_contains "$CLI_ERR" "Unknown option"
capture_cli issue create --body one --template missing.md --no-input
[[ "$CLI_STATUS" != 0 ]] || fail "Conflicting body inputs unexpectedly succeeded"
assert_contains "$CLI_ERR" "Use either --body or --template, not both."
