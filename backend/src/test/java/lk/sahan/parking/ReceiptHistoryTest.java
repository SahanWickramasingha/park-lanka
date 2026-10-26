package lk.sahan.parking;
import lk.sahan.parking.domain.*;
import lk.sahan.parking.repo.*;
import lk.sahan.parking.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"test.cashier.username=history","test.cashier.password=history-password","PARKING_ADMIN_USER=historyadmin","PARKING_ADMIN_PASSWORD=admin-password","DATABASE_URL=jdbc:h2:mem:receipthistory;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @Transactional
class ReceiptHistoryTest {
 @Autowired MockMvc mvc;@Autowired BranchService branches;@Autowired SlotRepository slots;@Autowired SessionRepository sessions;@Autowired PaymentRepository payments;
 @Test void historyIsBranchScopedSearchablePagedAndReadOnly() throws Exception {
  var own=branches.main();var other=branches.create("Other history",2);Instant at=Instant.parse("2026-10-20T20:00:00Z");
  var a=paid(own.getId(),"HIST-111",at);var b=paid(own.getId(),"HIST-222",at.plusSeconds(60));paid(other.getId(),"HIST-333",at);
  long count=payments.count();var cashier=httpBasic("history","history-password");var admin=httpBasic("historyadmin","admin-password");
  mvc.perform(get("/api/receipts").with(cashier).param("branchId",other.getId().toString()).param("size","1").param("from","2026-10-21").param("to","2026-10-21"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.pages").value(2)).andExpect(jsonPath("$.rows[0].sessionId").value(b.getSession().getId()));
  mvc.perform(get("/api/receipts").with(cashier).param("search",a.getSession().getId().toString())).andExpect(jsonPath("$.total").value(1));
  mvc.perform(get("/api/receipts").with(cashier).param("search","hist-111")).andExpect(jsonPath("$.rows[0].reference").value("original-ref"));
  mvc.perform(get("/api/receipts").with(cashier).param("search","%")).andExpect(jsonPath("$.total").value(0));
  mvc.perform(get("/api/admin/receipts").with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3));
  mvc.perform(get("/api/admin/receipts").with(admin).param("branchId",other.getId().toString())).andExpect(jsonPath("$.total").value(1));
  mvc.perform(get("/api/admin/receipts").with(cashier)).andExpect(status().isForbidden());
  mvc.perform(get("/api/receipts").with(cashier).param("from","2026-10-22").param("to","2026-10-21")).andExpect(status().isBadRequest());
  assertEquals(count,payments.count());assertEquals(new BigDecimal("12.50"),payments.findById(a.getId()).orElseThrow().getAmount());
 }
 Payment paid(Long branch,String plate,Instant at){var slot=slots.findByBranchId(branch).get(0);var s=sessions.saveAndFlush(new ParkingSession(plate,"CAR",slot,at.minusSeconds(600),BigDecimal.ONE,"history"));s.close(at);return payments.saveAndFlush(new Payment(s,new BigDecimal("12.50"),"EXTERNAL_CARD","original-ref",at,"history"));}
}
