package lk.sahan.parking;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
@EnabledIfEnvironmentVariable(named="PARKING_TEST_POSTGRES_URL",matches=".+")
class PostgresParkingHistoryTest extends ParkingHistoryTest {
 private static String database;
 private static synchronized String database(){if(database==null)try{database=PostgresTestDatabases.create();}catch(java.sql.SQLException e){throw new IllegalStateException(e);}return database;}
 @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties){properties.add("spring.datasource.url",PostgresParkingHistoryTest::database);properties.add("spring.datasource.username",PostgresTestDatabases::user);properties.add("spring.datasource.password",PostgresTestDatabases::password);}
}
