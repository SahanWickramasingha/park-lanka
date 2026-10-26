package lk.sahan.parking;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"test.cashier.username=checkout","test.cashier.password=checkout-password","DATABASE_URL=jdbc:h2:mem:checkoutsafety;DB_CLOSE_DELAY=-1","PARKING_INITIAL_SLOTS=10"})
@AutoConfigureMockMvc
class CheckoutSafetyTest {
 @BeforeEach void activeShiftFixture()throws Exception{FinancialTestSupport.openShift(mvc,"checkout","checkout-password");}
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate sql;
 long enter()throws Exception{
  JsonNode slots=json.readTree(mvc.perform(get("/api/slots").with(httpBasic("checkout","checkout-password"))).andReturn().getResponse().getContentAsString());long slot=0;for(JsonNode s:slots)if(!s.path("occupied").asBoolean()){slot=s.path("id").asLong();break;}
  return json.readTree(mvc.perform(post("/api/sessions").with(httpBasic("checkout","checkout-password")).contentType("application/json").content(json.writeValueAsString(Map.of("plate","CS-"+UUID.randomUUID().toString().substring(0,8),"vehicleType","CAR","slotId",slot)))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asLong();
 }
 Map<String,Object> request(){return new HashMap<>(Map.of("method","CASH","requestKey",UUID.randomUUID().toString(),"confirmedAmount","1.00","cashTendered","5.00"));}
 org.springframework.test.web.servlet.ResultActions pay(long id,Map<String,Object> data)throws Exception{
  synchronized(data){if(!data.containsKey("quoteId")){Object quoted=FinancialTestSupport.quoted(mvc,json,"checkout","checkout-password",post("/api/sessions/"+id+"/checkout"),data);if(quoted instanceof Map<?,?> q&&q.containsKey("quoteId"))data.put("quoteId",q.get("quoteId"));}}
  return mvc.perform(post("/api/sessions/{id}/checkout",id).with(httpBasic("checkout","checkout-password")).contentType("application/json").content(json.writeValueAsString(data)));
 }
 @Test void completedCheckoutLeavesActiveListReleasesReusableSlotAndPreservesHistoryAndTotals()throws Exception{
  JsonNode before=json.readTree(mvc.perform(get("/api/reports/today").with(httpBasic("checkout","checkout-password"))).andReturn().getResponse().getContentAsString());
  JsonNode shiftBefore=json.readTree(mvc.perform(get("/api/shifts/current").with(httpBasic("checkout","checkout-password"))).andReturn().getResponse().getContentAsString()).path("shift");
  long id=enter();long slot=sql.queryForObject("select slot_id from parking_sessions where id=?",Long.class,id);
  mvc.perform(get("/api/sessions/{id}/quote",id).with(httpBasic("checkout","checkout-password"))).andExpect(status().isOk());
  assertNull(sql.queryForObject("select exited_at from parking_sessions where id=?",Object.class,id));
  assertNotNull(sql.queryForObject("select active_plate from parking_sessions where id=?",String.class,id));
  assertTrue(sql.queryForObject("select occupied from parking_slots where id=?",Boolean.class,slot));
  assertEquals(0,sql.queryForObject("select count(*) from payments where session_id=?",Integer.class,id));
  var request=request();request.put("cashTendered","0.00");pay(id,request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INSUFFICIENT_CASH"));
  assertNull(sql.queryForObject("select exited_at from parking_sessions where id=?",Object.class,id));assertTrue(sql.queryForObject("select occupied from parking_slots where id=?",Boolean.class,slot));
  request.put("cashTendered","5.00");JsonNode receipt=json.readTree(pay(id,request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  assertNotNull(sql.queryForObject("select exited_at from parking_sessions where id=?",Object.class,id));assertNull(sql.queryForObject("select active_plate from parking_sessions where id=?",Object.class,id));
  assertFalse(sql.queryForObject("select occupied from parking_slots where id=?",Boolean.class,slot));
  JsonNode active=json.readTree(mvc.perform(get("/api/sessions").with(httpBasic("checkout","checkout-password"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  assertTrue(java.util.stream.StreamSupport.stream(active.spliterator(),false).noneMatch(row->row.path("id").asLong()==id));
  JsonNode slots=json.readTree(mvc.perform(get("/api/slots").with(httpBasic("checkout","checkout-password"))).andReturn().getResponse().getContentAsString());
  assertFalse(java.util.stream.StreamSupport.stream(slots.spliterator(),false).filter(row->row.path("id").asLong()==slot).findFirst().orElseThrow().path("occupied").asBoolean());
  mvc.perform(get("/api/receipts").param("search",String.valueOf(id)).with(httpBasic("checkout","checkout-password"))).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].paymentId").value(receipt.path("paymentId").asLong()));
  mvc.perform(post("/api/receipts/{id}/reprints",id).with(httpBasic("checkout","checkout-password"))).andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value(id));
  JsonNode after=json.readTree(mvc.perform(get("/api/reports/today").with(httpBasic("checkout","checkout-password"))).andReturn().getResponse().getContentAsString());
  assertEquals(before.path("payments").asInt()+1,after.path("payments").asInt());
  assertEquals(0,after.path("revenue").decimalValue().subtract(before.path("revenue").decimalValue()).compareTo(java.math.BigDecimal.ONE));
  JsonNode shiftAfter=json.readTree(mvc.perform(get("/api/shifts/current").with(httpBasic("checkout","checkout-password"))).andReturn().getResponse().getContentAsString()).path("shift");
  assertEquals(0,shiftAfter.path("expectedCash").decimalValue().subtract(shiftBefore.path("expectedCash").decimalValue()).compareTo(java.math.BigDecimal.ONE));
  // Same-key replay remains one historical payment, then the very same slot can accept a new session.
  pay(id,request).andExpect(status().isOk()).andExpect(jsonPath("$.paymentId").value(receipt.path("paymentId").asLong()));
  assertEquals(1,sql.queryForObject("select count(*) from payments where session_id=?",Integer.class,id));
  mvc.perform(post("/api/sessions").with(httpBasic("checkout","checkout-password")).contentType("application/json").content(json.writeValueAsString(Map.of("plate",receipt.path("plate").asText(),"vehicleType","CAR","slotId",slot)))).andExpect(status().isCreated());
 }
 @Test void validatesCashQuoteAndExternalConfirmationThenReplaysOriginalResult()throws Exception{
  long id=enter();var request=request();
  request.put("confirmedAmount","0.00");pay(id,request).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_CHANGED")).andExpect(jsonPath("$.quote.amount").value(1));
  request.put("confirmedAmount","1.00");request.put("cashTendered","0.99");pay(id,request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INSUFFICIENT_CASH")).andExpect(jsonPath("$.attemptRecorded").value(false));
  request.put("cashTendered","1.001");pay(id,request).andExpect(status().isBadRequest());
  request.put("method","EXTERNAL_CARD");request.put("reference","terminal-1");pay(id,request).andExpect(status().isBadRequest());
  request.put("method","CASH");request.put("reference","");request.put("cashTendered","5.00");
  JsonNode first=json.readTree(pay(id,request).andExpect(status().isOk()).andExpect(jsonPath("$.changeDue").value(4)).andReturn().getResponse().getContentAsString());
  JsonNode again=json.readTree(pay(id,request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertEquals(first,again);
  mvc.perform(get("/api/checkouts/{key}",request.get("requestKey")).with(httpBasic("checkout","checkout-password"))).andExpect(status().isOk()).andExpect(jsonPath("$.paymentId").value(first.path("paymentId").asLong()));
  request.put("cashTendered","6.00");pay(id,request).andExpect(status().isConflict());
  assertEquals(1,sql.queryForObject("select count(*) from payments where session_id=?",Integer.class,id));
 }
 @Test void concurrentSameAttemptCreatesOnlyOnePayment()throws Exception{
  long id=enter();var request=request();ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);
  try{Callable<Long> job=()->{gate.await();return json.readTree(pay(id,request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("paymentId").asLong();};var a=pool.submit(job);var b=pool.submit(job);gate.countDown();assertEquals(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
  assertEquals(1,sql.queryForObject("select count(*) from payments where session_id=?",Integer.class,id));pay(id,request()).andExpect(status().isConflict());
 }
 @Test void elapsedBoundaryRequiresNewConfirmationAndDoesNotCloseSession()throws Exception{
  long id=enter();sql.update("update parking_sessions set entered_at=? where id=?",java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(61),id);
  pay(id,request()).andExpect(status().isConflict()).andExpect(jsonPath("$.quote.amount").value(2));
  assertNull(sql.queryForObject("select exited_at from parking_sessions where id=?",Object.class,id));
  var confirmed=request();confirmed.put("confirmedAmount","2.00");pay(id,confirmed).andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(2));
 }
 @Test void exactExcessAndZeroCashPersistAndOnlyAmountDueEntersCollections()throws Exception{
  for(String[] scenario:List.of(new String[]{"350.00","350.00","0.00"},new String[]{"350.00","500.00","150.00"},new String[]{"0.00","0.00","0.00"})){
   var before=json.readTree(mvc.perform(get("/api/shifts/current").with(httpBasic("checkout","checkout-password"))).andReturn().getResponse().getContentAsString()).path("shift");
   var reportBefore=json.readTree(mvc.perform(get("/api/reports/today").with(httpBasic("checkout","checkout-password"))).andReturn().getResponse().getContentAsString());
   long id=enter();sql.update("update parking_sessions set policy_rate=350.00, policy_unit='HOUR', policy_grace_minutes=? where id=?",scenario[0].equals("0.00")?60:0,id);
   var request=request();request.put("confirmedAmount",scenario[0]);request.put("cashTendered",scenario[1]);
   JsonNode receipt=json.readTree(pay(id,request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
   assertEquals(0,new java.math.BigDecimal(scenario[0]).compareTo(receipt.path("amount").decimalValue()));
   assertEquals(0,new java.math.BigDecimal(scenario[1]).compareTo(receipt.path("cashTendered").decimalValue()));
   assertEquals(0,new java.math.BigDecimal(scenario[2]).compareTo(receipt.path("changeDue").decimalValue()));
   var stored=sql.queryForMap("select amount,cash_tendered,change_due from payments where session_id=?",id);
   assertEquals(0,new java.math.BigDecimal(scenario[1]).compareTo((java.math.BigDecimal)stored.get("cash_tendered")));
   assertEquals(0,new java.math.BigDecimal(scenario[2]).compareTo((java.math.BigDecimal)stored.get("change_due")));
   JsonNode reprint=json.readTree(mvc.perform(post("/api/receipts/{id}/reprints",id).with(httpBasic("checkout","checkout-password"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
   assertEquals(receipt,reprint);pay(id,request).andExpect(status().isOk());
   pay(id,request()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_CHECKED_OUT"));
   var after=json.readTree(mvc.perform(get("/api/shifts/current").with(httpBasic("checkout","checkout-password"))).andReturn().getResponse().getContentAsString()).path("shift");
   assertEquals(0,new java.math.BigDecimal(scenario[0]).compareTo(after.path("cashCollections").decimalValue().subtract(before.path("cashCollections").decimalValue())));
   assertEquals(0,new java.math.BigDecimal(scenario[0]).compareTo(after.path("expectedCash").decimalValue().subtract(before.path("expectedCash").decimalValue())));
   var reportAfter=json.readTree(mvc.perform(get("/api/reports/today").with(httpBasic("checkout","checkout-password"))).andReturn().getResponse().getContentAsString());
   assertEquals(0,new java.math.BigDecimal(scenario[0]).compareTo(reportAfter.path("revenue").decimalValue().subtract(reportBefore.path("revenue").decimalValue())));
   assertEquals(1,sql.queryForObject("select count(*) from payments where session_id=?",Integer.class,id));
  }
 }
 @Test void invalidCashAndShiftRejectionsNeverCloseSessionAndKeyRemainsUsable()throws Exception{
  long id=enter();var request=request();
  for(String value:List.of("-1.00","1.001","NaN","not-money","10000000000.00")){
   request.put("cashTendered",value);pay(id,request).andExpect(status().isBadRequest());
   assertNull(sql.queryForObject("select exited_at from parking_sessions where id=?",Object.class,id));
   assertEquals(0,sql.queryForObject("select count(*) from payments where session_id=?",Integer.class,id));
  }
  var shift=json.readTree(mvc.perform(get("/api/shifts/current").with(httpBasic("checkout","checkout-password"))).andReturn().getResponse().getContentAsString()).path("shift");
  mvc.perform(post("/api/shifts/{id}/close",shift.path("id").asLong()).with(httpBasic("checkout","checkout-password")).contentType("application/json").content(json.writeValueAsString(Map.of("requestKey",UUID.randomUUID().toString(),"countedCash",shift.path("expectedCash").decimalValue(),"reason","Test close")))).andExpect(status().isOk());
  request.put("cashTendered","5.00");pay(id,request).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_REQUIRED")).andExpect(jsonPath("$.attemptRecorded").value(false));
  mvc.perform(get("/api/checkouts/{key}",request.get("requestKey")).with(httpBasic("checkout","checkout-password"))).andExpect(status().isNotFound());
  assertNull(sql.queryForObject("select exited_at from parking_sessions where id=?",Object.class,id));
  assertTrue(sql.queryForObject("select occupied from parking_slots where id=(select slot_id from parking_sessions where id=?)",Boolean.class,id));
  FinancialTestSupport.openShift(mvc,"checkout","checkout-password");
  pay(id,request).andExpect(status().isOk());
  // Treat the response as lost, then recover by the original key and replay the original payload.
  long payment=json.readTree(mvc.perform(get("/api/checkouts/{key}",request.get("requestKey")).with(httpBasic("checkout","checkout-password"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("paymentId").asLong();
  pay(id,request).andExpect(status().isOk()).andExpect(jsonPath("$.paymentId").value(payment));
  assertEquals(1,sql.queryForObject("select count(*) from payments where session_id=?",Integer.class,id));
 }
}
