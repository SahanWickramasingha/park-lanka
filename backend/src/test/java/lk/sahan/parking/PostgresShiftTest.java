package lk.sahan.parking;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Exercise shift ownership, rollback and branch-lock concurrency on a disposable PostgreSQL database. */
@EnabledIfEnvironmentVariable(named="PARKING_TEST_POSTGRES_URL",matches=".+")
class PostgresShiftTest extends ShiftTest {
 private static String database;
 private static synchronized String database(){
  if(database==null)try{database=PostgresTestDatabases.create();}catch(java.sql.SQLException error){throw new IllegalStateException("Could not create isolated shift test database",error);}
  return database;
 }
 @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties){
  properties.add("spring.datasource.url",PostgresShiftTest::database);
  properties.add("spring.datasource.username",PostgresTestDatabases::user);
  properties.add("spring.datasource.password",PostgresTestDatabases::password);
 }
}
