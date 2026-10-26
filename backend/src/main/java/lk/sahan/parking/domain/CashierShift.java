package lk.sahan.parking.domain;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
@Entity @Table(name="cashier_shifts")
public class CashierShift {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Column(nullable=false,length=160) public String ownerKey;
 @Column(nullable=false,length=128) public String cashier;
 @Column(nullable=false) public Long branchId;
 @Column(nullable=false,length=100) public String branch;
 @Column(unique=true,length=200) public String activeKey;
 @Column(nullable=false,unique=true,length=36) public String openRequestKey;
 @Column(nullable=false,length=64) public String openFingerprint;
 @Column(unique=true,length=36) public String closeRequestKey;
 @Column(length=64) public String closeFingerprint;
 @Column(nullable=false) public Instant openedAt;
 public Instant closedAt;
 @Column(nullable=false,precision=12,scale=2) public BigDecimal openingCash;
 @Column(precision=12,scale=2) public BigDecimal countedCash;
 @Column(precision=12,scale=2) public BigDecimal expectedCash;
 @Column(precision=12,scale=2) public BigDecimal difference;
 @Column(precision=12,scale=2) public BigDecimal cashCollections;
 @Column(precision=12,scale=2) public BigDecimal cardCollections;
 @Column(precision=12,scale=2) public BigDecimal cashRefunds;
 @Column(precision=12,scale=2) public BigDecimal cashVoids;
 @Column(length=500) public String reason;
}
