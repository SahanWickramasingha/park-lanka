package lk.sahan.parking.repo;

import lk.sahan.parking.domain.CashierAccount;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface CashierRepository extends JpaRepository<CashierAccount,Long> {
    Optional<CashierAccount> findByUsername(String username);
    long countByBranchId(Long branchId);
    long countByBranchIdAndDeletedFalse(Long branchId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CashierAccount c where c.id = :id")
    Optional<CashierAccount> lockById(@Param("id") Long id);
    @Query("select c.branch.id from CashierAccount c where c.id = :id")
    Optional<Long> branchIdFor(@Param("id") Long id);
    Page<CashierAccount> findByBranchId(Long branchId, Pageable pageable);
}
