package io.github.devpulse.cli;

import io.github.devpulse.core.environment.EnvironmentGatherer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Report the environment the tasks will actually run in: which Bash was found, and whether the
 * tools a typical project needs are on the path.
 *
 * <p>This is the first half of the future two-part {@code doctor}: local diagnostics that work even
 * when nothing else does. The second half — a report from the server — arrives with it.
 */
@Command(
        name = "doctor",
        mixinStandardHelpOptions = true,
        versionProvider = PulseCLI.VersionProvider.class,
        description = "Check Bash and local tool availability.")
public final class DoctorCommand implements Callable<Integer> {
    @Spec private CommandSpec spec;

    @Option(
            names = "--directory",
            defaultValue = ".",
            description = "Project directory used to detect project markers " + "(default: .).")
    private Path directory;

    @Option(names = "--bash", paramLabel = "PATH", description = "Explicit path to Bash.")
    private Path bash;

    @Option(
            names = "--format",
            paramLabel = "FORMAT",
            defaultValue = "text",
            description = "Result format: text (default) or json.")
    private OutputFormat format;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        var json = format == OutputFormat.json;
        EnvironmentGatherer.Facts facts;
        try {
            facts = new EnvironmentGatherer().gather(directory, bash);
        } catch (IOException exception) {
            return CommandOutput.fail(
                    json,
                    out,
                    err,
                    "doctor",
                    ExitCodes.ENVIRONMENT,
                    "ENVIRONMENT",
                    exception.getMessage(),
                    exception);
        } catch (InterruptedException exception) {
            return CommandOutput.interrupted(json, out, err, "doctor", "check interrupted.");
        }
        if (json) {
            out.println(DoctorJson.report(facts));
            out.flush();
            return ExitCodes.OK;
        }
        out.println("OS: " + facts.os());
        out.println("Java: " + facts.javaVersion());
        out.println("Bash: " + facts.bash().executable());
        out.println("Availability only; no daemon health checks.");
        for (var tool : facts.tools()) {
            out.printf(
                    "%-12s %-10s %s%n",
                    tool.name(),
                    tool.location().isEmpty() ? "NOT_FOUND" : "AVAILABLE",
                    tool.location());
        }
        return ExitCodes.OK;
    }
}
