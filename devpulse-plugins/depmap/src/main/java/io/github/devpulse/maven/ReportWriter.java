package io.github.devpulse.maven;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ReportWriter {
    private ReportWriter() {}

    public static void print(DependencyReport report, PrintWriter out, PrintWriter err) {
        out.println("Depmap - Maven " + report.mavenVersion() + " dependency inventory");
        for (var module : report.modules()) {
            out.println();
            out.println("Module: " + text(module.artifact().toString())
                    + (module.complete() ? "" : " [INCOMPLETE]"));
            out.println("POM: " + text(module.pom()));
            out.println("Project dependencies:");
            printTree(module.projectDependencies(), out, err);
            out.println("Plugin dependencies:");
            if (module.plugins().isEmpty()) {
                out.println("  (none)");
            }
            for (var plugin : module.plugins()) {
                out.println("  Plugin: " + text(plugin.artifact().toString())
                        + " [" + String.join(", ", plugin.kinds()) + "]");
                printTree(plugin.dependencies(), out, err);
            }
            module.issues().forEach(issue -> printIssue(issue, err));
        }
        report.issues().forEach(issue -> printIssue(issue, err));
        out.println();
        out.println(report.complete() ? "Inventory complete."
                : "Inventory incomplete; see errors. Missing trees are not empty dependencies.");
        out.flush();
        err.flush();
    }

    private static void printTree(DependencyReport.Tree tree, PrintWriter out, PrintWriter err) {
        if (tree.root() == null) {
            out.println("    [UNAVAILABLE]");
        } else {
            printNode(tree.root(), "    ", "", out);
        }
        if (!tree.complete()) {
            out.println("    [INCOMPLETE]");
        }
        tree.issues().forEach(issue -> printIssue(issue, err));
    }

    private static void printNode(DependencyReport.Node node, String prefix,
                                  String branch, PrintWriter out) {
        out.println(prefix + branch + text(node.artifact().toString())
                + (node.scope().isEmpty() ? "" : ":" + text(node.scope()))
                + (node.optional() ? " (optional)" : ""));
        var children = node.dependencies();
        for (int index = 0; index < children.size(); index++) {
            var last = index == children.size() - 1;
            printNode(children.get(index),
                    prefix + (branch.isEmpty() ? "" : branch.startsWith("\\") ? "   " : "|  "),
                    last ? "\\- " : "+- ", out);
        }
    }

    private static void printIssue(DependencyReport.Issue issue, PrintWriter err) {
        err.println("depmap: [" + text(issue.stage()) + "] " + text(issue.target())
                + ": " + text(issue.message()));
    }

    public static void writeJson(DependencyReport report, Path output) throws IOException {
        var absolute = output.toAbsolutePath().normalize();
        var temporary = Files.createTempFile(absolute.getParent(), ".devpulse-depmap-", ".json");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().serializeNulls().create()
                        .toJson(report, writer);
                writer.write(System.lineSeparator());
            }
            Files.move(temporary, absolute);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String text(String value) {
        return value.replaceAll("[\\p{Cntrl}\\p{Cf}]", "?");
    }
}
