package lk.sahan.parking.config;
import lk.sahan.parking.maintenance.PostgresTools;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PostgresToolsTest {
 @Test void backupUsesTheSameCloudDestinationAndVerifiedTlsOptions(){
  var env=PostgresTools.connectionEnvironment("jdbc:postgresql://db.example.test:6432/parking?sslmode=verify-full&sslrootcert=C%3A%2Fcerts%2Froot.crt","app","test-secret");
  assertEquals("db.example.test",env.get("PGHOST"));assertEquals("6432",env.get("PGPORT"));assertEquals("parking",env.get("PGDATABASE"));
  assertEquals("verify-full",env.get("PGSSLMODE"));assertEquals("C:/certs/root.crt",env.get("PGSSLROOTCERT"));assertEquals("test-secret",env.get("PGPASSWORD"));
 }
 @Test void embeddedCredentialsAndUnsupportedOptionsFailWithoutEchoingSecrets(){
  for(String url:new String[]{"jdbc:postgresql://user:private-value@localhost/parking","jdbc:postgresql://localhost/parking?password=private-value","jdbc:postgresql://localhost/parking?unsupported=private-value"}){
   var error=assertThrows(IllegalArgumentException.class,()->PostgresTools.connectionEnvironment(url,"app","secret"));assertFalse(error.getMessage().contains("private-value"));
  }
 }
}
