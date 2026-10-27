package lk.sahan.parking.config;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit cloud configuration. Never expose a provider URI in logs or exceptions. */
public final class NeonEnvironment {
    private NeonEnvironment() {}

    public static Map<String, String> load(Path directory, Map<String, String> injected) {
        String explicit = injected.get("PARKING_NEON_ENV_FILE");
        // Container deployments use injected secrets only. Complete environment
        // configuration must not depend on discovering or parsing a dotenv file.
        if ((explicit == null || explicit.isBlank()) && injected.containsKey("DATABASE_URL")
                && injected.containsKey("DATABASE_URL_UNPOOLED")) return adapt(injected);
        Path file = explicit != null && !explicit.isBlank() ? Path.of(explicit)
                : Files.isRegularFile(directory.resolve(".env.local")) ? directory.resolve(".env.local")
                : directory.resolve("../.env.local");
        Map<String, String> values = new HashMap<>();
        if (Files.isRegularFile(file)) values.putAll(LocalEnvironment.load(directory, file.toString()));
        else if (explicit != null && !explicit.isBlank())
            throw new IllegalStateException("PARKING_NEON_ENV_FILE does not exist.");
        // Real environment variables override the cloud file, never backend/.env's local database.
        for (String key : List.of("DATABASE_URL", "DATABASE_URL_UNPOOLED"))
            if (injected.containsKey(key)) values.put(key, injected.get(key));
        return adapt(values);
    }

    public static Map<String, String> adapt(Map<String, String> values) {
        Connection app = parse(values.get("DATABASE_URL"));
        Connection direct = parse(values.get("DATABASE_URL_UNPOOLED"));
        if (direct.host.contains("-pooler.") || !app.host.replace("-pooler.", ".").equals(direct.host)
                || !app.database.equals(direct.database) || !app.user.equals(direct.user))
            throw new IllegalStateException("Neon application and direct migration URLs must use the same endpoint, database and role; migrations require an unpooled host.");
        Map<String, String> result = new LinkedHashMap<>();
        result.put("DATABASE_URL", app.jdbc);
        result.put("DATABASE_USER", app.user);
        result.put("DATABASE_PASSWORD", app.password);
        result.put("FLYWAY_DATABASE_URL", direct.jdbc);
        result.put("FLYWAY_DATABASE_USER", direct.user);
        result.put("FLYWAY_DATABASE_PASSWORD", direct.password);
        return result;
    }

    private static Connection parse(String value) {
        try {
            if (value == null) throw new IllegalArgumentException();
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!Set.of("postgres", "postgresql").contains(uri.getScheme()) || host == null
                    || !host.endsWith(".neon.tech") || uri.getFragment() != null
                    || uri.getRawUserInfo() == null || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException();
            String[] auth = uri.getRawUserInfo().split(":", 2);
            if (auth.length != 2) throw new IllegalArgumentException();
            String user = decode(auth[0]), password = decode(auth[1]);
            String database = uri.getPath();
            if (user.isBlank() || password.isBlank() || database == null || !database.matches("/[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException();
            Map<String, String> query = new LinkedHashMap<>();
            if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&", -1)) {
                String[] setting = part.split("=", 2);
                if (setting.length != 2) throw new IllegalArgumentException();
                String key = decode(setting[0]);
                key = switch (key) {
                    case "channel_binding" -> "channelBinding";
                    case "connect_timeout" -> "connectTimeout";
                    default -> key;
                };
                if (!Set.of("sslmode", "sslrootcert", "channelBinding", "connectTimeout", "socketTimeout", "ApplicationName").contains(key)
                        || query.putIfAbsent(key, decode(setting[1])) != null) throw new IllegalArgumentException();
            }
            String mode = query.getOrDefault("sslmode", "verify-full");
            if (!Set.of("require", "verify-ca", "verify-full").contains(mode)) throw new IllegalArgumentException();
            query.put("sslmode", "verify-full");
            if (!Set.of("require", "prefer").contains(query.getOrDefault("channelBinding", "require")))
                throw new IllegalArgumentException();
            query.putIfAbsent("channelBinding", "require");
            // pgJDBC's default LibPQFactory expects a separate root.crt. Use the JDK's
            // trusted CA store for Neon unless an explicit PEM root was supplied.
            if (!query.containsKey("sslrootcert") || "system".equals(query.get("sslrootcert"))) {
                query.remove("sslrootcert");
                query.put("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
            } else if (query.get("sslrootcert").isBlank()) throw new IllegalArgumentException();
            query.putIfAbsent("connectTimeout", "15");
            StringJoiner options = new StringJoiner("&");
            query.forEach((key, val) -> options.add(encode(key) + "=" + encode(val)));
            String jdbc = "jdbc:postgresql://" + host + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                    + database + "?" + options;
            lk.sahan.parking.maintenance.PostgresTools.connectionEnvironment(jdbc, user, password);
            return new Connection(jdbc, user, password, host, database);
        } catch (RuntimeException ignored) {
            // Do not retain a URI parsing exception as its message can contain credentials.
            throw new IllegalStateException("Invalid Neon connection URI. Set DATABASE_URL and DATABASE_URL_UNPOOLED to matching Neon PostgreSQL URIs with credentials and secure TLS options.");
        }
    }

    private static String decode(String value) {
        // URI userinfo uses literal '+', unlike HTML form encoding. Decode exactly once.
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static final class Connection {
        final String jdbc, user, password, host, database;
        Connection(String jdbc, String user, String password, String host, String database) {
            this.jdbc = jdbc; this.user = user; this.password = password; this.host = host; this.database = database;
        }
    }
}
