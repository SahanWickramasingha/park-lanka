package lk.sahan.parking.service;
import lk.sahan.parking.domain.Branch;
import lk.sahan.parking.repo.CashierRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
@Service
public class UserIdentity {
    private final CashierService cashiers; private final CashierRepository accounts;
    public UserIdentity(CashierService cashiers,CashierRepository accounts) { this.cashiers=cashiers; this.accounts=accounts; }
    public boolean admin(Authentication auth) { return auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN")); }
    public String key(Authentication auth) { return admin(auth)?"admin:"+auth.getName():cashier(auth.getName()).key(); }
    public record Owner(String key,String username,Branch branch) {}
    public Owner cashier(String username) {
        var account=accounts.findByUsername(username).filter(a->a.canAccess()).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Cashier is stopped, removed or branch deleted"));
        return new Owner(account.ownerKey(),account.getUsername(),account.getBranch());
    }
    public Long branch(Authentication auth) { return cashiers.branchFor(auth); }
}
