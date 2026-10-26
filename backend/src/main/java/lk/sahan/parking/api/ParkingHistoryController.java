package lk.sahan.parking.api;

import lk.sahan.parking.service.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.*;

/** History always derives its scope from the authenticated cashier, never a client branch parameter. */
@RestController @RequestMapping("/api/parking-history")
public class ParkingHistoryController {
 private final ParkingService parking;private final UserIdentity users;
 public ParkingHistoryController(ParkingService parking,UserIdentity users){this.parking=parking;this.users=users;}
 private LocalDate today(){return LocalDate.now(ZoneId.of("Asia/Colombo"));}
 @GetMapping("/entries") public ParkingService.EntryHistoryPage entries(Authentication auth,
  @RequestParam(defaultValue="") String search,@RequestParam(required=false) String cashier,
  @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
  @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){
  if(from==null&&to==null){from=today();to=from;}return parking.entryHistory(users.branch(auth),search,cashier,from,to,page,size);
 }
 @GetMapping("/entries/{id}") public ParkingService.EntryHistoryView entry(@PathVariable Long id,Authentication auth){return parking.entryHistoryDetails(id,users.branch(auth));}
 @GetMapping("/checkouts") public ParkingService.ReceiptPage checkouts(Authentication auth,
  @RequestParam(defaultValue="") String search,@RequestParam(required=false) String cashier,
  @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
  @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){
  if(from==null&&to==null){from=today();to=from;}return parking.receipts(users.branch(auth),search,cashier,from,to,page,size);
 }
}
