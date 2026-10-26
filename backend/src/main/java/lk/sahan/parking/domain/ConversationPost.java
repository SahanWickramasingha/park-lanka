package lk.sahan.parking.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
@Table(name="conversation_posts",uniqueConstraints=@UniqueConstraint(columnNames="request_key"),indexes={@Index(columnList="conversation_id,id"),@Index(columnList="senderKey,sentAt,id"),@Index(columnList="admin,sentAt,id")})
public class ConversationPost {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @ManyToOne(optional=false) public Conversation conversation;
    @Column(name="request_key",nullable=false,length=36) public String requestKey;
    @Column(nullable=false,length=64) public String fingerprint;
    @Column(nullable=false,length=160) public String senderKey;
    @Column(nullable=false,length=128) public String sender;
    @Column(nullable=false) public boolean admin;
    @Column(nullable=false,length=24) public String kind;
    @Column(nullable=false,length=4000) public String body;
    @Column(nullable=false) public Instant sentAt;
    public ConversationPost() {}
}
