package lk.sahan.parking;

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
        "DATABASE_URL=jdbc:h2:mem:parkingtest;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "PARKING_INITIAL_SLOTS=2"
})
@AutoConfigureMockMvc
class ParkingFlowTest {
    @org.junit.jupiter.api.BeforeEach void activeShiftFixture() throws Exception { FinancialTestSupport.openShift(mvc,"testcashier","testpassword"); }
    @Autowired MockMvc mvc;

    @Test void checkoutReleasesSlotAndAllowsPlateToReturn() throws Exception {
        var slotResult = mvc.perform(get("/api/slots").with(httpBasic("testcashier", "testpassword")))
                .andExpect(status().isOk()).andReturn();
        long slotId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(slotResult.getResponse().getContentAsString()).get(0).path("id").asLong();
        String entry = "{\"plate\":\"ABC-1234\",\"vehicleType\":\"CAR\",\"slotId\":" + slotId + "}";
        var response = mvc.perform(post("/api/sessions").with(httpBasic("testcashier", "testpassword"))
                .contentType(MediaType.APPLICATION_JSON).content(entry)).andExpect(status().isCreated()).andReturn();
        long sessionId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getResponse().getContentAsString()).path("id").asLong();
        mvc.perform(post("/api/sessions").with(httpBasic("testcashier", "testpassword"))
                .contentType(MediaType.APPLICATION_JSON).content(entry)).andExpect(status().isConflict());
        mvc.perform(get("/api/sessions/{id}/quote", sessionId).with(httpBasic("testcashier", "testpassword")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(1.00));
        mvc.perform(post("/api/sessions/{id}/checkout", sessionId).with(httpBasic("testcashier", "testpassword"))
                .contentType(MediaType.APPLICATION_JSON).content(FinancialTestSupport.cashJson(mvc, sessionId, "testcashier", "testpassword")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.plate").value("ABC-1234"));
        mvc.perform(post("/api/sessions/{id}/checkout", sessionId).with(httpBasic("testcashier", "testpassword"))
                .contentType(MediaType.APPLICATION_JSON).content(FinancialTestSupport.cashJson(mvc, sessionId, "testcashier", "testpassword")))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/receipts/{id}", sessionId).with(httpBasic("testcashier", "testpassword")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(1.00));
        mvc.perform(post("/api/sessions").with(httpBasic("testcashier", "testpassword"))
                .contentType(MediaType.APPLICATION_JSON).content(entry)).andExpect(status().isCreated());
    }
}
