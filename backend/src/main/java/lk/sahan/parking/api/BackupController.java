package lk.sahan.parking.api;
import lk.sahan.parking.service.*;
import lk.sahan.parking.domain.BackupJob;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
@RestController @RequestMapping("/api/admin")
public class BackupController {
 private final BackupService backups;private final MaintenanceGuard maintenance;private final AuditService audit;
 public BackupController(BackupService backups,MaintenanceGuard maintenance,AuditService audit){this.backups=backups;this.maintenance=maintenance;this.audit=audit;}
 @GetMapping("/backups") public BackupService.History list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return backups.history(page,size);}
 @PostMapping("/backups") @ResponseStatus(HttpStatus.ACCEPTED) public BackupJob start(Authentication auth){return backups.start(auth);}
 public record Restore(String confirmation){}
 @PostMapping("/backups/{id}/restore-plan") @ResponseStatus(HttpStatus.ACCEPTED) public BackupJob plan(@PathVariable Long id,@RequestBody Restore request,Authentication auth){return backups.prepareRestore(id,request.confirmation(),auth);}
 public record Maintenance(boolean enabled,String confirmation){}
 @PostMapping("/maintenance") @Transactional public Map<String,Object> maintenance(@RequestBody Maintenance request){if(!"CHANGE MAINTENANCE".equals(request.confirmation()))throw FinancialInputs.bad("Explicit CHANGE MAINTENANCE confirmation is required");boolean before=maintenance.enabled();maintenance.set(request.enabled());audit.record("MAINTENANCE_CHANGED","SERVER",null,null,Map.of("enabled",before),Map.of("enabled",request.enabled()));return Map.of("maintenance",maintenance.enabled());}
}
