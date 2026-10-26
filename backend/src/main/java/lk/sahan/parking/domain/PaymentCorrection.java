package lk.sahan.parking.domain;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
@Entity @Table(name="payment_corrections",uniqueConstraints=@UniqueConstraint(columnNames={"payment_id","external_reference"}))
public class PaymentCorrection {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @ManyToOne(optional=false) @JoinColumn(updatable=false) public Payment payment;
 @Column(nullable=false,length=8,updatable=false) public String type;
 @Column(nullable=false,precision=12,scale=2,updatable=false) public BigDecimal amount;
 @Column(nullable=false,length=500,updatable=false) public String reason;
 @Column(nullable=false,length=128,updatable=false) public String actor;
 @Column(nullable=false,updatable=false) public Instant occurredAt;
 @Column(nullable=false,updatable=false) public Long branchId;
 @Column(nullable=false,length=100,updatable=false) public String branch;
 @Column(nullable=false,length=16,updatable=false) public String method;
 @Column(length=80,updatable=false) public String externalReference;
 @ManyToOne @JoinColumn(updatable=false) public CashierShift movementShift;
 @Column(nullable=false,unique=true,length=36,updatable=false) public String requestKey;
 @Column(nullable=false,length=64,updatable=false) public String fingerprint;
}
