package io.github.devpulse.maven;

import java.util.List;

public record DependencyReport(
        int schemaVersion,
        String mavenVersion,
        String project,
        boolean complete,
        List<Module> modules,
        List<Issue> issues) {

    public record Coordinates(
            String groupId, String artifactId, String type,
            String classifier, String version) {
        @Override
        public String toString() {
            return groupId + ":" + artifactId + ":" + type + ":"
                    + (classifier.isEmpty() ? "" : classifier + ":") + version;
        }
    }

    public record Node(
            Coordinates artifact, String scope, boolean optional,
            List<Node> dependencies) {}

    public record Tree(boolean complete, Node root, List<Issue> issues) {}

    public record Plugin(Coordinates artifact, List<String> kinds, Tree dependencies) {}

    public record Module(
            String pom, Coordinates artifact, boolean complete,
            Tree projectDependencies, List<Plugin> plugins, List<Issue> issues) {}

    public record Issue(String stage, String target, String message) {}
}
