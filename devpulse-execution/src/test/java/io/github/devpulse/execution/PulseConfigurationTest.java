package io.github.devpulse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.typesafe.config.ConfigException;
import io.github.devpulse.bash.BashTaskDefinition;
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
 * Guards the workflow-file contract: one file is one workflow, the file name is the id, and unknown
 * fields, wrong types, blank commands, and daemon-only fields are rejected before any task runs.
 */
class PulseConfigurationTest {
    @TempDir Path directory;

    private PulseConfiguration load(String id, String yaml) throws IOException {
        var workflows = directory.resolve(".workflow");
        Files.createDirectories(workflows);
        var file = workflows.resolve(id + ".yaml");
        Files.writeString(file, yaml);
        return PulseConfiguration.load(directory, file);
    }

    private static String yaml(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    @Test
    void appliesDocumentedDefaults() throws IOException {
        var configuration =
                load("check", yaml("tasks:", "  - id: check", "    spec:", "      run: 'true'"));
        assertThat(configuration.workflowId()).isEqualTo("check");
        assertThat(configuration.name()).isEqualTo("check");
        assertThat(configuration.tasks())
                .extracting(BashTaskDefinition::name)
                .containsExactly("check");
        assertThat(configuration.daemonOptions()).isEmpty();
    }

    @Test
    void parsesDaemonReadyOptions() throws IOException {
        var configuration =
                load(
                        "server",
                        yaml(
                                "tasks:",
                                "  - id: server",
                                "    kind: bash",
                                "    spec:",
                                "      run: 'node server.js'",
                                "      ready:",
                                "        cmd: 'true'",
                                "        interval: 250ms"));
        DaemonOptions options = configuration.daemonOptions().get("server");
        assertThat(options.checkInterval()).isEqualTo(Duration.ofMillis(250));
        assertThat(options.readyTimeout()).isEqualTo(DaemonOptions.DEFAULT.readyTimeout());
        assertThat(options.checkTimeout()).isEqualTo(DaemonOptions.DEFAULT.checkTimeout());
    }

    @Test
    void keepsTheDisplayNameFromTheFile() throws IOException {
        var configuration =
                load(
                        "build",
                        yaml(
                                "name: Build pipeline",
                                "tasks:",
                                "  - id: check",
                                "    spec:",
                                "      run: 'true'"));
        assertThat(configuration.workflowId()).isEqualTo("build");
        assertThat(configuration.name()).isEqualTo("Build pipeline");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidWorkflows")
    void rejectsInvalidWorkflow(String description, String id, String yaml, String message) {
        assertThatThrownBy(() -> load(id, yaml)).hasMessageContaining(message);
    }

    static Stream<Arguments> invalidWorkflows() {
        return Stream.of(
                arguments(
                        "unknown top-level field",
                        "check",
                        yaml(
                                "tasks:",
                                "  - id: check",
                                "    spec:",
                                "      run: 'true'",
                                "extra: 1"),
                        "Unknown field: extra"),
                arguments(
                        "unknown task field",
                        "check",
                        yaml(
                                "tasks:",
                                "  - id: check",
                                "    retries: 1",
                                "    spec:",
                                "      run: 'true'"),
                        "Unknown field: retries"),
                arguments(
                        "unknown spec field",
                        "check",
                        yaml(
                                "tasks:",
                                "  - id: check",
                                "    spec:",
                                "      run: 'true'",
                                "      shell: bash"),
                        "Unknown field: shell"),
                arguments(
                        "unknown ready field",
                        "server",
                        yaml(
                                "tasks:",
                                "  - id: server",
                                "    spec:",
                                "      run: 'x'",
                                "      ready:",
                                "        cmd: 'true'",
                                "        retries: 1"),
                        "Unknown field: retries"),
                arguments(
                        "blank run command",
                        "check",
                        yaml("tasks:", "  - id: check", "    spec:", "      run: '   '"),
                        "Must not be blank"),
                arguments(
                        "missing tasks",
                        "check",
                        yaml("name: empty"),
                        "Required field is missing."),
                arguments(
                        "task id outside the allowed pattern",
                        "check",
                        yaml("tasks:", "  - id: Check", "    spec:", "      run: 'true'"),
                        "Must match [a-z][a-z0-9_-]*"),
                arguments(
                        "duplicate task id",
                        "check",
                        yaml(
                                "tasks:",
                                "  - id: check",
                                "    spec:",
                                "      run: 'true'",
                                "  - id: check",
                                "    spec:",
                                "      run: 'false'"),
                        "Duplicate task id: check"),
                arguments(
                        "unknown task kind",
                        "check",
                        yaml(
                                "tasks:",
                                "  - id: check",
                                "    kind: service",
                                "    spec:",
                                "      run: 'true'"),
                        "Unknown task kind: service"),
                arguments(
                        "daemon-only field on a batch task",
                        "check",
                        yaml(
                                "tasks:",
                                "  - id: check",
                                "    spec:",
                                "      run: 'true'",
                                "      stopTimeout: 5s"),
                        "Only daemon tasks support this field."),
                arguments(
                        "non-positive stop timeout",
                        "server",
                        yaml(
                                "tasks:",
                                "  - id: server",
                                "    spec:",
                                "      run: 'x'",
                                "      ready:",
                                "        cmd: 'true'",
                                "      stopTimeout: 0s"),
                        "Must be a positive duration."));
    }

    @Test
    void rejectsUnknownDependency() throws IOException {
        var configuration =
                load(
                        "check",
                        yaml(
                                "tasks:",
                                "  - id: check",
                                "    needs: [missing]",
                                "    spec:",
                                "      run: 'true'"));
        assertThatThrownBy(() -> configuration.plan())
                .isInstanceOf(ConfigException.BadValue.class)
                .hasMessageContaining("needs");
    }

    @Test
    void rejectsDependencyCycle() throws IOException {
        var configuration =
                load(
                        "check",
                        yaml(
                                "tasks:",
                                "  - id: a",
                                "    needs: [b]",
                                "    spec:",
                                "      run: 'true'",
                                "  - id: b",
                                "    needs: [a]",
                                "    spec:",
                                "      run: 'true'"));
        assertThatThrownBy(() -> configuration.plan())
                .isInstanceOf(ConfigException.BadValue.class)
                .hasMessageContaining("needs");
    }

    @Test
    void reportsMissingWorkflowFile() {
        var file = directory.resolve(".workflow").resolve("missing.yaml");
        assertThatThrownBy(() -> PulseConfiguration.load(directory, file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Workflow not found");
    }

    @Test
    void reportsEmptyWorkflow() throws IOException {
        var workflows = directory.resolve(".workflow");
        Files.createDirectories(workflows);
        var file = workflows.resolve("empty.yaml");
        Files.writeString(file, "{}\n");
        assertThatThrownBy(() -> PulseConfiguration.load(directory, file))
                .isInstanceOf(ConfigException.BadValue.class)
                .hasMessageContaining("at least one task");
    }

    @Test
    void rejectsDuplicateYamlKey() {
        var duplicate =
                yaml(
                        "tasks:",
                        "  - id: check",
                        "    spec:",
                        "      run: 'true'",
                        "      run: 'false'");
        assertThatThrownBy(() -> load("check", duplicate))
                .hasMessageContaining("Duplicate YAML key: run");
    }
}
