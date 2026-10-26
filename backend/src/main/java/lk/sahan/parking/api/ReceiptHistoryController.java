package lk.sahan.parking.api;
import lk.sahan.parking.service.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
@RestController
@RequestMapping("/api")
public class ReceiptHistoryController {
 private final ParkingService parking;private final UserIdentity users;
 public ReceiptHistoryController(ParkingService parking,UserIdentity users){this.parking=parking;this.users=users;}
 @GetMapping({"/receipts","/admin/receipts"})
 public ParkingService.ReceiptPage list(Authentication auth,@RequestParam(required=false) Long branchId,
  @RequestParam(defaultValue="") String search,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
  @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){
  return parking.receipts(users.admin(auth)?branchId:users.branch(auth),search,from,to,page,size);
 }
 @GetMapping("/admin/receipts/{sessionId}")
 public ParkingService.ReceiptView receipt(@PathVariable Long sessionId){return parking.receiptBySession(sessionId,null);}
 @PostMapping({"/receipts/{sessionId}/reprints","/admin/receipts/{sessionId}/reprints"})
 public ParkingService.ReceiptView reprint(@PathVariable Long sessionId,Authentication auth){return parking.reprintReceipt(sessionId,users.admin(auth)?null:users.branch(auth));}
}
