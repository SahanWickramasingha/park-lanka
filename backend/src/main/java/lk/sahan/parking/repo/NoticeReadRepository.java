package lk.sahan.parking.repo;
import lk.sahan.parking.domain.NoticeRead;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface NoticeReadRepository extends JpaRepository<NoticeRead,Long> {
    Optional<NoticeRead> findByNoticeIdAndReaderKey(Long id,String key);
    java.util.List<NoticeRead> findByNoticeId(Long id);
}
