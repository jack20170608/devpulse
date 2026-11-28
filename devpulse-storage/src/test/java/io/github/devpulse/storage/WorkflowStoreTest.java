package io.github.devpulse.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the workflow registry: every push is an immutable revision, the head moves forward, and a
 * push based on a stale revision is refused instead of overwriting newer content.
 */
class WorkflowStoreTest {
    private JdbiRunStore store;

    @BeforeEach
    void open() throws IOException {
        store = JdbiRunStore.open("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        store.migrate();
    }

    @AfterEach
    void close() throws IOException {
        store.close();
    }

    private static ProjectRecord project(String path) {
        var now = Instant.now();
        return new ProjectRecord(UUID.randomUUID().toString(), path, path, now, now);
    }

    @Test
    void createsTheFirstRevisionWithAContentHash() throws IOException {
        var project = project("/tmp/one");
        var content = "tasks:\n  - id: check\n    spec:\n      run: 'true'\n";

        var head = store.push(project, "build", "Build", content, null);

        assertThat(head.revision()).isEqualTo(1);
        assertThat(head.name()).isEqualTo("Build");
        assertThat(head.contentHash()).isEqualTo(ContentHash.sha256(content));
        var stored = store.findWorkflow(project.id(), "build").orElseThrow();
        assertThat(stored.revision()).isEqualTo(1);
        assertThat(stored.content()).isEqualTo(content);
        assertThat(stored.contentHash()).isEqualTo(head.contentHash());
        assertThat(store.listRevisions(project.id(), "build", 10)).hasSize(1);
    }

    @Test
    void advancesTheHeadAndKeepsEveryRevision() throws IOException {
        var project = project("/tmp/two");
        var first = "tasks:\n  - id: a\n    spec:\n      run: 'true'\n";
        var second = first + "  - id: b\n    spec:\n      run: 'true'\n";

        var revisionOne = store.push(project, "build", null, first, null);
        var revisionTwo = store.push(project, "build", null, second, 1);

        assertThat(revisionTwo.revision()).isEqualTo(2);
        assertThat(store.findWorkflow(project.id(), "build").orElseThrow().content())
                .isEqualTo(second);
        assertThat(store.listRevisions(project.id(), "build", 10))
                .extracting(WorkflowRevisionRecord::revision, WorkflowRevisionRecord::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2, second),
                        org.assertj.core.groups.Tuple.tuple(1, first));
    }

    @Test
    void refusesAStaleBaseRevisionAndLeavesTheHeadAlone() throws IOException {
        var project = project("/tmp/three");
        var content = "tasks:\n  - id: a\n    spec:\n      run: 'true'\n";
        store.push(project, "build", null, content, null);

        assertThatThrownBy(() ->
                        store.push(
                                project,
                                "build",
                                null,
                                content + "  - id: b\n    spec:\n      run: 'true'\n",
                                0))
                .isInstanceOf(WorkflowConflictException.class)
                .satisfies(exception ->
                        assertThat(((WorkflowConflictException) exception).current().revision())
                                .isEqualTo(1));
        assertThat(store.findWorkflow(project.id(), "build").orElseThrow().revision()).isEqualTo(1);
        assertThat(store.listRevisions(project.id(), "build", 10)).hasSize(1);
    }

    @Test
    void aNullBaseRevisionOverwritesTheHead() throws IOException {
        var project = project("/tmp/four");
        store.push(project, "build", null, "tasks: []\n", null);

        var head = store.push(project, "build", null, "tasks: []\n", null);

        assertThat(head.revision()).isEqualTo(2);
    }

    @Test
    void listsWorkflowsPerProjectAndKeepsProjectsApart() throws IOException {
        var one = project("/tmp/five");
        var two = project("/tmp/six");
        store.push(one, "build", null, "tasks: []\n", null);
        store.push(one, "deploy", null, "tasks: []\n", null);
        store.push(two, "build", null, "tasks: []\n", null);

        assertThat(store.listWorkflows(one.id()))
                .extracting(WorkflowRecord::workflowId)
                .containsExactly("build", "deploy");
        assertThat(store.listWorkflows(two.id()))
                .extracting(WorkflowRecord::workflowId)
                .containsExactly("build");
    }

    @Test
    void rejectsAnInvalidWorkflowIdOrBlankContent() {
        var project = project("/tmp/seven");

        assertThatThrownBy(() -> store.push(project, "Build", null, "tasks: []\n", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[a-z][a-z0-9_-]*");
        assertThatThrownBy(() -> store.push(project, "build", null, "   ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }
}
