package io.github.devpulse.execution;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigUtil;
import com.typesafe.config.ConfigValueType;
import io.github.devpulse.core.bash.BashTaskDefinition;
import io.github.devpulse.core.bash.BashTaskDefinition.Daemon;
import io.github.devpulse.core.dag.DaemonOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One workflow file: a single YAML document describing one DAG.
 *
 * <p>The file name is the identifier, so {@code .workflow/build.yaml} is the workflow {@code
 * build}. The document itself carries only a display name and the task list; everything about the
 * machine belongs to the application configuration instead.
 */
public record PulseConfiguration(
        Path directory,
        Path file,
        String workflowId,
        String name,
        List<BashTaskDefinition> tasks,
        Map<String, DaemonOptions> daemonOptions,
        Config source) {

    private static final Pattern TASK_ID = Pattern.compile("[a-z][a-z0-9_-]*");
    private static final List<String> TOP_LEVEL = List.of("name", "tasks");
    private static final List<String> TASK_FIELDS = List.of("id", "kind", "needs", "spec");
    private static final List<String> BASH_FIELDS = List.of("run", "ready", "stop", "stopTimeout");
    private static final List<String> READY_FIELDS =
            List.of("cmd", "timeout", "interval", "checkTimeout");

    public PulseConfiguration {
        tasks = List.copyOf(tasks);
        daemonOptions = Map.copyOf(daemonOptions);
    }

    /**
     * Loads one workflow file.
     *
     * @param directory the project directory relative paths are based on
     * @param file the workflow file
     */
    public static PulseConfiguration load(Path directory, Path file) throws IOException {
        var root = directory.toAbsolutePath().normalize();
        var workflow = file.isAbsolute() ? file.normalize() : root.resolve(file).normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("Project directory does not exist: " + root);
        }
        if (!Files.isRegularFile(workflow)) {
            throw new IOException(
                    "Workflow not found: " + workflow + ". Run devpulse init <name> first.");
        }
        var config = YamlConfiguration.load(workflow);
        if (config.isEmpty()) {
            throw invalid(config, "tasks", "A workflow must contain at least one task.");
        }
        rejectUnknown(config, "workflow", TOP_LEVEL);
        if (config.hasPathOrNull("name")) {
            requireType(config, "name", ConfigValueType.STRING);
        }
        requireType(config, "tasks", ConfigValueType.LIST);
        var id = id(workflow);
        var tasks = new ArrayList<BashTaskDefinition>();
        var daemonOptions = new LinkedHashMap<String, DaemonOptions>();
        var seen = new LinkedHashSet<String>();
        var list = config.getList("tasks");
        for (var index = 0; index < list.size(); index++) {
            var item = list.get(index);
            if (item.valueType() != ConfigValueType.OBJECT) {
                throw new ConfigException.BadValue(item.origin(), "tasks", "Must be an object.");
            }
            var task = ((com.typesafe.config.ConfigObject) item).toConfig();
            requireType(task, "id", ConfigValueType.STRING);
            var taskId = task.getString("id");
            if (!TASK_ID.matcher(taskId).matches()) {
                throw invalid(task, "id", "Must match [a-z][a-z0-9_-]*");
            }
            if (!seen.add(taskId)) {
                throw invalid(task, "id", "Duplicate task id: " + taskId);
            }
            rejectUnknown(task, "task", TASK_FIELDS);
            var kind = task.hasPathOrNull("kind") ? nonBlankString(task, "kind") : "bash";
            if (!kind.equals("bash")) {
                throw invalid(task, "kind", "Unknown task kind: " + kind);
            }
            requireType(task, "spec", ConfigValueType.OBJECT);
            var spec = task.getConfig("spec");
            rejectUnknown(spec, "spec", BASH_FIELDS);
            var daemon = daemon(spec);
            if (daemon != null) {
                var ready = spec.getConfig("ready");
                daemonOptions.put(
                        taskId,
                        new DaemonOptions(
                                duration(ready, "timeout", DaemonOptions.DEFAULT.readyTimeout()),
                                duration(ready, "interval", DaemonOptions.DEFAULT.checkInterval()),
                                duration(
                                        ready,
                                        "checkTimeout",
                                        DaemonOptions.DEFAULT.checkTimeout())));
            }
            tasks.add(
                    new BashTaskDefinition(
                            taskId,
                            nonBlankString(spec, "run"),
                            task.hasPathOrNull("needs") ? stringList(task, "needs") : List.of(),
                            daemon));
        }
        var name = config.hasPathOrNull("name") ? config.getString("name") : id;
        return new PulseConfiguration(root, workflow, id, name, tasks, daemonOptions, config);
    }

    public PulsePlan plan() throws IOException {
        try {
            return PulsePlan.create(tasks, daemonOptions);
        } catch (PlanningException exception) {
            var section =
                    switch (exception.scope()) {
                        case WORKFLOW -> source;
                        case TASK ->
                                source.getList("tasks").stream()
                                        .map(value ->
                                                ((com.typesafe.config.ConfigObject) value)
                                                        .toConfig())
                                        .filter(task ->
                                                task.getString("id").equals(exception.name()))
                                        .findFirst()
                                        .orElse(source);
                        case PIPELINE, TARGET -> source;
                    };
            var origin =
                    section.hasPathOrNull(exception.field())
                            ? section.getValue(exception.field()).origin()
                            : section.root().origin();
            throw new ConfigException.BadValue(origin, exception.field(), exception.getMessage());
        }
    }

    /** The identifier of a workflow file: its stem. */
    public static String id(Path file) {
        var name = file.getFileName().toString();
        var dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Daemon daemon(Config spec) {
        var type = spec.hasPathOrNull("ready") ? "daemon" : "task";
        if (type.equals("task")) {
            for (var field : List.of("stop", "stopTimeout")) {
                if (spec.hasPathOrNull(field)) {
                    throw invalid(spec, field, "Only daemon tasks support this field.");
                }
            }
            return null;
        }
        requireType(spec, "ready", ConfigValueType.OBJECT);
        var ready = spec.getConfig("ready");
        rejectUnknown(ready, "ready", READY_FIELDS);
        return new Daemon(
                nonBlankString(ready, "cmd"),
                spec.hasPathOrNull("stop") ? nonBlankString(spec, "stop") : null,
                duration(spec, "stopTimeout", Duration.ofSeconds(5)));
    }

    private static Duration duration(Config config, String path, Duration fallback) {
        if (!config.hasPathOrNull(path)) {
            return fallback;
        }
        requireType(config, path, ConfigValueType.STRING);
        var value = config.getDuration(path);
        try {
            if (value.toNanos() <= 0) {
                throw invalid(config, path, "Must be a positive duration.");
            }
        } catch (ArithmeticException exception) {
            throw invalid(config, path, "Duration is too large.");
        }
        return value;
    }

    private static String nonBlankString(Config config, String path) {
        requireType(config, path, ConfigValueType.STRING);
        var value = config.getString(path);
        if (value.isBlank()) {
            throw invalid(config, path, "Must not be blank.");
        }
        return value;
    }

    private static List<String> stringList(Config config, String path) {
        requireType(config, path, ConfigValueType.LIST);
        for (var value : config.getList(path)) {
            if (value.valueType() != ConfigValueType.STRING
                    || ((String) value.unwrapped()).isBlank()) {
                throw new ConfigException.BadValue(
                        value.origin(), path, "Must contain nonblank strings only.");
            }
        }
        return List.copyOf(new LinkedHashSet<>(config.getStringList(path)));
    }

    private static void requireType(Config config, String path, ConfigValueType expected) {
        if (!config.hasPathOrNull(path)) {
            throw invalid(config, path, "Required field is missing.");
        }
        var value = config.getValue(path);
        if (value.valueType() != expected) {
            throw invalid(config, path, "Expected " + expected + ".");
        }
    }

    private static void rejectUnknown(Config config, String scope, List<String> allowed) {
        for (var key : config.root().keySet()) {
            if (!allowed.contains(key)) {
                throw invalid(config, ConfigUtil.quoteString(key), "Unknown field: " + key);
            }
        }
    }

    private static ConfigException.BadValue invalid(Config config, String path, String message) {
        var origin =
                config.hasPathOrNull(path)
                        ? config.getValue(path).origin()
                        : config.root().origin();
        return new ConfigException.BadValue(origin, path, message);
    }
}
