package lk.sahan.parking.api;
import lk.sahan.parking.service.AuditService;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
@RestController @RequestMapping("/api/admin/audit")
public class AuditController {
 private final AuditService audit;public AuditController(AuditService audit){this.audit=audit;}
 @GetMapping public AuditService.PageView list(@RequestParam(required=false) Long branchId,@RequestParam(required=false) String actor,@RequestParam(required=false) String action,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return audit.list(branchId,actor,action,from,to,page,size);}
}
