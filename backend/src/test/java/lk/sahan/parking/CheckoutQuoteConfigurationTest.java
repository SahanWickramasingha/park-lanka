package lk.sahan.parking;
import lk.sahan.parking.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"test.cashier.username=quote.config","test.cashier.password=quote-password","DATABASE_URL=jdbc:h2:mem:quoteconfig;DB_CLOSE_DELAY=-1","parking.checkout.quote-validity-seconds=7"})
class CheckoutQuoteConfigurationTest {
 @Autowired ParkingService parking;@Autowired BranchService branches;
 @Test void configuredValidityReplacesDefault(){
  long branch=branches.main().getId();long slot=parking.allSlots(branch).stream().filter(s->!s.occupied()).findFirst().orElseThrow().id();
  long id=parking.enter(new ParkingService.EntryRequest("QUOTE-CONFIG","CAR",slot),branch,"quote.config").id();
  var quote=parking.quote(id,branch,"quote.config");assertEquals(7,java.time.Duration.between(quote.billingCutoff(),quote.expiresAt()).getSeconds());
 }
}
