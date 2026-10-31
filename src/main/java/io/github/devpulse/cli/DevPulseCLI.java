package io.github.devpulse.cli;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
        name = "devpulse",
        mixinStandardHelpOptions = true,
        versionProvider = DevPulseCLI.VersionProvider.class,
        description = "A local DevOps orchestration toolbox.",
        subcommands = {
                GatherCommand.class,
                InitCommand.class,
                PulseCommand.class,
                ReviewCommand.class,
                StatusCommand.class
        }
)
public final class DevPulseCLI implements Runnable {
    @Spec
    private CommandSpec spec;

    public static void main(String[] args) {
        var cli = new CommandLine(new DevPulseCLI())
                .setOut(new PrintWriter(System.out, true,
                        StandardCharsets.UTF_8))
                .setErr(new PrintWriter(System.err, true,
                        StandardCharsets.UTF_8));
        System.exit(cli.execute(args));
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    public static final class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            var version = DevPulseCLI.class.getPackage()
                    .getImplementationVersion();
            return new String[]{
                    "devpulse " + (version == null ? "development" : version)
            };
        }
    }
}
