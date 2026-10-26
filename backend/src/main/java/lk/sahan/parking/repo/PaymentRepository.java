package lk.sahan.parking.repo;

import lk.sahan.parking.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.*;
import java.time.Instant;
import java.util.*;

public interface PaymentRepository extends JpaRepository<Payment,Long>, JpaSpecificationExecutor<Payment> {
    Optional<Payment> findBySessionId(Long sessionId);
    Optional<Payment> findByRequestKey(String requestKey);
    List<Payment> findByShiftId(Long shiftId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from Payment p where p.id=:id") Optional<Payment> lock(@org.springframework.data.repository.query.Param("id") Long id);
    List<Payment> findByPaidAtGreaterThanEqualAndPaidAtLessThan(Instant start, Instant end);
    List<Payment> findBySessionSlotBranchIdAndPaidAtGreaterThanEqualAndPaidAtLessThan(Long branchId, Instant start, Instant end);
    Page<Payment> findBySessionSlotBranchId(Long branchId, Pageable pageable);
    long countBySessionSlotBranchId(Long branchId);
}
