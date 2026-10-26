package lk.sahan.parking.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "broadcast_recipients", uniqueConstraints = @UniqueConstraint(columnNames = {"broadcast_id", "recipient_key"}))
public class BroadcastRecipient {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) private Broadcast broadcast;
    @Column(name = "recipient_key", nullable = false, length = 160) private String recipientKey;
    @Column(nullable = false, length = 128) private String username;
    @Column(nullable = false) private Long branchId;
    @Column(nullable = false, length = 100) private String branch;
    private Instant readAt;
    protected BroadcastRecipient() {}
    public BroadcastRecipient(Broadcast broadcast, String key, String username, Long branchId, String branch) {
        this.broadcast = broadcast; this.recipientKey = key; this.username = username; this.branchId = branchId; this.branch = branch;
    }
    public Broadcast getBroadcast() { return broadcast; }
    public String getUsername() { return username; }
    public Long getBranchId() { return branchId; }
    public String getBranch() { return branch; }
    public Instant getReadAt() { return readAt; }
    public void markRead(Instant now) { if (readAt == null) readAt = now; }
}
