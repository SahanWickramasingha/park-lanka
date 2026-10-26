package lk.sahan.parking;

import com.fasterxml.jackson.databind.*;
import lk.sahan.parking.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"test.cashier.username=quote.cashier","test.cashier.password=quote-password","DATABASE_URL=jdbc:h2:mem:quotes;DB_CLOSE_DELAY=-1","PARKING_INITIAL_SLOTS=20"})
@AutoConfigureMockMvc @Import(CheckoutQuoteTest.Time.class)
class CheckoutQuoteTest {
 static final MutableClock CLOCK=new MutableClock();
 static class MutableClock extends Clock {
  final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-25T00:00:00.123456789Z"));
  public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return Clock.fixed(instant(),z);}public Instant instant(){return now.get();}
  void advance(long seconds){now.updateAndGet(t->t.plusSeconds(seconds));}
 }
 @TestConfiguration static class Time {@Bean @Primary Clock quoteClock(){return CLOCK;}}
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate sql;@Autowired CashierService cashiers;@Autowired BranchService branches;
 @BeforeEach void prepare()throws Exception{CLOCK.now.set(Instant.parse("2026-10-25T00:00:00.123456789Z"));FinancialTestSupport.openShift(mvc,"quote.cashier","quote-password");}
 JsonNode body(ResultActions r)throws Exception{return json.readTree(r.andReturn().getResponse().getContentAsString());}
 long enter()throws Exception{
  JsonNode slots=body(mvc.perform(get("/api/slots").with(httpBasic("quote.cashier","quote-password"))));long slot=0;for(JsonNode s:slots)if(!s.path("occupied").asBoolean()){slot=s.path("id").asLong();break;}
  return body(mvc.perform(post("/api/sessions").with(httpBasic("quote.cashier","quote-password")).contentType("application/json").content(json.writeValueAsString(Map.of("plate","Q-"+UUID.randomUUID().toString().substring(0,8),"vehicleType","CAR","slotId",slot)))).andExpect(status().isCreated())).path("id").asLong();
 }
 JsonNode quote(long id)throws Exception{return body(mvc.perform(get("/api/sessions/{id}/quote",id).with(httpBasic("quote.cashier","quote-password"))).andExpect(status().isOk()));}
 Map<String,Object> request(JsonNode q,String cash){return new HashMap<>(Map.of("method","CASH","requestKey",UUID.randomUUID().toString(),"quoteId",q.path("quoteId").asText(),"confirmedAmount",q.path("amount").decimalValue(),"cashTendered",cash));}
 ResultActions pay(long id,Map<String,Object> request)throws Exception{return pay(id,request,"quote.cashier","quote-password");}
 ResultActions pay(long id,Map<String,Object> request,String user,String password)throws Exception{return mvc.perform(post("/api/sessions/{id}/checkout",id).with(httpBasic(user,password)).contentType("application/json").content(json.writeValueAsString(request)));}
 void unpaid(long id){assertEquals(0,sql.queryForObject("select count(*) from payments where session_id=?",Integer.class,id));assertNull(sql.queryForObject("select exited_at from parking_sessions where id=?",Object.class,id));assertTrue(sql.queryForObject("select occupied from parking_slots where id=(select slot_id from parking_sessions where id=?)",Boolean.class,id));}

 @Test void reproduces3037MinuteBoundaryButHonorsQuoted3037WithinValidity()throws Exception{
  long id=enter();CLOCK.advance(3036*60+59);JsonNode q=quote(id);assertEquals(3037,q.path("billableMinutes").asLong());assertEquals(3037,q.path("amount").asInt());
  Instant cutoff=Instant.parse(q.path("billingCutoff").asText());assertEquals(cutoff.plusSeconds(120),Instant.parse(q.path("expiresAt").asText()));assertEquals(0,cutoff.getNano()%1000);
  CLOCK.advance(119);
  // This is precisely the pre-fix mismatch: re-billing at confirmation would charge 3039, despite cash covering 3037.
  assertEquals(3039,FeeCalculator.policyCharge(Instant.parse(q.path("enteredAt").asText()),CLOCK.instant(),new lk.sahan.parking.domain.PricingPolicy(BigDecimal.ONE,"MINUTE",0,null)).amount().intValue());
  JsonNode receipt=body(pay(id,request(q,"3100.00")).andExpect(status().isOk()).andExpect(jsonPath("$.changeDue").value(63)).andExpect(jsonPath("$.amount").value(3037)).andExpect(jsonPath("$.billableMinutes").value(3037)));
  assertEquals(q.path("billingCutoff"),receipt.path("billingCutoff"));assertTrue(Instant.parse(receipt.path("exitedAt").asText()).isAfter(cutoff));assertEquals(q.path("quoteId"),receipt.path("quoteId"));
  assertFalse(sql.queryForObject("select occupied from parking_slots where id=(select slot_id from parking_sessions where id=?)",Boolean.class,id));
  JsonNode saved=body(mvc.perform(get("/api/receipts/{id}",id).with(httpBasic("quote.cashier","quote-password"))).andExpect(status().isOk()));assertEquals(receipt,saved);
  JsonNode history=body(mvc.perform(get("/api/parking-history/checkouts").param("from","2026-10-25").param("to","2026-10-30").param("search",String.valueOf(id)).with(httpBasic("quote.cashier","quote-password"))).andExpect(status().isOk()));
  JsonNode historical=java.util.stream.StreamSupport.stream(history.path("rows").spliterator(),false).filter(r->r.path("sessionId").asLong()==id).findFirst().orElseThrow();assertEquals(3037,historical.path("amount").asInt());assertEquals(63,historical.path("changeDue").asInt());
 }
 @Test void expiryReturnsPersistedFreshQuoteAndExplicitReconfirmationSucceeds()throws Exception{
  long id=enter();CLOCK.advance(59);JsonNode q=quote(id);var request=request(q,"5.00");CLOCK.advance(120);
  JsonNode rejected=body(pay(id,request).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_CHANGED")).andExpect(jsonPath("$.attemptRecorded").value(false)));unpaid(id);
  JsonNode fresh=rejected.path("quote");assertNotEquals(q.path("quoteId"),fresh.path("quoteId"));assertEquals(3,fresh.path("amount").asInt());
  assertEquals(1,sql.queryForObject("select count(*) from checkout_quotes where id=?",Integer.class,fresh.path("quoteId").asText()));
  request.put("quoteId",fresh.path("quoteId").asText());request.put("confirmedAmount",fresh.path("amount").decimalValue());request.put("requestKey",UUID.randomUUID().toString());CLOCK.advance(1);
  pay(id,request).andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(3)).andExpect(jsonPath("$.changeDue").value(2));
 }
 @Test void insufficientCashAndTamperedPricesDoNotCloseSession()throws Exception{
  long id=enter();JsonNode q=quote(id);var r=request(q,"0.99");pay(id,r).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INSUFFICIENT_CASH"));unpaid(id);
  r.put("cashTendered","1.001");pay(id,r).andExpect(status().isBadRequest());unpaid(id);
  r.put("cashTendered","5.00");r.put("confirmedAmount","0.00");pay(id,r).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_CHANGED"));unpaid(id);
  r.put("confirmedAmount","1.00");pay(id,r).andExpect(status().isOk()).andExpect(jsonPath("$.changeDue").value(4));
 }
 @Test void concurrentSameQuoteAndKeyMakesOnePaymentAndCompletedReplayIgnoresExpiry()throws Exception{
  long id=enter();JsonNode q=quote(id);var r=request(q,"5.00");ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);
  try{Callable<JsonNode> job=()->{gate.await();return body(pay(id,r).andExpect(status().isOk()));};var a=pool.submit(job);var b=pool.submit(job);gate.countDown();assertEquals(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
  assertEquals(1,sql.queryForObject("select count(*) from payments where session_id=?",Integer.class,id));CLOCK.advance(1000);
  JsonNode replay=body(pay(id,r).andExpect(status().isOk()));mvc.perform(get("/api/checkouts/{key}",r.get("requestKey")).with(httpBasic("quote.cashier","quote-password"))).andExpect(status().isOk()).andExpect(jsonPath("$.paymentId").value(replay.path("paymentId").asLong()));
  r.put("requestKey",UUID.randomUUID().toString());pay(id,r).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_CHECKED_OUT"));assertEquals(1,sql.queryForObject("select count(*) from payments where session_id=?",Integer.class,id));
 }
 @Test void differentKeysForSameQuoteStillMakeOnlyOnePayment()throws Exception{
  long id=enter();JsonNode q=quote(id);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);
  try{Callable<Integer> job=()->{var r=request(q,"5.00");gate.await();return pay(id,r).andReturn().getResponse().getStatus();};var a=pool.submit(job);var b=pool.submit(job);gate.countDown();var codes=new ArrayList<>(List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS)));Collections.sort(codes);assertEquals(List.of(200,409),codes);}finally{pool.shutdownNow();}assertEquals(1,sql.queryForObject("select count(*) from payments where session_id=?",Integer.class,id));
 }
 @Test void quotesAreBoundToCashierBranchAndSession()throws Exception{
  long id=enter(),other=enter();JsonNode q=quote(id);pay(other,request(q,"5.00")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QUOTE_NOT_FOUND"));unpaid(id);unpaid(other);
  String name="q"+UUID.randomUUID().toString().substring(0,8);cashiers.create(name,"other-password",branches.main().getId());FinancialTestSupport.openShift(mvc,name,"other-password");pay(id,request(q,"5.00"),name,"other-password").andExpect(status().isNotFound());unpaid(id);
  long foreign=branches.create("Foreign quote branch",2).getId();String remote=name+"r";cashiers.create(remote,"other-password",foreign);FinancialTestSupport.openShift(mvc,remote,"other-password");pay(id,request(q,"5.00"),remote,"other-password").andExpect(status().isNotFound());unpaid(id);
 }
 @Test void missingQuoteRequiresReviewAndClosedShiftStillBlocksValidQuote()throws Exception{
  long id=enter();JsonNode q=quote(id);var r=request(q,"5.00");r.remove("quoteId");pay(id,r).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_CHANGED"));unpaid(id);r.put("quoteId",q.path("quoteId").asText());
  JsonNode shift=body(mvc.perform(get("/api/shifts/current").with(httpBasic("quote.cashier","quote-password")))).path("shift");
  mvc.perform(post("/api/shifts/{id}/close",shift.path("id").asLong()).with(httpBasic("quote.cashier","quote-password")).contentType("application/json").content(json.writeValueAsString(Map.of("requestKey",UUID.randomUUID().toString(),"countedCash",shift.path("expectedCash").decimalValue(),"reason","Test closing")))).andExpect(status().isOk());
  pay(id,r).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_REQUIRED"));unpaid(id);
 }
 @Test void legacyCommittedAttemptWithoutQuoteIdStillReplaysItsOriginalReceipt()throws Exception{
  long id=enter();JsonNode q=quote(id);var r=request(q,"5.00");r.remove("quoteId");
  String fingerprint=FinancialInputs.hash(id,"CASH","",new BigDecimal("1.00"),new BigDecimal("5.00"),false);
  sql.update("update parking_sessions set exited_at=?,active_plate=null where id=?",java.time.OffsetDateTime.ofInstant(CLOCK.instant(),ZoneOffset.UTC),id);
  sql.update("update parking_slots set occupied=false where id=(select slot_id from parking_sessions where id=?)",id);
  sql.update("insert into payments(session_id,amount,method,reference,paid_at,cashier_username,request_key,request_fingerprint,cash_tendered,change_due) values(?,1.00,'CASH','',?,'quote.cashier',?,?,5.00,4.00)",id,java.time.OffsetDateTime.ofInstant(CLOCK.instant(),ZoneOffset.UTC),r.get("requestKey"),fingerprint);
  CLOCK.advance(1000);pay(id,r).andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(1)).andExpect(jsonPath("$.changeDue").value(4)).andExpect(jsonPath("$.quoteId").isEmpty());
  assertEquals(1,sql.queryForObject("select count(*) from payments where session_id=?",Integer.class,id));
 }
}
