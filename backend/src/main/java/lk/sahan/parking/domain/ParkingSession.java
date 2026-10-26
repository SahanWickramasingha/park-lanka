package lk.sahan.parking.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "parking_sessions", uniqueConstraints = @UniqueConstraint(columnNames = "active_plate"))
public class ParkingSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "plate", nullable = false, length = 24) private String plate;
    @Column(name = "active_plate", length = 24) private String activePlate;
    @Column(nullable = false, length = 24) private String vehicleType;
    @ManyToOne(optional = false, fetch = FetchType.EAGER) private ParkingSlot slot;
    @Column(nullable = false) private Instant enteredAt;
    @Column(precision = 12, scale = 2) private BigDecimal ratePerMinute;
    @Column(length = 32) private String enteredBy;
    private Instant exitedAt;
    @Column(length=36, unique=true) private String ticketToken;
    @Column(precision=12,scale=2) private BigDecimal policyRate;
    @Column(length=8) private String policyUnit;
    private Integer policyGraceMinutes;
    @Column(precision=12,scale=2) private BigDecimal policyDailyCap;
    @Column(length=80) private String policySource;
    protected ParkingSession() {}
    public ParkingSession(String plate, String vehicleType, ParkingSlot slot, Instant enteredAt, BigDecimal ratePerMinute, String enteredBy) {
        this.plate = plate; this.activePlate = plate; this.vehicleType = vehicleType;
        this.slot = slot; this.enteredAt = enteredAt; this.ratePerMinute = ratePerMinute; this.enteredBy = enteredBy;
        this.ticketToken = java.util.UUID.randomUUID().toString();
    }
    public Long getId() { return id; }
    public String getPlate() { return plate; }
    public String getVehicleType() { return vehicleType; }
    public ParkingSlot getSlot() { return slot; }
    public Instant getEnteredAt() { return enteredAt; }
    public Instant getExitedAt() { return exitedAt; }
    public BigDecimal getRatePerMinute() { return ratePerMinute; }
    public String getEnteredBy() { return enteredBy; }
    public String getTicketToken() { return ticketToken; }
    public PricingPolicy getPolicy(){return policyUnit==null?null:new PricingPolicy(policyRate,policyUnit,policyGraceMinutes,policyDailyCap);}
    public String getPolicySource(){return policySource;}
    public void snapshotPolicy(PricingPolicy p,String source){policyRate=p.rate();policyUnit=p.unit();policyGraceMinutes=p.graceMinutes();policyDailyCap=p.dailyCap();policySource=source;}
    public String ensureTicketToken() { if(ticketToken==null)ticketToken=java.util.UUID.randomUUID().toString();return ticketToken; }
    public void close(Instant at) { this.exitedAt = at; this.activePlate = null; }
}
