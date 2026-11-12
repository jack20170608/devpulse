#!/bin/bash

set -euo pipefail

unset JAVA_TOOL_OPTIONS

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT=${SCRIPT_DIR}/../

bash "$PROJECT_ROOT/build.sh" jira --quiet

exec java -jar \
	"$PROJECT_ROOT/devpulse-plugins/jira/target/devpulse-jira.jar" "$@"
