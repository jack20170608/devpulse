#!/bin/bash

set -euo pipefail

unset JAVA_TOOL_OPTIONS

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

bash "$SCRIPT_DIR/build.sh" jira --quiet

exec java -jar \
	"$SCRIPT_DIR/devpulse-plugins/jira/target/devpulse-jira.jar" "$@"
