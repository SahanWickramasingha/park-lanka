package lk.sahan.parking.maintenance;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.io.IOException;

/** JDBC and libpq use the same destination and certificate verification settings. */
public final class PostgresTools {
    public static Map<String,String> connectionEnvironment(String url, String user, String password) {
        try {
            if (!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
            URI uri = URI.create(url.substring(5));
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPath() == null || !uri.getPath().matches("/[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            Map<String,String> result = new HashMap<>();
            result.put("PGHOST", uri.getHost()); result.put("PGPORT", Integer.toString(uri.getPort() < 0 ? 5432 : uri.getPort()));
            result.put("PGDATABASE", uri.getPath().substring(1)); result.put("PGUSER", user); result.put("PGPASSWORD", password);
            result.put("PGCONNECT_TIMEOUT", "15");
            Map<String,String> supported = Map.of("sslmode", "PGSSLMODE", "sslrootcert", "PGSSLROOTCERT", "sslcert", "PGSSLCERT",
                    "sslkey", "PGSSLKEY", "channelBinding", "PGCHANNELBINDING", "ApplicationName", "PGAPPNAME", "connectTimeout", "PGCONNECT_TIMEOUT");
            if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
                String[] setting = part.split("=", 2);
                if (setting.length != 2) throw new IllegalArgumentException();
                String key = URLDecoder.decode(setting[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(setting[1], StandardCharsets.UTF_8);
                if (key.equals("ssl") && value.equals("true")) result.put("PGSSLMODE", "verify-full");
                else if (key.equals("sslfactory") && value.equals("org.postgresql.ssl.DefaultJavaSSLFactory")) {
                    // libpq 17+ equivalent of Java's trusted CA store; never disable verification.
                    result.putIfAbsent("PGSSLROOTCERT", "system");
                }
                else if (key.equals("socketTimeout")) { /* JDBC-only read timeout. */ }
                else if (supported.containsKey(key)) result.put(supported.get(key), value);
                else throw new IllegalArgumentException();
            }
            return result;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid PostgreSQL URL or unsupported connection option. Keep credentials in separate settings; use a single host/database and supported TLS options.");
        }
    }
    public static void run(String executable, boolean dump, String url, String user, String password, Path file) throws Exception {
        Map<String,String> connection = connectionEnvironment(url, user, password);
        List<String> args = new ArrayList<>(List.of(executable, "--no-password"));
        if (dump) args.addAll(List.of("--format=custom", "--no-owner", "--no-acl", "--file", file.toString()));
        else args.addAll(List.of("--single-transaction", "--exit-on-error", "--no-owner", "--no-acl", "--dbname", connection.get("PGDATABASE"), file.toString()));
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.environment().keySet().removeIf(k -> k.startsWith("PG"));
        builder.environment().putAll(connection);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD); builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process child = builder.start();
        if (!child.waitFor(600, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new IOException("PostgreSQL backup/restore tool timed out"); }
        if (child.exitValue() != 0) throw new IOException("PostgreSQL tool failed; verify tool/server versions, access, TLS certificates and privileges");
    }
}
