package lk.sahan.parking;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import lk.sahan.parking.repo.*;
import lk.sahan.parking.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"test.cashier.username=testcashier", "test.cashier.password=testpassword", "PARKING_ADMIN_USER=testadmin", "PARKING_ADMIN_PASSWORD=adminpassword", "DATABASE_URL=jdbc:h2:mem:broadcasttest;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop", "PARKING_INITIAL_SLOTS=2"})
@AutoConfigureMockMvc
class BroadcastTest {
    private static final Instant NOW = Instant.parse("2026-10-21T03:00:00Z"); private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final MutableClock CLOCK = new MutableClock(); private final ObjectMapper json = new ObjectMapper();
    @TestConfiguration static class TestTime { @Bean @Primary Clock testClock() { return CLOCK; } }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> instant = new AtomicReference<>(NOW);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; } @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); } @Override public Instant instant() { return instant.get(); }
    }
    @Autowired MockMvc mvc; @Autowired BranchService branches; @Autowired CashierService cashiers;
    @Autowired BroadcastService service; @Autowired BroadcastRepository broadcasts; @Autowired BroadcastRecipientRepository recipients;
    private long branchId; private String username; private long accountId;
    @BeforeEach void fixture() {
        CLOCK.instant.set(NOW); int number = SEQUENCE.incrementAndGet(); username = "messagecashier" + number;
        branchId = branches.create("Message Branch " + number, 1).getId(); accountId = cashiers.create(username, "cashierpassword123", branchId).id();
    }
    @Test void audiencesAreFixedAtSendTimeAndIncludeEligibleOfflineBootstrapCashier() throws Exception {
        long stopped = cashiers.create(username + "stop", "cashierpassword123", branchId).id(); cashiers.enable(stopped, false);
        long removed = cashiers.create(username + "remove", "cashierpassword123", branchId).id(); cashiers.remove(removed);
        long deadBranch = branches.create("Deleted audience " + username, 1).getId(); String deadUser = username + "dead"; cashiers.create(deadUser, "cashierpassword123", deadBranch); branches.remove(deadBranch);
        branches.enable(branchId, false); // Branch stopping preserves account access and message eligibility.
        long message = send(payload(true, Set.of(), null)).path("id").asLong();
        assertTrue(has(inbox(username), message)); assertTrue(has(inbox("testcashier"), message));
        cashiers.enable(stopped, true); assertFalse(has(inbox(username + "stop"), message));
        String late = username + "late"; cashiers.create(late, "cashierpassword123", branchId); assertFalse(has(inbox(late), message));
        JsonNode details = body(mvc.perform(get("/api/admin/broadcasts/{id}", message).with(httpBasic("testadmin", "adminpassword"))).andExpect(status().isOk()));
        String text = details.toString(); assertFalse(text.contains(username + "stop")); assertFalse(text.contains(username + "remove")); assertFalse(text.contains(deadUser)); assertFalse(text.contains("password"));
        long selected = send(payload(false, Set.of(branchId), null)).path("id").asLong(); assertTrue(has(inbox(username), selected)); assertFalse(has(inbox("testcashier"), selected));
        mvc.perform(put("/api/messages/{id}/read", selected).with(httpBasic("testcashier", "testpassword"))).andExpect(status().isNotFound());
        long mainMessage = send(payload(false, Set.of(branches.main().getId()), null)).path("id").asLong(); assertTrue(has(inbox("testcashier"), mainMessage));
    }
    @Test void ownReadStateIsPersistentIdempotentAndIsolated() throws Exception {
        long id = send(payload(false, Set.of(branchId), null)).path("id").asLong();
        JsonNode first = body(mvc.perform(put("/api/messages/{id}/read", id).with(httpBasic(username, "cashierpassword123"))).andExpect(status().isOk()));
        CLOCK.instant.set(NOW.plusSeconds(5)); JsonNode second = body(mvc.perform(put("/api/messages/{id}/read", id).with(httpBasic(username, "cashierpassword123"))).andExpect(status().isOk()));
        assertEquals(first.path("readAt"), second.path("readAt")); assertEquals(1, recipients.countByBroadcastIdAndReadAtIsNotNull(id)); assertEquals(1, recipients.countByBroadcastId(id));
        JsonNode own = body(mvc.perform(get("/api/messages?username=testcashier").with(httpBasic(username, "cashierpassword123"))).andExpect(status().isOk())); assertTrue(has(own, id)); assertTrue(own.get(0).hasNonNull("readAt"));
        mvc.perform(put("/api/messages/{id}/read", id).with(httpBasic("testcashier", "testpassword"))).andExpect(status().isNotFound());
        cashiers.enable(accountId, false); mvc.perform(get("/api/messages").with(httpBasic(username, "cashierpassword123"))).andExpect(status().isUnauthorized());
    }
    @Test void expiryAndRevocationRemoveActiveMessagesButRetainHistory() throws Exception {
        long expires = send(payload(false, Set.of(branchId), NOW.plusSeconds(60))).path("id").asLong();
        CLOCK.instant.set(NOW.plusSeconds(60)); assertFalse(has(inbox(username), expires));
        mvc.perform(put("/api/messages/{id}/read", expires).with(httpBasic(username, "cashierpassword123"))).andExpect(status().isGone());
        mvc.perform(get("/api/admin/broadcasts/{id}", expires).with(httpBasic("testadmin", "adminpassword"))).andExpect(jsonPath("$.message.status").value("Expired"));
        long revoked = send(payload(false, Set.of(branchId), null)).path("id").asLong();
        mvc.perform(put("/api/admin/broadcasts/{id}/revoke", revoked).with(httpBasic("testadmin", "adminpassword"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Revoked"));
        mvc.perform(put("/api/admin/broadcasts/{id}/revoke", revoked).with(httpBasic("testadmin", "adminpassword"))).andExpect(status().isOk());
        assertFalse(has(inbox(username), revoked)); assertEquals(1, recipients.countByBroadcastId(revoked));
        mvc.perform(get("/api/admin/broadcasts/{id}", revoked).with(httpBasic("testadmin", "adminpassword"))).andExpect(jsonPath("$.recipients[0].username").value(username));
        branches.remove(branchId); mvc.perform(get("/api/messages").with(httpBasic(username, "cashierpassword123"))).andExpect(status().isUnauthorized());
    }
    @Test void duplicateSendsIncludingConcurrentRetriesReturnOneBroadcast() throws Exception {
        Map<String, Object> request = payload(false, Set.of(branchId), null); JsonNode first = send(request); assertEquals(first.path("id"), send(request).path("id"));
        request.put("title", "Changed title"); mvc.perform(post("/api/admin/broadcasts").with(httpBasic("testadmin", "adminpassword")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))).andExpect(status().isConflict());
        String key = UUID.randomUUID().toString(); var direct = new BroadcastService.SendRequest(key, "Concurrent", "Same request", "NORMAL", false, Set.of(branchId), null);
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch gate = new CountDownLatch(1);
        try {
            Callable<Long> send = () -> { gate.await(); return service.send(direct, "testadmin").id(); };
            Future<Long> a = pool.submit(send), b = pool.submit(send); gate.countDown(); long id = a.get(15, TimeUnit.SECONDS); assertEquals(id, b.get(15, TimeUnit.SECONDS)); assertEquals(1, recipients.countByBroadcastId(id));
            assertEquals(id, broadcasts.findByRequestKey(key).orElseThrow().getId());
        } finally { pool.shutdownNow(); }
    }
    @Test void authorizationAndValidationProtectAdministrativeOperations() throws Exception {
        Map<String, Object> request = payload(false, Set.of(branchId), null);
        mvc.perform(post("/api/admin/broadcasts").with(httpBasic(username, "cashierpassword123")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/broadcasts").with(httpBasic(username, "cashierpassword123"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/broadcasts/1/revoke").with(httpBasic(username, "cashierpassword123"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/messages").with(httpBasic("testadmin", "adminpassword"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/broadcasts")).andExpect(status().isUnauthorized());
        for (Map<String, Object> invalid : List.of(payload(false, Set.of(), null), payload(false, Set.of(branchId), NOW), payload(true, Set.of(branchId), null)))
            mvc.perform(post("/api/admin/broadcasts").with(httpBasic("testadmin", "adminpassword")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        long empty = branches.create("Empty audience " + username, 1).getId(); mvc.perform(post("/api/admin/broadcasts").with(httpBasic("testadmin", "adminpassword")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload(false, Set.of(empty), null)))).andExpect(status().isConflict());
    }
    private Map<String, Object> payload(boolean all, Set<Long> ids, Instant expires) { Map<String, Object> value = new LinkedHashMap<>(); value.put("requestKey", UUID.randomUUID().toString()); value.put("title", "Shift notice"); value.put("body", "Please review the parking update."); value.put("priority", "IMPORTANT"); value.put("allBranches", all); value.put("branchIds", ids); value.put("expiresAt", expires == null ? null : expires.toString()); return value; }
    private JsonNode send(Map<String, Object> request) throws Exception { return body(mvc.perform(post("/api/admin/broadcasts").with(httpBasic("testadmin", "adminpassword")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))).andExpect(status().isOk())); }
    private JsonNode inbox(String user) throws Exception { return body(mvc.perform(get("/api/messages").with(httpBasic(user, user.equals("testcashier") ? "testpassword" : "cashierpassword123"))).andExpect(status().isOk())); }
    private JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    private boolean has(JsonNode messages, long id) { for (JsonNode message : messages) if (message.path("id").asLong() == id) return true; return false; }
}
