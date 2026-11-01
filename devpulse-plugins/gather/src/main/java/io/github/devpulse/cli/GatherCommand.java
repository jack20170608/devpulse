package io.github.devpulse.cli;

import io.github.devpulse.environment.EnvironmentGatherer;
import io.github.devpulse.environment.GatherPrompt;
import java.awt.AWTError;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "gather",
        mixinStandardHelpOptions = true,
        versionProvider = GatherCLI.VersionProvider.class,
        description = "Gather local environment facts "
                + "and prepare an AI prompt."
)
public final class GatherCommand implements Callable<Integer> {
    @Spec
    private CommandSpec spec;

    @Option(names = "--directory", defaultValue = ".",
            description = "Project directory to inspect "
                    + "(default: ${DEFAULT-VALUE}).")
    private Path directory;

    @Option(names = "--bash", paramLabel = "PATH",
            description = "Explicit path to Bash (Git Bash on Windows).")
    private Path bash;

    @Option(names = "--language", defaultValue = "both",
            paramLabel = "zh|en|both",
            description = "Prompt language: ${COMPLETION-CANDIDATES} "
                    + "(default: ${DEFAULT-VALUE}).")
    private GatherPrompt.Language language;

    @Option(names = "--no-clipboard",
            description = "Print the prompt without changing the clipboard.")
    private boolean noClipboard;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            var facts = new EnvironmentGatherer().gather(directory, bash);
            var console = System.console();
            var encoding = console == null
                    ? System.out.charset() : console.charset();
            if (!StandardCharsets.UTF_8.equals(encoding)) {
                err.println("gather: Warning: Java reports "
                        + (console == null ? "standard output" : "console")
                        + " encoding " + encoding.name()
                        + ", but devpulse writes UTF-8. Chinese text may "
                        + "appear garbled. Configure your terminal to use "
                        + "UTF-8; in Git Bash, open Options > Text > "
                        + "Character set and select UTF-8. The terminal's "
                        + "actual display encoding cannot be verified.");
                err.flush();
            }
            var prompt = GatherPrompt.render(facts, language);
            out.print(prompt);
            out.flush();
            if (!noClipboard) {
                try {
                    Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new StringSelection(prompt), null);
                    err.println("Prompt copied to the clipboard. Review it "
                            + "before sharing it with an AI service.");
                } catch (HeadlessException | IllegalStateException
                        | SecurityException | AWTError exception) {
                    err.println("Clipboard unavailable; copy the prompt "
                            + "from standard output.");
                }
            }
            return 0;
        } catch (IOException exception) {
            err.println("gather: " + exception.getMessage());
            return 1;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            err.println("gather: environment scan interrupted.");
            return 1;
        }
    }
}
