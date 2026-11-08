package io.github.devpulse.core;

import java.util.ArrayList;
import java.util.List;

import io.github.devpulse.core.bash.BashTaskDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BashTaskDefinitionTest {

    @Test
    void normalizesScriptAndSnapshotsDependencies() {
        var dependencies = new ArrayList<>(
                List.of("prepare", "check", "prepare"));

        var task = new BashTaskDefinition(
                "build", "echo start\r\nmvn package\r\n", dependencies);

        dependencies.clear();

        assertThat(task.command())
                .isEqualTo("echo start\nmvn package\n");
        assertThat(task.needs())
                .containsExactly("prepare", "check");
        assertThatThrownBy(() -> task.needs().add("deploy"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
