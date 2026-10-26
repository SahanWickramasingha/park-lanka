package lk.sahan.parking;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.*;
@EnabledIfEnvironmentVariable(named="PARKING_TEST_POSTGRES_URL",matches=".+")
class PostgresCheckoutQuoteTest extends CheckoutQuoteTest {
 private static String database;
 private static synchronized String database(){if(database==null)try{database=PostgresTestDatabases.create();}catch(java.sql.SQLException e){throw new IllegalStateException(e);}return database;}
 @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("spring.datasource.url",PostgresCheckoutQuoteTest::database);p.add("spring.datasource.username",PostgresTestDatabases::user);p.add("spring.datasource.password",PostgresTestDatabases::password);}
}
