package lk.sahan.parking.repo;
import lk.sahan.parking.domain.AuditRecord;
import org.springframework.data.jpa.repository.*;
public interface AuditRepository extends JpaRepository<AuditRecord,Long>,JpaSpecificationExecutor<AuditRecord>{}
