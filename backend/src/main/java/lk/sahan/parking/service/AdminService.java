package lk.sahan.parking.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lk.sahan.parking.domain.*;
import lk.sahan.parking.repo.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class AdminService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Colombo");
    private final BranchService branches;
    private final BranchRepository branchRepo;
    private final CashierRepository cashierRepo;
    private final SlotRepository slots;
    private final SessionRepository sessions;
    private final PaymentRepository payments;
    private final CorrectionService corrections;

    public AdminService(BranchService branches, BranchRepository branchRepo,
                        CashierRepository cashierRepo, SlotRepository slots, SessionRepository sessions,
                        PaymentRepository payments,CorrectionService corrections) {
        this.branches = branches; this.branchRepo = branchRepo;
        this.cashierRepo = cashierRepo; this.slots = slots; this.sessions = sessions; this.payments = payments;
        this.corrections=corrections;
    }

    public record BranchView(Long id, String code, String name, int totalSlots, int occupied,
                             long cashiers, long activeVehicles, long todayPayments,
                             BigDecimal todayRevenue, long totalPayments, String status, boolean main) {}

    public List<BranchView> overview() { return overview(false); }
    public List<BranchView> overview(boolean includeDeleted) {
        LocalDate date = LocalDate.now(ZONE);
        Instant start = date.atStartOfDay(ZONE).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(ZONE).toInstant();
        Long mainId = branches.main().getId();
        return branches.all().stream().filter(b -> includeDeleted || !b.isDeleted()).map(b -> {
            var branchSlots = slots.findByBranchId(b.getId());
            var active = sessions.findBySlotBranchIdAndExitedAtIsNullOrderByEnteredAtAsc(b.getId());
            var sales = payments.findBySessionSlotBranchIdAndPaidAtGreaterThanEqualAndPaidAtLessThan(b.getId(), start, end);
            BigDecimal revenue = sales.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            var changes=corrections.totals(b.getId(),start,end);revenue=revenue.subtract(changes.refunds()).subtract(changes.voids());
            long staff = cashierRepo.countByBranchIdAndDeletedFalse(b.getId());
            return new BranchView(b.getId(), b.getCode(), b.getName(), branchSlots.size(),
                    (int) branchSlots.stream().filter(ParkingSlot::isOccupied).count(), staff, active.size(),
                    sales.size(), revenue.setScale(2), payments.countBySessionSlotBranchId(b.getId()), b.getStatus(), b.getId().equals(mainId));
        }).toList();
    }

    public record RecordPage(List<Map<String, Object>> rows, long total, int page, int pages) {}

    public RecordPage records(String table, Long branchId, int page, int size) {
        return records(table, branchId, page, size, null, null);
    }
    public RecordPage records(String table, Long branchId, int page, int size, LocalDate from, LocalDate to) {
        if (table == null || !Set.of("branches", "cashiers", "slots", "sessions", "payments").contains(table.toLowerCase(Locale.ROOT)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose branches, cashiers, slots, sessions, or payments");
        if ((from != null || to != null) && !Set.of("sessions", "payments").contains(table.toLowerCase(Locale.ROOT)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date filters apply only to Sessions and Payments");
        if (from != null && to != null && from.isAfter(to))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "From date must be on or before To date");
        if (page < 0 || size < 1 || size > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be >= 0 and size must be 1..100");
        if (branchId != null) branches.require(branchId);
        if (table.equalsIgnoreCase("branches") && branchId != null) {
            Branch b = branches.require(branchId);
            return new RecordPage(page == 0 ? List.of(branchRow(b))
                    : List.of(), 1, page, 1);
        }
        Pageable request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        Page<Map<String, Object>> found = switch (table.toLowerCase(Locale.ROOT)) {
            case "branches" -> branchRepo.findAll(request).map(this::branchRow);
            case "cashiers" -> branchId == null
                    ? cashierRepo.findAll(request).map(this::cashierRow)
                    : cashierRepo.findByBranchId(branchId, request).map(this::cashierRow);
            case "slots" -> branchId == null
                    ? slots.findAll(request).map(this::slotRow)
                    : slots.findByBranchId(branchId, request).map(this::slotRow);
            case "sessions" -> sessions.findAll(sessionFilter(branchId, from, to), request).map(this::sessionRow);
            case "payments" -> payments.findAll(paymentFilter(branchId, from, to), request).map(this::paymentRow);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose branches, cashiers, slots, sessions, or payments");
        };
        return new RecordPage(found.getContent(), found.getTotalElements(), page, found.getTotalPages());
    }

    private Specification<ParkingSession> sessionFilter(Long branchId, LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            var clauses = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (branchId != null) clauses.add(cb.equal(root.get("slot").get("branch").get("id"), branchId));
            if (from != null) clauses.add(cb.greaterThanOrEqualTo(root.get("enteredAt"), from.atStartOfDay(ZONE).toInstant()));
            if (to != null) clauses.add(cb.lessThan(root.get("enteredAt"), to.plusDays(1).atStartOfDay(ZONE).toInstant()));
            return cb.and(clauses.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }
    private Specification<Payment> paymentFilter(Long branchId, LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            var clauses = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (branchId != null) clauses.add(cb.equal(root.get("session").get("slot").get("branch").get("id"), branchId));
            if (from != null) clauses.add(cb.greaterThanOrEqualTo(root.get("paidAt"), from.atStartOfDay(ZONE).toInstant()));
            if (to != null) clauses.add(cb.lessThan(root.get("paidAt"), to.plusDays(1).atStartOfDay(ZONE).toInstant()));
            return cb.and(clauses.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }
    private Map<String, Object> cashierRow(CashierAccount c) {
        return map("id", c.getId(), "username", c.getUsername(), "branch", branches.label(c.getBranch()),
                "status", c.getStatus(), "branchStatus", c.getBranch().getStatus());
    }
    private Map<String, Object> branchRow(Branch b) {
        return map("id", b.getId(), "code", b.getCode(), "name", b.getName(), "status", b.getStatus());
    }
    private Map<String, Object> slotRow(ParkingSlot s) {
        return map("id", s.getId(), "code", s.getCode(), "branch", branchCode(s),
                "branchStatus", branchStatus(s), "occupied", s.isOccupied());
    }
    private Map<String, Object> sessionRow(ParkingSession s) {
        return map("id", s.getId(), "plate", s.getPlate(), "type", s.getVehicleType(),
                "branch", branchCode(s.getSlot()), "branchStatus", branchStatus(s.getSlot()), "slot", s.getSlot().getCode(), "enteredAt", s.getEnteredAt(),
                "exitedAt", s.getExitedAt(), "entryRate", s.getRatePerMinute(), "enteredBy", s.getEnteredBy(),"policyRate",s.getPolicy()==null?null:s.getPolicy().rate(),"policyUnit",s.getPolicy()==null?null:s.getPolicy().unit(),"graceMinutes",s.getPolicy()==null?null:s.getPolicy().graceMinutes(),"dailyCap",s.getPolicy()==null?null:s.getPolicy().dailyCap());
    }
    private Map<String, Object> paymentRow(Payment p) {
        return map("id", p.getId(), "ticket", p.getSession().getId(), "plate", p.getSession().getPlate(),
                "branch", branchCode(p.getSession().getSlot()), "branchStatus", branchStatus(p.getSession().getSlot()), "amount", p.getAmount(),
                "method", p.getMethod(), "paidAt", p.getPaidAt(), "cashier", p.getCashierUsername(),"reference",p.getReference(),"cashTendered",p.getCashTendered(),"changeDue",p.getChangeDue(),"shiftId",p.getShift()==null?null:p.getShift().id);
    }
    private String branchCode(ParkingSlot slot) { return slot.getBranch() == null ? branches.label(branches.main()) : branches.label(slot.getBranch()); }
    private String branchStatus(ParkingSlot slot) { return slot.getBranch() == null ? branches.main().getStatus() : slot.getBranch().getStatus(); }
    private Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
