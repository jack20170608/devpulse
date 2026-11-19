package io.github.devpulse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.typesafe.config.ConfigException;
import io.github.devpulse.core.bash.BashTaskDefinition;
import io.github.devpulse.core.dag.DaemonOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Guards the configuration contract stated in the Pulse guide: unknown fields, wrong types, blank
 * commands, daemon-only fields, and plan-time dependency errors are rejected before any task runs.
 */
class PulseConfigurationTest {
    private static final String TASK = "    cmd: 'true'";

    @TempDir Path directory;

    private PulseConfiguration load(String yaml) throws IOException {
        Files.writeString(directory.resolve(".devpulse.yaml"), yaml);
        return PulseConfiguration.load(directory);
    }

    private static String yaml(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    @Test
    void appliesDocumentedDefaults() throws IOException {
        var configuration = load(yaml("tasks:", "  check:", TASK));
        assertThat(configuration.parallelism()).isEqualTo(4);
        assertThat(configuration.tasks())
                .extracting(BashTaskDefinition::name)
                .containsExactly("check");
        assertThat(configuration.pipelines()).isEmpty();
        assertThat(configuration.daemonOptions()).isEmpty();
    }

    @Test
    void parsesDaemonReadyOptions() throws IOException {
        var configuration =
                load(
                        yaml(
                                "tasks:",
                                "  server:",
                                "    type: daemon",
                                "    cmd: 'node server.js'",
                                "    ready:",
                                "      cmd: 'true'",
                                "      interval: 250ms"));
        DaemonOptions options = configuration.daemonOptions().get("server");
        assertThat(options.checkInterval()).isEqualTo(Duration.ofMillis(250));
        assertThat(options.readyTimeout()).isEqualTo(DaemonOptions.DEFAULT.readyTimeout());
        assertThat(options.checkTimeout()).isEqualTo(DaemonOptions.DEFAULT.checkTimeout());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigurations")
    void rejectsInvalidConfiguration(String description, String yaml, String message) {
        assertThatThrownBy(() -> load(yaml)).hasMessageContaining(message);
    }

    static Stream<Arguments> invalidConfigurations() {
        return Stream.of(
                arguments(
                        "unknown top-level field",
                        yaml("tasks:", "  check:", TASK, "extra: 1"),
                        "Unknown field: extra"),
                arguments(
                        "unknown task field",
                        yaml("tasks:", "  check:", TASK, "    retries: 1"),
                        "Unknown field: retries"),
                arguments(
                        "unknown ready field",
                        yaml(
                                "tasks:",
                                "  server:",
                                "    type: daemon",
                                "    cmd: 'x'",
                                "    ready:",
                                "      cmd: 'true'",
                                "      retries: 1"),
                        "Unknown field: retries"),
                arguments(
                        "blank command",
                        yaml("tasks:", "  check:", "    cmd: '   '"),
                        "Must not be blank"),
                arguments(
                        "unsupported version",
                        yaml("version: 2", "tasks:", "  check:", TASK),
                        "Only version 1 is supported."),
                arguments(
                        "non-positive parallelism",
                        yaml("parallelism: 0", "tasks:", "  check:", TASK),
                        "Must be a positive 32-bit integer."),
                arguments(
                        "daemon-only field on a batch task",
                        yaml("tasks:", "  check:", "    type: task", TASK, "    stopTimeout: 5s"),
                        "Only daemon tasks support this field."),
                arguments(
                        "daemon without ready",
                        yaml("tasks:", "  server:", "    type: daemon", "    cmd: 'x'"),
                        "ready"),
                arguments(
                        "unknown task type",
                        yaml("tasks:", "  check:", "    type: service", TASK),
                        "Expected task or daemon."),
                arguments(
                        "non-positive stop timeout",
                        yaml(
                                "tasks:",
                                "  server:",
                                "    type: daemon",
                                "    cmd: 'x'",
                                "    ready:",
                                "      cmd: 'true'",
                                "    stopTimeout: 0s"),
                        "Must be a positive duration."),
                arguments(
                        "duplicate YAML key",
                        yaml("tasks:", "  check:", TASK, "  check:", "    cmd: 'false'"),
                        "Duplicate YAML key: check"));
    }

    @Test
    void rejectsUnknownTarget() throws IOException {
        var configuration = load(yaml("tasks:", "  check:", TASK));
        assertThatThrownBy(() -> configuration.plan("missing"))
                .hasMessageContaining("Unknown task or pipeline: missing");
    }

    @Test
    void rejectsAmbiguousTarget() throws IOException {
        var configuration =
                load(yaml("tasks:", "  dev:", TASK, "pipelines:", "  dev:", "    tasks: [dev]"));
        assertThatThrownBy(() -> configuration.plan("dev"))
                .hasMessageContaining("Ambiguous target: dev");
    }

    @Test
    void rejectsUnknownDependency() throws IOException {
        var configuration = load(yaml("tasks:", "  check:", TASK, "    needs: [missing]"));
        assertThatThrownBy(() -> configuration.plan("check"))
                .isInstanceOf(ConfigException.BadValue.class)
                .hasMessageContaining("needs");
    }

    @Test
    void rejectsDependencyCycle() throws IOException {
        var configuration =
                load(
                        yaml(
                                "tasks:",
                                "  a:",
                                TASK,
                                "    needs: [b]",
                                "  b:",
                                TASK,
                                "    needs: [a]"));
        assertThatThrownBy(() -> configuration.plan("a"))
                .isInstanceOf(ConfigException.BadValue.class)
                .hasMessageContaining("needs");
    }

    @Test
    void reportsMissingConfigurationFile() {
        assertThatThrownBy(() -> PulseConfiguration.load(directory))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Run devpulse init first");
    }

    @Test
    void reportsLegacyConfFile() throws IOException {
        Files.writeString(directory.resolve(".devpulse.conf"), "tasks {}");
        assertThatThrownBy(() -> PulseConfiguration.load(directory))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no longer supported");
    }
}
