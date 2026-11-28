package io.github.devpulse.bash;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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

    public static BashShell discover() throws IOException, InterruptedException {
        return discover(null);
    }

    public static BashShell discover(Path override) throws IOException, InterruptedException {
        var windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
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
                var directory = Path.of(entry.replace("\"", "")).toAbsolutePath().normalize();
                candidates.add(directory.resolve(windows ? "bash.exe" : "bash"));
                if (windows && Files.isRegularFile(directory.resolve("git.exe"))) {
                    addGitCandidates(candidates, directory.getParent());
                }
            } catch (InvalidPathException ignored) {
                // An invalid PATH entry must not hide later valid entries.
            }
        }
        if (windows) {
            for (var variable : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
                var base = System.getenv(variable);
                if (base != null) {
                    var root = Path.of(base);
                    addGitCandidates(candidates, root.resolve("Git"));
                    addGitCandidates(candidates, root.resolve("Programs").resolve("Git"));
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
        throw new IOException(
                "Bash was not found. Install Bash (Git Bash on "
                        + "Windows) or specify its executable with --bash.");
    }

    public ProcessBuilder script(Path directory) {
        var builder =
                new ProcessBuilder(executable.toString(), "--noprofile", "--norc", "-s", "--");
        builder.directory(directory.toFile());
        builder.environment().remove("BASH_ENV");
        builder.environment().remove("ENV");
        builder.environment().keySet().removeIf(name -> name.startsWith("BASH_FUNC_"));
        return builder;
    }

    /**
     * Executes a command in the Bash shell and captures its output.
     *
     * @param command the command to execute
     * @param directory the working directory
     * @return the output of the command
     * @throws IOException if an I/O error occurs
     * @throws InterruptedException if the process is interrupted
     */
    public String capture(String command, Path directory) throws IOException, InterruptedException {
        // 设定最大保留字节数，例如：标准输出最多保留 1MB，错误输出最多保留 64KB
        int maxStdoutBytes = 1024 * 1024;
        int maxStderrBytes = 64 * 1024;

        var process = script(directory).start();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 读取标准输出 (stdout) 并限制大小
            var output =
                    executor.submit(() -> {
                        try (var stream = process.getInputStream()) {
                            return readWithLimit(stream, maxStdoutBytes);
                        }
                    });

            // 读取标准错误 (stderr) 并限制大小
            var error =
                    executor.submit(() -> {
                        try (var stream = process.getErrorStream()) {
                            return readWithLimit(stream, maxStderrBytes);
                        }
                    });

            // 写入命令 (stdin)
            var input =
                    executor.submit(() -> {
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
                    String errorMessage = "";
                    try {
                        errorMessage = error.get(1, TimeUnit.SECONDS).trim();
                    } catch (Exception ignored) {
                    }
                    throw new IOException(
                            "Bash environment scan failed with exit code "
                                    + process.exitValue()
                                    + ". Error output: "
                                    + errorMessage);
                }

                input.get(1, TimeUnit.SECONDS);
                return output.get(1, TimeUnit.SECONDS);

            } catch (ExecutionException exception) {
                throw new IOException("Could not exchange data with Bash.", exception.getCause());
            } catch (TimeoutException exception) {
                throw new IOException("Bash output capture timed out.", exception);
            } finally {
                process.descendants().toList().reversed().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.getInputStream().close();
                process.getErrorStream().close();
            }
        }
    }

    /** 安全读取流：最多保留 maxBytes 字节的数据，超出的部分继续读取并丢弃（防止管道阻塞） */
    private String readWithLimit(InputStream stream, int maxBytes) throws IOException {
        var buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192]; // 8KB 每次读取的缓冲块
        int totalRead = 0;
        boolean truncated = false;
        int bytesRead;

        while ((bytesRead = stream.read(chunk)) != -1) {
            if (totalRead < maxBytes) {
                // 计算当前块还能往内存里放多少字节
                int bytesToWrite = Math.min(bytesRead, maxBytes - totalRead);
                buffer.write(chunk, 0, bytesToWrite);
                totalRead += bytesToWrite;

                // 如果当前块没写完，说明已经达到上限
                if (bytesToWrite < bytesRead) {
                    truncated = true;
                }
            } else {
                // 超过上限后，不再写入 ByteArrayOutputStream，但依然保持 while 循环读取
                // 这样可以把操作系统的管道排空，防止子进程死锁
                truncated = true;
            }
        }

        String result = buffer.toString(StandardCharsets.UTF_8);
        if (truncated) {
            result += "\n... [Output truncated, exceeded " + maxBytes + " bytes limit]";
        }
        return result;
    }

    private static void addGitCandidates(LinkedHashSet<Path> candidates, Path root) {
        if (root != null) {
            candidates.add(root.resolve("bin").resolve("bash.exe"));
            candidates.add(root.resolve("usr").resolve("bin").resolve("bash.exe"));
        }
    }

    private static boolean isGitBash(Path executable) {
        var directory = executable.getParent();
        for (var level = 0; directory != null && level < 4; level++) {
            if (Files.isRegularFile(directory.resolve("cmd").resolve("git.exe"))
                    || Files.isRegularFile(directory.resolve("bin").resolve("git.exe"))) {
                return true;
            }
            directory = directory.getParent();
        }
        return false;
    }

    private static boolean isBash(Path executable) throws InterruptedException {
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            return false;
        }
        Process process;
        try {
            process =
                    new BashShell(executable)
                            .script(Path.of("").toAbsolutePath())
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
        } catch (IOException exception) {
            return false;
        }
        try {
            try (var stream = process.getOutputStream()) {
                stream.write("test -n \"$BASH_VERSION\"\n".getBytes(StandardCharsets.UTF_8));
            }
            return process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        } finally {
            process.descendants().toList().reversed().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }
}
