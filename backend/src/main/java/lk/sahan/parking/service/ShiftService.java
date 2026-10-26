package lk.sahan.parking.service;
import lk.sahan.parking.domain.*;
import lk.sahan.parking.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
@Service @Transactional(readOnly=true)
public class ShiftService {
 private final ShiftRepository shifts;private final PaymentRepository payments;private final UserIdentity users;private final BranchService branches;private final Clock clock;private final AuditService audit;
 private final CorrectionRepository corrections;
 public ShiftService(ShiftRepository shifts,PaymentRepository payments,UserIdentity users,BranchService branches,Clock clock,AuditService audit,CorrectionRepository corrections){this.shifts=shifts;this.payments=payments;this.users=users;this.branches=branches;this.clock=clock;this.audit=audit;this.corrections=corrections;}
 public record Open(String requestKey,BigDecimal openingCash){}
 public record Close(String requestKey,BigDecimal countedCash,String reason){}
 public record View(Long id,String cashier,Long branchId,String branch,Instant openedAt,Instant closedAt,BigDecimal openingCash,BigDecimal cashCollections,BigDecimal cardCollections,BigDecimal cashRefunds,BigDecimal cashVoids,BigDecimal expectedCash,BigDecimal countedCash,BigDecimal difference,String reason){}
 public record PageView(List<View> rows,long total,int page,int pages){}
 public record Current(boolean active,View shift){}
 public static final class AlreadyOpen extends ResponseStatusException {
  public AlreadyOpen(){super(HttpStatus.CONFLICT,"An active shift already exists. Reload and continue that shift.");}
 }
 private String activeKey(String key,Long branch){return key+"|"+branch;}
 public Current current(Authentication auth){var owner=users.cashier(auth.getName());return shifts.findByActiveKey(activeKey(owner.key(),owner.branch().getId())).map(s->new Current(true,view(s))).orElse(new Current(false,null));}
 @Transactional public View open(Open request,Authentication auth){
  if(request==null)throw FinancialInputs.bad("Opening cash and request key required");var owner=users.cashier(auth.getName());Branch branch=branches.lock(owner.branch().getId());branches.requireAvailable(branch);
  if(branch.isStopped())throw conflict("Branch is stopped; contact an administrator before opening a shift");
  String key=FinancialInputs.key(request.requestKey());BigDecimal cash=FinancialInputs.money(request.openingCash(),"Opening cash");String fingerprint=FinancialInputs.hash(owner.key(),branch.getId(),cash);
  var previous=shifts.findByOpenRequestKey(key);if(previous.isPresent()){CashierShift s=previous.get();if(!owner.key().equals(s.ownerKey)||!fingerprint.equals(s.openFingerprint))throw conflict("Shift key was used with different opening details");return view(s);}
  String active=activeKey(owner.key(),branch.getId());if(shifts.findByActiveKey(active).isPresent())throw new AlreadyOpen();
  CashierShift s=new CashierShift();s.ownerKey=owner.key();s.cashier=owner.username();s.branchId=branch.getId();s.branch=branches.label(branch);s.activeKey=active;s.openRequestKey=key;s.openFingerprint=fingerprint;s.openingCash=cash;s.openedAt=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  View result=view(shifts.saveAndFlush(s));audit.record("SHIFT_OPENED","SHIFT:"+s.id,s.branchId,null,null,result);return result;
 }
 @Transactional public View close(Long id,Close request,Authentication auth){
  if(users.admin(auth)){CashierShift initial=shifts.findById(id).orElseThrow(()->missing());branches.lock(initial.branchId);if(request==null||request.reason()==null||request.reason().trim().length()<3)throw FinancialInputs.bad("An administrator closing another cashier's shift must provide a reason");}
  else {var owner=users.cashier(auth.getName());branches.lock(owner.branch().getId());}
  CashierShift s=shifts.lock(id).orElseThrow(()->missing());owned(s,auth);
  if(request==null)throw FinancialInputs.bad("Counted cash and request key required");String key=FinancialInputs.key(request.requestKey());BigDecimal counted=FinancialInputs.money(request.countedCash(),"Counted cash");String reason=request.reason()==null?"":request.reason().trim();
  if(reason.length()>500)throw FinancialInputs.bad("Explanation must be at most 500 characters");String fingerprint=FinancialInputs.hash(id,counted,reason);
  if(s.closedAt!=null){if(key.equals(s.closeRequestKey)&&fingerprint.equals(s.closeFingerprint))return view(s);throw conflict("Shift is already closed");}
  View totals=view(s);BigDecimal difference=counted.subtract(totals.expectedCash());if(difference.signum()!=0&&reason.length()<3)throw FinancialInputs.bad("Explain the nonzero cash difference (3-500 characters)");
  s.cashCollections=totals.cashCollections();s.cardCollections=totals.cardCollections();s.cashRefunds=totals.cashRefunds();s.cashVoids=totals.cashVoids();s.expectedCash=totals.expectedCash();s.countedCash=counted;s.difference=difference;s.reason=reason;
  s.closeRequestKey=key;s.closeFingerprint=fingerprint;s.closedAt=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);s.activeKey=null;
  shifts.flush();View result=view(s);audit.record("SHIFT_CLOSED","SHIFT:"+s.id,s.branchId,reason,totals,result);return result;
 }
 // Caller takes the branch lock before this lock: checkout and closure cannot interleave.
 public CashierShift requireActive(Long branchId,String cashier){var owner=users.cashier(cashier);if(!branchId.equals(owner.branch().getId()))throw missing();CashierShift s=shifts.findByActiveKey(activeKey(owner.key(),branchId)).orElseThrow(()->shiftRequired());return shifts.lock(s.id).filter(row->row.closedAt==null).orElseThrow(()->shiftRequired());}
 private CheckoutRejected shiftRequired(){return new CheckoutRejected(HttpStatus.CONFLICT,"SHIFT_REQUIRED","Open a cashier shift before checkout.");}
 public View get(Long id,Authentication auth){CashierShift s=shifts.findById(id).orElseThrow(()->missing());owned(s,auth);return view(s);}
 private void owned(CashierShift s,Authentication auth){if(!users.admin(auth)&&(!users.key(auth).equals(s.ownerKey)||!users.branch(auth).equals(s.branchId)))throw missing();}
 public PageView list(Authentication auth,Long branchId,String cashier,LocalDate from,LocalDate to,int page,int size){
  if(page<0||size<1||size>100)throw FinancialInputs.bad("Page must be >=0 and size 1..100");if(from!=null&&to!=null&&from.isAfter(to))throw FinancialInputs.bad("Invalid date range");boolean admin=users.admin(auth);String owner=admin?null:users.key(auth);Long scope=admin?branchId:users.branch(auth);
  var found=shifts.findAll((r,q,cb)->{var p=new ArrayList<jakarta.persistence.criteria.Predicate>();if(scope!=null)p.add(cb.equal(r.get("branchId"),scope));if(owner!=null)p.add(cb.equal(r.get("ownerKey"),owner));if(admin&&cashier!=null&&!cashier.isBlank())p.add(cb.equal(r.get("cashier"),cashier));if(from!=null)p.add(cb.greaterThanOrEqualTo(r.get("openedAt"),from.atStartOfDay(ZoneId.of("Asia/Colombo")).toInstant()));if(to!=null)p.add(cb.lessThan(r.get("openedAt"),to.plusDays(1).atStartOfDay(ZoneId.of("Asia/Colombo")).toInstant()));return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));},PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"id")));
  return new PageView(found.map(this::view).getContent(),found.getTotalElements(),page,found.getTotalPages());
 }
 public View view(CashierShift s){
  BigDecimal cash=BigDecimal.ZERO.setScale(2),card=cash,refunds=cash,voids=cash;
  if(s.closedAt==null){for(Payment p:payments.findByShiftId(s.id)){if(p.getMethod().equals("CASH"))cash=cash.add(p.getAmount());else card=card.add(p.getAmount());}refunds=corrections.shiftTotal(s.id,"REFUND");voids=corrections.shiftTotal(s.id,"VOID");}
  else {cash=s.cashCollections;card=s.cardCollections;refunds=s.cashRefunds;voids=s.cashVoids;}
  return new View(s.id,s.cashier,s.branchId,s.branch,s.openedAt,s.closedAt,s.openingCash,cash,card,refunds,voids,s.openingCash.add(cash).subtract(refunds).subtract(voids),s.countedCash,s.difference,s.reason);
 }
 private ResponseStatusException conflict(String text){return new ResponseStatusException(HttpStatus.CONFLICT,text);}
 private ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Shift not found");}
}
