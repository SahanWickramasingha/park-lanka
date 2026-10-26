package lk.sahan.parking;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.*;
import lk.sahan.parking.service.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.math.BigDecimal;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"test.cashier.username=correctionbootstrap","test.cashier.password=bootstrap-password","PARKING_ADMIN_USER=correctionadmin","PARKING_ADMIN_PASSWORD=admin-password","DATABASE_URL=jdbc:h2:mem:corrections;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class PaymentCorrectionTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired BranchService branches;@Autowired CashierService cashiers;@Autowired JdbcTemplate sql;@Autowired ExcelReportService excel;
 static AtomicInteger seq=new AtomicInteger();String user;long branch,shift,ticket,payment;
 @BeforeEach void fixture()throws Exception{int n=seq.incrementAndGet();user="correctioncashier"+n;branch=branches.create("Correction branch "+n,2).getId();cashiers.create(user,"cashier-password",branch);shift=open("10.00");}
 JsonNode body(ResultActions result)throws Exception{return json.readTree(result.andReturn().getResponse().getContentAsString());}
 ResultActions staffPost(String path,Object request)throws Exception{return mvc.perform(post(path).with(httpBasic(user,"cashier-password")).contentType("application/json").content(json.writeValueAsString(FinancialTestSupport.quoted(mvc,json,user,"cashier-password",post(path),request))));}
 ResultActions correction(Map<String,Object> request)throws Exception{return mvc.perform(post("/api/admin/payments/{id}/corrections",payment).with(httpBasic("correctionadmin","admin-password")).contentType("application/json").content(json.writeValueAsString(request)));}
 long open(String amount)throws Exception{return body(staffPost("/api/shifts",Map.of("requestKey",UUID.randomUUID().toString(),"openingCash",amount)).andExpect(status().isOk())).path("id").asLong();}
 void sale(String method)throws Exception{JsonNode slots=body(mvc.perform(get("/api/slots").with(httpBasic(user,"cashier-password"))).andExpect(status().isOk()));long slot=slots.get(0).path("id").asLong();ticket=body(staffPost("/api/sessions",Map.of("plate","COR-"+user.substring(user.length()-1),"vehicleType","CAR","slotId",slot)).andExpect(status().isCreated())).path("id").asLong();payment=body(staffPost("/api/sessions/"+ticket+"/checkout",Map.of("requestKey",UUID.randomUUID().toString(),"method",method,"reference",method.equals("CASH")?"":"sale-terminal-reference","externalConfirmed",true,"confirmedAmount","1.00","cashTendered","100.00")).andExpect(status().isOk())).path("paymentId").asLong();}
 Map<String,Object> request(String type,String amount){var r=new HashMap<String,Object>(Map.of("requestKey",UUID.randomUUID().toString(),"type",type,"amount",amount,"reason","Customer correction requested","confirmed",true));if(type.equals("REFUND"))r.put("movementShiftId",shift);return r;}
 @Test void cashRefundUsesOccurrenceShiftPreservesClosedOriginalAndDatedNetReports()throws Exception{
  sale("CASH");long originalShift=shift;staffPost("/api/shifts/"+shift+"/close",Map.of("requestKey",UUID.randomUUID().toString(),"countedCash","11.00","reason","")).andExpect(status().isOk());shift=open("10.00");
  sql.update("update payments set paid_at=? where id=?",OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),payment);
  var refund=request("REFUND","0.40");long id=body(correction(refund).andExpect(status().isOk())).path("id").asLong();correction(refund).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
  refund.put("amount","0.41");correction(refund).andExpect(status().isConflict());
  correction(request("VOID","1.00")).andExpect(status().isConflict());correction(request("REFUND","0.60")).andExpect(status().isOk());correction(request("REFUND","0.01")).andExpect(status().isConflict());
  mvc.perform(get("/api/shifts/{id}",originalShift).with(httpBasic(user,"cashier-password"))).andExpect(jsonPath("$.expectedCash").value(11)).andExpect(jsonPath("$.cashRefunds").value(0));
  mvc.perform(get("/api/shifts/current").with(httpBasic(user,"cashier-password"))).andExpect(jsonPath("$.shift.expectedCash").value(9)).andExpect(jsonPath("$.shift.cashRefunds").value(1));
  mvc.perform(get("/api/receipts/{id}",ticket).with(httpBasic(user,"cashier-password"))).andExpect(jsonPath("$.amount").value(1)).andExpect(jsonPath("$.refunded").value(1)).andExpect(jsonPath("$.netAmount").value(0));
  mvc.perform(get("/api/parking-history/checkouts").param("from",LocalDate.now(ZoneId.of("Asia/Colombo")).minusDays(1).toString()).param("to",LocalDate.now(ZoneId.of("Asia/Colombo")).toString()).with(httpBasic(user,"cashier-password"))).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].status").value("Refunded")).andExpect(jsonPath("$.rows[0].amount").value(1));
  mvc.perform(get("/api/reports/today").with(httpBasic(user,"cashier-password"))).andExpect(jsonPath("$.grossCollections").value(0)).andExpect(jsonPath("$.refunds").value(1)).andExpect(jsonPath("$.revenue").value(-1));
  assertEquals(new BigDecimal("1.00"),sql.queryForObject("select amount from payments where id=?",BigDecimal.class,payment));assertNotNull(sql.queryForObject("select exited_at from parking_sessions where id=?",Object.class,ticket));assertEquals(0,sql.queryForObject("select count(*) from parking_slots where branch_id=? and occupied=true",Integer.class,branch));
  LocalDate today=LocalDate.now(ZoneId.of("Asia/Colombo"));try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(excel.generate("payments",branch,today,today)))){assertEquals(1,workbook.getSheet("Records").getPhysicalNumberOfRows());assertEquals(3,workbook.getSheet("Corrections").getPhysicalNumberOfRows());assertEquals("-1.00",workbook.getSheet("Report").getRow(14).getCell(1).getStringCellValue());}
 }
 @Test void voidIsFullUncollectedReversalAndCannotFollowOrPrecedeRefund()throws Exception{
  sale("CASH");var request=request("VOID","0.50");request.put("notCollectedConfirmed",true);correction(request).andExpect(status().isConflict());request.put("amount","1.00");request.put("notCollectedConfirmed",false);correction(request).andExpect(status().isConflict());request.put("notCollectedConfirmed",true);correction(request).andExpect(status().isOk());correction(request("REFUND","0.50")).andExpect(status().isConflict());
  mvc.perform(get("/api/shifts/current").with(httpBasic(user,"cashier-password"))).andExpect(jsonPath("$.shift.expectedCash").value(10)).andExpect(jsonPath("$.shift.cashVoids").value(1));assertEquals(1,sql.queryForObject("select count(*) from payments where id=?",Integer.class,payment));
  mvc.perform(get("/api/parking-history/checkouts").with(httpBasic(user,"cashier-password"))).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].status").value("Voided")).andExpect(jsonPath("$.rows[0].amount").value(1));
 }
 @Test void externalCorrectionsNeedTerminalEvidenceAndRemainAdminOnly()throws Exception{
  sale("EXTERNAL_CARD");var request=request("REFUND","0.25");request.remove("movementShiftId");correction(request).andExpect(status().isBadRequest());request.put("externalReference","terminal-refund-1");request.put("externalConfirmed",true);correction(request).andExpect(status().isOk());request.put("requestKey",UUID.randomUUID().toString());correction(request).andExpect(status().isConflict());
  mvc.perform(post("/api/admin/payments/{id}/corrections",payment).with(httpBasic(user,"cashier-password")).contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isForbidden());
 }
 @Test void concurrentRefundsRespectCumulativeLimitAndSameAttemptReplays()throws Exception{
  sale("CASH");var same=request("REFUND","0.25");ExecutorService pool=Executors.newFixedThreadPool(2);try{Callable<Long> job=()->body(correction(same).andExpect(status().isOk())).path("id").asLong();var a=pool.submit(job);var b=pool.submit(job);assertEquals(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
   Callable<Integer> refund=()->correction(request("REFUND","0.60")).andReturn().getResponse().getStatus();var x=pool.submit(refund);var y=pool.submit(refund);var codes=new ArrayList<>(List.of(x.get(30,TimeUnit.SECONDS),y.get(30,TimeUnit.SECONDS)));Collections.sort(codes);assertEquals(List.of(200,409),codes);
  }finally{pool.shutdownNow();}assertEquals(new BigDecimal("0.85"),sql.queryForObject("select sum(amount) from payment_corrections where payment_id=?",BigDecimal.class,payment));
 }
}
