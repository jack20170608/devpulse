package io.github.devpulse.environment;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

public record BashShell(Path executable) {
    public static BashShell discover(Path override)
            throws IOException, InterruptedException {
        var windows = System.getProperty("os.name")
                .toLowerCase(Locale.ROOT).startsWith("windows");
        if (override != null) {
            var path = override.toAbsolutePath().normalize();
            if ((windows && !isGitBash(path)) || !isBash(path)) {
                throw new IOException("Not a usable Bash executable: " + path);
            }
            return new BashShell(path);
        }

        var candidates = new LinkedHashSet<Path>();
        var pathValue = System.getenv("PATH");
        if (pathValue == null) {
            pathValue = "";
        }
        for (var entry : pathValue.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                var directory = Path.of(entry.replace("\"", ""))
                        .toAbsolutePath().normalize();
                candidates.add(directory.resolve(
                        windows ? "bash.exe" : "bash"));
                if (windows && Files.isRegularFile(
                        directory.resolve("git.exe"))) {
                    addGitCandidates(candidates, directory.getParent());
                }
            } catch (InvalidPathException ignored) {
                // An invalid PATH entry must not hide later valid entries.
            }
        }
        if (windows) {
            for (var variable : List.of("ProgramFiles", "ProgramFiles(x86)",
                    "LOCALAPPDATA")) {
                var base = System.getenv(variable);
                if (base != null) {
                    var root = Path.of(base);
                    addGitCandidates(candidates, root.resolve("Git"));
                    addGitCandidates(candidates,
                            root.resolve("Programs").resolve("Git"));
                }
            }
        } else {
            candidates.add(Path.of("/bin/bash"));
            candidates.add(Path.of("/usr/bin/bash"));
        }

        for (var candidate : candidates) {
            if ((!windows || isGitBash(candidate)) && isBash(candidate)) {
                return new BashShell(candidate);
            }
        }
        throw new IOException("Bash was not found. Install Bash (Git Bash on "
                + "Windows) or specify its executable with --bash.");
    }

    public ProcessBuilder script(Path directory) {
        var builder = new ProcessBuilder(executable.toString(),
                "--noprofile", "--norc", "-s", "--");
        builder.directory(directory.toFile());
        builder.environment().remove("BASH_ENV");
        builder.environment().remove("ENV");
        builder.environment().keySet()
                .removeIf(name -> name.startsWith("BASH_FUNC_"));
        return builder;
    }

    public String capture(String command, Path directory)
            throws IOException, InterruptedException {
        var process = script(directory)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = executor.submit(() -> {
                try (var stream = process.getInputStream()) {
                    return new String(stream.readAllBytes(),
                            StandardCharsets.UTF_8);
                }
            });
            var input = executor.submit(() -> {
                try (var stream = process.getOutputStream()) {
                    stream.write(command.getBytes(StandardCharsets.UTF_8));
                    stream.write('\n');
                }
                return null;
            });
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IOException("Bash environment scan timed out.");
                }
                if (process.exitValue() != 0) {
                    throw new IOException("Bash environment scan failed with "
                            + "exit code " + process.exitValue() + ".");
                }
                input.get(1, TimeUnit.SECONDS);
                return output.get(1, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                throw new IOException("Could not exchange data with Bash.",
                        exception.getCause());
            } catch (TimeoutException exception) {
                throw new IOException("Bash output capture timed out.",
                        exception);
            } finally {
                process.descendants().toList().reversed()
                        .forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.getInputStream().close();
            }
        }
    }

    private static void addGitCandidates(LinkedHashSet<Path> candidates,
            Path root) {
        if (root != null) {
            candidates.add(root.resolve("bin").resolve("bash.exe"));
            candidates.add(root.resolve("usr").resolve("bin")
                    .resolve("bash.exe"));
        }
    }

    private static boolean isGitBash(Path executable) {
        var directory = executable.getParent();
        for (var level = 0; directory != null && level < 4; level++) {
            if (Files.isRegularFile(directory.resolve("cmd")
                    .resolve("git.exe"))
                    || Files.isRegularFile(directory.resolve("bin")
                    .resolve("git.exe"))) {
                return true;
            }
            directory = directory.getParent();
        }
        return false;
    }

    private static boolean isBash(Path executable)
            throws InterruptedException {
        if (!Files.isRegularFile(executable)
                || !Files.isExecutable(executable)) {
            return false;
        }
        Process process;
        try {
            process = new BashShell(executable).script(
                    Path.of("").toAbsolutePath())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException exception) {
            return false;
        }
        try {
            try (var stream = process.getOutputStream()) {
                stream.write("test -n \"$BASH_VERSION\"\n"
                        .getBytes(StandardCharsets.UTF_8));
            }
            return process.waitFor(3, TimeUnit.SECONDS)
                    && process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        } finally {
            process.descendants().toList().reversed()
                    .forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }
}
