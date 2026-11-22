package io.github.devpulse.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import io.github.devpulse.core.bash.DaemonManager;
import io.github.devpulse.core.bash.RunHistory;
import io.github.devpulse.core.dag.DagDaemon;
import io.github.devpulse.core.environment.BashShell;
import io.github.devpulse.core.environment.EnvironmentGatherer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Guards the {@code status --format json} contract: one document per view, `ok` reports that the
 * query itself succeeded, and the subject's outcome stays in `state`.
 */
class StatusCommandJsonTest {
    private static final Instant START = Instant.parse("2026-11-20T00:00:00Z");

    @TempDir Path directory;

    private record Outcome(int exitCode, String out, String err) {}

    private Outcome execute(String... args) {
        var command = new CommandLine(new StatusCommand());
        var out = new StringWriter();
        var err = new StringWriter();
        command.setOut(new PrintWriter(out, true));
        command.setErr(new PrintWriter(err, true));
        var all = new String[args.length + 2];
        all[0] = "--directory";
        all[1] = directory.toString();
        System.arraycopy(args, 0, all, 2, args.length);
        return new Outcome(command.execute(all), out.toString(), err.toString());
    }

    private static RunHistory.TaskState task(
            String id,
            String name,
            RunHistory.State state,
            Integer exitCode,
            DagDaemon.Health health) {
        return new RunHistory.TaskState(
                id,
                name,
                List.of(),
                state,
                START,
                START.plusSeconds(2),
                exitCode,
                null,
                health,
                null,
                null);
    }

    @Test
    void listsAnEmptyHistoryAsApi() {
        var result = execute("--list", "--format", "json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.OK);
        var json = JsonParser.parseString(result.out()).getAsJsonObject();
        assertThat(json.get("schemaVersion").getAsInt()).isEqualTo(1);
        assertThat(json.get("command").getAsString()).isEqualTo("status");
        assertThat(json.get("view").getAsString()).isEqualTo("list");
        assertThat(json.get("runs").getAsJsonArray().size()).isZero();
    }

    @Test
    void classifiesALogRequestWithNoRecordedRuns() {
        var result = execute("--log", "build", "--format", "json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.TARGET_INVALID);
        var error = JsonParser.parseString(result.out()).getAsJsonObject().getAsJsonObject("error");
        assertThat(error.get("code").getAsString()).isEqualTo("TARGET_INVALID");
    }

    @Test
    void rejectsTailWithoutLog() {
        var result = execute("--tail", "10", "--format", "json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.USAGE);
    }

    @Test
    void rejectsAnInvalidGrepExpression() {
        var result = execute("--log", "build", "--grep", "[unclosed", "--format", "json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.USAGE);
    }

    @Test
    void describesALogWithTruncationMetadata() {
        var json =
                JsonParser.parseString(
                                StatusJson.log(
                                        "build",
                                        "FAILED",
                                        "logs/x.log",
                                        List.of("a", "b"),
                                        9,
                                        20,
                                        true,
                                        null))
                        .getAsJsonObject();

        assertThat(json.get("view").getAsString()).isEqualTo("log");
        assertThat(json.get("task").getAsString()).isEqualTo("build");
        assertThat(json.getAsJsonArray("lines").size()).isEqualTo(2);
        assertThat(json.get("lineCount").getAsInt()).isEqualTo(2);
        assertThat(json.get("matchedLines").getAsLong()).isEqualTo(9);
        assertThat(json.get("totalLines").getAsLong()).isEqualTo(20);
        assertThat(json.get("truncated").getAsBoolean()).isTrue();
    }

    @Test
    void classifiesAnUnknownRun() {
        var result = execute("--run", "no-such-run", "--format", "json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.TARGET_INVALID);
        var error = JsonParser.parseString(result.out()).getAsJsonObject().getAsJsonObject("error");
        assertThat(error.get("code").getAsString()).isEqualTo("TARGET_INVALID");
        assertThat(error.get("exitCode").getAsInt()).isEqualTo(12);
    }

    @Test
    void describesARunWithTasksAndDaemons() {
        var snapshot =
                new RunHistory.Snapshot(
                        "run-1",
                        "dev",
                        "project",
                        4,
                        99L,
                        START,
                        START,
                        START.plusSeconds(2),
                        RunHistory.State.SUCCESS,
                        List.of(
                                task("t1", "build", RunHistory.State.SUCCESS, 0, null),
                                task(
                                        "t2",
                                        "server",
                                        RunHistory.State.READY,
                                        null,
                                        DagDaemon.Health.READY)));
        var daemons =
                Map.of(
                        "t2",
                        List.of(
                                new DaemonManager.Instance(
                                        "inst-1",
                                        1,
                                        new DaemonManager.Observation("READY", 4242L, null))));

        var json =
                JsonParser.parseString(
                                StatusJson.run(snapshot, daemons, null, START.plusSeconds(3)))
                        .getAsJsonObject();
        var run = json.getAsJsonObject("run");

        assertThat(json.get("view").getAsString()).isEqualTo("run");
        assertThat(json.get("ok").getAsBoolean()).isTrue();
        assertThat(run.get("state").getAsString()).isEqualTo("SUCCESS");
        assertThat(run.get("durationMs").getAsLong()).isEqualTo(2000L);
        var tasks = run.getAsJsonArray("tasks");
        assertThat(tasks.size()).isEqualTo(2);
        var server = tasks.get(1).getAsJsonObject();
        assertThat(server.get("name").getAsString()).isEqualTo("server");
        assertThat(server.get("daemonHealth").getAsString()).isEqualTo("READY");
        var instances = server.getAsJsonArray("daemons");
        assertThat(instances.size()).isEqualTo(1);
        assertThat(instances.get(0).getAsJsonObject().get("pid").getAsLong()).isEqualTo(4242L);
    }

    @Test
    void reportsAnEstimatedRunningDurationAndWarning() {
        var snapshot =
                new RunHistory.Snapshot(
                        "run-2",
                        "dev",
                        "project",
                        2,
                        99L,
                        START,
                        START,
                        null,
                        RunHistory.State.RUNNING,
                        List.of());
        var warning = "recovered from an interrupted executor";

        var json =
                JsonParser.parseString(
                                StatusJson.list(
                                        List.of(new RunHistory.Observation(snapshot, warning)),
                                        START.plusSeconds(5)))
                        .getAsJsonObject();
        var run = json.getAsJsonArray("runs").get(0).getAsJsonObject();

        assertThat(run.get("durationMs").getAsLong()).isEqualTo(5000L);
        assertThat(run.get("running").getAsBoolean()).isTrue();
        assertThat(run.get("warning").getAsString()).isEqualTo(warning);
    }

    @Test
    void reportsToolAvailability() {
        var facts =
                new EnvironmentGatherer.Facts(
                        directory,
                        "Windows 11",
                        "amd64",
                        "21.0.2",
                        new BashShell(Path.of("bash")),
                        List.of(
                                new EnvironmentGatherer.Tool("git", "/usr/bin/git"),
                                new EnvironmentGatherer.Tool("docker", "")),
                        List.of("pom.xml"));

        var json = JsonParser.parseString(StatusJson.tools(facts)).getAsJsonObject();
        var tools = json.getAsJsonArray("tools");

        assertThat(json.getAsJsonObject("environment").get("bash").getAsString()).isEqualTo("bash");
        assertThat(tools.get(0).getAsJsonObject().get("available").getAsBoolean()).isTrue();
        assertThat(tools.get(1).getAsJsonObject().get("available").getAsBoolean()).isFalse();
    }
}
