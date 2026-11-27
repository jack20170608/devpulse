package io.github.devpulse.server;

/**
 * The two checks every request must pass before it reaches a handler.
 *
 * <p>Both are pure functions so they can be tested without a socket.
 */
public final class Auth {
    private Auth() {}

    /** Whether the Authorization header carries exactly this bearer token. */
    public static boolean bearer(String authorization, String expected) {
        if (authorization == null || expected == null) {
            return false;
        }
        var prefix = "Bearer ";
        if (!authorization.startsWith(prefix)) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                authorization
                        .substring(prefix.length())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Whether the Host header names this loopback server.
     *
     * <p>Rejecting other hosts blocks a browser page on another origin from rebinding a name to
     * 127.0.0.1 and reaching the API.
     */
    public static boolean hostAllowed(String host, int port) {
        if (host == null) {
            return false;
        }
        var expected = Integer.toString(port);
        return host.equals("127.0.0.1:" + expected)
                || host.equals("localhost:" + expected)
                || host.equals("[::1]:" + expected);
    }
}
