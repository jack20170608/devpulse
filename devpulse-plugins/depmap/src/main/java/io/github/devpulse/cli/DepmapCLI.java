package io.github.devpulse.cli;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine;
import picocli.CommandLine.IVersionProvider;

public final class DepmapCLI {
    private DepmapCLI() {}

    public static void main(String[] args) {
        var cli = new CommandLine(new DepmapCommand())
                .setOut(new PrintWriter(System.out, true, StandardCharsets.UTF_8))
                .setErr(new PrintWriter(System.err, true, StandardCharsets.UTF_8));
        System.exit(cli.execute(args));
    }

    public static final class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            var version = DepmapCLI.class.getPackage().getImplementationVersion();
            return new String[]{"depmap " + (version == null ? "development" : version)};
        }
    }
}
