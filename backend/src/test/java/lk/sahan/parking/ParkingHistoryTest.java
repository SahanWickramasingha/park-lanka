package lk.sahan.parking;

import com.fasterxml.jackson.databind.*;
import lk.sahan.parking.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"DATABASE_URL=jdbc:h2:mem:parkinghistory;DB_CLOSE_DELAY=-1","PARKING_INITIAL_SLOTS=20"}) @AutoConfigureMockMvc
class ParkingHistoryTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate sql;@Autowired CashierService cashiers;@Autowired BranchService branches;
 static final String PASSWORD="history-password";
 String user(Long branch){String name="hist-"+UUID.randomUUID().toString().substring(0,8);cashiers.create(name,PASSWORD,branch);return name;}
 ResultActions write(String path,String user,Object body)throws Exception{return mvc.perform(post(path).with(httpBasic(user,PASSWORD)).contentType("application/json").content(json.writeValueAsString(FinancialTestSupport.quoted(mvc,json,user,PASSWORD,post(path),body))));}
 JsonNode read(String path,String user)throws Exception{return json.readTree(mvc.perform(get(path).with(httpBasic(user,PASSWORD))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
 JsonNode enter(String user,String plate,Long slot)throws Exception{
  if(slot==null){for(JsonNode row:read("/api/slots",user))if(!row.path("occupied").asBoolean()){slot=row.path("id").asLong();break;}}
  return json.readTree(write("/api/sessions",user,Map.of("plate",plate,"vehicleType","CAR","slotId",slot)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
 }
 @Test void completedReceiptsAndEntryHistorySurviveSlotReuseWithoutRepricing()throws Exception{
  String user=user(branches.main().getId());FinancialTestSupport.openShift(mvc,user,PASSWORD);JsonNode entry=enter(user,"HS-"+UUID.randomUUID().toString().substring(0,6),null);long id=entry.path("id").asLong();
  long slot=sql.queryForObject("select slot_id from parking_sessions where id=?",Long.class,id);
  sql.update("update parking_sessions set policy_rate=350,policy_unit='HOUR',policy_grace_minutes=0 where id=?",id);
  assertEquals("CAR",read("/api/sessions/"+id+"/quote",user).path("vehicleType").asText());
  var request=Map.of("requestKey",UUID.randomUUID().toString(),"method","CASH","confirmedAmount","350.00","cashTendered","500.00");
  JsonNode saved=json.readTree(write("/api/sessions/"+id+"/checkout",user,request).andExpect(status().isOk()).andExpect(jsonPath("$.changeDue").value(150)).andExpect(jsonPath("$.cashier").value(user)).andExpect(jsonPath("$.breakdown.rate").value(350)).andReturn().getResponse().getContentAsString());
  JsonNode checkout=read("/api/parking-history/checkouts?cashier="+user,user).path("rows").get(0);
  assertEquals(saved,checkout);assertEquals("Paid",checkout.path("status").asText());
  String plate=entry.path("plate").asText();enter(user,plate,slot);
  JsonNode entries=read("/api/parking-history/entries?cashier="+user+"&search="+plate,user);assertEquals(2,entries.path("total").asInt());
  assertEquals("Parked",entries.path("rows").get(0).path("status").asText());assertEquals("Completed",entries.path("rows").get(1).path("status").asText());
  assertEquals(entry.path("slot"),entries.path("rows").get(1).path("slot"));
  mvc.perform(put("/api/rates").with(httpBasic("testadmin","adminpassword")).contentType("application/json").content("{\"perMinute\":777.00}")).andExpect(status().isOk());
  JsonNode reprinted=json.readTree(write("/api/receipts/"+id+"/reprints",user,Map.of()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertEquals(saved,reprinted);
  assertEquals(1,sql.queryForObject("select count(*) from payments where session_id=?",Integer.class,id));
 }
 @Test void historyUsesSriLankanDateBoundariesExactCashierAndStableServerPagination()throws Exception{
  String user=user(branches.main().getId());LocalDate day=LocalDate.now(ZoneId.of("Asia/Colombo"));
  long first=enter(user,"HB-ONE",null).path("id").asLong(),second=enter(user,"HB-TWO",null).path("id").asLong(),old=enter(user,"HB-OLD",null).path("id").asLong();
  Instant start=day.atStartOfDay(ZoneId.of("Asia/Colombo")).toInstant();
  sql.update("update parking_sessions set entered_at=? where id in (?,?)",java.time.OffsetDateTime.ofInstant(start,ZoneOffset.UTC),first,second);
  sql.update("update parking_sessions set entered_at=? where id=?",java.time.OffsetDateTime.ofInstant(start.minusSeconds(1),ZoneOffset.UTC),old);
  JsonNode page=read("/api/parking-history/entries?cashier="+user+"&size=1",user);assertEquals(2,page.path("total").asInt());assertEquals(second,page.path("rows").get(0).path("id").asLong());
  assertEquals(first,read("/api/parking-history/entries?cashier="+user+"&size=1&page=1",user).path("rows").get(0).path("id").asLong());
  assertEquals(old,read("/api/parking-history/entries?cashier="+user+"&from="+day.minusDays(1)+"&to="+day.minusDays(1),user).path("rows").get(0).path("id").asLong());
  assertEquals(0,read("/api/parking-history/entries?cashier="+user+"&search=%25",user).path("total").asInt());
  mvc.perform(get("/api/parking-history/entries?from="+day+"&to="+day.minusDays(1)).with(httpBasic(user,PASSWORD))).andExpect(status().isBadRequest());
  mvc.perform(get("/api/parking-history/checkouts?size=101").with(httpBasic(user,PASSWORD))).andExpect(status().isBadRequest());
  FinancialTestSupport.openShift(mvc,user,PASSWORD);
  sql.update("update parking_sessions set policy_grace_minutes=1440 where id in (?,?)",first,second);
  for(long id:List.of(first,second)){
   JsonNode quote=read("/api/sessions/"+id+"/quote",user);
   write("/api/sessions/"+id+"/checkout",user,Map.of("requestKey",UUID.randomUUID().toString(),"method","CASH","confirmedAmount",quote.path("amount").decimalValue(),"cashTendered",quote.path("amount").decimalValue())).andExpect(status().isOk());
   sql.update("update payments set paid_at=? where session_id=?",java.time.OffsetDateTime.ofInstant(id==first?start:start.minusSeconds(1),ZoneOffset.UTC),id);
  }
  JsonNode payments=read("/api/parking-history/checkouts?cashier="+user,user);assertEquals(1,payments.path("total").asInt());assertEquals(first,payments.path("rows").get(0).path("sessionId").asLong());
  assertEquals(second,read("/api/parking-history/checkouts?cashier="+user+"&from="+day.minusDays(1)+"&to="+day.minusDays(1),user).path("rows").get(0).path("sessionId").asLong());
 }
 @Test void foreignHistoryAndReprintsCannotBeSelectedBySupplyingAnotherBranch()throws Exception{
  String own=user(branches.main().getId());var branch=branches.create("History private "+UUID.randomUUID().toString().substring(0,6),2);String foreign=user(branch.getId());
  long id=enter(foreign,"HP-PRIVATE",null).path("id").asLong();
  assertEquals(0,read("/api/parking-history/entries?branchId="+branch.getId()+"&search=HP-PRIVATE",own).path("total").asInt());
  mvc.perform(get("/api/parking-history/entries/"+id).with(httpBasic(own,PASSWORD))).andExpect(status().isNotFound());
  write("/api/sessions/"+id+"/ticket/reprints",own,Map.of()).andExpect(status().isNotFound());
  FinancialTestSupport.openShift(mvc,foreign,PASSWORD);JsonNode quote=read("/api/sessions/"+id+"/quote",foreign);
  write("/api/sessions/"+id+"/checkout",foreign,Map.of("requestKey",UUID.randomUUID().toString(),"method","CASH","confirmedAmount",quote.path("amount").decimalValue(),"cashTendered",quote.path("amount").decimalValue())).andExpect(status().isOk());
  assertEquals(0,read("/api/parking-history/checkouts?branchId="+branch.getId()+"&search=HP-PRIVATE",own).path("total").asInt());
  mvc.perform(get("/api/receipts/"+id).with(httpBasic(own,PASSWORD))).andExpect(status().isNotFound());write("/api/receipts/"+id+"/reprints",own,Map.of()).andExpect(status().isNotFound());
  mvc.perform(get("/api/parking-history/entries").with(httpBasic("testadmin","adminpassword"))).andExpect(status().isForbidden());
 }
}
