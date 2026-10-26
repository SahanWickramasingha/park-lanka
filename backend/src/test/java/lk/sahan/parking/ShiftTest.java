package lk.sahan.parking;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import java.math.BigDecimal;
import lk.sahan.parking.service.*;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"test.cashier.username=shiftbootstrap","test.cashier.password=shift-password","PARKING_ADMIN_USER=shiftadmin","PARKING_ADMIN_PASSWORD=admin-password","DATABASE_URL=jdbc:h2:mem:shifttest;DB_CLOSE_DELAY=-1","PARKING_INITIAL_SLOTS=10"})
@AutoConfigureMockMvc
class ShiftTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired CashierService cashiers;@Autowired BranchService branches;
 @Autowired org.springframework.jdbc.core.JdbcTemplate sql;
 org.springframework.test.web.servlet.ResultActions postAs(String path,String user,Map<String,Object> data)throws Exception{return mvc.perform(post(path).with(httpBasic(user,"shift-password")).contentType("application/json").content(json.writeValueAsString(FinancialTestSupport.quoted(mvc,json,user,"shift-password",post(path),data))));}
 @Test void missingClosedAndForeignShiftsCannotAuthorizeCheckout()throws Exception{
  String owner="shift-owner",other="shift-other",remote="shift-remote";
  cashiers.create(owner,"shift-password",branches.main().getId());cashiers.create(other,"shift-password",branches.main().getId());
  var branch=branches.create("Shift isolated branch",2);cashiers.create(remote,"shift-password",branch.getId());
  for(String user:List.of(owner,remote))postAs("/api/shifts",user,Map.of("requestKey",UUID.randomUUID().toString(),"openingCash","0.00")).andExpect(status().isOk());
  mvc.perform(get("/api/shifts/current").with(httpBasic(other,"shift-password"))).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
  JsonNode slots=json.readTree(mvc.perform(get("/api/slots").with(httpBasic(other,"shift-password"))).andReturn().getResponse().getContentAsString());
  long slot=0;for(JsonNode row:slots)if(!row.path("occupied").asBoolean()){slot=row.path("id").asLong();break;}
  long ticket=json.readTree(postAs("/api/sessions",other,Map.of("plate","SHIFT-OWNER","vehicleType","CAR","slotId",slot)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asLong();
  var payment=Map.<String,Object>of("requestKey",UUID.randomUUID().toString(),"method","CASH","confirmedAmount","1.00","cashTendered","5.00");
  postAs("/api/sessions/"+ticket+"/checkout",other,payment).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_REQUIRED")).andExpect(jsonPath("$.attemptRecorded").value(false));
  postAs("/api/sessions/"+ticket+"/checkout",remote,payment).andExpect(status().isNotFound());
  assertEquals(0,sql.queryForObject("select count(*) from payments where session_id=?",Integer.class,ticket));assertNull(sql.queryForObject("select exited_at from parking_sessions where id=?",Object.class,ticket));
  JsonNode opened=json.readTree(postAs("/api/shifts",other,Map.of("requestKey",UUID.randomUUID().toString(),"openingCash","10.00")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());long id=opened.path("id").asLong();
  // A fresh authenticated request restores the persisted shift without any client-supplied owner/branch.
  mvc.perform(get("/api/shifts/current").with(httpBasic(other,"shift-password"))).andExpect(status().isOk()).andExpect(jsonPath("$.shift.id").value(id)).andExpect(jsonPath("$.shift.branchId").value(branches.main().getId()));
  mvc.perform(get("/api/shifts/{id}",id).with(httpBasic(owner,"shift-password"))).andExpect(status().isNotFound());
  postAs("/api/shifts/"+id+"/close",owner,Map.of("requestKey",UUID.randomUUID().toString(),"countedCash","10.00","reason","")).andExpect(status().isNotFound());
  postAs("/api/shifts/"+id+"/close",other,Map.of("requestKey",UUID.randomUUID().toString(),"countedCash","10.00","reason","")).andExpect(status().isOk());
  postAs("/api/sessions/"+ticket+"/checkout",other,payment).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_REQUIRED"));
  long next=json.readTree(postAs("/api/shifts",other,Map.of("requestKey",UUID.randomUUID().toString(),"openingCash","0.00")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asLong();
  postAs("/api/sessions/"+ticket+"/checkout",other,payment).andExpect(status().isOk()).andExpect(jsonPath("$.shiftId").value(next));
  postAs("/api/sessions/"+ticket+"/checkout",other,payment).andExpect(status().isOk());assertEquals(1,sql.queryForObject("select count(*) from payments where session_id=?",Integer.class,ticket));
 }
 @Test void invalidOpenRollsBackAndConcurrentDifferentKeysHaveOneWinner()throws Exception{
  String user="shift-two-keys";cashiers.create(user,"shift-password",branches.main().getId());
  for(String value:List.of("-1.00","1.001","10000000000.00"))postAs("/api/shifts",user,Map.of("requestKey",UUID.randomUUID().toString(),"openingCash",value)).andExpect(status().isBadRequest());
  assertEquals(0,sql.queryForObject("select count(*) from cashier_shifts where cashier=?",Integer.class,user));
  ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);
  try{Callable<Integer> job=()->{gate.await();return postAs("/api/shifts",user,Map.of("requestKey",UUID.randomUUID().toString(),"openingCash","0.00")).andReturn().getResponse().getStatus();};var a=pool.submit(job);var b=pool.submit(job);gate.countDown();var results=new ArrayList<>(List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS)));Collections.sort(results);assertEquals(List.of(200,409),results);}
  finally{pool.shutdownNow();}
  assertEquals(1,sql.queryForObject("select count(*) from cashier_shifts where cashier=? and closed_at is null",Integer.class,user));
  postAs("/api/shifts",user,Map.of("requestKey",UUID.randomUUID().toString(),"openingCash","0.00")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_ALREADY_OPEN"));
 }
 @Test void bootstrapAndDatabaseCashiersUseOwnShiftsWithSafeCloseAndHistoricalTotals()throws Exception{
  String db="shift-db";cashiers.create(db,"shift-password",branches.main().getId());
  for(String user:List.of("shiftbootstrap",db)){
   JsonNode slots=json.readTree(mvc.perform(get("/api/slots").with(httpBasic(user,"shift-password"))).andReturn().getResponse().getContentAsString());long slot=0;for(JsonNode row:slots)if(!row.path("occupied").asBoolean()){slot=row.path("id").asLong();break;}
   long ticket=json.readTree(postAs("/api/sessions",user,Map.of("plate","SH-"+user,"vehicleType","CAR","slotId",slot)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asLong();
   var payment=new HashMap<String,Object>(Map.of("requestKey",UUID.randomUUID().toString(),"method","CASH","confirmedAmount","1.00","cashTendered","100.00"));
   postAs("/api/sessions/"+ticket+"/checkout",user,payment).andExpect(status().isConflict());
   var opening=Map.<String,Object>of("requestKey",UUID.randomUUID().toString(),"openingCash","50.00");
   long shift=json.readTree(postAs("/api/shifts",user,opening).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asLong();
   postAs("/api/shifts",user,opening).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(shift));
   postAs("/api/shifts",user,Map.of("requestKey",UUID.randomUUID().toString(),"openingCash","50.00")).andExpect(status().isConflict());
   postAs("/api/sessions/"+ticket+"/checkout",user,payment).andExpect(status().isOk()).andExpect(jsonPath("$.changeDue").value(99));
   mvc.perform(get("/api/shifts/current").with(httpBasic(user,"shift-password"))).andExpect(jsonPath("$.shift.expectedCash").value(51)).andExpect(jsonPath("$.shift.cashCollections").value(1));
   var closing=new HashMap<String,Object>(Map.of("requestKey",UUID.randomUUID().toString(),"countedCash","50.00","reason",""));
   postAs("/api/shifts/"+shift+"/close",user,closing).andExpect(status().isBadRequest());closing.put("reason","Drawer short by one");
   postAs("/api/shifts/"+shift+"/close",user,closing).andExpect(status().isOk()).andExpect(jsonPath("$.difference").value(-1));postAs("/api/shifts/"+shift+"/close",user,closing).andExpect(status().isOk());
   // A successful attempt still replays after closing; a new financial attempt cannot use it.
   postAs("/api/sessions/"+ticket+"/checkout",user,payment).andExpect(status().isOk());
   String other=user.equals(db)?"shiftbootstrap":db;
   mvc.perform(get("/api/shifts/{id}",shift).with(httpBasic(other,"shift-password"))).andExpect(status().isNotFound());
   mvc.perform(get("/api/admin/shifts/{id}",shift).with(httpBasic("shiftadmin","admin-password"))).andExpect(status().isOk()).andExpect(jsonPath("$.expectedCash").value(51));
  }
 }
 @Test void concurrentOpenAndCloseReuseKeysWithoutDuplicateShifts()throws Exception{
  String user="shift-concurrent";cashiers.create(user,"shift-password",branches.main().getId());var request=Map.<String,Object>of("requestKey",UUID.randomUUID().toString(),"openingCash","0.00");
  ExecutorService pool=Executors.newFixedThreadPool(2);try{Callable<Long> open=()->json.readTree(postAs("/api/shifts",user,request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asLong();var a=pool.submit(open);var b=pool.submit(open);long id=a.get(30,TimeUnit.SECONDS);assertEquals(id,b.get(30,TimeUnit.SECONDS));
   var close=Map.<String,Object>of("requestKey",UUID.randomUUID().toString(),"countedCash","0.00","reason","");Callable<Integer> job=()->postAs("/api/shifts/"+id+"/close",user,close).andReturn().getResponse().getStatus();var x=pool.submit(job);var y=pool.submit(job);assertEquals(200,x.get(30,TimeUnit.SECONDS));assertEquals(200,y.get(30,TimeUnit.SECONDS));
  }finally{pool.shutdownNow();}
 }
 @Test void administratorCanReconcileAStoppedCashierButMustGiveReason()throws Exception{
  var account=cashiers.create("shift-stopped","shift-password",branches.main().getId());long id=json.readTree(postAs("/api/shifts","shift-stopped",Map.of("requestKey",UUID.randomUUID().toString(),"openingCash","10.00")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asLong();cashiers.enable(account.id(),false);
  String url="/api/admin/shifts/"+id+"/close";var request=new HashMap<String,Object>(Map.of("requestKey",UUID.randomUUID().toString(),"countedCash","10.00","reason",""));
  mvc.perform(post(url).with(httpBasic("shiftadmin","admin-password")).contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isBadRequest());request.put("reason","Cashier stopped; drawer reconciled by admin");
  mvc.perform(post(url).with(httpBasic("shiftadmin","admin-password")).contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isOk()).andExpect(jsonPath("$.expectedCash").value(10));
 }
}
