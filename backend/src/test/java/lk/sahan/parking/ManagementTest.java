package lk.sahan.parking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import lk.sahan.parking.repo.*;
import lk.sahan.parking.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "test.cashier.username=testcashier", "test.cashier.password=testpassword",
        "PARKING_ADMIN_USER=testadmin", "PARKING_ADMIN_PASSWORD=adminpassword",
        "DATABASE_URL=jdbc:h2:mem:management;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop",
        "PARKING_INITIAL_SLOTS=2"
})
@AutoConfigureMockMvc
class ManagementTest {
    private static final String PASSWORD = "cashierpassword123";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private final ObjectMapper mapper = new ObjectMapper();
    @Autowired MockMvc mvc;
    @Autowired BranchService branches;
    @Autowired BranchRepository branchRepo;
    @Autowired SlotRepository slots;
    @Autowired CashierRepository cashiers;
    @Autowired ParkingService parking;
    @Autowired PlatformTransactionManager transactions;
    private long branchId, cashierId, slotId;
    private String username;

    @BeforeEach void createBranchAndCashier() throws Exception {
        int sequence = SEQUENCE.incrementAndGet();
        username = "managecashier" + sequence;
        branchId = body(admin(post("/api/admin/branches"), Map.of("name", "Manage Branch " + sequence, "slots", 2))
                .andExpect(status().isCreated())).path("id").asLong();
        cashierId = body(admin(post("/api/admin/cashiers"), Map.of("username", username, "password", PASSWORD, "branchId", branchId))
                .andExpect(status().isCreated())).path("id").asLong();
        slotId = slots.findByBranchId(branchId).get(0).getId();
        FinancialTestSupport.openShift(mvc,username,PASSWORD);
    }

    @Test void stoppedBranchBlocksEntryButPreservesCheckoutReceiptAndRate() throws Exception {
        long ticket = enter("STOP-001");
        setBranch(false).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Stopped"));
        staff(get("/api/me"), null).andExpect(status().isOk());
        staff(post("/api/sessions"), entry("STOP-002")).andExpect(status().isConflict());
        staff(get("/api/sessions/{id}/quote", ticket), null).andExpect(status().isOk());
        staff(post("/api/sessions/{id}/checkout", ticket), FinancialTestSupport.cash())
                .andExpect(status().isOk()).andExpect(jsonPath("$.plate").value("STOP-001"));
        staff(get("/api/receipts/{id}", ticket), null).andExpect(status().isOk());
        setBranch(true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Active"));
        enter("STOP-002");
    }

    @Test void branchDeletionChecksBothActiveSessionsAndOccupiedSlots() throws Exception {
        long ticket = enter("DEL-001");
        admin(delete("/api/admin/branches/{id}", branchId), null).andExpect(status().isConflict());
        // Even inconsistent legacy occupancy must not permit deletion of an active session.
        new TransactionTemplate(transactions).executeWithoutResult(tx -> slots.findById(slotId).orElseThrow().setOccupied(false));
        admin(delete("/api/admin/branches/{id}", branchId), null).andExpect(status().isConflict());
        staff(post("/api/sessions/{id}/checkout", ticket), FinancialTestSupport.cash()).andExpect(status().isOk());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> slots.findById(slotId).orElseThrow().setOccupied(true));
        admin(delete("/api/admin/branches/{id}", branchId), null).andExpect(status().isConflict());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> slots.findById(slotId).orElseThrow().setOccupied(false));
        admin(delete("/api/admin/branches/{id}", branchId), null).andExpect(status().isOk());

        assertTrue(branchRepo.findById(branchId).orElseThrow().isDeleted());
        JsonNode operational = body(admin(get("/api/admin/branches"), null).andExpect(status().isOk()));
        assertFalse(containsId(operational, branchId));
        assertTrue(containsId(body(admin(get("/api/admin/branches?includeDeleted=true"), null)), branchId));
        admin(get("/api/admin/records?table=branches&branchId=" + branchId), null)
                .andExpect(jsonPath("$.rows[0].status").value("Deleted"));
        admin(get("/api/admin/records?table=sessions&branchId=" + branchId), null)
                .andExpect(jsonPath("$.rows[0].enteredBy").value(username))
                .andExpect(jsonPath("$.rows[0].branchStatus").value("Deleted"));
        admin(get("/api/admin/records?table=payments&branchId=" + branchId), null)
                .andExpect(jsonPath("$.rows[0].cashier").value(username));
        staff(get("/api/me"), null).andExpect(status().isUnauthorized());
        staff(get("/api/receipts/{id}", ticket), null).andExpect(status().isUnauthorized());
        admin(put("/api/admin/cashiers/{id}/enabled", cashierId), Map.of("enabled", true)).andExpect(status().isConflict());
        setBranch(true).andExpect(status().isConflict());
        admin(post("/api/admin/cashiers"), Map.of("username", username + "new", "password", PASSWORD, "branchId", branchId))
                .andExpect(status().isConflict());
        branches.initialize();
        assertTrue(branchRepo.findById(branchId).orElseThrow().isDeleted());
    }

    @Test void cashierStopStartAndRemoveRevokeOpenAppAndReserveUsername() throws Exception {
        long ticket = enter("CASH-001");
        staff(post("/api/sessions/{id}/checkout", ticket), FinancialTestSupport.cash()).andExpect(status().isOk());
        staff(get("/api/me"), null).andExpect(status().isOk());
        admin(put("/api/admin/cashiers/{id}/enabled", cashierId), Map.of("enabled", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Stopped"));
        assertRevoked();
        setBranch(false).andExpect(status().isOk());
        setBranch(true).andExpect(status().isOk());
        assertRevoked(); // Starting a branch must not enable an individually stopped cashier.
        admin(put("/api/admin/cashiers/{id}/enabled", cashierId), Map.of("enabled", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Active"));
        staff(get("/api/me"), null).andExpect(status().isOk());
        admin(delete("/api/admin/cashiers/{id}", cashierId), null).andExpect(status().isOk());
        assertRevoked();
        assertFalse(containsId(body(admin(get("/api/admin/cashiers"), null)), cashierId));
        assertTrue(cashiers.findById(cashierId).orElseThrow().isDeleted());
        admin(post("/api/admin/cashiers"), Map.of("username", username, "password", PASSWORD, "branchId", branchId))
                .andExpect(status().isConflict());
        admin(put("/api/admin/cashiers/{id}/enabled", cashierId), Map.of("enabled", true)).andExpect(status().isConflict());
        admin(get("/api/admin/records?table=cashiers&branchId=" + branchId), null)
                .andExpect(jsonPath("$.rows[0].status").value("Removed"))
                .andExpect(jsonPath("$.rows[0].passwordHash").doesNotExist());
        admin(get("/api/admin/records?table=payments&branchId=" + branchId), null)
                .andExpect(jsonPath("$.rows[0].cashier").value(username));
    }

    @Test void mainIsProtectedAndMissingCashiersAndManagementPermissionsAreChecked() throws Exception {
        admin(delete("/api/admin/branches/{id}", branches.main().getId()), null).andExpect(status().isConflict());
        for (boolean enabled : List.of(true, false))
            admin(put("/api/admin/cashiers/0/enabled"), Map.of("enabled", enabled))
                    .andExpect(status().isNotFound());
        admin(delete("/api/admin/cashiers/0"), null).andExpect(status().isNotFound());
        admin(put("/api/admin/cashiers/0/password"), Map.of("password", PASSWORD)).andExpect(status().isNotFound());
        staff(delete("/api/admin/branches/{id}", branchId), null).andExpect(status().isForbidden());
        staff(put("/api/admin/branches/{id}/enabled", branchId), Map.of("enabled", false)).andExpect(status().isForbidden());
        staff(delete("/api/admin/cashiers/{id}", cashierId), null).andExpect(status().isForbidden());
        staff(put("/api/admin/cashiers/{id}/enabled", cashierId), Map.of("enabled", false)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/branches/{id}", branchId)).andExpect(status().isUnauthorized());
    }

    @Test void entryWaitsForConcurrentBranchStopOrDeletionAndRechecksStatus() throws Exception {
        assertEntryCannotRaceManagement(false);
        // The same database lock protects deletion as well as stopping.
        assertEntryCannotRaceManagement(true);
    }

    private void assertEntryCannotRaceManagement(boolean deleteBranch) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entryStarted = new CountDownLatch(1);
        CompletableFuture<Void> finished = new CompletableFuture<>();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                branchRepo.lockById(branchId).orElseThrow();
                executor.submit(() -> {
                    entryStarted.countDown();
                    try {
                        parking.enter(new ParkingService.EntryRequest("RACE-001", "CAR", slotId), branchId, username);
                        finished.completeExceptionally(new AssertionError("Entry should be blocked"));
                    } catch (Throwable error) { finished.completeExceptionally(error); }
                });
                try { assertTrue(entryStarted.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                if (deleteBranch) branches.remove(branchId); else branches.enable(branchId, false);
            });
            ExecutionException error = assertThrows(ExecutionException.class, () -> finished.get(10, TimeUnit.SECONDS));
            assertInstanceOf(ResponseStatusException.class, error.getCause());
            assertEquals(409, ((ResponseStatusException) error.getCause()).getStatusCode().value());
            assertFalse(slots.findById(slotId).orElseThrow().isOccupied());
        } finally { executor.shutdownNow(); }
    }

    private void assertRevoked() throws Exception {
        MockHttpSession oldSession = new MockHttpSession();
        oldSession.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(username, null, AuthorityUtils.createAuthorityList("ROLE_CASHIER"))));
        staff(get("/api/slots").session(oldSession), null).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").session(oldSession)).andExpect(status().isUnauthorized());
        staff(post("/api/sessions"), entry("DENY-001")).andExpect(status().isUnauthorized());
    }
    private long enter(String plate) throws Exception {
        return body(staff(post("/api/sessions"), entry(plate)).andExpect(status().isCreated())).path("id").asLong();
    }
    private Map<String, Object> entry(String plate) { return Map.of("plate", plate, "vehicleType", "CAR", "slotId", slotId); }
    private ResultActions setBranch(boolean enabled) throws Exception {
        return admin(put("/api/admin/branches/{id}/enabled", branchId), Map.of("enabled", enabled));
    }
    private ResultActions admin(MockHttpServletRequestBuilder request, Object data) throws Exception {
        return perform(request.with(httpBasic("testadmin", "adminpassword")), data);
    }
    private ResultActions staff(MockHttpServletRequestBuilder request, Object data) throws Exception {
        data=FinancialTestSupport.quoted(mvc,mapper,username,PASSWORD,request,data);
        return perform(request.with(httpBasic(username, PASSWORD)), data);
    }
    private ResultActions perform(MockHttpServletRequestBuilder request, Object data) throws Exception {
        if (data != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(data));
        return mvc.perform(request);
    }
    private JsonNode body(ResultActions response) throws Exception { return mapper.readTree(response.andReturn().getResponse().getContentAsString()); }
    private boolean containsId(JsonNode rows, long id) {
        for (JsonNode row : rows) if (row.path("id").asLong() == id) return true;
        return false;
    }
}
