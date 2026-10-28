package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CancellationException;
import javax.net.ssl.SSLException;

/** Runs on the desktop IO executor. Only the credential-free GET probe is retried. */
final class LoginStartup {
    static final Duration STARTUP_LIMIT = Duration.ofSeconds(240);
    enum Kind { CONNECTION, TIMEOUT, TLS, SERVER, CONFIGURATION, INVALID_CREDENTIALS }
    static final class Failure extends IOException {
        final Kind kind;
        Failure(Kind kind, String message) { super(message); this.kind = kind; }
    }
    private final ApiClient client;
    private final Duration limit, probeTimeout, initialBackoff, maxBackoff;
    private volatile boolean cancelled;
    private Thread worker;
    private long started;

    LoginStartup(ApiClient client) {
        this(client, STARTUP_LIMIT, Duration.ofSeconds(20), Duration.ofSeconds(1), Duration.ofSeconds(8));
    }

    // Shorter timings are injectable by local regression checks, never by user configuration.
    LoginStartup(ApiClient client, Duration limit, Duration probeTimeout, Duration initialBackoff, Duration maxBackoff) {
        if (limit.isNegative() || limit.isZero() || limit.compareTo(STARTUP_LIMIT) > 0
                || probeTimeout.isNegative() || probeTimeout.isZero() || initialBackoff.isNegative()
                || initialBackoff.isZero() || maxBackoff.compareTo(initialBackoff) < 0)
            throw new IllegalArgumentException("Invalid startup timing");
        this.client = client; this.limit = limit; this.probeTimeout = probeTimeout;
        this.initialBackoff = initialBackoff; this.maxBackoff = maxBackoff;
    }

    JsonNode connect() throws IOException, InterruptedException {
        synchronized (this) { started = System.nanoTime(); worker = Thread.currentThread(); }
        long backoff = initialBackoff.toNanos();
        Kind last = Kind.TIMEOUT;
        diagnostic("start", 0, "none", "none");
        try {
            for (int attempt = 1; ; attempt++) {
                checkCancelled();
                if (remaining() <= 0) throw exhausted(last);
                try {
                    ApiClient.Readiness response = client.probeReadiness(within(probeTimeout));
                    diagnostic("readiness", attempt, Integer.toString(response.statusCode()), "none");
                    checkCancelled();
                    if (response.ready()) break;
                    int status = response.statusCode();
                    // Render may serve a 200 loading page during startup. It is not readiness.
                    if (status / 100 == 2 || status == 408 || status == 429 || status / 100 == 5) last = Kind.SERVER;
                    else throw new Failure(Kind.CONFIGURATION,
                            "The server did not return the expected API sign-in challenge (HTTP " + status + "). Check Connection settings.");
                } catch (ApiClient.NetworkFailure ex) {
                    checkCancelled(); last = networkKind(ex);
                    diagnostic("readiness", attempt, "none", last.name());
                    if (last == Kind.TLS) throw tlsFailure();
                }
                checkCancelled();
                if (remaining() <= 0) throw exhausted(last);
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(backoff, remaining()));
                backoff = Math.min(maxBackoff.toNanos(), backoff * 2);
            }
            checkCancelled();
            if (remaining() <= 0) throw exhausted(Kind.TIMEOUT);
            diagnostic("authentication", 1, "none", "none");
            try {
                JsonNode result = client.authenticate(within(Duration.ofSeconds(15)));
                checkCancelled();
                if (result == null || !Set.of("ADMIN", "CASHIER").contains(result.path("role").asText()))
                    throw new Failure(Kind.SERVER, "The server returned an unexpected sign-in response. Check Connection settings or contact the administrator.");
                diagnostic("authenticated", 1, "200", "none");
                return result;
            } catch (ApiClient.HttpFailure ex) {
                diagnostic("authentication", 1, Integer.toString(ex.statusCode()), "HTTP");
                if (ex.statusCode() == 401) throw new Failure(Kind.INVALID_CREDENTIALS,
                        "Sign-in rejected. Check your username and password, or contact the administrator if your account is disabled.");
                if (ex.statusCode() == 403) throw new Failure(Kind.INVALID_CREDENTIALS,
                        "This account is not permitted to sign in. Contact the administrator.");
                throw new Failure(Kind.SERVER, "The server could not complete sign-in (HTTP " + ex.statusCode() + "). Please retry.");
            } catch (ApiClient.NetworkFailure ex) {
                checkCancelled(); Kind kind = networkKind(ex);
                diagnostic("authentication", 1, "none", kind.name());
                if (kind == Kind.TLS) throw tlsFailure();
                throw new Failure(kind, kind == Kind.TIMEOUT
                        ? "The sign-in request timed out. Please retry. Authentication was not retried automatically."
                        : "Connection lost during sign-in. Check your connection and retry.");
            } catch (Failure ex) { throw ex; }
            catch (IOException ex) {
                throw new Failure(Kind.SERVER, "The server returned an unreadable sign-in response. Check Connection settings and retry.");
            }
        } catch (Failure ex) {
            diagnostic("failed", 0, "none", ex.kind.name()); throw ex;
        } finally { synchronized (this) { worker = null; } }
    }

    synchronized void cancel() {
        cancelled = true; client.logOut();
        // Serialize with worker cleanup so a reused IO thread is never interrupted.
        if (worker != null) { diagnostic("cancelled", 0, "none", "CANCELLED"); worker.interrupt(); }
    }

    private void checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException("Connection cancelled");
    }
    private long remaining() { return limit.toNanos() - (System.nanoTime() - started); }
    private Duration within(Duration requested) { return Duration.ofNanos(Math.max(1, Math.min(requested.toNanos(), remaining()))); }
    private Failure exhausted(Kind last) {
        String detail = switch (last) {
            case CONNECTION -> "Could not reach the server. Check your internet connection and Connection settings.";
            case SERVER -> "The server is still unavailable or starting. Please try again shortly.";
            default -> "The server connection timed out. Please try again shortly.";
        };
        return new Failure(last, "Startup wait ended after " + limit.toSeconds() + " seconds. " + detail);
    }
    static Kind networkKind(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLException) return Kind.TLS;
            if (cause instanceof HttpTimeoutException) return Kind.TIMEOUT;
        }
        return Kind.CONNECTION;
    }
    private static Failure tlsFailure() {
        return new Failure(Kind.TLS, "A secure HTTPS connection could not be verified. Check the server certificate and your PC's date/time. Certificate validation remains enabled.");
    }
    private void diagnostic(String phase, int attempt, String status, String failure) {
        // The validated origin has no user info/query/path. Never log headers, bodies or credentials.
        System.out.println("LOGIN endpoint=" + client.baseUrl() + " phase=" + phase + " attempt=" + attempt
                + " elapsedMs=" + Duration.ofNanos(System.nanoTime() - started).toMillis()
                + " status=" + status + " failure=" + failure);
    }
}
