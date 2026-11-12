#!/bin/bash
# Offline executable and command-registration checks.
set -euo pipefail
run_cli --help
assert_contains "$CLI_OUT" "Usage: jira"
run_cli --version
[[ -n "$CLI_OUT" ]] || fail "Version output is empty"
run_cli issue create --help
assert_contains "$CLI_OUT" "--template"
run_cli issue view --help
assert_contains "$CLI_OUT" "--json"
capture_cli attach
[[ "$CLI_STATUS" == 2 ]] || fail "Missing attachment arguments must return exit code 2"
assert_contains "$CLI_ERR" "Usage: jira attach"
