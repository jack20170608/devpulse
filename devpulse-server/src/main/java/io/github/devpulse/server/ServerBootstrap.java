package io.github.devpulse.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.devpulse.bash.EnvironmentGatherer;
import io.muserver.Method;
import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the loopback HTTP server: two read-only endpoints, a bearer check and a Host check on
 * every request, and a state file so a client can find the port the OS assigned.
 *
 * <p>The structure mirrors the host-helper reference (app context, bootstrap, handlers, one JSON
 * response helper) without its SSO or database layers; devpulse has no accounts, so it relies on
 * the loopback bind plus the Host and client-header checks in {@link Auth}.
 */
public final class ServerBootstrap {
    public static final String VERSION = implementationVersion();
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerBootstrap.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ServerBootstrap() {}

    /** A running server and the facts a caller needs to reach or stop it. */
    public static final class Running implements AutoCloseable {
        private final MuServer server;
        private final String bootId;
        private final Instant startedAt = Instant.now();

        private Running(MuServer server, String bootId) {
            this.server = server;
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
        }
    }

    public static Running start(ServerSettings settings) throws IOException {
        var bootId = UUID.randomUUID().toString();
        var startedAt = Instant.now();
        var server =
                MuServerBuilder.httpServer()
                        .withInterface(settings.host())
                        .withHttpPort(settings.port())
                        .addHandler(
                                Method.GET,
                                "/api/health",
                                (request, response, params) ->
                                        guarded(
                                                request,
                                                response,
                                                () ->
                                                        healthJson(
                                                                bootId,
                                                                startedAt,
                                                                settings.directory())))
                        .addHandler(
                                Method.GET,
                                "/api/doctor",
                                (request, response, params) ->
                                        guarded(
                                                request,
                                                response,
                                                () -> doctorJson(settings.directory())))
                        .start();
        var handle = new Running(server, bootId);
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
    private static void guarded(MuRequest request, MuResponse response, JsonSupplier body)
            throws IOException {
        if (!Auth.hostAllowed(request.headers().get("Host"), request.serverURI().getPort())) {
            deny(response, 400, "BAD_HOST", "The Host header does not name this server.");
            return;
        }
        if (!Auth.clientHeader(request.headers().get(Auth.CLIENT_HEADER))) {
            deny(
                    response,
                    403,
                    "CLIENT_HEADER_REQUIRED",
                    "Send " + Auth.CLIENT_HEADER + " to identify the client.");
            return;
        }
        response.contentType("application/json");
        response.status(200);
        response.write(GSON.toJson(body.get()));
    }

    private static void deny(MuResponse response, int status, String code, String message)
            throws IOException {
        var error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        var root = new JsonObject();
        root.add("error", error);
        response.contentType("application/json");
        response.status(status);
        response.write(GSON.toJson(root));
    }

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

    @FunctionalInterface
    private interface JsonSupplier {
        JsonObject get() throws IOException;
    }

    private static String implementationVersion() {
        var version = ServerBootstrap.class.getPackage().getImplementationVersion();
        return version == null ? "development" : version;
    }
}
