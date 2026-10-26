package lk.sahan.parking.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "broadcasts", uniqueConstraints = @UniqueConstraint(columnNames = "request_key"))
public class Broadcast {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "request_key", nullable = false, length = 36) private String requestKey;
    @Column(nullable = false, length = 120) private String title;
    @Column(nullable = false, length = 4000) private String body;
    @Column(nullable = false, length = 16) private String priority;
    @Column(nullable = false, length = 128) private String sender;
    @Column(nullable = false) private Instant sentAt;
    private Instant expiresAt;
    private Instant revokedAt;
    @Column(nullable = false) private boolean allBranches;
    @Column(nullable = false, length = 64000) private String audience;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "broadcast_branches", joinColumns = @JoinColumn(name = "broadcast_id"), uniqueConstraints = @UniqueConstraint(columnNames = {"broadcast_id", "branch_id"}))
    @Column(name = "branch_id", nullable = false) private Set<Long> branchIds = new HashSet<>();
    protected Broadcast() {}
    public Broadcast(String key, String title, String body, String priority, String sender, Instant sentAt,
                     Instant expiresAt, boolean all, Set<Long> ids, String audience) {
        this.requestKey = key; this.title = title; this.body = body; this.priority = priority; this.sender = sender;
        this.sentAt = sentAt; this.expiresAt = expiresAt; this.allBranches = all; this.branchIds.addAll(ids); this.audience = audience;
    }
    public Long getId() { return id; }
    public String getRequestKey() { return requestKey; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getPriority() { return priority; }
    public String getSender() { return sender; }
    public Instant getSentAt() { return sentAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public boolean isAllBranches() { return allBranches; }
    public Set<Long> getBranchIds() { return Collections.unmodifiableSet(branchIds); }
    public String getAudience() { return audience; }
    public void revoke(Instant now) { if (revokedAt == null) revokedAt = now; }
    public String status(Instant now) { return revokedAt != null ? "Revoked" : expiresAt != null && !expiresAt.isAfter(now) ? "Expired" : "Active"; }
}
