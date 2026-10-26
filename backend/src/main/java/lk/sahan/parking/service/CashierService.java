package lk.sahan.parking.service;

import java.util.*;
import lk.sahan.parking.domain.*;
import lk.sahan.parking.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CashierService {
    private final CashierRepository cashiers;
    private final BranchService branches;
    private final PasswordEncoder encoder;
    private final String adminUser;
    private final EventLog events;

    public CashierService(CashierRepository cashiers, BranchService branches, PasswordEncoder encoder,
                          @Value("${parking.admin-username}") String adminUser, EventLog events) {
        this.cashiers = cashiers; this.branches = branches; this.encoder = encoder;
        this.adminUser = adminUser;
        this.events=events;
    }

    public Long branchFor(Authentication auth) {
        CashierAccount account = cashiers.findByUsername(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Cashier account not found"));
        if (!account.canAccess()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cashier stopped, removed, or branch deleted");
        return account.getBranch().getId();
    }

    public record CashierView(Long id, String username, Long branchId, String branch, boolean enabled, boolean bootstrap,
                              String status, boolean branchDeleted) {}
    public CashierView view(CashierAccount c) {
        return new CashierView(c.getId(), c.getUsername(), c.getBranch().getId(), branches.label(c.getBranch()),
                c.isEnabled(), false, c.getStatus(), c.getBranch().isDeleted());
    }
    public List<CashierView> all() {
        List<CashierView> result = new ArrayList<>();
        cashiers.findAll().stream().filter(c -> !c.isDeleted()).sorted(Comparator.comparing(CashierAccount::getUsername))
                .map(this::view).forEach(result::add);
        return result;
    }

    @Transactional
    public CashierView create(String username, String password, Long branchId) {
        String name = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z][a-z0-9_.-]{2,31}") || name.equalsIgnoreCase(adminUser))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username needs 3-32 characters and must be unique");
        validatePassword(password);
        if (cashiers.findByUsername(name).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        if (branchId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch is required");
        Branch branch = branches.lock(branchId);
        branches.requireAvailable(branch);
        CashierAccount account=cashiers.saveAndFlush(new CashierAccount(name, encoder.encode(password), branch));
        events.business("Cashier","Cashier created",name+" assigned to "+branches.label(branch),branch,name,"CASHIER",account.getId(),null,false);
        return view(account);
    }

    @Transactional
    public CashierView enable(Long id, boolean enabled) {
        CashierAccount account = lockAccount(id);
        if (enabled) branches.requireAvailable(account.getBranch());
        boolean changed=account.isEnabled()!=enabled;
        account.setEnabled(enabled);
        if(changed) events.business("Cashier",enabled?"Cashier started":"Cashier stopped",account.getUsername()+" · "+branches.label(account.getBranch()),account.getBranch(),account.getUsername(),"CASHIER",id,null,false);
        return view(account);
    }

    @Transactional
    public void resetPassword(Long id, String password) {
        CashierAccount account = lockAccount(id);
        branches.requireAvailable(account.getBranch());
        validatePassword(password);
        account.setPasswordHash(encoder.encode(password));
        events.business("Cashier","Cashier access reset",account.getUsername()+" access was reset by an administrator.",account.getBranch(),account.getUsername(),"CASHIER",id,null,false);
    }

    @Transactional
    public void remove(Long id) { CashierAccount account=lockAccount(id); account.archive(); events.business("Cashier","Cashier removed",account.getUsername()+" was archived; attribution is preserved.",account.getBranch(),account.getUsername(),"CASHIER",id,null,false); }

    private CashierAccount lockAccount(Long id) {
        if (id == null || id <= 0L)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cashier account not found");
        Long branchId = cashiers.branchIdFor(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cashier not found"));
        branches.lock(branchId);
        CashierAccount account = cashiers.lockById(id).orElseThrow();
        if (account.isDeleted()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cashier has been removed; username remains reserved");
        return account;
    }

    // Mutating parking operations hold the branch lock before rechecking the account.
    // Management takes the same lock, so revocation cannot race a queued entry/payment.
    public void checkAccess(String username, Long branchId) {
        CashierAccount account = cashiers.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Cashier not found"));
        if (!account.canAccess() || !account.getBranch().getId().equals(branchId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cashier stopped, removed, or branch deleted");
    }

    private void validatePassword(String value) {
        if (value == null || value.length() < 10 || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must have at least 10 characters and at most 72 UTF-8 bytes");
    }
}
