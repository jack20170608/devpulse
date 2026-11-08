#!/usr/bin/env bash

set -euo pipefail

usage() {
	cat <<'USAGE'
Usage: bash build.sh [all|pulse|gather|gatherer|jira|depmap|cli|dist] [--clean] [--quiet]

Build all modules by default, or select a tool and its Maven dependencies.
cli builds the combined compatibility CLI; dist builds the Pulse and Jira
archives. --clean removes selected modules' previous build outputs first.
USAGE
}

target=all
selected=false
clean=false
quiet=false
while (($#)); do
	case "$1" in
		all|pulse|gather|gatherer|jira|depmap|cli|dist)
			if [[ "$selected" == true ]]; then
				echo "build: select exactly one target." >&2
				exit 2
			fi
			target="$1"
			selected=true
			;;
		--clean) clean=true ;;
		--quiet) quiet=true ;;
		-h|--help) usage; exit 0 ;;
		*) echo "build: unknown argument: $1" >&2; usage >&2; exit 2 ;;
	esac
	shift
done

command -v mvn >/dev/null 2>&1 || {
	echo "build: Maven was not found on PATH." >&2
	exit 1
}

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
args=(--batch-mode --no-transfer-progress --file "$SCRIPT_DIR/pom.xml")
if [[ "$quiet" == true ]]; then
	args+=(--quiet)
fi

case "$target" in
	all) ;;
	cli) args+=(--projects :devpulse-cli --also-make) ;;
	dist) args+=(--projects :devpulse-dist --also-make) ;;
	*) args+=(--projects ":$target" --also-make) ;;
esac

goals=(package)
if [[ "$clean" == true ]]; then
	goals=(clean package)
fi
exec mvn "${args[@]}" "${goals[@]}"
