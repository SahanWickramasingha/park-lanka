package lk.sahan.parking.repo;

import lk.sahan.parking.domain.Branch;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface BranchRepository extends JpaRepository<Branch,Long> {
    Optional<Branch> findByCode(String code);
    Optional<Branch> findByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCase(String name);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Branch b where b.id = :id")
    Optional<Branch> lockById(@Param("id") Long id);
}
