package io.github.devpulse.common.http;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

public class HttpClients {

    public static HttpClient safeClient(final Supplier<HttpClient.Builder> supplier) {
        return supplier.get().build();
    }

    public static HttpClient safeClient() {
        return HttpClients.safeClient(() -> HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)));
    }

    public static HttpClient unsafeClient() {
        return HttpClients.unsafeClientFrom(() -> HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)))
            ;
    }

    public static HttpClient unsafeClientFrom(final Supplier<HttpClient.Builder> supplier) {
        final var disableHostnameVerification = System.getProperty(HOSTNAME_VERIFICATION_KEY);
        System.setProperty(HOSTNAME_VERIFICATION_KEY, "true");
        try {
            final var builder = supplier.get();
            builder.sslContext(TrustAllSslContext.getInstance());
            return builder.build();
        } finally {
            if (Objects.isNull(disableHostnameVerification)) {
                System.clearProperty(HOSTNAME_VERIFICATION_KEY);
            } else {
                System.setProperty(HOSTNAME_VERIFICATION_KEY, disableHostnameVerification);
            }
        }

    }

    private static final String HOSTNAME_VERIFICATION_KEY = "jdk.internal.httpclient.disableHostnameVerification";

    private HttpClients() {
    }
}
