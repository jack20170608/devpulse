package io.github.devpulse.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.devpulse.core.environment.EnvironmentGatherer;
import io.github.devpulse.storage.JdbiRunStore;
import io.github.devpulse.storage.ProjectRecord;
import io.github.devpulse.storage.WorkflowConflictException;
import io.github.devpulse.storage.WorkflowRecord;
import io.github.devpulse.storage.WorkflowRevisionRecord;
import io.muserver.Method;
import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the loopback HTTP server: the read-only environment endpoints and the workflow registry,
 * with the bearer and Host checks on every request, plus a state file so a client can find the port
 * the OS assigned.
 *
 * <p>The workflow endpoints make the server the source of truth for workflow content. A project's
 * local file is a twin: a push writes an immutable revision, and a stale base revision is refused
 * with the current head so the caller can show both sides instead of overwriting blindly.
 */
public final class ServerBootstrap {
    public static final String VERSION = implementationVersion();
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerBootstrap.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int DEFAULT_REVISIONS = 20;
    private static final int MAX_REVISIONS = 100;

    private ServerBootstrap() {}

    /** A running server and the facts a caller needs to reach or stop it. */
    public static final class Running implements AutoCloseable {
        private final MuServer server;
        private final JdbiRunStore store;
        private final String bootId;
        private final Instant startedAt = Instant.now();

        private Running(MuServer server, JdbiRunStore store, String bootId) {
            this.server = server;
            this.store = store;
            this.bootId = bootId;
        }

        public int port() {
            return server.uri().getPort();
        }

        public String bootId() {
            return bootId;
        }

        public Duration uptime() {
            return Duration.between(startedAt, Instant.now());
        }

        @Override
        public void close() {
            LOGGER.info("Shutting down (boot {})", bootId);
            server.stop();
            try {
                store.close();
            } catch (IOException exception) {
                LOGGER.warn("Could not close the workflow store: {}", exception.getMessage());
            }
        }
    }

    public static Running start(ServerSettings settings) throws IOException {
        var store = JdbiRunStore.pooled(settings.database());
        try {
            store.migrate();
        } catch (IOException | RuntimeException exception) {
            store.close();
            throw exception;
        }
        var bootId = UUID.randomUUID().toString();
        var startedAt = Instant.now();
        MuServer server;
        try {
            server =
                    MuServerBuilder.httpServer()
                            .withInterface(settings.host())
                            .withHttpPort(settings.port())
                            .addHandler(
                                    Method.GET,
                                    "/api/health",
                                    (request, response, params) -> {
                                        if (!guard(request, response)) {
                                            return;
                                        }
                                        json(
                                                response,
                                                200,
                                                healthJson(
                                                        bootId, startedAt, settings.directory()));
                                    })
                            .addHandler(
                                    Method.GET,
                                    "/api/doctor",
                                    (request, response, params) -> {
                                        if (!guard(request, response)) {
                                            return;
                                        }
                                        json(response, 200, doctorJson(settings.directory()));
                                    })
                            .addHandler(
                                    Method.GET,
                                    "/api/workflows",
                                    (request, response, params) ->
                                            listWorkflows(request, response, store))
                            .addHandler(
                                    Method.GET,
                                    "/api/workflows/{id}",
                                    (request, response, params) ->
                                            getWorkflow(request, response, params, store))
                            .addHandler(
                                    Method.PUT,
                                    "/api/workflows/{id}",
                                    (request, response, params) ->
                                            pushWorkflow(request, response, params, store))
                            .addHandler(
                                    Method.GET,
                                    "/api/workflows/{id}/revisions",
                                    (request, response, params) ->
                                            listRevisions(request, response, params, store))
                            .start();
        } catch (RuntimeException exception) {
            store.close();
            throw exception;
        }
        var handle = new Running(server, store, bootId);
        new ServerState(ProcessHandle.current().pid(), handle.port(), bootId, VERSION, startedAt)
                .write(settings.stateFile());
        LOGGER.info(
                "Listening on http://127.0.0.1:{} (boot {}, version {})",
                handle.port(),
                bootId,
                VERSION);
        return handle;
    }

    /** Run a handler only after the Host and bearer checks pass. */
    private static boolean guard(MuRequest request, MuResponse response) throws IOException {
        if (!Auth.hostAllowed(request.headers().get("Host"), request.serverURI().getPort())) {
            deny(response, 400, "BAD_HOST", "The Host header does not name this server.");
            return false;
        }
        if (!Auth.clientHeader(request.headers().get(Auth.CLIENT_HEADER))) {
            deny(
                    response,
                    403,
                    "CLIENT_HEADER_REQUIRED",
                    "Send " + Auth.CLIENT_HEADER + " to identify the client.");
            return false;
        }
        return true;
    }

    // --- workflow endpoints ---------------------------------------------------

    private static void listWorkflows(MuRequest request, MuResponse response, JdbiRunStore store)
            throws IOException {
        if (!guard(request, response)) {
            return;
        }
        var project = project(request, response);
        if (project == null) {
            return;
        }
        var workflows = new JsonArray();
        for (var workflow : store.listWorkflows(project.id())) {
            workflows.add(summary(workflow));
        }
        var root = new JsonObject();
        root.addProperty("view", "workflows");
        root.add("project", projectJson(project));
        root.add("workflows", workflows);
        json(response, 200, root);
    }

    private static void getWorkflow(
            MuRequest request, MuResponse response, Map<String, String> params, JdbiRunStore store)
            throws IOException {
        if (!guard(request, response)) {
            return;
        }
        var project = project(request, response);
        if (project == null) {
            return;
        }
        var id = params.get("id");
        var revision = request.query().get("revision");
        try {
            if (revision != null && !revision.isBlank()) {
                var found = store.findRevision(project.id(), id, Integer.parseInt(revision));
                if (found.isEmpty()) {
                    deny(response, 404, "TARGET_INVALID", "No revision " + revision + " of " + id);
                    return;
                }
                respond(response, project, found.get());
                return;
            }
            var head = store.findWorkflow(project.id(), id);
            if (head.isEmpty()) {
                deny(response, 404, "TARGET_INVALID", "Workflow not found: " + id);
                return;
            }
            respond(response, project, head.get());
        } catch (NumberFormatException exception) {
            deny(response, 400, "USAGE", "revision must be an integer.");
        } catch (IOException exception) {
            fail(response, exception);
        }
    }

    private static void pushWorkflow(
            MuRequest request, MuResponse response, Map<String, String> params, JdbiRunStore store)
            throws IOException {
        if (!guard(request, response)) {
            return;
        }
        JsonObject body;
        try {
            body = JsonParser.parseString(request.readBodyAsString()).getAsJsonObject();
        } catch (RuntimeException exception) {
            deny(response, 400, "USAGE", "The request body must be one JSON object.");
            return;
        }
        var projectPath =
                body.has("project") && !body.get("project").isJsonNull()
                        ? body.get("project").getAsString()
                        : request.query().get("project");
        ProjectRecord project;
        try {
            if (projectPath == null || projectPath.isBlank()) {
                deny(response, 400, "USAGE", "project is required.");
                return;
            }
            project = project(projectPath);
        } catch (IOException exception) {
            deny(response, 404, "TARGET_INVALID", exception.getMessage());
            return;
        }
        var id = params.get("id");
        var content =
                body.has("content") && !body.get("content").isJsonNull()
                        ? body.get("content").getAsString()
                        : null;
        var name =
                body.has("name") && !body.get("name").isJsonNull()
                        ? body.get("name").getAsString()
                        : null;
        Integer baseRevision = null;
        if (body.has("baseRevision") && !body.get("baseRevision").isJsonNull()) {
            try {
                baseRevision = body.get("baseRevision").getAsInt();
            } catch (RuntimeException exception) {
                deny(response, 400, "USAGE", "baseRevision must be an integer.");
                return;
            }
        }
        try {
            var head = store.push(project, id, name, content, baseRevision);
            respond(response, project, head);
        } catch (WorkflowConflictException exception) {
            var error = new JsonObject();
            error.addProperty("code", "WORKFLOW_CONFLICT");
            error.addProperty("message", exception.getMessage());
            error.addProperty("currentRevision", exception.current().revision());
            error.addProperty("currentHash", exception.current().contentHash());
            var root = new JsonObject();
            root.add("error", error);
            json(response, 409, root);
        } catch (IllegalArgumentException exception) {
            deny(response, 422, "CONFIG_INVALID", exception.getMessage());
        } catch (IOException exception) {
            fail(response, exception);
        }
    }

    private static void listRevisions(
            MuRequest request, MuResponse response, Map<String, String> params, JdbiRunStore store)
            throws IOException {
        if (!guard(request, response)) {
            return;
        }
        var project = project(request, response);
        if (project == null) {
            return;
        }
        var id = params.get("id");
        var limit = DEFAULT_REVISIONS;
        var rawLimit = request.query().get("limit");
        if (rawLimit != null && !rawLimit.isBlank()) {
            try {
                limit = Math.clamp(Integer.parseInt(rawLimit), 1, MAX_REVISIONS);
            } catch (NumberFormatException exception) {
                deny(response, 400, "USAGE", "limit must be an integer.");
                return;
            }
        }
        try {
            var revisions = new JsonArray();
            for (var revision : store.listRevisions(project.id(), id, limit)) {
                revisions.add(revisionSummary(revision));
            }
            var root = new JsonObject();
            root.addProperty("view", "workflowRevisions");
            root.add("project", projectJson(project));
            root.addProperty("workflowId", id);
            root.add("revisions", revisions);
            json(response, 200, root);
        } catch (IOException exception) {
            fail(response, exception);
        }
    }

    // --- JSON helpers ---------------------------------------------------------

    private static ProjectRecord project(MuRequest request, MuResponse response)
            throws IOException {
        var path = request.query().get("project");
        if (path == null || path.isBlank()) {
            deny(response, 400, "USAGE", "project is required.");
            return null;
        }
        try {
            return project(path);
        } catch (IOException exception) {
            deny(response, 404, "TARGET_INVALID", exception.getMessage());
            return null;
        }
    }

    private static ProjectRecord project(String directory) throws IOException {
        var path = Path.of(directory).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IOException("Project directory does not exist: " + path);
        }
        var name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        var id =
                UUID.nameUUIDFromBytes(path.toString().getBytes(StandardCharsets.UTF_8)).toString();
        var now = Instant.now();
        return new ProjectRecord(id, path.toString(), name, now, now);
    }

    private static void respond(MuResponse response, ProjectRecord project, WorkflowRecord head)
            throws IOException {
        var root = new JsonObject();
        root.addProperty("view", "workflow");
        root.add("project", projectJson(project));
        root.add("workflow", detail(head));
        json(response, 200, root);
    }

    private static void respond(
            MuResponse response, ProjectRecord project, WorkflowRevisionRecord revision)
            throws IOException {
        var root = new JsonObject();
        root.addProperty("view", "workflow");
        root.add("project", projectJson(project));
        var detail = revisionSummary(revision);
        detail.addProperty("content", revision.content());
        root.add("workflow", detail);
        json(response, 200, root);
    }

    private static JsonObject projectJson(ProjectRecord project) {
        var json = new JsonObject();
        json.addProperty("id", project.id());
        json.addProperty("path", project.path());
        json.addProperty("name", project.name());
        return json;
    }

    private static JsonObject summary(WorkflowRecord workflow) {
        var json = new JsonObject();
        json.addProperty("id", workflow.workflowId());
        if (workflow.name() != null) {
            json.addProperty("name", workflow.name());
        }
        json.addProperty("revision", workflow.revision());
        json.addProperty("hash", workflow.contentHash());
        json.addProperty("updatedAt", workflow.updatedAt().toString());
        return json;
    }

    private static JsonObject detail(WorkflowRecord workflow) {
        var json = summary(workflow);
        json.addProperty("createdAt", workflow.createdAt().toString());
        json.addProperty("content", workflow.content());
        return json;
    }

    private static JsonObject revisionSummary(WorkflowRevisionRecord revision) {
        var json = new JsonObject();
        json.addProperty("id", revision.workflowId());
        json.addProperty("revision", revision.revision());
        json.addProperty("hash", revision.contentHash());
        json.addProperty("createdAt", revision.createdAt().toString());
        return json;
    }

    private static void json(MuResponse response, int status, JsonObject body) throws IOException {
        response.contentType("application/json");
        response.status(status);
        response.write(GSON.toJson(body));
    }

    private static void deny(MuResponse response, int status, String code, String message)
            throws IOException {
        var error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        var root = new JsonObject();
        root.add("error", error);
        json(response, status, root);
    }

    private static void fail(MuResponse response, IOException exception) throws IOException {
        deny(response, 500, "INFRASTRUCTURE", exception.getMessage());
    }

    // --- environment endpoints ------------------------------------------------

    private static JsonObject healthJson(
            String bootId, Instant startedAt, java.nio.file.Path directory) throws IOException {
        var root = new JsonObject();
        root.addProperty("view", "health");
        root.addProperty("version", VERSION);
        root.addProperty("bootId", bootId);
        root.addProperty("uptimeMs", Duration.between(startedAt, Instant.now()).toMillis());
        root.addProperty("pid", ProcessHandle.current().pid());
        root.addProperty("directory", directory.toString());
        return root;
    }

    /**
     * The environment report, using the same source as {@code devpulse doctor}.
     *
     * <p>A missing Bash is reported as a field rather than a failure: the endpoint answers, and the
     * client decides what to do about an environment that cannot run tasks.
     */
    private static JsonObject doctorJson(java.nio.file.Path directory) throws IOException {
        var root = new JsonObject();
        root.addProperty("view", "doctor");
        try {
            var facts = new EnvironmentGatherer().gather(directory, null);
            var environment = new JsonObject();
            environment.addProperty("os", facts.os());
            environment.addProperty("java", facts.javaVersion());
            environment.addProperty("bash", facts.bash().executable().toString());
            root.add("environment", environment);
            var tools = new JsonArray();
            for (var tool : facts.tools()) {
                var item = new JsonObject();
                item.addProperty("name", tool.name());
                item.addProperty("available", !tool.location().isEmpty());
                item.addProperty("location", tool.location());
                tools.add(item);
            }
            root.add("tools", tools);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            root.addProperty("error", "environment check interrupted");
        } catch (IOException exception) {
            root.addProperty("error", exception.getMessage());
        }
        return root;
    }

    private static String implementationVersion() {
        var version = ServerBootstrap.class.getPackage().getImplementationVersion();
        return version == null ? "development" : version;
    }
}
