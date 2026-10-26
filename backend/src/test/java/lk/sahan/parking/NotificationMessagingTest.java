package lk.sahan.parking;

import com.fasterxml.jackson.databind.*;
import lk.sahan.parking.repo.*;
import lk.sahan.parking.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"test.cashier.username=testcashier","test.cashier.password=testpassword","PARKING_ADMIN_USER=testadmin","PARKING_ADMIN_PASSWORD=adminpassword","DATABASE_URL=jdbc:h2:mem:notificationtest;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop","PARKING_INITIAL_SLOTS=2"})
@AutoConfigureMockMvc
class NotificationMessagingTest {
    @Autowired MockMvc mvc; @Autowired BranchService branches; @Autowired CashierService cashiers; @Autowired SlotRepository slots; @Autowired NoticeRepository notices; @Autowired NoticeReadRepository reads;
    @Autowired ConversationService conversations; @Autowired ConversationPostRepository posts; @Autowired ParkingService parking; @Autowired PlatformTransactionManager transactions;
    @Autowired NotificationService notifications; @Autowired EventLog eventLog;
    @Autowired BroadcastNoticeMigration broadcastMigration;
    @Autowired ConversationNoticeMigration conversationMigration;
    final ObjectMapper json=new ObjectMapper();static final AtomicInteger sequence=new AtomicInteger();String cashier,other;long branch,otherBranch,account,slot;
    @BeforeEach void fixture(){int n=sequence.incrementAndGet();cashier="notifycashier"+n;other="notifyother"+n;branch=branches.create("Notify Branch "+n,2).getId();otherBranch=branches.create("Notify Other "+n,1).getId();account=cashiers.create(cashier,"cashierpassword123",branch).id();cashiers.create(other,"cashierpassword123",otherBranch);slot=slots.findByBranchId(branch).get(0).getId();}
    @Test void committedBusinessEventsAreDurableAndRolledBackFailuresLeaveNoNotification() throws Exception {
        long count=notices.count();long id=body(cashier(post("/api/sessions"),Map.of("plate","NOTICE-001","vehicleType","CAR","slotId",slot)).andExpect(status().isCreated())).path("id").asLong();assertEquals(count+1,notices.count());
        cashier(post("/api/sessions"),Map.of("plate","NOTICE-001","vehicleType","CAR","slotId",slot)).andExpect(status().isConflict());assertEquals(count+1,notices.count());
        long afterEntry=notices.count();new TransactionTemplate(transactions).executeWithoutResult(tx->{parking.enter(new ParkingService.EntryRequest("ROLLBACK-1","CAR",slots.findByBranchId(branch).get(1).getId()),branch,cashier);tx.setRollbackOnly();});assertEquals(afterEntry,notices.count());
        FinancialTestSupport.openShift(mvc,cashier,"cashierpassword123");
        JsonNode payment=body(cashier(post("/api/sessions/{id}/checkout",id),FinancialTestSupport.cash()).andExpect(status().isOk()));
        JsonNode admin=adminNotices();JsonNode event=find(admin,"relatedType","PAYMENT",payment.path("paymentId").asLong());assertNotNull(event);assertEquals(branch,event.path("branchId").asLong());assertEquals(cashier,event.path("cashier").asText());assertTrue(event.path("description").asText().contains("LKR"));assertFalse(admin.toString().contains("password"));
        assertFalse(fetch("/api/notifications",cashier).path("rows").toString().contains("NOTICE-001"));
        branches.enable(branch,false);JsonNode own=fetch("/api/notifications",cashier);assertTrue(own.path("rows").toString().contains("Branch stopped"));assertFalse(fetch("/api/notifications",other).path("rows").toString().contains("Branch stopped"));
        // Fetching again, including after a fresh authentication, neither loses events nor creates more.
        long stable=notices.count();assertNotNull(find(adminNotices(),"relatedType","PAYMENT",payment.path("paymentId").asLong()));assertEquals(stable,notices.count());
    }
    @Test void privateThreadsRepliesAndActualOpeningDriveIndependentPersistentReadState() throws Exception {
        JsonNode created=body(cashier(post("/api/conversations"),draft(UUID.randomUUID().toString())).andExpect(status().isOk()));long id=created.path("id").asLong();assertEquals("Open",created.path("status").asText());
        JsonNode listed=fetch("/api/conversations", "testadmin");assertEquals("Open",findId(listed,id).path("status").asText());assertTrue(findId(listed,id).path("unread").asBoolean());
        mvc.perform(get("/api/conversations/{id}",id).with(httpBasic(other,"cashierpassword123"))).andExpect(status().isNotFound());assertTrue(fetch("/api/conversations",other).path("rows").isEmpty());
        JsonNode details=fetch("/api/conversations/"+id,"testadmin");assertEquals("Open",details.path("conversation").path("status").asText());long postId=details.path("messages").path("rows").get(0).path("id").asLong();
        body(admin(put("/api/conversations/{id}/read",id),Map.of("throughPostId",postId)).andExpect(status().isOk()));assertEquals("Open",fetch("/api/conversations/"+id,cashier).path("conversation").path("status").asText());assertFalse(findId(fetch("/api/conversations","testadmin"),id).path("unread").asBoolean());
        var second=new UsernamePasswordAuthenticationToken("second-admin","",AuthorityUtils.createAuthorityList("ROLE_ADMIN"));assertTrue(conversations.list(second,null,null,null,null,null,null,0,30).rows().stream().filter(v->v.id()==id).findFirst().orElseThrow().unread());
        admin(post("/api/conversations/{id}/replies",id),Map.of("requestKey",UUID.randomUUID().toString(),"body","Please use the alternate lane.")).andExpect(status().isOk());
        JsonNode reply=find(fetch("/api/notifications",cashier),"relatedType","CONVERSATION",id);assertNotNull(reply);assertTrue(reply.path("readAt").isNull());assertNull(find(fetch("/api/notifications",other),"relatedType","CONVERSATION",id));assertTrue(findId(fetch("/api/conversations",cashier),id).path("unread").asBoolean());
        admin(put("/api/admin/conversations/{id}/resolution",id),Map.of("requestKey",UUID.randomUUID().toString(),"resolved",true)).andExpect(status().isOk());assertEquals("Resolved",fetch("/api/conversations/"+id,cashier).path("conversation").path("status").asText());
        cashier(post("/api/conversations/{id}/replies",id),Map.of("requestKey",UUID.randomUUID().toString(),"body","Late reply")).andExpect(status().isConflict());
        admin(put("/api/admin/conversations/{id}/resolution",id),Map.of("requestKey",UUID.randomUUID().toString(),"resolved",false)).andExpect(status().isOk());
        assertEquals(1,fetch("/api/conversations?branchId="+branch+"&cashier="+cashier+"&priority=IMPORTANT&status=OPEN&search=Help","testadmin").path("total").asInt());
    }
    @Test void readAllIsUserScopedAndBroadcastReceiptsAreSharedWithExistingInbox() throws Exception {
        branches.enable(branch,false);long ownId=fetch("/api/notifications",cashier).path("rows").get(0).path("id").asLong();long events=notices.count();
        cashier(put("/api/notifications/read-all"),Map.of()).andExpect(status().isOk());assertEquals(0,fetch("/api/notifications",cashier).path("unread").asInt());assertTrue(fetch("/api/notifications/"+ownId,cashier).path("notification").hasNonNull("readAt"));assertEquals(events,notices.count());
        assertTrue(adminNotices().path("unread").asInt()>0);admin(put("/api/notifications/read-all"),Map.of()).andExpect(status().isOk());assertEquals(0,adminNotices().path("unread").asInt());
        mvc.perform(get("/api/notifications/"+ownId).with(httpBasic(other,"cashierpassword123"))).andExpect(status().isNotFound());
        long broadcast=body(admin(post("/api/admin/broadcasts"),Map.of("requestKey",UUID.randomUUID().toString(),"title","Shared receipt","body","Broadcast notification","priority","URGENT","allBranches",false,"branchIds",List.of(branch))).andExpect(status().isOk())).path("id").asLong();
        JsonNode notice=find(fetch("/api/notifications",cashier),"relatedType","BROADCAST",broadcast);assertNotNull(notice);
        cashier(put("/api/notifications/{id}/read",notice.path("id").asLong()),Map.of()).andExpect(status().isOk());assertTrue(fetch("/api/messages",cashier).get(0).hasNonNull("readAt"));
        long receiptCount=reads.count();cashier(put("/api/notifications/{id}/read",notice.path("id").asLong()),Map.of()).andExpect(status().isOk());assertEquals(receiptCount,reads.count());
        admin(put("/api/admin/broadcasts/{id}/revoke",broadcast),Map.of()).andExpect(status().isOk());assertNull(find(fetch("/api/notifications",cashier),"relatedType","BROADCAST",broadcast));
    }
    @Test void duplicateSendsIncludingConcurrentRetriesCreateOneThreadPostAndNotification() throws Exception {
        String key=UUID.randomUUID().toString();Map<String,Object> payload=draft(key);long before=posts.count();long noticesBefore=notices.count();long id=body(cashier(post("/api/conversations"),payload).andExpect(status().isOk())).path("id").asLong();
        assertEquals(id,body(cashier(post("/api/conversations"),payload).andExpect(status().isOk())).path("id").asLong());assertEquals(before+1,posts.count());assertEquals(noticesBefore+1,notices.count());
        payload.put("body","changed");cashier(post("/api/conversations"),payload).andExpect(status().isConflict());
        var auth=new UsernamePasswordAuthenticationToken(cashier,"",AuthorityUtils.createAuthorityList("ROLE_CASHIER"));String concurrent=UUID.randomUUID().toString();var request=new ConversationService.Send(concurrent,"Concurrent","GENERAL","NORMAL","Retry me",null,null,null);ExecutorService pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->conversations.send(request,auth));var b=pool.submit(()->conversations.send(request,auth));assertEquals(a.get(10,TimeUnit.SECONDS).id(),b.get(10,TimeUnit.SECONDS).id());}finally{pool.shutdownNow();}
        String replyKey=UUID.randomUUID().toString();Map<String,Object> reply=Map.of("requestKey",replyKey,"body","One reply");admin(post("/api/conversations/{id}/replies",id),reply).andExpect(status().isOk());long after=posts.count();admin(post("/api/conversations/{id}/replies",id),reply).andExpect(status().isOk());assertEquals(after,posts.count());
    }
    @Test void linkedRecordsDirectEligibilityValidationAndRevokedAccountsAreEnforced() throws Exception {
        long foreign=parking.enter(new ParkingService.EntryRequest("FOREIGN-1","CAR",slots.findByBranchId(otherBranch).get(0).getId()),otherBranch,other).id();Map<String,Object> request=draft(UUID.randomUUID().toString());request.put("ticketId",foreign);cashier(post("/api/conversations"),request).andExpect(status().isBadRequest());request.remove("ticketId");request.put("cashier",other);long id=body(cashier(post("/api/conversations"),request).andExpect(status().isOk())).path("id").asLong();assertEquals(cashier,fetch("/api/conversations/"+id,"testadmin").path("conversation").path("cashier").asText());
        var direct=draft(UUID.randomUUID().toString());direct.put("cashier","testcashier");long boot=body(admin(post("/api/conversations"),direct).andExpect(status().isOk())).path("id").asLong();assertNotNull(find(fetch("/api/notifications","testcashier"),"relatedType","CONVERSATION",boot));
        cashier(put("/api/admin/conversations/{id}/resolution",id),Map.of("requestKey",UUID.randomUUID().toString(),"resolved",true)).andExpect(status().isForbidden());mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        cashiers.enable(account,false);mvc.perform(get("/api/notifications").with(httpBasic(cashier,"cashierpassword123"))).andExpect(status().isUnauthorized());var stopped=draft(UUID.randomUUID().toString());stopped.put("cashier",cashier);admin(post("/api/conversations"),stopped).andExpect(status().isConflict());cashiers.enable(account,true);
        branches.remove(branch);mvc.perform(get("/api/conversations").with(httpBasic(cashier,"cashierpassword123"))).andExpect(status().isUnauthorized());admin(post("/api/conversations"),stopped).andExpect(status().isConflict());assertEquals(id,fetch("/api/conversations/"+id,"testadmin").path("conversation").path("id").asLong());
        long otherAccount=cashiers.all().stream().filter(c->c.username().equals(other)).findFirst().orElseThrow().id();cashiers.remove(otherAccount);mvc.perform(get("/api/notifications").with(httpBasic(other,"cashierpassword123"))).andExpect(status().isUnauthorized());
    }
    @Test void notificationPaginationMarkAllAndAdministratorReadStateAreIndependent() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(tx->{for(int i=0;i<125;i++)eventLog.business("Branch","Notice "+i,"Branch operational notice",branches.require(branch),cashier,"BRANCH",branch,null,true);});
        JsonNode first=fetch("/api/notifications?size=20",cashier),last=fetch("/api/notifications?page=6&size=20",cashier);assertEquals(20,first.path("rows").size());assertEquals(5,last.path("rows").size());assertEquals(125,first.path("unread").asInt());
        assertEquals(5,fetch("/api/notifications?section=announcements&page=6&size=20",cashier).path("rows").size());
        cashier(put("/api/notifications/read-all"),Map.of()).andExpect(status().isOk());assertEquals(0,fetch("/api/notifications",cashier).path("unread").asInt());assertEquals(125,fetch("/api/notifications?size=20",cashier).path("total").asInt());
        var one=new UsernamePasswordAuthenticationToken("testadmin","",AuthorityUtils.createAuthorityList("ROLE_ADMIN"));var two=new UsernamePasswordAuthenticationToken("other-admin","",AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        long count=notifications.list(two,0,20).unread();assertTrue(count>0);notifications.readAll(one);assertEquals(0,notifications.list(one,0,20).unread());assertEquals(count,notifications.list(two,0,20).unread());assertEquals(count,notifications.readAll(two).total());
        assertEquals(0,notifications.list(two,0,20).unread());
    }
    @Test void olderBroadcastsGainCenterReferencesWithoutResettingReceiptsOrCreatingDuplicates() throws Exception {
        long id=body(admin(post("/api/admin/broadcasts"),Map.of("requestKey",UUID.randomUUID().toString(),"title","Pre-upgrade broadcast","body","Keep my receipt","priority","NORMAL","allBranches",false,"branchIds",List.of(branch))).andExpect(status().isOk())).path("id").asLong();
        cashier(put("/api/messages/{id}/read",id),Map.of()).andExpect(status().isOk());
        long noticeId=find(fetch("/api/notifications",cashier),"relatedType","BROADCAST",id).path("id").asLong();notices.deleteById(noticeId);
        assertNull(find(fetch("/api/notifications",cashier),"relatedType","BROADCAST",id));broadcastMigration.migrate();
        JsonNode restored=find(fetch("/api/notifications",cashier),"relatedType","BROADCAST",id);assertNotNull(restored);assertTrue(restored.hasNonNull("readAt"));long count=notices.count();broadcastMigration.migrate();assertEquals(count,notices.count());
    }
    @Test void highestUnreadPriorityIsGlobalAndAnnouncementCountsReuseBroadcastReceipts() throws Exception {
        Map<String,Long> ids=new LinkedHashMap<>();
        for(String priority:List.of("NORMAL","IMPORTANT","URGENT")){var payload=draft(UUID.randomUUID().toString());payload.put("cashier",cashier);payload.put("priority",priority);ids.put(priority,body(admin(post("/api/conversations"),payload).andExpect(status().isOk())).path("id").asLong());}
        JsonNode all=fetch("/api/notifications?page=2&size=1",cashier);assertEquals(3,all.path("unread").asInt());assertEquals("Urgent",all.path("highestPriority").asText());assertTrue(all.path("latestId").asLong()>0);assertEquals(0,fetch("/api/notifications?section=announcements",cashier).path("total").asInt());
        JsonNode urgent=find(fetch("/api/notifications",cashier),"relatedType","CONVERSATION",ids.get("URGENT"));cashier(put("/api/notifications/{id}/read",urgent.path("id").asLong()),Map.of()).andExpect(status().isOk());assertEquals("Important",fetch("/api/notifications",cashier).path("highestPriority").asText());
        long thread=ids.get("IMPORTANT"),post=fetch("/api/conversations/"+thread,cashier).path("messages").path("rows").get(0).path("id").asLong();cashier(put("/api/conversations/{id}/read",thread),Map.of("throughPostId",post,"displayedPostIds",List.of(post))).andExpect(status().isOk());
        assertEquals(1,fetch("/api/notifications",cashier).path("unread").asInt());assertEquals("Normal",fetch("/api/notifications",cashier).path("highestPriority").asText());
        long broadcast=body(admin(post("/api/admin/broadcasts"),Map.of("requestKey",UUID.randomUUID().toString(),"title","One announcement","body","Do not count twice","priority","NORMAL","allBranches",false,"branchIds",List.of(branch))).andExpect(status().isOk())).path("id").asLong();
        assertEquals(2,fetch("/api/notifications",cashier).path("unread").asInt());assertEquals(1,fetch("/api/notifications?section=announcements",cashier).path("total").asInt());cashier(put("/api/messages/{id}/read",broadcast),Map.of()).andExpect(status().isOk());assertEquals(1,fetch("/api/notifications",cashier).path("unread").asInt());
        cashier(put("/api/notifications/read-all"),Map.of()).andExpect(status().isOk());assertEquals("Neutral",fetch("/api/notifications",cashier).path("highestPriority").asText());
    }
    @Test void displayingOneConversationPostMarksOnlyItsOwnNotificationAndMigrationPreservesOlderReads() throws Exception {
        long id=body(cashier(post("/api/conversations"),draft(UUID.randomUUID().toString())).andExpect(status().isOk())).path("id").asLong();
        long incoming=find(adminNotices(),"relatedType","CONVERSATION",id).path("id").asLong();admin(put("/api/notifications/{id}/read?opened=true",incoming),Map.of()).andExpect(status().isOk());assertEquals("Open",fetch("/api/conversations/"+id,cashier).path("conversation").path("status").asText());
        for(String message:List.of("First response","Second response"))admin(post("/api/conversations/{id}/replies",id),Map.of("requestKey",UUID.randomUUID().toString(),"body",message)).andExpect(status().isOk());
        JsonNode rows=fetch("/api/conversations/"+id,cashier).path("messages").path("rows");long displayed=rows.get(1).path("id").asLong();
        cashier(put("/api/conversations/{id}/read",id),Map.of("throughPostId",displayed,"displayedPostIds",List.of(displayed))).andExpect(status().isOk());assertEquals(1,fetch("/api/notifications",cashier).path("unread").asInt());
        long first=fetch("/api/notifications",cashier).path("rows").get(1).path("id").asLong();assertTrue(fetch("/api/notifications/"+first,cashier).path("notification").hasNonNull("readAt"));
        long total=notices.count();new TransactionTemplate(transactions).executeWithoutResult(tx->{notices.findById(first).orElseThrow().relatedPostId=null;reads.findByNoticeIdAndReaderKey(first,"cashier:"+account).ifPresent(r->reads.delete(r));});
        conversationMigration.migrate();assertEquals(total,notices.count());assertTrue(fetch("/api/notifications/"+first,cashier).path("notification").hasNonNull("readAt"));assertEquals(1,fetch("/api/notifications",cashier).path("unread").asInt());conversationMigration.migrate();assertEquals(total,notices.count());
        assertTrue(fetch("/api/notifications",other).path("rows").isEmpty());
    }
    @Test void newestDisplayedPostDoesNotConsumeAnUnreadOlderPost() throws Exception {
        long id=body(cashier(post("/api/conversations"),draft(UUID.randomUUID().toString())).andExpect(status().isOk())).path("id").asLong();
        for(String text:List.of("An older unread reply","Newest reply"))admin(post("/api/conversations/{id}/replies",id),Map.of("requestKey",UUID.randomUUID().toString(),"body",text)).andExpect(status().isOk());
        JsonNode rows=fetch("/api/conversations/"+id,cashier).path("messages").path("rows");long older=rows.get(1).path("id").asLong(),newer=rows.get(2).path("id").asLong();
        cashier(put("/api/conversations/{id}/read",id),Map.of("throughPostId",newer,"displayedPostIds",List.of(newer))).andExpect(status().isOk());assertEquals(1,fetch("/api/notifications",cashier).path("unread").asInt());assertTrue(fetch("/api/conversations/"+id,cashier).path("conversation").path("unread").asBoolean());assertEquals(1,fetch("/api/conversations?unread=true",cashier).path("total").asInt());
        cashier(put("/api/conversations/{id}/read",id),Map.of("throughPostId",older,"displayedPostIds",List.of(older))).andExpect(status().isOk());assertEquals(0,fetch("/api/notifications",cashier).path("unread").asInt());assertFalse(fetch("/api/conversations/"+id,cashier).path("conversation").path("unread").asBoolean());assertEquals(0,fetch("/api/conversations?unread=true",cashier).path("total").asInt());
    }
    @Test void receivedAndSentProjectEveryMessageAndReplyWithExactPersistentReceipts() throws Exception {
        long id=body(cashier(post("/api/conversations"),draft(UUID.randomUUID().toString())).andExpect(status().isOk())).path("id").asLong();
        JsonNode initial=fetch("/api/conversations/"+id,cashier).path("messages").path("rows").get(0);long first=initial.path("id").asLong();
        String sent="/api/conversation-messages?folder=sent&branchId="+branch,received="/api/conversation-messages?folder=received&branchId="+branch;
        assertEquals(first,fetch(sent,cashier).path("rows").get(0).path("id").asLong());
        assertEquals("Sent",findId(fetch(sent,cashier),first).path("readState").asText());
        assertEquals("Unread",findId(fetch(received,"testadmin"),first).path("readState").asText());
        assertEquals("Open",fetch("/api/conversations/"+id,"testadmin").path("conversation").path("status").asText());
        assertFalse(findId(fetch(sent,cashier),first).hasNonNull("recipientReadAt")); // GET never marks read.
        admin(put("/api/conversations/{id}/read",id),Map.of("throughPostId",first,"displayedPostIds",List.of(first))).andExpect(status().isOk());
        assertEquals("Read by recipient",findId(fetch(sent,cashier),first).path("readState").asText());
        admin(post("/api/conversations/{id}/replies",id),Map.of("requestKey",UUID.randomUUID().toString(),"body","Admin reply in the original thread")).andExpect(status().isOk());
        JsonNode reply=fetch(received,cashier).path("rows").get(0);long replyId=reply.path("id").asLong();
        assertEquals(id,reply.path("conversationId").asLong());assertEquals("testadmin",reply.path("sender").asText());assertEquals("Unread",reply.path("readState").asText());
        assertEquals(replyId,fetch(sent,"testadmin").path("rows").get(0).path("id").asLong());
        JsonNode notice=find(fetch("/api/notifications",cashier),"relatedType","CONVERSATION",id);assertEquals(replyId,notice.path("relatedPostId").asLong());
        cashier(put("/api/notifications/{id}/read",notice.path("id").asLong()),Map.of()).andExpect(status().isOk());
        assertEquals("Read",findId(fetch(received,cashier),replyId).path("readState").asText());
        assertTrue(findId(fetch(sent,"testadmin"),replyId).hasNonNull("recipientReadAt"));
        assertEquals(0,fetch("/api/notifications",cashier).path("unread").asInt());
        cashier(post("/api/conversations/{id}/replies",id),Map.of("requestKey",UUID.randomUUID().toString(),"body","Cashier follow-up")).andExpect(status().isOk());
        JsonNode followup=fetch(sent,cashier).path("rows").get(0);assertEquals("Cashier follow-up",followup.path("body").asText());
        assertEquals(2,fetch(sent,cashier).path("total").asInt());assertEquals(2,fetch(received,"testadmin").path("total").asInt());
        assertEquals("Unread",findId(fetch(received,"testadmin"),followup.path("id").asLong()).path("readState").asText());
        // Each request authenticates again, so this also verifies a fresh login sees saved history/read state.
        assertEquals("Read",findId(fetch(received,cashier),replyId).path("readState").asText());
        assertEquals(0,fetch(received,other).path("total").asInt());
        assertEquals(0,fetch(received+"&cashier="+cashier,other).path("total").asInt());
        mvc.perform(get("/api/conversations/{id}?postId={post}",id,replyId).with(httpBasic(other,"cashierpassword123"))).andExpect(status().isNotFound());
    }
    @Test void selectedPostPaginationAndUnseenRepliesRemainIndependentOfBellHistory() throws Exception {
        var direct=draft(UUID.randomUUID().toString());direct.put("cashier",cashier);
        long id=body(admin(post("/api/conversations"),direct).andExpect(status().isOk())).path("id").asLong();
        for(int i=0;i<33;i++)admin(post("/api/conversations/{id}/replies",id),Map.of("requestKey",UUID.randomUUID().toString(),"body","Response "+i)).andExpect(status().isOk());
        JsonNode firstPage=fetch("/api/conversations/"+id+"?size=30",cashier),secondPage=fetch("/api/conversations/"+id+"?page=1&size=30",cashier);
        long selected=secondPage.path("messages").path("rows").get(1).path("id").asLong();
        assertEquals(1,fetch("/api/conversations/"+id+"?postId="+selected,cashier).path("messages").path("page").asInt());
        assertEquals(34,fetch("/api/notifications",cashier).path("unread").asInt());
        cashier(put("/api/conversations/{id}/read",id),Map.of("throughPostId",selected,"displayedPostIds",List.of(selected))).andExpect(status().isOk());
        assertEquals(33,fetch("/api/notifications",cashier).path("unread").asInt());
        String messages="/api/conversation-messages?folder=received&branchId="+branch+"&size=100";
        assertEquals("Read",findId(fetch(messages,cashier),selected).path("readState").asText());
        assertEquals(33,fetch(messages+"&unread=true",cashier).path("total").asInt());
        assertEquals(1,fetch(messages+"&unread=false",cashier).path("total").asInt());
        JsonNode top=fetch(messages+"&search=Response",cashier).path("rows").get(0);assertEquals("Response 32",top.path("body").asText());
        assertEquals(4,fetch(messages.replace("size=100","size=30&page=1"),cashier).path("rows").size());
        long foreign=body(cashier(post("/api/conversations"),draft(UUID.randomUUID().toString())).andExpect(status().isOk())).path("id").asLong();
        mvc.perform(get("/api/conversations/{id}?postId={post}",foreign,selected).with(httpBasic(cashier,"cashierpassword123"))).andExpect(status().isNotFound());
        // Removing a notification does not remove a message or its exact receipt.
        long noticeId=notices.findAll().stream().filter(n->Objects.equals(n.relatedPostId,selected)).findFirst().orElseThrow().id;
        new TransactionTemplate(transactions).executeWithoutResult(tx->{reads.deleteAll(reads.findByNoticeId(noticeId));notices.deleteById(noticeId);});
        assertEquals(34,fetch(messages,cashier).path("total").asInt());assertEquals("Read",findId(fetch(messages,cashier),selected).path("readState").asText());
        conversationMigration.migrate();assertEquals(33,fetch(messages+"&unread=true",cashier).path("total").asInt());
        assertTrue(firstPage.path("conversation").path("unread").asBoolean());
    }
    @Test void messageFoldersKeepAdminIdentityFiltersAndLegacyReadReceipts() throws Exception {
        long id=body(cashier(post("/api/conversations"),draft(UUID.randomUUID().toString())).andExpect(status().isOk())).path("id").asLong();
        JsonNode incoming=find(adminNotices(),"relatedType","CONVERSATION",id);long notice=incoming.path("id").asLong(),post=incoming.path("relatedPostId").asLong();
        // Model a read receipt saved before message folders existed.
        java.time.Instant legacy=java.time.Instant.parse("2026-10-01T00:00:00Z");
        new TransactionTemplate(transactions).executeWithoutResult(tx->reads.save(new lk.sahan.parking.domain.NoticeRead(notices.findById(notice).orElseThrow(),"admin:testadmin",legacy)));
        conversationMigration.migrate();conversationMigration.migrate();
        JsonNode row=findId(fetch("/api/conversation-messages?folder=received&branchId="+branch,"testadmin"),post);assertEquals(legacy.toString(),row.path("readAt").asText());
        var otherAdmin=new UsernamePasswordAuthenticationToken("second-admin","",AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        assertEquals("Unread",conversations.messages(otherAdmin,"received",branch,null,null,null,null,null,0,30).rows().get(0).readState());
        admin(post("/api/conversations/{id}/replies",id),Map.of("requestKey",UUID.randomUUID().toString(),"body","Only in my sent folder")).andExpect(status().isOk());
        assertEquals(0,conversations.messages(otherAdmin,"sent",branch,null,null,null,null,null,0,30).total());
        assertEquals(1,fetch("/api/conversation-messages?folder=sent&branchId="+branch+"&cashier="+cashier+"&priority=IMPORTANT&status=OPEN&search=Only","testadmin").path("total").asInt());
        cashier(put("/api/notifications/read-all"),Map.of()).andExpect(status().isOk());
        assertEquals("Read",fetch("/api/conversation-messages?folder=received&branchId="+branch,cashier).path("rows").get(0).path("readState").asText());
        mvc.perform(get("/api/conversation-messages?folder=invalid").with(httpBasic(cashier,"cashierpassword123"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/conversation-messages")).andExpect(status().isUnauthorized());
    }
    Map<String,Object> draft(String key){Map<String,Object> m=new LinkedHashMap<>();m.put("requestKey",key);m.put("subject","Help with parking");m.put("category","PARKING_ISSUE");m.put("priority","IMPORTANT");m.put("body","Please help with this lane.");return m;}
    ResultActions admin(MockHttpServletRequestBuilder req,Object data)throws Exception{return mvc.perform(req.with(httpBasic("testadmin","adminpassword")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(data)));}
    ResultActions cashier(MockHttpServletRequestBuilder req,Object data)throws Exception{return mvc.perform(req.with(httpBasic(cashier,"cashierpassword123")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(FinancialTestSupport.quoted(mvc,json,cashier,"cashierpassword123",req,data))));}
    JsonNode fetch(String path,String user)throws Exception{return body(mvc.perform(get(path).with(httpBasic(user,user.equals("testadmin")?"adminpassword":user.equals("testcashier")?"testpassword":"cashierpassword123"))).andExpect(status().isOk()));}
    JsonNode adminNotices()throws Exception{return fetch("/api/notifications?size=100","testadmin");}
    JsonNode body(ResultActions response)throws Exception{return json.readTree(response.andReturn().getResponse().getContentAsString());}
    JsonNode find(JsonNode page,String field,String value,long id){for(JsonNode n:page.path("rows"))if(n.path(field).asText().equals(value)&&n.path("relatedId").asLong()==id)return n;return null;}
    JsonNode findId(JsonNode page,long id){for(JsonNode n:page.path("rows"))if(n.path("id").asLong()==id)return n;throw new AssertionError("Conversation missing "+id);}
}
