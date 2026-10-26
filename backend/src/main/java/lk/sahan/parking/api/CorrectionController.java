package lk.sahan.parking.api;
import lk.sahan.parking.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import java.time.LocalDate;
import java.util.List;
@RestController @RequestMapping("/api/admin")
public class CorrectionController {
 private final CorrectionService corrections;private final ParkingService parking;
 public CorrectionController(CorrectionService corrections,ParkingService parking){this.corrections=corrections;this.parking=parking;}
 @PostMapping("/payments/{id}/corrections") public CorrectionService.View correct(@PathVariable Long id,@RequestBody CorrectionService.Request request,Authentication auth){return corrections.correct(id,request,auth);}
 @GetMapping("/payments/{id}/refund-shifts") public List<ShiftService.View> shifts(@PathVariable Long id,Authentication auth){return corrections.refundShifts(id,auth);}
 @GetMapping("/payments/{id}") public ParkingService.ReceiptView payment(@PathVariable Long id){return parking.receiptByPayment(id);}
 @GetMapping("/correction-attempts/{key}") public CorrectionService.View status(@PathVariable String key,Authentication auth){return corrections.status(key,auth);}
 @GetMapping("/corrections") public CorrectionService.PageView list(@RequestParam(required=false) Long branchId,@RequestParam(required=false) Long paymentId,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return corrections.list(branchId,paymentId,from,to,page,size);}
}
