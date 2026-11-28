package io.github.devpulse.cli.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigResolveOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The application configuration: one HOCON file, command-line overrides, built-in defaults.
 *
 * <p>There is deliberately no directory scanning and no project/user layering. A missing file means
 * defaults; a malformed file is an error rather than a silent fallback, because a typo in a port
 * should never start the server somewhere unexpected.
 *
 * <p>Every resolved value records where it came from, so {@code doctor} can explain the outcome
 * instead of leaving the user to guess which layer won.
 */
public record AppConfig(
        Path workflowDir,
        String host,
        int port,
        Path runDirectory,
        String logLevel,
        Map<String, String> sources) {

    /** The path used when neither {@code --config} nor {@code DEVPULSE_CONFIG} is given. */
    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".devpulse", "config.conf");
    }

    private static final String DEFAULTS =
            """
            devpulse {
              workflows { dir = ".workflow" }
              server { host = "127.0.0.1", port = 12580 }
              run { directory = "." }
              log { level = "info" }
            }
            """;

    /**
     * Loads the configuration.
     *
     * @param file the configuration file, or {@code null} to use {@link #defaultFile()}
     * @param portOverride a port from the command line, or {@code null}
     * @param directoryOverride a run directory from the command line, or {@code null}
     */
    public static AppConfig load(Path file, Integer portOverride, Path directoryOverride) {
        var path = file == null ? defaultFile() : file;
        var sources = new LinkedHashMap<String, String>();
        Config config;
        if (Files.isRegularFile(path)) {
            try {
                config =
                        ConfigFactory.parseFile(path)
                                .withFallback(ConfigFactory.parseString(DEFAULTS))
                                .resolve(ConfigResolveOptions.defaults());
            } catch (ConfigException.Parse | ConfigException.BadValue exception) {
                throw new IllegalArgumentException(
                        "cannot read configuration " + path + ": " + exception.getMessage(),
                        exception);
            }
            record(sources, "workflows.dir", path);
            record(sources, "server.host", path);
            record(sources, "server.port", path);
            record(sources, "run.directory", path);
            record(sources, "log.level", path);
        } else {
            config = ConfigFactory.parseString(DEFAULTS).resolve();
        }
        for (var key : new String[] {"workflows.dir", "server.host", "server.port", "run.directory",
                "log.level"}) {
            recordDefault(sources, key);
        }

        var host = config.getString("devpulse.server.host");
        var port = portOverride == null ? config.getInt("devpulse.server.port") : portOverride;
        if (portOverride != null) {
            sources.put("server.port", "command line");
        }
        var directory =
                directoryOverride == null
                        ? Path.of(config.getString("devpulse.run.directory"))
                        : directoryOverride;
        if (directoryOverride != null) {
            sources.put("run.directory", "command line");
        }

        if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
            throw new IllegalArgumentException(
                    "devpulse.server.host must be a loopback address, not " + host);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    "devpulse.server.port must be between 1 and 65535, not " + port);
        }
        var level = config.getString("devpulse.log.level").toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("trace", "debug", "info", "warn", "error").contains(level)) {
            throw new IllegalArgumentException(
                    "devpulse.log.level must be trace, debug, info, warn or error, not " + level);
        }

        return new AppConfig(
                Path.of(config.getString("devpulse.workflows.dir")),
                host,
                port,
                directory,
                level,
                Map.copyOf(sources));
    }

    /** The configuration file used, for messages. */
    public static String describe(Path file) {
        var path = file == null ? defaultFile() : file;
        return Files.isRegularFile(path) ? path.toString() : path + " (missing, using defaults)";
    }

    private static void record(Map<String, String> sources, String key, Path path) {
        sources.put(key, path.toString());
    }

    private static void recordDefault(Map<String, String> sources, String key) {
        sources.putIfAbsent(key, "default");
    }
}
