package io.github.devpulse.core.bash;

import io.github.devpulse.core.dag.DagRun;
import io.github.devpulse.core.dag.DagWorkflow;
import io.github.devpulse.core.dag.TaskKey;
import io.github.devpulse.core.dag.TaskOptions;
import io.github.devpulse.core.environment.BashShell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

public class BashTaskAdapterTest {

    @Test
    void testExecute(@TempDir Path directory) throws Exception {
        var bash = BashShell.discover();
        Files.writeString(directory.resolve("input.txt"), "hello bash\n");
        var output = new StringWriter();
        var key = TaskKey.of("execute", Integer.class);
        var options = new TaskOptions(1, Duration.ZERO, Duration.ofSeconds(10));

        try (var out = new PrintWriter(output)) {
            var adapter = new BashTaskAdapter(bash, directory, out);
            var workflow = DagWorkflow.builder()
                    .task(key, adapter.task("""
                            set -euo pipefail
                            IFS= read -r message < input.txt
                            printf '%s\\n' "$message"
                            printf 'stderr message\\n' >&2
                            printf '%s' "$message" > result.txt
                            printf 'last line without newline'
                            """), options)
                    .build();

            var result = workflow.newRun(1).execute();

            assertThat(result.state()).isEqualTo(DagRun.RunState.SUCCESS);
            assertThat(result.task(key).state()).isEqualTo(DagRun.NodeState.SUCCESS);
            assertThat(result.task(key).error()).isNull();
            assertThat(result.result(key)).isZero();
        }

        assertThat(Files.readString(directory.resolve("result.txt"))).isEqualTo("hello bash");
        assertThat(output.toString()).contains(
                "[execute] Started.",
                "[execute] hello bash",
                "[execute] stderr message",
                "[execute] last line without newline",
                "[execute] Finished with exit code 0.");
    }
}
