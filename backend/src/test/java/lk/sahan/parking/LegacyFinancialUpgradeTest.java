package lk.sahan.parking;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
/** A complete pre-migration database with real historical financial/message rows, never the operational file. */
@SpringBootTest(properties={"test.cashier.username=legacycashier","test.cashier.password=legacy-password","PARKING_ADMIN_USER=legacyadmin","PARKING_ADMIN_PASSWORD=admin-password","parking.upgrade.adopt-legacy=true"})
@AutoConfigureMockMvc
class LegacyFinancialUpgradeTest {
 @DynamicPropertySource static void legacy(DynamicPropertyRegistry properties)throws Exception{
  Path folder=Files.createTempDirectory(Path.of("target"),"legacy-financial-").toAbsolutePath();String url="jdbc:h2:file:"+folder.resolve("before").toString().replace('\\','/');var ds=new DriverManagerDataSource(url,"sa","");
  new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__existing_pos_schema.sql")).execute(ds);
  try(var connection=ds.getConnection();var sql=connection.createStatement()){
   sql.execute("insert into branches(id,code,name) values(99,'001','Main Branch')");
   sql.execute("insert into parking_slots(id,code,branch_id,occupied) values(99,'LEGACY-001',99,false),(100,'LEGACY-002',99,true)");
   sql.execute("insert into parking_sessions(id,plate,vehicle_type,slot_id,entered_at,exited_at,rate_per_minute,entered_by) values(77,'LEGACY-PAID','CAR',99,TIMESTAMP WITH TIME ZONE '2026-10-20 03:00:00Z',TIMESTAMP WITH TIME ZONE '2026-10-20 03:10:00Z',2.50,'legacycashier')");
   sql.execute("insert into parking_sessions(id,plate,active_plate,vehicle_type,slot_id,entered_at,entered_by) values(78,'LEGACY-PARKED','LEGACY-PARKED','CAR',100,CURRENT_TIMESTAMP,'legacycashier')");
   sql.execute("insert into payments(id,session_id,amount,method,reference,paid_at,cashier_username) values(66,77,25.00,'EXTERNAL_CARD','original-terminal',TIMESTAMP WITH TIME ZONE '2026-10-20 03:10:00Z','legacycashier')");
   sql.execute("insert into conversations(id,owner_key,cashier,branch_id,branch,subject,category,priority,resolved,created_at,updated_at,last_post_id) values(7,'bootstrap:legacycashier','legacycashier',99,'001 Main Branch','Historical direct message','GENERAL','NORMAL',false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,8)");
   sql.execute("insert into conversation_posts(id,conversation_id,request_key,fingerprint,sender_key,sender,admin,kind,body,sent_at) values(8,7,'9404c5e2-9f92-44db-a5b0-4ce71de7d859','historical-fingerprint','admin:legacyadmin','legacyadmin',true,'MESSAGE','Historical reply survives upgrade',CURRENT_TIMESTAMP)");
   String backup=folder.resolve("verified-before.sql").toString().replace('\\','/');sql.execute("SCRIPT TO '"+backup+"'");properties.add("parking.upgrade.backup",()->backup);
  }
  properties.add("spring.datasource.url",()->url);
 }
 @Autowired MockMvc mvc;@Autowired JdbcTemplate sql;
 @Test void idsGrossReferencesLegacyRatesAndThreadsSurviveVersionedUpgrade()throws Exception{
  var auth=httpBasic("legacycashier","legacy-password");
  mvc.perform(get("/api/receipts/77").with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.paymentId").value(66)).andExpect(jsonPath("$.amount").value(25)).andExpect(jsonPath("$.reference").value("original-terminal")).andExpect(jsonPath("$.billableMinutes").value(10)).andExpect(jsonPath("$.shiftId").isEmpty()).andExpect(jsonPath("$.cashTendered").isEmpty()).andExpect(jsonPath("$.changeDue").isEmpty());
  mvc.perform(get("/api/sessions/78/quote").with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.breakdown.rate").value(1)).andExpect(jsonPath("$.breakdown.unit").value("MINUTE"));
  mvc.perform(get("/api/conversation-messages?folder=received").with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].id").value(8)).andExpect(jsonPath("$.rows[0].conversationId").value(7));
  mvc.perform(get("/api/conversations/7").with(auth)).andExpect(jsonPath("$.messages.rows[0].body").value("Historical reply survives upgrade"));
  assertEquals(2,sql.queryForObject("select count(*) from parking_sessions",Integer.class));assertEquals(1,sql.queryForObject("select count(*) from payments",Integer.class));assertEquals(0,sql.queryForObject("select count(*) from cashier_shifts",Integer.class));assertEquals(1,sql.queryForObject("select count(*) from conversation_posts",Integer.class));
 }
}
