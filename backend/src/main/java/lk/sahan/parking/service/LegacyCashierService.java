package lk.sahan.parking.service;

import lk.sahan.parking.repo.CashierRepository;
import lk.sahan.parking.domain.CashierAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Administrator-only, explicit binding; history is never assigned just by creating a name. */
@Service
public class LegacyCashierService {
    private final CashierRepository accounts;
    private final BranchService branches;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    public LegacyCashierService(CashierRepository accounts, BranchService branches, JdbcTemplate jdbc, AuditService audit) {
        this.accounts=accounts; this.branches=branches; this.jdbc=jdbc; this.audit=audit;
    }
    private static final Map<String,String> KEYS=Map.of("broadcast_recipients","recipient_key", "conversations","owner_key",
            "cashier_shifts","owner_key", "conversation_posts","sender_key", "conversation_reads","reader_key",
            "conversation_post_reads","reader_key", "notices","target_key", "notice_reads","reader_key");
    @Transactional
    public void link(Long id, String legacyUsername, String confirmation) {
        if (legacyUsername==null || !Objects.equals(confirmation,"LINK LEGACY "+legacyUsername)) throw bad("Type LINK LEGACY followed by the exact legacy username");
        var main=branches.lock(branches.main().getId());
        CashierAccount account=accounts.lockById(id).orElseThrow(()->bad("Cashier not found"));
        if (!account.canAccess() || !account.getBranch().getId().equals(main.getId()) || !account.getUsername().equals(legacyUsername))
            throw bad("Link only the same legacy username in Main Branch, after verifying who owns that account");
        String key="bootstrap:"+legacyUsername;
        if (account.getLegacyOwnerKey()!=null) {
            if (account.getLegacyOwnerKey().equals(key)) return;
            throw bad("This account already has a legacy identity");
        }
        if (accounts.findAll().stream().anyMatch(a->key.equals(a.getLegacyOwnerKey()))) throw bad("Legacy identity already belongs to an account");
        long oldRecords=0, newRecords=0;
        for (var entry:KEYS.entrySet()) {
            oldRecords+=jdbc.queryForObject("select count(*) from "+entry.getKey()+" where "+entry.getValue()+"=?",Long.class,key);
            newRecords+=jdbc.queryForObject("select count(*) from "+entry.getKey()+" where "+entry.getValue()+"=?",Long.class,account.ownerKey());
        }
        if (oldRecords==0) throw bad("No legacy private history found for this exact identity; no link is necessary");
        if (newRecords!=0) throw bad("This database account already has private history. A reviewed reconciliation is required before linking");
        for (String table:List.of("conversations","cashier_shifts")) {
            Long inconsistent=jdbc.queryForObject("select count(*) from "+table+" where owner_key=? and (cashier<>? or branch_id<>?)",Long.class,key,legacyUsername,main.getId());
            if (inconsistent!=0) throw bad("Historical owner/branch conflict; review before linking");
        }
        if (jdbc.queryForObject("select count(*) from broadcast_recipients where recipient_key=? and (username<>? or branch_id<>?)",Long.class,key,legacyUsername,main.getId())!=0)
            throw bad("Historical recipient conflict; review before linking");
        account.linkLegacyOwner(key);
        audit.record("LEGACY_CASHIER_LINKED","CASHIER:"+id,main.getId(),"Explicit same-person legacy ownership confirmation",null,Map.of("username",legacyUsername,"ownerKey",key));
    }
    private ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
