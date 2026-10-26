package lk.sahan.parking;

import com.fasterxml.jackson.databind.*;
import lk.sahan.parking.repo.*;
import lk.sahan.parking.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercise the real security filter chain, not mock roles that could hide bootstrap identity bugs. */
@SpringBootTest(properties={"test.cashier.username=bootstrapcashier","test.cashier.password=bootstrap-password",
        "PARKING_ADMIN_USER=messageadmin","PARKING_ADMIN_PASSWORD=admin-password",
        "DATABASE_URL=jdbc:h2:mem:messagingauthorization;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop","PARKING_INITIAL_SLOTS=2"})
@AutoConfigureMockMvc
class MessagingAuthorizationTest {
    @Autowired MockMvc mvc;
    @Autowired BranchService branches;
    @Autowired CashierService cashiers;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationPostRepository posts;
    private final ObjectMapper json=new ObjectMapper();
    private static final AtomicInteger sequence=new AtomicInteger();
    private String databaseCashier,other,subject;
    private long databaseId,branch;

    @BeforeEach void fixture(){
        int n=sequence.incrementAndGet();databaseCashier="messagecashier"+n;other="messageother"+n;subject="Access case "+n;
        branch=branches.main().getId();databaseId=cashiers.create(databaseCashier,"database-password",branch).id();
        // Same branch deliberately: ownership must not degrade into a branch-only permission check.
        cashiers.create(other,"database-password",branch);
    }
    @ParameterizedTest @ValueSource(strings={"bootstrap","database"})
    void cashierCanListOpenReplyAndReadOwnMessages(String type)throws Exception {
        String user=type.equals("bootstrap")?"bootstrapcashier":databaseCashier;
        Map<String,Object> draft=draft();draft.put("cashier",other); // The authenticated cashier owns it regardless.
        long thread=write("POST","/api/conversations",user,draft,200).path("id").asLong();
        String key=type.equals("bootstrap")?"bootstrap:bootstrapcashier":"cashier:"+databaseId;
        assertEquals(key,conversations.findById(thread).orElseThrow().ownerKey);
        JsonNode details=fetch("/api/conversations/"+thread,user);long first=details.path("messages").path("rows").get(0).path("id").asLong();
        assertEquals(key,posts.findById(first).orElseThrow().senderKey);
        assertEquals(user,details.path("conversation").path("cashier").asText());
        assertEquals(1,folder("sent",user).path("total").asInt());assertEquals(0,folder("received",user).path("total").asInt());
        assertEquals("Sent",folder("sent",user).path("rows").get(0).path("readState").asText());
        assertEquals(1,folder("received","messageadmin").path("total").asInt());
        write("POST","/api/conversations/"+thread+"/replies","messageadmin",reply("Admin answer"),200);
        JsonNode incoming=folder("received",user).path("rows").get(0);long answer=incoming.path("id").asLong();
        assertEquals(thread,incoming.path("conversationId").asLong());assertEquals("messageadmin",incoming.path("sender").asText());assertEquals("Unread",incoming.path("readState").asText());
        assertEquals(1,folder("sent","messageadmin").path("total").asInt());
        fetch("/api/conversations/"+thread+"?postId="+answer,user);
        assertEquals("Unread",folder("received",user).path("rows").get(0).path("readState").asText());
        write("PUT","/api/conversations/"+thread+"/read",user,Map.of("throughPostId",answer,"displayedPostIds",List.of(answer)),200);
        assertEquals("Read",folder("received",user).path("rows").get(0).path("readState").asText());
        assertEquals("Read by recipient",folder("sent","messageadmin").path("rows").get(0).path("readState").asText());
        JsonNode notice=fetch("/api/notifications?size=100",user).path("rows");
        for(JsonNode n:notice)if(n.path("relatedPostId").asLong()==answer)assertTrue(n.hasNonNull("readAt"));
        write("POST","/api/conversations/"+thread+"/replies",user,reply("Cashier follow-up"),200);
        assertEquals(2,folder("sent",user).path("total").asInt());assertEquals(2,folder("received","messageadmin").path("total").asInt());
        assertEquals("Cashier follow-up",folder("sent",user).path("rows").get(0).path("body").asText());
        // Also cover an admin-created thread and a bell read by the same cashier identity.
        Map<String,Object> direct=draft();direct.put("cashier",user);
        long directId=write("POST","/api/conversations","messageadmin",direct,200).path("id").asLong();
        long directPost=fetch("/api/conversations/"+directId,user).path("messages").path("rows").get(0).path("id").asLong();
        JsonNode notification=null;for(JsonNode n:fetch("/api/notifications?size=100",user).path("rows"))if(n.path("relatedPostId").asLong()==directPost)notification=n;
        assertNotNull(notification);write("PUT","/api/notifications/"+notification.path("id").asLong()+"/read",user,Map.of(),200);
        assertEquals("Read",fetch("/api/conversations/"+directId,user).path("messages").path("rows").get(0).path("readState").asText());
    }
    @ParameterizedTest @ValueSource(strings={"bootstrap","database"})
    void cashierCannotAccessAnotherOwnerOrAdministrativeEndpoints(String type)throws Exception {
        String user=type.equals("bootstrap")?"bootstrapcashier":databaseCashier;
        long thread=write("POST","/api/conversations",other,draft(),200).path("id").asLong();
        write("POST","/api/conversations/"+thread+"/replies","messageadmin",reply("Private answer"),200);
        JsonNode messages=fetch("/api/conversations/"+thread,other).path("messages").path("rows");long incoming=messages.get(1).path("id").asLong();
        assertEquals(0,folder("received",user).path("total").asInt());assertEquals(0,folder("sent",user).path("total").asInt());
        mvc.perform(get("/api/conversation-messages").param("folder","received").param("search",subject).param("cashier",other).param("branchId",Long.toString(branch)).with(httpBasic(user,password(user)))).andExpect(status().isOk()).andExpect(result->assertEquals(0,json.readTree(result.getResponse().getContentAsString()).path("total").asInt()));
        mvc.perform(get("/api/conversations/"+thread).with(httpBasic(user,password(user)))).andExpect(status().isNotFound());
        mvc.perform(get("/api/conversations/"+thread).param("postId",Long.toString(incoming)).with(httpBasic(user,password(user)))).andExpect(status().isNotFound());
        write("POST","/api/conversations/"+thread+"/replies",user,reply("Forbidden reply"),404);
        write("PUT","/api/conversations/"+thread+"/read",user,Map.of("throughPostId",incoming,"displayedPostIds",List.of(incoming)),404);
        for(String path:List.of("/api/admin/cashiers","/api/admin/branches"))mvc.perform(get(path).with(httpBasic(user,password(user)))).andExpect(status().isForbidden());
        write("PUT","/api/admin/conversations/"+thread+"/resolution",user,Map.of("requestKey",UUID.randomUUID().toString(),"resolved",true),403);
        mvc.perform(get("/api/admin/cashiers").with(httpBasic("messageadmin",password("messageadmin")))).andExpect(status().isOk());
        mvc.perform(get("/api/conversation-messages")).andExpect(status().isUnauthorized());
        assertEquals(2,fetch("/api/conversations/"+thread,other).path("messages").path("total").asInt());
    }
    private Map<String,Object> draft(){var m=new LinkedHashMap<String,Object>();m.put("requestKey",UUID.randomUUID().toString());m.put("subject",subject);m.put("category","GENERAL");m.put("priority","NORMAL");m.put("body","Own account message");return m;}
    private Map<String,Object> reply(String body){return Map.of("requestKey",UUID.randomUUID().toString(),"body",body);}
    private String password(String user){return user.equals("bootstrapcashier")?"bootstrap-password":user.equals("messageadmin")?"admin-password":"database-password";}
    private JsonNode fetch(String path,String user)throws Exception{return json.readTree(mvc.perform(get(path).with(httpBasic(user,password(user)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    private JsonNode folder(String folder,String user)throws Exception{return json.readTree(mvc.perform(get("/api/conversation-messages").param("folder",folder).param("search",subject).with(httpBasic(user,password(user)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    private JsonNode write(String method,String path,String user,Object data,int expected)throws Exception {
        var request=method.equals("POST")?post(path):put(path);
        String content=mvc.perform(request.with(httpBasic(user,password(user))).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(data))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return content.isBlank()?json.nullNode():json.readTree(content);
    }
}
