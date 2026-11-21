package io.github.devpulse.cli;

import com.typesafe.config.ConfigException;
import java.io.PrintWriter;

/**
 * Shared failure reporting for the CLI commands: one classified line on stderr in text mode, or one
 * error document on stdout in JSON mode. Keeping both in one place is what makes the exit-code
 * contract uniform across commands.
 */
final class CommandOutput {
    private CommandOutput() {}

    /**
     * Report a classified failure and return the process exit code.
     *
     * @param prefix command name used to prefix text errors, such as {@code pulse}
     * @param cause the exception that produced the message, or {@code null}; configuration
     *     exceptions add {@code file} and {@code line} to the JSON document
     */
    static Integer fail(
            boolean json,
            PrintWriter out,
            PrintWriter err,
            String prefix,
            int exitCode,
            String code,
            String message,
            Exception cause) {
        if (json) {
            out.println(PulseJson.error(exitCode, code, message, filename(cause), line(cause)));
            out.flush();
        } else {
            err.println(prefix + ": " + message);
        }
        return exitCode;
    }

    /** Report an interruption, restoring the thread's interrupt flag. */
    static Integer interrupted(
            boolean json, PrintWriter out, PrintWriter err, String prefix, String message) {
        Thread.currentThread().interrupt();
        return fail(json, out, err, prefix, ExitCodes.INTERRUPTED, "INTERRUPTED", message, null);
    }

    private static String filename(Exception cause) {
        return cause instanceof ConfigException bad ? bad.origin().filename() : null;
    }

    private static Integer line(Exception cause) {
        return cause instanceof ConfigException bad ? bad.origin().lineNumber() : null;
    }
}
