package lk.sahan.parking.service;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import lk.sahan.parking.domain.*;
import lk.sahan.parking.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class BroadcastService {
    private final BroadcastRepository broadcasts;
    private final BroadcastRecipientRepository recipients;
    private final CashierRepository accounts;
    private final BranchService branches;
    private final CashierService cashiers;
    private final Clock clock;
    private final String admin;
    private final EventLog events;
    @PersistenceContext private EntityManager entityManager;
    public BroadcastService(BroadcastRepository broadcasts, BroadcastRecipientRepository recipients,
                            CashierRepository accounts, BranchService branches, CashierService cashiers, Clock clock,
                            @Value("${parking.admin-username}") String admin, EventLog events) {
        this.broadcasts = broadcasts; this.recipients = recipients; this.accounts = accounts;
        this.branches = branches; this.cashiers = cashiers; this.clock = clock; this.admin = admin;
        this.events=events;
    }
    public record SendRequest(String requestKey, String title, String body, String priority, boolean allBranches, Set<Long> branchIds, Instant expiresAt) {}
    public record BroadcastView(Long id, String title, String body, String sender, String priority, String audience,
                                Instant sentAt, Instant expiresAt, String status, long recipientCount, long readCount) {}
    public record RecipientView(String username, String branch, Instant readAt) {}
    public record Details(BroadcastView message, List<RecipientView> recipients) {}
    public record MessageView(Long id, String title, String body, String sender, String priority, Instant sentAt, Instant expiresAt, Instant readAt) {}
    public record History(List<BroadcastView> rows, long total, int page, int pages) {}

    @Transactional
    public BroadcastView send(SendRequest request, String sender) {
        if (request == null) throw bad("Message is required");
        String key;
        try { key = UUID.fromString(request.requestKey()).toString(); } catch (Exception ex) { throw bad("A valid send request key is required"); }
        String title = clean(request.title()), body = clean(request.body()), priority = clean(request.priority()).toUpperCase(Locale.ROOT);
        if (title.isEmpty() || title.length() > 120 || body.isEmpty() || body.length() > 4000) throw bad("Title needs 1–120 characters; message needs 1–4000 characters");
        if (!Set.of("NORMAL", "IMPORTANT", "URGENT").contains(priority)) throw bad("Choose Normal, Important or Urgent");
        Set<Long> ids = request.branchIds() == null ? Set.of() : new HashSet<>(request.branchIds());
        if (ids.contains(null) || ids.size() > 1000 || request.allBranches() && !ids.isEmpty() || !request.allBranches() && ids.isEmpty())
            throw bad("Choose all cashiers or at least one branch (maximum 1000)");
        // Global send serialization makes an ambiguous retry idempotent even across concurrent requests.
        // All account/branch management also locks the branch, so audience resolution sees a stable snapshot.
        Branch main = branches.lock(branches.main().getId());
        Optional<Broadcast> previous = broadcasts.findByRequestKey(key);
        if (previous.isPresent()) {
            Broadcast old = previous.get();
            if (!old.getSender().equals(sender) || !old.getTitle().equals(title) || !old.getBody().equals(body)
                    || !old.getPriority().equals(priority) || old.isAllBranches() != request.allBranches()
                    || !old.getBranchIds().equals(ids) || !Objects.equals(old.getExpiresAt(), request.expiresAt()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Send key already belongs to a different message");
            return view(old);
        }
        Instant now = clock.instant();
        if (request.expiresAt() != null && !request.expiresAt().isAfter(now)) throw bad("Expiry must be in the future");
        List<Long> targets = request.allBranches() ? branches.all().stream().filter(b -> !b.isDeleted()).map(Branch::getId).sorted().toList() : ids.stream().sorted().toList();
        Map<Long, Branch> locked = new LinkedHashMap<>();
        for (Long id : targets) {
            Branch branch = id.equals(main.getId()) ? main : branches.lock(id);
            entityManager.refresh(branch, LockModeType.PESSIMISTIC_WRITE);
            if (branch.isDeleted()) { if (!request.allBranches()) throw bad("A selected branch has been deleted"); else continue; }
            locked.put(id, branch);
        }
        String audience = request.allBranches() ? "All eligible cashiers" : "Branches: " + String.join(", ", locked.values().stream().map(branches::label).toList());
        if (audience.length() > 64000) throw bad("Select fewer branches");
        Broadcast message = broadcasts.saveAndFlush(new Broadcast(key, title, body, priority, sender, now, request.expiresAt(), request.allBranches(), ids, audience));
        List<BroadcastRecipient> eligible = new ArrayList<>();
        for (CashierAccount account : accounts.findAll()) {
            if (account.canAccess() && locked.containsKey(account.getBranch().getId()) && !account.getUsername().equals(admin))
                eligible.add(new BroadcastRecipient(message, account.ownerKey(), account.getUsername(), account.getBranch().getId(), branches.label(account.getBranch())));
        }
        if (eligible.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "No eligible cashiers in this audience");
        recipients.saveAllAndFlush(eligible); events.broadcast(message,false); return view(message);
    }
    public History history(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw bad("Invalid history page");
        Page<Broadcast> result = broadcasts.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return new History(result.stream().map(this::view).toList(), result.getTotalElements(), page, result.getTotalPages());
    }
    public Details details(Long id) {
        Broadcast message = require(id);
        return new Details(view(message), recipients.findByBroadcastIdOrderByUsernameAsc(id).stream()
                .map(r -> new RecipientView(r.getUsername(), r.getBranch(), r.getReadAt())).toList());
    }
    @Transactional public BroadcastView revoke(Long id) {
        Broadcast message = broadcasts.lockById(id).orElseThrow(() -> missing()); message.revoke(clock.instant()); events.broadcast(message,true); return view(message);
    }
    public List<MessageView> messages(Authentication auth) {
        Long branchId = cashiers.branchFor(auth); String key = recipientKey(auth.getName());
        return recipients.active(key, branchId, clock.instant()).stream().map(this::messageView).toList();
    }
    @Transactional public MessageView read(Long id, Authentication auth) {
        Long branchId = cashiers.branchFor(auth); String key = recipientKey(auth.getName());
        Broadcast message = broadcasts.lockById(id).orElseThrow(() -> missing());
        BroadcastRecipient recipient = recipients.findByBroadcastIdAndRecipientKey(id, key).filter(r -> r.getBranchId().equals(branchId)).orElseThrow(() -> missing());
        Instant now = clock.instant();
        if (!message.status(now).equals("Active")) throw new ResponseStatusException(HttpStatus.GONE, "Message has expired or been revoked");
        recipient.markRead(now); return messageView(recipient);
    }
    private String recipientKey(String username) {
        return accounts.findByUsername(username).filter(CashierAccount::canAccess).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Cashier unavailable")).ownerKey();
    }
    private Broadcast require(Long id) { return broadcasts.findById(id).orElseThrow(() -> missing()); }
    private BroadcastView view(Broadcast b) { return new BroadcastView(b.getId(), b.getTitle(), b.getBody(), b.getSender(), b.getPriority(), b.getAudience(), b.getSentAt(), b.getExpiresAt(), b.status(clock.instant()), recipients.countByBroadcastId(b.getId()), recipients.countByBroadcastIdAndReadAtIsNotNull(b.getId())); }
    private MessageView messageView(BroadcastRecipient r) { Broadcast b = r.getBroadcast(); return new MessageView(b.getId(), b.getTitle(), b.getBody(), b.getSender(), b.getPriority(), b.getSentAt(), b.getExpiresAt(), r.getReadAt()); }
    private String clean(String value) { return value == null ? "" : value.trim(); }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"); }
}
