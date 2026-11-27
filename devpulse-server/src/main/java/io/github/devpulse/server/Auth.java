package io.github.devpulse.server;

/**
 * The two request checks a loopback-only server still needs.
 *
 * <p>There is no token: the product is a single-user local tool, and the network peer is always the
 * machine itself. Two risks survive that simplification, and both are cheap to close:
 *
 * <ul>
 *   <li>A browser page on another origin can rebind a hostname to {@code 127.0.0.1} and reach this
 *       port. Validating {@code Host} blocks that.
 *   <li>A cross-site form can send a simple POST without a CORS preflight. Requiring a custom
 *       header forces the preflight, which the browser then refuses.
 * </ul>
 */
public final class Auth {
    /** The header every client must send; its value is not secret, its presence is the point. */
    public static final String CLIENT_HEADER = "X-Devpulse-Client";

    private Auth() {}

    /** Whether the Host header names this loopback server. */
    public static boolean hostAllowed(String host, int port) {
        if (host == null) {
            return false;
        }
        var expected = Integer.toString(port);
        return host.equals("127.0.0.1:" + expected)
                || host.equals("localhost:" + expected)
                || host.equals("[::1]:" + expected);
    }

    /**
     * Whether the request announced itself as a devpulse client.
     *
     * <p>Values are ignored on purpose: this is a CSRF guard, not authentication. Any value works,
     * but a cross-origin form cannot set it without a preflight.
     */
    public static boolean clientHeader(String value) {
        return value != null && !value.isBlank();
    }
}
