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
@SpringBootTest(properties={"test.cashier.username=legacyreceipt","test.cashier.password=history-password","DATABASE_URL=jdbc:h2:mem:legacyreceipt;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @Transactional
class LegacyCashReceiptTest {
 @Autowired MockMvc mvc;@Autowired BranchService branches;@Autowired SlotRepository slots;@Autowired SessionRepository sessions;@Autowired PaymentRepository payments;
 @Test void legacyCashReceiptAndReprintDoNotInventTenderOrChange()throws Exception{
  var slot=slots.findByBranchId(branches.main().getId()).get(0);var at=Instant.now();
  var session=sessions.saveAndFlush(new ParkingSession("LEGACY-CASH","CAR",slot,at.minusSeconds(60),BigDecimal.ONE,"legacyreceipt"));session.close(at);
  var payment=payments.saveAndFlush(new Payment(session,new BigDecimal("12.50"),"CASH","",at,"legacyreceipt"));long count=payments.count();
  mvc.perform(post("/api/receipts/{id}/reprints",session.getId()).with(httpBasic("legacyreceipt","history-password")))
   .andExpect(status().isOk()).andExpect(jsonPath("$.paymentId").value(payment.getId())).andExpect(jsonPath("$.cashTendered").isEmpty()).andExpect(jsonPath("$.changeDue").isEmpty());
  assertEquals(count,payments.count());
 }
}
