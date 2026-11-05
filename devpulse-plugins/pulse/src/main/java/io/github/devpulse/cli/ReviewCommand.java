package io.github.devpulse.cli;

import com.typesafe.config.ConfigException;
import io.github.devpulse.execution.PulseConfiguration;
import io.github.devpulse.execution.TaskPlan;
import io.github.devpulse.review.ReviewTui;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "review",
        mixinStandardHelpOptions = true,
        versionProvider = PulseCLI.VersionProvider.class,
        description = "Validate and review a task or pipeline plan "
                + "without executing commands."
)
public final class ReviewCommand implements Callable<Integer> {
    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", defaultValue = "dev",
            paramLabel = "TARGET",
            description = "Task or pipeline to review "
                    + "(default: ${DEFAULT-VALUE}).")
    private String target;

    @Option(names = "--directory", defaultValue = ".",
            description = "Project directory containing .devpulse.yaml "
                    + "(default: ${DEFAULT-VALUE}).")
    private Path directory;

    @Option(names = "--jobs", paramLabel = "N",
            description = "Review with this concurrency limit; "
                    + "overrides parallelism.")
    private Integer jobs;

    @Option(names = "--plain",
            description = "Print a plain-text plan instead of opening the TUI.")
    private boolean plain;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            if (jobs != null && jobs <= 0) {
                err.println("review: --jobs must be a positive integer.");
                return 1;
            }
            var config = PulseConfiguration.load(directory);
            var plan = TaskPlan.create(config, target);
            var concurrency = jobs == null ? config.parallelism() : jobs;
            if (!plain && ReviewTui.show(target, config, plan, concurrency)) {
                return 0;
            }
            out.println("Plan: " + target + " (" + plan.tasks().size()
                    + " tasks, parallelism " + concurrency + ")");
            out.println("Project directory: " + config.directory());
            out.println();
            out.println("Dependencies (task <- prerequisites):");
            for (var task : plan.tasks()) {
                var dependencies = task.needs().isEmpty()
                        ? "(none)" : task.needs().stream()
                                .map(name -> "[" + name + "]")
                                .collect(Collectors.joining(", "));
                out.println("  [" + task.name() + "] <- " + dependencies);
            }
            out.println();
            out.println("Topological order and commands "
                    + "(not a fixed execution schedule):");
            var index = 1;
            for (var task : plan.tasks()) {
                out.println(index++ + ". [" + task.name() + "]");
                out.println(task.command());
                task.daemonDetails().forEach(out::println);
                out.println();
            }
            out.println("Static review only: no commands executed. "
                    + "Configuration validity does not guarantee "
                    + "command safety or tool availability.");
            out.flush();
            return 0;
        } catch (ConfigException | IOException exception) {
            err.println("review: " + exception.getMessage());
            return 1;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            err.println("review: interrupted.");
            return 130;
        }
    }
}
