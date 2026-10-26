package lk.sahan.parking.service;
import lk.sahan.parking.domain.*;
import lk.sahan.parking.repo.PricingRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
@Service @Transactional(readOnly=true)
public class PricingService {
 private final PricingRuleRepository rules;private final RateService rates;private final BranchService branches;private final AuditService audit;
 public PricingService(PricingRuleRepository rules,RateService rates,BranchService branches,AuditService audit){this.rules=rules;this.rates=rates;this.branches=branches;this.audit=audit;}
 public record Effective(PricingPolicy policy,String source){}
 public record Request(String vehicleType,BigDecimal rate,String unit,int graceMinutes,BigDecimal dailyCap){}
 public record View(Long id,Long branchId,String vehicleType,BigDecimal rate,String unit,int graceMinutes,BigDecimal dailyCap){}
 public Effective effective(Long branch,String type){
  var rule=rules.findByBranchIdAndVehicleType(branch,type);if(rule.isPresent())return new Effective(rule.get().policy(),"Branch + vehicle type");
  rule=rules.findByBranchIdAndVehicleType(branch,"DEFAULT");return rule.map(r->new Effective(r.policy(),"Branch default")).orElseGet(()->new Effective(new PricingPolicy(rates.current(),"MINUTE",0,null),"Global default"));
 }
 public List<View> list(Long branch){branches.require(branch);return rules.findByBranchIdOrderByVehicleType(branch).stream().map(this::view).toList();}
 @Transactional public View save(Long branchId,Request request){
  branches.requireAvailable(branches.lock(branchId));if(request==null)throw FinancialInputs.bad("Pricing policy required");String type=request.vehicleType()==null?"":request.vehicleType().trim().toUpperCase(Locale.ROOT);String unit=request.unit()==null?"":request.unit().toUpperCase(Locale.ROOT);
  if(!Set.of("DEFAULT","CAR","VAN","MOTORCYCLE","THREE_WHEELER","OTHER").contains(type))throw FinancialInputs.bad("Choose a vehicle type or DEFAULT");
  BigDecimal rate=FinancialInputs.money(request.rate(),"Rate");if(rate.signum()<=0||rate.compareTo(new BigDecimal("100000.00"))>0)throw FinancialInputs.bad("Rate must be 0.01 to 100000.00");
  if(!Set.of("MINUTE","HOUR").contains(unit)||request.graceMinutes()<0||request.graceMinutes()>1440)throw FinancialInputs.bad("Choose MINUTE or HOUR and grace 0..1440 minutes");
  BigDecimal cap=request.dailyCap()==null?null:FinancialInputs.money(request.dailyCap(),"Daily cap");
  PricingRule r=rules.findByBranchIdAndVehicleType(branchId,type).orElseGet(PricingRule::new);View before=r.id==null?null:view(r);r.branchId=branchId;r.vehicleType=type;r.rate=rate;r.billingUnit=unit;r.graceMinutes=request.graceMinutes();r.dailyCap=cap;View after=view(rules.saveAndFlush(r));audit.record("PRICING_RULE_SAVED","PRICING:"+r.id,branchId,null,before,after);return after;
 }
 @Transactional public void remove(Long branch,String type){branches.lock(branch);rules.findByBranchIdAndVehicleType(branch,type).ifPresent(r->{audit.record("PRICING_RULE_REMOVED","PRICING:"+r.id,branch,null,view(r),null);rules.delete(r);});}
 private View view(PricingRule r){return new View(r.id,r.branchId,r.vehicleType,r.rate,r.billingUnit,r.graceMinutes,r.dailyCap);}
 public static String describe(PricingPolicy p){return "LKR "+p.rate()+" per started "+p.unit().toLowerCase(Locale.ROOT)+"; grace "+p.graceMinutes()+" min"+(p.dailyCap()==null?"; no cap":"; max LKR "+p.dailyCap()+" per consecutive 24h block")+(p.graceMinutes()==0?"; minimum one unit":"; after grace bill remaining elapsed time");}
}
