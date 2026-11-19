package io.github.devpulse.core.bash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.devpulse.core.dag.DagDaemon.Health;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the readiness state machine behind {@code DaemonManager.check()} without spawning a real
 * process, so reordering these outcomes is caught by a test instead of by a broken workflow.
 */
class DaemonManagerTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("decisions")
    void decidesHealthAfterReadinessCheck(
            String description,
            boolean alive,
            int checkCode,
            boolean alreadyReady,
            Health expected) {
        assertThat(DaemonManager.healthAfterCheck(alive, checkCode, alreadyReady))
                .isEqualTo(expected);
    }

    static Stream<Arguments> decisions() {
        return Stream.of(
                arguments("died during the readiness check", false, 0, false, Health.EXITED),
                arguments("died after becoming ready", false, 0, true, Health.EXITED),
                arguments("died with a failing check", false, 1, false, Health.EXITED),
                arguments("readiness check passed", true, 0, false, Health.READY),
                arguments("readiness check passed again", true, 0, true, Health.READY),
                arguments("still starting", true, 1, false, Health.STARTING),
                arguments("was ready, now failing", true, 1, true, Health.UNAVAILABLE));
    }
}
