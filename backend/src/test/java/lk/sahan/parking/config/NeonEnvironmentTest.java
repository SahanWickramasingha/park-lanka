package lk.sahan.parking.config;

import lk.sahan.parking.maintenance.PostgresTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NeonEnvironmentTest {
    @TempDir Path temp;
    private Map<String, String> urls(String auth, String query) {
        return Map.of("DATABASE_URL", "postgresql://" + auth + "@ep-test-pooler.example.neon.tech/neondb" + query,
                "DATABASE_URL_UNPOOLED", "postgres://" + auth + "@ep-test.example.neon.tech/neondb" + query);
    }

    @Test void separatesCredentialsDecodesExactlyOnceAndRequiresVerifiedTls() {
        var result = NeonEnvironment.adapt(urls("app%2Buser:p%40ss+%2B%3A%2F%25%2521", "?sslmode=require&channel_binding=require"));
        assertEquals("app+user", result.get("DATABASE_USER"));
        assertEquals("p@ss++:/%%21", result.get("DATABASE_PASSWORD"));
        assertTrue(result.get("DATABASE_URL").contains("-pooler."));
        assertFalse(result.get("FLYWAY_DATABASE_URL").contains("-pooler."));
        for (String key : List.of("DATABASE_URL", "FLYWAY_DATABASE_URL")) {
            String jdbc = result.get(key);
            assertTrue(jdbc.startsWith("jdbc:postgresql://"));
            assertFalse(jdbc.contains("@"));
            assertFalse(jdbc.contains("p%40ss"));
            var pg = PostgresTools.connectionEnvironment(jdbc, "app", "secret");
            assertEquals("verify-full", pg.get("PGSSLMODE"));
            assertEquals("system", pg.get("PGSSLROOTCERT"));
            assertEquals("require", pg.get("PGCHANNELBINDING"));
        }
    }

    @Test void supportsExplicitRootAndEncodedDatabaseName() {
        var input = new HashMap<>(urls("app:secret", "?sslmode=verify-full&sslrootcert=C%3A%2Fcerts%2Froot.crt"));
        input.replaceAll((key, value) -> value.replace("/neondb?", "/parking%5Fpos?"));
        var result = NeonEnvironment.adapt(input);
        var pg = PostgresTools.connectionEnvironment(result.get("FLYWAY_DATABASE_URL"), "app", "secret");
        assertEquals("C:/certs/root.crt", pg.get("PGSSLROOTCERT"));
        assertEquals("parking_pos", pg.get("PGDATABASE"));
    }

    @Test void rejectsUnsafeOptionsAndMalformedUrisWithoutLeakingCredentials() {
        for (String query : List.of("?sslmode=disable", "?sslmode=prefer", "?channel_binding=disable",
                "?sslmode=require&sslmode=disable", "?sslfactory=private-secret", "?password=private-secret",
                "?channel_binding=require&channelBinding=disable", "?sslrootcert=")) {
            var error = assertThrows(IllegalStateException.class,
                    () -> NeonEnvironment.adapt(urls("app:private-secret", query)));
            assertFalse(error.getMessage().contains("private-secret"));
            assertNull(error.getCause());
        }
        for (String bad : List.of("postgresql://app:private-secret@localhost/neondb",
                "postgresql://app:private-secret@ep-test.example.neon.tech/neondb#fragment",
                "postgresql://app:private-secret%zz@ep-test.example.neon.tech/neondb")) {
            var input = new HashMap<>(urls("app:secret", "")); input.put("DATABASE_URL", bad);
            var error = assertThrows(IllegalStateException.class, () -> NeonEnvironment.adapt(input));
            assertFalse(error.getMessage().contains("private-secret"));
            assertNull(error.getCause());
        }
    }

    @Test void refusesMissingDirectUrlPooledMigrationsAndDifferentDatabases() {
        var input = new HashMap<>(urls("app:secret", ""));
        input.remove("DATABASE_URL_UNPOOLED");
        assertThrows(IllegalStateException.class, () -> NeonEnvironment.adapt(input));
        input.put("DATABASE_URL_UNPOOLED", input.get("DATABASE_URL"));
        assertThrows(IllegalStateException.class, () -> NeonEnvironment.adapt(input));
        input.put("DATABASE_URL_UNPOOLED", "postgresql://app:secret@ep-test.example.neon.tech/other");
        assertThrows(IllegalStateException.class, () -> NeonEnvironment.adapt(input));
    }

    @Test void cloudFileIsExplicitAndInjectedUrisOverrideItWithoutUsingLocalDatabase() throws Exception {
        var input = urls("app:secret", "");
        Path file = temp.resolve(".env.local");
        Files.writeString(file, "DATABASE_URL='" + input.get("DATABASE_URL") + "'\nDATABASE_URL_UNPOOLED='"
                + input.get("DATABASE_URL_UNPOOLED") + "'\nNEON_BRANCH=production\n");
        Files.writeString(temp.resolve(".env"), "DATABASE_URL=jdbc:postgresql://localhost/local\n");
        assertTrue(NeonEnvironment.load(temp, Map.of()).get("DATABASE_URL").contains("ep-test-pooler"));
        var overrides = new HashMap<>(urls("app:injected%2Bsecret", ""));
        assertEquals("injected+secret", NeonEnvironment.load(temp, overrides).get("DATABASE_PASSWORD"));
        Files.delete(file);
        assertThrows(IllegalStateException.class, () -> NeonEnvironment.load(temp, Map.of()));
        assertDoesNotThrow(() -> NeonEnvironment.load(temp, overrides));
        assertThrows(IllegalStateException.class, () -> NeonEnvironment.load(temp,
                Map.of("PARKING_NEON_ENV_FILE", temp.resolve("missing").toString())));
    }

    @Test void explicitProfileWiresSeparateSpringDatasourcesAndLocalProfileStaysLocal() {
        var input = new HashMap<String, Object>(urls("app:secret", "?sslmode=require"));
        var env = new StandardEnvironment() {
            @Override public Map<String, Object> getSystemEnvironment() { return input; }
        };
        env.getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                "parking.env.enabled", false,
                "spring.datasource.url", "jdbc:postgresql://localhost/local",
                "spring.datasource.username", "local_app", "spring.datasource.password", "local_secret",
                "parking.admin-username", "testadmin", "parking.admin-password", "test-admin-secret")));
        new LocalEnvironment().postProcessEnvironment(env, new SpringApplication());
        assertEquals("jdbc:postgresql://localhost/local", env.getProperty("spring.datasource.url"));
        assertNull(env.getProperty("spring.flyway.url"));
        env.setActiveProfiles("neon");
        new LocalEnvironment().postProcessEnvironment(env, new SpringApplication());
        assertTrue(env.getProperty("spring.datasource.url").contains("ep-test-pooler"));
        assertTrue(env.getProperty("spring.flyway.url").contains("ep-test.example"));
        assertFalse(env.getProperty("spring.flyway.url").contains("pooler"));
        assertEquals("app", env.getProperty("spring.datasource.username"));
        assertEquals("secret", env.getProperty("spring.flyway.password"));
        env.setActiveProfiles("neon", "test");
        assertThrows(IllegalStateException.class,
                () -> new LocalEnvironment().postProcessEnvironment(env, new SpringApplication()));
    }
}
