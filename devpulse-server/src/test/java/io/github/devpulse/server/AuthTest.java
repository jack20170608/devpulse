package io.github.devpulse.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Guards the two request checks: the bearer match and the loopback Host match. */
class AuthTest {

    @Test
    void acceptsTheExactBearerToken() {
        assertThat(Auth.bearer("Bearer abc123", "abc123")).isTrue();
    }

    @Test
    void rejectsMissingOrDifferentTokens() {
        assertThat(Auth.bearer(null, "abc123")).isFalse();
        assertThat(Auth.bearer("Bearer abc124", "abc123")).isFalse();
        assertThat(Auth.bearer("Basic abc123", "abc123")).isFalse();
        assertThat(Auth.bearer("abc123", "abc123")).isFalse();
    }

    @Test
    void acceptsOnlyLoopbackHostsWithTheRightPort() {
        assertThat(Auth.hostAllowed("127.0.0.1:5000", 5000)).isTrue();
        assertThat(Auth.hostAllowed("localhost:5000", 5000)).isTrue();
        assertThat(Auth.hostAllowed("[::1]:5000", 5000)).isTrue();

        assertThat(Auth.hostAllowed("127.0.0.1:5001", 5000)).isFalse();
        assertThat(Auth.hostAllowed("evil.example:5000", 5000)).isFalse();
        assertThat(Auth.hostAllowed(null, 5000)).isFalse();
    }
}
