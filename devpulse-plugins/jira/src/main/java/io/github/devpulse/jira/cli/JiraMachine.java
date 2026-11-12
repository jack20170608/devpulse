package io.github.devpulse.jira.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.devpulse.jira.JiraRequestException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import picocli.CommandLine;

final class JiraMachine {
    private JiraMachine() {}

    static CommandLine configure(CommandLine cli, JiraCommand root) {
        cli.setExecutionStrategy(parsed -> {
            if (!root.machine) {
                return new CommandLine.RunLast().execute(parsed);
            }
            var selected = parsed;
            boolean help = parsed.isUsageHelpRequested()
                    || parsed.isVersionHelpRequested();
            while (selected.hasSubcommand()) {
                selected = selected.subcommand();
                help |= selected.isUsageHelpRequested()
                        || selected.isVersionHelpRequested();
            }
            var command = selected.commandSpec().commandLine();
            enable(command, "--no-input");
            enable(command, "--json");
            enable(command, "--print-url");
            var out = cli.getOut();
            var err = cli.getErr();
            var output = new StringWriter();
            var diagnostics = new StringWriter();
            cli.setOut(new PrintWriter(output, true));
            cli.setErr(new PrintWriter(diagnostics, true));
            cli.setColorScheme(CommandLine.Help.defaultColorScheme(
                    CommandLine.Help.Ansi.OFF));
            try {
                int status = new CommandLine.RunLast().execute(parsed);
                if (status != 0) {
                    return failure(out, new IllegalStateException());
                }
                var response = envelope(true);
                var action = command.getCommand() instanceof JiraCommand.Action value
                        ? value : null;
                var data = action == null ? null : action.machineData;
                var raw = command.getCommandSpec().findOption("--json");
                if (data == null && raw != null && !help) {
                    try {
                        data = JsonParser.parseString(output.toString());
                    } catch (RuntimeException exception) {
                        return failure(out, new IllegalStateException());
                    }
                }
                response.add("data", data == null ? JsonNull.INSTANCE : data);
                var messages = new JsonArray();
                if (data == null && !output.toString().isBlank()) {
                    messages.add(output.toString().stripTrailing());
                }
                response.add("messages", messages);
                // Human diagnostics can contain user input; never echo them here.
                out.println(response);
                return 0;
            } catch (CommandLine.ExecutionException exception) {
                return failure(out, exception.getCause() instanceof Exception cause
                        ? cause : exception);
            } finally {
                cli.setOut(out);
                cli.setErr(err);
            }
        });
        cli.setExecutionExceptionHandler((exception, command, parsed) -> {
            if (root.machine) {
                return failure(command.getOut(), exception);
            }
            command.getErr().println("jira: unexpected command failure.");
            return 1;
        });
        cli.setParameterExceptionHandler((exception, arguments) -> {
            if (root.machine || requested(arguments)) {
                return error(cli.getOut(), "INVALID_ARGUMENT", 2,
                        "Invalid command arguments; consult command --help.",
                        null, false, false);
            }
            cli.getErr().println(exception.getMessage());
            exception.getCommandLine().usage(cli.getErr());
            return 2;
        });
        return cli;
    }

    private static void enable(CommandLine command, String name) {
        var option = command.getCommandSpec().findOption(name);
        if (option != null) {
            option.setValue(true);
        }
    }

    private static boolean requested(String[] arguments) {
        for (var argument : arguments) {
            if (argument.equals("--")) {
                break;
            }
            if (argument.equals("--machine") || argument.equals("--machine=true")) {
                return true;
            }
        }
        return false;
    }

    static int failure(PrintWriter out, Exception exception) {
        if (exception instanceof JiraRequestException request) {
            int status = request.outcomeUnknown() ? 5
                    : request.httpStatus() == null ? 4 : 3;
            return error(out, request.code(), status, request.getMessage(),
                    request.httpStatus(), request.outcomeUnknown(), request.retryable());
        }
        if (exception instanceof IOException
                || exception instanceof com.typesafe.config.ConfigException) {
            return error(out, "COMMAND_FAILED", 1,
                    "Command failed locally; check configuration and inputs.",
                    null, false, false);
        }
        return error(out, "INTERNAL_ERROR", 1,
                "Unexpected command failure; do not retry writes automatically.",
                null, true, false);
    }

    private static int error(PrintWriter out, String code, int exitCode,
            String message, Integer httpStatus, boolean unknown, boolean retryable) {
        var response = envelope(false);
        var error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        error.addProperty("outcome", unknown ? "unknown" : "failed");
        error.addProperty("retryable", retryable);
        if (httpStatus != null) {
            error.addProperty("httpStatus", httpStatus);
        }
        response.add("error", error);
        out.println(response);
        out.flush();
        return exitCode;
    }

    private static JsonObject envelope(boolean ok) {
        var response = new JsonObject();
        response.addProperty("schemaVersion", 1);
        response.addProperty("ok", ok);
        return response;
    }
}
