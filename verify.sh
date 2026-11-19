#!/usr/bin/env bash

set -euo pipefail

usage() {
	cat <<'USAGE'
Usage: bash verify.sh [--clean] [--quiet]

Run the complete machine gate: Spotless format check, full compilation, every
test, and SpotBugs static analysis. Exits non-zero when any gate fails.

  --clean  Run `mvn clean` first. Required after changing compiler settings,
           because Maven incremental compilation can skip recompiling sources
           whose own content did not change, leaving stale bytecode in the JAR.
  --quiet  Reduce Maven output; useful when only the tail needs reading.
USAGE
}

clean=false
quiet=false
while (($#)); do
	case "$1" in
		--clean) clean=true ;;
		--quiet) quiet=true ;;
		-h|--help) usage; exit 0 ;;
		*) echo "verify: unknown argument: $1" >&2; usage >&2; exit 2 ;;
	esac
	shift
done

command -v mvn >/dev/null 2>&1 || {
	echo "verify: Maven was not found on PATH." >&2
	exit 1
}

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
args=(--batch-mode --no-transfer-progress --file "$SCRIPT_DIR/pom.xml")
if [[ "$quiet" == true ]]; then
	args+=(--quiet)
fi

goals=(verify)
if [[ "$clean" == true ]]; then
	goals=(clean verify)
fi

exec mvn "${args[@]}" "${goals[@]}"
