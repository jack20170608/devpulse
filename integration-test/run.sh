#!/bin/bash
# Dispatch integration suites without depending on the caller's directory.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
case "${1:-jira}" in
	jira) if (($#)); then shift; fi; exec bash "$SCRIPT_DIR/jira/run.sh" "$@" ;;
	-h|--help) echo "Usage: bash integration-test/run.sh [jira] [suite options]" ;;
	*) echo "integration: unknown tool: $1" >&2; exit 2 ;;
esac
