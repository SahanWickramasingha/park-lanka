package lk.sahan.parking.service;
import lk.sahan.parking.domain.*;
import lk.sahan.parking.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service @Transactional(readOnly=true)
public class CorrectionService {
 private final CorrectionRepository corrections;private final PaymentRepository payments;private final ShiftRepository shifts;private final BranchService branches;private final ShiftService shiftService;private final UserIdentity users;private final AuditService audit;private final Clock clock;
 public CorrectionService(CorrectionRepository corrections,PaymentRepository payments,ShiftRepository shifts,BranchService branches,ShiftService shiftService,UserIdentity users,AuditService audit,Clock clock){this.corrections=corrections;this.payments=payments;this.shifts=shifts;this.branches=branches;this.shiftService=shiftService;this.users=users;this.audit=audit;this.clock=clock;}
 public record Request(String requestKey,String type,BigDecimal amount,String reason,boolean confirmed,boolean notCollectedConfirmed,String externalReference,boolean externalConfirmed,Long movementShiftId){}
 public record View(Long id,Long paymentId,Long sessionId,Long branchId,String branch,String type,BigDecimal amount,String method,String externalReference,Long movementShiftId,String actor,Instant originalPaidAt,Instant occurredAt,String reason){}
 public record PageView(List<View> rows,long total,int page,int pages){}
 public static class Rejected extends RuntimeException {public Rejected(String text){super(text);}}
 private void admin(Authentication auth){if(!users.admin(auth))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only administrators may correct payments");}
 @Transactional public View correct(Long paymentId,Request request,Authentication auth){
  admin(auth);if(request==null)throw FinancialInputs.bad("Correction details required");String key=FinancialInputs.key(request.requestKey());
  Payment initial=payments.findById(paymentId).orElseThrow(()->missing());Long branchId=initial.getSession().getSlot().getBranch().getId();branches.lock(branchId);
  Payment payment=payments.lock(paymentId).orElseThrow(()->missing());String type=request.type()==null?"":request.type().toUpperCase(Locale.ROOT);BigDecimal amount=FinancialInputs.money(request.amount(),"Correction amount");String reason=request.reason()==null?"":request.reason().trim();String reference=request.externalReference()==null?"":request.externalReference().trim();
  String fingerprint=FinancialInputs.hash(paymentId,type,amount,reason,request.confirmed(),request.notCollectedConfirmed(),reference,request.externalConfirmed(),request.movementShiftId());
  var previous=corrections.findByRequestKey(key);if(previous.isPresent()){var c=previous.get();if(!c.actor.equals(auth.getName())||!c.fingerprint.equals(fingerprint))throw new ResponseStatusException(HttpStatus.CONFLICT,"Correction key belongs to different details or actor");return view(c);}
  if(!Set.of("REFUND","VOID").contains(type)||amount.signum()<=0||reason.length()<3||reason.length()>500||!request.confirmed())throw FinancialInputs.bad("Choose REFUND/VOID, a positive amount, a 3-500 character reason and explicit confirmation");
  List<PaymentCorrection> prior=corrections.findByPaymentIdOrderByOccurredAtAscIdAsc(paymentId);BigDecimal reversed=prior.stream().map(c->c.amount).reduce(BigDecimal.ZERO,BigDecimal::add);
  if(prior.stream().anyMatch(c->c.type.equals("VOID")))throw new Rejected("This payment was fully voided; further corrections are forbidden");
  if(type.equals("VOID")&&(!prior.isEmpty()||amount.compareTo(payment.getAmount())!=0||!request.notCollectedConfirmed()))throw new Rejected("Void requires the full original amount, no previous refunds, and confirmation that collection/settlement did not occur");
  if(reversed.add(amount).compareTo(payment.getAmount())>0)throw new Rejected("Cumulative reversals cannot exceed the original amount");
  CashierShift movement=null;
  if(payment.getMethod().equals("EXTERNAL_CARD")){
   if(reference.isBlank()||reference.length()>80||!request.externalConfirmed())throw FinancialInputs.bad("Confirm the correction performed on the external terminal and provide its reference (max 80 characters); this app does not transfer funds");
   if(corrections.existsByPaymentIdAndExternalReference(paymentId,reference))throw new Rejected("This external-terminal correction reference is already recorded");
  }else{
   if(!reference.isBlank())throw FinancialInputs.bad("Cash corrections must not include an external-terminal reference");
   if(type.equals("REFUND")){
    if(request.movementShiftId()==null)throw new Rejected("Select the active shift paying out the cash refund");
    movement=shifts.lock(request.movementShiftId()).orElseThrow(()->new Rejected("Refund shift not found"));
    if(!movement.branchId.equals(branchId)||movement.closedAt!=null||movement.activeKey==null)throw new Rejected("Refunds require an active shift in the original branch");
    var owner=users.cashier(movement.cashier);if(!owner.key().equals(movement.ownerKey))throw new Rejected("Refund shift owner is no longer eligible");
   }else if(payment.getShift()!=null&&payment.getShift().closedAt==null){
    // No money moved. Remove the uncollected sale from the still-open original drawer expectation.
    movement=shifts.lock(payment.getShift().id).orElseThrow();if(request.movementShiftId()!=null&&!request.movementShiftId().equals(movement.id))throw new Rejected("An uncollected cash void belongs to its original open shift");
   }else if(request.movementShiftId()!=null)throw new Rejected("A void after original shift closure has no cash movement; do not select a refund shift");
  }
  PaymentCorrection c=new PaymentCorrection();c.payment=payment;c.type=type;c.amount=amount;c.reason=reason;c.actor=auth.getName();c.occurredAt=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);c.branchId=branchId;c.branch=branches.label(payment.getSession().getSlot().getBranch());c.method=payment.getMethod();c.externalReference=reference.isBlank()?null:reference;c.movementShift=movement;c.requestKey=key;c.fingerprint=fingerprint;corrections.saveAndFlush(c);
  View result=view(c);audit.record("PAYMENT_"+type,"PAYMENT:"+paymentId,branchId,reason,Map.of("gross",payment.getAmount(),"previouslyReversed",reversed),result);return result;
 }
 public View status(String key,Authentication auth){admin(auth);return view(corrections.findByRequestKey(FinancialInputs.key(key)).orElseThrow(()->missing()));}
 public List<View> forPayment(Long id){return corrections.findByPaymentIdOrderByOccurredAtAscIdAsc(id).stream().map(this::view).toList();}
 public record Totals(BigDecimal refunds,BigDecimal voids){}
 public Totals totals(Long branch,Instant start,Instant end){return new Totals(corrections.periodTotal("REFUND",branch,start,end).setScale(2),corrections.periodTotal("VOID",branch,start,end).setScale(2));}
 public List<ShiftService.View> refundShifts(Long paymentId,Authentication auth){admin(auth);Payment p=payments.findById(paymentId).orElseThrow(()->missing());return shifts.findByBranchIdAndClosedAtIsNullOrderByIdDesc(p.getSession().getSlot().getBranch().getId()).stream().map(shiftService::view).toList();}
 public PageView list(Long branchId,Long paymentId,LocalDate from,LocalDate to,int page,int size){
  if(page<0||size<1||size>100)throw FinancialInputs.bad("Invalid pagination");if(from!=null&&to!=null&&from.isAfter(to))throw FinancialInputs.bad("Invalid date range");
  var found=corrections.findAll((r,q,cb)->{var p=new ArrayList<jakarta.persistence.criteria.Predicate>();if(branchId!=null)p.add(cb.equal(r.get("branchId"),branchId));if(paymentId!=null)p.add(cb.equal(r.get("payment").get("id"),paymentId));if(from!=null)p.add(cb.greaterThanOrEqualTo(r.get("occurredAt"),from.atStartOfDay(ZoneId.of("Asia/Colombo")).toInstant()));if(to!=null)p.add(cb.lessThan(r.get("occurredAt"),to.plusDays(1).atStartOfDay(ZoneId.of("Asia/Colombo")).toInstant()));return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));},PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"id")));
  return new PageView(found.map(this::view).getContent(),found.getTotalElements(),page,found.getTotalPages());
 }
 private View view(PaymentCorrection c){return new View(c.id,c.payment.getId(),c.payment.getSession().getId(),c.branchId,c.branch,c.type,c.amount,c.method,c.externalReference,c.movementShift==null?null:c.movementShift.id,c.actor,c.payment.getPaidAt(),c.occurredAt,c.reason);}
 private ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment/correction attempt not found");}
}
