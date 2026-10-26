package lk.sahan.parking.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Message receipts outlive the notification that originally announced the message. */
@Entity
@Table(name="conversation_post_reads", uniqueConstraints=@UniqueConstraint(columnNames={"post_id","reader_key"}),
        indexes=@Index(columnList="reader_key,post_id"))
public class ConversationPostRead {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @ManyToOne(optional=false) @JoinColumn(name="post_id") public ConversationPost post;
    @Column(name="reader_key",nullable=false,length=160) public String readerKey;
    @Column(nullable=false) public Instant readAt;
    public ConversationPostRead() {}
    public ConversationPostRead(ConversationPost post,String readerKey,Instant readAt) {
        this.post=post; this.readerKey=readerKey; this.readAt=readAt;
    }
}
