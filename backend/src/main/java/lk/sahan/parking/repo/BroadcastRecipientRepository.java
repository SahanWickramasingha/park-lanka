package lk.sahan.parking.repo;

import java.util.*;
import java.time.Instant;
import lk.sahan.parking.domain.BroadcastRecipient;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface BroadcastRecipientRepository extends JpaRepository<BroadcastRecipient, Long> {
    long countByBroadcastId(Long broadcastId);
    long countByBroadcastIdAndReadAtIsNotNull(Long broadcastId);
    List<BroadcastRecipient> findByBroadcastIdOrderByUsernameAsc(Long broadcastId);
    Optional<BroadcastRecipient> findByBroadcastIdAndRecipientKey(Long broadcastId, String key);
    @Query("select r from BroadcastRecipient r join fetch r.broadcast b where r.recipientKey = :key and r.branchId = :branchId and b.revokedAt is null and (b.expiresAt is null or b.expiresAt > :now) order by b.sentAt desc, b.id desc")
    List<BroadcastRecipient> active(@Param("key") String key, @Param("branchId") Long branchId, @Param("now") Instant now);
}
