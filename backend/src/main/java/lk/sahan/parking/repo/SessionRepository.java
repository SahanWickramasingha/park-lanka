package lk.sahan.parking.repo;

import lk.sahan.parking.domain.ParkingSession;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import java.util.*;

public interface SessionRepository extends JpaRepository<ParkingSession,Long>, JpaSpecificationExecutor<ParkingSession> {
    boolean existsByActivePlate(String activePlate);
    List<ParkingSession> findByExitedAtIsNullOrderByEnteredAtAsc();
    List<ParkingSession> findBySlotBranchIdAndExitedAtIsNullOrderByEnteredAtAsc(Long branchId);
    Page<ParkingSession> findBySlotBranchId(Long branchId, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ParkingSession s where s.id = :id") Optional<ParkingSession> lockById(@Param("id") Long id);
}
