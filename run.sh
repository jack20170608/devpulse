#!/bin/bash

set -euo pipefail

unset JAVA_TOOL_OPTIONS

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

bash "$SCRIPT_DIR/build.sh" cli --quiet

exec java -jar "$SCRIPT_DIR/devpulse-cli/target/devpulse.jar" "$@"
