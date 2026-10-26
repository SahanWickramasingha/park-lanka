package lk.sahan.parking.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
@Table(name="notice_reads", uniqueConstraints=@UniqueConstraint(columnNames={"notice_id","reader_key"}), indexes=@Index(columnList="reader_key,notice_id"))
public class NoticeRead {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @ManyToOne(optional=false) public Notice notice;
    @Column(name="reader_key",nullable=false,length=160) public String readerKey;
    @Column(nullable=false) public Instant readAt;
    protected NoticeRead() {}
    public NoticeRead(Notice notice, String readerKey, Instant time) { this.notice=notice; this.readerKey=readerKey; this.readAt=time; }
}
