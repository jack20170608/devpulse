package io.github.devpulse.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.devpulse.execution.PulsePlan;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Builds the JSON documents that {@code --format json} prints.
 *
 * <p>Each document carries {@code schemaVersion}, {@code command}, and {@code ok}, so a caller can
 * identify the shape before reading the payload. Field names are part of the CLI contract.
 */
final class PulseJson {
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private PulseJson() {}

    /** The validated plan for {@code --dry-run}, including daemon readiness settings. */
    static String plan(String target, Path directory, int parallelism, PulsePlan plan) {
        var root = base("plan", true);
        root.addProperty("target", target);
        root.addProperty("directory", normalized(directory));
        root.addProperty("parallelism", parallelism);
        root.addProperty("taskCount", plan.tasks().size());
        var tasks = new JsonArray();
        for (var task : plan.tasks()) {
            var item = new JsonObject();
            item.addProperty("name", task.name());
            item.addProperty("command", task.command());
            var needs = new JsonArray();
            task.needs().forEach(needs::add);
            item.add("needs", needs);
            var daemon = task.daemon();
            item.addProperty("kind", daemon == null ? "task" : "daemon");
            if (daemon != null) {
                var ready = new JsonObject();
                ready.addProperty("cmd", daemon.readyCommand());
                var options = plan.daemonOptions().get(task.name());
                if (options != null) {
                    ready.addProperty("interval", options.checkInterval().toString());
                    ready.addProperty("timeout", options.readyTimeout().toString());
                    ready.addProperty("checkTimeout", options.checkTimeout().toString());
                }
                item.add("ready", ready);
                item.addProperty("stop", daemon.stopCommand());
                item.addProperty("stopTimeout", daemon.stopTimeout().toString());
            }
            tasks.add(item);
        }
        root.add("tasks", tasks);
        return GSON.toJson(root);
    }

    /** The outcome of a completed run. */
    static String run(
            String target,
            Path directory,
            String runId,
            String state,
            int succeeded,
            int failed,
            int skipped,
            String daemonOutcome,
            boolean ok) {
        var root = base("run", ok);
        root.addProperty("target", target);
        root.addProperty("directory", normalized(directory));
        root.addProperty("runId", runId);
        root.addProperty("state", state);
        root.addProperty("succeeded", succeeded);
        root.addProperty("failed", failed);
        root.addProperty("skipped", skipped);
        root.addProperty("daemonOutcome", daemonOutcome);
        return GSON.toJson(root);
    }

    /**
     * A failure envelope; unknown location fields are omitted rather than reported as null.
     *
     * <p>The originating configuration field is not reported: {@code ConfigException.BadValue} does
     * not expose its path, and deriving it from the message text would couple this contract to a
     * third-party message format. The message already names the offending field.
     */
    static String error(int exitCode, String code, String message, String file, Integer line) {
        var root = base("error", false);
        var error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("exitCode", exitCode);
        error.addProperty("message", message);
        if (file != null) {
            error.addProperty("file", file);
        }
        if (line != null) {
            error.addProperty("line", line);
        }
        root.add("error", error);
        return GSON.toJson(root);
    }

    /** The retry decision for a parent run: what will rerun, what will be reused, and why. */
    static String retry(
            String parentRunId,
            boolean dryRun,
            List<String> rerun,
            List<String> reuse,
            Map<String, String> reasons) {
        var root = base("retry", true);
        root.addProperty("dryRun", dryRun);
        root.addProperty("parentRunId", parentRunId);
        var rerunArray = new JsonArray();
        rerun.forEach(rerunArray::add);
        root.add("rerun", rerunArray);
        var reuseArray = new JsonArray();
        reuse.forEach(reuseArray::add);
        root.add("reuse", reuseArray);
        var reasonObject = new JsonObject();
        reasons.forEach(reasonObject::addProperty);
        root.add("rerunReasons", reasonObject);
        return GSON.toJson(root);
    }

    private static JsonObject base(String command, boolean ok) {
        var root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("command", command);
        root.addProperty("ok", ok);
        return root;
    }

    private static String normalized(Path directory) {
        return directory.toAbsolutePath().normalize().toString();
    }
}
