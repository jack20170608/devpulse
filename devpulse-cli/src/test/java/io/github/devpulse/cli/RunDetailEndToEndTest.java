package io.github.devpulse.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import io.github.devpulse.core.bash.PlanManifest;
import io.github.devpulse.core.bash.RunHistory;
import io.github.devpulse.core.dag.DagDaemon;
import io.github.devpulse.core.dag.DagGraph;
import io.github.devpulse.core.dag.TaskKey;
import io.github.devpulse.storage.DaemonRecord;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * End-to-end coverage for the run-detail view.
 *
 * <p>The run is produced by the real {@link RunHistory} writer and then rendered by the real {@code
 * status} command, so this test covers serialization, reading, and rendering as one path. A
 * hand-built {@code Snapshot} cannot catch a writer and reader that disagree about the record.
 */
class RunDetailEndToEndTest {
    @TempDir Path directory;

    private record Outcome(int exitCode, String out, String err) {}

    private Outcome status(String... args) {
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

    /** Record one completed run with a batch task and a ready daemon, through the real writer. */
    private String recordRun() throws IOException {
        var graph = DagGraph.builder();
        var build = TaskKey.of("build", Integer.class);
        var server = TaskKey.of("server", ProcessHandle.class);
        graph.node(build, List.<TaskKey<?>>of());
        graph.node(server, List.<TaskKey<?>>of());
        var nodes = new LinkedHashMap<String, PlanManifest.Node>();
        nodes.put("build", new PlanManifest.Node("build", "fp-build", List.of()));
        nodes.put("server", new PlanManifest.Node("server", "fp-server", List.of()));
        var manifest =
                new PlanManifest("dev", directory.toString(), 2, "bash", "test", null, nodes);
        try (var writer = new RunHistory(directory).begin("dev", graph.build(), 2, manifest)) {
            writer.started("build");
            try (var log = writer.openLog("build")) {
                log.write("build finished\n");
            }
            writer.completed("build", RunHistory.State.SUCCESS, 0, null, Instant.now());
            writer.started("server");
            var serverTask =
                    writer.snapshot().tasks().stream()
                            .filter(task -> task.name().equals("server"))
                            .findFirst()
                            .orElseThrow();
            writer.store()
                    .recordDaemon(
                            new DaemonRecord(
                                    writer.id(),
                                    serverTask.id(),
                                    "instance-1",
                                    1,
                                    "server",
                                    "bash",
                                    4242L,
                                    Instant.now(),
                                    "true",
                                    null,
                                    1_000_000_000L,
                                    100_000_000L,
                                    1_000_000_000L,
                                    100_000_000L,
                                    null,
                                    null,
                                    null,
                                    Instant.now(),
                                    null,
                                    null));
            writer.completed("server", RunHistory.State.READY, null, null, Instant.now());
            writer.daemonChecked("server", DagDaemon.Health.READY, null, Instant.now());
            writer.ended(RunHistory.State.SUCCESS);
            return writer.id();
        }
    }

    @Test
    void rendersARunRecordedByTheRealWriter() throws IOException {
        var id = recordRun();

        var result = status("--run", id, "--format", "json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.OK);
        var json = JsonParser.parseString(result.out()).getAsJsonObject();
        assertThat(json.get("view").getAsString()).isEqualTo("run");
        var run = json.getAsJsonObject("run");
        assertThat(run.get("id").getAsString()).isEqualTo(id);
        assertThat(run.get("target").getAsString()).isEqualTo("dev");
        assertThat(run.get("state").getAsString()).isEqualTo("SUCCESS");
        assertThat(run.get("parallelism").getAsInt()).isEqualTo(2);
        assertThat(run.get("durationMs").getAsLong()).isNotNegative();
        var tasks = run.getAsJsonArray("tasks");
        assertThat(tasks.size()).isEqualTo(2);
        var build = tasks.get(0).getAsJsonObject();
        assertThat(build.get("name").getAsString()).isEqualTo("build");
        assertThat(build.get("state").getAsString()).isEqualTo("SUCCESS");
        assertThat(build.get("exitCode").getAsInt()).isZero();
        assertThat(build.get("logPath").getAsString()).startsWith("logs/");
        var server = tasks.get(1).getAsJsonObject();
        assertThat(server.get("state").getAsString()).isEqualTo("READY");
        assertThat(server.get("daemonHealth").getAsString()).isEqualTo("READY");
        // The observation is stored on the instance, so the live section exists with it.
        assertThat(server.has("daemons")).isTrue();
    }

    @Test
    void listsTheRunWithItsRecordedState() throws IOException {
        var id = recordRun();

        var result = status("--list", "--format", "json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.OK);
        var runs = JsonParser.parseString(result.out()).getAsJsonObject().getAsJsonArray("runs");
        assertThat(runs.size()).isEqualTo(1);
        var run = runs.get(0).getAsJsonObject();
        assertThat(run.get("id").getAsString()).isEqualTo(id);
        assertThat(run.get("state").getAsString()).isEqualTo("SUCCESS");
        assertThat(run.get("durationMs").getAsLong()).isNotNegative();
    }

    @Test
    void readsTheTaskLogRecordedInsideTheRun() throws IOException {
        recordRun();

        var result = status("--log", "build", "--format", "json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.OK);
        var json = JsonParser.parseString(result.out()).getAsJsonObject();
        assertThat(json.get("view").getAsString()).isEqualTo("log");
        assertThat(json.get("task").getAsString()).isEqualTo("build");
        assertThat(json.getAsJsonArray("lines").size()).isEqualTo(1);
        assertThat(json.getAsJsonArray("lines").get(0).getAsString()).isEqualTo("build finished");
    }

    @Test
    void rendersTheSameRunAsText() throws IOException {
        var id = recordRun();

        var result = status("--run", id);

        assertThat(result.exitCode()).isEqualTo(ExitCodes.OK);
        assertThat(result.out()).contains("Run ID: " + id);
        assertThat(result.out()).contains("Recorded daemon health: READY");
    }
}
