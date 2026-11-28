package io.github.devpulse.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.devpulse.execution.RunHistory;
import io.github.devpulse.execution.WorkflowRun;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Guards the machine-readable contract of {@code pulse}: exit codes classify what went wrong, and
 * {@code --format json} prints exactly one parseable document so a caller needs no stderr parsing.
 */
class RunCommandJsonTest {
    @TempDir Path directory;

    private record Outcome(int exitCode, String out, String err) {}

    private Outcome execute(String... args) {
        var command = new CommandLine(new RunCommand());
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

    private void write(String yaml) throws IOException {
        Files.writeString(directory.resolve(".devpulse.yaml"), yaml);
    }

    private static JsonObject task(JsonArray tasks, String name) {
        for (var element : tasks) {
            var item = element.getAsJsonObject();
            if (item.get("name").getAsString().equals(name)) {
                return item;
            }
        }
        throw new AssertionError("missing task: " + name);
    }

    @Test
    void printsThePlanAsOneJsonDocument() throws IOException {
        write(
                """
                tasks:
                  build:
                    cmd: 'true'
                  test:
                    cmd: 'true'
                    needs: [build]
                pipelines:
                  dev:
                    tasks: [test]
                """);
        var result = execute("dev", "--dry-run", "--format", "json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.OK);
        var json = JsonParser.parseString(result.out()).getAsJsonObject();
        assertThat(json.get("schemaVersion").getAsInt()).isEqualTo(1);
        assertThat(json.get("command").getAsString()).isEqualTo("plan");
        assertThat(json.get("ok").getAsBoolean()).isTrue();
        assertThat(json.get("target").getAsString()).isEqualTo("dev");
        assertThat(json.get("taskCount").getAsInt()).isEqualTo(2);
        var tasks = json.getAsJsonArray("tasks");
        assertThat(tasks.size()).isEqualTo(2);
        assertThat(task(tasks, "test").getAsJsonArray("needs").size()).isEqualTo(1);
    }

    @Test
    void describesDaemonReadinessInThePlan() throws IOException {
        write(
                """
                tasks:
                  server:
                    type: daemon
                    cmd: 'true'
                    ready:
                      cmd: 'true'
                      interval: 250ms
                """);
        var result = execute("server", "--dry-run", "--format", "json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.OK);
        var server =
                task(
                        JsonParser.parseString(result.out())
                                .getAsJsonObject()
                                .getAsJsonArray("tasks"),
                        "server");
        assertThat(server.get("kind").getAsString()).isEqualTo("daemon");
        var ready = server.getAsJsonObject("ready");
        assertThat(ready.get("interval").getAsString()).isEqualTo("PT0.25S");
        assertThat(ready.get("timeout").getAsString()).isEqualTo("PT30S");
    }

    @Test
    void keepsTextOutputForDryRun() throws IOException {
        write("tasks:\n  check:\n    cmd: 'true'\n");
        var result = execute("check", "--dry-run");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.OK);
        assertThat(result.out()).contains("Plan: check (1 tasks, parallelism 4)");
        assertThat(result.out()).doesNotContain("schemaVersion");
    }

    @Test
    void classifiesAnInvalidConfiguration() throws IOException {
        write("tasks:\n  check:\n    cmd: 'true'\nextra: 1\n");
        var result = execute("check", "--dry-run", "--format", "json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.CONFIG_INVALID);
        var error = JsonParser.parseString(result.out()).getAsJsonObject().getAsJsonObject("error");
        assertThat(error.get("code").getAsString()).isEqualTo("CONFIG_INVALID");
        assertThat(error.get("exitCode").getAsInt()).isEqualTo(10);
        assertThat(error.get("message").getAsString()).contains("Unknown field: extra");
        assertThat(error.get("line").getAsInt()).isPositive();
        assertThat(error.get("file").getAsString()).endsWith(".devpulse.yaml");
    }

    @Test
    void classifiesAnUnknownTarget() throws IOException {
        write("tasks:\n  check:\n    cmd: 'true'\n");
        var result = execute("missing", "--dry-run", "--format", "json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.TARGET_INVALID);
        var error = JsonParser.parseString(result.out()).getAsJsonObject().getAsJsonObject("error");
        assertThat(error.get("code").getAsString()).isEqualTo("TARGET_INVALID");
        assertThat(error.get("message").getAsString())
                .contains("Unknown task or pipeline: missing");
    }

    @Test
    void classifiesADependencyCycle() throws IOException {
        write(
                """
                tasks:
                  a:
                    cmd: 'true'
                    needs: [b]
                  b:
                    cmd: 'true'
                    needs: [a]
                """);
        var result = execute("a", "--dry-run", "--format", "json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.CONFIG_GRAPH);
        var error = JsonParser.parseString(result.out()).getAsJsonObject().getAsJsonObject("error");
        assertThat(error.get("code").getAsString()).isEqualTo("CONFIG_GRAPH");
    }

    @Test
    void mapsDaemonOutcomesToTheirOwnExitCodes() {
        assertThat(
                        RunCommand.exitCodeFor(
                                new WorkflowRun.Result(
                                        RunHistory.State.SUCCESS,
                                        1,
                                        0,
                                        0,
                                        WorkflowRun.DaemonOutcome.NONE)))
                .isEqualTo(ExitCodes.OK);
        assertThat(
                        RunCommand.exitCodeFor(
                                new WorkflowRun.Result(
                                        RunHistory.State.FAILED,
                                        0,
                                        1,
                                        0,
                                        WorkflowRun.DaemonOutcome.NONE)))
                .isEqualTo(ExitCodes.TASK_FAILED);
        assertThat(
                        RunCommand.exitCodeFor(
                                new WorkflowRun.Result(
                                        RunHistory.State.FAILED,
                                        0,
                                        1,
                                        0,
                                        WorkflowRun.DaemonOutcome.NEVER_READY)))
                .isEqualTo(ExitCodes.DAEMON_NOT_READY);
        assertThat(
                        RunCommand.exitCodeFor(
                                new WorkflowRun.Result(
                                        RunHistory.State.FAILED,
                                        0,
                                        1,
                                        0,
                                        WorkflowRun.DaemonOutcome.LOST)))
                .isEqualTo(ExitCodes.DAEMON_LOST);
    }

    @Test
    void keepsTextErrorsOnStderr() throws IOException {
        write("tasks:\n  check:\n    cmd: 'true'\nextra: 1\n");
        var result = execute("check", "--dry-run");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.CONFIG_INVALID);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).startsWith("devpulse: ");
    }
}
