package io.github.devpulse.cli;

import picocli.CommandLine.IVersionProvider;

/**
 * Prints the product version for every command's {@code -V}.
 *
 * <p>The version comes from the packaging manifest, so an unpackaged build reports {@code
 * development} rather than an empty string.
 */
public final class VersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() {
        var version = VersionProvider.class.getPackage().getImplementationVersion();
        return new String[] {"devpulse " + (version == null ? "development" : version)};
    }
}
