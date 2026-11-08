package io.github.devpulse.core;

import io.github.devpulse.core.dag.DagWorkflow;
import io.github.devpulse.core.dag.TaskKey;
import io.github.devpulse.core.dag.TaskOptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

public class DagWorkFlowTest {

    @Test
    void simpleTest() {
        try {
            var a = TaskKey.of("A", Integer.class);
            var b = TaskKey.of("B", Integer.class);
            var c = TaskKey.of("C", Integer.class);
            var d = TaskKey.of("D", Integer.class);
            var options = new TaskOptions(3, Duration.ofMillis(100), Duration.ofSeconds(2));

            var workflow = DagWorkflow.builder()
                .task(a, context -> 10)
                .task(b, List.of(a), context -> context.result(a) * 2, options)
                .task(c, List.of(a), context -> context.result(a) + 1)
                .task(d, List.of(b, c), context -> context.result(b) + context.result(c))
                .build();

            var run = workflow.newRun(2, event -> {
                if (event.task() != null) {
                    System.out.println(event.task().id() + ": " + event.result().state());
                }
            });
            var result = run.execute();
            System.out.println(result.state());
            System.out.println(result.result(d)); // 31
        }catch (InterruptedException ie){
            Thread.currentThread().interrupt();
        }
    }
}
