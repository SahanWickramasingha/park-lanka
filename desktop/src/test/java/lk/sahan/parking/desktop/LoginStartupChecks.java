package lk.sahan.parking.desktop;

import com.sun.net.httpserver.*;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import java.io.IOException;
import java.net.*;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import javax.net.ssl.SSLHandshakeException;

/** Disposable loopback servers only. No live accounts, database or hosted writes. */
public final class LoginStartupChecks {
    public static void main(String[] args) throws Exception {
        CountDownLatch ready = new CountDownLatch(1); Platform.startup(ready::countDown);
        check(ready.await(10, TimeUnit.SECONDS), "JavaFX startup"); Platform.setImplicitExit(false);
        try { run(); } finally { Platform.exit(); }
    }

    static void run() throws Exception {
        check(LoginStartup.STARTUP_LIMIT.equals(Duration.ofSeconds(240)), "Production startup bound changed");
        // Reproduce the old 15s failure, then exceed that same delay successfully with the new flow.
        try (Fixture fixture = new Fixture(); Client client = fixture.client()) {
            fixture.authDelay = 16_000;
            try { client.get("/api/me"); throw new AssertionError("Old request unexpectedly survived 16s delay"); }
            catch (ApiClient.NetworkFailure ex) { check(LoginStartup.networkKind(ex) == LoginStartup.Kind.TIMEOUT, "Old failure was not a timeout"); }
            check(fixture.auth.get() == 1, "Old login submitted twice");
        }
        try (Fixture fixture = new Fixture(); Client client = fixture.client()) {
            fixture.probeDelay = 16_000;
            long start = System.nanoTime();
            check(new LoginStartup(client).connect().path("role").asText().equals("CASHIER"), "Delayed startup failed");
            check(Duration.ofNanos(System.nanoTime() - start).toSeconds() >= 16, "Delayed startup not exercised");
            check(fixture.probes.get() == 1 && fixture.auth.get() == 1, "Delayed startup duplicated authentication");
        }
        try (Fixture fixture = new Fixture(); Client client = fixture.client()) {
            fixture.statuses.addAll(List.of(503, 502, 200, 429));
            fast(client, 2500, 200).connect();
            check(fixture.probes.get() == 5 && fixture.auth.get() == 1, "Startup retries sent credentials or accepted loading HTML");
        }
        try (Fixture fixture = new Fixture(); Client client = fixture.client()) {
            fixture.probeDelay = 1000;
            ScheduledExecutorService warming = Executors.newSingleThreadScheduledExecutor();
            try {
                warming.schedule(() -> fixture.probeDelay = 0, 220, TimeUnit.MILLISECONDS);
                fast(client, 2000, 80).connect();
                check(fixture.probes.get() > 1 && fixture.auth.get() == 1, "Timed-out readiness did not recover safely");
            } finally { warming.shutdownNow(); }
        }
        try (Fixture fixture = new Fixture(); Client client = fixture.client()) {
            fixture.fallback = 503;
            expect(LoginStartup.Kind.SERVER, () -> fast(client, 400, 100).connect());
            check(fixture.auth.get() == 0 && fixture.probes.get() > 1 && fixture.probes.get() <= 6, "503 retry bound/authentication");
        }
        try (Fixture fixture = new Fixture(); Client client = fixture.client()) {
            fixture.probeDelay = 1000;
            expect(LoginStartup.Kind.TIMEOUT, () -> fast(client, 350, 80).connect());
            check(fixture.auth.get() == 0, "Probe timeout submitted authentication");
        }
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { unusedPort = socket.getLocalPort(); }
        try (Client client = new Client("http://127.0.0.1:" + unusedPort)) {
            long start = System.nanoTime();
            expect(LoginStartup.Kind.CONNECTION, () -> fast(client, 350, 80).connect());
            check(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000, "Unavailable server exceeded bound");
        }
        for (int status : List.of(401, 403, 500)) {
            try (Fixture fixture = new Fixture(); Client client = fixture.client()) {
                fixture.authStatus = status;
                LoginStartup.Failure failure = expect(status < 500 ? LoginStartup.Kind.INVALID_CREDENTIALS : LoginStartup.Kind.SERVER,
                        () -> fast(client, 1000, 200).connect());
                check(!failure.getMessage().contains("private-diagnostic"), "Raw server body leaked to UI");
                check(fixture.probes.get() == 1 && fixture.auth.get() == 1, "Authentication was retried");
            }
        }
        try (Fixture fixture = new Fixture(); Client client = fixture.client()) {
            fixture.authDelay = 2000; fixture.authHeadersFirst = true;
            expect(LoginStartup.Kind.TIMEOUT, () -> fast(client, 500, 200).connect());
            check(fixture.auth.get() == 1, "Authentication timeout retried credentials");
        }
        try (Fixture fixture = new Fixture(); Client client = fixture.client()) {
            fixture.fallback = 404;
            expect(LoginStartup.Kind.CONFIGURATION, () -> fast(client, 1000, 200).connect());
            check(fixture.probes.get() == 1 && fixture.auth.get() == 0, "Wrong endpoint retried");
        }
        AtomicInteger tlsAttempts = new AtomicInteger();
        try (Client client = new Client("https://tls.example.invalid") {
            @Override Readiness probeReadiness(Duration timeout) throws IOException {
                tlsAttempts.incrementAndGet(); throw new NetworkFailure(new SSLHandshakeException("untrusted certificate"));
            }
        }) {
            expect(LoginStartup.Kind.TLS, () -> fast(client, 1000, 200).connect());
            check(tlsAttempts.get() == 1, "TLS validation failure was retried");
        }
        check(LoginStartup.networkKind(new ApiClient.NetworkFailure(new HttpTimeoutException("timeout"))) == LoginStartup.Kind.TIMEOUT, "Timeout classification");
        try (Fixture fixture = new Fixture(); Client client = fixture.client()) {
            try { client.post("/fixture/write", Map.of("value", "fixture")); throw new AssertionError("Expected write failure"); }
            catch (ApiClient.HttpFailure ex) { check(ex.statusCode() == 503 && fixture.writes.get() == 1, "Write retried"); }
        }
        checkLoginUi();
        System.out.println("LOGIN STARTUP CHECK PASSED: reproduced old 15s timeout, 16s delayed startup, bounded backoff/deadline, unavailable server, loading HTML, invalid credentials, authentication timeout/server errors, TLS classification, Cancel/Retry, responsive UI, duplicate/stale submission protection and no write retries.");
    }

    private static void checkLoginUi() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.probeDelay = 1200;
            DesktopApp app = new DesktopApp(fixture::endpoint, client -> fast(client, 2000, 1500));
            Stage stage = fx(() -> { Stage window = new Stage(); app.start(window); window.setX(-10000); window.setY(-10000); return window; });
            try {
                fx(() -> {
                    input(stage, "Your username").setText("startup.fixture"); input(stage, "Your password").setText("fixture-password");
                    Button connect = button(stage, "Connect to Workspace");
                    connect.getOnAction().handle(new ActionEvent()); connect.getOnAction().handle(new ActionEvent());
                    check(text(stage, "Connecting to server… The first connection may take a few minutes."), "Startup message missing");
                    check(connect.isDisabled() && button(stage, "Cancel").isVisible() && button(stage, "Connection settings").isDisabled(), "Busy controls");
                    return null;
                });
                await(() -> fixture.probes.get() == 1);
                // This FX roundtrip completes while the server is still sleeping.
                fx(() -> { check(fixture.auth.get() == 0, "Credentials sent before readiness"); button(stage, "Cancel").fire(); return null; });
                fx(() -> { check(button(stage, "Retry").isVisible() && !input(stage, "Your username").isDisabled(), "Cancel did not release form"); return null; });
                fixture.probeDelay = 0; fixture.authStatus = 401;
                fx(() -> { button(stage, "Retry").fire(); return null; });
                await(() -> fxUnchecked(() -> text(stage, "Sign-in rejected.")));
                check(fixture.auth.get() == 1, "Invalid credentials retried");
                fx(() -> { check(!input(stage, "Your password").isDisabled() && button(stage, "Retry").isVisible(), "Invalid credentials locked form"); return null; });
                fixture.authStatus = 200;
                fx(() -> { Button retry = button(stage, "Retry"); retry.getOnAction().handle(new ActionEvent()); retry.getOnAction().handle(new ActionEvent()); return null; });
                await(() -> fxUnchecked(() -> stage.getTitle().equals("Park Lanka | Cashier")));
                check(fixture.auth.get() == 2, "Successful retry duplicated authentication");
                Thread.sleep(1500);
                fx(() -> { check(stage.getTitle().equals("Park Lanka | Cashier"), "Stale cancelled attempt changed screen"); return null; });
            } finally { fx(() -> { app.stop(); stage.close(); return null; }); }
        }
        // Cancel on application close must prevent a late successful login callback.
        try (Fixture fixture = new Fixture()) {
            fixture.probeDelay = 500;
            DesktopApp app = new DesktopApp(fixture::endpoint);
            Stage stage = fx(() -> { Stage window = new Stage(); app.start(window); window.setX(-10000); window.setY(-10000); return window; });
            fx(() -> { input(stage, "Your username").setText("startup.fixture"); input(stage, "Your password").setText("fixture-password"); button(stage, "Connect to Workspace").fire(); return null; });
            await(() -> fixture.probes.get() == 1);
            fx(() -> { app.stop(); stage.close(); return null; }); Thread.sleep(650);
            check(fixture.auth.get() == 0, "Closed app submitted authentication");
        }
    }

    private static LoginStartup fast(ApiClient client, long limitMs, long requestMs) {
        return new LoginStartup(client, Duration.ofMillis(limitMs), Duration.ofMillis(requestMs), Duration.ofMillis(40), Duration.ofMillis(120));
    }
    private interface Work { void run() throws Exception; }
    private static LoginStartup.Failure expect(LoginStartup.Kind kind, Work work) throws Exception {
        long start = System.nanoTime();
        try { work.run(); throw new AssertionError("Expected " + kind); }
        catch (LoginStartup.Failure ex) {
            check(ex.kind == kind, "Expected " + kind + ", got " + ex.kind);
            check(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3500, "Failure exceeded test deadline");
            return ex;
        }
    }
    private static <T> T fx(Supplier<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action::get); Platform.runLater(task); return task.get(5, TimeUnit.SECONDS);
    }
    private static boolean fxUnchecked(Supplier<Boolean> action) {
        try { return fx(action); } catch (Exception ex) { throw new CompletionException(ex); }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) { if (condition.getAsBoolean()) return; Thread.sleep(20); }
        throw new AssertionError("Startup check timed out");
    }
    private static List<Node> nodes(Node node) {
        List<Node> found = new ArrayList<>(); found.add(node);
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(child -> found.addAll(nodes(child)));
        if (node instanceof ScrollPane scroll && scroll.getContent() != null && !found.contains(scroll.getContent())) found.addAll(nodes(scroll.getContent()));
        return found;
    }
    private static Button button(Stage stage, String label) { return nodes(stage.getScene().getRoot()).stream().filter(n -> n instanceof Button b && b.getText().equals(label)).map(Button.class::cast).findFirst().orElseThrow(); }
    private static TextField input(Stage stage, String prompt) { return nodes(stage.getScene().getRoot()).stream().filter(n -> n instanceof TextField t && prompt.equals(t.getPromptText())).map(TextField.class::cast).findFirst().orElseThrow(); }
    private static boolean text(Stage stage, String value) { return nodes(stage.getScene().getRoot()).stream().anyMatch(n -> n instanceof Label label && label.getText().contains(value)); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static class Client extends ApiClient implements AutoCloseable {
        Client(String endpoint) { super(endpoint, "startup.fixture", "fixture-password"); }
        @Override public void close() { logOut(); }
    }
    private static final class Fixture implements AutoCloseable {
        final AtomicInteger probes = new AtomicInteger(), auth = new AtomicInteger(), writes = new AtomicInteger();
        final Queue<Integer> statuses = new ConcurrentLinkedQueue<>();
        volatile int fallback = 401, authStatus = 200;
        volatile long probeDelay, authDelay;
        volatile boolean authHeadersFirst;
        final HttpServer server; final ExecutorService http = Executors.newCachedThreadPool();
        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(http);
            server.createContext("/", exchange -> {
                try {
                    if (exchange.getRequestURI().getPath().equals("/fixture/write")) { writes.incrementAndGet(); send(exchange, 503, "{}"); return; }
                    if (!exchange.getRequestURI().getPath().equals("/api/me")) { send(exchange, 200, "[]"); return; }
                    check(exchange.getRequestMethod().equals("GET"), "Readiness/login must be GET");
                    if (exchange.getRequestHeaders().getFirst("Authorization") == null) {
                        probes.incrementAndGet(); long delay = probeDelay; if (delay > 0) Thread.sleep(delay);
                        Integer next = statuses.poll(); int status = next == null ? fallback : next;
                        if (status == 401) exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"Realm\"");
                        send(exchange, status, status == 200 ? "<html>Starting</html>" : "");
                    } else {
                        auth.incrementAndGet();
                        if (authHeadersFirst) {
                            byte[] body = "{\"role\":\"CASHIER\"}".getBytes(StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(200, body.length); Thread.sleep(authDelay);
                            exchange.getResponseBody().write(body); return;
                        }
                        if (authDelay > 0) Thread.sleep(authDelay);
                        send(exchange, authStatus, authStatus == 200 ? "{\"role\":\"CASHIER\",\"branchId\":1,\"branchName\":\"Fixture\"}" : "{\"error\":\"private-diagnostic\"}");
                    }
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                catch (IOException ignored) { /* Expected when a timed-out/cancelled request closes. */ }
                finally { exchange.close(); }
            }); server.start();
        }
        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        Client client() { return new Client(endpoint()); }
        private void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        }
        @Override public void close() { server.stop(0); http.shutdownNow(); }
    }
}
