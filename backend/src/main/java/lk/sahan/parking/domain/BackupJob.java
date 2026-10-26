package lk.sahan.parking.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="backup_jobs")
public class BackupJob {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Column(nullable=false,unique=true,length=36) public String jobKey;
 @Column(nullable=false,length=24) public String kind;
 @Column(nullable=false,length=24) public String status;
 @Column(nullable=false,length=128) public String actor;
 @Column(length=16) public String engine;
 @Column(nullable=false) public Instant createdAt;
 public Instant completedAt;
 public Long sizeBytes;
 @Column(length=100) public String fileName;
 @Column(length=500) public String failure;
 public Long sourceJobId;
 @Column(length=100) public String restorePlan;
}
