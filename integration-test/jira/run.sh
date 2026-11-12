#!/bin/bash
# Build once, run offline or explicitly authorized live Jira scenarios.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
source "$SCRIPT_DIR/../common/assertions.sh"

live=false
selected=all
config=""
project=""
issue_type=Task
api_version=""
skip_build=false
issue_key=""
run_dir=""
command_index=0
CLI_OUT=""
CLI_ERR=""
CLI_STATUS=0

usage() {
	cat <<'USAGE'
Usage: bash integration-test/run.sh jira [options]
  --case all|smoke|failure-cases|issue-lifecycle|description-rendering|attachments
  --live                 Authorize creation, editing and deletion in a test project
  --config FILE          Explicit connection configuration (required for --live)
  --project KEY          Dedicated test project (required for --live)
  --api-version 2|3      Expected Jira body format (required for --live)
  --type NAME            Creatable issue type (default: Task)
  --skip-build           Use an existing Jira JAR
Default: offline smoke and failure cases; no credentials or Jira server required.
USAGE
}

while (($#)); do
	case "$1" in
		--live) live=true; shift ;;
		--skip-build) skip_build=true; shift ;;
		--case|--config|--project|--api-version|--type)
			(($# >= 2)) && [[ -n "$2" && "$2" != --* ]] || fail "$1 requires a value"
			case "$1" in
				--case) selected="$2" ;;
				--config) config="$2" ;;
				--project) project="$2" ;;
				--api-version) api_version="$2" ;;
				--type) issue_type="$2" ;;
			esac
			shift 2 ;;
		-h|--help) usage; exit 0 ;;
		*) fail "Unknown option: $1" ;;
	esac
done
case "$selected" in
	all|smoke|failure-cases) ;;
	issue-lifecycle|description-rendering|attachments)
		[[ "$live" == true ]] || fail "$selected requires --live" ;;
	*) fail "Unknown case: $selected" ;;
esac
command -v java >/dev/null || fail "Java 25+ is required"
if [[ "$live" == true ]]; then
	command -v jq >/dev/null || fail "jq is required for live JSON assertions"
	[[ -f "$config" ]] || fail "--live requires an existing --config FILE"
	[[ "$project" =~ ^[A-Z][A-Z0-9_]*$ ]] || fail "--live requires a dedicated --project KEY"
	[[ "$api_version" == 2 || "$api_version" == 3 ]] || fail "--api-version must be 2 or 3"
	[[ -n "${JIRA_API_TOKEN:-}" ]] || fail "Set JIRA_API_TOKEN before live testing"
	config="$(cd -- "$(dirname -- "$config")" && pwd)/$(basename -- "$config")"
elif [[ -n "$config" || -n "$project" || -n "$api_version" ]]; then
	fail "Connection options require --live"
fi

redact() {
	local text="$1"
	if [[ -n "${JIRA_API_TOKEN:-}" ]]; then
		text="${text//"$JIRA_API_TOKEN"/[REDACTED]}"
	fi
	printf '%s\n' "$text"
}

capture_cli() {
	command_index=$((command_index + 1))
	if java -Dpicocli.ansi=false -jar "$PROJECT_ROOT/devpulse-plugins/jira/target/devpulse-jira.jar" \
		"${connection[@]}" "$@" </dev/null >"$run_dir/current.out" 2>"$run_dir/current.err"; then
		CLI_STATUS=0
	else
		CLI_STATUS=$?
	fi
	CLI_OUT="$(<"$run_dir/current.out")"
	CLI_ERR="$(<"$run_dir/current.err")"
	redact "$CLI_OUT" >"$run_dir/$command_index.stdout"
	redact "$CLI_ERR" >"$run_dir/$command_index.stderr"
	printf '%s\n' "$CLI_STATUS" >"$run_dir/$command_index.exit-code"
	rm -f -- "$run_dir/current.out" "$run_dir/current.err"
}

run_cli() {
	capture_cli "$@"
	[[ "$CLI_STATUS" == 0 ]] || fail "CLI failed ($CLI_STATUS); see command $command_index in $run_dir"
}

cleanup() {
	local status=$?
	trap - EXIT
	if [[ -n "$issue_key" ]]; then
		if [[ "$issue_key" == "$project"-* ]]; then
			capture_cli issue delete "$issue_key" --no-input
			if [[ "$CLI_STATUS" != 0 ]]; then
				echo "integration: cleanup failed; retained issue: $issue_key" >&2
				printf '%s\n' "$issue_key" >"$run_dir/retained-issue.txt"
				if [[ "$status" == 0 ]]; then status=1; fi
			fi
		else
			echo "integration: refusing deletion outside test project: $issue_key" >&2
			status=1
		fi
	fi
	if [[ -n "$run_dir" ]]; then
		printf 'exit-code=%s\n' "$status" >>"$run_dir/summary.txt"
		echo "integration: results: $run_dir"
	fi
	exit "$status"
}

connection=()
if [[ "$live" == true ]]; then connection=(-c "$config"); fi
umask 077
mkdir -p -- "$SCRIPT_DIR/../output"
run_dir="$(mktemp -d "$SCRIPT_DIR/../output/jira-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")"
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
run_id="$(basename -- "$run_dir")"
printf 'suite=jira\nlive=%s\ncase=%s\n' "$live" "$selected" >"$run_dir/summary.txt"
if [[ "$skip_build" == false ]]; then
	bash "$PROJECT_ROOT/build.sh" jira --quiet
fi
[[ -f "$PROJECT_ROOT/devpulse-plugins/jira/target/devpulse-jira.jar" ]] || fail "Jira JAR not found; build first"

run_case() {
	echo "integration: $1"
	source "$SCRIPT_DIR/cases/$1.sh"
	printf 'passed=%s\n' "$1" >>"$run_dir/summary.txt"
}

if [[ "$selected" == all || "$selected" == smoke ]]; then run_case smoke; fi
if [[ "$selected" == all || "$selected" == failure-cases ]]; then run_case failure-cases; fi
if [[ "$live" == true && "$selected" != smoke && "$selected" != failure-cases ]]; then
	run_cli issue create --project "$project" --type "$issue_type" \
		--summary "Integration $run_id" --label devpulse-integration \
		--label "$run_id" --template "$SCRIPT_DIR/fixtures/issue.md" --no-input
	if [[ "$CLI_OUT" =~ ^Created\ ([A-Z][A-Z0-9_]*-[0-9]+)$ ]]; then
		issue_key="${BASH_REMATCH[1]}"
	else
		fail "Cannot identify created issue; inspect command $command_index and remove any orphan manually"
	fi
	printf '%s\n' "$issue_key" >"$run_dir/created-issue.txt"
	[[ "$issue_key" == "$project"-* ]] || fail "Created key does not match the test project"
	for case_name in issue-lifecycle description-rendering attachments; do
		if [[ "$selected" == all || "$selected" == "$case_name" ]]; then run_case "$case_name"; fi
	done
fi
