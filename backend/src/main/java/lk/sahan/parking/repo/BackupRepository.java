package lk.sahan.parking.repo;
import lk.sahan.parking.domain.BackupJob;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface BackupRepository extends JpaRepository<BackupJob,Long>{List<BackupJob> findByStatusOrderByIdDesc(String status);}
