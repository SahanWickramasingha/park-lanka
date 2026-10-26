package lk.sahan.parking;

import lk.sahan.parking.repo.*;
import lk.sahan.parking.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.core.env.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="PARKING_TEST_POSTGRES_URL",matches=".+")
@SpringBootTest(properties={"PARKING_INITIAL_SLOTS=3","spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureMockMvc
class PostgresFlowTest {
 @DynamicPropertySource static void postgres(DynamicPropertyRegistry props){
  props.add("spring.datasource.url",PostgresTestDatabases::configuredUrl);
  props.add("spring.datasource.username",PostgresTestDatabases::user);
  props.add("spring.datasource.password",PostgresTestDatabases::password);
 }
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired BranchService branches;@Autowired RateService rates;
 @Autowired CashierRepository accounts;@Autowired JdbcTemplate jdbc;
 @Test void freshPostgresLifecycleConcurrencyMessagesLegacyLinkAndRestartPersistence()throws Exception{
  assertEquals(0,accounts.count());assertEquals(1,jdbc.queryForObject("select count(*) from branches",Integer.class));
  assertEquals(0,fetch(get("/api/admin/branches"),"testadmin","adminpassword").get(0).path("cashiers").asInt());
  mvc.perform(get("/api/me").with(httpBasic("cashier","unused-password"))).andExpect(status().isUnauthorized());
  long main=branches.main().getId();
  JsonNode branch=write(post("/api/admin/branches"),"testadmin","adminpassword",Map.of("name","PostgreSQL Branch","slots",2),201);
  long otherBranch=branch.path("id").asLong();
  write(post("/api/admin/cashiers"),"testadmin","adminpassword",Map.of("username","testadmin","password","cashier-password","branchId",main),400);
  long userId=write(post("/api/admin/cashiers"),"testadmin","adminpassword",Map.of("username","pg.cashier","password","cashier-password","branchId",main),201).path("id").asLong();
  write(post("/api/admin/cashiers"),"testadmin","adminpassword",Map.of("username","other.cashier","password","other-password","branchId",otherBranch),201);
  assertTrue(accounts.findByUsername("pg.cashier").orElseThrow().getPasswordHash().startsWith("$2"));
  mvc.perform(get("/api/admin/branches").with(httpBasic("pg.cashier","cashier-password"))).andExpect(status().isForbidden());
  long slot=fetch(get("/api/slots"),"pg.cashier","cashier-password").get(0).path("id").asLong();
  long shift=write(post("/api/shifts"),"pg.cashier","cashier-password",Map.of("requestKey",UUID.randomUUID().toString(),"openingCash","100.00"),200).path("id").asLong();
  var entry=Map.of("plate","PG-1234","vehicleType","CAR","slotId",slot);
  long ticket=write(post("/api/sessions"),"pg.cashier","cashier-password",entry,201).path("id").asLong();
  write(post("/api/sessions"),"pg.cashier","cashier-password",entry,409);
  mvc.perform(get("/api/sessions/{id}/quote",ticket).with(httpBasic("other.cashier","other-password"))).andExpect(status().isNotFound());
  JsonNode quote=fetch(get("/api/sessions/{id}/quote",ticket),"pg.cashier","cashier-password");
  var payment=new HashMap<String,Object>(Map.of("requestKey",UUID.randomUUID().toString(),"method","CASH","confirmedAmount",quote.path("amount").decimalValue(),"cashTendered","100.00","quoteId",quote.path("quoteId").asText()));
  ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);
  JsonNode receipt;
  try{
   Callable<JsonNode> checkout=()->{gate.await();return write(post("/api/sessions/"+ticket+"/checkout"),"pg.cashier","cashier-password",payment,200);};
   Future<JsonNode> a=pool.submit(checkout),b=pool.submit(checkout);gate.countDown();receipt=a.get(20,TimeUnit.SECONDS);assertEquals(receipt.path("paymentId"),b.get(20,TimeUnit.SECONDS).path("paymentId"));
  }finally{pool.shutdownNow();}
  assertEquals(1,jdbc.queryForObject("select count(*) from payments",Integer.class));
  fetch(get("/api/receipts/{id}",ticket),"pg.cashier","cashier-password");
  long cardTicket=write(post("/api/sessions"),"pg.cashier","cashier-password",Map.of("plate","PG-CARD","vehicleType","CAR","slotId",slot),201).path("id").asLong();
  JsonNode cardQuote=fetch(get("/api/sessions/{id}/quote",cardTicket),"pg.cashier","cashier-password");
  write(post("/api/sessions/{id}/checkout",cardTicket),"pg.cashier","cashier-password",Map.of("requestKey",UUID.randomUUID().toString(),"method","EXTERNAL_CARD","confirmedAmount",cardQuote.path("amount").decimalValue(),"externalConfirmed",true,"reference","isolated-terminal-test","quoteId",cardQuote.path("quoteId").asText()),200);
  long thread=write(post("/api/conversations"),"pg.cashier","cashier-password",Map.of("requestKey",UUID.randomUUID().toString(),"subject","PostgreSQL saved message","category","GENERAL","priority","IMPORTANT","body","Persistent question"),200).path("id").asLong();
  write(post("/api/conversations/{id}/replies",thread),"testadmin","adminpassword",Map.of("requestKey",UUID.randomUUID().toString(),"body","Persistent answer"),200);
  mvc.perform(get("/api/conversations/{id}",thread).with(httpBasic("other.cashier","other-password"))).andExpect(status().isNotFound());
  assertTrue(fetch(get("/api/conversation-messages?folder=received"),"pg.cashier","cashier-password").path("total").asInt()>0);
  write(put("/api/notifications/read-all"),"pg.cashier","cashier-password",Map.of(),200);
  assertEquals(0,fetch(get("/api/notifications"),"pg.cashier","cashier-password").path("unread").asInt());
  long broadcast=write(post("/api/admin/broadcasts"),"testadmin","adminpassword",Map.of("requestKey",UUID.randomUUID().toString(),"title","PostgreSQL notice","body","Preserved announcement","priority","NORMAL","allBranches",true,"branchIds",List.of()),200).path("id").asLong();
  write(put("/api/messages/{id}/read",broadcast),"pg.cashier","cashier-password",Map.of(),200);
  assertEquals(1,jdbc.queryForObject("select count(*) from broadcast_recipients where broadcast_id=? and read_at is not null",Integer.class,broadcast));
  var correction=Map.of("requestKey",UUID.randomUUID().toString(),"type","REFUND","amount","0.25","reason","Isolated PostgreSQL correction","confirmed",true,"movementShiftId",shift);
  write(post("/api/admin/payments/{id}/corrections",receipt.path("paymentId").asLong()),"testadmin","adminpassword",correction,200);
  assertTrue(fetch(get("/api/admin/audit"),"testadmin","adminpassword").path("total").asInt()>0);
  mvc.perform(get("/api/admin/reports/excel?table=payments").with(httpBasic("testadmin","adminpassword"))).andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
  write(put("/api/admin/cashiers/{id}/enabled",userId),"testadmin","adminpassword",Map.of("enabled",false),200);
  mvc.perform(get("/api/me").with(httpBasic("pg.cashier","cashier-password"))).andExpect(status().isUnauthorized());
  write(put("/api/admin/cashiers/{id}/enabled",userId),"testadmin","adminpassword",Map.of("enabled",true),200);
  write(put("/api/admin/cashiers/{id}/password",userId),"testadmin","adminpassword",Map.of("password","changed-password"),200);
  mvc.perform(get("/api/me").with(httpBasic("pg.cashier","cashier-password"))).andExpect(status().isUnauthorized());
  fetch(get("/api/me"),"pg.cashier","changed-password");
  // Creating the old username never grants access to someone else's legacy messages.
  long legacyId=write(post("/api/admin/cashiers"),"testadmin","adminpassword",Map.of("username","legacy.cashier","password","legacy-password","branchId",main),201).path("id").asLong();
  jdbc.update("insert into conversations(owner_key,cashier,branch_id,branch,subject,category,priority,resolved,created_at,updated_at) values('bootstrap:legacy.cashier','legacy.cashier',?,'001 Main Branch','Legacy private history','GENERAL','NORMAL',false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",main);
  assertEquals(0,fetch(get("/api/conversations"),"legacy.cashier","legacy-password").path("total").asInt());
  write(put("/api/admin/cashiers/{id}/legacy-identity",legacyId),"testadmin","adminpassword",Map.of("legacyUsername","someone.else","confirmation","LINK LEGACY someone.else"),409);
  write(put("/api/admin/cashiers/{id}/legacy-identity",legacyId),"testadmin","adminpassword",Map.of("legacyUsername","legacy.cashier","confirmation","LINK LEGACY legacy.cashier"),200);
  assertEquals(1,fetch(get("/api/conversations"),"legacy.cashier","legacy-password").path("total").asInt());
  long branchCount=jdbc.queryForObject("select count(*) from branches",Long.class),slotCount=jdbc.queryForObject("select count(*) from parking_slots",Long.class);
  StandardEnvironment env=new StandardEnvironment();env.setActiveProfiles("test");
  env.getPropertySources().addFirst(new MapPropertySource("isolatedRestart",Map.of("parking.env.enabled",false,"spring.datasource.url",PostgresTestDatabases.configuredUrl(),"spring.datasource.username",PostgresTestDatabases.user(),"spring.datasource.password",PostgresTestDatabases.password(),"server.port",0)));
  try(var restarted=new SpringApplicationBuilder(ParkingApplication.class).environment(env).run()){
   var sql=restarted.getBean(JdbcTemplate.class);
   assertEquals(branchCount,sql.queryForObject("select count(*) from branches",Long.class));assertEquals(slotCount,sql.queryForObject("select count(*) from parking_slots",Long.class));
   assertEquals(2,sql.queryForObject("select count(*) from payments",Integer.class));
   assertTrue(sql.queryForObject("select count(*) from notice_reads",Integer.class)>0);
   assertEquals("bootstrap:legacy.cashier",restarted.getBean(CashierRepository.class).findByUsername("legacy.cashier").orElseThrow().ownerKey());
  }
  write(delete("/api/admin/cashiers/{id}",userId),"testadmin","adminpassword",Map.of(),200);
  mvc.perform(get("/api/me").with(httpBasic("pg.cashier","changed-password"))).andExpect(status().isUnauthorized());
 }
 private JsonNode fetch(MockHttpServletRequestBuilder request,String user,String password)throws Exception{return json.readTree(mvc.perform(request.with(httpBasic(user,password))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
 private JsonNode write(MockHttpServletRequestBuilder request,String user,String password,Object body,int status)throws Exception{return json.readTree(mvc.perform(request.with(httpBasic(user,password)).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());}
}
