package lk.sahan.parking.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Configuration only: no Spring context, network connection, or production secrets. */
class RenderEnvironmentTest {
    @TempDir Path temp;

    private Map<String, String> injected() {
        Map<String, String> values = new HashMap<>();
        values.put("DATABASE_URL", "postgresql://fixture:encoded%2Bpassword@ep-render-pooler.example.neon.tech/neondb?sslmode=require");
        values.put("DATABASE_URL_UNPOOLED", "postgresql://fixture:encoded%2Bpassword@ep-render.example.neon.tech/neondb?sslmode=require");
        values.put("PARKING_ADMIN_USER", "renderadmin");
        values.put("PARKING_ADMIN_PASSWORD", "render-test-password");
        values.put("PARKING_ENV_ENABLED", "false");
        values.put("PORT", "10000");
        values.put("SERVER_ADDRESS", "0.0.0.0");
        return values;
    }

    private StandardEnvironment environment(Map<String, String> values) throws Exception {
        Map<String, Object> system = new HashMap<>(values);
        var env = new StandardEnvironment() {
            @Override public Map<String, Object> getSystemEnvironment() { return system; }
        };
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, system));
        env.getPropertySources().addLast(new PropertiesPropertySource("neonProfile",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-neon.properties"))));
        env.getPropertySources().addLast(new PropertiesPropertySource("application",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"))));
        env.setActiveProfiles("neon");
        return env;
    }

    @Test void completeInjectedUrisNeedNoDotenvAndIgnoreAccidentalLocalFiles() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("container/app"));
        assertTrue(NeonEnvironment.load(directory, injected()).get("DATABASE_URL").startsWith("jdbc:postgresql://"));
        Files.writeString(directory.resolve(".env.local"), "not a valid dotenv setting");
        assertDoesNotThrow(() -> NeonEnvironment.load(directory, injected()));
        var explicit = injected();
        explicit.put("PARKING_NEON_ENV_FILE", directory.resolve("missing.env").toString());
        assertThrows(IllegalStateException.class, () -> NeonEnvironment.load(directory, explicit));
        var incomplete = injected();
        incomplete.remove("DATABASE_URL_UNPOOLED");
        assertThrows(IllegalStateException.class, () -> NeonEnvironment.load(temp.resolve("empty/app"), incomplete));
    }

    @Test void convertedJdbcSettingsOverrideRawSpringEnvironmentUrlsAndKeepMigrationSafeguards() throws Exception {
        var values = injected();
        values.put("SPRING_DATASOURCE_URL", values.get("DATABASE_URL"));
        values.put("SPRING_DATASOURCE_USERNAME", "incorrect-override");
        values.put("SPRING_DATASOURCE_PASSWORD", "incorrect-override");
        values.put("SPRING_FLYWAY_URL", values.get("DATABASE_URL"));
        values.put("SPRING_FLYWAY_USER", "incorrect-override");
        values.put("SPRING_FLYWAY_PASSWORD", "incorrect-override");
        var env = environment(values);
        assertTrue(env.getProperty("spring.datasource.url").startsWith("postgresql://"));
        new LocalEnvironment().postProcessEnvironment(env, new SpringApplication());
        String app = env.getProperty("spring.datasource.url"), direct = env.getProperty("spring.flyway.url");
        assertTrue(app.startsWith("jdbc:postgresql://ep-render-pooler."));
        assertTrue(direct.startsWith("jdbc:postgresql://ep-render."));
        assertTrue(app.contains("sslmode=verify-full"));
        assertTrue(direct.contains("sslmode=verify-full"));
        assertFalse(app.contains("@"));
        assertFalse(direct.contains("@"));
        assertEquals("fixture", env.getProperty("spring.datasource.username"));
        assertEquals("fixture", env.getProperty("spring.flyway.user"));
        assertEquals("encoded+password", env.getProperty("spring.datasource.password"));
        assertEquals("encoded+password", env.getProperty("spring.flyway.password"));
        assertEquals(app, env.getProperty("DATABASE_URL"));
        assertEquals("renderadmin", env.getProperty("parking.admin-username"));
        assertEquals("render-test-password", env.getProperty("parking.admin-password"));
        assertEquals(10000, env.getProperty("server.port", Integer.class));
        assertEquals("0.0.0.0", env.getProperty("server.address"));
        assertEquals("validate", env.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals(false, env.getProperty("spring.flyway.baseline-on-migrate", Boolean.class));
        assertEquals(true, env.getProperty("spring.flyway.clean-disabled", Boolean.class));
        assertEquals(false, env.getProperty("parking.upgrade.adopt-legacy", Boolean.class));
    }

    @Test void injectedAdminConfigurationIsStillRequired() throws Exception {
        var values = injected();
        values.remove("PARKING_ADMIN_PASSWORD");
        var env = environment(values);
        var error = assertThrows(IllegalStateException.class,
                () -> new LocalEnvironment().postProcessEnvironment(env, new SpringApplication()));
        assertFalse(error.getMessage().contains("encoded+password"));
        assertTrue(error.getMessage().contains("PARKING_ADMIN_PASSWORD"));
    }
}
