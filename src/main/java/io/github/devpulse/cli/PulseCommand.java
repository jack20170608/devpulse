package io.github.devpulse.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
        name = "pulse",
        mixinStandardHelpOptions = true,
        versionProvider = DevPulseCLI.VersionProvider.class,
        description = "Run a local task pipeline according to its dependencies."
)
public final class PulseCommand implements Callable<Integer> {
    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getErr()
                .println("pulse is not implemented yet.");
        return 1;
    }
}
