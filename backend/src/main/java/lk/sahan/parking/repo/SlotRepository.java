package lk.sahan.parking.repo;

import lk.sahan.parking.domain.ParkingSlot;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import java.util.*;

public interface SlotRepository extends JpaRepository<ParkingSlot,Long> {
    List<ParkingSlot> findByBranchId(Long branchId);
    Page<ParkingSlot> findByBranchId(Long branchId, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ParkingSlot s where s.id = :id") Optional<ParkingSlot> lockById(@Param("id") Long id);
}
