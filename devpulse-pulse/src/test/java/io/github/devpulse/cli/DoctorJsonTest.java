package io.github.devpulse.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import io.github.devpulse.core.environment.BashShell;
import io.github.devpulse.core.environment.EnvironmentGatherer;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guards the {@code doctor --format json} contract: one document, per-tool availability. */
class DoctorJsonTest {

    @Test
    void reportsTheEnvironmentAndToolAvailability() {
        var facts =
                new EnvironmentGatherer.Facts(
                        Path.of("."),
                        "Windows 11",
                        "amd64",
                        "21.0.2",
                        new BashShell(Path.of("bash")),
                        List.of(
                                new EnvironmentGatherer.Tool("git", "/usr/bin/git"),
                                new EnvironmentGatherer.Tool("docker", "")),
                        List.of("pom.xml"));

        var json = JsonParser.parseString(DoctorJson.report(facts)).getAsJsonObject();

        assertThat(json.get("view").getAsString()).isEqualTo("doctor");
        assertThat(json.getAsJsonObject("environment").get("bash").getAsString()).isEqualTo("bash");
        var tools = json.getAsJsonArray("tools");
        assertThat(tools.get(0).getAsJsonObject().get("available").getAsBoolean()).isTrue();
        assertThat(tools.get(1).getAsJsonObject().get("available").getAsBoolean()).isFalse();
    }
}
