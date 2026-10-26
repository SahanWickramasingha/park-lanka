package lk.sahan.parking.service;

import lk.sahan.parking.domain.*;
import lk.sahan.parking.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;

@Service
public class ParkingService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ParkingService.class);
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Colombo");
    private final SlotRepository slots;
    private final SessionRepository sessions;
    private final PaymentRepository payments;
    private final RateService rates;
    private final BranchService branches;
    private final CashierService cashiers;
    private final Clock clock;
    private final EventLog events;
    private final ShiftService shifts;
    private final PricingService pricing;
    private final AuditService audit;
    private final CorrectionService corrections;
    private final CheckoutQuoteRepository quotes;
    private final UserIdentity users;
    private final long quoteValiditySeconds;

    public ParkingService(SlotRepository slots, SessionRepository sessions, PaymentRepository payments, RateService rates,
                          BranchService branches, CashierService cashiers, EventLog events,ShiftService shifts,PricingService pricing,AuditService audit,CorrectionService corrections,
                          CheckoutQuoteRepository quotes,UserIdentity users,Clock clock,
                          @org.springframework.beans.factory.annotation.Value("${parking.checkout.quote-validity-seconds:120}") long quoteValiditySeconds) {
        this.slots = slots; this.sessions = sessions; this.payments = payments;
        this.rates = rates; this.clock = clock;
        if(quoteValiditySeconds<1||quoteValiditySeconds>86400)throw new IllegalArgumentException("Quote validity must be 1..86400 seconds");
        this.quotes=quotes;this.users=users;this.quoteValiditySeconds=quoteValiditySeconds;
        this.branches = branches; this.cashiers = cashiers;
        this.events=events;
        this.shifts=shifts;
        this.pricing=pricing;
        this.audit=audit;
        this.corrections=corrections;
    }

    public record EntryRequest(String plate, String vehicleType, Long slotId) {}
    public record CheckoutRequest(String method,String reference,String requestKey,BigDecimal confirmedAmount,BigDecimal cashTendered,boolean externalConfirmed,String quoteId) {}
    public record SlotView(Long id, String code, boolean occupied) {}
    public record SessionView(Long id, String plate, String vehicleType, String slot, Instant enteredAt) {}
    public record QuoteView(Long sessionId, String plate, String slot, Instant enteredAt,
                            Instant calculatedAt, long billableMinutes, BigDecimal amount, String currency,FeeCalculator.Breakdown breakdown,String vehicleType,
                            String quoteId,int quoteVersion,Instant billingCutoff,Instant expiresAt) {}
    public record ReceiptView(Long paymentId, Long sessionId, String plate, String slot,
                              Instant enteredAt, Instant exitedAt, long billableMinutes,
                              BigDecimal amount, String currency, String method, String reference,String branch,String vehicleType,
                              BigDecimal cashTendered,BigDecimal changeDue,String requestKey,Long shiftId,
                              List<CorrectionService.View> corrections,BigDecimal refunded,BigDecimal voided,BigDecimal netAmount,Instant paidAt,
                              String cashier,FeeCalculator.Breakdown breakdown,long elapsedSeconds,String status,Instant billingCutoff,String quoteId) {}
    public static class QuoteChanged extends RuntimeException {
        public final QuoteView quote;
        public QuoteChanged(QuoteView quote,String reason){super(reason+" No payment was recorded. Review and confirm the fresh quote.");this.quote=quote;}
    }
    public record ReceiptPage(List<ReceiptView> rows,long total,int page,int pages) {}
    public ReceiptPage receipts(Long branchId,String search,LocalDate from,LocalDate to,int page,int size) {
        return receipts(branchId,search,null,from,to,page,size);
    }
    public ReceiptPage receipts(Long branchId,String search,String cashier,LocalDate from,LocalDate to,int page,int size) {
        if(page<0||size<1||size>100)throw bad("Page must be >= 0 and size 1..100");
        if(from!=null&&to!=null&&from.isAfter(to))throw bad("From date must be on or before To date");
        String text=search==null?"":search.trim().toUpperCase(Locale.ROOT);
        if(text.length()>80)throw bad("Search must be at most 80 characters");
        if(cashier!=null&&cashier.trim().length()>128)throw bad("Cashier filter must be at most 128 characters");
        Specification<Payment> filter=(root,q,cb)->{
            var conditions=new ArrayList<jakarta.persistence.criteria.Predicate>();var session=root.get("session");
            if(branchId!=null)conditions.add(cb.equal(session.get("slot").get("branch").get("id"),branchId));
            if(cashier!=null&&!cashier.isBlank())conditions.add(cb.equal(root.get("cashierUsername"),cashier.trim()));
            if(from!=null)conditions.add(cb.greaterThanOrEqualTo(root.get("paidAt"),from.atStartOfDay(REPORT_ZONE).toInstant()));
            if(to!=null)conditions.add(cb.lessThan(root.get("paidAt"),to.plusDays(1).atStartOfDay(REPORT_ZONE).toInstant()));
            if(!text.isEmpty()) {
                var plate=cb.like(cb.upper(session.get("plate")),"%"+text.replace("!","!!").replace("%","!%").replace("_","!_")+"%",'!');
                if(text.matches("[0-9]+")){try{conditions.add(cb.or(plate,cb.equal(session.get("id"),Long.parseLong(text))));}catch(NumberFormatException ex){conditions.add(plate);}}
                else conditions.add(plate);
            }
            return cb.and(conditions.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var found=payments.findAll(filter,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"paidAt","id")));
        return new ReceiptPage(found.map(this::receipt).getContent(),found.getTotalElements(),page,found.getTotalPages());
    }
    public record EntryHistoryView(Long id,String plate,String vehicleType,String slot,Instant enteredAt,String enteredBy,
                                   Instant exitedAt,String status,String branch,String pricingSummary) {}
    public record EntryHistoryPage(List<EntryHistoryView> rows,long total,int page,int pages) {}
    public EntryHistoryPage entryHistory(Long branchId,String search,String cashier,LocalDate from,LocalDate to,int page,int size){
        if(branchId==null)throw bad("An authenticated branch is required");
        if(page<0||size<1||size>100)throw bad("Page must be >= 0 and size 1..100");
        if(from!=null&&to!=null&&from.isAfter(to))throw bad("From date must be on or before To date");
        String text=search==null?"":search.trim().toUpperCase(Locale.ROOT);
        if(text.length()>80)throw bad("Search must be at most 80 characters");
        if(cashier!=null&&cashier.trim().length()>128)throw bad("Cashier filter must be at most 128 characters");
        Specification<ParkingSession> filter=(root,q,cb)->{
            var conditions=new ArrayList<jakarta.persistence.criteria.Predicate>();conditions.add(cb.equal(root.get("slot").get("branch").get("id"),branchId));
            if(from!=null)conditions.add(cb.greaterThanOrEqualTo(root.get("enteredAt"),from.atStartOfDay(REPORT_ZONE).toInstant()));
            if(to!=null)conditions.add(cb.lessThan(root.get("enteredAt"),to.plusDays(1).atStartOfDay(REPORT_ZONE).toInstant()));
            if(cashier!=null&&!cashier.isBlank())conditions.add(cb.equal(root.get("enteredBy"),cashier.trim()));
            if(!text.isEmpty()){
                var plate=cb.like(cb.upper(root.get("plate")),"%"+text.replace("!","!!").replace("%","!%").replace("_","!_")+"%",'!');
                if(text.matches("[0-9]+")){try{conditions.add(cb.or(plate,cb.equal(root.get("id"),Long.parseLong(text))));}catch(NumberFormatException ex){conditions.add(plate);}}
                else conditions.add(plate);
            }
            return cb.and(conditions.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var found=sessions.findAll(filter,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"enteredAt","id")));
        return new EntryHistoryPage(found.map(this::entryHistoryView).getContent(),found.getTotalElements(),page,found.getTotalPages());
    }
    public EntryHistoryView entryHistoryDetails(Long id,Long branchId){
        ParkingSession session=sessions.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Entry not found"));
        checkBranch(session.getSlot(),branchId);return entryHistoryView(session);
    }
    private EntryHistoryView entryHistoryView(ParkingSession s){return new EntryHistoryView(s.getId(),s.getPlate(),s.getVehicleType(),s.getSlot().getCode(),s.getEnteredAt(),s.getEnteredBy(),s.getExitedAt(),s.getExitedAt()==null?"Parked":"Completed",branches.label(s.getSlot().getBranch()),PricingService.describe(sessionPolicy(s)));}
    public record ReportView(LocalDate date, int payments, BigDecimal revenue, String currency, int occupied,BigDecimal grossCollections,BigDecimal refunds,BigDecimal voids) {}
    public record TicketView(Long id,String branch,String plate,String vehicleType,String slot,Instant enteredAt,String pricingSummary,String opaqueIdentifier) {}

    @Transactional
    public TicketView ticket(Long id,Long branchId) {
        ParkingSession s=sessions.lockById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Ticket not found"));
        checkBranch(s.getSlot(),branchId);
        return new TicketView(s.getId(),branches.label(s.getSlot().getBranch()),s.getPlate(),s.getVehicleType(),s.getSlot().getCode(),s.getEnteredAt(),
            PricingService.describe(sessionPolicy(s)),s.ensureTicketToken());
    }
    @Transactional public TicketView reprintTicket(Long id,Long branchId){TicketView result=ticket(id,branchId);audit.record("TICKET_REPRINT_REQUESTED","SESSION:"+id,branchId,null,null,Map.of("ticket",id));return result;}
    @Transactional public ReceiptView reprintReceipt(Long id,Long branchId){ReceiptView result=receiptBySession(id,branchId);Payment payment=payments.findBySessionId(id).orElseThrow();audit.record("RECEIPT_REPRINT_REQUESTED","PAYMENT:"+result.paymentId(),payment.getSession().getSlot().getBranch().getId(),null,null,Map.of("paymentId",result.paymentId(),"sessionId",id));return result;}

    public List<SlotView> allSlots(Long branchId) {
        return slots.findByBranchId(branchId).stream().sorted(Comparator.comparing(ParkingSlot::getCode))
                .map(s -> new SlotView(s.getId(), s.getCode(), s.isOccupied())).toList();
    }

    public List<SessionView> activeSessions(Long branchId) {
        return sessions.findBySlotBranchIdAndExitedAtIsNullOrderByEnteredAtAsc(branchId).stream()
                .map(s -> new SessionView(s.getId(), s.getPlate(), s.getVehicleType(), s.getSlot().getCode(), s.getEnteredAt())).toList();
    }

    @Transactional
    public SessionView enter(EntryRequest request, Long branchId, String cashier) {
        Branch branch = branches.lock(branchId);
        branches.requireAvailable(branch);
        cashiers.checkAccess(cashier, branchId);
        if (branch.isStopped()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Branch is stopped; new vehicle entries are disabled");
        if (request == null || request.plate() == null || request.vehicleType() == null || request.slotId() == null)
            throw bad("Plate, vehicle type and slot are required");
        String plate = request.plate().trim().toUpperCase(Locale.ROOT);
        String type = request.vehicleType().trim().toUpperCase(Locale.ROOT);
        if (!plate.matches("[A-Z0-9 -]{3,24}") || !Set.of("CAR", "VAN", "MOTORCYCLE", "THREE_WHEELER", "OTHER").contains(type))
            throw bad("Invalid plate or vehicle type");
        ParkingSlot slot = slots.lockById(request.slotId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot not found"));
        checkBranch(slot, branchId);
        if (sessions.existsByActivePlate(plate)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Vehicle already parked");
        if (slot.isOccupied()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot already occupied");
        slot.setOccupied(true);
        ParkingSession session = new ParkingSession(plate,type,slot,clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),rates.current(),cashier);
        var effective=pricing.effective(branchId,type);session.snapshotPolicy(effective.policy(),effective.source());sessions.saveAndFlush(session);
        events.business("Vehicle entry","Vehicle entered",plate+" · Ticket #"+session.getId()+" · Slot "+slot.getCode(),branch,cashier,"SESSION",session.getId(),null,false);
        return new SessionView(session.getId(), plate, type, slot.getCode(), session.getEnteredAt());
    }

    @Transactional
    public QuoteView quote(Long id, Long branchId,String cashier) {
        cashiers.checkAccess(cashier,branchId);
        branches.requireAvailable(branches.require(branchId));
        ParkingSession session = sessions.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        checkBranch(session.getSlot(), branchId);
        if (session.getExitedAt() != null) throw new CheckoutRejected(HttpStatus.CONFLICT, "ALREADY_CHECKED_OUT", "Vehicle is already checked out. View its saved receipt.");
        return issueQuote(session,branchId,cashier,clock.instant());
    }

    private QuoteView issueQuote(ParkingSession session,Long branchId,String cashier,Instant time){
        Instant cutoff=time.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var charge=FeeCalculator.policyCharge(session.getEnteredAt(),cutoff,sessionPolicy(session));
        CheckoutQuote q=new CheckoutQuote();q.id=UUID.randomUUID().toString();q.session=session;q.branchId=branchId;q.cashierKey=users.cashier(cashier).key();
        q.billingCutoff=cutoff;q.expiresAt=cutoff.plusSeconds(quoteValiditySeconds);q.amount=FinancialInputs.money(charge.amount(),"Quoted amount");q.billableMinutes=charge.billableMinutes();quotes.saveAndFlush(q);
        LOG.info("CHECKOUT quote issued session={} quote={} version=1 branch={} cutoff={} expires={} minutes={} units={} fee={}",session.getId(),q.id,branchId,cutoff,q.expiresAt,q.billableMinutes,charge.billedUnits(),q.amount);
        return new QuoteView(session.getId(),session.getPlate(),session.getSlot().getCode(),session.getEnteredAt(),cutoff,q.billableMinutes,q.amount,"LKR",charge,session.getVehicleType(),q.id,1,cutoff,q.expiresAt);
    }

    // QUOTE_CHANGED commits only a fresh immutable quote. No payment/session/slot mutation precedes validation.
    // Keeping that quote in this transaction avoids a second transaction deadlocking on the locked session FK.
    @Transactional(noRollbackFor=QuoteChanged.class)
    public ReceiptView checkout(Long id, CheckoutRequest request, Long branchId, String cashier) {
        branches.requireAvailable(branches.lock(branchId));
        cashiers.checkAccess(cashier, branchId);
        if (request == null || request.method() == null) throw bad("Payment method is required");
        String key=FinancialInputs.key(request.requestKey());
        BigDecimal confirmed=FinancialInputs.money(request.confirmedAmount(),"Confirmed amount");
        String method = request.method().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("CASH", "EXTERNAL_CARD").contains(method)) throw bad("Payment method must be CASH or EXTERNAL_CARD");
        String reference = request.reference() == null ? "" : request.reference().trim();
        if (reference.length() > 80 || (method.equals("EXTERNAL_CARD") && reference.isBlank()))
            throw bad("External card payment requires a reference (max 80 characters)");
        if(method.equals("EXTERNAL_CARD")&&!request.externalConfirmed())throw bad("Confirm the payment performed on the external terminal; this app does not charge cards");
        BigDecimal tendered=method.equals("CASH")?FinancialInputs.money(request.cashTendered(),"Cash tendered"):null;
        // Preserve replay compatibility with already committed attempts created before quote IDs existed.
        String fingerprint=request.quoteId()==null?FinancialInputs.hash(id,method,reference,confirmed,tendered,request.externalConfirmed()):FinancialInputs.hash(id,method,reference,confirmed,tendered,request.externalConfirmed(),request.quoteId());
        var previous=payments.findByRequestKey(key);
        if(previous.isPresent())return replay(previous.get(),fingerprint,branchId,cashier);
        ParkingSession session = sessions.lockById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        checkBranch(session.getSlot(), branchId);
        previous=payments.findByRequestKey(key);
        if(previous.isPresent())return replay(previous.get(),fingerprint,branchId,cashier);
        if (session.getExitedAt() != null) throw new CheckoutRejected(HttpStatus.CONFLICT, "ALREADY_CHECKED_OUT", "Vehicle is already checked out. View its saved receipt.");
        CashierShift shift=shifts.requireActive(branchId,cashier);
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        CheckoutQuote quote=request.quoteId()==null?null:quotes.findById(request.quoteId()).orElse(null);
        if(quote!=null&&(!quote.session.getId().equals(id)||!quote.branchId.equals(branchId)||!quote.cashierKey.equals(users.cashier(cashier).key())))
            throw new CheckoutRejected(HttpStatus.NOT_FOUND,"QUOTE_NOT_FOUND","Quote does not belong to this session and cashier/branch. Request a new quote.");
        String reason=quote==null?"A server-issued quote is required; update the desktop if the quote identifier is missing":
            !now.isBefore(quote.expiresAt)?"The quote expired":quote.amount.compareTo(confirmed)!=0?"The confirmed amount does not match the server-issued quote":null;
        LOG.info("CHECKOUT confirm session={} request={} quote={} branch={} now={} cutoff={} expires={} minutes={} quotedFee={} confirmedFee={} result={}",id,key,request.quoteId(),branchId,now,quote==null?null:quote.billingCutoff,quote==null?null:quote.expiresAt,quote==null?null:quote.billableMinutes,quote==null?null:quote.amount,confirmed,reason==null?"VALID":reason);
        if(reason!=null)throw new QuoteChanged(issueQuote(session,branchId,cashier,now),reason);
        BigDecimal total=quote.amount;
        if(tendered!=null&&tendered.compareTo(total)<0)throw new CheckoutRejected(HttpStatus.BAD_REQUEST, "INSUFFICIENT_CASH", "Cash received is less than the confirmed amount due.");
        session.close(now);
        session.getSlot().setOccupied(false);
        Payment payment = new Payment(session,total,method,reference,now,cashier);
        payment.quoted(quote);
        payment.attempt(key,fingerprint,tendered,tendered==null?null:tendered.subtract(total));
        payment.assignShift(shift);
        payments.saveAndFlush(payment);
        events.business("Payment","Checkout completed",session.getPlate()+" · Ticket #"+id+" · Payment #"+payment.getId()+" · LKR "+total,session.getSlot().getBranch(),cashier,"PAYMENT",payment.getId(),total,false);
        return receipt(payment);
    }
    private ReceiptView replay(Payment p,String fingerprint,Long branchId,String cashier){
        checkBranch(p.getSession().getSlot(),branchId);
        if(!cashier.equals(p.getCashierUsername()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Checkout attempt not found");
        if(!fingerprint.equals(p.getRequestFingerprint()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Request key was used with different checkout details");
        return receipt(p);
    }
    public ReceiptView checkoutStatus(String key,Long branchId,String cashier){
        Payment p=payments.findByRequestKey(FinancialInputs.key(key)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"No committed payment for this attempt. Retry only with the same request key and details."));
        return replay(p,p.getRequestFingerprint(),branchId,cashier);
    }

    public ReceiptView receiptBySession(Long id, Long branchId) {
        return payments.findBySessionId(id).map(p -> {
                    checkBranch(p.getSession().getSlot(), branchId);
                    return receipt(p);
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found"));
    }
    public ReceiptView receiptByPayment(Long id){return receipt(payments.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment not found")));}

    public ReportView today(Long branchId) {
        LocalDate date = LocalDate.now(clock.withZone(REPORT_ZONE));
        Instant start = date.atStartOfDay(REPORT_ZONE).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(REPORT_ZONE).toInstant();
        List<Payment> sales = payments.findBySessionSlotBranchIdAndPaidAtGreaterThanEqualAndPaidAtLessThan(branchId, start, end);
        BigDecimal total = sales.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        int occupied = (int) slots.findByBranchId(branchId).stream().filter(ParkingSlot::isOccupied).count();
        var adjustments=corrections.totals(branchId,start,end);
        return new ReportView(date,sales.size(),total.subtract(adjustments.refunds()).subtract(adjustments.voids()).setScale(2),"LKR",occupied,total.setScale(2),adjustments.refunds(),adjustments.voids());
    }

    private ReceiptView receipt(Payment p) {
        ParkingSession s = p.getSession();
        var changes=corrections.forPayment(p.getId());BigDecimal refunded=changes.stream().filter(c->c.type().equals("REFUND")).map(CorrectionService.View::amount).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2);
        BigDecimal voided=changes.stream().filter(c->c.type().equals("VOID")).map(CorrectionService.View::amount).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2);
        return new ReceiptView(p.getId(), s.getId(), s.getPlate(), s.getSlot().getCode(), s.getEnteredAt(),
                s.getExitedAt(), FeeCalculator.policyCharge(s.getEnteredAt(),p.getBillingCutoff(),sessionPolicy(s)).billableMinutes(),
                p.getAmount(), "LKR", p.getMethod(), p.getReference(),branches.label(s.getSlot().getBranch()),s.getVehicleType(),p.getCashTendered(),p.getChangeDue(),p.getRequestKey(),p.getShift()==null?null:p.getShift().id,changes,refunded,voided,p.getAmount().subtract(refunded).subtract(voided),p.getPaidAt(),
                p.getCashierUsername(),s.getPolicy()==null&&s.getRatePerMinute()==null?null:FeeCalculator.policyCharge(s.getEnteredAt(),p.getBillingCutoff(),sessionPolicy(s)),
                java.time.Duration.between(s.getEnteredAt(),s.getExitedAt()).getSeconds(),voided.signum()>0?"Voided":refunded.compareTo(p.getAmount())>=0&&refunded.signum()>0?"Refunded":refunded.signum()>0?"Partially refunded":"Paid",p.getBillingCutoff(),p.getQuoteId());
    }
    private BigDecimal sessionRate(ParkingSession session) {
        return session.getRatePerMinute() == null ? rates.legacyRate() : session.getRatePerMinute();
    }
    private PricingPolicy sessionPolicy(ParkingSession s){return s.getPolicy()==null?new PricingPolicy(sessionRate(s),"MINUTE",0,null):s.getPolicy();}
    private void checkBranch(ParkingSlot slot, Long branchId) {
        if(branchId==null)return; // only admin callers may supply an unrestricted scope
        if (slot.getBranch() == null || !slot.getBranch().getId().equals(branchId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found in this branch");
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
