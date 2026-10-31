#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

mvn --batch-mode --no-transfer-progress --quiet \
	--file "$SCRIPT_DIR/pom.xml" package

exec java -jar "$SCRIPT_DIR/target/devpulse.jar" "$@"
