package lk.sahan.parking.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="audit_records")
public class AuditRecord {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Column(nullable=false,length=128,updatable=false) public String actor;
 @Column(nullable=false,length=100,updatable=false) public String action;
 @Column(nullable=false,length=160,updatable=false) public String target;
 @Column(updatable=false) public Long branchId;
 @Column(nullable=false,updatable=false) public Instant occurredAt;
 @Column(length=500,updatable=false) public String reason;
 @Column(length=12000,updatable=false) public String beforeDetails;
 @Column(length=12000,updatable=false) public String afterDetails;
}
