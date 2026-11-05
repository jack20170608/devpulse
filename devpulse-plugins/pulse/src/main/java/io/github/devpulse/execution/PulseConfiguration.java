package io.github.devpulse.execution;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigUtil;
import com.typesafe.config.ConfigValueType;
import io.github.devpulse.execution.TaskDefinition.Daemon;
import io.github.devpulse.execution.TaskDefinition.Readiness;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public record PulseConfiguration(Path directory, int parallelism,
        WorkflowDefinition workflow, Config source) {

    public static PulseConfiguration load(Path directory) throws IOException {
        var root = directory.toAbsolutePath().normalize();
        var file = root.resolve(".devpulse.yaml");
        if (!Files.isDirectory(root)) {
            throw new IOException("Project directory does not exist: " + root);
        }
        if (!Files.isRegularFile(file)) {
            if (Files.exists(root.resolve(".devpulse.conf"))) {
                throw new IOException("Legacy .devpulse.conf is no longer supported. "
                        + "Migrate it to .devpulse.yaml using the migration guide "
                        + "in docs/core-cli.md; renaming alone is not sufficient.");
            }
            throw new IOException("Configuration not found: " + file
                    + ". Run devpulse init first.");
        }
        var config = YamlConfiguration.load(file);
        rejectUnknownFields(config, List.of("version", "parallelism", "tasks", "pipelines"));
        config = config.withFallback(ConfigFactory.parseMap(Map.of(
                "version", 1, "parallelism", 4, "pipelines", Map.of())));
        if (positiveInteger(config, "version") != 1) {
            throw invalid(config, "version", "Only version 1 is supported.");
        }
        var parallelism = positiveInteger(config, "parallelism");
        requireType(config, "tasks", ConfigValueType.OBJECT);
        var tasks = new ArrayList<TaskDefinition>();
        var taskSection = config.getConfig("tasks");
        for (var name : taskSection.root().keySet()) {
            var path = ConfigUtil.quoteString(name);
            requireType(taskSection, path, ConfigValueType.OBJECT);
            var tool = taskSection.getConfig(path);
            rejectUnknownFields(tool, List.of("description", "cmd", "needs", "type",
                    "ready", "shutdownHook", "stop", "stopTimeout"));
            if (tool.hasPathOrNull("description")) {
                requireType(tool, "description", ConfigValueType.STRING);
            }
            tasks.add(new TaskDefinition(name, nonBlankString(tool, "cmd"),
                    tool.hasPathOrNull("needs")
                            ? stringList(tool, "needs") : List.of(),
                    daemon(tool)));
        }
        requireType(config, "pipelines", ConfigValueType.OBJECT);
        var pipelines = new LinkedHashMap<String, List<String>>();
        var section = config.getConfig("pipelines");
        for (var name : section.root().keySet()) {
            var path = ConfigUtil.quoteString(name);
            requireType(section, path, ConfigValueType.OBJECT);
            var pipeline = section.getConfig(path);
            rejectUnknownFields(pipeline, List.of("description", "tasks"));
            if (pipeline.hasPathOrNull("description")) {
                requireType(pipeline, "description", ConfigValueType.STRING);
            }
            pipelines.put(name, stringList(pipeline, "tasks"));
        }
        return new PulseConfiguration(root, parallelism,
                new WorkflowDefinition(tasks, pipelines), config);
    }

    public TaskPlan plan(String target) throws IOException {
        try {
            return TaskPlan.create(workflow, target);
        } catch (PlanningException exception) {
            if (exception.scope() == PlanningException.Scope.TARGET) {
                throw new IOException(exception.getMessage(), exception);
            }
            var section = switch (exception.scope()) {
                case WORKFLOW -> source;
                case TASK -> source.getConfig("tasks")
                        .getConfig(ConfigUtil.quoteString(exception.name()));
                case PIPELINE -> source.getConfig("pipelines")
                        .getConfig(ConfigUtil.quoteString(exception.name()));
                case TARGET -> throw new IllegalStateException(exception);
            };
            var origin = section.hasPathOrNull(exception.field())
                    ? section.getValue(exception.field()).origin()
                    : section.root().origin();
            throw new ConfigException.BadValue(origin, exception.field(),
                    exception.getMessage());
        }
    }

    private static Daemon daemon(Config tool) {
        var type = tool.hasPathOrNull("type")
                ? nonBlankString(tool, "type") : "task";
        if (!List.of("task", "daemon").contains(type)) {
            throw invalid(tool, "type", "Expected task or daemon.");
        }
        if (type.equals("task")) {
            for (var field : List.of("ready", "shutdownHook", "stop",
                    "stopTimeout")) {
                if (tool.hasPathOrNull(field)) {
                    throw invalid(tool, field,
                            "Only daemon tasks support this field.");
                }
            }
            return null;
        }
        requireType(tool, "ready", ConfigValueType.OBJECT);
        var ready = tool.getConfig("ready");
        rejectUnknownFields(ready,
                List.of("cmd", "interval", "timeout", "checkTimeout"));
        var readiness = new Readiness(nonBlankString(ready, "cmd"),
                duration(ready, "interval", Duration.ofMillis(500)),
                duration(ready, "timeout", Duration.ofSeconds(30)),
                duration(ready, "checkTimeout", Duration.ofSeconds(2)));
        if (tool.hasPathOrNull("shutdownHook")) {
            requireType(tool, "shutdownHook", ConfigValueType.BOOLEAN);
        }
        return new Daemon(readiness,
                tool.hasPath("shutdownHook")
                        && tool.getBoolean("shutdownHook"),
                tool.hasPathOrNull("stop")
                        ? nonBlankString(tool, "stop") : null,
                duration(tool, "stopTimeout", Duration.ofSeconds(5)));
    }

    private static Duration duration(Config config, String path,
            Duration fallback) {
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

    private static int positiveInteger(Config config, String path) {
        requireType(config, path, ConfigValueType.NUMBER);
        var number = config.getNumber(path);
        var value = number.intValue();
        if (value <= 0 || number.doubleValue() != value) {
            throw invalid(config, path, "Must be a positive 32-bit integer.");
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
                throw new ConfigException.BadValue(value.origin(), path,
                        "Must contain nonblank strings only.");
            }
        }
        return List.copyOf(new LinkedHashSet<>(config.getStringList(path)));
    }

    private static void requireType(Config config, String path,
            ConfigValueType expected) {
        var value = config.getValue(path);
        if (value.valueType() != expected) {
            throw invalid(config, path, "Expected " + expected + ".");
        }
    }

    private static void rejectUnknownFields(Config config,
            List<String> allowed) {
        for (var key : config.root().keySet()) {
            if (!allowed.contains(key)) {
                throw invalid(config, ConfigUtil.quoteString(key),
                        "Unknown field: " + key);
            }
        }
    }

    private static ConfigException.BadValue invalid(Config config,
            String path, String message) {
        return new ConfigException.BadValue(config.getValue(path).origin(),
                path, message);
    }

}
