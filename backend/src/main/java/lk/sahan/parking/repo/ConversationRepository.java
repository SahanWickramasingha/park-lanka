package lk.sahan.parking.repo;
import lk.sahan.parking.domain.Conversation;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
public interface ConversationRepository extends JpaRepository<Conversation,Long>,JpaSpecificationExecutor<Conversation> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from Conversation c where c.id=:id") Optional<Conversation> lock(@Param("id") Long id);
}
