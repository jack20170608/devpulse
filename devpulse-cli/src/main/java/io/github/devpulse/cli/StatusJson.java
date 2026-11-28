package io.github.devpulse.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.devpulse.bash.DaemonManager;
import io.github.devpulse.execution.RunHistory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Builds the JSON documents that {@code status --format json} prints. */
final class StatusJson {
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private StatusJson() {}

    /** One recorded run, with per-task state and live daemon observations keyed by task id. */
    static String run(
            RunHistory.Snapshot snapshot,
            Map<String, List<DaemonManager.Instance>> daemons,
            String warning,
            Instant now) {
        var root = base();
        root.addProperty("view", "run");
        if (warning != null) {
            root.addProperty("warning", warning);
        }
        root.add("run", runObject(snapshot, daemons, now));
        return GSON.toJson(root);
    }

    /** Every recorded run, newest first, without per-task detail. */
    static String list(List<RunHistory.Observation> records, Instant now) {
        var root = base();
        root.addProperty("view", "list");
        var runs = new JsonArray();
        for (var record : records) {
            var snapshot = record.snapshot();
            var item = new JsonObject();
            item.addProperty("id", snapshot.id());
            item.addProperty("target", snapshot.target());
            item.addProperty("state", snapshot.state().name());
            item.addProperty("startedAt", iso(snapshot.startedAt()));
            item.addProperty("finishedAt", iso(snapshot.finishedAt()));
            addDuration(item, snapshot.startedAt(), snapshot.finishedAt(), snapshot.state(), now);
            if (record.warning() != null) {
                item.addProperty("warning", record.warning());
            }
            runs.add(item);
        }
        root.add("runs", runs);
        return GSON.toJson(root);
    }

    /** A task log, bounded to the requested tail so the payload stays predictable. */
    static String log(
            String task,
            String state,
            String logPath,
            List<String> lines,
            long matched,
            long total,
            boolean truncated,
            String note) {
        var root = base();
        root.addProperty("view", "log");
        root.addProperty("task", task);
        root.addProperty("state", state);
        root.addProperty("logPath", logPath);
        if (note != null) {
            root.addProperty("note", note);
        }
        var array = new JsonArray();
        lines.forEach(array::add);
        root.add("lines", array);
        root.addProperty("lineCount", lines.size());
        root.addProperty("matchedLines", matched);
        root.addProperty("totalLines", total);
        root.addProperty("truncated", truncated);
        return GSON.toJson(root);
    }

    /** Local tool availability, without daemon health checks. */
    private static JsonObject runObject(
            RunHistory.Snapshot snapshot,
            Map<String, List<DaemonManager.Instance>> daemons,
            Instant now) {
        var run = new JsonObject();
        run.addProperty("id", snapshot.id());
        run.addProperty("target", snapshot.target());
        run.addProperty("project", snapshot.project());
        run.addProperty("state", snapshot.state().name());
        run.addProperty("parallelism", snapshot.parallelism());
        run.addProperty("executorPid", snapshot.executorPid());
        run.addProperty("startedAt", iso(snapshot.startedAt()));
        run.addProperty("finishedAt", iso(snapshot.finishedAt()));
        addDuration(run, snapshot.startedAt(), snapshot.finishedAt(), snapshot.state(), now);
        var tasks = new JsonArray();
        for (var task : snapshot.tasks()) {
            tasks.add(taskObject(task, daemons.get(task.id()), now));
        }
        run.add("tasks", tasks);
        return run;
    }

    private static JsonObject taskObject(
            RunHistory.TaskState task, List<DaemonManager.Instance> instances, Instant now) {
        var item = new JsonObject();
        item.addProperty("name", task.name());
        item.addProperty("id", task.id());
        item.addProperty("state", task.state().name());
        var needs = new JsonArray();
        task.needs().forEach(needs::add);
        item.add("needs", needs);
        if (task.exitCode() != null) {
            item.addProperty("exitCode", task.exitCode());
        }
        item.addProperty("startedAt", iso(task.startedAt()));
        item.addProperty("finishedAt", iso(task.finishedAt()));
        addDuration(item, task.startedAt(), task.finishedAt(), task.state(), now);
        if (task.error() != null) {
            item.addProperty("error", task.error());
        }
        item.addProperty("logPath", task.logPath());
        if (task.daemonHealth() != null) {
            item.addProperty("daemonHealth", task.daemonHealth().name());
            item.addProperty("daemonCheckedAt", iso(task.daemonCheckedAt()));
            if (task.daemonError() != null) {
                item.addProperty("daemonError", task.daemonError());
            }
        }
        if (instances != null && !instances.isEmpty()) {
            var array = new JsonArray();
            for (var instance : instances) {
                var observation = instance.observation();
                var value = new JsonObject();
                value.addProperty("id", instance.id());
                value.addProperty("attempt", instance.attempt());
                value.addProperty("state", observation.state());
                value.addProperty("pid", observation.pid());
                if (observation.detail() != null) {
                    value.addProperty("detail", observation.detail());
                }
                array.add(value);
            }
            item.add("daemons", array);
        }
        return item;
    }

    private static JsonObject base() {
        var root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("command", "status");
        root.addProperty("ok", true);
        return root;
    }

    private static void addDuration(
            JsonObject target, Instant start, Instant finish, RunHistory.State state, Instant now) {
        if (start == null || (finish == null && state != RunHistory.State.RUNNING)) {
            return;
        }
        var elapsed = Duration.between(start, finish == null ? now : finish).toMillis();
        if (elapsed >= 0) {
            target.addProperty("durationMs", elapsed);
        }
        if (finish == null) {
            target.addProperty("running", true);
        }
    }

    private static String iso(Instant value) {
        return value == null ? null : value.toString();
    }
}
