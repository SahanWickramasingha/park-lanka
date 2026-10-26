package lk.sahan.parking.repo;
import lk.sahan.parking.domain.ConversationRead;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface ConversationReadRepository extends JpaRepository<ConversationRead,Long> {
    Optional<ConversationRead> findByConversationIdAndReaderKey(Long id,String key);
    java.util.List<ConversationRead> findByConversationId(Long id);
}
