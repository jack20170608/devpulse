package io.github.devpulse.jira.cli;

import static io.github.devpulse.jira.cli.JiraCommand.nonblank;
import static io.github.devpulse.jira.cli.JiraCommand.string;
import static io.github.devpulse.jira.cli.JiraCommand.text;
import static io.github.devpulse.jira.cli.JiraCommand.wrong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.devpulse.jira.JiraClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import picocli.AutoComplete;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

public final class JiraIssueExtras {
    private JiraIssueExtras() {}

    static JsonObject attachmentUploadData(String key,
            JiraClient.AttachmentUpload upload) {
        var result = new JsonObject();
        result.addProperty("key", key);
        result.addProperty("filename", upload.filename());
        result.addProperty("size", upload.size());
        result.addProperty("mimeType", upload.contentType());
        result.addProperty("sha256", upload.sha256());
        var attachments = new JsonArray();
        for (var value : upload.attachments()) {
            attachments.add(attachmentMetadata(value));
        }
        result.add("attachments", attachments);
        return result;
    }

    private static JsonObject attachmentMetadata(JsonElement value) {
        var source = value.getAsJsonObject();
        var result = new JsonObject();
        for (var field : new String[]{"id", "filename", "size",
                "mimeType", "created"}) {
            if (source.has(field)) {
                result.add(field, source.get(field).deepCopy());
            }
        }
        return result;
    }

    @Command(name = "attachment", mixinStandardHelpOptions = true,
            description = "Manage issue attachments.",
            subcommands = {AttachmentAdd.class, AttachmentList.class,
                    AttachmentDelete.class})
    public static final class Attachment implements Runnable {
        @Spec CommandSpec spec;
        @Override public void run() {
            spec.commandLine().usage(spec.commandLine().getOut());
        }
    }

    @Command(name = "add", mixinStandardHelpOptions = true,
            description = "Upload one file to an issue.")
    public static final class AttachmentAdd extends JiraCommand.Action {
        @Parameters(index = "0", paramLabel = "KEY")
        String key;
        @Option(names = "--file", required = true, paramLabel = "PATH")
        Path file;

        @Override protected void execute() throws IOException {
            key = nonblank(key, "Issue key");
            var upload = client().attach(key, file);
            machineData = attachmentUploadData(key, upload);
            print("Attached " + file.getFileName() + " to " + key + ".");
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true,
            description = "List attachment metadata for an issue.")
    public static final class AttachmentList extends JiraCommand.Action {
        @Parameters(index = "0", paramLabel = "KEY")
        String key;

        @Override protected void execute() throws IOException {
            key = nonblank(key, "Issue key");
            var values = client().attachments(key);
            var attachments = new JsonArray();
            for (var value : values) {
                attachments.add(attachmentMetadata(value));
            }
            if (machine()) {
                var result = new JsonObject();
                result.addProperty("key", key);
                result.add("attachments", attachments);
                machineData = result;
            } else {
                for (var value : attachments) {
                    var attachment = value.getAsJsonObject();
                    print(string(attachment, "id") + " "
                            + string(attachment, "filename") + " "
                            + string(attachment, "size") + " "
                            + string(attachment, "mimeType"));
                }
            }
        }
    }

    @Command(name = "delete", mixinStandardHelpOptions = true,
            description = "Delete an attachment after verifying its issue.")
    public static final class AttachmentDelete extends JiraCommand.Action {
        @Parameters(index = "0", paramLabel = "KEY")
        String key;
        @Parameters(index = "1", paramLabel = "ATTACHMENT_ID")
        String id;

        @Override protected void execute() throws IOException {
            key = nonblank(key, "Issue key");
            id = nonblank(id, "Attachment ID");
            var jira = client();
            var found = jira.attachments(key).asList().stream()
                    .anyMatch(value -> value.isJsonObject()
                            && value.getAsJsonObject().has("id")
                            && value.getAsJsonObject().get("id").getAsString()
                                    .equals(id));
            if (!found) {
                throw wrong("Attachment ID does not belong to the specified issue.");
            }
            jira.deleteAttachment(id);
            var result = new JsonObject();
            result.addProperty("key", key);
            result.addProperty("attachmentId", id);
            result.addProperty("deleted", true);
            machineData = result;
            print("Deleted attachment " + id + " from " + key + ".");
        }
    }

    @Command(name = "comment", mixinStandardHelpOptions = true,
            description = "Manage issue comments.", subcommands = CommentAdd.class)
    public static final class Comment implements Runnable {
        @Spec CommandSpec spec;
        @Override public void run() {
            spec.commandLine().usage(spec.commandLine().getOut());
        }
    }

    @Command(name = "add", mixinStandardHelpOptions = true,
            description = "Add a comment from text, a template, stdin, or a prompt.")
    public static final class CommentAdd extends JiraCommand.Action {
        @Parameters(index = "0", arity = "0..1", paramLabel = "KEY")
        String key;
        @Parameters(index = "1", arity = "0..1", paramLabel = "TEXT",
                description = "Comment text; takes precedence over --template.")
        String body;
        @Option(names = "--template", paramLabel = "FILE")
        Path template;
        @Option(names = "--internal",
                description = "Mark as an internal Jira Service Management comment.")
        boolean internal;
        @Option(names = "--no-input")
        boolean noInput;
        @Mixin JiraCommand.BodyFormat bodyFormat = new JiraCommand.BodyFormat();

        @Override protected void execute() throws IOException {
            key = required(this, key, "Issue key", noInput);
            issueKey(key);
            if (body == null && template != null) {
                body = template.toString().equals("-") ? stdin() : text(template);
            }
            if (body == null && !machine() && System.console() == null) {
                body = stdin();
            }
            body = required(this, body, "Comment", noInput);
            var jira = client();
            var data = new JsonObject();
            data.add("body", bodyFormat.toJira(body, jira));
            if (internal) {
                var property = new JsonObject();
                property.addProperty("key", "sd.public.comment");
                var value = new JsonObject();
                value.addProperty("internal", true);
                property.add("value", value);
                var properties = new JsonArray();
                properties.add(property);
                data.add("properties", properties);
            }
            var created = object(jira.request("POST",
                    "issue/" + key + "/comment", data));
            var result = new JsonObject();
            result.addProperty("key", key);
            if (created.has("id")) {
                result.add("commentId", created.get("id").deepCopy());
            }
            result.addProperty("created", true);
            machineData = result;
            print("Commented on " + key + ".");
        }
    }

    @Command(name = "worklog", mixinStandardHelpOptions = true,
            description = "Manage issue worklogs.", subcommands = WorklogAdd.class)
    public static final class Worklog implements Runnable {
        @Spec CommandSpec spec;
        @Override public void run() {
            spec.commandLine().usage(spec.commandLine().getOut());
        }
    }

    @Command(name = "add", mixinStandardHelpOptions = true,
            description = "Log time using Jira duration syntax, e.g. '2d 3h 30m'.")
    public static final class WorklogAdd extends JiraCommand.Action {
        @Parameters(index = "0", arity = "0..1", paramLabel = "KEY")
        String key;
        @Parameters(index = "1", arity = "0..1", paramLabel = "DURATION")
        String duration;
        @Option(names = "--comment", paramLabel = "TEXT")
        String comment;
        @Option(names = "--started", paramLabel = "ISO_OFFSET_DATE_TIME",
                description = "Start time including offset; defaults to now.")
        String started;
        @Option(names = "--no-input")
        boolean noInput;
        @Mixin JiraCommand.BodyFormat bodyFormat = new JiraCommand.BodyFormat();

        @Override protected void execute() throws IOException {
            key = required(this, key, "Issue key", noInput);
            issueKey(key);
            duration = required(this, duration, "Time spent (e.g. 2h 30m)", noInput);
            if (!duration.matches("(?:[0-9]+[wdhm]\\s*)+")
                    || !duration.matches(".*[1-9].*")) {
                throw wrong("DURATION must be a positive Jira duration, e.g. 2h 30m.");
            }
            OffsetDateTime time;
            try {
                time = started == null ? OffsetDateTime.now()
                        : OffsetDateTime.parse(started);
            } catch (java.time.format.DateTimeParseException exception) {
                throw wrong("--started must be an ISO date/time with an offset.");
            }
            var jira = client();
            var data = new JsonObject();
            data.addProperty("timeSpent", duration);
            data.addProperty("started", time.format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")));
            if (comment != null) {
                data.add("comment", bodyFormat.toJira(
                        nonblank(comment, "--comment"), jira));
            }
            var created = object(jira.request("POST",
                    "issue/" + key + "/worklog", data));
            var result = new JsonObject();
            result.addProperty("key", key);
            if (created.has("id")) {
                result.add("worklogId", created.get("id").deepCopy());
            }
            result.addProperty("created", true);
            machineData = result;
            print("Logged time on " + key + ".");
        }
    }

    @Command(name = "me", mixinStandardHelpOptions = true,
            description = "Print the current Cloud accountId or DC username.")
    public static final class Me extends JiraCommand.Action {
        @Override protected void execute() throws IOException {
            var jira = client();
            var identifier = jira.cloud() ? "accountId" : "name";
            var value = nonblank(string(jira.myself(), identifier), "Current user");
            var result = new JsonObject();
            result.addProperty(identifier, value);
            machineData = result;
            print(value);
        }
    }

    @Command(name = "completion", mixinStandardHelpOptions = true,
            description = "Print a Bash or Zsh completion script; source the output.")
    public static final class Completion extends JiraCommand.Action {
        enum Shell { bash, zsh }
        @Parameters(index = "0", arity = "0..1", defaultValue = "bash")
        Shell shell;
        @Override protected void execute() {
            if (shell == Shell.zsh) {
                print("#compdef jira\nautoload -Uz bashcompinit\nbashcompinit");
            }
            print(AutoComplete.bash("jira", spec.root().commandLine()));
        }
    }

    static String required(JiraCommand.Action action, String value,
            String label, boolean noInput) throws IOException {
        if (value != null && !value.isBlank()) {
            return value;
        }
        var console = System.console();
        if (noInput || console == null) {
            throw wrong(label + " is required; supply it explicitly or use a terminal.");
        }
        action.spec.commandLine().getErr().print(label + ": ");
        action.spec.commandLine().getErr().flush();
        var answer = console.readLine();
        if (answer == null) {
            throw wrong("Input ended; no changes were submitted.");
        }
        return nonblank(answer.strip(), label);
    }

    static void issueKey(String value) throws IOException {
        if (!value.matches("(?:[A-Za-z][A-Za-z0-9_]*-[0-9]+|[0-9]+)")) {
            throw wrong("Invalid Jira issue key or ID.");
        }
    }

    private static String stdin() throws IOException {
        return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
