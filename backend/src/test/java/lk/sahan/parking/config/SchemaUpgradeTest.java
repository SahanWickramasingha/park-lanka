package lk.sahan.parking.config;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.flywaydb.core.Flyway;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
class SchemaUpgradeTest {
 @Test void adoptsOnlyVerifiedSchemaAndPreservesIdsOnDisposableFile() throws Exception {
  Path dir=Files.createTempDirectory("pos-upgrade-");Path file=dir.resolve("legacy");
  var ds=new DriverManagerDataSource("jdbc:h2:file:"+file.toString().replace('\\','/'),"sa","");
  new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__existing_pos_schema.sql")).execute(ds);
  try(var c=ds.getConnection();var s=c.createStatement()){s.execute("insert into branches(id,code,name) values(99,'099','Historical branch')");SchemaUpgrade.validateLegacy(c);}
  Path backup=Files.copy(dir.resolve("legacy.mv.db"),dir.resolve("before.mv.db"));
  var flyway=Flyway.configure().dataSource(ds).load();
  assertThrows(IllegalStateException.class,()->new SchemaUpgrade().safeMigrations(false,backup.toString()).migrate(flyway));
  new SchemaUpgrade().safeMigrations(true,backup.toString()).migrate(flyway);
  try(var c=ds.getConnection();var s=c.createStatement();var rows=s.executeQuery("select name from branches where id=99")){assertTrue(rows.next());assertEquals("Historical branch",rows.getString(1));}
 }
 @Test void refusesUnknownOrIncompatibleLegacySchema() throws Exception {
  var ds=new DriverManagerDataSource("jdbc:h2:mem:bad-schema;DB_CLOSE_DELAY=-1","sa","");
  new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__existing_pos_schema.sql")).execute(ds);
  try(var c=ds.getConnection();var s=c.createStatement()){s.execute("alter table payments drop constraint if exists fake_constraint");s.execute("alter table payments add column unexpected varchar(10)");assertThrows(IllegalStateException.class,()->SchemaUpgrade.validateLegacy(c));}
 }
}
