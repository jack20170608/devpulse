package io.github.devpulse.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.devpulse.bash.EnvironmentGatherer;

/** Builds the JSON document that {@code doctor --format json} prints. */
final class DoctorJson {
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private DoctorJson() {}

    static String report(EnvironmentGatherer.Facts facts) {
        var root = new JsonObject();
        root.addProperty("view", "doctor");
        root.addProperty("schemaVersion", SCHEMA_VERSION);
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
        return GSON.toJson(root);
    }
}
