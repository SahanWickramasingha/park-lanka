package lk.sahan.parking.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Durable event log: committed events are delivered by querying, with no fallible fan-out on payment threads. */
@Entity
@Table(name="notices", indexes={@Index(columnList="audience,branchId,id"), @Index(columnList="targetKey,id"), @Index(columnList="relatedType,relatedId,relatedPostId")},
       uniqueConstraints=@UniqueConstraint(columnNames="event_key"))
public class Notice {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="event_key", nullable=false, length=160) public String eventKey;
    @Column(nullable=false, length=24) public String audience;
    @Column(length=160) public String targetKey;
    @Column(nullable=false, length=60) public String category;
    @Column(nullable=false, length=16) public String priority;
    @Column(nullable=false, length=180) public String title;
    @Column(nullable=false, length=4000) public String description;
    @Column(nullable=false, length=128) public String sender;
    public Long branchId;
    @Column(length=100) public String branch;
    @Column(length=128) public String cashier;
    @Column(length=24) public String relatedType;
    public Long relatedId;
    // Nullable for older events; startup links legacy conversation events without replacing records.
    public Long relatedPostId;
    @Column(precision=19,scale=2) public BigDecimal amount;
    @Column(nullable=false) public Instant createdAt;
    @ManyToOne(fetch=FetchType.LAZY) public Broadcast broadcast;
    protected Notice() {}
    public Notice(String key, String audience, String targetKey, String category, String priority, String title,
                  String body, String sender, Long branchId, String branch, String cashier, String type, Long relatedId,
                  BigDecimal amount, Instant time, Broadcast broadcast) {
        this.eventKey=key; this.audience=audience; this.targetKey=targetKey; this.category=category; this.priority=priority;
        this.title=title; this.description=body; this.sender=sender; this.branchId=branchId; this.branch=branch;
        this.cashier=cashier; this.relatedType=type; this.relatedId=relatedId; this.amount=amount; this.createdAt=time; this.broadcast=broadcast;
    }
}
