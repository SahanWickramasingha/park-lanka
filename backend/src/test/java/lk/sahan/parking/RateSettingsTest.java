package lk.sahan.parking;

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
        "DATABASE_URL=jdbc:h2:mem:ratetest;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "PARKING_INITIAL_SLOTS=2"
})
@AutoConfigureMockMvc
class RateSettingsTest {
    @org.junit.jupiter.api.BeforeEach void activeShiftFixture() throws Exception { FinancialTestSupport.openShift(mvc,"testcashier","testpassword"); }
    @Autowired MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void adminRatePersistsAndExistingSessionKeepsOriginalRate() throws Exception {
        mvc.perform(get("/api/rates").with(httpBasic("testcashier", "testpassword")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/me").with(httpBasic("testadmin", "adminpassword")))
                .andExpect(jsonPath("$.role").value("ADMIN"));
        var slots = mvc.perform(get("/api/slots").with(httpBasic("testcashier", "testpassword")))
                .andExpect(status().isOk()).andReturn();
        long first = mapper.readTree(slots.getResponse().getContentAsString()).get(0).path("id").asLong();
        long second = mapper.readTree(slots.getResponse().getContentAsString()).get(1).path("id").asLong();
        long oldSession = enter("OLD-123", first);
        mvc.perform(put("/api/rates").with(httpBasic("testcashier", "testpassword"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"perMinute\":2.50}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/rates").with(httpBasic("testadmin", "adminpassword"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"perMinute\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/rates").with(httpBasic("testadmin", "adminpassword"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"perMinute\":2.50}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.perMinute").value(2.50));
        mvc.perform(get("/api/rates").with(httpBasic("testadmin", "adminpassword")))
                .andExpect(jsonPath("$.perMinute").value(2.50));
        long newSession = enter("NEW-456", second);
        mvc.perform(get("/api/sessions/{id}/quote", oldSession).with(httpBasic("testcashier", "testpassword")))
                .andExpect(jsonPath("$.amount").value(1.00));
        mvc.perform(get("/api/sessions/{id}/quote", newSession).with(httpBasic("testcashier", "testpassword")))
                .andExpect(jsonPath("$.amount").value(2.50));
        mvc.perform(post("/api/sessions/{id}/checkout", oldSession).with(httpBasic("testcashier", "testpassword"))
                .contentType(MediaType.APPLICATION_JSON).content(FinancialTestSupport.cashJson(mvc, oldSession, "testcashier", "testpassword")))
                .andExpect(jsonPath("$.amount").value(1.00));
        mvc.perform(get("/api/receipts/{id}", oldSession).with(httpBasic("testcashier", "testpassword")))
                .andExpect(jsonPath("$.amount").value(1.00));
    }

    private long enter(String plate, long slotId) throws Exception {
        var response = mvc.perform(post("/api/sessions").with(httpBasic("testcashier", "testpassword"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"plate\":\"" + plate + "\",\"vehicleType\":\"CAR\",\"slotId\":" + slotId + "}"))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString()).path("id").asLong();
    }
}
