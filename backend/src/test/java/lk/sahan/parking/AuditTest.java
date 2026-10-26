package lk.sahan.parking;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import lk.sahan.parking.service.*;
import lk.sahan.parking.repo.AuditRepository;
import java.util.Map;
import com.fasterxml.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"test.cashier.username=auditcashier","test.cashier.password=audit-password","PARKING_ADMIN_USER=auditor","PARKING_ADMIN_PASSWORD=admin-password","DATABASE_URL=jdbc:h2:mem:audittest;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class AuditTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired AuditRepository records;@Autowired BranchService branches;@Autowired AuditService audit;@Autowired PlatformTransactionManager transactions;
 @Test void authorizedFilteredImmutableAuditHasNoSecretsAndRollsBackWithBusinessAction()throws Exception{
  var admin=httpBasic("auditor","admin-password");
  long branch=json.readTree(mvc.perform(post("/api/admin/branches").with(admin).contentType("application/json").content("{\"name\":\"Audit branch\",\"slots\":1}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asLong();
  mvc.perform(put("/api/rates").with(admin).contentType("application/json").content("{\"perMinute\":2.00}")).andExpect(status().isOk());
  String result=mvc.perform(get("/api/admin/audit").with(admin).param("actor","auditor").param("action","GLOBAL_RATE_CHANGED").param("size","1")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andReturn().getResponse().getContentAsString();
  assertTrue(result.contains("perMinute"));assertFalse(result.contains("admin-password"));assertFalse(result.contains("passwordHash"));assertFalse(result.contains("Authorization"));
  long before=records.count();new TransactionTemplate(transactions).executeWithoutResult(tx->{branches.create("Rolled back audit",1);tx.setRollbackOnly();});assertEquals(before,records.count());
  assertThrows(IllegalArgumentException.class,()->new TransactionTemplate(transactions).executeWithoutResult(tx->audit.record("TEST","TEST:1",branch,null,null,Map.of("passwordHash","must-not-log"))));assertEquals(before,records.count());
  mvc.perform(delete("/api/admin/branches/{id}",branch).with(admin)).andExpect(status().isOk());
  mvc.perform(get("/api/admin/audit").with(admin).param("branchId",Long.toString(branch))).andExpect(jsonPath("$.total").value(2));
  mvc.perform(get("/api/admin/audit").with(httpBasic("auditcashier","audit-password"))).andExpect(status().isForbidden());
  mvc.perform(delete("/api/admin/audit/1").with(admin)).andExpect(status().isNotFound());
  mvc.perform(put("/api/admin/audit/1").with(admin).contentType("application/json").content("{}")).andExpect(status().isNotFound());
 }
}
