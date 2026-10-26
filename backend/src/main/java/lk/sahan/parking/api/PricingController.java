package lk.sahan.parking.api;
import lk.sahan.parking.service.PricingService;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/admin/pricing/{branchId}")
public class PricingController {
 private final PricingService pricing;public PricingController(PricingService pricing){this.pricing=pricing;}
 @GetMapping public List<PricingService.View> list(@PathVariable Long branchId){return pricing.list(branchId);}
 @PutMapping public PricingService.View save(@PathVariable Long branchId,@RequestBody PricingService.Request request){return pricing.save(branchId,request);}
 @DeleteMapping("/{vehicleType}") public Map<String,String> remove(@PathVariable Long branchId,@PathVariable String vehicleType){pricing.remove(branchId,vehicleType);return Map.of("status","Removed; new entries use fallback");}
}
