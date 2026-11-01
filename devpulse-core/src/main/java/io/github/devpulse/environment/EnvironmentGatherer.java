package io.github.devpulse.environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class EnvironmentGatherer {
    private static final List<String> TOOLS = List.of(
            "git", "docker", "kubectl", "helm", "node", "npm", "pnpm",
            "yarn", "java", "mvn", "gradle", "python", "python3", "go",
            "cargo", "make", "terraform");

    private static final List<String> PROJECT_MARKERS = List.of(
            ".git", "package.json", "pom.xml", "build.gradle",
            "build.gradle.kts", "Dockerfile", "compose.yaml", "compose.yml",
            "docker-compose.yaml", "docker-compose.yml", "pyproject.toml",
            "requirements.txt", "go.mod", "Cargo.toml", "Makefile",
            ".devpulse.conf");

    public record Tool(String name, String location) {}

    public record Facts(Path directory, String os, String architecture,
            String javaVersion, BashShell bash, List<Tool> tools,
            List<String> projectFiles) {
        public Facts {
            tools = List.copyOf(tools);
            projectFiles = List.copyOf(projectFiles);
        }
    }

    public Facts gather(Path directory, Path bashOverride)
            throws IOException, InterruptedException {
        var root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("Project directory does not exist: " + root);
        }
        var bash = BashShell.discover(bashOverride);
        var scan = """
                for tool in %s; do
                    location=$(command -v "$tool") || location=
                    printf '%%s\\t%%s\\n' "$tool" "$location"
                done
                """.formatted(String.join(" ", TOOLS));
        var output = bash.capture(scan, root);
        var tools = new ArrayList<Tool>();
        for (var line : output.lines().toList()) {
            var fields = line.split("\t", 2);
            if (fields.length == 2 && TOOLS.contains(fields[0])) {
                tools.add(new Tool(fields[0], fields[1]));
            }
        }
        if (tools.size() != TOOLS.size()) {
            throw new IOException(
                    "Bash returned an incomplete tool inventory.");
        }
        var markers = PROJECT_MARKERS.stream()
                .filter(name -> Files.exists(root.resolve(name))).toList();
        return new Facts(root, System.getProperty("os.name"),
                System.getProperty("os.arch"),
                System.getProperty("java.version"), bash, tools, markers);
    }
}
