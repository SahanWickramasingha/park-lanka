package lk.sahan.parking.api;
import lk.sahan.parking.service.ShiftService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
@RestController @RequestMapping("/api")
public class ShiftController {
 private final ShiftService shifts;public ShiftController(ShiftService shifts){this.shifts=shifts;}
 @GetMapping("/shifts/current") public ShiftService.Current current(Authentication auth){return shifts.current(auth);}
 @PostMapping("/shifts") public ShiftService.View open(@RequestBody ShiftService.Open request,Authentication auth){return shifts.open(request,auth);}
 @PostMapping({"/shifts/{id}/close","/admin/shifts/{id}/close"}) public ShiftService.View close(@PathVariable Long id,@RequestBody ShiftService.Close request,Authentication auth){return shifts.close(id,request,auth);}
 @GetMapping({"/shifts/{id}","/admin/shifts/{id}"}) public ShiftService.View get(@PathVariable Long id,Authentication auth){return shifts.get(id,auth);}
 @GetMapping({"/shifts","/admin/shifts"}) public ShiftService.PageView list(Authentication auth,@RequestParam(required=false) Long branchId,@RequestParam(required=false) String cashier,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return shifts.list(auth,branchId,cashier,from,to,page,size);}
}
