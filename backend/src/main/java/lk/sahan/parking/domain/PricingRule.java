package lk.sahan.parking.domain;
import jakarta.persistence.*;
import java.math.BigDecimal;
@Entity @Table(name="pricing_rules",uniqueConstraints=@UniqueConstraint(columnNames={"branch_id","vehicle_type"}))
public class PricingRule {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Column(nullable=false) public Long branchId;
 @Column(nullable=false,length=24) public String vehicleType;
 @Column(nullable=false,precision=12,scale=2) public BigDecimal rate;
 @Column(nullable=false,length=8) public String billingUnit;
 @Column(nullable=false) public int graceMinutes;
 @Column(precision=12,scale=2) public BigDecimal dailyCap;
 public PricingPolicy policy(){return new PricingPolicy(rate,billingUnit,graceMinutes,dailyCap);}
}
