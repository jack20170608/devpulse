package io.github.devpulse.execution;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigIncludeContext;
import com.typesafe.config.ConfigIncluder;
import com.typesafe.config.ConfigIncluderClasspath;
import com.typesafe.config.ConfigIncluderFile;
import com.typesafe.config.ConfigIncluderURL;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigSyntax;
import com.typesafe.config.ConfigUtil;
import com.typesafe.config.ConfigValueType;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public record PulseConfiguration(Path directory, int parallelism,
        Map<String, Task> tasks, Map<String, Pipeline> pipelines) {
    public PulseConfiguration {
        tasks = Collections.unmodifiableMap(new LinkedHashMap<>(tasks));
        pipelines = Collections.unmodifiableMap(
                new LinkedHashMap<>(pipelines));
    }

    public record Task(String name, String command, List<String> needs,
            ConfigOrigin origin) {
        public Task {
            command = command.replace("\r\n", "\n");
            needs = List.copyOf(needs);
        }
    }

    public record Pipeline(List<String> tasks, ConfigOrigin origin) {
        public Pipeline {
            tasks = List.copyOf(tasks);
        }
    }

    public static PulseConfiguration load(Path directory) throws IOException {
        var root = directory.toAbsolutePath().normalize();
        var file = root.resolve(".devpulse.conf");
        if (!Files.isDirectory(root)) {
            throw new IOException("Project directory does not exist: " + root);
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("Configuration not found: " + file
                    + ". Run devpulse init first.");
        }
        var options = ConfigParseOptions.defaults()
                .setSyntax(ConfigSyntax.CONF)
                .setIncluder(new LocalIncludes());
        var defaults = ConfigFactory.parseResources("reference.conf", options);
        var config = ConfigFactory.parseFile(file.toFile(),
                options.setAllowMissing(false))
                .withFallback(defaults).resolve();
        if (positiveInteger(config, "version") != 1) {
            throw invalid(config, "version", "Only version 1 is supported.");
        }
        var parallelism = positiveInteger(config, "parallelism");
        requireType(config, "tools", ConfigValueType.LIST);
        var tasks = new LinkedHashMap<String, Task>();
        for (var tool : config.getConfigList("tools")) {
            rejectUnknownFields(tool, List.of("name", "cmd", "needs"));
            var name = nonBlankString(tool, "name");
            if (!name.equals(name.strip())
                    || name.chars().anyMatch(Character::isISOControl)) {
                throw invalid(tool, "name",
                        "Task names cannot contain surrounding whitespace "
                                + "or control characters.");
            }
            var task = new Task(name, nonBlankString(tool, "cmd"),
                    tool.hasPathOrNull("needs")
                            ? stringList(tool, "needs") : List.of(),
                    tool.origin());
            if (tasks.putIfAbsent(name, task) != null) {
                throw invalid(tool, "name", "Duplicate task: " + name);
            }
        }
        if (tasks.isEmpty()) {
            throw invalid(config, "tools", "At least one task is required.");
        }
        for (var task : tasks.values()) {
            for (var dependency : task.needs()) {
                if (!tasks.containsKey(dependency)) {
                    throw new ConfigException.BadValue(task.origin(), "needs",
                            "Task " + task.name() + " has unknown dependency: "
                                    + dependency);
                }
            }
        }
        requireType(config, "pipelines", ConfigValueType.OBJECT);
        var pipelines = new LinkedHashMap<String, Pipeline>();
        var section = config.getConfig("pipelines");
        for (var name : section.root().keySet()) {
            var path = ConfigUtil.quoteString(name);
            requireType(section, path, ConfigValueType.OBJECT);
            var pipeline = section.getConfig(path);
            rejectUnknownFields(pipeline, List.of("description", "tasks"));
            if (pipeline.hasPathOrNull("description")) {
                requireType(pipeline, "description", ConfigValueType.STRING);
            }
            var targets = stringList(pipeline, "tasks");
            if (targets.isEmpty()) {
                throw invalid(pipeline, "tasks",
                        "Pipeline " + name
                                + " must contain at least one task.");
            }
            for (var target : targets) {
                if (!tasks.containsKey(target)) {
                    throw invalid(pipeline, "tasks",
                            "Pipeline " + name + " has unknown task: "
                                    + target);
                }
            }
            pipelines.put(name, new Pipeline(targets, pipeline.origin()));
        }
        return new PulseConfiguration(root, parallelism, tasks, pipelines);
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

    private static final class LocalIncludes implements ConfigIncluder,
            ConfigIncluderFile, ConfigIncluderClasspath, ConfigIncluderURL {
        private final ArrayDeque<String> active = new ArrayDeque<>();

        @Override
        public ConfigIncluder withFallback(ConfigIncluder fallback) {
            return this;
        }

        @Override
        public ConfigObject include(ConfigIncludeContext context,
                String name) {
            rejectRemote(name);
            var basename = name.substring(Math.max(name.lastIndexOf('/'),
                    name.lastIndexOf('\\')) + 1);
            var filename = basename.contains(".") ? name : name + ".conf";
            return parseRelative(context, filename);
        }

        @Override
        public ConfigObject includeFile(ConfigIncludeContext context,
                File file) {
            rejectRemote(file.toString());
            if (!file.isAbsolute()) {
                return parseRelative(context, file.toString());
            }
            var key = file.toPath().toAbsolutePath().normalize().toString();
            enter(key);
            try {
                return ConfigFactory.parseFile(file, localOptions(context))
                        .root();
            } finally {
                active.removeLast();
            }
        }

        @Override
        public ConfigObject includeResources(ConfigIncludeContext context,
                String resource) {
            rejectRemote(resource);
            enter("classpath:" + resource);
            try {
                return ConfigFactory.parseResources(resource,
                        localOptions(context)).root();
            } finally {
                active.removeLast();
            }
        }

        @Override
        public ConfigObject includeURL(ConfigIncludeContext context, URL url) {
            throw new ConfigException.Generic(
                    "URL includes are not allowed: " + url);
        }

        private ConfigParseOptions localOptions(ConfigIncludeContext context) {
            return context.parseOptions().setSyntax(ConfigSyntax.CONF)
                    .setIncluder(this);
        }

        private ConfigObject parseRelative(ConfigIncludeContext context,
                String filename) {
            var source = context.relativeTo(filename);
            if (source == null) {
                if (context.parseOptions().getAllowMissing()) {
                    return ConfigFactory.empty().root();
                }
                throw new ConfigException.Generic(
                        "Cannot locate required include: " + filename);
            }
            enter(source.origin().description());
            try {
                return source.parse(localOptions(context));
            } finally {
                active.removeLast();
            }
        }

        private void enter(String key) {
            if (active.contains(key) || active.size() >= 32) {
                throw new ConfigException.Generic(
                        "Cyclic or excessively nested include: " + key);
            }
            active.addLast(key);
        }

        private static void rejectRemote(String name) {
            if (name.startsWith("\\\\") || name.startsWith("//")) {
                throw new ConfigException.Generic(
                        "Network filesystem includes are not allowed: "
                                + name);
            }
            if (name.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")
                    && !name.matches("^[A-Za-z]:[\\\\/].*")) {
                throw new ConfigException.Generic(
                        "URL includes are not allowed: " + name);
            }
        }
    }
}
