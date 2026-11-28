package io.github.devpulse.cli.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards the three layers: defaults, one configuration file, and command-line overrides. */
class AppConfigTest {
    @TempDir Path directory;

    @Test
    void missingFileFallsBackToDefaults() {
        var config = AppConfig.load(directory.resolve("absent.conf"), null, null);

        assertThat(config.host()).isEqualTo("127.0.0.1");
        assertThat(config.port()).isEqualTo(12580);
        assertThat(config.workflowDir()).hasToString(".workflow");
        assertThat(config.runDirectory()).hasToString(".");
        assertThat(config.logLevel()).isEqualTo("info");
        assertThat(config.sources().get("server.port")).isEqualTo("default");
    }

    @Test
    void fileOverridesOnlyTheKeysItMentions() throws Exception {
        var file = directory.resolve("config.conf");
        Files.writeString(file, "devpulse { server { port = 13000 }, log { level = \"debug\" } }");

        var config = AppConfig.load(file, null, null);

        assertThat(config.port()).isEqualTo(13000);
        assertThat(config.logLevel()).isEqualTo("debug");
        assertThat(config.host()).isEqualTo("127.0.0.1");
        assertThat(config.sources().get("server.port")).isEqualTo(file.toString());
        assertThat(config.sources().get("server.host")).isEqualTo(file.toString());
    }

    @Test
    void commandLineWinsOverTheFile() throws Exception {
        var file = directory.resolve("config.conf");
        Files.writeString(file, "devpulse { server { port = 13000 } }");

        var config = AppConfig.load(file, 14000, Path.of("sub"));

        assertThat(config.port()).isEqualTo(14000);
        assertThat(config.runDirectory()).hasToString("sub");
        assertThat(config.sources().get("server.port")).isEqualTo("command line");
        assertThat(config.sources().get("run.directory")).isEqualTo("command line");
    }

    @Test
    void malformedFileIsAnErrorRatherThanASilentFallback() throws Exception {
        var file = directory.resolve("broken.conf");
        Files.writeString(file, "devpulse { server { port = ");

        assertThatThrownBy(() -> AppConfig.load(file, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot read configuration");
    }

    @Test
    void rejectsANonLoopbackHost() throws Exception {
        var file = directory.resolve("public.conf");
        Files.writeString(file, "devpulse { server { host = \"0.0.0.0\" } }");

        assertThatThrownBy(() -> AppConfig.load(file, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }
}
