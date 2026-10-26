package lk.sahan.parking;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Run the cash, rejection and concurrent-retry contract against an isolated PostgreSQL cluster too. */
@EnabledIfEnvironmentVariable(named="PARKING_TEST_POSTGRES_URL",matches=".+")
class PostgresCheckoutSafetyTest extends CheckoutSafetyTest {
 private static String database;
 private static synchronized String database(){
  if(database==null)try{database=PostgresTestDatabases.create();}catch(java.sql.SQLException error){throw new IllegalStateException("Could not create isolated checkout test database",error);}
  return database;
 }
 @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties){
  properties.add("spring.datasource.url",PostgresCheckoutSafetyTest::database);
  properties.add("spring.datasource.username",PostgresTestDatabases::user);
  properties.add("spring.datasource.password",PostgresTestDatabases::password);
 }
 @org.junit.jupiter.api.Test void completedSessionAndReceiptSurviveBackendRestart()throws Exception{
  long id=enter();var request=request();pay(id,request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  long branch=sql.queryForObject("select branch_id from parking_slots where id=(select slot_id from parking_sessions where id=?)",Long.class,id);
  long slot=sql.queryForObject("select slot_id from parking_sessions where id=?",Long.class,id);
  var env=new org.springframework.core.env.StandardEnvironment();env.setActiveProfiles("test");
  env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("checkoutRestart",java.util.Map.of("parking.env.enabled",false,"spring.datasource.url",database(),"spring.datasource.username",PostgresTestDatabases.user(),"spring.datasource.password",PostgresTestDatabases.password(),"server.port",0)));
  try(var restarted=new org.springframework.boot.builder.SpringApplicationBuilder(ParkingApplication.class).environment(env).run()){
   var service=restarted.getBean(lk.sahan.parking.service.ParkingService.class);
   org.junit.jupiter.api.Assertions.assertTrue(service.activeSessions(branch).stream().noneMatch(row->row.id()==id));
   org.junit.jupiter.api.Assertions.assertFalse(service.allSlots(branch).stream().filter(row->row.id()==slot).findFirst().orElseThrow().occupied());
   org.junit.jupiter.api.Assertions.assertNotNull(service.receiptBySession(id,branch).exitedAt());
   org.junit.jupiter.api.Assertions.assertTrue(service.entryHistory(branch,String.valueOf(id),null,null,null,0,25).rows().stream().anyMatch(row->row.id()==id&&row.status().equals("Completed")));
   org.junit.jupiter.api.Assertions.assertTrue(service.receipts(branch,String.valueOf(id),null,null,0,25).rows().stream().anyMatch(row->row.sessionId()==id&&row.cashier().equals("checkout")));
   org.junit.jupiter.api.Assertions.assertEquals(id,service.checkoutStatus((String)request.get("requestKey"),branch,"checkout").sessionId());
  }
 }
}
