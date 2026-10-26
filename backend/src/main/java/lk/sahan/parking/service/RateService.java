package lk.sahan.parking.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import lk.sahan.parking.domain.ParkingRate;
import lk.sahan.parking.repo.RateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RateService {
    private final RateRepository rates;
    private final BigDecimal initialRate;
    private final AuditService audit;

    public RateService(RateRepository rates, @Value("${parking.rate-per-minute}") BigDecimal initialRate,AuditService audit) {
        this.rates = rates;
        this.initialRate = validate(initialRate);
        this.audit=audit;
    }

    @Bean CommandLineRunner seedRate(RateService service) {
        return args -> service.initialize();
    }

    @Transactional
    public void initialize() {
        if (!rates.existsById(1L)) rates.save(new ParkingRate(initialRate));
    }

    public BigDecimal current() {
        return rates.findById(1L).orElseThrow(() -> new IllegalStateException("Rate not initialized")).getPerMinute();
    }

    // Sessions created before this upgrade have no rate snapshot; use their original configured rate.
    public BigDecimal legacyRate() { return initialRate; }

    @Transactional
    public BigDecimal update(BigDecimal amount) {
        BigDecimal valid = validate(amount);
        ParkingRate rate = rates.findById(1L).orElseThrow(() -> new IllegalStateException("Rate not initialized"));
        audit.record("GLOBAL_RATE_CHANGED","RATE:1",null,null,java.util.Map.of("perMinute",rate.getPerMinute()),java.util.Map.of("perMinute",valid));rate.setPerMinute(valid);
        return valid;
    }

    private static BigDecimal validate(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.compareTo(new BigDecimal("100000.00")) > 0 || value.scale() > 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rate must be LKR 0.01 to 100000.00 with at most two decimals");
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
}
