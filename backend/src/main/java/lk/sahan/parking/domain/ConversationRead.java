package lk.sahan.parking.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
@Table(name="conversation_reads",uniqueConstraints=@UniqueConstraint(columnNames={"conversation_id","reader_key"}),indexes=@Index(columnList="reader_key,conversation_id"))
public class ConversationRead {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @ManyToOne(optional=false) public Conversation conversation;
    @Column(name="reader_key",nullable=false,length=160) public String readerKey;
    @Column(nullable=false) public Long lastPostId;
    @Column(nullable=false) public Instant readAt;
    public ConversationRead() {}
}
