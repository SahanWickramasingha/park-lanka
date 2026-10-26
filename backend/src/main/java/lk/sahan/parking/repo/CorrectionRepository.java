package lk.sahan.parking.repo;
import lk.sahan.parking.domain.PaymentCorrection;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
import java.math.BigDecimal;
import java.time.Instant;
public interface CorrectionRepository extends JpaRepository<PaymentCorrection,Long>,JpaSpecificationExecutor<PaymentCorrection>{
 Optional<PaymentCorrection> findByRequestKey(String key);
 List<PaymentCorrection> findByPaymentIdOrderByOccurredAtAscIdAsc(Long paymentId);
 boolean existsByPaymentIdAndExternalReference(Long paymentId,String reference);
 @Query("select coalesce(sum(c.amount),0) from PaymentCorrection c where c.type=:type and c.movementShift.id=:shift") BigDecimal shiftTotal(@Param("shift") Long shift,@Param("type") String type);
 // Explicit flags avoid untyped nullable timestamp parameters in PostgreSQL.
 @Query("select coalesce(sum(c.amount),0) from PaymentCorrection c where c.type=:type and (:allBranches=true or c.branchId=:branch) and (:anyStart=true or c.occurredAt>=:start) and (:anyEnd=true or c.occurredAt<:end)")
 BigDecimal filteredPeriodTotal(@Param("type") String type,@Param("branch") Long branch,@Param("allBranches") boolean allBranches,
   @Param("start") Instant start,@Param("anyStart") boolean anyStart,@Param("end") Instant end,@Param("anyEnd") boolean anyEnd);
 default BigDecimal periodTotal(String type,Long branch,Instant start,Instant end){
  return filteredPeriodTotal(type,branch==null?0L:branch,branch==null,start==null?Instant.EPOCH:start,start==null,end==null?Instant.EPOCH:end,end==null);
 }
}
