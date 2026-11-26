package io.github.devpulse.execution;

import io.github.devpulse.core.bash.BashTaskAdapter;
import io.github.devpulse.core.bash.PlanManifest;
import io.github.devpulse.core.bash.RunHistory;
import io.github.devpulse.core.bash.WorkflowRun;
import io.github.devpulse.core.environment.BashShell;
import io.github.devpulse.storage.TaskResult;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Executes one planned run and records it.
 *
 * <p>This is the piece the CLI and a local server must share. Planning and error classification
 * stay with the caller (they decide what an invalid configuration means for the user), and
 * presentation stays there too. Everything in between — adapter binding, run history, the
 * cancellation hook, and the reuse index — lives here, so a second front end does not have to copy
 * it.
 */
public final class RunService {

    /** A finished run: the id it was recorded under, and the result the caller classifies. */
    public record Outcome(String runId, WorkflowRun.Result result) {}

    private RunService() {}

    /**
     * Run the plan to completion.
     *
     * @param shell the validated Bash to execute with; discovery stays with the caller so a missing
     *     Bash keeps its own classification
     * @param console where task output goes; pass a writer over a null stream to keep it silent
     * @param onStarted receives the run id once the record exists, before the first task starts
     */
    public static Outcome execute(
            BashShell shell,
            Path directory,
            String target,
            PulsePlan plan,
            int concurrency,
            PrintWriter console,
            Consumer<String> onStarted)
            throws IOException, InterruptedException {
        Objects.requireNonNull(shell, "shell");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(console, "console");
        Objects.requireNonNull(onStarted, "onStarted");

        var fingerprints = TaskFingerprints.of(plan.tasks(), plan.daemonOptions());
        try (var history =
                new RunHistory(directory)
                        .begin(
                                target,
                                plan.graph(),
                                concurrency,
                                manifest(
                                        target,
                                        directory,
                                        plan,
                                        concurrency,
                                        shell,
                                        fingerprints))) {
            onStarted.accept(history.id());
            var adapter = new BashTaskAdapter(shell, directory, console, history);
            var run = new WorkflowRun(plan.workflow(adapter), history, concurrency);
            var hook = Thread.ofPlatform().unstarted(run::cancel);
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                var result = run.execute();
                recordReuse(history, fingerprints);
                return new Outcome(history.id(), result);
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException exception) {
                    // Shutdown is already in progress; the hook owns cancellation.
                }
            }
        }
    }

    /**
     * Record the immutable inputs of this run: the selected nodes, their execution fingerprints,
     * and the environment. A later retry compares a fresh configuration against this record, so the
     * nodes that actually ran are the ones recorded, not every task in the file.
     */
    private static PlanManifest manifest(
            String target,
            Path directory,
            PulsePlan plan,
            int concurrency,
            BashShell shell,
            Map<String, String> fingerprints) {
        var nodes = new LinkedHashMap<String, PlanManifest.Node>();
        for (var task : plan.tasks()) {
            nodes.put(
                    task.name(),
                    new PlanManifest.Node(
                            task.name(), fingerprints.get(task.name()), task.needs()));
        }
        return new PlanManifest(
                target,
                directory.toString(),
                concurrency,
                shell.executable().toString(),
                RunService.class.getPackage().getImplementationVersion(),
                null,
                nodes);
    }

    /**
     * Publish every successful node to the cross-run reuse index.
     *
     * <p>The index is an optimisation: if an entry cannot be written, a later cached run simply
     * reruns that node, so a failure here must not turn a successful run into a failed one.
     */
    static void recordReuse(RunHistory.Writer history, Map<String, String> fingerprints) {
        var snapshot = history.snapshot();
        for (var task : snapshot.tasks()) {
            if (task.state() != RunHistory.State.SUCCESS) {
                continue;
            }
            var fingerprint = fingerprints.get(task.name());
            if (fingerprint == null) {
                continue;
            }
            try {
                history.store()
                        .recordResult(
                                new TaskResult(
                                        history.projectId(),
                                        task.name(),
                                        fingerprint,
                                        snapshot.id(),
                                        task.finishedAt()));
            } catch (IOException ignored) {
                // Losing an index entry only costs a future rerun.
            }
        }
    }
}
