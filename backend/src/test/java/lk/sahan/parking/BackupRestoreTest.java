package lk.sahan.parking;
import lk.sahan.parking.repo.BackupRepository;
import lk.sahan.parking.domain.BackupJob;
import lk.sahan.parking.maintenance.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"test.cashier.username=backupcashier","test.cashier.password=backup-password","PARKING_ADMIN_USER=backupadmin","PARKING_ADMIN_PASSWORD=admin-password"})
@AutoConfigureMockMvc
class BackupRestoreTest {
 static final Path ROOT=Path.of("target","backup-test-runs",UUID.randomUUID().toString()).toAbsolutePath();
 @DynamicPropertySource static void properties(DynamicPropertyRegistry properties){try{Files.createDirectories(ROOT);}catch(Exception e){throw new RuntimeException(e);}properties.add("spring.datasource.url",()->"jdbc:h2:file:"+ROOT.resolve("operational-test-only").toString().replace('\\','/'));properties.add("parking.backup.directory",()->ROOT.resolve("backups").toString());}
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired BackupRepository jobs;
 BackupJob finish(long id)throws Exception{long limit=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);while(System.nanoTime()<limit){BackupJob job=jobs.findById(id).orElseThrow();if(!job.status.equals("RUNNING")){assertNotEquals("FAILED",job.status,job.failure);return job;}Thread.sleep(100);}throw new AssertionError("Backup job timed out");}
 @Test void consistentBackupApprovedOfflineRestoreAndMaintenancePreserveOriginal()throws Exception{
  var admin=httpBasic("backupadmin","admin-password");var cashier=httpBasic("backupcashier","backup-password");
  long branch=json.readTree(mvc.perform(post("/api/admin/branches").with(admin).contentType("application/json").content("{\"name\":\"Before backup\",\"slots\":1}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asLong();
  var conversation=Map.of("requestKey",UUID.randomUUID().toString(),"subject","Recovery history","category","GENERAL","priority","NORMAL","body","Preserve this historical message");
  mvc.perform(post("/api/conversations").with(cashier).contentType("application/json").content(json.writeValueAsString(conversation))).andExpect(status().isOk());
  mvc.perform(post("/api/admin/backups").with(cashier)).andExpect(status().isForbidden());
  long id=json.readTree(mvc.perform(post("/api/admin/backups").with(admin)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("id").asLong();BackupJob backup=finish(id);Path archive=ROOT.resolve("backups").resolve(backup.fileName);assertEquals("H2",BackupArchive.inspect(archive).engine());
  mvc.perform(post("/api/admin/branches").with(admin).contentType("application/json").content("{\"name\":\"After backup\",\"slots\":1}")).andExpect(status().isCreated());
  mvc.perform(post("/api/admin/backups/{id}/restore-plan",id).with(admin).contentType("application/json").content("{\"confirmation\":\"PREPARE RESTORE "+id+"\"}")).andExpect(status().isConflict());
  FinancialTestSupport.openShift(mvc,"backupcashier","backup-password");
  mvc.perform(post("/api/admin/maintenance").with(admin).contentType("application/json").content("{\"enabled\":true,\"confirmation\":\"CHANGE MAINTENANCE\"}")).andExpect(status().isConflict());
  long shift=json.readTree(mvc.perform(get("/api/shifts/current").with(cashier)).andReturn().getResponse().getContentAsString()).path("shift").path("id").asLong();
  mvc.perform(post("/api/shifts/{id}/close",shift).with(cashier).contentType("application/json").content(json.writeValueAsString(Map.of("requestKey",UUID.randomUUID().toString(),"countedCash","0.00","reason","")))).andExpect(status().isOk());
  mvc.perform(post("/api/admin/maintenance").with(admin).contentType("application/json").content("{\"enabled\":true,\"confirmation\":\"CHANGE MAINTENANCE\"}")).andExpect(status().isOk());
  mvc.perform(post("/api/admin/branches").with(admin).contentType("application/json").content("{\"name\":\"Blocked during restore\",\"slots\":1}")).andExpect(status().isServiceUnavailable());
  long prepared=json.readTree(mvc.perform(post("/api/admin/backups/{id}/restore-plan",id).with(admin).contentType("application/json").content("{\"confirmation\":\"PREPARE RESTORE "+id+"\"}")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("id").asLong();BackupJob ready=finish(prepared);assertEquals("RESTORE_READY",ready.status);
  Path plan=ROOT.resolve("backups").resolve(ready.restorePlan),target=ROOT.resolve("restored-copy.mv.db");assertThrows(Exception.class,()->BackupRestore.restoreH2(plan,target,"WRONG"));
  BackupRestore.restoreH2(plan,target,"RESTORE_STOPPED_SERVER");assertThrows(Exception.class,()->BackupRestore.restoreH2(plan,target,"RESTORE_STOPPED_SERVER"));
  try(Connection c=DriverManager.getConnection("jdbc:h2:file:"+ROOT.resolve("restored-copy").toString().replace('\\','/'),"sa","");Statement s=c.createStatement()){
   try(ResultSet rows=s.executeQuery("select count(*) from branches where id="+branch+" and name='Before backup'")){rows.next();assertEquals(1,rows.getInt(1));}
   try(ResultSet rows=s.executeQuery("select count(*) from branches where name='After backup'")){rows.next();assertEquals(0,rows.getInt(1));}
   try(ResultSet rows=s.executeQuery("select count(*) from conversation_posts where body='Preserve this historical message'")){rows.next();assertEquals(1,rows.getInt(1));}
  }
  // The still-running disposable source was never overwritten by restore.
  mvc.perform(get("/api/admin/branches").with(admin)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("After backup")));
  Path corrupt=ROOT.resolve("corrupt.zip");Files.writeString(corrupt,"not a supported backup");assertThrows(Exception.class,()->BackupArchive.inspect(corrupt));
 }
}
