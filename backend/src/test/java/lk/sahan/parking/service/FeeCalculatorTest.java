package lk.sahan.parking.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class FeeCalculatorTest {
    @Test void chargesStartedMinutesAcrossMidnight() {
        Instant start = Instant.parse("2026-09-26T18:29:30Z");
        var charge = FeeCalculator.charge(start, start.plusSeconds(91), new BigDecimal("1.50"));
        assertEquals(2, charge.minutes());
        assertEquals(new BigDecimal("3.00"), charge.amount());
        assertEquals(1, FeeCalculator.charge(start, start, BigDecimal.ONE).minutes());
        assertEquals(2, FeeCalculator.charge(start, start.plusSeconds(60).plusMillis(1), BigDecimal.ONE).minutes());
    }
    @Test void rejectsTimeTravel() {
        Instant start = Instant.parse("2026-09-26T18:29:30Z");
        assertThrows(IllegalArgumentException.class, () -> FeeCalculator.charge(start, start.minusSeconds(1), BigDecimal.ONE));
    }
}
