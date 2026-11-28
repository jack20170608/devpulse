package io.github.devpulse.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Guards {@code review --format json}: same plan document as {@code run --dry-run}, no TUI. */
class ReviewCommandJsonTest {
    @TempDir Path directory;

    private record Outcome(int exitCode, String out, String err) {}

    private Outcome execute(String... args) {
        var command = new CommandLine(new ReviewCommand());
        var out = new StringWriter();
        var err = new StringWriter();
        command.setOut(new PrintWriter(out, true));
        command.setErr(new PrintWriter(err, true));
        var all = new String[args.length + 2];
        all[0] = "--directory";
        all[1] = directory.toString();
        System.arraycopy(args, 0, all, 2, args.length);
        return new Outcome(command.execute(all), out.toString(), err.toString());
    }

    private void write(String workflow, String yaml) throws IOException {
        var workflows = directory.resolve(".workflow");
        Files.createDirectories(workflows);
        Files.writeString(workflows.resolve(workflow + ".yaml"), yaml);
    }

    @Test
    void emitsThePlanWithoutOpeningTheInterface() throws IOException {
        write(
                "dev",
                """
                tasks:
                  - id: build
                    spec:
                      run: 'true'
                  - id: test
                    needs: [build]
                    spec:
                      run: 'true'
                """);
        var result = execute("dev", "--format", "json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.OK);
        var json = JsonParser.parseString(result.out()).getAsJsonObject();
        assertThat(json.get("command").getAsString()).isEqualTo("plan");
        assertThat(json.get("ok").getAsBoolean()).isTrue();
        assertThat(json.get("taskCount").getAsInt()).isEqualTo(2);
    }

    @Test
    void classifiesAnInvalidConfiguration() throws IOException {
        write("check", "tasks:\n  - id: check\n    spec:\n      run: 'true'\nextra: 1\n");
        var result = execute("check", "--format", "json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.CONFIG_INVALID);
        var error = JsonParser.parseString(result.out()).getAsJsonObject().getAsJsonObject("error");
        assertThat(error.get("code").getAsString()).isEqualTo("CONFIG_INVALID");
    }

    @Test
    void classifiesAMissingWorkflow() {
        var result = execute("missing", "--format", "json");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.CONFIG_INVALID);
        var error = JsonParser.parseString(result.out()).getAsJsonObject().getAsJsonObject("error");
        assertThat(error.get("code").getAsString()).isEqualTo("CONFIG_INVALID");
        assertThat(error.get("message").getAsString()).contains("Workflow not found");
    }
}
