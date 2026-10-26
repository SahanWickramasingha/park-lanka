package lk.sahan.parking.service;

import java.util.*;
import lk.sahan.parking.domain.*;
import lk.sahan.parking.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BranchService {
    private static final String MAIN_NAME = "Main Branch";
    private static final String NUMERIC_CODE = "\\d{3,8}";
    private final BranchRepository branches;
    private final SlotRepository slots;
    private final SessionRepository sessions;
    private final int initialSlots;
    private final EventLog events;

    public BranchService(BranchRepository branches, SlotRepository slots, SessionRepository sessions,
                         @Value("${parking.initial-slots}") int initialSlots, EventLog events) {
        this.branches = branches; this.slots = slots; this.sessions = sessions; this.initialSlots = initialSlots; this.events=events;
    }

    @Bean CommandLineRunner initializeBranches(BranchService service) {
        return args -> service.initialize();
    }

    @Transactional
    public void initialize() {
        Branch main = branches.findByCode("MAIN")
                .or(() -> branches.findByNameIgnoreCase(MAIN_NAME))
                .orElseGet(() -> branches.save(new Branch("001", MAIN_NAME)));
        normalizeCodes(main);
        // Old database rows have a null branch; link them to MAIN without changing IDs or payment history.
        for (ParkingSlot slot : slots.findAll()) if (slot.getBranch() == null) slot.setBranch(main);
        if (slots.count() == 0) {
            checkCount(initialSlots);
            for (int i = 1; i <= initialSlots; i++) slots.save(new ParkingSlot("A-%03d".formatted(i), main));
        }
    }

    public Branch main() {
        return branches.findByNameIgnoreCase(MAIN_NAME)
                .or(() -> branches.findByCode("001"))
                .orElseThrow();
    }
    public Branch require(Long id) {
        return branches.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
    }
    public List<Branch> all() {
        return branches.findAll().stream().sorted(Comparator.comparingInt(this::numericCode)
                .thenComparing(Branch::getName, String.CASE_INSENSITIVE_ORDER)).toList();
    }
    // Call inside a transaction, before taking cashier/session/slot locks.
    public Branch lock(Long id) {
        return branches.lockById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
    }
    public void requireAvailable(Branch branch) {
        if (branch.isDeleted()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Branch has been deleted");
    }

    @Transactional
    public Branch enable(Long id, boolean enabled) {
        Branch branch = lock(id);
        requireAvailable(branch);
        boolean changed=branch.isStopped()==enabled;
        branch.setStopped(!enabled);
        if(changed) events.business("Branch",enabled?"Branch started":"Branch stopped",label(branch)+(enabled?" is accepting new entries.":" has stopped new entries. Checkout remains available."),branch,null,"BRANCH",id,null,true);
        return branch;
    }

    @Transactional
    public void remove(Long id) {
        Branch branch = lock(id);
        if (isMain(branch)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Main Branch cannot be deleted");
        requireAvailable(branch);
        if (slots.findByBranchId(id).stream().anyMatch(ParkingSlot::isOccupied)
                || !sessions.findBySlotBranchIdAndExitedAtIsNullOrderByEnteredAtAsc(id).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Check out all parked vehicles and free occupied slots before deleting this branch");
        branch.archive();
        events.business("Branch","Branch deleted",label(branch)+" was archived; history is preserved.",branch,null,"BRANCH",id,null,false);
    }
    public String label(Branch branch) { return branch.getCode() + " \u2013 " + branch.getName(); }
    public boolean isMain(Branch branch) { return main().getId().equals(branch.getId()); }

    @Transactional
    public Branch create(String name, int slotCount) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.length() < 2 || normalizedName.length() > 60)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch name needs 2-60 characters");
        checkCount(slotCount);
        if (branches.existsByNameIgnoreCase(normalizedName))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Branch name already exists");
        String normalizedCode = nextCode();
        Branch branch = branches.saveAndFlush(new Branch(normalizedCode, normalizedName));
        for (int i = 1; i <= slotCount; i++) slots.save(new ParkingSlot("%s-%03d".formatted(normalizedCode, i), branch));
        events.business("Branch","Branch created",label(branch)+" · "+slotCount+" slots",branch,null,"BRANCH",branch.getId(),null,false);
        return branch;
    }

    private void normalizeCodes(Branch main) {
        Set<String> used = new HashSet<>();
        for (Branch branch : branches.findAll()) if (isNumericCode(branch.getCode())) used.add(branch.getCode());
        if (!isNumericCode(main.getCode())) {
            String code = "001";
            if (used.contains(code)) code = nextCode(used);
            main.setCode(code); used.add(code);
        }
        branches.findAll().stream()
                .filter(branch -> !branch.getId().equals(main.getId()))
                .filter(branch -> !isNumericCode(branch.getCode()))
                .sorted(Comparator.comparing(Branch::getCode, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Branch::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(branch -> {
                    String code = nextCode(used);
                    branch.setCode(code);
                    used.add(code);
                });
    }

    private String nextCode() {
        Set<String> used = new HashSet<>();
        for (Branch branch : branches.findAll()) if (isNumericCode(branch.getCode())) used.add(branch.getCode());
        return nextCode(used);
    }

    private String nextCode(Set<String> used) {
        for (int value = 1; value <= 99_999_999; value++) {
            String code = "%03d".formatted(value);
            if (!used.contains(code)) return code;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "No branch codes available");
    }

    private boolean isNumericCode(String code) { return code != null && code.matches(NUMERIC_CODE); }
    private int numericCode(Branch branch) {
        return isNumericCode(branch.getCode()) ? Integer.parseInt(branch.getCode()) : Integer.MAX_VALUE;
    }

    private void checkCount(int count) {
        if (count < 1 || count > 1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot count must be between 1 and 1000");
    }
}
