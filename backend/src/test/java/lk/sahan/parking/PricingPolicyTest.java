package lk.sahan.parking;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import lk.sahan.parking.service.*;
import lk.sahan.parking.repo.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"test.cashier.username=pricing","test.cashier.password=pricing-password","DATABASE_URL=jdbc:h2:mem:pricingpolicy;DB_CLOSE_DELAY=-1"})
@Transactional
class PricingPolicyTest {
 @Autowired PricingService pricing;@Autowired ParkingService parking;@Autowired BranchService branches;@Autowired SlotRepository slots;@Autowired RateService rates;@Autowired SessionRepository sessions;
 @Test void precedenceAndFullEntrySnapshotSurviveLaterSettingsChanges(){
  long branch=branches.main().getId();pricing.save(branch,new PricingService.Request("DEFAULT",new BigDecimal("3.00"),"HOUR",0,new BigDecimal("50.00")));
  pricing.save(branch,new PricingService.Request("CAR",new BigDecimal("2.00"),"MINUTE",5,new BigDecimal("20.00")));
  assertEquals("Branch + vehicle type",pricing.effective(branch,"CAR").source());assertEquals(new BigDecimal("3.00"),pricing.effective(branch,"VAN").policy().rate());
  var entered=parking.enter(new ParkingService.EntryRequest("POLICY-123","CAR",slots.findByBranchId(branch).get(0).getId()),branch,"pricing");
  pricing.remove(branch,"CAR");pricing.remove(branch,"DEFAULT");rates.update(new BigDecimal("9.00"));assertEquals("Global default",pricing.effective(branch,"CAR").source());
  var quote=parking.quote(entered.id(),branch,"pricing");assertEquals(new BigDecimal("0.00"),quote.amount());assertEquals(5,quote.breakdown().graceMinutes());assertEquals(new BigDecimal("20.00"),quote.breakdown().dailyCap());
  assertEquals(new BigDecimal("2.00"),sessions.findById(entered.id()).orElseThrow().getPolicy().rate());
 }
}
