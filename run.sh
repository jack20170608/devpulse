#!/bin/bash

set -euo pipefail

unset JAVA_TOOL_OPTIONS

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

mvn --batch-mode --no-transfer-progress --quiet \
	--file "$SCRIPT_DIR/pom.xml" --projects devpulse-core \
	--also-make package

exec java -jar "$SCRIPT_DIR/devpulse-core/target/devpulse.jar" "$@"
