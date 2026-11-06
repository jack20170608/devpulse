package io.github.devpulse.maven;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record AnalysisOptions(
        Path pom,
        boolean recursive,
        Path settings,
        Path globalSettings,
        Path localRepository,
        List<String> profiles,
        Map<String, String> properties,
        boolean offline) {
    public AnalysisOptions {
        profiles = List.copyOf(profiles);
        properties = Map.copyOf(properties);
    }
}
