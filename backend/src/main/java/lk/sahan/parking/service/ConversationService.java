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
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service @Transactional(readOnly=true)
public class ConversationService {
    private final ConversationRepository threads; private final ConversationPostRepository posts; private final ConversationReadRepository reads;
    private final UserIdentity users; private final BranchService branches; private final EventLog events; private final SessionRepository sessions; private final PaymentRepository payments; private final Clock clock;
    private final NotificationService notifications;
    private final ConversationReceipts receipts;
    public ConversationService(ConversationRepository threads,ConversationPostRepository posts,ConversationReadRepository reads,UserIdentity users,BranchService branches,EventLog events,SessionRepository sessions,PaymentRepository payments,Clock clock,NotificationService notifications,ConversationReceipts receipts) {
        this.threads=threads; this.posts=posts; this.reads=reads; this.users=users; this.branches=branches; this.events=events; this.sessions=sessions; this.payments=payments; this.clock=clock;
        this.notifications=notifications;
        this.receipts=receipts;
    }
    public record Send(String requestKey,String subject,String category,String priority,String body,String cashier,Long ticketId,Long paymentId) {}
    public record Reply(String requestKey,String body) {}
    public record Resolve(String requestKey,boolean resolved) {}
    public record View(Long id,String subject,String category,String priority,String cashier,Long branchId,String branch,String status,boolean unread,Instant createdAt,Instant updatedAt,Long ticketId,Long paymentId,Long lastPostId) {}
    public record Post(Long id,String sender,boolean admin,String kind,String body,Instant sentAt,boolean incoming,Instant readAt,Instant recipientReadAt,String readState) {}
    public record Message(Long id,Long conversationId,String subject,String preview,String body,String sender,String recipient,String priority,Instant sentAt,boolean incoming,Instant readAt,Instant recipientReadAt,String readState,Long branchId,String branch,String cashier,String conversationStatus) {}
    public record PageView<T>(List<T> rows,long total,int page,int pages) {}
    public record Details(View conversation,PageView<Post> messages) {}

    /** A projection of posts, never another copy or another conversation per reply. */
    public PageView<Message> messages(Authentication auth,String folder,Long branchId,String cashier,String priority,String status,Boolean unread,String search,int page,int size) {
        paging(page,size);
        if(!Set.of("received","sent").contains(folder)) throw bad("Choose Received or Sent messages");
        boolean admin=users.admin(auth),incoming=folder.equals("received"); String key=users.key(auth); Long ownBranch=admin?null:users.branch(auth);
        if(status!=null&&!Set.of("OPEN","RESOLVED").contains(status)) throw bad("Choose Open or Resolved");
        if(priority!=null&&!Set.of("NORMAL","IMPORTANT","URGENT").contains(priority)) throw bad("Choose a priority");
        if(search!=null&&search.length()>120) throw bad("Search is too long");
        Specification<ConversationPost> filter=(root,query,cb)->{
            var thread=root.join("conversation"); List<jakarta.persistence.criteria.Predicate> p=new ArrayList<>();
            p.add(root.get("kind").in("MESSAGE","REPLY"));
            if(!admin) { p.add(cb.equal(thread.get("ownerKey"),key)); p.add(cb.equal(thread.get("branchId"),ownBranch)); }
            if(incoming) p.add(cb.equal(root.get("admin"),!admin));
            else p.add(cb.equal(root.get("senderKey"),key));
            if(branchId!=null) p.add(cb.equal(thread.get("branchId"),branchId));
            if(cashier!=null&&!cashier.isBlank()) p.add(cb.equal(thread.get("cashier"),cashier));
            if(priority!=null) p.add(cb.equal(thread.get("priority"),priority));
            if(status!=null) p.add(cb.equal(thread.get("resolved"),status.equals("RESOLVED")));
            if(search!=null&&!search.isBlank()) {
                String pattern="%"+search.toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
                p.add(cb.or(cb.like(cb.lower(thread.get("subject")),pattern,'\\'),cb.like(cb.lower(root.get("body")),pattern,'\\'),cb.like(cb.lower(root.get("sender")),pattern,'\\')));
            }
            if(incoming&&unread!=null) {
                var read=query.subquery(Long.class); var state=read.from(ConversationPostRead.class);
                read.select(state.get("id")).where(cb.equal(state.get("post").get("id"),root.get("id")),cb.equal(state.get("readerKey"),key));
                p.add(unread?cb.not(cb.exists(read)):cb.exists(read));
            }
            return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var result=posts.findAll(filter,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"sentAt","id")));
        return new PageView<>(result.stream().map(p->{
            Conversation t=p.conversation; Post state=post(p,auth);
            return new Message(p.id,t.id,t.subject,p.body.substring(0,Math.min(180,p.body.length())),p.body,p.sender,p.admin?t.cashier:"Administrators",label(t.priority),p.sentAt,state.incoming(),state.readAt(),state.recipientReadAt(),state.readState(),t.branchId,t.branch,t.cashier,t.resolved?"Resolved":"Open");
        }).toList(),result.getTotalElements(),page,result.getTotalPages());
    }

    @Transactional public View send(Send request,Authentication auth) {
        if(request==null) throw bad("Message is required");
        String subject=text(request.subject(),120),body=text(request.body(),4000),category=request.category(),priority=request.priority();
        if(!Set.of("GENERAL","PARKING_ISSUE","PAYMENT_ISSUE","TECHNICAL_ISSUE","OTHER").contains(category==null?"":category)) throw bad("Choose a message category");
        if(!Set.of("NORMAL","IMPORTANT","URGENT").contains(priority==null?"":priority)) throw bad("Choose a priority");
        var owner=users.cashier(users.admin(auth)?text(request.cashier(),128):auth.getName());
        branches.requireAvailable(branches.lock(owner.branch().getId()));
        owner=users.cashier(owner.username());
        String fingerprint=fingerprint("NEW",subject,body,category,priority,owner.key(),request.ticketId(),request.paymentId());
        ConversationPost old=retry(request.requestKey(),auth,fingerprint); if(old!=null) return view(access(old.conversation.id,auth),auth);
        linked(request.ticketId(),request.paymentId(),owner.branch().getId());
        Conversation thread=new Conversation(); thread.ownerKey=owner.key(); thread.cashier=owner.username(); thread.branchId=owner.branch().getId(); thread.branch=branches.label(owner.branch()); thread.subject=subject; thread.category=category; thread.priority=priority; thread.ticketId=request.ticketId(); thread.paymentId=request.paymentId(); thread.createdAt=clock.instant(); thread.updatedAt=thread.createdAt;
        threads.saveAndFlush(thread); append(thread,request.requestKey(),fingerprint,body,"MESSAGE",auth);
        return view(thread,auth);
    }
    public PageView<View> list(Authentication auth,Long branchId,String cashier,String priority,String status,Boolean unread,String search,int page,int size) {
        paging(page,size); boolean admin=users.admin(auth); String key=users.key(auth); Long ownBranch=admin?null:users.branch(auth);
        Specification<Conversation> filter=(root,query,cb)-> {
            List<jakarta.persistence.criteria.Predicate> p=new ArrayList<>();
            if(!admin) { p.add(cb.equal(root.get("ownerKey"),key)); p.add(cb.equal(root.get("branchId"),ownBranch)); }
            if(branchId!=null) p.add(cb.equal(root.get("branchId"),branchId));
            if(cashier!=null&&!cashier.isBlank()) p.add(cb.equal(root.get("cashier"),cashier));
            if(priority!=null&&!priority.isBlank()) p.add(cb.equal(root.get("priority"),priority));
            if(status!=null&&!status.isBlank()) {
                if(status.equals("RESOLVED")) p.add(cb.isTrue(root.get("resolved")));
                else if(status.equals("OPEN")) p.add(cb.isFalse(root.get("resolved")));
                else throw bad("Choose Open or Resolved");
            }
            if(search!=null&&!search.isBlank()) { if(search.length()>120) throw bad("Search is too long"); p.add(cb.like(cb.lower(root.get("subject")),"%"+search.toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%",'\\')); }
            if(unread!=null) {
                var incoming=query.subquery(Long.class);var n=incoming.from(ConversationPost.class);
                var read=incoming.subquery(Long.class);var state=read.from(ConversationPostRead.class);read.select(state.get("id")).where(cb.equal(state.get("post").get("id"),n.get("id")),cb.equal(state.get("readerKey"),key));
                incoming.select(n.get("id")).where(cb.equal(n.get("conversation").get("id"),root.get("id")),cb.equal(n.get("admin"),!admin),cb.not(cb.exists(read)));
                p.add(unread?cb.exists(incoming):cb.not(cb.exists(incoming)));
            }
            return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var result=threads.findAll(filter,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"updatedAt","id")));
        return new PageView<>(result.stream().map(t->view(t,auth)).toList(),result.getTotalElements(),page,result.getTotalPages());
    }
    public Details details(Long id,Authentication auth,int page,int size) {
        return details(id,auth,page,size,null);
    }
    public Details details(Long id,Authentication auth,int page,int size,Long selectedPostId) {
        paging(page,size); Conversation thread=access(id,auth);
        if(selectedPostId!=null) {
            posts.findById(selectedPostId).filter(p->p.conversation.id.equals(id)).orElseThrow(()->missing());
            page=(int)(posts.countByConversationIdAndIdLessThan(id,selectedPostId)/size);
        }
        var result=posts.findByConversationId(id,PageRequest.of(page,size,Sort.by("id")));
        return new Details(view(thread,auth),new PageView<>(result.stream().map(p->post(p,auth)).toList(),result.getTotalElements(),page,result.getTotalPages()));
    }
    private Post post(ConversationPost p,Authentication auth) {
        boolean incoming=receipts.incoming(p,auth); Instant own= incoming?receipts.readAt(p,auth):null,recipient=incoming?null:receipts.recipientReadAt(p);
        return new Post(p.id,p.sender,p.admin,p.kind,p.body,p.sentAt,incoming,own,recipient,incoming?(own==null?"Unread":"Read"):(recipient==null?"Sent":"Read by recipient"));
    }
    @Transactional public View open(Long id,Long throughPostId,Authentication auth) {
        return open(id,throughPostId,throughPostId==null?null:List.of(throughPostId),auth);
    }
    @Transactional public View open(Long id,Long throughPostId,List<Long> displayedPostIds,Authentication auth) {
        access(id,auth); Conversation thread=threads.lock(id).orElseThrow(()->missing());
        if(throughPostId==null || !posts.findById(throughPostId).filter(p->p.conversation.id.equals(id)).isPresent()) throw bad("Choose a displayed message to mark read");
        List<Long> displayed=displayedPostIds==null?List.of(throughPostId):displayedPostIds;
        if(displayed.isEmpty()||displayed.size()>100||displayed.stream().anyMatch(Objects::isNull)||!displayed.contains(throughPostId)) throw bad("Choose 1–100 displayed messages");
        for(Long postId:displayed) if(postId>throughPostId||posts.findById(postId).filter(p->p.conversation.id.equals(id)).isEmpty()) throw bad("Displayed message is not in this conversation");
        displayed.stream().distinct().map(postId->posts.findById(postId).orElseThrow()).forEach(p->receipts.mark(p,auth));
        mark(thread,auth,throughPostId);
        if(users.admin(auth)&&thread.firstAdminReadAt==null) thread.firstAdminReadAt=clock.instant();
        notifications.readConversationPosts(id,displayed,auth);
        return view(thread,auth);
    }
    @Transactional public View reply(Long id,Reply request,Authentication auth) {
        Conversation thread=access(id,auth); branches.requireAvailable(branches.lock(thread.branchId)); users.cashier(thread.cashier);
        thread=threads.lock(id).orElseThrow(()->missing());
        String body=text(request==null?null:request.body(),4000),fingerprint=fingerprint("REPLY",id,body);
        var old=retry(request.requestKey(),auth,fingerprint); if(old!=null) return view(thread,auth);
        if(thread.resolved) throw new ResponseStatusException(HttpStatus.CONFLICT,"Conversation is resolved; ask an administrator to reopen it");
        append(thread,request.requestKey(),fingerprint,body,"REPLY",auth); return view(thread,auth);
    }
    @Transactional public View resolve(Long id,Resolve request,Authentication auth) {
        if(!users.admin(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(request==null) throw bad("Resolution is required");
        Conversation thread=threads.lock(id).orElseThrow(()->missing());
        String fingerprint=fingerprint("RESOLVE",id,request.resolved());
        if(retry(request.requestKey(),auth,fingerprint)!=null) return view(thread,auth);
        if(thread.resolved==request.resolved()) return view(thread,auth);
        thread.resolved=request.resolved(); append(thread,request.requestKey(),fingerprint,thread.resolved?"Conversation resolved":"Conversation reopened","STATUS",auth); return view(thread,auth);
    }
    private void append(Conversation thread,String requestKey,String fingerprint,String body,String kind,Authentication auth) {
        ConversationPost post=new ConversationPost(); post.conversation=thread; post.requestKey=uuid(requestKey); post.fingerprint=fingerprint; post.senderKey=users.key(auth); post.sender=auth.getName(); post.admin=users.admin(auth); post.kind=kind; post.body=body; post.sentAt=clock.instant(); posts.saveAndFlush(post);
        thread.lastPostId=post.id; thread.updatedAt=post.sentAt; mark(thread,auth,post.id);
        events.conversation(thread.id,post.id,thread.ownerKey,branches.require(thread.branchId),thread.cashier,auth.getName(),post.admin,thread.subject,body,label(thread.priority));
    }
    private void mark(Conversation thread,Authentication auth,Long through) {
        String key=users.key(auth); ConversationRead state=reads.findByConversationIdAndReaderKey(thread.id,key).orElseGet(ConversationRead::new);
        state.conversation=thread; state.readerKey=key; state.lastPostId=state.lastPostId==null?through:Math.max(state.lastPostId,through); state.readAt=clock.instant(); reads.save(state);
    }
    private ConversationPost retry(String key,Authentication auth,String fingerprint) {
        var old=posts.findByRequestKey(uuid(key)).orElse(null);
        if(old!=null&&(!old.senderKey.equals(users.key(auth))||!old.fingerprint.equals(fingerprint))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Request key belongs to another submission");
        return old;
    }
    private Conversation access(Long id,Authentication auth) {
        Conversation thread=threads.findById(id).orElseThrow(()->missing());
        if(!users.admin(auth)&&(!thread.ownerKey.equals(users.key(auth))||!thread.branchId.equals(users.branch(auth)))) throw missing();
        return thread;
    }
    private View view(Conversation t,Authentication auth) {
        boolean unread=notifications.hasUnreadConversation(t.id,auth);
        return new View(t.id,t.subject,label(t.category),label(t.priority),t.cashier,t.branchId,t.branch,t.resolved?"Resolved":"Open",unread,t.createdAt,t.updatedAt,t.ticketId,t.paymentId,t.lastPostId);
    }
    private void linked(Long ticket,Long payment,Long branch) {
        if(ticket!=null) sessions.findById(ticket).filter(s->s.getSlot().getBranch().getId().equals(branch)).orElseThrow(()->bad("Ticket is not in your branch"));
        if(payment!=null) { var p=payments.findById(payment).filter(v->v.getSession().getSlot().getBranch().getId().equals(branch)).orElseThrow(()->bad("Payment is not in your branch")); if(ticket!=null&&!p.getSession().getId().equals(ticket)) throw bad("Ticket and payment do not match"); }
    }
    private String text(String s,int max) { if(s==null||s.isBlank()||s.trim().length()>max) throw bad("Required text must contain 1–"+max+" characters"); return s.trim(); }
    private String uuid(String s) { try { return UUID.fromString(s).toString(); } catch(Exception ex) { throw bad("A valid request key is required"); } }
    private String fingerprint(Object... values) { try { StringBuilder s=new StringBuilder(); for(Object v:values) { String t=String.valueOf(v); s.append(t.length()).append(':').append(t); } return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toString().getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    public static String label(String s) { String text=s.toLowerCase(Locale.ROOT).replace('_',' '); return Character.toUpperCase(text.charAt(0))+text.substring(1); }
    private void paging(int page,int size) { if(page<0||size<1||size>100) throw bad("Invalid page (size must be 1–100)"); }
    private ResponseStatusException bad(String text) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,text); }
    private ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Conversation not found"); }
}
