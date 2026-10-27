package lk.sahan.parking.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.*;

/** Local secrets stay outside the artifact and below real environment variables. */
public class LocalEnvironment implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 1; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        if (env.getProperty("parking.env.enabled", Boolean.class, true)) {
            Map<String, Object> values = new LinkedHashMap<>(load(Path.of(""), env.getProperty("PARKING_ENV_FILE")));
            env.getPropertySources().addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new MapPropertySource("parkingLocalEnv", values));
        }
        boolean h2 = Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.equals("h2") || p.equals("test"));
        if (Arrays.asList(env.getActiveProfiles()).contains("neon")) {
            if (h2) throw new IllegalStateException("The neon profile cannot be combined with h2/test.");
            Map<String, String> injected = new HashMap<>();
            env.getSystemEnvironment().forEach((key, value) -> injected.put(key, value.toString()));
            Map<String, Object> cloud = new HashMap<>(NeonEnvironment.load(Path.of(""), injected));
            // Enforce the explicit cloud destination even if local datasource settings exist.
            cloud.put("spring.datasource.url", cloud.get("DATABASE_URL"));
            cloud.put("spring.datasource.username", cloud.get("DATABASE_USER"));
            cloud.put("spring.datasource.password", cloud.get("DATABASE_PASSWORD"));
            cloud.put("spring.flyway.url", cloud.get("FLYWAY_DATABASE_URL"));
            cloud.put("spring.flyway.user", cloud.get("FLYWAY_DATABASE_USER"));
            cloud.put("spring.flyway.password", cloud.get("FLYWAY_DATABASE_PASSWORD"));
            env.getPropertySources().addFirst(new MapPropertySource("parkingNeon", cloud));
        }
        validate(env.getProperty("spring.datasource.url", ""), env.getProperty("spring.datasource.username", ""),
                env.getProperty("spring.datasource.password", ""), h2,
                env.getProperty("parking.admin-username", ""), env.getProperty("parking.admin-password", ""));
    }
    public static void validate(String url, String user, String password, boolean allowH2, String admin, String adminPass) {
        if (url.startsWith("jdbc:h2:") && allowH2) { /* Explicit profile, still require an admin. */ }
        else {
            if (!url.startsWith("jdbc:postgresql://"))
                throw new IllegalStateException("Set DATABASE_URL to a PostgreSQL JDBC URL. H2 requires the explicit h2/test profile; no fallback is permitted.");
            if (user.isBlank() || password.isBlank())
                throw new IllegalStateException("Set DATABASE_USER and DATABASE_PASSWORD locally in backend/.env or injected environment variables.");
            lk.sahan.parking.maintenance.PostgresTools.connectionEnvironment(url, user, password);
        }
        if (admin.isBlank() || adminPass.isBlank() || admin.startsWith("${") || adminPass.startsWith("${"))
            throw new IllegalStateException("Set PARKING_ADMIN_USER and PARKING_ADMIN_PASSWORD locally; configured admin credentials are required.");
        if (!admin.matches("[a-z][a-z0-9_.-]{2,31}") || adminPass.getBytes(StandardCharsets.UTF_8).length < 10
                || adminPass.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalStateException("Admin username needs 3-32 lowercase username characters; password needs 10-72 UTF-8 bytes.");
    }
    public static Map<String, String> load(Path workingDirectory, String explicit) {
        Path file = explicit != null && !explicit.isBlank() ? Path.of(explicit)
                : Files.isRegularFile(workingDirectory.resolve("backend/.env"))
                    ? workingDirectory.resolve("backend/.env") : workingDirectory.resolve(".env");
        if (!Files.exists(file)) {
            if (explicit != null && !explicit.isBlank()) throw new IllegalStateException("PARKING_ENV_FILE does not exist.");
            return Map.of();
        }
        try { return parse(Files.readAllLines(file, StandardCharsets.UTF_8)); }
        catch (IOException e) { throw new IllegalStateException("Cannot read the local backend .env file."); }
    }
    public static Map<String, String> parse(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (i == 0 && line.startsWith("\uFEFF")) line = line.substring(1).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring(7).strip();
            int split = line.indexOf('=');
            if (split < 1 || !line.substring(0, split).strip().matches("[A-Z][A-Z0-9_]*")) throw invalid(i);
            String key = line.substring(0, split).strip(), value = line.substring(split + 1).strip();
            if (value.startsWith("\"") || value.startsWith("'")) {
                char quote = value.charAt(0); int end = value.lastIndexOf(quote);
                if (end == 0 || !value.substring(end + 1).strip().matches("(#.*)?")) throw invalid(i);
                value = value.substring(1, end);
            } else value = value.replaceFirst("\\s+#.*$", "").stripTrailing();
            if (values.putIfAbsent(key, value) != null) throw invalid(i);
        }
        return values;
    }
    private static IllegalStateException invalid(int line) {
        return new IllegalStateException("Invalid or duplicate .env setting at line " + (line + 1) + ". Use KEY=value; values are literal, without shell expansion.");
    }
    /** For standalone maintenance tools, which do not start Spring. */
    public static Map<String, String> settings() {
        Map<String, String> settings = new HashMap<>(load(Path.of(""), System.getenv("PARKING_ENV_FILE")));
        settings.putAll(System.getenv()); return settings;
    }
    public static Map<String, String> neonSettings() {
        Map<String, String> settings = new HashMap<>(settings());
        settings.putAll(NeonEnvironment.load(Path.of(""), System.getenv()));
        return settings;
    }
}
