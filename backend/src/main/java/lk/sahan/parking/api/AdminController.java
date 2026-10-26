package lk.sahan.parking.api;

import java.util.*;
import lk.sahan.parking.service.*;
import lk.sahan.parking.service.AdminService.*;
import lk.sahan.parking.service.CashierService.CashierView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final BranchService branches;
    private final CashierService cashiers;
    private final AdminService dashboard;
    private final LegacyCashierService legacy;
    public AdminController(BranchService branches, CashierService cashiers, AdminService dashboard, LegacyCashierService legacy) {
        this.branches = branches; this.cashiers = cashiers; this.dashboard = dashboard; this.legacy = legacy;
    }

    public record BranchRequest(String name, int slots) {}
    public record CashierRequest(String username, String password, Long branchId) {}
    public record LegacyRequest(String legacyUsername, String confirmation) {}
    @PutMapping("/cashiers/{id}/legacy-identity") public Map<String,String> linkLegacy(@PathVariable Long id, @RequestBody LegacyRequest request) {
        legacy.link(id, request.legacyUsername(), request.confirmation());
        return Map.of("status", "Linked");
    }

    public record EnabledRequest(boolean enabled) {}
    public record PasswordRequest(String password) {}

    @GetMapping("/branches") public List<BranchView> overview(@RequestParam(defaultValue = "false") boolean includeDeleted) {
        return dashboard.overview(includeDeleted);
    }
    @PutMapping("/branches/{id}/enabled") public Map<String, String> enableBranch(@PathVariable Long id, @RequestBody EnabledRequest request) {
        if (request == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST);
        return Map.of("status", branches.enable(id, request.enabled()).getStatus());
    }
    @DeleteMapping("/branches/{id}") public Map<String, String> deleteBranch(@PathVariable Long id) {
        branches.remove(id);
        return Map.of("status", "Deleted");
    }
    @PostMapping("/branches") @ResponseStatus(HttpStatus.CREATED)
    public BranchView createBranch(@RequestBody BranchRequest request) {
        if (request == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST);
        var added = branches.create(request.name(), request.slots());
        return dashboard.overview().stream().filter(b -> b.id().equals(added.getId())).findFirst().orElseThrow();
    }
    @GetMapping("/cashiers") public List<CashierView> cashiers() { return cashiers.all(); }
    @PostMapping("/cashiers") @ResponseStatus(HttpStatus.CREATED)
    public CashierView createCashier(@RequestBody CashierRequest request) {
        if (request == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST);
        return cashiers.create(request.username(), request.password(), request.branchId());
    }
    @PutMapping("/cashiers/{id}/enabled") public CashierView enableCashier(@PathVariable Long id, @RequestBody EnabledRequest request) {
        if (request == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST);
        return cashiers.enable(id, request.enabled());
    }
    @PutMapping("/cashiers/{id}/password") public Map<String, String> password(@PathVariable Long id, @RequestBody PasswordRequest request) {
        cashiers.resetPassword(id, request == null ? null : request.password());
        return Map.of("status", "updated");
    }
    @DeleteMapping("/cashiers/{id}") public Map<String, String> removeCashier(@PathVariable Long id) {
        cashiers.remove(id);
        return Map.of("status", "Removed");
    }
    @GetMapping("/records") public RecordPage records(@RequestParam String table,
            @RequestParam(required = false) Long branchId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) java.time.LocalDate from, @RequestParam(required = false) java.time.LocalDate to) {
        return dashboard.records(table, branchId, page, size, from, to);
    }
}
