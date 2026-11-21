package io.github.devpulse.execution;

import io.github.devpulse.core.bash.BashTaskDefinition;
import io.github.devpulse.core.dag.DaemonOptions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The per-node execution fingerprint recorded in a run's manifest.
 *
 * <p>It covers exactly the inputs that change what a node does, so a retry can invalidate only the
 * nodes that are genuinely affected. Description-like and scheduling-only settings are deliberately
 * excluded: a renamed or re-described task, or a different concurrency limit, does not change any
 * node's result and must not force a rerun.
 */
public final class TaskFingerprints {
    private static final char NUL = '\u0000';

    private TaskFingerprints() {}

    /** Fingerprint every task in the configuration, keyed by task name in configuration order. */
    public static Map<String, String> of(
            List<BashTaskDefinition> tasks, Map<String, DaemonOptions> daemonOptions) {
        var result = new LinkedHashMap<String, String>();
        for (var task : tasks) {
            result.put(task.name(), of(task, daemonOptions.get(task.name())));
        }
        return result;
    }

    static String of(BashTaskDefinition task, DaemonOptions options) {
        var daemon = task.daemon();
        var parts = new ArrayList<String>();
        parts.add("type=" + (daemon == null ? "task" : "daemon"));
        parts.add("cmd=" + task.command());
        parts.add("needs=" + String.join(",", sortedDistinct(task.needs())));
        if (daemon != null) {
            parts.add("ready.cmd=" + daemon.readyCommand());
            parts.add("stop=" + (daemon.stopCommand() == null ? "" : daemon.stopCommand()));
            parts.add("stopTimeout=" + daemon.stopTimeout().toNanos());
            var effective = options == null ? DaemonOptions.DEFAULT : options;
            parts.add("ready.interval=" + effective.checkInterval().toNanos());
            parts.add("ready.timeout=" + effective.readyTimeout().toNanos());
            parts.add("ready.checkTimeout=" + effective.checkTimeout().toNanos());
        }
        return sha256(String.join(String.valueOf(NUL), parts));
    }

    private static List<String> sortedDistinct(List<String> needs) {
        return new ArrayList<>(new TreeSet<>(needs));
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(bytes.length * 2);
            for (var b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by every JRE", exception);
        }
    }
}
