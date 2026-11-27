# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

devpulse is a local DevOps toolbox built on Java 21 and Maven. It ships independent CLIs:

- **Pulse** — runs tasks/pipelines defined in `.devpulse.yaml` through Bash, with a DAG scheduler, run history, and local daemons.
- **Jira** — standalone Jira REST client (issue create/view/update/transition, comments, attachments).
- **`devpulse`** — the unified CLI: `init`, `run`, `review`, `status`, `stop`, `doctor`. The `run` command executes Pulse's task graph.

Java 21+ is required to build and run. Actual Bash execution (Pulse, `doctor`) also requires Bash; on Windows use Git Bash, never CMD/PowerShell/WSL as a substitute. `init`, `review`, `--dry-run`, history queries, and Jira do not need Bash.

## Commands

```bash
bash build.sh                 # build all modules
bash build.sh pulse|jira|devpulse|dist [--clean] [--quiet]
bash run.sh --help            # rebuild combined CLI, then run it

bash verify.sh                # THE machine gate: Spotless check + full compile + all tests + SpotBugs
bash verify.sh --clean        # mandatory after changing compiler settings or dependency versions
mvn spotless:apply            # apply google-java-format instead of hand-formatting
```

Build outputs:
- Combined CLI: `devpulse/target/devpulse.jar`
- Standalone tool: `devpulse-tools/jira/target/devpulse-jira.jar` (Pulse's commands ship inside `devpulse.jar`)
- Release archives: `devpulse-dist/target/` (devpulse and Jira)

Run a single test (artifact ID `pulse`/`jira` is unprefixed; keep `-am` so reactor dependencies build):

```bash
mvn --batch-mode -pl :devpulse-storage -am test -Dtest=RunStoreTest -Dsurefire.failIfNoSpecifiedTests=false
mvn --batch-mode -pl :devpulse-core -am test -Dtest=DagWorkFlowTest -Dsurefire.failIfNoSpecifiedTests=false
mvn --batch-mode -pl :pulse -am test -Dtest=PulseConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Integration scripts: `integration-test/jira/run-jira.sh` runs the built Jira JAR. `integration-test/jira/jira-creation-test.bash` performs live Jira reads/writes with an installed `jira` CLI — never run it without explicit authorization and a configured Jira instance. Neither is part of `verify.sh`. CI (`.github/workflows/verify.yml`) runs `bash verify.sh` on JDK 21 and 25.

## Architecture

### Reactor layout

The root `pom.xml` is the only place the project version is defined (`<revision>`); child `<parent><version>` uses `${revision}`, and internal dependencies use `${project.version}`. `devpulse-dependencies` manages external dependency versions, `devpulse-bom` manages consumable internal versions. The Flatten plugin (`resolveCiFriendliesOnly`) keeps installed POMs free of unresolved `${revision}`.

Dependency direction:
- Shared libraries: `devpulse-common` (HTTP/TLS helpers), `devpulse-storage` (run persistence), `devpulse-core` (DAG engine, Bash adapter, environment collection), `devpulse-execution` (YAML configuration, plan assembly, fingerprints, and `RunService`), `devpulse-server` (loopback HTTP daemon).
- `devpulse-pulse` holds Pulse's commands as a library for the unified CLI; `devpulse-tools` aggregates independent tools and currently holds only `jira`. Tools may depend on shared libraries but **never on each other**.
- Libraries register no CLI commands and there is no dynamic plugin loading.
- `devpulse` is the unified entry point: its commands wrap Pulse's task graph. `devpulse-dist` packages Pulse and Jira only. `devpulse-poc` is an experimental module, not shipped.

Core does not read Pulse YAML. Pulse owns YAML config, plan assembly, CLI commands, and presentation; core owns graph planning, scheduling, process execution, and persistence.

### Pulse execution flow

`.devpulse.yaml` (strict YAML 1.2, single mapping document) → `PulseConfiguration` parses with SnakeYAML Engine and converts the AST into Typesafe Config for typed reads/validation → `PulsePlan` resolves the target and delegates dependency closure, cycle detection, and validation to core's `DagGraph` (iterative Kahn) → `DagWorkflow` binds sealed `DagTask`/`DagDaemon` actions supplied by `BashTaskAdapter` → `DagRun` schedules virtual threads from a ready queue using dependency counters and cooperative cancellation → `WorkflowRun` consumes events, records history, and summarizes results.

Failure semantics: a failed batch task skips its descendants while independent branches continue; a daemon's readiness releases its startup slot, but later health loss cancels its active dependents and skips unstarted ones. Do not infer daemons from result types; the lifecycle is explicit. Pulse maps results to categorized exit codes (0, 2, 10–13, 20, 30–32, 40, 130) documented in `devpulse-pulse/README.md`.

### Runtime state and storage

Run state lives in `.devpulse/state.mv.db` (H2 via JDBI); `.devpulse/runs/<run-id>/` keeps the immutable `plan.conf`, the executor lock, task logs, and daemon scripts. `.devpulse/` is generated and gitignored. `--dry-run` creates no run record.

`devpulse-storage` exposes `RunStore` plus record types, implemented by `JdbiRunStore`. Schema SQL lives under `devpulse-storage/src/main/resources/db/migration/` and is applied in order by `Schema.java`. The build ships one baseline, `V1__central-store.sql`, which is the whole schema rather than an upgrade step: a layout change edits it and old stores are deleted, and the file drops every table first so a leftover store is rebuilt rather than blended. `SchemaTest` fails when `VERSION` and the file count disagree. Once stores hold data worth keeping, add numbered files and bump `VERSION` instead of editing the baseline. A store whose version is newer than the build is refused and told to delete `.devpulse`. The DAO layer (`dao/*Dao`, `BaseDao`/`BaseDaoJdbiImpl`) derives snake_case columns from record components via `TableDescription`; a key record's component names must match the configured ID fields, and non-column components are excluded with `withoutField`.

### Bash runtime boundaries

`BashShell` locates/validates Bash and executes via `bash --noprofile --norc -s --` with the script on stdin. It never falls back to `sh`, CMD, PowerShell, or WSL, strips `BASH_ENV`, `ENV`, and `BASH_FUNC_*`, and supports no interactive input. Daemons require a `ready.cmd`; identity uses PID plus process start time; readiness is not a cross-process checkpoint; daemons are never auto-stopped (users run `stop`), and liveness checks are on demand, not a background scheduler.

### Tool-specific notes

- **Jira**: connection config is HOCON, selected whole-file from `--config`, `./.jira/local_jira_config.conf`, or `~/.jira/local_jira_config.conf` (no merging). Only `JIRA_API_TOKEN` comes from the environment. Cloud defaults to REST v3 (ADF descriptions), Data Center to v2; Markdown conversion is covered by golden-file tests.

## Conventions

The canonical repository rules are in `.github/copilot-instructions.md`. Key points:

- **Java 21 only**: `release=21` is enforced. Do not use Java 22+ syntax/APIs (unnamed variables `_`, `Stream.gather`, `ScopedValue`, ClassFile API). Prefer records, sealed types/pattern matching/switch expressions, text blocks for prompts, virtual threads for blocking waits, and try-with-resources.
- **Libraries**: picocli (`@Command`) for CLIs; Typesafe Config for HOCON; SnakeYAML Engine for task YAML. Do not introduce Jackson for configuration mapping.
- **Formatting**: Spotless with google-java-format AOSP style (4-space) runs at `verify`; fix with `mvn spotless:apply`. Do not hand-adjust wrapping/indentation.
- **Static analysis**: SpotBugs runs at `verify`. Lines prefixed `[ERROR]` in its output are report content, not build failures; judge the gate only by `verify.sh`'s exit code and `BUILD SUCCESS`. Do not suppress warnings to clean the report.
- **Tests**: test-on-touch — add the smallest test for each change; no repo-wide backfill. Keep test files under 150 lines and put large expected output in `src/test/resources` golden files. Never delete tests, weaken assertions, or add `@Disabled`/`assumeTrue` to make the build green.
- **Documentation**: all docs are bilingual — Chinese first, English second, with `[中文](#中文) | [English](#english)` navigation at the top; keep both in sync. Usage guides live in module-root `README.md`; architecture lives in `docs/<module path>/architecture.md`; root `readme.MD` stays short and `docs/architecture.md` is only an index. Storage design details are in `docs/design/relational-storage.md`.
- **Commits**: agents may stage and commit, but every `git add` / `git commit` needs the user's confirmation first. Ask once with the file list, a draft message, and the checks that passed; without that confirmation the change stays in the working tree. "提交" means commit **and push**: after the commit, push the current branch to every remote (`origin` and `github`) and report each result. Never rewrite history unless asked.
- **Build hygiene**: modules with internal code dependencies must build through the root reactor (`--also-make`); do not add the internal BOM as a runtime dependency. `.worktree/` contains separate git worktrees — do not edit it, and ignore generated `target/`.
