package lk.sahan.parking.api;

import lk.sahan.parking.service.ParkingService;
import lk.sahan.parking.service.RateService;
import lk.sahan.parking.service.CashierService;
import lk.sahan.parking.service.BranchService;
import lk.sahan.parking.service.ParkingService.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ParkingController {
    private final ParkingService service;
    private final RateService rates;
    private final CashierService cashiers;
    private final BranchService branches;
    public ParkingController(ParkingService service, RateService rates, CashierService cashiers, BranchService branches) {
        this.service = service; this.rates = rates; this.cashiers = cashiers; this.branches = branches;
    }
    @GetMapping("/me") public Map<String, Object> me(Authentication auth) {
        if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")))
            return Map.of("role", "ADMIN");
        Long branchId = cashiers.branchFor(auth);
        return Map.of("role", "CASHIER", "branchId", branchId, "branchName", branches.label(branches.require(branchId)));
    }
    public record RateView(BigDecimal perMinute, String currency) {}
    public record RateRequest(BigDecimal perMinute) {}
    @GetMapping("/rates") public RateView rate() { return new RateView(rates.current(), "LKR"); }
    @PutMapping("/rates") public RateView updateRate(@RequestBody RateRequest request) {
        return new RateView(rates.update(request == null ? null : request.perMinute()), "LKR");
    }
    @GetMapping("/slots") public List<SlotView> slots(Authentication auth) { return service.allSlots(cashiers.branchFor(auth)); }
    @GetMapping("/sessions") public List<SessionView> sessions(Authentication auth) { return service.activeSessions(cashiers.branchFor(auth)); }
    @PostMapping("/sessions") @ResponseStatus(HttpStatus.CREATED)
    public SessionView enter(@RequestBody EntryRequest request, Authentication auth) {
        return service.enter(request, cashiers.branchFor(auth), auth.getName());
    }
    @GetMapping("/sessions/{id}/quote") public QuoteView quote(@PathVariable Long id, Authentication auth) {
        return service.quote(id, cashiers.branchFor(auth),auth.getName());
    }
    @GetMapping("/sessions/{id}/ticket") public TicketView ticket(@PathVariable Long id,Authentication auth) {
        return service.ticket(id,cashiers.branchFor(auth));
    }
    @PostMapping("/sessions/{id}/ticket/reprints") public TicketView reprintTicket(@PathVariable Long id,Authentication auth){return service.reprintTicket(id,cashiers.branchFor(auth));}
    @PostMapping("/sessions/{id}/checkout") public ReceiptView checkout(@PathVariable Long id,
            @RequestBody CheckoutRequest request, Authentication auth) {
        return service.checkout(id, request, cashiers.branchFor(auth), auth.getName());
    }
    @GetMapping("/receipts/{sessionId}") public ReceiptView receipt(@PathVariable Long sessionId, Authentication auth) {
        return service.receiptBySession(sessionId, cashiers.branchFor(auth));
    }
    @GetMapping("/reports/today") public ReportView report(Authentication auth) { return service.today(cashiers.branchFor(auth)); }
    @GetMapping("/checkouts/{key}") public ReceiptView checkoutStatus(@PathVariable String key,Authentication auth){return service.checkoutStatus(key,cashiers.branchFor(auth),auth.getName());}
}
