package lk.sahan.parking.service;

import jakarta.persistence.*;
import lk.sahan.parking.domain.*;
import lk.sahan.parking.repo.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Links old one-event-per-post records and carries forward read positions without replacing history. */
@Service
public class ConversationNoticeMigration {
    private final NoticeRepository notices; private final NoticeReadRepository noticeReads;
    private final ConversationPostRepository posts; private final ConversationReadRepository threadReads;
    private final ConversationReceipts receipts;
    @PersistenceContext private EntityManager em;
    public ConversationNoticeMigration(NoticeRepository notices,NoticeReadRepository noticeReads,ConversationPostRepository posts,ConversationReadRepository threadReads,ConversationReceipts receipts) {
        this.notices=notices;this.noticeReads=noticeReads;this.posts=posts;this.threadReads=threadReads;
        this.receipts=receipts;
    }
    @Bean CommandLineRunner linkConversationNotices(ConversationNoticeMigration migration) {return args->migration.migrate();}
    @Transactional public void migrate() {
        long cursor=0;
        while(true) {
            long after=cursor;
            var batch=notices.findAll((r,q,cb)->cb.and(cb.equal(r.get("relatedType"),"CONVERSATION"),cb.isNull(r.get("relatedPostId")),cb.greaterThan(r.get("id"),after)),PageRequest.of(0,100,Sort.by("id"))).getContent();
            if(batch.isEmpty())break;
            for(Notice n:batch) {
                cursor=n.id;notices.lock(n.id);em.refresh(n,LockModeType.PESSIMISTIC_WRITE);if(n.relatedPostId!=null)continue;
                long previous=notices.findAll((r,q,cb)->cb.and(cb.equal(r.get("relatedType"),"CONVERSATION"),cb.equal(r.get("relatedId"),n.relatedId),cb.lessThan(r.get("id"),n.id),cb.isNotNull(r.get("relatedPostId"))),PageRequest.of(0,1,Sort.by(Sort.Direction.DESC,"id"))).stream().mapToLong(v->v.relatedPostId).findFirst().orElse(0);
                var match=posts.findByConversationIdAndIdGreaterThanAndSenderAndBodyOrderByIdAsc(n.relatedId,previous,n.sender,n.description,PageRequest.of(0,1));
                if(match.isEmpty())continue;
                n.relatedPostId=match.getContent().get(0).id;
                for(ConversationRead state:threadReads.findByConversationId(n.relatedId)) {
                    boolean eligible=n.audience.equals("ADMIN")?state.readerKey.startsWith("admin:"):state.readerKey.equals(n.targetKey);
                    if(eligible&&state.lastPostId>=n.relatedPostId&&noticeReads.findByNoticeIdAndReaderKey(n.id,state.readerKey).isEmpty())noticeReads.saveAndFlush(new NoticeRead(n,state.readerKey,state.readAt));
                }
            }
        }
        // Preserve exact legacy receipts, including explicit bell reads. Never infer new reads from a cursor.
        cursor=0;
        while(true) {
            long after=cursor;
            var batch=notices.findAll((r,q,cb)->cb.and(cb.equal(r.get("relatedType"),"CONVERSATION"),cb.isNotNull(r.get("relatedPostId")),cb.greaterThan(r.get("id"),after)),PageRequest.of(0,100,Sort.by("id"))).getContent();
            if(batch.isEmpty()) break;
            for(Notice n:batch) {
                cursor=n.id;
                posts.findById(n.relatedPostId).ifPresent(post->{
                    for(NoticeRead state:noticeReads.findByNoticeId(n.id)) receipts.preserve(post,state.readerKey,state.readAt);
                });
            }
        }
    }
}
