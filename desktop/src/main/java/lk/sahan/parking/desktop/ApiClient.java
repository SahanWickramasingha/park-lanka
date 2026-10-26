package lk.sahan.parking.desktop;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;

public class ApiClient {
    /** Keep status and request identity available without parsing user-facing error strings. */
    public static final class HttpFailure extends IOException {
        private final int statusCode;
        private final String method,path;
        private JsonNode details;
        HttpFailure(int statusCode,String method,String path,String reason) {
            super("HTTP " + statusCode + ": " + reason);
            this.statusCode=statusCode;this.method=method;this.path=path;
        }
        public int statusCode(){return statusCode;}
        public String method(){return method;}
        public String path(){return path;}
        public JsonNode details(){return details;}
    }
    public static final class NetworkFailure extends IOException {
        NetworkFailure(Throwable cause){super("Could not connect to the server",cause);}
    }
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final String base;
    private volatile String token;
    private volatile String username;
    private final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();

    public ApiClient(String base, String username, String password) {
        String endpoint = base.trim().replaceAll("/+$", "");
        URI uri = URI.create(endpoint);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Enter a valid http(s) API base URL");
        if (uri.getScheme().equals("http") && !isLocal(uri.getHost()))
            throw new IllegalArgumentException("Use HTTPS for public servers; HTTP is for localhost or a trusted local network only");
        if (username.isBlank() || username.contains(":")) throw new IllegalArgumentException("Enter a valid username");
        this.base = endpoint;
        this.username = username;
        this.token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    public String username() { return username; }
    public String baseUrl(){return base;}
    public boolean isActive() { return token != null; }
    public synchronized void logOut() {
        token = null; username = "";
        requests.forEach(request -> request.cancel(true)); requests.clear();
    }

    private boolean isLocal(String host) {
        if (Set.of("localhost", "127.0.0.1").contains(host)) return true;
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        try {
            int[] ip = Arrays.stream(parts).mapToInt(Integer::parseInt).toArray();
            for (int value : ip) if (value < 0 || value > 255) return false;
            return ip[0] == 10 || ip[0] == 192 && ip[1] == 168 || ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31;
        } catch (NumberFormatException e) { return false; }
    }

    public JsonNode get(String path) throws IOException, InterruptedException { return call("GET", path, null); }
    public JsonNode post(String path, Object data) throws IOException, InterruptedException { return call("POST", path, data); }
    public JsonNode put(String path, Object data) throws IOException, InterruptedException { return call("PUT", path, data); }
    public JsonNode delete(String path) throws IOException, InterruptedException { return call("DELETE", path, null); }
    public JsonNode setBranchEnabled(long id, boolean enabled) throws IOException, InterruptedException {
        return put("/api/admin/branches/" + id + "/enabled", Map.of("enabled", enabled));
    }
    public JsonNode deleteBranch(long id) throws IOException, InterruptedException { return delete("/api/admin/branches/" + id); }
    public JsonNode setCashierEnabled(long id, boolean enabled) throws IOException, InterruptedException {
        return put("/api/admin/cashiers/" + id + "/enabled", Map.of("enabled", enabled));
    }
    public JsonNode removeCashier(long id) throws IOException, InterruptedException { return delete("/api/admin/cashiers/" + id); }

    private JsonNode call(String method, String path, Object data) throws IOException, InterruptedException {
        return mapper.readTree(request(method, path, data, false));
    }

    public void saveReport(String path, Path destination) throws IOException, InterruptedException {
        byte[] workbook = request("GET", path, null, true);
        if (!isActive() || Thread.currentThread().isInterrupted()) throw new IOException("Report canceled on logout");
        Path target = destination.toAbsolutePath(), temp = Files.createTempFile(target.getParent(), ".parking-report-", ".tmp");
        try {
            Files.write(temp, workbook);
            if (!isActive() || Thread.currentThread().isInterrupted()) throw new IOException("Report canceled on logout");
            try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private byte[] request(String method, String path, Object data, boolean report) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<byte[]>> pending;
        synchronized (this) {
            if (token == null) throw new IOException("Logged out. Sign in again.");
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(report ? 120 : 15))
                    .header("Authorization", "Basic " + token).header("Accept", report ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/json");
            if (data == null && method.equals("GET")) request.GET();
            else if (data == null && method.equals("DELETE")) request.DELETE();
            else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(data)));
            pending = client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray()); requests.add(pending);
        }
        HttpResponse<byte[]> response;
        try { response = pending.get(); }
        catch (ExecutionException | CancellationException ex) {
            if(!isActive())throw new IOException("Logged out",ex);
            throw new NetworkFailure(ex.getCause()==null?ex:ex.getCause());
        }
        catch (InterruptedException ex) { pending.cancel(true); throw ex; }
        finally { requests.remove(pending); }
        if (response.statusCode() / 100 != 2) {
            String reason = new String(response.body(), StandardCharsets.UTF_8);
            try { reason = mapper.readTree(reason).path("error").asText(reason); } catch (Exception ignored) { }
            if (reason.isBlank()) reason = switch (response.statusCode()) {
                case 401 -> "Login rejected or account access revoked. Check your credentials or contact the administrator.";
                case 403 -> "This account is not permitted to perform this action.";
                default -> "Request failed; refresh and retry.";
            };
            HttpFailure failure=new HttpFailure(response.statusCode(),method,path,reason);
            try{failure.details=mapper.readTree(response.body());}catch(Exception ignored){}
            throw failure;
        }
        return response.body();
    }
}
