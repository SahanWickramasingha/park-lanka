package lk.sahan.parking;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"test.cashier.username=ticketcashier","test.cashier.password=testpassword","DATABASE_URL=jdbc:h2:mem:ticket;DB_CLOSE_DELAY=-1","PARKING_INITIAL_SLOTS=2"})
@AutoConfigureMockMvc
class EntryTicketTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;
 @Test void savedTicketIsOpaqueStableAndNeverDuplicatesEntry() throws Exception {
  var auth=httpBasic("ticketcashier","testpassword");
  long slot=json.readTree(mvc.perform(get("/api/slots").with(auth)).andReturn().getResponse().getContentAsString()).get(0).path("id").asLong();
  long id=json.readTree(mvc.perform(post("/api/sessions").with(auth).contentType("application/json").content("{\"plate\":\"TKT-123\",\"vehicleType\":\"CAR\",\"slotId\":"+slot+"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asLong();
  String first=mvc.perform(get("/api/sessions/{id}/ticket",id).with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.pricingSummary").isNotEmpty()).andReturn().getResponse().getContentAsString();
  String second=mvc.perform(get("/api/sessions/{id}/ticket",id).with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertEquals(first,second);assertDoesNotThrow(()->java.util.UUID.fromString(json.readTree(first).path("opaqueIdentifier").asText()));
  mvc.perform(get("/api/sessions").with(auth)).andExpect(jsonPath("$.length()").value(1));
  mvc.perform(get("/api/sessions/{id}/ticket",id)).andExpect(status().isUnauthorized());
 }
}
