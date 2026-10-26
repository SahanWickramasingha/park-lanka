package lk.sahan.parking.service;

import lk.sahan.parking.domain.*;
import lk.sahan.parking.repo.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Called inside the caller's transaction with the conversation locked for writes. */
@Service
public class ConversationReceipts {
    private final ConversationPostReadRepository reads;
    private final UserIdentity users;
    private final Clock clock;
    public ConversationReceipts(ConversationPostReadRepository reads,UserIdentity users,Clock clock) {
        this.reads=reads; this.users=users; this.clock=clock;
    }
    public boolean incoming(ConversationPost post,Authentication auth) { return post.admin!=users.admin(auth); }
    public Instant readAt(ConversationPost post,Authentication auth) {
        return reads.findByPostIdAndReaderKey(post.id,users.key(auth)).map(r->r.readAt).orElse(null);
    }
    public Instant recipientReadAt(ConversationPost post) {
        return reads.findByPostIdOrderByReadAtAsc(post.id).stream()
                .filter(r->post.admin?r.readerKey.equals(post.conversation.ownerKey):r.readerKey.startsWith("admin:"))
                .map(r->r.readAt).findFirst().orElse(null);
    }
    public void mark(ConversationPost post,Authentication auth) {
        if(incoming(post,auth)) preserve(post,users.key(auth),clock.instant());
    }
    public void preserve(ConversationPost post,String key,Instant at) {
        if(reads.findByPostIdAndReaderKey(post.id,key).isEmpty()) reads.saveAndFlush(new ConversationPostRead(post,key,at));
    }
}
