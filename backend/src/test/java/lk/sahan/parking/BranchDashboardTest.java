package lk.sahan.parking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "test.cashier.username=testcashier", "test.cashier.password=testpassword",
        "PARKING_ADMIN_USER=testadmin", "PARKING_ADMIN_PASSWORD=adminpassword",
        "DATABASE_URL=jdbc:h2:mem:branchtest;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "PARKING_INITIAL_SLOTS=2"
})
@AutoConfigureMockMvc
class BranchDashboardTest {
    @org.junit.jupiter.api.BeforeEach void activeShiftFixture() throws Exception { FinancialTestSupport.openShift(mvc,"testcashier","testpassword"); }
    @Autowired MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void branchesSeparateCashierDataAndAdminCanBrowseRecords() throws Exception {
        JsonNode created = body(mvc.perform(post("/api/admin/branches")
                .with(httpBasic("testadmin", "adminpassword"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Kandy Branch\",\"slots\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("002"))
                .andReturn());
        long branchId = created.path("id").asLong();
        mvc.perform(post("/api/admin/cashiers").with(httpBasic("testadmin", "adminpassword"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"kandycashier\",\"password\":\"cashierpassword123\",\"branchId\":" + branchId + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.branchId").value(branchId));

        mvc.perform(get("/api/me").with(httpBasic("kandycashier", "cashierpassword123")))
                .andExpect(jsonPath("$.branchId").value(branchId));
        mvc.perform(get("/api/admin/branches").with(httpBasic("kandycashier", "cashierpassword123")))
                .andExpect(status().isForbidden());

        JsonNode kandySlots = body(mvc.perform(get("/api/slots")
                .with(httpBasic("kandycashier", "cashierpassword123"))).andExpect(status().isOk()).andReturn());
        long slotId = kandySlots.get(0).path("id").asLong();
        long sessionId = body(mvc.perform(post("/api/sessions")
                .with(httpBasic("kandycashier", "cashierpassword123"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plate\":\"KD-1234\",\"vehicleType\":\"CAR\",\"slotId\":" + slotId + "}"))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        mvc.perform(get("/api/sessions").with(httpBasic("testcashier", "testpassword")))
                .andExpect(content().json("[]"));
        mvc.perform(get("/api/sessions/{id}/quote", sessionId).with(httpBasic("testcashier", "testpassword")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/sessions/{id}/checkout", sessionId).with(httpBasic("testcashier", "testpassword"))
                .contentType(MediaType.APPLICATION_JSON).content(FinancialTestSupport.cashJson(mvc, sessionId, "testcashier", "testpassword")))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/admin/records?table=sessions&branchId=" + branchId)
                .with(httpBasic("testadmin", "adminpassword")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].enteredBy").value("kandycashier"));
        mvc.perform(get("/api/admin/records?table=sessions")
                .with(httpBasic("testcashier", "testpassword"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/records?table=cashiers")
                .with(httpBasic("testadmin", "adminpassword"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].passwordHash").doesNotExist());

        long id = java.util.stream.StreamSupport.stream(body(mvc.perform(get("/api/admin/cashiers").with(httpBasic("testadmin", "adminpassword")))
                .andExpect(status().isOk()).andReturn()).spliterator(), false).filter(row -> row.path("username").asText().equals("kandycashier")).findFirst().orElseThrow().path("id").asLong();
        mvc.perform(put("/api/admin/cashiers/{id}/enabled", id).with(httpBasic("testadmin", "adminpassword"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me").with(httpBasic("kandycashier", "cashierpassword123")))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }
}
