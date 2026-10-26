package lk.sahan.parking.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "parking_rates")
public class ParkingRate {
    @Id private Long id;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal perMinute;
    protected ParkingRate() {}
    public ParkingRate(BigDecimal perMinute) { this.id = 1L; this.perMinute = perMinute; }
    public BigDecimal getPerMinute() { return perMinute; }
    public void setPerMinute(BigDecimal perMinute) { this.perMinute = perMinute; }
}
