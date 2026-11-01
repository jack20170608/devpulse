#!/bin/bash

set -euo pipefail

unset JAVA_TOOL_OPTIONS

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

mvn --batch-mode --no-transfer-progress --quiet \
	--file "$SCRIPT_DIR/pom.xml" --projects :devpulse-jira \
	--also-make package

exec java -jar \
	"$SCRIPT_DIR/devpulse-plugins/jira/target/devpulse-jira.jar" "$@"
