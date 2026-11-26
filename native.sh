#!/usr/bin/env bash

set -euo pipefail

usage() {
	cat <<'USAGE'
Usage: bash native.sh [--verify] [--clean] [--quiet]

Build the combined compatibility CLI as a GraalVM native executable.

  --verify  Run the native smoke checks after building.
  --clean   Rebuild the CLI JAR from a clean state first.
  --quiet   Reduce Maven output.

Requires native-image, either on PATH or under $GRAALVM_HOME/bin. The JAR is
still the complete artifact; the native executable is an additional build.

Output: devpulse-cli/target/devpulse-native
USAGE
}

verify=false
clean=false
quiet=false
while (($#)); do
	case "$1" in
		--verify) verify=true ;;
		--clean) clean=true ;;
		--quiet) quiet=true ;;
		-h|--help) usage; exit 0 ;;
		*) echo "native: unknown argument: $1" >&2; usage >&2; exit 2 ;;
	esac
	shift
done

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

if [[ -n "${GRAALVM_HOME:-}" && -x "${GRAALVM_HOME}/bin/native-image" ]]; then
	native_image="${GRAALVM_HOME}/bin/native-image"
else
	native_image="$(command -v native-image || true)"
fi
if [[ -z "$native_image" ]]; then
	echo "native: native-image was not found. Set GRAALVM_HOME or put it on PATH." >&2
	exit 1
fi

jar="${SCRIPT_DIR}/devpulse-cli/target/devpulse.jar"
binary="${SCRIPT_DIR}/devpulse-cli/target/devpulse-native"

build_args=()
if [[ "$clean" == true ]]; then
	build_args+=(--clean)
fi
if [[ "$quiet" == true ]]; then
	build_args+=(--quiet)
fi
bash "${SCRIPT_DIR}/build.sh" cli "${build_args[@]}"

# The reachability metadata lives in the CLI resources, so native-image reads it
# from the JAR; no extra configuration directory is needed here.
"$native_image" -jar "$jar" -o "$binary" --no-fallback

# A native build only fails at runtime on a code path the metadata missed, so the
# smoke checks are the real gate for this artifact.
smoke() {
	local work
	work="$(mktemp -d)"
	# shellcheck disable=SC2064
	trap "'$binary' stop --directory '$work' >/dev/null 2>&1 || true; rm -rf '$work'" EXIT

	cat >"$work/.devpulse.yaml" <<'YAML'
version: 1
parallelism: 2
tasks:
  hello:
    cmd: "echo hello"
  mid:
    cmd: "echo mid"
    needs: [hello]
  fail:
    cmd: "exit 3"
  srv:
    type: daemon
    cmd: "sleep 30"
    ready:
      cmd: "true"
      interval: 200ms
      timeout: 5s
    stopTimeout: 2s
  use:
    cmd: "echo use"
    needs: [srv]
pipelines:
  dev:
    tasks: [mid]
  daemon:
    tasks: [use]
YAML

	expect() {
		local expected="$1" label="$2"
		shift 2
		local actual=0
		"$@" >"$work/last.out" 2>&1 || actual=$?
		if [[ "$actual" != "$expected" ]]; then
			echo "native smoke: $label expected exit $expected, got $actual" >&2
			sed -n '1,20p' "$work/last.out" >&2
			return 1
		fi
		echo "  ok  $label"
	}

	echo "native smoke: ${binary}"
	expect 0 "version" "$binary" --version
	if ! grep -q "devpulse" "$work/last.out"; then
		echo "native smoke: --version printed no version" >&2
		return 1
	fi
	expect 0 "help" "$binary" --help
	expect 0 "run pipeline" "$binary" pulse dev --directory "$work"
	expect 0 "status" "$binary" status --directory "$work"
	expect 0 "status --tools" "$binary" status --tools
	expect 0 "dry run" "$binary" pulse dev --directory "$work" --dry-run
	expect 0 "review" "$binary" review dev --directory "$work"
	expect 0 "daemon lifecycle" "$binary" pulse daemon --directory "$work"
	expect 0 "stop" "$binary" stop --directory "$work"
	expect 12 "unknown target" "$binary" pulse absent --directory "$work"
	expect 30 "task failure" "$binary" pulse fail --directory "$work"
	echo "native smoke: all checks passed"
}

if [[ "$verify" == true ]]; then
	smoke
fi

echo "native: $binary"
