package lk.sahan.parking.config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LocalEnvironmentTest {
 @TempDir Path temp;
 @Test void dotenvPreservesLiteralSecretsAndRejectsInvalidOrDuplicateSettings(){
  var settings=LocalEnvironment.parse(List.of("# local", "export DATABASE_PASSWORD='a$!#\\secret' # comment", "PARKING_ADMIN_PASSWORD=long!value # explanation"));
  assertEquals("a$!#\\secret",settings.get("DATABASE_PASSWORD"));assertEquals("long!value",settings.get("PARKING_ADMIN_PASSWORD"));
  assertThrows(IllegalStateException.class,()->LocalEnvironment.parse(List.of("PASSWORD=first","PASSWORD=second")));
  var error=assertThrows(IllegalStateException.class,()->LocalEnvironment.parse(List.of("PASSWORD='private-value")));
  assertFalse(error.getMessage().contains("private-value"));
 }
 @Test void realEnvironmentOverridesLocalFileAndExplicitDiscoveryWorks()throws Exception{
  Path file=temp.resolve(".env");Files.writeString(file,"DATABASE_PASSWORD=local-secret\nPARKING_ADMIN_USER=admin\nPARKING_ADMIN_PASSWORD=local-admin-secret\n");
  var env=new StandardEnvironment();env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,new SystemEnvironmentPropertySource("systemEnvironment",Map.of("DATABASE_PASSWORD","injected-secret")));
  env.getPropertySources().addFirst(new MapPropertySource("test",Map.of("parking.env.enabled",true,"PARKING_ENV_FILE",file.toString(),"spring.datasource.url","jdbc:postgresql://localhost:5432/parking_pos_test_config","spring.datasource.username","test","spring.datasource.password","${DATABASE_PASSWORD}","parking.admin-username","${PARKING_ADMIN_USER}","parking.admin-password","${PARKING_ADMIN_PASSWORD}")));
  new LocalEnvironment().postProcessEnvironment(env,new SpringApplication());
  assertEquals("injected-secret",env.getProperty("spring.datasource.password"));assertEquals("admin",env.getProperty("parking.admin-username"));
 }
 @Test void postgresAndAdminConfigurationAreRequiredWithoutAHiddenH2Fallback(){
  assertThrows(IllegalStateException.class,()->LocalEnvironment.validate("","","",false,"admin","admin-password"));
  assertThrows(IllegalStateException.class,()->LocalEnvironment.validate("jdbc:h2:mem:accidental","sa","",false,"admin","admin-password"));
  assertDoesNotThrow(()->LocalEnvironment.validate("jdbc:h2:mem:explicit","sa","",true,"admin","admin-password"));
  assertThrows(IllegalStateException.class,()->LocalEnvironment.validate("jdbc:postgresql://localhost/test","test","",false,"admin","admin-password"));
 }
}
