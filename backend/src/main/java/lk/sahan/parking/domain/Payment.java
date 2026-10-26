package lk.sahan.parking.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments")
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @OneToOne(optional = false) @JoinColumn(unique = true) private ParkingSession session;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 16) private String method;
    @Column(length = 80) private String reference;
    @Column(nullable = false) private Instant paidAt;
    @Column(length = 32) private String cashierUsername;
    @Column(length=36,unique=true) private String requestKey;
    @Column(length=64) private String requestFingerprint;
    @Column(precision=12,scale=2) private BigDecimal cashTendered;
    @Column(precision=12,scale=2) private BigDecimal changeDue;
    @ManyToOne private CashierShift shift;
    private Instant billingCutoff;
    @Column(length=36) private String quoteId;
    protected Payment() {}
    public Payment(ParkingSession session, BigDecimal amount, String method, String reference, Instant paidAt, String cashierUsername) {
        this.session = session; this.amount = amount; this.method = method; this.reference = reference;
        this.paidAt = paidAt; this.cashierUsername = cashierUsername;
    }
    public Long getId() { return id; }
    public ParkingSession getSession() { return session; }
    public BigDecimal getAmount() { return amount; }
    public String getMethod() { return method; }
    public String getReference() { return reference; }
    public Instant getPaidAt() { return paidAt; }
    public String getCashierUsername() { return cashierUsername; }
    public String getRequestKey(){return requestKey;}
    public String getRequestFingerprint(){return requestFingerprint;}
    public BigDecimal getCashTendered(){return cashTendered;}
    public BigDecimal getChangeDue(){return changeDue;}
    public CashierShift getShift(){return shift;}
    public Instant getBillingCutoff(){return billingCutoff==null?session.getExitedAt():billingCutoff;}
    public String getQuoteId(){return quoteId;}
    public void quoted(CheckoutQuote quote){billingCutoff=quote.billingCutoff;quoteId=quote.id;}
    public void assignShift(CashierShift shift){this.shift=shift;}
    public void attempt(String key,String fingerprint,BigDecimal tendered,BigDecimal change){this.requestKey=key;this.requestFingerprint=fingerprint;this.cashTendered=tendered;this.changeDue=change;}
}
