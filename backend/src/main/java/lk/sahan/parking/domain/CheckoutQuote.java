package lk.sahan.parking.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Immutable server-issued billing decision. Client timestamps and prices never authorize a charge. */
@Entity @Table(name="checkout_quotes")
public class CheckoutQuote {
    @Id @Column(length=36) public String id;
    @ManyToOne(optional=false) public ParkingSession session;
    @Column(nullable=false) public Long branchId;
    @Column(nullable=false,length=160) public String cashierKey;
    @Column(nullable=false) public Instant billingCutoff;
    @Column(nullable=false) public Instant expiresAt;
    @Column(nullable=false,precision=12,scale=2) public BigDecimal amount;
    @Column(nullable=false) public long billableMinutes;
}
