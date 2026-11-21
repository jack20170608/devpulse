package io.github.devpulse.storage;

/** Key of the reuse index: a node name together with its execution fingerprint. */
public record TaskResultKey(String name, String fingerprint) {}
