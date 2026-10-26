package lk.sahan.parking.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/** Desktop connection settings only; backend secrets are never loaded by the client. */
final class DesktopConfiguration {
    private static final String SERVER_URL = "PARKING_SERVER_URL";
    private static final String DEFAULT_URL = "http://localhost:8080";

    private DesktopConfiguration() {}

    static String serverUrl() { return serverUrl(Path.of(""), System.getenv()); }

    static Path userSettingsFile(Map<String, String> environment) {
        return Path.of(environment.getOrDefault("APPDATA", System.getProperty("user.home")), "Park Lanka", "desktop.env");
    }

    static void saveServerUrl(String value) throws IOException { saveServerUrl(value, System.getenv()); }

    static void saveServerUrl(String value, Map<String, String> environment) throws IOException {
        if (value == null || value.isBlank() || !java.net.URI.create(value.trim()).isAbsolute())
            throw new IllegalArgumentException("Enter a valid http(s) API base URL");
        // Reuse the client's origin/HTTPS validation without making a network request.
        ApiClient validation = new ApiClient(value, "connection-settings", "unused");
        String normalized = validation.baseUrl();
        validation.logOut();
        Path file = userSettingsFile(environment);
        Files.createDirectories(file.getParent());
        java.util.List<String> lines = Files.exists(file)
                ? new java.util.ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new java.util.ArrayList<>();
        lines.removeIf(line -> line.replaceFirst("^\\uFEFF", "").strip().matches("(?:export\\s+)?PARKING_SERVER_URL\\s*=.*"));
        lines.add(SERVER_URL + "=" + normalized);
        Path temporary = Files.createTempFile(file.getParent(), "desktop-", ".tmp");
        try {
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    static String serverUrl(Path workingDirectory, Map<String, String> environment) {
        String override = environment.get(SERVER_URL);
        if (override != null) return requireUrl(override);

        String explicit = environment.get("PARKING_DESKTOP_ENV_FILE");
        Path file = explicit != null && !explicit.isBlank() ? Path.of(explicit)
                : Files.isRegularFile(workingDirectory.resolve("desktop/.env"))
                    ? workingDirectory.resolve("desktop/.env")
                : Files.isRegularFile(workingDirectory.resolve(".env")) ? workingDirectory.resolve(".env")
                : userSettingsFile(environment);
        if (!Files.exists(file)) {
            if (explicit != null && !explicit.isBlank())
                throw new IllegalStateException("PARKING_DESKTOP_ENV_FILE does not exist.");
            return requireUrl(System.getProperty("parking.server.defaultUrl", DEFAULT_URL));
        }

        try {
            String value = null;
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.startsWith("\uFEFF")) line = line.substring(1).strip();
                if (line.startsWith("export ")) line = line.substring(7).strip();
                int split = line.indexOf('=');
                if (split < 0 || !line.substring(0, split).strip().equals(SERVER_URL)) continue;
                if (value != null) throw new IllegalStateException("Duplicate PARKING_SERVER_URL in desktop configuration.");
                String setting = line.substring(split + 1).strip();
                if (setting.startsWith("\"") || setting.startsWith("'")) {
                    char quote = setting.charAt(0);
                    int end = setting.lastIndexOf(quote);
                    if (end == 0 || !setting.substring(end + 1).strip().matches("(#.*)?"))
                        throw new IllegalStateException("Check the quotes around PARKING_SERVER_URL in desktop configuration.");
                    setting = setting.substring(1, end);
                } else setting = setting.replaceFirst("\\s+#.*$", "").stripTrailing();
                value = requireUrl(setting);
            }
            return value == null ? requireUrl(System.getProperty("parking.server.defaultUrl", DEFAULT_URL)) : value;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read desktop configuration. Check desktop/.env or PARKING_DESKTOP_ENV_FILE.");
        }
    }

    private static String requireUrl(String value) {
        if (value.isBlank()) throw new IllegalStateException("Set PARKING_SERVER_URL in desktop/.env.");
        return value.trim();
    }
}
