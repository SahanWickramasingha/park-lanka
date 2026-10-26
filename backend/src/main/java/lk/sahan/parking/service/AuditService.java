package lk.sahan.parking.service;
import lk.sahan.parking.domain.AuditRecord;
import lk.sahan.parking.repo.AuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
@Service
public class AuditService {
 private final AuditRepository records;private final ObjectMapper json;private final Clock clock;
 public AuditService(AuditRepository records,ObjectMapper json,Clock clock){this.records=records;this.json=json;this.clock=clock;}
 @Transactional(propagation=Propagation.MANDATORY)
 public void record(String action,String target,Long branch,String reason,Object before,Object after){
  var auth=SecurityContextHolder.getContext().getAuthentication();AuditRecord r=new AuditRecord();r.actor=auth!=null&&auth.isAuthenticated()?auth.getName():"System";r.action=action;r.target=target;r.branchId=branch;r.reason=reason;r.occurredAt=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);r.beforeDetails=safe(before);r.afterDetails=safe(after);records.save(r);
 }
 private String safe(Object value){if(value==null)return null;JsonNode node=json.valueToTree(value);check(node);String text=node.toString();if(text.length()>12000)throw new IllegalArgumentException("Audit projection exceeds safe limit");return text;}
 private void check(JsonNode node){if(node.isObject()){var fields=node.fields();while(fields.hasNext()){var field=fields.next();String name=field.getKey().toLowerCase(Locale.ROOT);if(name.contains("password")||name.contains("token")||name.contains("credential")||name.contains("secret"))throw new IllegalArgumentException("Sensitive field cannot enter audit history");check(field.getValue());}}else if(node.isArray())node.forEach(this::check);}
 public record PageView(List<AuditRecord> rows,long total,int page,int pages){}
 @Transactional(readOnly=true) public PageView list(Long branch,String actor,String action,LocalDate from,LocalDate to,int page,int size){
  if(page<0||size<1||size>100)throw FinancialInputs.bad("Page must be >=0 and size 1..100");if(from!=null&&to!=null&&from.isAfter(to))throw FinancialInputs.bad("Invalid date range");
  var found=records.findAll((r,q,cb)->{var p=new ArrayList<jakarta.persistence.criteria.Predicate>();if(branch!=null)p.add(cb.equal(r.get("branchId"),branch));if(actor!=null&&!actor.isBlank())p.add(cb.equal(r.get("actor"),actor));if(action!=null&&!action.isBlank())p.add(cb.equal(r.get("action"),action));if(from!=null)p.add(cb.greaterThanOrEqualTo(r.get("occurredAt"),from.atStartOfDay(ZoneId.of("Asia/Colombo")).toInstant()));if(to!=null)p.add(cb.lessThan(r.get("occurredAt"),to.plusDays(1).atStartOfDay(ZoneId.of("Asia/Colombo")).toInstant()));return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));},PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"id")));
  return new PageView(found.getContent(),found.getTotalElements(),page,found.getTotalPages());
 }
}
