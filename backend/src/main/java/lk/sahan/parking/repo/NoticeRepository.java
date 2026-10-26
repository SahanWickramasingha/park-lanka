package lk.sahan.parking.repo;
import lk.sahan.parking.domain.Notice;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
public interface NoticeRepository extends JpaRepository<Notice,Long>, JpaSpecificationExecutor<Notice> {
    boolean existsByEventKey(String key);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select n from Notice n where n.id=:id") Optional<Notice> lock(@Param("id") Long id);
}
