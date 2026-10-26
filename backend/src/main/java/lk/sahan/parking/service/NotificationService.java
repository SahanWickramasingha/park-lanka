package lk.sahan.parking.service;

import lk.sahan.parking.domain.*;
import lk.sahan.parking.repo.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;

@Service @Transactional(readOnly=true)
public class NotificationService {
    private final NoticeRepository notices; private final NoticeReadRepository reads; private final BroadcastRecipientRepository recipients; private final BroadcastRepository broadcasts;
    private final UserIdentity users; private final Clock clock; private final SessionRepository sessions; private final PaymentRepository payments; private final BranchRepository branches; private final CashierRepository cashiers;
    private final ConversationRepository conversations;private final ConversationReadRepository conversationReads;
    private final ConversationPostRepository posts; private final ConversationReceipts receipts;
    public NotificationService(NoticeRepository notices,NoticeReadRepository reads,BroadcastRecipientRepository recipients,BroadcastRepository broadcasts,UserIdentity users,Clock clock,SessionRepository sessions,PaymentRepository payments,BranchRepository branches,CashierRepository cashiers,ConversationRepository conversations,ConversationReadRepository conversationReads,ConversationPostRepository posts,ConversationReceipts receipts) {
        this.notices=notices; this.reads=reads; this.recipients=recipients; this.broadcasts=broadcasts; this.users=users; this.clock=clock; this.sessions=sessions; this.payments=payments; this.branches=branches; this.cashiers=cashiers;
        this.conversations=conversations;this.conversationReads=conversationReads;
        this.posts=posts;this.receipts=receipts;
    }
    public record View(Long id,String title,String description,String category,String priority,String sender,Long branchId,String branch,String cashier,String relatedType,Long relatedId,Long relatedPostId,BigDecimal amount,Instant createdAt,Instant expiresAt,Instant readAt) {}
    public record Result(List<View> rows,long unread,long total,int page,int pages,List<View> important,String highestPriority,long latestId,long latestUnreadId) {}
    public record Details(View notification,Map<String,Object> record) {}
    public Result list(Authentication auth,int page,int size) {
        return list(auth,page,size,null);
    }
    public Result list(Authentication auth,int page,int size,String section) {
        if(page<0||size<1||size>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page (size 1–100)");
        if(section!=null&&!section.equals("announcements")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown notification section");
        Specification<Notice> sectionFilter=(r,q,cb)->section==null?cb.conjunction():cb.notEqual(r.get("relatedType"),"CONVERSATION");
        var all=visible(auth,false).and(sectionFilter);var unread=visible(auth,true).and(sectionFilter);
        var result=notices.findAll(all,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"id")));
        var important=notices.findAll(unread.and((r,q,cb)->r.get("priority").in("Important","Urgent")),PageRequest.of(0,5,Sort.by(Sort.Direction.DESC,"priority","id"))).stream().map(n->view(n,auth)).toList();
        long count=notices.count(unread);
        String highest=important.isEmpty()?(count==0?"Neutral":"Normal"):important.get(0).priority();
        long latest=notices.findAll(all,PageRequest.of(0,1,Sort.by(Sort.Direction.DESC,"id"))).stream().mapToLong(n->n.id).findFirst().orElse(0);
        long latestUnread=notices.findAll(unread,PageRequest.of(0,1,Sort.by(Sort.Direction.DESC,"id"))).stream().mapToLong(n->n.id).findFirst().orElse(0);
        return new Result(result.stream().map(n->view(n,auth)).toList(),count,result.getTotalElements(),page,result.getTotalPages(),important,highest,latest,latestUnread);
    }
    public Details details(Long id,Authentication auth) {
        Notice n=accessible(id,auth); Map<String,Object> record=new LinkedHashMap<>();
        if(n.relatedType.equals("SESSION")) sessions.findById(n.relatedId).ifPresent(s->{ record.put("Ticket",s.getId()); record.put("Plate",s.getPlate()); record.put("Slot",s.getSlot().getCode()); record.put("Entry time",s.getEnteredAt()); record.put("Exit time",s.getExitedAt()); record.put("Entered by",s.getEnteredBy()); });
        if(n.relatedType.equals("PAYMENT")) payments.findById(n.relatedId).ifPresent(p->{ record.put("Payment",p.getId()); record.put("Ticket",p.getSession().getId()); record.put("Plate",p.getSession().getPlate()); record.put("Amount (LKR)",p.getAmount()); record.put("Method",p.getMethod()); record.put("Paid at",p.getPaidAt()); record.put("Cashier",p.getCashierUsername()); });
        if(n.relatedType.equals("BRANCH")) branches.findById(n.relatedId).ifPresent(b->{ record.put("Code",b.getCode()); record.put("Name",b.getName()); record.put("Status",b.getStatus()); });
        if(n.relatedType.equals("CASHIER")&&users.admin(auth)) cashiers.findById(n.relatedId).ifPresent(c->{ record.put("Username",c.getUsername()); record.put("Status",c.getStatus()); record.put("Branch",c.getBranch().getName()); });
        return new Details(view(n,auth),record);
    }
    @Transactional public View read(Long id,Authentication auth) {return read(id,auth,false);}
    @Transactional public View read(Long id,Authentication auth,boolean opened) {
        Notice n=accessible(id,auth);
        // Match the conversation -> notice lock order used when opening a thread page.
        if("CONVERSATION".equals(n.relatedType)&&n.relatedPostId!=null) {
            Conversation thread=conversations.lock(n.relatedId).orElseThrow(()->missing());String key=users.key(auth);
            ConversationRead state=conversationReads.findByConversationIdAndReaderKey(thread.id,key).orElseGet(ConversationRead::new);
            state.conversation=thread;state.readerKey=key;state.lastPostId=state.lastPostId==null?n.relatedPostId:Math.max(state.lastPostId,n.relatedPostId);state.readAt=clock.instant();conversationReads.save(state);
            if(users.admin(auth)&&thread.firstAdminReadAt==null)thread.firstAdminReadAt=clock.instant();
        }
        notices.lock(id);mark(n,auth);return view(n,auth);
    }
    @Transactional public Result readAll(Authentication auth) {
        // Bound the operation to the snapshot observed at start; a later event remains unread.
        var first=notices.findAll(visible(auth,false),PageRequest.of(0,1,Sort.by(Sort.Direction.DESC,"id")));
        if(first.isEmpty()) return list(auth,0,20);
        Long ceiling=first.getContent().get(0).id; long cursor=0;
        // All receipt writers lock threads before notices. Use a stable order for a multi-thread read-all.
        notices.findAll(visible(auth,false).and((r,q,cb)->cb.and(cb.lessThanOrEqualTo(r.get("id"),ceiling),cb.equal(r.get("relatedType"),"CONVERSATION"))))
                .stream().map(n->n.relatedId).distinct().sorted().forEach(conversations::lock);
        while(true) {
            long after=cursor;
            Specification<Notice> range=visible(auth,false).and((r,q,cb)->cb.and(cb.greaterThan(r.get("id"),after),cb.lessThanOrEqualTo(r.get("id"),ceiling)));
            var batch=notices.findAll(range,PageRequest.of(0,100,Sort.by("id"))).getContent(); if(batch.isEmpty()) break;
            for(Notice n:batch) { notices.lock(n.id); mark(n,auth); cursor=n.id; }
        }
        return list(auth,0,20);
    }
    @Transactional public void readConversationPosts(Long conversationId,List<Long> displayed,Authentication auth) {
        var matching=visible(auth,false).and((r,q,cb)->cb.and(cb.equal(r.get("relatedType"),"CONVERSATION"),cb.equal(r.get("relatedId"),conversationId),r.get("relatedPostId").in(displayed)));
        for(Notice n:notices.findAll(matching,Sort.by("id"))) { notices.lock(n.id);mark(n,auth); }
    }
    public boolean hasUnreadConversation(Long conversationId,Authentication auth) {
        String key=users.key(auth);boolean admin=users.admin(auth);
        return posts.exists((r,q,cb)->{
            var read=q.subquery(Long.class);var state=read.from(ConversationPostRead.class);
            read.select(state.get("id")).where(cb.equal(state.get("post").get("id"),r.get("id")),cb.equal(state.get("readerKey"),key));
            return cb.and(cb.equal(r.get("conversation").get("id"),conversationId),cb.equal(r.get("admin"),!admin),cb.not(cb.exists(read)));
        });
    }
    private void mark(Notice n,Authentication auth) {
        String key=users.key(auth);
        if(n.broadcast!=null) {
            Broadcast b=broadcasts.lockById(n.broadcast.getId()).orElseThrow(()->missing());
            if(b.status(clock.instant()).equals("Active")) recipients.findByBroadcastIdAndRecipientKey(b.getId(),key).filter(r->r.getBranchId().equals(users.branch(auth))).ifPresent(r->r.markRead(clock.instant()));
        } else {
            if("CONVERSATION".equals(n.relatedType)&&n.relatedPostId!=null)
                posts.findById(n.relatedPostId).filter(p->p.conversation.id.equals(n.relatedId)).ifPresent(p->receipts.mark(p,auth));
            if(reads.findByNoticeIdAndReaderKey(n.id,key).isEmpty()) reads.saveAndFlush(new NoticeRead(n,key,clock.instant()));
        }
    }
    private Notice accessible(Long id,Authentication auth) { return notices.findOne(visible(auth,false).and((r,q,cb)->cb.equal(r.get("id"),id))).orElseThrow(()->missing()); }
    private View view(Notice n,Authentication auth) {
        Instant readAt=n.broadcast==null?reads.findByNoticeIdAndReaderKey(n.id,users.key(auth)).map(r->r.readAt).orElse(null):recipients.findByBroadcastIdAndRecipientKey(n.broadcast.getId(),users.key(auth)).map(BroadcastRecipient::getReadAt).orElse(null);
        return new View(n.id,n.title,n.description,n.category,n.priority,n.sender,n.branchId,n.branch,n.cashier,n.relatedType,n.relatedId,n.relatedPostId,n.amount,n.createdAt,n.broadcast==null?null:n.broadcast.getExpiresAt(),readAt);
    }
    private Specification<Notice> visible(Authentication auth,boolean unread) {
        boolean admin=users.admin(auth); String key=users.key(auth); Long branch=admin?null:users.branch(auth); Instant now=clock.instant();
        return (root,query,cb)-> {
            var normalRead=query.subquery(Long.class); var nr=normalRead.from(NoticeRead.class); normalRead.select(nr.get("id")).where(cb.equal(nr.get("notice").get("id"),root.get("id")),cb.equal(nr.get("readerKey"),key));
            var normal=admin?cb.equal(root.get("audience"),"ADMIN"):cb.or(cb.and(cb.equal(root.get("audience"),"USER"),cb.equal(root.get("targetKey"),key),cb.equal(root.get("branchId"),branch)),cb.and(cb.equal(root.get("audience"),"BRANCH"),cb.equal(root.get("branchId"),branch)));
            if(unread) normal=cb.and(normal,cb.not(cb.exists(normalRead)));
            if(admin) return normal;
            var recipient=query.subquery(Long.class); var r=recipient.from(BroadcastRecipient.class); var b=r.join("broadcast");
            List<jakarta.persistence.criteria.Predicate> predicates=new ArrayList<>();
            predicates.add(cb.equal(b.get("id"),root.get("broadcast").get("id"))); predicates.add(cb.equal(r.get("recipientKey"),key)); predicates.add(cb.equal(r.get("branchId"),branch)); predicates.add(cb.isNull(b.get("revokedAt"))); predicates.add(cb.or(cb.isNull(b.get("expiresAt")),cb.greaterThan(b.get("expiresAt"),now))); if(unread) predicates.add(cb.isNull(r.get("readAt")));
            recipient.select(r.get("id")).where(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
            return cb.or(normal,cb.and(cb.equal(root.get("audience"),"BROADCAST"),cb.exists(recipient)));
        };
    }
    private ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification not found"); }
}
