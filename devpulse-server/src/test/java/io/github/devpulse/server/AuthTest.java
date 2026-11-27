package io.github.devpulse.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Guards the two request checks: the loopback Host match and the client-header requirement. */
class AuthTest {

    @Test
    void acceptsOnlyLoopbackHostsWithTheRightPort() {
        assertThat(Auth.hostAllowed("127.0.0.1:12580", 12580)).isTrue();
        assertThat(Auth.hostAllowed("localhost:12580", 12580)).isTrue();
        assertThat(Auth.hostAllowed("[::1]:12580", 12580)).isTrue();

        assertThat(Auth.hostAllowed("127.0.0.1:12581", 12580)).isFalse();
        assertThat(Auth.hostAllowed("evil.example:12580", 12580)).isFalse();
        assertThat(Auth.hostAllowed(null, 12580)).isFalse();
    }

    @Test
    void requiresANonEmptyClientHeader() {
        assertThat(Auth.clientHeader("web")).isTrue();
        assertThat(Auth.clientHeader("cli")).isTrue();
        assertThat(Auth.clientHeader("")).isFalse();
        assertThat(Auth.clientHeader("   ")).isFalse();
        assertThat(Auth.clientHeader(null)).isFalse();
    }
}
