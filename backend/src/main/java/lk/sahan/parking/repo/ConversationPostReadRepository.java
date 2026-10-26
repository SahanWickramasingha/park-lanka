package lk.sahan.parking.repo;

import lk.sahan.parking.domain.ConversationPostRead;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ConversationPostReadRepository extends JpaRepository<ConversationPostRead,Long> {
    Optional<ConversationPostRead> findByPostIdAndReaderKey(Long post,String key);
    List<ConversationPostRead> findByPostIdOrderByReadAtAsc(Long post);
}
