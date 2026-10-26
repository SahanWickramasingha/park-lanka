package lk.sahan.parking.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
@Table(name="conversations",indexes={@Index(columnList="ownerKey,updatedAt"),@Index(columnList="branchId,updatedAt"),@Index(columnList="priority,resolved")})
public class Conversation {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable=false,length=160) public String ownerKey;
    @Column(nullable=false,length=128) public String cashier;
    @Column(nullable=false) public Long branchId;
    @Column(nullable=false,length=100) public String branch;
    @Column(nullable=false,length=120) public String subject;
    @Column(nullable=false,length=24) public String category;
    @Column(nullable=false,length=16) public String priority;
    public Long ticketId, paymentId;
    @Column(nullable=false) public Instant createdAt,updatedAt;
    public Instant firstAdminReadAt;
    @Column(nullable=false) public boolean resolved;
    public Long lastPostId;
    public Conversation() {}
}
