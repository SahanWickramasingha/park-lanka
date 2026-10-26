package lk.sahan.parking.repo;
import lk.sahan.parking.domain.CashierShift;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
public interface ShiftRepository extends JpaRepository<CashierShift,Long>,JpaSpecificationExecutor<CashierShift>{
 Optional<CashierShift> findByActiveKey(String key);
 Optional<CashierShift> findByOpenRequestKey(String key);
 long countByClosedAtIsNull();
 java.util.List<CashierShift> findByBranchIdAndClosedAtIsNullOrderByIdDesc(Long branchId);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from CashierShift s where s.id=:id") Optional<CashierShift> lock(@Param("id") Long id);
}
