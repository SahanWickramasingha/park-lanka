package lk.sahan.parking.repo;

import java.util.*;
import jakarta.persistence.LockModeType;
import lk.sahan.parking.domain.Broadcast;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface BroadcastRepository extends JpaRepository<Broadcast, Long> {
    Optional<Broadcast> findByRequestKey(String key);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select b from Broadcast b where b.id = :id")
    Optional<Broadcast> lockById(@Param("id") Long id);
}
