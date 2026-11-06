package io.github.devpulse.cli;

import io.github.devpulse.maven.AnalysisOptions;
import io.github.devpulse.maven.DependencyReport;
import io.github.devpulse.maven.MavenAnalyzer;
import io.github.devpulse.maven.ReportWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.apache.maven.execution.MavenExecutionRequestPopulationException;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "depmap",
        mixinStandardHelpOptions = true,
        versionProvider = DepmapCLI.VersionProvider.class,
        description = "Inspect Maven project and plugin dependency trees."
)
public final class DepmapCommand implements Callable<Integer> {
    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", defaultValue = ".",
            paramLabel = "PROJECT", description = "Project directory or POM (default: current directory).")
    private Path project;

    @Option(names = {"-N", "--non-recursive"}, description = "Analyze only the specified module.")
    private boolean nonRecursive;

    @Option(names = {"-s", "--settings"}, paramLabel = "FILE",
            description = "User settings.xml (default: ~/.m2/settings.xml).")
    private Path settings;

    @Option(names = "--global-settings", paramLabel = "FILE",
            description = "Optional global settings.xml; no Maven installation is consulted.")
    private Path globalSettings;

    @Option(names = "--local-repository", paramLabel = "DIR",
            description = "Override the local repository from settings.xml or -Dmaven.repo.local.")
    private Path localRepository;

    @Option(names = {"-P", "--profiles"}, split = ",", paramLabel = "ID[,ID...]",
            description = "Activate profiles; prefix an ID with ! to deactivate it.")
    private List<String> profiles = new ArrayList<>();

    @Option(names = "-D", mapFallbackValue = "true", paramLabel = "KEY=VALUE",
            description = "Maven user property; repeat for multiple properties.")
    private Map<String, String> properties = Map.of();

    @Option(names = {"-o", "--offline"}, description = "Use only the local repository.")
    private boolean offline;

    @Option(names = "--json", paramLabel = "FILE",
            description = "Also write UTF-8 JSON. The file must not already exist.")
    private Path json;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        var pom = project.toAbsolutePath().normalize();
        if (Files.isDirectory(pom)) {
            pom = pom.resolve("pom.xml");
        }
        if (!Files.isRegularFile(pom) || !Files.isReadable(pom)) {
            err.println("depmap: PROJECT must identify a readable POM or project directory.");
            return 2;
        }
        for (var file : new Path[]{settings, globalSettings}) {
            if (file != null && (!Files.isRegularFile(file) || !Files.isReadable(file))) {
                err.println("depmap: Explicit settings files must exist and be readable.");
                return 2;
            }
        }
        if (profiles.stream().anyMatch(p -> p.isBlank() || p.equals("!") || p.equals("-"))) {
            err.println("depmap: Profile IDs must not be empty.");
            return 2;
        }
        if (properties.containsKey("maven.repo.local")
                && properties.get("maven.repo.local").isBlank()) {
            err.println("depmap: maven.repo.local must not be empty.");
            return 2;
        }
        var repository = localRepository;
        if (repository == null && properties.containsKey("maven.repo.local")) {
            repository = Path.of(properties.get("maven.repo.local"));
        }
        if (repository != null && Files.exists(repository) && !Files.isDirectory(repository)) {
            err.println("depmap: Local repository must be a directory.");
            return 2;
        }
        if (json != null && (Files.exists(json)
                || !Files.isDirectory(json.toAbsolutePath().normalize().getParent()))) {
            err.println("depmap: JSON destination must be a new file in an existing directory.");
            return 2;
        }
        DependencyReport report;
        try {
            report = new MavenAnalyzer().analyze(new AnalysisOptions(pom, !nonRecursive,
                    absolute(settings), absolute(globalSettings), absolute(localRepository),
                    profiles, properties, offline));
        } catch (PlexusContainerException | ComponentLookupException
                 | SettingsBuildingException | MavenExecutionRequestPopulationException exception) {
            report = new DependencyReport(1, MavenAnalyzer.MAVEN_VERSION, pom.toString(), false,
                    List.of(), List.of(new DependencyReport.Issue("initialization", "Maven",
                            "Cannot initialize analysis (" + exception.getClass().getSimpleName()
                                    + "). Check settings, repository access and file permissions.")));
        }
        ReportWriter.print(report, out, err);
        if (json != null) {
            try {
                ReportWriter.writeJson(report, json);
                err.println("depmap: JSON report written.");
            } catch (IOException exception) {
                err.println("depmap: Cannot write JSON report ("
                        + exception.getClass().getSimpleName()
                        + "). Check the output path and permissions.");
                return 1;
            }
        }
        return report.complete() ? 0 : 1;
    }

    private static Path absolute(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }
}
