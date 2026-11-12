#!/bin/bash
# Assertions shared by Bash integration suites.
set -euo pipefail

fail() {
	echo "integration: $*" >&2
	exit 1
}

assert_contains() {
	[[ "$1" == *"$2"* ]] || fail "Expected output to contain: $2"
}

assert_json() {
	local json="$1" filter="$2"
	shift 2
	jq -e "$@" "$filter" <<<"$json" >/dev/null || fail "JSON assertion failed: $filter"
}
