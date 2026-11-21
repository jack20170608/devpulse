package io.github.devpulse.cli;

/** Selects how a command reports its own result. */
public enum OutputFormat {
    /** Human-readable text; the historical behaviour. */
    text,
    /** Exactly one JSON document on stdout, for a program to read. */
    json
}
