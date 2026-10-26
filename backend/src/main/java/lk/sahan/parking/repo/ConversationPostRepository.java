package lk.sahan.parking.repo;
import lk.sahan.parking.domain.ConversationPost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.*;
import java.util.Optional;
public interface ConversationPostRepository extends JpaRepository<ConversationPost,Long>,JpaSpecificationExecutor<ConversationPost> {
    Optional<ConversationPost> findByRequestKey(String key);
    Page<ConversationPost> findByConversationId(Long id,Pageable page);
    long countByConversationIdAndIdLessThan(Long conversation,Long post);
    Page<ConversationPost> findByConversationIdAndIdGreaterThanAndSenderAndBodyOrderByIdAsc(Long thread,Long after,String sender,String body,Pageable page);
}
